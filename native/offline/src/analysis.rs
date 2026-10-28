//! Analysing a finished game with the bot, on the phone: every move that is not what the bot would have done.
//!
//! The game comes either as the website's export (`/export/<id>`: players, deck, actions) or as an
//! `offlineRecord` of a game played offline, which is turned into the same thing first.

use std::panic::{catch_unwind, AssertUnwindSafe};

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

/// Analyses [`game_json`] (an export or an offline record) for [`variant`] with [`convention`]. Returns
/// `{"players": [...], "comments": [{"turn", "player", "mistake", "text"}]}` as text.
pub fn analyze(game_json: &str, variant: &str, convention: &str) -> Result<String, String> {
    let parsed: Value = serde_json::from_str(game_json).map_err(|e| e.to_string())?;
    let export = if parsed["version"].is_number() { export_from_record(&parsed)? } else { parsed };
    let mut data = GameData::parse(&export)?;
    data.options.variant_name = variant.to_string();
    let variant = Variant::get(variant)?;
    let convention = ConventionKind::parse(convention)?;

    logger::set_default_level(LogLevel::Off);
    let comments = catch_unwind(AssertUnwindSafe(|| {
        let game = build_game(convention, &data, variant, 0, false)?;
        Ok::<_, String>(game.analyze_comments())
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
