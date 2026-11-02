//! Writes the parametric bands as an Equalizer APO `ParametricEQ.txt`, the format AutoEq
//! publishes and [`crate::import`] reads.

use crate::model::Band;

pub(crate) fn parametric_text(_bands: &[Band], _preamp_db: f32) -> String {
    todo!()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::parse_any;
    use crate::model::FilterKind;

    fn band(kind: FilterKind, freq: f32, gain: f32, q: f32, enabled: bool) -> Band {
        Band { kind, freq, gain_db: gain, q, enabled }
    }

    #[test]
    fn writes_the_documented_lines() {
        let text = parametric_text(
            &[
                band(FilterKind::LowShelf, 105.0, 5.5, 0.7, true),
                band(FilterKind::Peak, 3250.0, -2.5, 2.85, false),
                band(FilterKind::HighShelf, 10000.0, 1.0, 0.5, true),
                band(FilterKind::LowPass, 18000.0, 0.0, 0.5, true),
                band(FilterKind::HighPass, 30.0, 0.0, 0.707, true),
            ],
            -6.2,
        );
        assert_eq!(
            text,
            "Preamp: -6.2 dB\n\
             Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.7\n\
             Filter 2: OFF PK Fc 3250 Hz Gain -2.5 dB Q 2.85\n\
             Filter 3: ON HSC Fc 10000 Hz Gain 1 dB Q 0.5\n\
             Filter 4: ON LP Fc 18000 Hz Q 0.5\n\
             Filter 5: ON HP Fc 30 Hz Q 0.707\n"
        );
    }

    #[test]
    fn round_trips_exactly_for_every_kind_and_a_disabled_band() {
        let bands = vec![
            band(FilterKind::Peak, 1234.5677, -7.123457, 1.4142135, true),
            band(FilterKind::LowShelf, 21.000002, 23.999998, 0.1, false),
            band(FilterKind::HighShelf, 19999.99, -24.0, 17.999998, true),
            band(FilterKind::LowPass, 8123.457, 0.0, std::f32::consts::FRAC_1_SQRT_2, true),
            band(FilterKind::HighPass, 45.678, 0.0, 0.9876543, false),
        ];
        let preamp = -3.3333333;
        let parsed = parse_any(&parametric_text(&bands, preamp), 10).unwrap();
        assert_eq!(parsed.bands, bands);
        assert_eq!(parsed.preamp_db, preamp);
        assert_eq!(parsed.skipped, 0);
    }

    #[test]
    fn round_trips_sixteen_bands() {
        let bands: Vec<Band> = (0..16)
            .map(|i| band(FilterKind::Peak, 50.0 * (i as f32 + 1.37), i as f32 - 8.3, 0.5 + i as f32 * 0.11, i % 3 != 0))
            .collect();
        let parsed = parse_any(&parametric_text(&bands, 0.0), 31).unwrap();
        assert_eq!(parsed.bands, bands);
    }

    #[test]
    fn an_empty_export_is_not_an_importable_file() {
        let text = parametric_text(&[], 0.0);
        assert_eq!(text, "Preamp: 0 dB\n");
        assert!(parse_any(&text, 10).is_err());
    }
}
