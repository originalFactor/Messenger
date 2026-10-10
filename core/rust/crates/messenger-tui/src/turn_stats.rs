/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Per-turn statistics: what a finished turn cost, how long it took, and which
//! agent/model answered it.
//!
//! These are what the transcript's turn header and "Worked for …" line are
//! made of. They live in the store's kv table rather than in a message column,
//! for three reasons:
//!
//! * **The transcript is a pure function of the store.** The renderer rebuilds
//!   every visible row each frame and hands settled rows to the terminal's
//!   scrollback exactly once (see [`crate::screen`]). Rows that change after
//!   they were written would be wrong forever, so a stat line must be resolved
//!   *before* the turn's rows can scroll away — i.e. at turn end, keyed by the
//!   message it belongs to.
//! * **No schema change and no cloud sync.** `messenger-store` is shared with
//!   the phone/desktop/web clients and mirrored to the cloud; a
//!   TUI-only display detail has no business in any of that. kv is local by
//!   definition.
//! * **The numbers already exist** — they are just not persisted anywhere.
//!
//! A turn with no entry simply renders without a header or a stats line: an
//! older conversation, or a turn this client did not observe, has no honest
//! numbers to show, and inventing zeros would be worse than showing nothing.

use std::collections::{HashMap, VecDeque};
use std::time::Duration;

use messenger_store::Store;
use serde::{Deserialize, Serialize};

/// The kv key holding the ledger (a JSON array of records).
pub const TURN_STATS_KEY: &str = "tui_turn_stats";

/// How many finished turns are kept. Enough that scrolling back through a long
/// session still shows the stats; small enough that the value never becomes a
/// meaningful share of the kv table.
pub const MAX_TURN_STATS: usize = 200;

/// One finished turn's statistics, keyed by the final assistant message.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TurnStats {
    /// The final text row of the turn — the line the stats describe.
    pub message_id: String,
    /// Who answered, as shown in the turn header. Empty when unknown.
    #[serde(default)]
    pub agent: String,
    #[serde(default)]
    pub model: String,
    /// The reasoning effort the request carried, if any.
    #[serde(default)]
    pub effort: Option<String>,
    #[serde(default)]
    pub prompt_tokens: i64,
    #[serde(default)]
    pub completion_tokens: i64,
    /// How much of `prompt_tokens` the provider served from its prompt cache.
    #[serde(default)]
    pub cached_tokens: i64,
    #[serde(default)]
    pub duration_ms: i64,
    /// Quota units this turn was billed at (see [`crate::turn_stats::cost_units`]).
    /// `None` when the model declares no rates, i.e. the turn is not billable
    /// at a known price.
    #[serde(default)]
    pub cost_units: Option<i64>,
}

impl TurnStats {
    /// The turn's total tokens (prompt + completion).
    pub fn total_tokens(&self) -> i64 {
        self.prompt_tokens + self.completion_tokens
    }

    /// The turn header: `Agent - Model (effort)`, degrading to whichever parts
    /// are known. Empty when nothing is.
    pub fn header(&self) -> String {
        let model = match &self.effort {
            Some(effort) if !effort.is_empty() => format!("{} ({effort})", self.model),
            _ => self.model.clone(),
        };
        match (self.agent.is_empty(), model.is_empty()) {
            (false, false) => format!("{} - {}", self.agent, model),
            (false, true) => self.agent.clone(),
            (true, false) => model,
            (true, true) => String::new(),
        }
    }

    /// The line under the reply, e.g.
    /// `Worked for 1s. Consumed 12.3k (9.1k cached) input / 431 output tokens.`
    ///
    /// The cached parenthetical is omitted when the provider reports no cache
    /// breakdown, rather than printed as `(0 cached)`.
    pub fn summary_line(&self) -> String {
        let cached = if self.cached_tokens > 0 {
            format!(" ({} cached)", format_tokens(self.cached_tokens))
        } else {
            String::new()
        };
        let cost = match self.cost_units {
            Some(units) => format!(" · cost {}", format_units(units)),
            None => String::new(),
        };
        format!(
            "Worked for {}. Consumed {}{} input / {} output tokens{}.",
            format_duration(self.duration_ms),
            format_tokens(self.prompt_tokens),
            cached,
            format_tokens(self.completion_tokens),
            cost
        )
    }
}

/// The turn ledger: records by message id, plus the order they were recorded.
///
/// Lookup is by message id (the transcript asks once per rendered row), while
/// eviction needs the recording order — which a map keyed by UUID cannot
/// express, since sorting the ids would drop arbitrary turns. So the order is
/// kept alongside, and the whole ledger is rewritten on each record: the kv
/// table has no partial-update story, and 200 small records are far cheaper to
/// rewrite than to query.
#[derive(Debug, Default)]
pub struct TurnStatsLedger {
    entries: HashMap<String, TurnStats>,
    order: VecDeque<String>,
}

impl TurnStatsLedger {
    /// Load the ledger from the store, dropping anything unreadable.
    ///
    /// A malformed value must never take the client down: the stats are
    /// decoration, so a decode failure degrades to an empty ledger.
    pub fn load(store: &Store) -> Self {
        let records = store
            .kv_get(TURN_STATS_KEY)
            .ok()
            .flatten()
            .and_then(|raw| serde_json::from_str::<Vec<TurnStats>>(&raw).ok())
            .unwrap_or_default();
        let mut ledger = Self::default();
        for record in records {
            if record.message_id.is_empty() {
                continue;
            }
            // A duplicate id keeps its FIRST position: the array is already in
            // recording order, so that is the order the entry really had.
            if !ledger.entries.contains_key(&record.message_id) {
                ledger.order.push_back(record.message_id.clone());
            }
            ledger.entries.insert(record.message_id.clone(), record);
        }
        ledger
    }

    pub fn get(&self, message_id: &str) -> Option<&TurnStats> {
        self.entries.get(message_id)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Record a finished turn and persist the ledger.
    pub fn record(&mut self, stats: TurnStats, store: &Store) {
        if stats.message_id.is_empty() {
            return;
        }
        if !self.entries.contains_key(&stats.message_id) {
            self.order.push_back(stats.message_id.clone());
        }
        self.entries.insert(stats.message_id.clone(), stats);
        self.trim();
        self.save(store);
    }

    /// Keep only the most recent [`MAX_TURN_STATS`] records, oldest first.
    fn trim(&mut self) {
        while self.order.len() > MAX_TURN_STATS {
            if let Some(oldest) = self.order.pop_front() {
                self.entries.remove(&oldest);
            }
        }
    }

    fn save(&self, store: &Store) {
        let records: Vec<&TurnStats> = self
            .order
            .iter()
            .filter_map(|id| self.entries.get(id))
            .collect();
        if let Ok(raw) = serde_json::to_string(&records) {
            let _ = store.kv_set(TURN_STATS_KEY, &raw);
        }
    }
}

/// Quota units a turn was billed at, matching the server's formula exactly:
/// `max(1, ceil(prompt × inputRate + completion × outputRate))`
/// (`server/lib/quota.ts`). `None` when the model declares no rates at all —
/// a BYOK provider has no rate metadata, so there is nothing to compute.
///
/// The unit is a QUOTA unit, not a currency: rates are multipliers against the
/// baseline model's output price, so the server's unit cancels the baseline out
/// of the ratio. Labelling it `$` would be a fabricated number.
pub fn cost_units(
    prompt_tokens: i64,
    completion_tokens: i64,
    input_rate: Option<f64>,
    output_rate: Option<f64>,
) -> Option<i64> {
    let rate = |value: Option<f64>| match value {
        Some(value) if value.is_finite() && value > 0.0 => value,
        _ => 0.0,
    };
    let (input_rate, output_rate) = (rate(input_rate), rate(output_rate));
    // Both rates zero or missing: the server treats this as uncompiled / free.
    if input_rate <= 0.0 && output_rate <= 0.0 {
        return None;
    }
    let prompt = prompt_tokens.max(0) as f64;
    let completion = completion_tokens.max(0) as f64;
    let cost = (prompt * input_rate + completion * output_rate).ceil();
    Some(cost.max(1.0) as i64)
}

/// `1234` → `1.2k`, `1000000` → `1M`, `1500000` → `1.5M`.
pub fn format_tokens(tokens: i64) -> String {
    let tokens = tokens.max(0);
    if tokens >= 1_000_000 {
        let millions = tokens as f64 / 1_000_000.0;
        if (tokens % 1_000_000) == 0 {
            format!("{}M", tokens / 1_000_000)
        } else {
            format!("{millions:.1}M")
        }
    } else if tokens >= 1_000 {
        let thousands = tokens as f64 / 1_000.0;
        if (tokens % 1_000) == 0 {
            format!("{}k", tokens / 1_000)
        } else {
            format!("{thousands:.1}k")
        }
    } else {
        tokens.to_string()
    }
}

/// The same scales as [`format_tokens`], for the cost chip.
pub fn format_units(units: i64) -> String {
    format_tokens(units)
}

/// `1000` → `1s`, `72_000` → `1m 12s`, `400` → `0.4s` (via the millisecond
/// range so a fast turn does not read as `0s`).
pub fn format_duration(ms: i64) -> String {
    let ms = ms.max(0);
    if ms < 1_000 {
        let seconds = ms as f64 / 1_000.0;
        return format!("{seconds:.1}s");
    }
    let total_seconds = ms / 1_000;
    if total_seconds < 60 {
        return format!("{total_seconds}s");
    }
    let minutes = total_seconds / 60;
    let seconds = total_seconds % 60;
    if minutes < 60 {
        return format!("{minutes}m {seconds}s");
    }
    let hours = minutes / 60;
    format!("{hours}h {}m", minutes % 60)
}

/// Convert a measured [`Duration`] into whole milliseconds.
pub fn duration_ms(duration: Duration) -> i64 {
    i64::try_from(duration.as_millis()).unwrap_or(i64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stats() -> TurnStats {
        TurnStats {
            message_id: "m1".into(),
            agent: "Default Agent".into(),
            model: "DeepSeek V4.1 Flash".into(),
            effort: Some("high".into()),
            prompt_tokens: 12_300,
            completion_tokens: 431,
            cached_tokens: 9_100,
            duration_ms: 1_000,
            cost_units: None,
        }
    }

    #[test]
    fn the_header_reads_agent_dash_model_and_effort() {
        assert_eq!(stats().header(), "Default Agent - DeepSeek V4.1 Flash (high)");
    }

    #[test]
    fn the_header_degrades_to_whatever_is_known() {
        let mut partial = stats();
        partial.agent = String::new();
        assert_eq!(partial.header(), "DeepSeek V4.1 Flash (high)");
        partial.effort = None;
        assert_eq!(partial.header(), "DeepSeek V4.1 Flash");
        partial.model = String::new();
        assert_eq!(partial.header(), "", "nothing known, nothing shown");
    }

    #[test]
    fn the_summary_line_matches_the_specified_shape() {
        assert_eq!(
            stats().summary_line(),
            "Worked for 1s. Consumed 12.3k (9.1k cached) input / 431 output tokens."
        );
    }

    #[test]
    fn the_summary_line_omits_an_absent_cache_breakdown() {
        let mut plain = stats();
        plain.cached_tokens = 0;
        assert_eq!(
            plain.summary_line(),
            "Worked for 1s. Consumed 12.3k input / 431 output tokens."
        );
    }

    #[test]
    fn the_summary_line_carries_the_cost_when_the_model_has_rates() {
        let mut billed = stats();
        billed.cost_units = Some(4_200);
        assert!(billed.summary_line().ends_with("· cost 4.2k."));
    }

    #[test]
    fn cost_matches_the_server_formula() {
        // 100 × 1.0 + 50 × 2.0 = 200.
        assert_eq!(cost_units(100, 50, Some(1.0), Some(2.0)), Some(200));
        // Ceil, and floored at 1 so a tiny turn still bills something.
        assert_eq!(cost_units(1, 0, Some(0.1), Some(0.1)), Some(1));
        // No rates at all: not billable at a known price.
        assert_eq!(cost_units(100, 50, None, None), None);
        // Zero rates mean "free / unmetered" on the server.
        assert_eq!(cost_units(100, 50, Some(0.0), Some(0.0)), None);
        // A missing rate is zero, not a reason to refuse the other one.
        assert_eq!(cost_units(100, 0, Some(1.0), None), Some(100));
    }

    #[test]
    fn tokens_use_k_and_m_scales() {
        assert_eq!(format_tokens(0), "0");
        assert_eq!(format_tokens(431), "431");
        assert_eq!(format_tokens(1_000), "1k");
        assert_eq!(format_tokens(12_300), "12.3k");
        assert_eq!(format_tokens(1_000_000), "1M");
        assert_eq!(format_tokens(1_500_000), "1.5M");
    }

    #[test]
    fn durations_read_as_seconds_minutes_and_hours() {
        assert_eq!(format_duration(400), "0.4s");
        assert_eq!(format_duration(1_000), "1s");
        assert_eq!(format_duration(59_000), "59s");
        assert_eq!(format_duration(72_000), "1m 12s");
        assert_eq!(format_duration(3_600_000), "1h 0m");
    }

    #[test]
    fn the_ledger_round_trips_through_the_store() {
        let store = Store::open_memory().unwrap();
        let mut ledger = TurnStatsLedger::load(&store);
        assert!(ledger.is_empty());
        ledger.record(stats(), &store);

        let reloaded = TurnStatsLedger::load(&store);
        assert_eq!(
            reloaded.get("m1").map(TurnStats::summary_line),
            Some(stats().summary_line())
        );
    }

    #[test]
    fn a_corrupt_ledger_degrades_to_empty_rather_than_failing() {
        let store = Store::open_memory().unwrap();
        store.kv_set(TURN_STATS_KEY, "{not json").unwrap();
        assert!(TurnStatsLedger::load(&store).is_empty());
    }

    #[test]
    fn the_ledger_caps_itself() {
        let store = Store::open_memory().unwrap();
        let mut ledger = TurnStatsLedger::load(&store);
        for index in 0..(MAX_TURN_STATS + 25) {
            let mut entry = stats();
            entry.message_id = format!("m{index:04}");
            ledger.record(entry, &store);
        }
        assert_eq!(ledger.entries.len(), MAX_TURN_STATS);
        // The oldest are the ones dropped.
        assert!(ledger.get("m0000").is_none());
        assert!(ledger.get(&format!("m{:04}", MAX_TURN_STATS + 24)).is_some());
    }
}
