#!/usr/bin/env python3
"""Offline model of Android DynamicsProcessing pre-EQ band quantisation (SPIKE-05).

Engine model (research Pattern 2, from DPFrequency.cpp): the pre-EQ is a piecewise-constant gain per
FFT bin range. bin_stop(b) = int(0.5 + cutoff*block/sr); band b covers [previous stop + 1 .. stop].

Two error metrics are reported:
  err_f0_db     point metric (the plan's pass metric): gain of the bin nearest f0 minus the target at f0.
  smeared_err_db  secondary metric: the gain a pure tone at f0 actually sees if the analysis window
                smears it over neighbouring bins. ASSUMPTION: sine analysis window (|W(d)| = cos(pi d)/|1-4d^2|),
                effective gain = sum |W|^2 g / sum |W|^2. This is an assumption about the engine's window,
                not something read from its source; it is compared against emulator data in FINDINGS.md.
Stdlib only. Run: python3 -I spike-tools/band_model.py --out <evidence dir>
"""
import argparse
import json
import math
import os

MIN_BLOCK = 8
MAX_BLOCK = 16384


def block_size(frame_ms, sr):
    samples = frame_ms * sr / 1000.0
    n = MIN_BLOCK
    while n < samples and n < MAX_BLOCK:
        n *= 2
    return n


def bin_stop(cutoff, block, sr):
    return int(0.5 + cutoff * block / sr)


def log_grid(bands, sr):
    lo, hi = 20.0, sr / 2.0
    out = []
    for i in range(bands):
        t = 1.0 if bands == 1 else i / (bands - 1)
        out.append(lo * (hi / lo) ** t)
    out[-1] = hi
    return out


def band_ranges(cutoffs, block, sr):
    """Engine loop: start = binNext; binNext = stop + 1 (set even if stop < start)."""
    ranges = []
    nxt = 0
    for c in cutoffs:
        stop = bin_stop(c, block, sr)
        ranges.append((nxt, stop))
        nxt = stop + 1
    return ranges


def peaking_db(f, f0, q, gain_db, sr=48000.0):
    """RBJ peaking-EQ magnitude in dB at frequency f."""
    if gain_db == 0.0:
        return 0.0
    a = 10 ** (gain_db / 40.0)
    w0 = 2 * math.pi * f0 / sr
    alpha = math.sin(w0) / (2 * q)
    b0, b1, b2 = 1 + alpha * a, -2 * math.cos(w0), 1 - alpha * a
    a0, a1, a2 = 1 + alpha / a, -2 * math.cos(w0), 1 - alpha / a
    w = 2 * math.pi * f / sr
    cw, sw = math.cos(w), math.sin(w)
    c2w, s2w = math.cos(2 * w), math.sin(2 * w)
    nr = b0 + b1 * cw + b2 * c2w
    ni = -(b1 * sw + b2 * s2w)
    dr = a0 + a1 * cw + a2 * c2w
    di = -(a1 * sw + a2 * s2w)
    return 10 * math.log10((nr * nr + ni * ni) / (dr * dr + di * di))


def bin_gains(cutoffs, block, sr, f0, q, gain_db):
    """Per-bin applied gain (dB), bins 0..block/2, following the engine loop."""
    half = block // 2
    g = [0.0] * (half + 1)
    binw = sr / block
    for start, stop in band_ranges(cutoffs, block, sr):
        if stop < start:
            continue  # empty band; nothing is written
        top = min(stop, half)
        if start > top:
            continue
        fa = max(start, 0.5) * binw
        fb = max(top, 0.5) * binw
        centre = math.sqrt(fa * fb)
        val = peaking_db(centre, f0, q, gain_db, sr)
        for k in range(start, top + 1):
            g[k] = val
    return g


def _wmag(d):
    d = abs(d)
    den = abs(1 - 4 * d * d)
    if den < 1e-9:
        return math.pi / 4
    return math.cos(math.pi * d) / den if d < 1.5 else 0.0


def smeared_gain_db(g, x):
    """Gain a tone at fractional bin x sees, sine-window smearing (see module docstring)."""
    num = den = 0.0
    k0 = int(math.floor(x))
    for k in range(max(0, k0 - 4), min(len(g) - 1, k0 + 5) + 1):
        w2 = _wmag(k - x) ** 2
        num += w2 * 10 ** (g[k] / 20.0)
        den += w2
    return 20 * math.log10(num / den)


def evaluate(cutoffs, frame_ms, sr, f0, q, gain_db):
    block = block_size(frame_ms, sr)
    binw = sr / block
    g = bin_gains(cutoffs, block, sr, f0, q, gain_db)
    kf = min(len(g) - 1, max(1, int(round(f0 / binw))))
    measured = g[kf]
    worst = 0.0
    for k in range(1, len(g)):
        e = abs(g[k] - peaking_db(k * binw, f0, q, gain_db, sr))
        worst = max(worst, e)
    x = f0 / binw
    sm = smeared_gain_db(g, x)
    return {
        "block": block, "bins": len(g), "f0_bin": kf, "target_db": gain_db,
        "measured_db": measured, "err_f0_db": measured - gain_db,
        "smeared_db": sm, "smeared_err_db": sm - gain_db, "worst_bin_db": worst,
    }


def adaptive_grid(f0, q, frame_ms, sr, n_sparse, mult):
    """Sparse log bands outside the skirt (f0 +/- mult*f0/q) and one band per bin inside it."""
    block = block_size(frame_ms, sr)
    binw = sr / block
    lo_f = max(f0 - mult * f0 / q, binw)
    hi_f = f0 + mult * f0 / q
    klo = max(1, int(lo_f / binw))
    khi = int(math.ceil(hi_f / binw))
    cuts = []
    for c in log_grid(n_sparse, sr):
        if c < klo * binw - binw or c > khi * binw + binw:
            cuts.append(c)
    cuts += [k * binw for k in range(klo, khi + 1)]
    cuts.append(sr / 2.0)
    cuts = sorted(set(cuts))
    # keep one cutoff per distinct bin_stop
    seen, out = set(), []
    for c in cuts:
        s = bin_stop(c, block, sr)
        if s in seen:
            continue
        seen.add(s)
        out.append(c)
    return out


def hybrid_grid(frame_ms, sr, n_log, f_lin=600.0):
    """One band per FFT bin up to f_lin, then n_log log-spaced bands up to Nyquist (candidate Phase 2 grid)."""
    block = block_size(frame_ms, sr)
    binw = sr / block
    kmax = max(1, int(f_lin / binw))
    cuts = [k * binw for k in range(1, kmax + 1)]
    lo = (kmax + 1) * binw
    hi = sr / 2.0
    for i in range(n_log):
        t = 1.0 if n_log == 1 else i / (n_log - 1)
        cuts.append(lo * (hi / lo) ** t)
    cuts[-1] = hi
    seen, out = set(), []
    for c in sorted(cuts):
        st = bin_stop(c, block, sr)
        if st in seen:
            continue
        seen.add(st)
        out.append(c)
    return out


def hybrid_table(sr=48000):
    L = ["\n## Hybrid grid (one band per bin up to 600 Hz, then log bands to Nyquist): worst over Q in {4,6,8}, gain in {+6,+12,-12}\n",
         "| frame ms | log bands | total bands | f0 Hz | worst point err dB | worst smeared err dB | worst-bin curve err dB |",
         "|---|---|---|---|---|---|---|"]
    for fr in (42.6, 85.3, 170.6):
        for nl in (128, 256):
            cuts = hybrid_grid(fr, sr, nl)
            for f0 in (100, 200, 500, 1000, 2000, 5000, 10000):
                rs = [evaluate(cuts, fr, sr, f0, q, g) for q in QS for g in GAINS]
                L.append("| %.1f | %d | %d | %d | %.2f | %.2f | %.1f |" % (
                    fr, nl, len(cuts), f0, max(abs(r["err_f0_db"]) for r in rs),
                    max(abs(r["smeared_err_db"]) for r in rs), max(r["worst_bin_db"] for r in rs)))
    return L


FRAMES = [10.0, 21.3, 42.6, 85.3, 170.6]
BANDS = [16, 32, 64, 128, 256, 512, 1024]
F0S = [100, 1000, 5000]
QS = [4, 6, 8]
GAINS = [6, 12, -12]


def sweep_fixed(sr):
    cells = []
    for fr in FRAMES:
        for nb in BANDS:
            cuts = log_grid(nb, sr)
            for f0 in F0S:
                for q in QS:
                    for gdb in GAINS:
                        r = evaluate(cuts, fr, sr, f0, q, gdb)
                        r.update(grid="log", sr=sr, frame_ms=fr, bands=nb, f0=f0, q=q, gain_db=gdb)
                        cells.append(r)
    return cells


def sweep_adaptive(sr, frames=(42.6, 85.3, 170.6)):
    cells = []
    for fr in frames:
        for f0 in F0S:
            for nsp in (8, 16, 24):
                for mult in (0.25, 0.5, 1.0, 2.0):
                    for q in QS:
                        for gdb in GAINS:
                            cuts = adaptive_grid(f0, q, fr, sr, nsp, mult)
                            r = evaluate(cuts, fr, sr, f0, q, gdb)
                            r.update(grid="adaptive", sr=sr, frame_ms=fr, bands=len(cuts), f0=f0, q=q,
                                     gain_db=gdb, n_sparse=nsp, skirt_mult=mult)
                            cells.append(r)
    return cells


def worst_over(cells, key, f0, frame, bands):
    sel = [c for c in cells if c["f0"] == f0 and c["frame_ms"] == frame and c["bands"] == bands]
    return max(abs(c[key]) for c in sel) if sel else None


def report(fixed, adaptive):
    L = []
    L.append("# SPIKE-05 offline band model\n")
    L.append("Engine model: piecewise-constant gain per FFT bin range (bin width = sample rate / block). "
             "Pass = |error| <= 1.0 dB, worst over Q in {4,6,8} and gain in {+6,+12,-12} dB, 48 kHz.\n")
    L.append("`point` = bin nearest f0 (the plan's metric). `smeared` = tone seen through an assumed sine analysis window.\n")
    for metric, label in (("err_f0_db", "point"), ("smeared_err_db", "smeared")):
        L.append("\n## Fixed log grid, metric: %s\n" % label)
        L.append("| f0 Hz | " + " | ".join("%.1f ms (blk %d)" % (fr, block_size(fr, 48000)) for fr in FRAMES) + " |")
        L.append("|---|" + "---|" * len(FRAMES))
        for f0 in F0S:
            row = []
            for fr in FRAMES:
                best = None
                for nb in BANDS:
                    w = worst_over([c for c in fixed if c["sr"] == 48000], metric, f0, fr, nb)
                    if w is not None and w <= 1.0:
                        best = (nb, w)
                        break
                row.append("%d bands (%.2f dB)" % best if best else "no passing cell in sweep")
            L.append("| %d | " % f0 + " | ".join(row) + " |")
    L.append("\n## Fixed log grid: best worst-case |point error| reachable (dB) at each frame, any band count <= 1024\n")
    L.append("| f0 Hz | " + " | ".join("%.1f ms" % fr for fr in FRAMES) + " |")
    L.append("|---|" + "---|" * len(FRAMES))
    for f0 in F0S:
        row = []
        for fr in FRAMES:
            vals = [(worst_over([c for c in fixed if c["sr"] == 48000], "err_f0_db", f0, fr, nb), nb) for nb in BANDS]
            w, nb = min(vals)
            row.append("%.2f @%d" % (w, nb))
        L.append("| %d | " % f0 + " | ".join(row) + " |")
    L.append("\n## Fixed log grid: worst-bin curve error (dB, over all bins vs the exact target, worst over Q/gain) at 85.3 ms\n")
    L.append("| f0 Hz | " + " | ".join("%d bands" % nb for nb in BANDS) + " |")
    L.append("|---|" + "---|" * len(BANDS))
    for f0 in F0S:
        row = []
        for nb in BANDS:
            sel = [c for c in fixed if c["sr"] == 48000 and c["f0"] == f0 and c["frame_ms"] == 85.3 and c["bands"] == nb]
            row.append("%.1f" % max(c["worst_bin_db"] for c in sel))
        L.append("| %d | " % f0 + " | ".join(row) + " |")
    L.append("\n## Adaptive grid (sparse log bands + one band per bin across f0 +/- m*f0/Q)\n")
    L.append("Smallest total band count that passes (worst over Q and gain) per f0 and frame; "
             "the adaptive grid is built per (f0,Q) so the count is the largest over Q.\n")
    for metric, label in (("err_f0_db", "point"), ("smeared_err_db", "smeared")):
        L.append("\nadaptive, metric: %s\n" % label)
        L.append("| f0 Hz | frame ms | smallest passing total bands | config | worst err dB | worst-bin curve err dB |")
        L.append("|---|---|---|---|---|---|")
        for f0 in F0S:
            for fr in (42.6, 85.3, 170.6):
                best = None
                for nsp in (8, 16, 24):
                    for mult in (0.25, 0.5, 1.0, 2.0):
                        sel = [c for c in adaptive if c["f0"] == f0 and c["frame_ms"] == fr
                               and c["n_sparse"] == nsp and c["skirt_mult"] == mult]
                        w = max(abs(c[metric]) for c in sel)
                        tot = max(c["bands"] for c in sel)
                        wb = max(c["worst_bin_db"] for c in sel)
                        if w <= 1.0 and (best is None or tot < best[0]):
                            best = (tot, "sparse=%d skirt=%.2f*f0/Q" % (nsp, mult), w, wb)
                if best:
                    L.append("| %d | %.1f | %d | %s | %.2f | %.1f |" % (f0, fr, best[0], best[1], best[2], best[3]))
                else:
                    L.append("| %d | %.1f | no passing cell in sweep | | | |" % (f0, fr))
    L += hybrid_table()
    L.append("\n## 44.1 kHz spot rows (fixed log grid, point metric, worst over Q/gain)\n")
    L.append("| f0 Hz | frame ms | block | bands | worst err dB |")
    L.append("|---|---|---|---|---|")
    return L


def _kv(line):
    d = {}
    for tok in line.split():
        if "=" in tok:
            k, v = tok.split("=", 1)
            d[k] = v
    return d


def compare(log_path, sr=48000):
    """Model prediction vs emulator for the SPIKE-05B (fitted peaks) and SPIKE-05D (single band) log lines."""
    out = ["# Model vs emulator (sr %d)\n" % sr,
           "## SPIKE-05B fitted Q8 +12 dB peaks (delta_db = measured level change on the emulator)\n",
           "| grid | frame ms | bands | f0 | emulator dB | model point dB | model smeared dB |", "|---|---|---|---|---|---|---|"]
    with open(log_path) as fh:
        lines = fh.read().splitlines()
    for ln in lines:
        if "SPIKE-05B:" not in ln or "measured_db=" not in ln:
            continue
        d = _kv(ln.split("SPIKE-05B:", 1)[1])
        fr, f0, nb = float(d["frame_ms"]), float(d["f0"]), int(d["bands"])
        if d["grid"] == "log":
            cuts = log_grid(nb, sr)
        else:
            cuts = adaptive_grid(f0, 8, fr, sr, 16, 1.0)
        r = evaluate(cuts, fr, sr, f0, 8, 12.0)
        out.append("| %s | %s | %d(model %d) | %d | %.2f | %.2f | %.2f |" % (
            d["grid"], d["frame_ms"], nb, len(cuts), f0, float(d["measured_db"]), r["measured_db"], r["smeared_db"]))
    out += ["\n## SPIKE-05D single boosted band, +6 dB target\n",
            "| case | frame ms | bins in band | tone Hz | emulator dB | model point dB | model smeared dB |", "|---|---|---|---|---|---|---|"]
    for ln in lines:
        if "SPIKE-05D:" not in ln or "delta_db=" not in ln:
            continue
        d = _kv(ln.split("SPIKE-05D:", 1)[1])
        fr = float(d["frame_ms"])
        block = block_size(fr, sr)
        binw = sr / block
        tone = float(d["tone"])
        if d["case"] == "D_a1_log64":
            cuts = log_grid(64, sr)
            idx = next(i for i, c in enumerate(cuts) if c >= 1000.0)
            ranges = band_ranges(cuts, block, sr)
            sel = ranges[idx]
        elif d["case"] == "D_a2_100Hzwide":
            a = int(round((1031.25 - 50) / binw)); b = int(round((1031.25 + 50) / binw))
            sel = (a, b)
        else:
            w = int(d["bins"]); kc = int(round(1031.25 / (sr / 512))); h = w // 2
            sel = (kc - h, kc + h)
        g = [0.0] * (block // 2 + 1)
        for k in range(sel[0], sel[1] + 1):
            g[k] = 6.0
        x = tone / binw
        out.append("| %s | %s | %d | %.0f | %.2f | %.2f | %.2f |" % (
            d["case"], d["frame_ms"], sel[1] - sel[0] + 1, tone, float(d["delta_db"]), g[int(round(x))], smeared_gain_db(g, x)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--compare", help="emulator log (SPIKE-05B/D lines) to compare with the model")
    args = ap.parse_args()
    fixed = sweep_fixed(48000)
    f441 = [c for c in sweep_fixed(44100) if c["frame_ms"] in (10.0, 85.3)]
    adaptive = sweep_adaptive(48000)
    lines = report(fixed, adaptive)
    for f0 in F0S:
        for fr in (10.0, 85.3):
            for nb in (64, 512):
                w = worst_over(f441, "err_f0_db", f0, fr, nb)
                lines.append("| %d | %.1f | %d | %d | %.2f |" % (f0, fr, block_size(fr, 44100), nb, w))
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "band-model.json"), "w") as fh:
        json.dump({"fixed_48k": fixed, "fixed_44k1_subset": f441, "adaptive_48k": adaptive}, fh)
    with open(os.path.join(args.out, "band-model.md"), "w") as fh:
        fh.write("\n".join(lines) + "\n")
    if args.compare:
        with open(os.path.join(args.out, "band-model-vs-emulator.md"), "w") as fh:
            fh.write("\n".join(compare(args.compare)) + "\n")
    print("wrote", len(fixed) + len(f441) + len(adaptive), "cells")


if __name__ == "__main__":
    main()
