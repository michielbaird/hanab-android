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

    fn next(handle: i64) -> Value {
        let out = tables().lock().unwrap().get(&handle).map(|t| Arc::clone(&t.out)).unwrap();
        let msg = out.pop(Duration::from_secs(30)).expect("the table went quiet");
        let (command, data) = msg.split_once(' ').unwrap_or((&msg, "null"));
        json!({"command": command, "data": serde_json::from_str::<Value>(data).unwrap_or(Value::Null)})
    }

    fn send(handle: i64, command: &str, data: Value) {
        tables().lock().unwrap().get(&handle).unwrap().tx.send(Event::Human(command.into(), data)).unwrap();
    }

    /// Plays the app's part: the hand-shake, then a simple human who discards (or clues at 8 clues).
    #[test]
    fn plays_a_whole_game_with_bots() {
        let h = start(VARIANTS, SUITS, r#"{"variant":"No Variant","players":3,"convention":"HGroup5","seed":5,"paceMs":0}"#).unwrap();
        assert_eq!(next(h)["command"], "tableStart");
        send(h, "getGameInfo1", json!({"tableID": 1}));
        let init = next(h);
        assert_eq!(init["command"], "init");
        assert_eq!(init["data"]["playerNames"].as_array().unwrap().len(), 3);
        send(h, "getGameInfo2", json!({"tableID": 1}));
        let list = next(h);
        assert_eq!(list["command"], "gameActionList");
        let actions = list["data"]["list"].as_array().unwrap();
        // 3 hands of 5, then status and turn
        assert_eq!(actions.len(), 15 + 2);
        let mut mine: Vec<i64> = actions
            .iter()
            .filter(|a| a["type"] == "draw" && a["playerIndex"] == 0)
            .map(|a| a["order"].as_i64().unwrap())
            .collect();
        assert!(actions.iter().filter(|a| a["type"] == "draw" && a["playerIndex"] == 0).all(|a| a["suitIndex"] == -1));
        let mut clues = 8;
        send(h, "loaded", json!({"tableID": 1}));

        // Rules are enforced: no discarding at 8 clues, and no clue to yourself.
        send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
        let w = next(h);
        assert_eq!(w["command"], "warning");
        send(h, "action", json!({"tableID": 1, "type": 2, "target": 0, "value": 0}));
        assert_eq!(next(h)["command"], "warning");

        let mut value = 1;
        let mut over = false;
        let mut moves = 0;
        // The first turn is the human's: it came in the action list, not as a live message.
        send(h, "action", json!({"tableID": 1, "type": 3, "target": 1, "value": value}));
        while !over {
            let m = next(h);
            match m["command"].as_str().unwrap() {
                "warning" => {
                    // That clue touched nothing: try the next rank.
                    value = value % 5 + 1;
                    send(h, "action", json!({"tableID": 1, "type": 3, "target": 1, "value": value}));
                }
                "gameAction" => {
                    let a = &m["data"]["action"];
                    match a["type"].as_str().unwrap() {
                        "status" => clues = a["clues"].as_i64().unwrap(),
                        "draw" if a["playerIndex"] == 0 => mine.push(a["order"].as_i64().unwrap()),
                        "play" | "discard" if a["playerIndex"] == 0 => mine.retain(|&o| o != a["order"].as_i64().unwrap()),
                        "gameOver" => over = true,
                        "turn" if a["currentPlayerIndex"] == 0 => {
                            moves += 1;
                            if clues == 8 {
                                send(h, "action", json!({"tableID": 1, "type": 3, "target": 1, "value": value}));
                            } else {
                                send(h, "action", json!({"tableID": 1, "type": 1, "target": mine[0], "value": 0}));
                            }
                        }
                        _ => {}
                    }
                }
                _ => {}
            }
            if moves > 200 {
                panic!("the game did not end");
            }
        }
        assert!(over);
        send(h, "tableUnattend", json!({"tableID": 1}));
        let _ = tables().lock().unwrap().remove(&h);
    }
}
