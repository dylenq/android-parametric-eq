#!/usr/bin/env python3
"""Generate oracle cases for the Rust band model from the Phase 1 Python model (band_model.py).

Writes core/tests/golden/band_model_cases.json. Stdlib only. Run: python3 -I spike-tools/gen_oracle_cases.py

Size choice: per-bin gain arrays are stored in full but rounded to 1e-9 dB (the Rust test
tolerance is 1e-6 dB); 24 cases come to well under 2 MB. Cutoffs are bin-centred (stop * binw),
mirroring the Rust grid, and `stops` are stored so the grid itself is checked exactly.

The Python block rule is a ceil of the float; the Rust/native rule truncates first. This script
asserts they agree for every frame duration it uses and fails otherwise.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import band_model as bm  # noqa: E402

SRS = (44100, 48000)
FRAMES = (10.0, 42.6, 85.3, 170.6)
PEAKS = ((1000.0, 4.0, 12.0), (200.0, 8.0, -12.0), (5000.0, 1.0, 6.0))
PEAKING = [
    # (f, f0, q, gain_db, sr)
    (1000.0, 1000.0, 1.0, 12.0, 48000), (700.0, 1000.0, 1.0, 12.0, 48000),
    (1500.0, 1000.0, 4.0, -6.0, 48000), (80.0, 100.0, 8.0, 12.0, 44100),
    (123.0, 100.0, 0.7, -12.0, 44100), (9000.0, 10000.0, 2.0, 6.0, 48000),
    (20.0, 50.0, 18.0, 24.0, 48000), (15000.0, 5000.0, 0.5, -24.0, 44100),
]


def native_block(frame_ms, sr):
    """Native rule: truncate, <8 -> 8, round up to a power of two, max 16384 (f32 arithmetic is
    equivalent for these inputs, which the agreement assertion below confirms)."""
    n = int(frame_ms * sr / 1000.0)
    if n < 8:
        return 8
    if n >= 16384:
        return 16384
    p = 1
    while p < n:
        p *= 2
    return p


def make_case(sr, frame_ms, f0, q, gain_db):
    block = bm.block_size(frame_ms, sr)
    assert block == native_block(frame_ms, sr), ("block rule disagreement", frame_ms, sr)
    binw = sr / block
    raw = bm.hybrid_grid(frame_ms, sr, 256)
    stops = [bm.bin_stop(c, block, sr) for c in raw]
    cutoffs = [s * binw for s in stops]
    ranges = bm.band_ranges(cutoffs, block, sr)
    assert [r[1] for r in ranges] == stops
    g = bm.bin_gains(cutoffs, block, sr, f0, q, gain_db)
    half = block // 2
    band_gains = []
    for start, stop in ranges:
        top = min(stop, half)
        assert stop >= start and start <= top
        band_gains.append(g[start])
    probes = [20.0 * (1000.0 ** (i / 39.0)) for i in range(40)]
    probes += [0.3 * binw, 1.5 * binw, sr / 2 - 0.5 * binw, sr / 2]
    probe_db = [bm.smeared_gain_db(g, f / binw) for f in probes]
    return {
        "sr": sr, "frame_ms": frame_ms, "block": block, "f0": f0, "q": q, "gain_db": gain_db,
        "stops": stops, "cutoffs": cutoffs, "band_gains": band_gains,
        "bins": [round(v, 9) for v in g],
        "probes": probes, "probe_db": probe_db,
    }


def main():
    cases = [make_case(sr, fr, *pk) for sr in SRS for fr in FRAMES for pk in PEAKS]
    peaking = [{"f": f, "f0": f0, "q": q, "gain_db": gd, "sr": sr,
                "db": bm.peaking_db(f, f0, q, gd, float(sr))} for f, f0, q, gd, sr in PEAKING]
    out = os.path.join(HERE, "..", "core", "tests", "golden", "band_model_cases.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as fh:
        json.dump({"cases": cases, "peaking_cases": peaking}, fh, sort_keys=True, separators=(",", ":"))
        fh.write("\n")
    print("wrote %d cases, %d peaking cases, %d bytes" % (len(cases), len(peaking), os.path.getsize(out)))


if __name__ == "__main__":
    main()
