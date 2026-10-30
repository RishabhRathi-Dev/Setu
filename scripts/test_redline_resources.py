import unittest

import redline_resources as resources


class RedlineResourcesTests(unittest.TestCase):
    def test_full_host_plan_scales_beyond_old_caps_and_leaves_native_headroom(self):
        available = 16 * 1024**3
        budget = resources.local_budget(24, available)
        self.assertEqual(24, budget['active_processors'])
        self.assertEqual(available, budget['memory_available_bytes'])
        self.assertGreater(budget['heap_mib'], 512)
        self.assertGreater(budget['direct_mib'], 128)
        self.assertLessEqual(budget['heap_mib'] + budget['direct_mib'], (available // resources.MIB - 768) * .75)
        self.assertLessEqual(budget['memory_log_bytes'], 2**31 - 1)
        self.assertGreater(budget['request_buffer_bytes'], 128 * resources.MIB)
        self.assertEqual(4 * resources.MIB, budget['fetch_bytes'])

    def test_small_host_rejects_insufficient_memory_and_keeps_frames_admissible(self):
        with self.assertRaises(ValueError):
            resources.local_budget(2, 1024 * resources.MIB)
        with self.assertRaises(ValueError):
            resources.local_budget(0, 8 * 1024**3)
        budget = resources.local_budget(2, 1152 * resources.MIB)
        self.assertGreaterEqual(budget['request_buffer_bytes'], 2 * 15 * resources.MIB + 1024)
        self.assertEqual(4096 * 256, resources.transport(128, 256)['fetch_bytes'])
