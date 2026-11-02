// Origin: macOS EQ app src/import.rs, commit 03be7aa, GPL-3.0.
// Ported for Android; `crate::state` -> `crate::model`, and files arrive as text, not paths.

use crate::api::ImportKind;
use crate::error::EqError;
use crate::graphic;
use crate::model::{Band, Curve, FilterKind, GRAPHIC_COUNTS, GRAPHIC_MAX_DB, MAX_BANDS, MAX_GAIN_DB};
use crate::presets;

/// Anything bigger is not an EQ file; the app checks this too before the text gets here.
pub(crate) const MAX_TEXT_BYTES: usize = 1_000_000;

/// A curve read from an Equalizer APO configuration, the format AutoEq publishes its
/// headphone corrections in (`ParametricEQ.txt`).
#[derive(Debug, PartialEq)]
pub(crate) struct Imported {
    pub curve: Curve,
    /// Filters left out: types this equalizer does not have, or more than it has room for.
    pub skipped: usize,
}

fn kind(name: &str) -> Option<FilterKind> {
    match name.to_ascii_uppercase().as_str() {
        "PK" | "PEQ" => Some(FilterKind::Peak),
        "LS" | "LSC" | "LSQ" => Some(FilterKind::LowShelf),
        "HS" | "HSC" | "HSQ" => Some(FilterKind::HighShelf),
        "LP" | "LPQ" => Some(FilterKind::LowPass),
        "HP" | "HPQ" => Some(FilterKind::HighPass),
        _ => None,
    }
}

/// The number after `key`, as in `Fc 105 Hz` or `Gain -2.5 dB`.
fn value(tokens: &[&str], key: &str) -> Option<f32> {
    let at = tokens.iter().position(|t| t.eq_ignore_ascii_case(key))?;
    tokens.get(at + 1)?.parse().ok().filter(|v: &f32| v.is_finite())
}

/// Parses `ON PK Fc 105 Hz Gain 5.5 dB Q 0.70`, the part of a filter line after the colon.
fn filter(tokens: &[&str]) -> Option<Band> {
    let mut band = Band::new(kind(tokens.get(1)?)?, value(tokens, "Fc")?);
    band.enabled = tokens[0].eq_ignore_ascii_case("ON");
    if band.kind.has_gain() {
        band.gain_db = value(tokens, "Gain")?;
    }
    if let Some(q) = value(tokens, "Q") {
        band.q = q;
    }
    band.clamp();
    Some(band)
}

fn preamp_of(tokens: &[&str]) -> Option<f32> {
    let db: f32 = tokens.first()?.parse().ok()?;
    db.is_finite().then(|| db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB))
}

/// A parametric file needs at least one filter line, usable or not, and one it can use.
pub(crate) fn parse(text: &str) -> Result<Imported, EqError> {
    let mut curve = Curve {
        bands: Vec::new(),
        preamp_db: 0.0,
    };
    let mut skipped = 0;
    let mut filters = 0;
    for line in text.lines() {
        let Some((head, rest)) = line.split_once(':') else {
            continue;
        };
        let head = head.trim().to_ascii_lowercase();
        let tokens: Vec<&str> = rest.split_whitespace().collect();
        if head == "preamp" {
            if let Some(db) = preamp_of(&tokens) {
                curve.preamp_db = db;
            }
        } else if head.starts_with("filter") {
            filters += 1;
            match filter(&tokens) {
                Some(band) if curve.bands.len() < MAX_BANDS => curve.bands.push(band),
                _ => skipped += 1,
            }
        }
    }
    if filters > 0 && !curve.bands.is_empty() {
        Ok(Imported { curve, skipped })
    } else {
        Err(EqError::NotAnEqFile)
    }
}

/// What `parse_any` found, ready to become a preset.
#[derive(Debug, PartialEq)]
pub(crate) struct Parsed {
    pub kind: ImportKind,
    /// Parametric files only.
    pub bands: Vec<Band>,
    /// Graphic files only: one gain per centre of the requested layout.
    pub graphic_gains: Vec<f32>,
    pub preamp_db: f32,
    pub skipped: usize,
    pub clamped: usize,
}

/// The points of a `GraphicEQ: 20 -5.2; 21 -5.1; ...` line, sorted by frequency, with the
/// number of entries that could not be used.
fn graphic_points(line: &str) -> (Vec<(f64, f64)>, usize) {
    let mut points = Vec::new();
    let mut skipped = 0;
    for pair in line.split(';').map(str::trim).filter(|p| !p.is_empty()) {
        let mut parts = pair.split_whitespace();
        let point = match (parts.next(), parts.next(), parts.next()) {
            (Some(f), Some(g), None) => f.parse::<f64>().ok().zip(g.parse::<f64>().ok()),
            _ => None,
        };
        match point {
            // Bounded far outside the slider range, so averaging huge values cannot overflow to NaN.
            Some((f, g)) if f.is_finite() && g.is_finite() && f > 0.0 => points.push((f, g.clamp(-1e6, 1e6))),
            _ => skipped += 1,
        }
    }
    points.sort_by(|a, b| a.0.total_cmp(&b.0));
    (points, skipped)
}

/// The curve at `hz`: linear in log frequency between points, the end values held beyond them.
fn level_at(points: &[(f64, f64)], hz: f64) -> f64 {
    let at = points.partition_point(|p| p.0 <= hz);
    if at == 0 {
        return points[0].1;
    }
    if at == points.len() {
        return points[at - 1].1;
    }
    let ((f0, g0), (f1, g1)) = (points[at - 1], points[at]);
    g0 + (g1 - g0) * (hz.ln() - f0.ln()) / (f1.ln() - f0.ln())
}

/// Samples per band when averaging. Enough that a 1/3-octave spike in the 31-band layout, whose
/// bands are a third of an octave wide, is seen across at least a dozen of them.
const SAMPLES_PER_BAND: usize = 96;

/// Mean of the curve over each band's width in log frequency. A band reaches from the geometric
/// midpoint to its lower neighbour to the one to its upper neighbour; the end bands are as wide
/// on the outside as on the inside.
fn resample(points: &[(f64, f64)], centres: &[f32]) -> Vec<f64> {
    let c: Vec<f64> = centres.iter().map(|&c| f64::from(c).ln()).collect();
    (0..c.len())
        .map(|i| {
            let below = if i > 0 { (c[i - 1] + c[i]) / 2.0 } else { c[i] - (c[1] - c[0]) / 2.0 };
            let above = if i + 1 < c.len() {
                (c[i] + c[i + 1]) / 2.0
            } else {
                c[i] + (c[i] - c[i - 1]) / 2.0
            };
            let sum: f64 = (0..SAMPLES_PER_BAND)
                .map(|k| {
                    let x = below + (above - below) * (k as f64 + 0.5) / SAMPLES_PER_BAND as f64;
                    level_at(points, x.exp())
                })
                .sum();
            sum / SAMPLES_PER_BAND as f64
        })
        .collect()
}

fn parse_graphic(text: &str, line: &str, count: u32) -> Result<Parsed, EqError> {
    let centres = graphic::centres(count).ok_or(EqError::InvalidValue)?;
    let (points, skipped) = graphic_points(line);
    if points.is_empty() {
        return Err(EqError::NotAnEqFile);
    }
    let mut clamped = 0;
    let graphic_gains = resample(&points, centres)
        .into_iter()
        .map(|g| {
            let g = g as f32;
            if g.abs() > GRAPHIC_MAX_DB {
                clamped += 1;
            }
            g.clamp(-GRAPHIC_MAX_DB, GRAPHIC_MAX_DB)
        })
        .collect();
    let preamp_db = text
        .lines()
        .filter_map(|l| l.split_once(':'))
        .filter(|(head, _)| head.trim().eq_ignore_ascii_case("preamp"))
        .find_map(|(_, rest)| preamp_of(&rest.split_whitespace().collect::<Vec<_>>()))
        .unwrap_or(0.0);
    Ok(Parsed {
        kind: ImportKind::Graphic,
        bands: Vec::new(),
        graphic_gains,
        preamp_db,
        skipped,
        clamped,
    })
}

/// Reads either AutoEq format: a `GraphicEQ:` line means graphic, anything else parametric.
/// Graphic curves are resampled onto `graphic_band_count` bands.
pub(crate) fn parse_any(text: &str, graphic_band_count: u32) -> Result<Parsed, EqError> {
    if text.len() > MAX_TEXT_BYTES {
        return Err(EqError::FileTooLarge);
    }
    if !GRAPHIC_COUNTS.contains(&graphic_band_count) {
        return Err(EqError::InvalidValue);
    }
    if text.contains('\0') {
        return Err(EqError::NotAnEqFile);
    }
    let text = text.trim_start_matches('\u{feff}');
    let graphic_line = text.lines().find_map(|l| {
        let (head, rest) = l.split_once(':')?;
        head.trim().eq_ignore_ascii_case("graphiceq").then_some(rest)
    });
    if let Some(line) = graphic_line {
        return parse_graphic(text, line, graphic_band_count);
    }
    let Imported { curve, skipped } = parse(text)?;
    Ok(Parsed {
        kind: ImportKind::Parametric,
        bands: curve.bands,
        graphic_gains: Vec::new(),
        preamp_db: curve.preamp_db,
        skipped,
        clamped: 0,
    })
}

/// The preset name for a file: no extension, no trailing `ParametricEQ` or `GraphicEQ`, at most
/// 40 characters, and not one already `taken` (or a built-in), compared the way presets are.
pub(crate) fn preset_name(file_name: &str, taken: &[String]) -> String {
    let stem = match file_name.rsplit_once('.') {
        Some((stem, ext)) if !ext.contains(char::is_whitespace) => stem,
        _ => file_name,
    };
    let stem: String = stem.chars().filter(|c| !c.is_control()).collect();
    let stem = stem.trim();
    let lower = stem.to_ascii_lowercase();
    let stem = ["parametriceq", "graphiceq"]
        .iter()
        .find(|suffix| lower.ends_with(**suffix))
        .map_or(stem, |suffix| &stem[..stem.len() - suffix.len()])
        .trim();
    let base = if stem.is_empty() { "Imported" } else { stem };
    let fit = |text: &str, room: usize| -> String {
        text.chars().take(room).collect::<String>().trim_end().to_string()
    };
    let used = |name: &str| {
        let norm = presets::normalize(name);
        taken.iter().any(|t| presets::normalize(t) == norm)
            || presets::builtin_presets().iter().any(|p| presets::normalize(&p.name) == norm)
    };
    let first = fit(base, presets::MAX_NAME_CHARS);
    if !used(&first) {
        return first;
    }
    (2..)
        .map(|n| {
            let suffix = format!(" {n}");
            let room = presets::MAX_NAME_CHARS - suffix.chars().count();
            format!("{}{suffix}", fit(base, room))
        })
        .find(|name| !used(name))
        .unwrap_or(first)
}

#[cfg(test)]
mod tests {
    use super::*;

    const AUTOEQ: &str = "\
Preamp: -6.2 dB
Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
Filter 2: ON PK Fc 180 Hz Gain -2.3 dB Q 1.20
Filter 3: ON PK Fc 3250 Hz Gain 4.1 dB Q 2.85
Filter 4: ON HSC Fc 10000 Hz Gain -3.0 dB Q 0.70
";

    #[test]
    fn reads_an_autoeq_file() {
        let imported = parse(AUTOEQ).unwrap();
        assert_eq!(imported.skipped, 0);
        assert_eq!(imported.curve.preamp_db, -6.2);
        let bands = &imported.curve.bands;
        assert_eq!(bands.len(), 4);
        assert_eq!(bands[0].kind, FilterKind::LowShelf);
        assert_eq!((bands[1].freq, bands[1].gain_db, bands[1].q), (180.0, -2.3, 1.2));
        assert_eq!(bands[3].kind, FilterKind::HighShelf);
        assert!(bands.iter().all(|b| b.enabled));
    }

    #[test]
    fn handles_off_filters_pass_filters_and_missing_q() {
        let imported = parse(
            "Filter 1: OFF PK Fc 1000 Hz Gain 3 dB Q 1\r\n\
             Filter 2: ON HP Fc 30 Hz\r\n\
             filter: on lpq fc 18000 hz q 0.5\r\n",
        )
        .unwrap();
        let bands = &imported.curve.bands;
        assert!(!bands[0].enabled);
        assert_eq!(bands[1].kind, FilterKind::HighPass);
        assert_eq!(bands[1].q, std::f32::consts::FRAC_1_SQRT_2);
        assert_eq!((bands[2].kind, bands[2].q), (FilterKind::LowPass, 0.5));
        assert_eq!(imported.curve.preamp_db, 0.0);
    }

    #[test]
    fn counts_what_it_cannot_use() {
        let mut text = String::from("Filter 1: ON NO Fc 50 Hz\nFilter 2: ON PK Fc 1000 Hz\n");
        for _ in 0..MAX_BANDS + 2 {
            text.push_str("Filter: ON PK Fc 1000 Hz Gain 1 dB Q 1\n");
        }
        let imported = parse(&text).unwrap();
        assert_eq!(imported.curve.bands.len(), MAX_BANDS);
        // The notch, the peak with no gain, and the two that did not fit.
        assert_eq!(imported.skipped, 4);
    }

    #[test]
    fn clamps_values_out_of_range() {
        let imported = parse("Preamp: -90 dB\nFilter 1: ON PK Fc 5 Hz Gain 40 dB Q 100\n").unwrap();
        assert_eq!(imported.curve.preamp_db, -MAX_GAIN_DB);
        let band = imported.curve.bands[0];
        assert_eq!((band.freq, band.gain_db, band.q), (20.0, MAX_GAIN_DB, 18.0));
    }

    #[test]
    fn rejects_files_that_are_something_else() {
        assert!(parse("").is_err());
        assert!(parse("Dear diary: today I boosted the bass\n").is_err());
    }

    // ---- parse_any: format detection and the GraphicEQ path ----

    fn graphic_text(points: &[(f32, f32)]) -> String {
        let body: Vec<String> = points.iter().map(|(f, g)| format!("{f} {g}")).collect();
        format!("GraphicEQ: {}\n", body.join("; "))
    }

    /// A curve with points at every 1/12 octave from 20 Hz to 20 kHz.
    fn dense(f: impl Fn(f32) -> f32) -> Vec<(f32, f32)> {
        let n = (12.0 * (1000.0f32).log2()).round() as usize;
        (0..=n)
            .map(|i| {
                let hz = 20.0 * 2f32.powf(i as f32 / 12.0);
                (hz, f(hz))
            })
            .collect()
    }

    const REAL_GRAPHIC: &str = "GraphicEQ: 20 -5.2; 21 -5.1; 22 -5.0; 100 -2.5; 1000 0.5; 3000 4.5; 10000 2.0; 20000 3.0\n";

    #[test]
    fn detects_the_format_by_the_graphiceq_line() {
        let g = parse_any(REAL_GRAPHIC, 10).unwrap();
        assert_eq!(g.kind, ImportKind::Graphic);
        assert_eq!(g.graphic_gains.len(), 10);
        assert!(g.bands.is_empty());
        let p = parse_any(AUTOEQ, 10).unwrap();
        assert_eq!(p.kind, ImportKind::Parametric);
        assert_eq!(p.bands.len(), 4);
        assert!(p.graphic_gains.is_empty());
        assert_eq!(p.preamp_db, -6.2);
        let with_preamp = format!("Preamp: -4.5 dB\n{REAL_GRAPHIC}");
        let g = parse_any(&with_preamp, 15).unwrap();
        assert_eq!((g.kind, g.preamp_db, g.graphic_gains.len()), (ImportKind::Graphic, -4.5, 15));
        assert_eq!(parse_any(REAL_GRAPHIC, 5).unwrap().preamp_db, 0.0);
    }

    #[test]
    fn tolerates_a_byte_order_mark_and_windows_line_ends() {
        let text = format!("\u{feff}{}", AUTOEQ.replace('\n', "\r\n"));
        assert_eq!(parse_any(&text, 10).unwrap().bands.len(), 4);
        let text = format!("\u{feff}Preamp: -2 dB\r\n{}", REAL_GRAPHIC.replace('\n', "\r\n"));
        assert_eq!(parse_any(&text, 10).unwrap().kind, ImportKind::Graphic);
    }

    #[test]
    fn a_flat_graphic_curve_stays_flat_on_every_layout() {
        for count in [5, 10, 15, 31] {
            let g = parse_any(&graphic_text(&dense(|_| 0.0)), count).unwrap();
            assert_eq!(g.graphic_gains, vec![0.0; count as usize], "{count} bands");
            assert_eq!((g.skipped, g.clamped), (0, 0));
        }
        let g = parse_any(&graphic_text(&dense(|_| -3.0)), 31).unwrap();
        assert!(g.graphic_gains.iter().all(|v| (v + 3.0).abs() < 1e-4));
    }

    #[test]
    fn a_falling_slope_stays_non_increasing() {
        for count in [5, 10, 15, 31] {
            let g = parse_any(&graphic_text(&dense(|hz| -4.0 * (hz / 20.0).log2())), count).unwrap();
            assert!(
                g.graphic_gains.windows(2).all(|w| w[1] <= w[0]),
                "{count} bands: {:?}",
                g.graphic_gains
            );
            assert!(g.graphic_gains[0] > g.graphic_gains[count as usize - 1]);
        }
    }

    #[test]
    fn absurdly_large_points_clamp_instead_of_failing_the_import() {
        // Interpolating between +1e308 and -1e308 overflows to NaN unless the points are bounded.
        let g = parse_any("GraphicEQ: 100 1e308; 200 -1e308; 400 1e308\n", 10).unwrap();
        assert!(g.graphic_gains.iter().all(|v| v.abs() <= GRAPHIC_MAX_DB));
        assert!(g.clamped > 0);
    }

    #[test]
    fn unsorted_points_are_sorted() {
        let sorted = parse_any("GraphicEQ: 20 1; 200 2; 2000 3; 20000 4\n", 10).unwrap();
        let shuffled = parse_any("GraphicEQ: 2000 3; 20 1; 20000 4; 200 2\n", 10).unwrap();
        assert_eq!(sorted.graphic_gains, shuffled.graphic_gains);
        assert_eq!(shuffled.skipped, 0);
    }

    #[test]
    fn unusable_graphic_points_are_dropped_and_counted() {
        let g = parse_any(
            "GraphicEQ: 0 3; -100 3; 100 NaN; 100 inf; abc 1; 1000 2; 1000; 5000 2\n",
            10,
        )
        .unwrap();
        assert_eq!(g.skipped, 6);
        // The two usable points are both +2 dB, so the curve is a flat +2 everywhere.
        assert!(g.graphic_gains.iter().all(|v| (v - 2.0).abs() < 1e-4));
    }

    #[test]
    fn a_narrow_spike_raises_the_band_it_sits_in() {
        let mut points = dense(|_| 0.0);
        // 3 dB at 1 kHz only, well inside the 1 kHz band of the 31-band layout.
        points.retain(|p| (p.0 - 1000.0).abs() > 150.0 || p.0 == 1000.0);
        let spike = points.iter().position(|p| p.0 > 900.0).unwrap();
        points.insert(spike, (950.0, 0.0));
        points.insert(spike + 1, (1000.0, 3.0));
        points.insert(spike + 2, (1050.0, 0.0));
        let g = parse_any(&graphic_text(&points), 31).unwrap();
        let at = graphic::centres(31).unwrap().iter().position(|&c| c == 1000.0).unwrap();
        assert!(g.graphic_gains[at] > 0.1, "{}", g.graphic_gains[at]);
        assert!(g.graphic_gains[at] < 3.0, "a mean, not a point sample");
    }

    #[test]
    fn out_of_range_values_clamp_and_are_counted() {
        let g = parse_any("GraphicEQ: 20 40; 200 -40; 20000 0\n", 10).unwrap();
        assert!(g.clamped >= 2, "{}", g.clamped);
        assert!(g.graphic_gains.iter().all(|v| v.abs() <= GRAPHIC_MAX_DB));
        assert_eq!(g.graphic_gains[0], GRAPHIC_MAX_DB);
        let calm = parse_any(REAL_GRAPHIC, 10).unwrap();
        assert_eq!(calm.clamped, 0);
    }

    #[test]
    fn the_ends_are_held_outside_the_files_range() {
        let g = parse_any("GraphicEQ: 1000 5; 2000 5\n", 10).unwrap();
        assert!(g.graphic_gains.iter().all(|v| (v - 5.0).abs() < 1e-4));
    }

    // ---- names ----

    fn taken(names: &[&str]) -> Vec<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn names_the_preset_after_the_file() {
        let none = taken(&[]);
        assert_eq!(preset_name("Sennheiser HD 650 ParametricEQ.txt", &none), "Sennheiser HD 650");
        assert_eq!(preset_name("X GraphicEQ.txt", &none), "X");
        assert_eq!(preset_name("x graphiceq.TXT", &none), "x");
        assert_eq!(preset_name("ParametricEQ.txt", &none), "Imported");
        assert_eq!(preset_name("  .txt", &none), "Imported");
        assert_eq!(preset_name("", &none), "Imported");
        assert_eq!(preset_name("My phones", &none), "My phones");
    }

    #[test]
    fn long_names_truncate_on_a_char_boundary() {
        let none = taken(&[]);
        let name = preset_name(&format!("{}.txt", "é".repeat(60)), &none);
        assert_eq!(name, "é".repeat(40));
        let name = preset_name(&format!("{} ParametricEQ.txt", "日".repeat(60)), &none);
        assert_eq!(name.chars().count(), 40);
        assert!(crate::presets::validate_name(&name).is_ok());
    }

    #[test]
    fn clashes_get_a_number_and_never_overwrite() {
        assert_eq!(preset_name("Flat.txt", &taken(&[])), "Flat 2");
        assert_eq!(preset_name("flat ParametricEQ.txt", &taken(&[])), "flat 2");
        let user = taken(&["HD 650"]);
        assert_eq!(preset_name("HD 650 ParametricEQ.txt", &user), "HD 650 2");
        let user = taken(&["HD 650", "hd 650 2"]);
        assert_eq!(preset_name("HD 650 ParametricEQ.txt", &user), "HD 650 3");
        let user = taken(&["Flat", "Flat 2"]);
        assert_eq!(preset_name("Flat.txt", &user), "Flat 3");
    }

    #[test]
    fn a_clash_on_a_full_length_name_still_fits() {
        let long = "a".repeat(40);
        let name = preset_name(&format!("{long}.txt"), &taken(&[&long]));
        assert_eq!(name.chars().count(), 40);
        assert!(name.ends_with(" 2"));
        assert!(crate::presets::validate_name(&name).is_ok());
    }

    #[test]
    fn control_characters_never_reach_a_name() {
        let name = preset_name("bad\nname\u{7}.txt", &taken(&[]));
        assert!(crate::presets::validate_name(&name).is_ok());
    }

    // ---- errors ----

    #[test]
    fn files_that_are_not_eq_files_are_typed_errors() {
        for text in ["", "Dear diary: today I boosted the bass\n", "GraphicEQ: \n", "GraphicEQ: x y; z\n"] {
            assert_eq!(parse_any(text, 10), Err(EqError::NotAnEqFile), "{text:?}");
        }
        assert_eq!(parse_any("binary\0stuff Filter 1: ON PK Fc 1000 Hz Gain 1 dB", 10), Err(EqError::NotAnEqFile));
    }

    #[test]
    fn a_preamp_only_file_is_rejected() {
        assert_eq!(parse_any("Preamp: -3 dB\n", 10), Err(EqError::NotAnEqFile));
        // Every filter unusable would also be a blank preset.
        assert_eq!(parse_any("Preamp: -3 dB\nFilter 1: ON NO Fc 50 Hz\n", 10), Err(EqError::NotAnEqFile));
    }

    #[test]
    fn oversize_text_is_rejected_by_the_core() {
        let mut text = String::from(AUTOEQ);
        text.push_str(&"# padding\n".repeat(120_000));
        assert!(text.len() > 1_000_000);
        assert_eq!(parse_any(&text, 10), Err(EqError::FileTooLarge));
    }

    #[test]
    fn an_unsupported_layout_is_invalid() {
        assert_eq!(parse_any(AUTOEQ, 7), Err(EqError::InvalidValue));
        assert_eq!(parse_any(REAL_GRAPHIC, 0), Err(EqError::InvalidValue));
    }
}
