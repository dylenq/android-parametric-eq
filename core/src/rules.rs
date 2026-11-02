//! Per-app rules: which apps the equalizer applies to and which preset each one uses. These are
//! listening settings like the limiter flag: saved, but not undoable and not part of history.

use serde::{Deserialize, Serialize};

use crate::error::EqError;
use crate::inner::Inner;
use crate::presets;

/// Most rules kept. Package names arrive from other apps, so the list must not grow with them.
pub const MAX_APP_RULES: usize = 64;
/// Longest package string accepted (Android's own limit is far lower).
pub const MAX_PKG_CHARS: usize = 255;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AppRule {
    pub pkg: String,
    pub enabled: bool,
    /// A preset key, or none for the live EQ.
    #[serde(default)]
    pub preset_key: Option<String>,
    /// A counter, not a clock: larger is more recent.
    pub last_seen: u64,
}

impl AppRule {
    /// A rule the user never changed. Only these may be evicted.
    fn is_default(&self) -> bool {
        self.enabled && self.preset_key.is_none()
    }
}

pub(crate) fn valid_pkg(pkg: &str) -> bool {
    !pkg.is_empty() && pkg.chars().count() <= MAX_PKG_CHARS
}

/// Keeps rules that are valid, unique and under the limit, and clears preset keys that no longer
/// resolve (the app falls back to the live EQ). Returns how many rules were removed or changed.
pub(crate) fn sanitize_rules(list: Vec<AppRule>, presets: &[presets::Preset]) -> (Vec<AppRule>, u32) {
    let mut out: Vec<AppRule> = Vec::new();
    let mut repairs: u32 = 0;
    for mut r in list {
        if !valid_pkg(&r.pkg) || out.iter().any(|o| o.pkg == r.pkg) || out.len() >= MAX_APP_RULES {
            repairs = repairs.saturating_add(1);
            continue;
        }
        if r.preset_key.as_deref().is_some_and(|k| presets::find_in(presets, k).is_none()) {
            r.preset_key = None;
            repairs = repairs.saturating_add(1);
        }
        out.push(r);
    }
    (out, repairs)
}

/// Most device rules kept. Device names come from the audio system, so the list is bounded.
pub const MAX_DEVICE_RULES: usize = 32;
/// Longest device name kept (Bluetooth names are set by the remote device).
pub const MAX_DEVICE_NAME_CHARS: usize = 100;

/// What to do when an output device connects. `preset_key` of `None` keeps the current sound.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct DeviceRule {
    pub key: String,
    pub name: String,
    #[serde(default)]
    pub preset_key: Option<String>,
    /// A counter, not a clock: larger is more recent.
    pub last_seen: u64,
}

impl Inner {
    pub fn note_device(&mut self, _key: &str, _name: &str) -> bool {
        false
    }

    pub fn set_device_preset(&mut self, _key: &str, _preset: Option<&str>) -> Result<(), EqError> {
        Ok(())
    }

    pub fn device_rules(&self) -> &[DeviceRule] {
        &[]
    }

    pub fn record_device(&mut self, _key: &str, _name: &str) -> Option<String> {
        None
    }

    fn next_tick(&self) -> u64 {
        self.app_rules.iter().map(|r| r.last_seen).max().map_or(1, |m| m.saturating_add(1))
    }

    /// Makes room for one more rule. False when every rule carries a user choice.
    fn make_room(&mut self) -> bool {
        if self.app_rules.len() < MAX_APP_RULES {
            return true;
        }
        let oldest = self
            .app_rules
            .iter()
            .enumerate()
            .filter(|(_, r)| r.is_default())
            .min_by_key(|(_, r)| r.last_seen)
            .map(|(i, _)| i);
        match oldest {
            Some(i) => {
                self.app_rules.remove(i);
                true
            }
            None => false,
        }
    }

    /// The rule for `pkg`, created (subject to the bound) when absent.
    fn rule_mut(&mut self, pkg: &str) -> Option<&mut AppRule> {
        if !valid_pkg(pkg) {
            return None;
        }
        if let Some(i) = self.app_rules.iter().position(|r| r.pkg == pkg) {
            return self.app_rules.get_mut(i);
        }
        if !self.make_room() {
            return None;
        }
        let last_seen = self.next_tick();
        self.app_rules.push(AppRule {
            pkg: pkg.to_string(),
            enabled: true,
            preset_key: None,
            last_seen,
        });
        self.app_rules.last_mut()
    }

    /// Records that an app has a session. True when a rule was added, false when it was already
    /// listed (that only refreshes its recency) or could not be kept.
    pub fn note_app(&mut self, pkg: &str) -> bool {
        if let Some(i) = self.app_rules.iter().position(|r| r.pkg == pkg) {
            let tick = self.next_tick();
            self.app_rules[i].last_seen = tick;
            return false;
        }
        self.rule_mut(pkg).is_some()
    }

    pub fn set_app_enabled(&mut self, pkg: &str, enabled: bool) {
        if let Some(r) = self.rule_mut(pkg) {
            r.enabled = enabled;
        }
    }

    /// Assigns a preset to an app, or `None` for the live EQ. A key that does not resolve is
    /// `NotFound` and changes nothing.
    pub fn set_app_preset(&mut self, pkg: &str, key: Option<&str>) -> Result<(), EqError> {
        if key.is_some_and(|k| self.find_preset(k).is_none()) {
            return Err(EqError::NotFound);
        }
        if let Some(r) = self.rule_mut(pkg) {
            r.preset_key = key.map(str::to_string);
        }
        Ok(())
    }

    pub fn app_rules(&self) -> &[AppRule] {
        &self.app_rules
    }

    /// Follows a preset rename (`new` is the new key) or delete (`None`: back to the live EQ).
    pub(crate) fn rekey_rules(&mut self, old: &str, new: Option<&str>) {
        for r in &mut self.app_rules {
            if r.preset_key.as_deref() == Some(old) {
                r.preset_key = new.map(str::to_string);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::persist;

    fn rule<'a>(s: &'a Inner, pkg: &str) -> Option<&'a AppRule> {
        s.app_rules().iter().find(|r| r.pkg == pkg)
    }

    #[test]
    fn note_app_adds_once_and_refresh_is_not_a_change() {
        let mut s = Inner::default();
        assert!(s.note_app("a.one"));
        assert!(s.note_app("a.two"));
        let first = rule(&s, "a.one").unwrap().last_seen;
        assert!(!s.note_app("a.one"));
        assert!(rule(&s, "a.one").unwrap().last_seen > first);
        let r = rule(&s, "a.two").unwrap();
        assert!(r.enabled && r.preset_key.is_none());
        assert_eq!(s.app_rules().len(), 2);
    }

    #[test]
    fn overflow_evicts_the_oldest_default_rule() {
        let mut s = Inner::default();
        for i in 0..MAX_APP_RULES {
            assert!(s.note_app(&format!("app.{i}")));
        }
        assert!(s.note_app("app.new"));
        assert_eq!(s.app_rules().len(), MAX_APP_RULES);
        assert!(rule(&s, "app.0").is_none());
        assert!(rule(&s, "app.1").is_some());
        assert!(rule(&s, "app.new").is_some());
    }

    #[test]
    fn changed_rules_are_never_evicted() {
        let mut s = Inner::default();
        s.set_app_enabled("keep.off", false);
        s.set_app_preset("keep.preset", Some("builtin:Bass")).unwrap();
        for i in 0..MAX_APP_RULES {
            s.note_app(&format!("app.{i}"));
        }
        assert!(rule(&s, "keep.off").is_some());
        assert!(rule(&s, "keep.preset").is_some());
    }

    #[test]
    fn spoof_flood_keeps_what_the_user_changed() {
        let mut s = Inner::default();
        s.set_app_enabled("mine.a", false);
        s.set_app_enabled("mine.b", false);
        s.set_app_preset("mine.c", Some("builtin:Bass")).unwrap();
        for i in 0..1000 {
            s.note_app(&format!("spoof.{i}"));
            assert!(s.app_rules().len() <= MAX_APP_RULES);
        }
        for p in ["mine.a", "mine.b", "mine.c"] {
            assert!(rule(&s, p).is_some(), "{p} was evicted");
        }
    }

    #[test]
    fn a_full_list_of_user_rules_drops_the_new_app() {
        let mut s = Inner::default();
        for i in 0..MAX_APP_RULES {
            s.set_app_enabled(&format!("user.{i}"), false);
        }
        assert!(!s.note_app("late.app"));
        s.set_app_enabled("late.two", false);
        assert!(rule(&s, "late.app").is_none() && rule(&s, "late.two").is_none());
        assert_eq!(s.app_rules().len(), MAX_APP_RULES);
    }

    #[test]
    fn setters_create_unknown_apps_and_reject_unknown_keys() {
        let mut s = Inner::default();
        s.set_app_enabled("x.y", false);
        assert!(!rule(&s, "x.y").unwrap().enabled);
        assert_eq!(s.set_app_preset("p.q", Some("user:Nope")), Err(EqError::NotFound));
        assert!(rule(&s, "p.q").is_none());
        s.set_app_preset("x.y", Some("builtin:Bass")).unwrap();
        assert_eq!(rule(&s, "x.y").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
        s.set_app_preset("x.y", None).unwrap();
        assert!(rule(&s, "x.y").unwrap().preset_key.is_none());
        assert_eq!(s.set_app_preset("x.y", Some("user:Nope")), Err(EqError::NotFound));
    }

    #[test]
    fn package_strings_are_length_limited() {
        let mut s = Inner::default();
        assert!(!s.note_app(""));
        assert!(!s.note_app(&"a".repeat(MAX_PKG_CHARS + 1)));
        assert!(s.note_app(&"a".repeat(MAX_PKG_CHARS)));
        assert_eq!(s.app_rules().len(), 1);
    }

    #[test]
    fn rules_are_not_undoable_and_do_not_mark_modified() {
        let mut s = Inner::default();
        s.apply_preset("builtin:Bass").unwrap();
        let undo = s.can_undo();
        s.note_app("a.b");
        s.set_app_enabled("a.b", false);
        s.set_app_preset("a.b", Some("builtin:Bass")).unwrap();
        assert_eq!(s.can_undo(), undo);
        assert!(!s.can_redo());
        assert!(!s.is_modified());
        s.undo();
        assert!(rule(&s, "a.b").is_some());
    }

    fn roundtrip(s: &Inner) -> Inner {
        let bytes = serde_json::to_vec(&persist::PersistedState::from_inner(s)).unwrap();
        persist::parse(&bytes).unwrap()
    }

    #[test]
    fn rules_survive_save_and_load() {
        let mut s = Inner::default();
        s.note_app("a.one");
        s.set_app_enabled("a.two", false);
        s.save_preset("Mine").unwrap();
        s.set_app_preset("a.three", Some("user:Mine")).unwrap();
        let l = roundtrip(&s);
        assert_eq!(l.app_rules(), s.app_rules());
        assert_eq!(l.load_repairs, 0);
        // The counter keeps counting from where it stopped.
        let mut l = l;
        let before = l.app_rules().iter().map(|r| r.last_seen).max().unwrap();
        l.note_app("a.four");
        assert!(rule(&l, "a.four").unwrap().last_seen > before);
    }

    #[test]
    fn an_old_file_loads_with_no_rules_and_no_repair() {
        let l = persist::parse(br#"{"version":1}"#).unwrap();
        assert!(l.app_rules().is_empty());
        assert_eq!(l.load_repairs, 0);
        assert_eq!(persist::SCHEMA_VERSION, 1);
    }

    #[test]
    fn a_malformed_rule_costs_only_itself() {
        let json = r#"{"version":1,"app_rules":[
            {"pkg":"good.one","enabled":true,"preset_key":null,"last_seen":1},
            {"pkg":7,"enabled":true},
            {"pkg":"good.two","enabled":false,"preset_key":null,"last_seen":2},
            "junk"]}"#;
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.app_rules().len(), 2);
        assert_eq!(l.load_repairs, 2);
        assert!(rule(&l, "good.two").is_some());
    }

    #[test]
    fn a_rules_entry_of_the_wrong_type_is_no_rules_and_counted() {
        let l = persist::parse(br#"{"version":1,"app_rules":5}"#).unwrap();
        assert!(l.app_rules().is_empty());
        assert_eq!(l.load_repairs, 1);
    }

    #[test]
    fn rules_over_the_limit_in_a_file_are_cut_and_counted() {
        let items: Vec<String> = (0..70)
            .map(|i| format!(r#"{{"pkg":"p.{i}","enabled":true,"preset_key":null,"last_seen":{i}}}"#))
            .collect();
        let json = format!(r#"{{"version":1,"app_rules":[{}]}}"#, items.join(","));
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.app_rules().len(), MAX_APP_RULES);
        assert_eq!(l.load_repairs, 6);
    }

    #[test]
    fn a_huge_rules_list_counts_every_rule_it_cuts() {
        let items: Vec<String> = (0..200)
            .map(|i| format!(r#"{{"pkg":"p.{i}","enabled":true,"preset_key":null,"last_seen":{i}}}"#))
            .collect();
        let json = format!(r#"{{"version":1,"app_rules":[{}]}}"#, items.join(","));
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.app_rules().len(), MAX_APP_RULES);
        assert_eq!(l.load_repairs, 200 - MAX_APP_RULES as u32);
    }

    #[test]
    fn bad_packages_and_stale_keys_are_repaired_on_load() {
        let json = r#"{"version":1,"app_rules":[
            {"pkg":"","enabled":true,"preset_key":null,"last_seen":1},
            {"pkg":"dup","enabled":true,"preset_key":null,"last_seen":2},
            {"pkg":"dup","enabled":false,"preset_key":null,"last_seen":3},
            {"pkg":"stale","enabled":false,"preset_key":"user:Gone","last_seen":4},
            {"pkg":"built","enabled":true,"preset_key":"builtin:Bass","last_seen":5}]}"#;
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.app_rules().len(), 3);
        assert_eq!(l.load_repairs, 3);
        let stale = rule(&l, "stale").unwrap();
        assert!(!stale.enabled && stale.preset_key.is_none());
        assert_eq!(rule(&l, "built").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
    }

    #[test]
    fn rename_keeps_assignments() {
        let mut s = Inner::default();
        s.save_preset("Mine").unwrap();
        s.set_app_preset("a.one", Some("user:Mine")).unwrap();
        s.set_app_preset("a.two", Some("builtin:Bass")).unwrap();
        let undo = s.can_undo();
        let k = s.rename_preset("user:Mine", "Yours").unwrap();
        assert_eq!(k, "user:Yours");
        assert_eq!(rule(&s, "a.one").unwrap().preset_key.as_deref(), Some("user:Yours"));
        assert_eq!(rule(&s, "a.two").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
        assert_eq!(s.can_undo(), undo);
    }

    #[test]
    fn delete_returns_apps_to_the_live_eq() {
        let mut s = Inner::default();
        s.save_preset("Mine").unwrap();
        s.set_app_preset("a.one", Some("user:Mine")).unwrap();
        s.set_app_enabled("a.one", false);
        s.delete_preset("user:Mine").unwrap();
        let r = rule(&s, "a.one").unwrap();
        assert!(r.preset_key.is_none());
        assert!(!r.enabled);
    }

    #[test]
    fn a_failed_rename_leaves_rules_alone() {
        let mut s = Inner::default();
        s.save_preset("Mine").unwrap();
        s.save_preset("Other").unwrap();
        s.set_app_preset("a.one", Some("user:Mine")).unwrap();
        assert_eq!(s.rename_preset("user:Mine", "Other"), Err(EqError::NameTaken));
        assert_eq!(s.delete_preset("builtin:Bass"), Err(EqError::ReadOnly));
        assert_eq!(rule(&s, "a.one").unwrap().preset_key.as_deref(), Some("user:Mine"));
    }

    #[test]
    fn preset_params_match_a_real_apply() {
        let mut s = Inner::default();
        s.set_band(0, crate::model::Band { gain_db: 7.0, ..s.parametric[0] }).unwrap();
        s.set_balance(0.4).unwrap();
        s.save_preset("Mine").unwrap();
        s.reset_bands();
        s.set_balance(0.0).unwrap();
        s.limiter_enabled = false;
        s.bypass = true;
        s.engine_band_cap = Some(12);
        for key in ["user:Mine", "builtin:Bass", "builtin:Vocal", "builtin:Flat"] {
            let got = s.engine_params_for_preset(key, 48_000, 85.3).unwrap();
            let mut real = Inner {
                presets: s.presets.clone(),
                limiter_enabled: s.limiter_enabled,
                bypass: s.bypass,
                engine_band_cap: s.engine_band_cap,
                ..Inner::default()
            };
            real.apply_preset(key).unwrap();
            let want = real.engine_params(48_000, 85.3).unwrap();
            assert_eq!(got.gains_db, want.gains_db, "{key}");
            assert_eq!(got.cutoffs_hz, want.cutoffs_hz, "{key}");
            assert_eq!(got.input_gain_db, want.input_gain_db, "{key}");
            assert_eq!(got.input_gain_left_db, want.input_gain_left_db, "{key}");
            assert_eq!(got.input_gain_right_db, want.input_gain_right_db, "{key}");
            assert_eq!(got.layout_id, want.layout_id, "{key}");
            assert!(!got.limiter_enabled && got.bypass);
        }
    }

    #[test]
    fn preset_params_leave_live_state_alone() {
        let mut s = Inner::default();
        s.apply_preset("builtin:Treble").unwrap();
        s.set_preamp(-3.0).unwrap();
        let before = (s.edit_snapshot(), s.active_preset.clone(), s.can_undo(), s.can_redo());
        let live = s.engine_params(48_000, 85.3).unwrap().gains_db;
        s.engine_params_for_preset("builtin:Bass", 48_000, 85.3).unwrap();
        assert_eq!(before, (s.edit_snapshot(), s.active_preset.clone(), s.can_undo(), s.can_redo()));
        assert_eq!(live, s.engine_params(48_000, 85.3).unwrap().gains_db);
        assert!(matches!(
            s.engine_params_for_preset("user:Nope", 48_000, 85.3),
            Err(EqError::NotFound)
        ));
    }

    fn dev<'a>(s: &'a Inner, key: &str) -> Option<&'a DeviceRule> {
        s.device_rules().iter().find(|r| r.key == key)
    }

    #[test]
    fn note_device_adds_once_and_refresh_is_not_a_change() {
        let mut s = Inner::default();
        assert!(s.note_device("speaker", "Phone speaker"));
        assert!(s.note_device("usb:dac", "USB: DAC"));
        let first = dev(&s, "speaker").unwrap().last_seen;
        assert!(!s.note_device("speaker", "Phone speaker"));
        assert!(dev(&s, "speaker").unwrap().last_seen > first);
        assert_eq!(s.device_rules().len(), 2);
        assert!(dev(&s, "usb:dac").unwrap().preset_key.is_none());
    }

    #[test]
    fn device_overflow_evicts_the_oldest_default_rule() {
        let mut s = Inner::default();
        s.set_device_preset("keep", Some("builtin:Bass")).unwrap();
        for i in 0..MAX_DEVICE_RULES - 1 {
            assert!(s.note_device(&format!("dev.{i}"), "D"));
        }
        assert!(s.note_device("dev.new", "D"));
        assert_eq!(s.device_rules().len(), MAX_DEVICE_RULES);
        assert!(dev(&s, "keep").is_some());
        assert!(dev(&s, "dev.0").is_none());
        assert!(dev(&s, "dev.1").is_some());
        assert!(dev(&s, "dev.new").is_some());
    }

    #[test]
    fn a_full_list_of_device_presets_drops_the_new_device() {
        let mut s = Inner::default();
        for i in 0..MAX_DEVICE_RULES {
            s.set_device_preset(&format!("dev.{i}"), Some("builtin:Bass")).unwrap();
        }
        assert!(!s.note_device("late", "Late"));
        assert_eq!(s.record_device("late", "Late"), None);
        assert!(dev(&s, "late").is_none());
        assert_eq!(s.device_rules().len(), MAX_DEVICE_RULES);
    }

    #[test]
    fn device_preset_setter_creates_clears_and_rejects_unknown_keys() {
        let mut s = Inner::default();
        assert_eq!(s.set_device_preset("a", Some("user:Nope")), Err(EqError::NotFound));
        assert!(dev(&s, "a").is_none());
        s.set_device_preset("a", Some("builtin:Bass")).unwrap();
        assert_eq!(dev(&s, "a").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
        assert_eq!(s.set_device_preset("a", Some("user:Nope")), Err(EqError::NotFound));
        assert_eq!(dev(&s, "a").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
        s.set_device_preset("a", None).unwrap();
        assert!(dev(&s, "a").unwrap().preset_key.is_none());
    }

    #[test]
    fn record_device_returns_a_preset_only_when_the_device_changed() {
        let mut s = Inner::default();
        assert_eq!(s.record_device("a", "A"), None);
        assert_eq!(s.last_device.as_deref(), Some("a"));
        s.set_device_preset("a", Some("builtin:Bass")).unwrap();
        // Same device as last time (a restart): nothing to apply.
        assert_eq!(s.record_device("a", "A"), None);
        s.record_device("b", "B");
        assert_eq!(s.record_device("a", "A").as_deref(), Some("builtin:Bass"));
        assert_eq!(s.record_device("a", "A"), None);
    }

    #[test]
    fn a_device_without_a_preset_keeps_the_sound_but_updates_last_device() {
        let mut s = Inner::default();
        s.set_device_preset("a", Some("builtin:Bass")).unwrap();
        s.record_device("a", "A");
        assert_eq!(s.record_device("plain", "Plain"), None);
        assert_eq!(s.last_device.as_deref(), Some("plain"));
        assert!(dev(&s, "plain").is_some());
    }

    #[test]
    fn device_names_and_keys_are_length_limited() {
        let mut s = Inner::default();
        assert!(!s.note_device("", "x"));
        assert!(!s.note_device(&"k".repeat(MAX_PKG_CHARS + 1), "x"));
        assert!(s.note_device("k", &"n".repeat(MAX_DEVICE_NAME_CHARS + 50)));
        assert_eq!(dev(&s, "k").unwrap().name.chars().count(), MAX_DEVICE_NAME_CHARS);
    }

    #[test]
    fn preset_rename_and_delete_follow_device_assignments() {
        let mut s = Inner::default();
        s.save_preset("Mine").unwrap();
        s.set_device_preset("a", Some("user:Mine")).unwrap();
        let new_key = s.rename_preset("user:Mine", "Theirs").unwrap();
        assert_eq!(dev(&s, "a").unwrap().preset_key.as_deref(), Some(new_key.as_str()));
        s.delete_preset(&new_key).unwrap();
        assert!(dev(&s, "a").unwrap().preset_key.is_none());
    }

    #[test]
    fn device_rules_survive_save_and_load() {
        let mut s = Inner::default();
        s.save_preset("Mine").unwrap();
        s.note_device("speaker", "Phone speaker");
        s.set_device_preset("bt:Buds", Some("user:Mine")).unwrap();
        s.record_device("bt:Buds", "Bluetooth: Buds");
        let l = roundtrip(&s);
        assert_eq!(l.device_rules(), s.device_rules());
        assert_eq!(l.last_device.as_deref(), Some("bt:Buds"));
        assert_eq!(l.load_repairs, 0);
    }

    #[test]
    fn an_old_file_loads_with_no_devices_and_no_repair() {
        let l = persist::parse(br#"{"version":1,"app_rules":[]}"#).unwrap();
        assert!(l.device_rules().is_empty());
        assert!(l.last_device.is_none());
        assert_eq!(l.load_repairs, 0);
    }

    #[test]
    fn a_malformed_device_costs_only_itself() {
        let json = r#"{"version":1,"last_device":"speaker","device_rules":[
            {"key":"speaker","name":"Phone speaker","preset_key":null,"last_seen":1},
            {"key":7,"name":"x"},
            "junk",
            {"key":"usb","name":"USB","preset_key":null,"last_seen":2}]}"#;
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.device_rules().len(), 2);
        assert_eq!(l.load_repairs, 2);
        assert_eq!(l.last_device.as_deref(), Some("speaker"));
    }

    #[test]
    fn a_device_list_of_the_wrong_type_is_no_devices_and_counted() {
        let l = persist::parse(br#"{"version":1,"device_rules":5,"last_device":3}"#).unwrap();
        assert!(l.device_rules().is_empty());
        assert!(l.last_device.is_none());
        assert_eq!(l.load_repairs, 2);
    }

    #[test]
    fn devices_over_the_limit_in_a_file_are_cut_and_counted() {
        let items: Vec<String> = (0..40)
            .map(|i| format!(r#"{{"key":"d.{i}","name":"D","preset_key":null,"last_seen":{i}}}"#))
            .collect();
        let json = format!(r#"{{"version":1,"device_rules":[{}]}}"#, items.join(","));
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.device_rules().len(), MAX_DEVICE_RULES);
        assert_eq!(l.load_repairs, 8);
        let items: Vec<String> = (0..200)
            .map(|i| format!(r#"{{"key":"d.{i}","name":"D","preset_key":null,"last_seen":{i}}}"#))
            .collect();
        let json = format!(r#"{{"version":1,"device_rules":[{}]}}"#, items.join(","));
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.device_rules().len(), MAX_DEVICE_RULES);
        assert_eq!(l.load_repairs, 200 - MAX_DEVICE_RULES as u32);
    }

    #[test]
    fn bad_device_strings_and_stale_keys_are_repaired_on_load() {
        let long_key = "k".repeat(MAX_PKG_CHARS + 1);
        let long_name = "n".repeat(MAX_DEVICE_NAME_CHARS + 1);
        let json = format!(
            r#"{{"version":1,"last_device":"","device_rules":[
            {{"key":"","name":"Empty","preset_key":null,"last_seen":1}},
            {{"key":"{long_key}","name":"Long key","preset_key":null,"last_seen":2}},
            {{"key":"dup","name":"One","preset_key":null,"last_seen":3}},
            {{"key":"dup","name":"Two","preset_key":null,"last_seen":4}},
            {{"key":"name","name":"{long_name}","preset_key":null,"last_seen":5}},
            {{"key":"stale","name":"Stale","preset_key":"user:Gone","last_seen":6}},
            {{"key":"built","name":"Built","preset_key":"builtin:Bass","last_seen":7}}]}}"#
        );
        let l = persist::parse(json.as_bytes()).unwrap();
        assert_eq!(l.device_rules().len(), 4);
        // Empty key, long key, duplicate, long name, stale preset, empty last_device.
        assert_eq!(l.load_repairs, 6);
        assert!(l.last_device.is_none());
        assert_eq!(dev(&l, "name").unwrap().name.chars().count(), MAX_DEVICE_NAME_CHARS);
        assert!(dev(&l, "stale").unwrap().preset_key.is_none());
        assert_eq!(dev(&l, "built").unwrap().preset_key.as_deref(), Some("builtin:Bass"));
    }

    #[test]
    fn device_rules_are_not_undoable_and_do_not_mark_modified() {
        let mut s = Inner::default();
        s.apply_preset("builtin:Bass").unwrap();
        let undo = s.can_undo();
        s.note_device("a", "A");
        s.set_device_preset("a", Some("builtin:Bass")).unwrap();
        s.record_device("a", "A");
        assert_eq!(s.can_undo(), undo);
        assert!(!s.can_redo());
        assert!(!s.is_modified());
        s.undo();
        assert!(dev(&s, "a").is_some());
    }
}
