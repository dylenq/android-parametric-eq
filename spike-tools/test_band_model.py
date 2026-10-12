"""Unit tests for band_model (stdlib unittest). Run: python3 -I -m unittest discover -s spike-tools -p 'test_band_model.py'"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band_model as bm  # noqa: E402


class BlockSize(unittest.TestCase):
    def test_block_sizes(self):
        self.assertEqual(bm.block_size(10, 48000), 512)
        self.assertEqual(bm.block_size(85.3, 48000), 4096)
        self.assertEqual(bm.block_size(0.1, 48000), 8)
        self.assertEqual(bm.block_size(1000, 48000), 16384)


class BinStop(unittest.TestCase):
    def test_bin_stop_rounds_half_up(self):
        self.assertEqual(bm.bin_stop(1000.0, 512, 48000), int(0.5 + 1000 * 512 / 48000))
        self.assertEqual(bm.bin_stop(24000.0, 512, 48000), 256)

    def test_bands_cover_contiguous_ranges(self):
        cutoffs = bm.log_grid(32, 48000)
        ranges = bm.band_ranges(cutoffs, 512, 48000)
        prev_end = -1
        for start, stop in ranges:
            if stop >= start:
                self.assertEqual(start, prev_end + 1)
                prev_end = stop
        self.assertEqual(prev_end, 256)  # last cutoff at Nyquist covers the top bins


class Error(unittest.TestCase):
    def test_flat_target_has_zero_error(self):
        for bands in (16, 256):
            for ms in (10, 85.3):
                r = bm.evaluate(bm.log_grid(bands, 48000), ms, 48000, 1000, 8, 0.0)
                self.assertAlmostEqual(r["err_f0_db"], 0.0, places=9)
                self.assertAlmostEqual(r["worst_bin_db"], 0.0, places=9)

    def test_anchor_100hz_block512_never_within_2db(self):
        for bands in (16, 32, 64, 128, 256, 512, 1024):
            r = bm.evaluate(bm.log_grid(bands, 48000), 10, 48000, 100, 8, 12.0)
            self.assertGreater(abs(r["err_f0_db"]), 2.0, "bands=%d" % bands)

    def test_anchor_5khz_block4096_512_bands_within_1db(self):
        r = bm.evaluate(bm.log_grid(512, 48000), 85.3, 48000, 5000, 8, 12.0)
        self.assertLessEqual(abs(r["err_f0_db"]), 1.0)

    def test_error_metric_definition(self):
        r = bm.evaluate(bm.log_grid(64, 48000), 85.3, 48000, 1000, 8, 6.0)
        self.assertAlmostEqual(r["err_f0_db"], r["measured_db"] - r["target_db"], places=9)


if __name__ == "__main__":
    unittest.main()
