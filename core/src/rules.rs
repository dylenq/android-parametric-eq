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

impl Inner {
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
        if let Some(k) = key {
            if self.find_preset(k).is_none() {
                return Err(EqError::NotFound);
            }
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
}
