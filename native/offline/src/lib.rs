//! JNI surface of the offline table: start a game, send the app's commands, poll for the table's messages.
mod table;

use std::collections::HashMap;
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use hanab_bot::basics::Variant;
use hanab_bot::logger::{self, LogLevel};
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};

use table::{Config, Event, Outbox};

struct Running {
    tx: Sender<Event>,
    out: Arc<Outbox>,
}

fn tables() -> &'static Mutex<HashMap<i64, Running>> {
    static TABLES: OnceLock<Mutex<HashMap<i64, Running>>> = OnceLock::new();
    TABLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn start(variants: &str, suits: &str, config: &str) -> Result<i64, String> {
    logger::set_default_level(LogLevel::Off);
    let c: Value = serde_json::from_str(config).map_err(|e| e.to_string())?;
    Variant::init(variants, suits)?;
    let cfg = Config {
        variant: c["variant"].as_str().unwrap_or("No Variant").to_string(),
        players: c["players"].as_u64().unwrap_or(3) as usize,
        convention: c["convention"].as_str().unwrap_or("HGroup5").to_string(),
        seed: c["seed"].as_u64().unwrap_or(1),
        human_name: c["humanName"].as_str().unwrap_or("You").to_string(),
        pace_ms: c["paceMs"].as_u64().unwrap_or(900),
    };
    if !(2..=4).contains(&cfg.players) {
        return Err("Offline games are for 2 to 4 players.".into());
    }
    // Reject a bad variant or convention now rather than on the table's own thread.
    Variant::get(&cfg.variant)?;
    table::convention(&cfg.convention)?;

    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let mut next = NEXT.get_or_init(|| Mutex::new(0)).lock().unwrap();
    *next += 1;
    let handle = *next;

    let (tx, rx) = channel();
    let out = Arc::new(Outbox::new());
    table::spawn(cfg, rx, Arc::clone(&out));
    tables().lock().unwrap().insert(handle, Running { tx, out });
    Ok(handle)
}

fn text(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|j| j.into()).unwrap_or_default()
}

fn to_java(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Starts a table. Returns `{"handle": n}` or `{"error": "..."}`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_start<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    variants_json: JString<'l>,
    suits_json: JString<'l>,
    config_json: JString<'l>,
) -> jstring {
    let (v, s, c) = (text(&mut env, &variants_json), text(&mut env, &suits_json), text(&mut env, &config_json));
    let result = std::panic::catch_unwind(|| start(&v, &s, &c)).unwrap_or_else(|_| Err("panic".into()));
    let reply = match result {
        Ok(handle) => json!({"handle": handle}),
        Err(e) => json!({"error": e}),
    };
    to_java(&mut env, &reply.to_string())
}

/// Starts a replay of a saved game (the record the table sent when it ended). Returns `{"handle": n}` or
/// `{"error": "..."}`.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_startReplay<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    record_json: JString<'l>,
) -> jstring {
    let record = text(&mut env, &record_json);
    let reply = match serde_json::from_str::<Value>(&record) {
        Ok(record) if record["actions"].is_array() => {
            static NEXT_REPLAY: OnceLock<Mutex<i64>> = OnceLock::new();
            let mut next = NEXT_REPLAY.get_or_init(|| Mutex::new(1 << 40)).lock().unwrap();
            *next += 1;
            let handle = *next;
            let (tx, rx) = channel();
            let out = Arc::new(Outbox::new());
            table::spawn_replay(record, rx, Arc::clone(&out));
            tables().lock().unwrap().insert(handle, Running { tx, out });
            json!({"handle": handle})
        }
        Ok(_) => json!({"error": "That saved game has no moves."}),
        Err(e) => json!({"error": e.to_string()}),
    };
    to_java(&mut env, &reply.to_string())
}

/// Passes one command of the app to the table.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_send<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    command: JString<'l>,
    data_json: JString<'l>,
) {
    let (command, data) = (text(&mut env, &command), text(&mut env, &data_json));
    let data = serde_json::from_str(&data).unwrap_or(Value::Null);
    if let Some(t) = tables().lock().unwrap().get(&handle) {
        let _ = t.tx.send(Event::Human(command, data));
    }
}

/// The next message of the table (`command {json}`), waiting up to `timeout_ms`. Null if there is none yet;
/// the empty string once the table has finished and everything has been read.
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_poll<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    timeout_ms: jlong,
) -> jstring {
    let out = match tables().lock().unwrap().get(&handle) {
        Some(t) => Arc::clone(&t.out),
        None => return to_java(&mut env, ""),
    };
    match out.pop(Duration::from_millis(timeout_ms.max(0) as u64)) {
        Some(msg) => to_java(&mut env, &msg),
        None if out.closed.load(std::sync::atomic::Ordering::SeqCst) => to_java(&mut env, ""),
        None => std::ptr::null_mut(),
    }
}

/// Stops the table (a game in progress is abandoned).
#[no_mangle]
pub extern "system" fn Java_live_hanab_client_NativeOffline_close<'l>(_env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    if let Some(t) = tables().lock().unwrap().remove(&handle) {
        let _ = t.tx.send(Event::Close);
        t.out.close();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VARIANTS: &str = include_str!("../../../app/src/main/assets/variants.json");
    const SUITS: &str = include_str!("../../../app/src/main/assets/suits.json");


    fn send(handle: i64, command: &str, data: Value) {
        tables().lock().unwrap().get(&handle).unwrap().tx.send(Event::Human(command.into(), data)).unwrap();
    }

    /// Plays the app's part: the hand-shake, then a simple human who discards (or clues at 8 clues).
    /// Returns an error instead of panicking, so a sweep can report every failing variant.
    fn play_scripted(variant: &str, players: usize, seed: u64, check_rules: bool) -> Result<Value, String> {
        let config = format!(r#"{{"variant":{variant:?},"players":{players},"convention":"HGroup1","seed":{seed},"paceMs":0}}"#);
        let h = start(VARIANTS, SUITS, &config)?;
        let result = std::panic::catch_unwind(|| scripted_game(h, players, check_rules)).unwrap_or_else(|_| Err("panic".into()));
        let _ = tables().lock().unwrap().remove(&h);
        result
    }

    fn scripted_game(h: i64, players: usize, check_rules: bool) -> Result<Value, String> {
        let wait = |what: &str| -> Result<Value, String> {
            let out = tables().lock().unwrap().get(&h).map(|t| Arc::clone(&t.out)).ok_or("gone")?;
            let msg = out.pop(Duration::from_secs(60)).ok_or(format!("went quiet waiting for {what}"))?;
            let (command, data) = msg.split_once(' ').unwrap_or((&msg, "null"));
            Ok(json!({"command": command, "data": serde_json::from_str::<Value>(data).unwrap_or(Value::Null)}))
        };
        let expect = |what: &str, command: &str| -> Result<Value, String> {
            let m = wait(what)?;
            if m["command"] == command { Ok(m) } else { Err(format!("expected {command}, got {}", m["command"])) }
        };
        expect("tableStart", "tableStart")?;
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let init = expect("init", "init")?;
        if init["data"]["playerNames"].as_array().map_or(0, Vec::len) != players {
            return Err("wrong number of players".into());
        }
        // The human sits at a random seat; seat 0 starts, so sometimes a bot goes first.
        let me = init["data"]["ourPlayerIndex"].as_i64().ok_or("no seat")?;
        let other = (me + 1) % players as i64;
        send(h, "getGameInfo2", json!({"tableID": 1}));
        let list = expect("gameActionList", "gameActionList")?;
        let actions = list["data"]["list"].as_array().ok_or("no list")?.clone();
        let hand = if players <= 3 { 5 } else { 4 };
        if actions.len() != players * hand + 2 {
            return Err(format!("{} dealt actions", actions.len()));
        }
        let mut mine: Vec<i64> = actions
            .iter()
            .filter(|a| a["type"] == "draw" && a["playerIndex"] == me)
            .map(|a| a["order"].as_i64().unwrap())
            .collect();
        let mut clues = 8;
        send(h, "loaded", json!({"tableID": 1}));

        // Rules are enforced: no discarding at 8 clues, and no clue to yourself. Probed on the first own turn.
        let mut probe = check_rules;
        let probe_rules = |mine: &Vec<i64>, clues: i64| -> Result<(), String> {
            if clues == 8 {
                send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
                expect("a warning", "warning")?;
            }
            send(h, "action", json!({"tableID": 1, "type": 2, "target": me, "value": 0}));
            expect("a warning", "warning")?;
            Ok(())
        };

        let mut value = 1;
        let mut moves = 0;
        // If the human starts, that turn came in the action list, not as a live message.
        if me == 0 {
            if probe {
                probe_rules(&mine, clues)?;
                probe = false;
            }
            send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
        }
        loop {
            let m = wait("the next message")?;
            match m["command"].as_str().unwrap() {
                "warning" => {
                    // That clue touched nothing: try the next rank.
                    value = value % 5 + 1;
                    send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
                }
                "offlineRecord" => return Ok(m["data"].clone()),
                "gameAction" => {
                    let a = &m["data"]["action"];
                    match a["type"].as_str().unwrap() {
                        "status" => clues = a["clues"].as_i64().unwrap(),
                        "draw" if a["playerIndex"] == me => mine.push(a["order"].as_i64().unwrap()),
                        "play" | "discard" if a["playerIndex"] == me => mine.retain(|&o| o != a["order"].as_i64().unwrap()),
                        "gameOver" => {}
                        "turn" if a["currentPlayerIndex"] == me => {
                            moves += 1;
                            if probe {
                                probe_rules(&mine, clues)?;
                                probe = false;
                            }
                            if mine.is_empty() {
                                return Err("the human has no cards but it is their turn".into());
                            }
                            if clues == 8 {
                                send(h, "action", json!({"tableID": 1, "type": 3, "target": other, "value": value}));
                            } else {
                                send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
                            }
                        }
                        _ => {}
                    }
                }
                _ => {}
            }
            if moves > 300 {
                return Err("the game did not end".into());
            }
        }
    }

    #[test]
    fn plays_a_whole_game_with_bots() {
        let record = play_scripted("No Variant", 3, 5, true).unwrap();
        assert_eq!(record["variant"], "No Variant");
        assert_eq!(record["names"].as_array().unwrap().len(), 3);
        assert!(record["endCondition"] == 1 || record["endCondition"] == 2);
        let actions = record["actions"].as_array().unwrap();
        assert!(actions.len() > 17);
        assert_eq!(actions.last().unwrap()["currentPlayerIndex"], -1);
        // A replay shows every card, including the ones the human was dealt.
        assert!(actions.iter().filter(|a| a["type"] == "draw").all(|a| a["suitIndex"] != -1));

        // ...and the saved game plays back through the same hand-shake as a real replay.
        let (tx, rx) = channel();
        let out = Arc::new(Outbox::new());
        table::spawn_replay(record.clone(), rx, Arc::clone(&out));
        let next = || out.pop(Duration::from_secs(10)).expect("the replay went quiet");
        assert!(next().starts_with("tableStart"));
        tx.send(Event::Human("getGameInfo1".into(), Value::Null)).unwrap();
        let init = next();
        assert!(init.starts_with("init") && init.contains("\"spectating\":true"));
        tx.send(Event::Human("getGameInfo2".into(), Value::Null)).unwrap();
        let list: Value = serde_json::from_str(next().strip_prefix("gameActionList ").unwrap()).unwrap();
        assert_eq!(list["list"].as_array().unwrap().len(), actions.len());
        tx.send(Event::Close).unwrap();
    }

    /// Every variant the app offers offline, with 2 to 4 players. Slow; run with `--ignored`.
    #[test]
    #[ignore]
    fn plays_every_offered_variant() {
        let all: Vec<Value> = serde_json::from_str(VARIANTS).unwrap();
        let suits: Vec<Value> = serde_json::from_str(SUITS).unwrap();
        let known: Vec<&str> = suits.iter().map(|s| s["name"].as_str().unwrap()).collect();
        let unusable: Vec<&str> = suits
            .iter()
            .filter(|s| s["prism"] == true || s["inverted"] == true)
            .map(|s| s["name"].as_str().unwrap())
            .collect();
        let bot_no = [
            "Blind", "Mute", "Ambiguous", "Dual-Color", "Synesthesia", "Mix", "Funnels", "Chimneys", "Matryoshka",
            "Alternating", "Odds", "Cow", "Duck", "Reversed", "Throw", "Up or Down", "Sudoku",
        ];
        let mut failures = Vec::new();
        let mut played = 0;
        for v in &all {
            let name = v["name"].as_str().unwrap();
            let plain = v.as_object().unwrap().keys().all(|k| ["id", "newID", "name", "suits"].contains(&k.as_str()));
            let suits_ok = v["suits"].as_array().unwrap().iter().all(|s| {
                let s = s.as_str().unwrap();
                known.contains(&s) && !unusable.contains(&s)
            });
            if !plain || !suits_ok || bot_no.iter().any(|b| name.contains(b)) {
                continue;
            }
            let players = 2 + played % 3;
            played += 1;
            if let Err(e) = play_scripted(name, players, played as u64, false) {
                failures.push(format!("{name} ({players}p): {e}"));
            }
        }
        eprintln!("played {played} variants");
        assert!(failures.is_empty(), "{} failures:
{}", failures.len(), failures.join("
"));
    }
}
