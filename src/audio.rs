//! Small audio helpers shared between the engine and the subtitle pipeline.

/// Centre of the quietest 100 ms window in `samples[from..to]`; used to pick a
/// natural split point when audio must be cut mid-speech.
pub fn find_quietest_split(samples: &[f32], from: usize, to: usize) -> usize {
    const WIN: usize = 1_600; // 100 ms
    if from + WIN > to {
        return to;
    }
    // Sliding-window energy (O3): instead of re-summing every 100 ms window
    // from scratch (O(n·WIN)), subtract the samples leaving the window and
    // add the ones entering (O(n)). Steps of WIN/2 keep exactly the same
    // candidate positions the old scan produced, so results are unchanged.
    let mut energy: f32 = samples[from..from + WIN].iter().map(|&x| x * x).sum();
    let mut best_pos = to;
    let mut best_energy = f32::MAX;
    let mut i = from;
    while i + WIN <= to {
        if energy < best_energy {
            best_energy = energy;
            best_pos = i + WIN / 2;
        }
        let step = WIN / 2;
        if i + WIN + step > to {
            break;
        }
        for &x in &samples[i..i + step] {
            energy -= x * x;
        }
        for &x in &samples[i + WIN..i + WIN + step] {
            energy += x * x;
        }
        i += step;
    }
    best_pos
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn returns_to_when_window_does_not_fit() {
        // from + WIN > to → no full window fits; contract is to return `to`.
        let samples = vec![0.0; 100];
        assert_eq!(find_quietest_split(&samples, 0, 100), 100);
    }

    #[test]
    fn empty_range_returns_to() {
        let samples: Vec<f32> = vec![];
        assert_eq!(find_quietest_split(&samples, 0, 0), 0);
    }

    #[test]
    fn flat_signal_returns_first_window_center() {
        // Uniform signal: every window ties, so the first (leftmost) wins and
        // its center is `from + WIN/2`.
        let samples = vec![1.0f32; 3200];
        assert_eq!(find_quietest_split(&samples, 0, 3200), 800);
    }

    #[test]
    fn quietest_window_center_selected() {
        // 16 kHz mono, 4800 samples. Quietest full 100 ms window is the span
        // [1600..3200] (all zeros); its centre is 2400 and must be chosen over
        // the louder neighbours.
        let mut samples = vec![1.0f32; 4800];
        for s in &mut samples[1600..3200] {
            *s = 0.0;
        }
        assert_eq!(find_quietest_split(&samples, 0, 4800), 2400);
    }

    #[test]
    fn scan_is_bounded_to_from_to_window() {
        // A quiet region outside [from, to] must NOT influence the result.
        let mut samples = vec![1.0f32; 6400];
        for s in &mut samples[4000..6400] {
            *s = 0.0;
        }
        // Scan the loud [0..3200] range only; the quiet tail is irrelevant, so
        // the result equals the flat-signal centre for that sub-range.
        assert_eq!(find_quietest_split(&samples, 0, 3200), 800);
    }
}
