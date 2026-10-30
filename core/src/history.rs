// Origin: macOS EQ app src/history.rs (stacks, pending step, LIMIT), commit 03be7aa, GPL-3.0.
// Reworked for Android: an explicit begin_edit/end_edit gesture API replaces the per-frame
// `observe(before, after, gesture_active)`, and snapshots replace whole-state copies.

use crate::model::Band;

const LIMIT: usize = 100;

/// What undo and redo record: parametric bands, graphic band count and gains, preamp, tone (bass, treble) and balance.
/// Mode, bypass, the limiter flag and the frame duration are listening settings, not edits,
/// so they are neither recorded nor restored.
#[derive(Clone, Debug, PartialEq)]
pub struct EditSnapshot {
    pub bands: Vec<Band>,
    pub band_count: u32,
    pub gains_db: Vec<f32>,
    pub preamp_db: f32,
    pub bass_db: f32,
    pub treble_db: f32,
    pub balance: f32,
}

#[derive(Default)]
pub struct History {
    undo: Vec<EditSnapshot>,
    redo: Vec<EditSnapshot>,
    /// The snapshot from before the gesture in progress.
    pending: Option<EditSnapshot>,
    /// The latest `after` seen inside the gesture, to drop a drag that ended where it began.
    last_after: Option<EditSnapshot>,
    gesture: bool,
}

impl History {
    /// Opens a gesture (a held drag). Idempotent.
    pub fn begin_edit(&mut self) {
        self.gesture = true;
    }

    /// Closes the gesture; everything recorded since `begin_edit` becomes one undo step.
    pub fn end_edit(&mut self) {
        self.gesture = false;
        self.commit_pending();
    }

    /// Records an edit from `before` to `after`. Inside a gesture the first `before` is kept
    /// and the step is committed by `end_edit`; outside one it is a step of its own.
    pub fn record(&mut self, before: EditSnapshot, after: &EditSnapshot) {
        if before == *after {
            return;
        }
        if self.gesture {
            if self.pending.is_none() {
                self.pending = Some(before);
            }
            self.last_after = Some(after.clone());
        } else {
            self.push(before);
        }
    }

    fn push(&mut self, before: EditSnapshot) {
        self.undo.push(before);
        if self.undo.len() > LIMIT {
            self.undo.remove(0);
        }
        self.redo.clear();
    }

    fn commit_pending(&mut self) {
        let after = self.last_after.take();
        if let Some(before) = self.pending.take() {
            if after.as_ref() != Some(&before) {
                self.push(before);
            }
        }
    }

    pub fn can_undo(&self) -> bool {
        self.pending.is_some() || !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        self.pending.is_none() && !self.redo.is_empty()
    }

    /// Returns the snapshot to restore, or None. A gesture never closed (pointer cancel) is
    /// committed first.
    pub fn undo(&mut self, current: &EditSnapshot) -> Option<EditSnapshot> {
        self.gesture = false;
        self.commit_pending();
        let saved = self.undo.pop()?;
        self.redo.push(current.clone());
        Some(saved)
    }

    /// Returns the snapshot to restore, or None. Refused while a step is pending.
    pub fn redo(&mut self, current: &EditSnapshot) -> Option<EditSnapshot> {
        if self.pending.is_some() {
            return None;
        }
        let saved = self.redo.pop()?;
        self.undo.push(current.clone());
        Some(saved)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::EqState;

    fn snap(preamp: f32) -> EditSnapshot {
        EditSnapshot {
            bands: EqState::default().bands,
            band_count: 10,
            gains_db: vec![0.0; 10],
            preamp_db: preamp,
            bass_db: 0.0,
            treble_db: 0.0,
            balance: 0.0,
        }
    }

    #[test]
    fn a_drag_is_one_step_and_redo_restores_it() {
        let mut h = History::default();
        h.begin_edit();
        for i in 0..30 {
            h.record(snap(i as f32), &snap(i as f32 + 1.0));
        }
        h.end_edit();

        let cur = snap(30.0);
        let back = h.undo(&cur).unwrap();
        assert_eq!(back, snap(0.0));
        assert!(h.undo(&back).is_none());
        let fwd = h.redo(&back).unwrap();
        assert_eq!(fwd, snap(30.0));
        assert!(!h.can_redo());
    }

    #[test]
    fn a_tap_is_one_step() {
        let mut h = History::default();
        h.record(snap(0.0), &snap(1.0));
        h.record(snap(1.0), &snap(2.0));
        assert_eq!(h.undo(&snap(2.0)).unwrap(), snap(1.0));
        assert_eq!(h.undo(&snap(1.0)).unwrap(), snap(0.0));
        assert!(!h.can_undo());
    }

    #[test]
    fn an_unchanged_record_adds_nothing() {
        let mut h = History::default();
        h.record(snap(1.0), &snap(1.0));
        assert!(!h.can_undo());
        h.begin_edit();
        h.record(snap(1.0), &snap(1.0));
        h.end_edit();
        assert!(!h.can_undo());
    }

    #[test]
    fn a_drag_that_returns_to_start_adds_nothing() {
        let mut h = History::default();
        h.begin_edit();
        h.record(snap(0.0), &snap(5.0));
        h.record(snap(5.0), &snap(0.0));
        h.end_edit();
        assert!(!h.can_undo());
    }

    #[test]
    fn a_cancelled_gesture_is_committed_by_the_next_undo() {
        let mut h = History::default();
        h.begin_edit();
        h.record(snap(0.0), &snap(3.0));
        // The pointer was cancelled: end_edit never arrives.
        assert!(h.can_undo());
        assert_eq!(h.undo(&snap(3.0)).unwrap(), snap(0.0));
        assert!(h.redo(&snap(0.0)).is_some());
        // A later tap is its own step, not folded into the dead gesture.
        h.record(snap(3.0), &snap(4.0));
        assert_eq!(h.undo(&snap(4.0)).unwrap(), snap(3.0));
    }

    #[test]
    fn a_cancelled_gesture_is_committed_by_end_edit_and_begin_is_idempotent() {
        let mut h = History::default();
        h.begin_edit();
        h.begin_edit();
        h.record(snap(0.0), &snap(1.0));
        h.end_edit();
        h.end_edit();
        assert_eq!(h.undo(&snap(1.0)).unwrap(), snap(0.0));
        assert!(!h.can_undo());
    }

    #[test]
    fn redo_is_refused_while_a_step_is_pending() {
        let mut h = History::default();
        h.record(snap(0.0), &snap(1.0));
        let back = h.undo(&snap(1.0)).unwrap();
        assert!(h.can_redo());
        h.begin_edit();
        h.record(back.clone(), &snap(7.0));
        assert!(!h.can_redo());
        assert!(h.redo(&snap(7.0)).is_none());
    }

    #[test]
    fn a_new_edit_clears_redo() {
        let mut h = History::default();
        h.record(snap(0.0), &snap(1.0));
        let back = h.undo(&snap(1.0)).unwrap();
        assert!(h.can_redo());
        h.record(back, &snap(9.0));
        assert!(!h.can_redo());
    }

    #[test]
    fn the_hundred_and_first_step_drops_the_oldest() {
        let mut h = History::default();
        for i in 0..101 {
            h.record(snap(i as f32), &snap(i as f32 + 1.0));
        }
        let mut cur = snap(101.0);
        let mut steps = 0;
        let mut oldest = None;
        while let Some(prev) = h.undo(&cur) {
            steps += 1;
            oldest = Some(prev.clone());
            cur = prev;
        }
        assert_eq!(steps, 100);
        assert_eq!(oldest.unwrap(), snap(1.0));
    }

    #[test]
    fn untracked_fields_create_no_steps_and_are_left_alone() {
        // Mode, bypass, limiter and frame duration are not in EditSnapshot; a caller that
        // only changes them records an identical snapshot pair, which is not a step.
        let mut h = History::default();
        h.record(snap(0.0), &snap(0.0));
        assert!(!h.can_undo());
        // A real edit followed by undo returns only the tracked fields.
        h.record(snap(0.0), &snap(3.0));
        let restored = h.undo(&snap(3.0)).unwrap();
        assert_eq!(restored.preamp_db, 0.0);
        assert_eq!(restored.band_count, 10);
    }
}
