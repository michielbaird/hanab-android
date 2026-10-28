//! Analysing a finished game with the bot, on the phone: every move that is not what the bot would have done.
//!
//! The game comes either as the website's export (`/export/<id>`: players, deck, actions) or as an
//! `offlineRecord` of a game played offline, which is turned into the same thing first.
//!
//! The bot replays each player on a thread of its own and reports how many moves it has looked at, and an analysis
//! can be cancelled between moves. [`Job`] is what the app starts, polls and cancels.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use hanab_bot::basics::game::{AnalysisOptions, AnalysisProgress};
use hanab_bot::basics::Variant;
use hanab_bot::logger::{self, LogLevel};
use hanab_bot::settings::ConventionKind;
use hanab_bot::tools::game_data::{build_game, GameData};
use serde_json::{json, Value};

/// The export-style game (`players`, `deck`, `actions`) of an offline record, whose actions are the events the app
/// was sent: draws give the deck, and plays, discards and clues are the moves.
pub fn export_from_record(record: &Value) -> Result<Value, String> {
    let events = record["actions"].as_array().ok_or("the saved game has no moves")?;
    let mut deck: Vec<Value> = Vec::new();
    let mut moves: Vec<Value> = Vec::new();
    for e in events {
        match e["type"].as_str() {
            Some("draw") => {
                let order = e["order"].as_u64().ok_or("a draw without an order")? as usize;
                if deck.len() <= order {
                    deck.resize(order + 1, Value::Null);
                }
                deck[order] = json!({"suitIndex": e["suitIndex"], "rank": e["rank"]});
            }
            Some("play") => moves.push(json!({"type": 0, "target": e["order"], "value": 0})),
            // A discard that failed was a play of a card that could not be played.
            Some("discard") if e["failed"] == true => moves.push(json!({"type": 0, "target": e["order"], "value": 0})),
            Some("discard") => moves.push(json!({"type": 1, "target": e["order"], "value": 0})),
            Some("clue") => {
                let kind = e["clue"]["type"].as_u64().ok_or("a clue without a type")?;
                moves.push(json!({"type": 2 + kind, "target": e["target"], "value": e["clue"]["value"]}));
            }
            _ => {}
        }
    }
    if deck.iter().any(Value::is_null) {
        return Err("the saved game does not show every card".into());
    }
    Ok(json!({"players": record["names"], "deck": deck, "actions": moves}))
}

/// How far a running analysis has got, and the flag that stops it. Shared between the thread doing the work and the
/// app asking about it.
#[derive(Default)]
pub struct Job {
    pub moves_done: AtomicUsize,
    pub moves_total: AtomicUsize,
    pub cancel: AtomicBool,
    /// The finished analysis as text (`{"players", "comments"}` or `{"error"}`), once there is one.
    pub result: Mutex<Option<String>>,
}

/// Analyses [`game_json`] (an export or an offline record) for [`variant`] with [`convention`], reporting into `job`.
/// Returns `{"players": [...], "comments": [{"turn", "player", "mistake", "text"}]}` as text.
pub fn analyze(game_json: &str, variant: &str, convention: &str, job: &Job) -> Result<String, String> {
    let parsed: Value = serde_json::from_str(game_json).map_err(|e| e.to_string())?;
    let export = if parsed["version"].is_number() { export_from_record(&parsed)? } else { parsed };
    let mut data = GameData::parse(&export)?;
    data.options.variant_name = variant.to_string();
    let variant = Variant::get(variant)?;
    let convention = ConventionKind::parse(convention)?;

    logger::set_default_level(LogLevel::Off);
    let report = |p: AnalysisProgress| {
        job.moves_total.store(p.moves_total, Ordering::Relaxed);
        // Players are replayed side by side, so a report can arrive out of order; only ever go forward.
        job.moves_done.fetch_max(p.moves_done, Ordering::Relaxed);
    };
    let options = AnalysisOptions { threads: 0, progress: Some(&report), cancel: Some(&job.cancel) };
    let comments = catch_unwind(AssertUnwindSafe(|| {
        let game = build_game(convention, &data, variant, 0, false)?;
        game.analyze_comments_with(&options).map_err(|_| "The analysis was cancelled.".to_string())
    }))
    .map_err(|_| "The bot could not follow this game.".to_string())??;

    Ok(json!({
        "players": data.players,
        "comments": comments.iter().map(|c| json!({
            "turn": c.turn, "player": c.player_index, "mistake": c.mistake, "text": c.text,
        })).collect::<Vec<_>>(),
    })
    .to_string())
}

/// Runs an analysis to the end on the calling thread (used by tests).
#[cfg(test)]
pub fn analyze_blocking(game_json: &str, variant: &str, convention: &str) -> Result<String, String> {
    analyze(game_json, variant, convention, &Job::default())
}

/// Starts [`analyze`] on a thread of its own; the answer lands in [`Job::result`].
pub fn spawn(job: Arc<Job>, game_json: String, variant: String, convention: String) {
    std::thread::Builder::new()
        .name("offline-analysis".into())
        .stack_size(16 * 1024 * 1024)
        .spawn(move || {
            let text = analyze(&game_json, &variant, &convention, &job).unwrap_or_else(|e| json!({"error": e}).to_string());
            *job.result.lock().unwrap() = Some(text);
        })
        .expect("could not start the analysis thread");
}
