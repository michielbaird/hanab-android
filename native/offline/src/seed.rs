//! Shuffles a deck exactly as hanab.live does, so the same seed gives the same deal.
//!
//! The site names a seed like `p3v0s50` (players, variant id, then a suffix), turns that string into a number
//! with CRC-64, seeds Go's `math/rand` with it and shuffles the deck by drawing `Intn(i + 1)` for each card
//! (server/src/misc.go, game_deck.go). Go's generator is an additive lagged-Fibonacci one (rng.go there), ported
//! below with the table it is seeded from (after Go's src/math/rand/rng.go, Copyright 2009 The Go Authors, BSD-style
//! license: https://go.dev/LICENSE); the tests check it against decks of real games from the site.

use crate::rng_cooked::RNG_COOKED;

const LEN: usize = 607;
const TAP: usize = 273;
const MASK: u64 = (1 << 63) - 1;
const INT32_MAX: i64 = (1 << 31) - 1;

/// CRC-64/XZ (the ECMA polynomial, as Go's `crc64.MakeTable(crc64.ECMA)` computes it) of `data`.
fn crc64(data: &[u8]) -> u64 {
    let mut crc = !0u64;
    for &b in data {
        crc ^= b as u64;
        for _ in 0..8 {
            crc = if crc & 1 == 1 { (crc >> 1) ^ 0xC96C_5795_D787_0F42 } else { crc >> 1 };
        }
    }
    !crc
}

/// The number the site seeds its random generator with for a seed string.
fn seed_to_i64(seed: &str) -> i64 {
    // Seeds of old games start with "legacy-<n>-", which does not count.
    let seed = if seed.starts_with("legacy-") { &seed[("legacy-x-").len().min(seed.len())..] } else { seed };
    crc64(seed.as_bytes()) as i64
}

fn seedrand(x: i32) -> i32 {
    const A: i32 = 48271;
    const Q: i32 = 44488;
    const R: i32 = 3399;
    let hi = x / Q;
    let lo = x % Q;
    let x = A.wrapping_mul(lo).wrapping_sub(R.wrapping_mul(hi));
    if x < 0 { x.wrapping_add(INT32_MAX as i32) } else { x }
}

/// Go's `math/rand` default source (rngSource).
struct GoRng {
    vec: [i64; LEN],
    tap: usize,
    feed: usize,
}

impl GoRng {
    fn new(seed: i64) -> Self {
        let mut seed = seed % INT32_MAX; // Go's % keeps the sign of the left side, like Rust's
        if seed < 0 {
            seed += INT32_MAX;
        }
        if seed == 0 {
            seed = 89482311;
        }
        let mut x = seed as i32;
        let mut vec = [0i64; LEN];
        for i in -20i32..LEN as i32 {
            x = seedrand(x);
            if i >= 0 {
                let mut u = (x as i64) << 40;
                x = seedrand(x);
                u ^= (x as i64) << 20;
                x = seedrand(x);
                u ^= x as i64;
                u ^= RNG_COOKED[i as usize];
                vec[i as usize] = u;
            }
        }
        Self { vec, tap: 0, feed: LEN - TAP }
    }

    fn uint64(&mut self) -> u64 {
        self.tap = if self.tap == 0 { LEN - 1 } else { self.tap - 1 };
        self.feed = if self.feed == 0 { LEN - 1 } else { self.feed - 1 };
        let x = self.vec[self.feed].wrapping_add(self.vec[self.tap]);
        self.vec[self.feed] = x;
        x as u64
    }

    fn int31(&mut self) -> i32 {
        ((self.uint64() & MASK) >> 32) as i32
    }

    /// Go's `Intn` for small `n`: uniform in 0..n.
    fn intn(&mut self, n: i32) -> i32 {
        if n & (n - 1) == 0 {
            return self.int31() & (n - 1);
        }
        let max = ((1i64 << 31) - 1 - (1i64 << 31) % n as i64) as i32;
        let mut v = self.int31();
        while v > max {
            v = self.int31();
        }
        v % n
    }
}

/// A number made from a seed name, to seed the table's own choices (where you sit, the bots' names).
pub fn numeric(seed: &str) -> u64 {
    seed_to_i64(seed) as u64
}

/// Splits a whole seed like `p3v0s258` into its players, variant id and suffix (`258`); None if it is not one.
pub fn split_name(seed: &str) -> Option<(usize, u32, &str)> {
    let rest = seed.strip_prefix('p')?;
    let (players, rest) = rest.split_once('v')?;
    let (variant, suffix) = rest.split_once('s')?;
    if suffix.is_empty() {
        return None;
    }
    Some((players.parse().ok()?, variant.parse().ok()?, suffix))
}

/// The name the site gives a deal: `p{players}v{variant id}s{suffix}`, e.g. `p3v0s50`.
pub fn seed_name(players: usize, variant_id: u32, suffix: &str) -> String {
    format!("p{players}v{variant_id}s{suffix}")
}

/// Shuffles `deck` for `seed`, then the seating, as the site does one after the other from the same seeded
/// generator. Returns who sits where: `seats[i]` is the join order (0 is whoever made the table) of the player
/// in seat `i`; seat 0 goes first.
pub fn deal<T>(deck: &mut [T], players: usize, seed: &str) -> Vec<usize> {
    let mut rng = GoRng::new(seed_to_i64(seed));
    for i in 0..deck.len() {
        let j = rng.intn(i as i32 + 1) as usize;
        deck.swap(i, j);
    }
    let mut seats: Vec<usize> = (0..players).collect();
    for i in 0..players {
        let j = rng.intn(i as i32 + 1) as usize;
        seats.swap(i, j);
    }
    seats
}

/// The suffix of the next game of a seeded series, as the site does on a rematch: the trailing digits go up by
/// one ("abc1" is followed by "abc2", keeping leading zeros), and a suffix without digits gets a "1".
pub fn next_suffix(suffix: &str) -> String {
    let digits = suffix.chars().rev().take_while(char::is_ascii_digit).count();
    let (base, number) = suffix.split_at(suffix.len() - digits);
    if number.is_empty() {
        return format!("{suffix}1");
    }
    let next = number.parse::<u128>().map_or_else(|_| number.to_string(), |n| (n + 1).to_string());
    format!("{base}{next:0>width$}", width = number.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use hanab_bot::basics::Variant;
    use hanab_bot::tools::self_play::full_deck;
    use serde_json::Value;

    const VARIANTS: &str = include_str!("../../../app/src/main/assets/variants.json");
    const SUITS: &str = include_str!("../../../app/src/main/assets/suits.json");
    /// Real games from hanab.live: the seed, the variant and the deck the site dealt (see tests/data).
    const GAMES: &str = include_str!("../tests/data/seeded_games.json");

    #[test]
    fn crc64_matches_the_standard_check_value() {
        // CRC-64/XZ of "123456789" is the published check value 0x995dc9bbdf1939fa.
        assert_eq!(crc64(b"123456789"), 0x995d_c9bb_df19_39fa);
    }

    #[test]
    fn reproduces_the_decks_of_real_games() {
        crate::init_variants(VARIANTS, SUITS).unwrap();
        let games: Vec<Value> = serde_json::from_str(GAMES).unwrap();
        let (mut checked, mut skipped) = (0, Vec::new());
        for g in &games {
            let name = g["variant"].as_str().unwrap();
            let Ok(variant) = Variant::get(name) else {
                skipped.push(name.to_string());
                continue;
            };
            let mut deck = full_deck(&variant);
            deal(&mut deck, g["players"].as_u64().unwrap() as usize, g["seed"].as_str().unwrap());
            let want: Vec<(u64, u64)> =
                g["deck"].as_array().unwrap().iter().map(|c| (c[0].as_u64().unwrap(), c[1].as_u64().unwrap())).collect();
            let got: Vec<(u64, u64)> = deck.iter().map(|id| (id.suit_index as u64, id.rank as u64)).collect();
            // Variants with a different mix of cards (Reversed, Up or Down, ...) are not ones the bots play.
            let (mut a, mut b) = (got.clone(), want.clone());
            a.sort_unstable();
            b.sort_unstable();
            if a != b {
                skipped.push(name.to_string());
                continue;
            }
            assert_eq!(got, want, "{name} with seed {}", g["seed"]);
            checked += 1;
        }
        eprintln!("checked {checked} real decks, skipped {skipped:?}");
        assert!(checked >= 15, "only {checked} decks were checked");
    }

    /// Where the creator of a table sat in real games of the site (50-card decks), from the site's exports.
    #[test]
    fn seats_the_table_creator_where_the_site_did() {
        for (seed, players, seat) in [("p3v0s258", 3, 0), ("p3v0s107", 3, 2), ("p3v0s110", 3, 1), ("p4v0s90", 4, 3), ("p5v0s73", 5, 1)] {
            let mut deck = vec![0u8; 50];
            let seats = deal(&mut deck, players, seed);
            assert_eq!(seats.iter().position(|&p| p == 0), Some(seat), "{seed}");
        }
    }

    #[test]
    fn rematch_seeds_count_up_like_the_site() {
        assert_eq!(next_suffix("7"), "8");
        assert_eq!(next_suffix("abc1"), "abc2");
        assert_eq!(next_suffix("abc"), "abc1");
        assert_eq!(next_suffix("x009"), "x010");
        assert_eq!(next_suffix("99"), "100");
    }

    #[test]
    fn a_whole_seed_splits_into_its_parts() {
        assert_eq!(split_name("p3v0s258"), Some((3, 0, "258")));
        assert_eq!(split_name("p2v1465ssalt-mine-7"), Some((2, 1465, "salt-mine-7")));
        assert_eq!(split_name("258"), None);
        assert_eq!(split_name("p3v0s"), None);
        assert_eq!(split_name("salt-mine-7"), None);
    }

    #[test]
    fn seed_names_look_like_the_sites() {
        assert_eq!(seed_name(3, 0, "50"), "p3v0s50");
        assert_eq!(seed_name(2, 1465, "salt-mine-7"), "p2v1465ssalt-mine-7");
    }
}

