import copy
import hashlib
import unittest

from analyze_cpu_body_gaps import _summary, analyze
from test_verify_cpu_body_pair import make_pair, raw_for
from test_verify_cpu_body_probe import events
from verify_cpu_body_probe import InvalidRecord


def pair_for(workload="B01", warmup=1, samples=2):
    left, right, _, _ = make_pair()
    left.update(workload=workload, warmup=warmup, samples=samples)
    right.update(workload=workload, warmup=warmup, samples=samples)
    rows = events(workload, warmup, samples)
    a, b = raw_for("A", rows), raw_for("B", rows)
    left["raw_log_sha256"] = hashlib.sha256(a).hexdigest()
    right["raw_log_sha256"] = hashlib.sha256(b).hexdigest()
    return left, right, a, b


class CpuBodyGapTest(unittest.TestCase):
    def test_b01_uses_after_end_and_excludes_warmup_boundary(self):
        left, right, _, raw_b = pair_for()
        rows = events("B01", 1, 2)
        rows[2].update(before_cpu_start_ns=5000, before_cpu_end_ns=5010,
                       after_cpu_start_ns=5020, after_cpu_end_ns=5030)
        rows[3].update(before_cpu_start_ns=5100, before_cpu_end_ns=5110,
                       after_cpu_start_ns=5120, after_cpu_end_ns=5130)
        raw_a = raw_for("A", rows)
        left["raw_log_sha256"] = hashlib.sha256(raw_a).hexdigest()
        saved = copy.deepcopy((left, right, raw_a, raw_b))
        result = analyze(left, right, raw_a, raw_b)
        self.assertEqual("gaps_described", result["result"])
        gap = result["sides"]["A"]["gaps"]
        self.assertEqual([{"from_ordinal": 1, "to_ordinal": 2, "prior_phase": "after",
                           "prior_end_ns": 5030, "next_before_start_ns": 5100, "gap_ns": 70}], gap)
        self.assertEqual({"numerator": "70", "denominator": 1}, result["sides"]["A"]["summary"]["median_ns"])
        self.assertEqual("not_evaluated", result["performance_gate"])
        self.assertIn("outside this gap", result["controlled_proceed_and_original"])
        self.assertEqual(saved, (left, right, raw_a, raw_b))

    def test_b02_uses_before_end_without_after(self):
        left, right, raw_a, raw_b = pair_for("B02")
        result = analyze(right, left, raw_b, raw_a)
        self.assertEqual("gaps_described", result["result"])
        self.assertEqual("before", result["sides"]["A"]["gaps"][0]["prior_phase"])
        self.assertEqual(90, result["sides"]["A"]["gaps"][0]["gap_ns"])
        self.assertIn("inside this gap", result["controlled_proceed_and_original"])

    def test_tampered_logs_or_mismatched_pair_are_rejected(self):
        left, right, raw_a, raw_b = pair_for()
        with self.assertRaises(InvalidRecord):
            analyze(left, right, raw_a + b"tamper", raw_b)
        right["control"] = "A2"
        with self.assertRaises(InvalidRecord):
            analyze(left, right, raw_a, raw_b)

    def test_single_sample_is_unresolved_not_zero_gap(self):
        left, right, raw_a, raw_b = pair_for("B02", 0, 1)
        result = analyze(left, right, raw_a, raw_b)
        self.assertEqual("unresolved", result["result"])
        self.assertEqual([], result["sides"]["A"]["gaps"])
        self.assertIsNone(result["sides"]["A"]["summary"]["median_ns"])

    def test_unresolved_source_is_not_upgraded_to_success(self):
        left, right, _, raw_b = pair_for("B02")
        rows = events("B02", 1, 2)
        for event in rows[2:-1]:
            event["before_cpu_end_ns"] = event["before_cpu_start_ns"]
        raw_a = raw_for("A", rows)
        left["raw_log_sha256"] = hashlib.sha256(raw_a).hexdigest()
        result = analyze(left, right, raw_a, raw_b)
        self.assertEqual("unresolved", result["result"])
        self.assertEqual({}, result["sides"])

    def test_even_median_remains_exact_for_large_integer_gaps(self):
        a, b = 2**63 - 3, 2**63 - 2
        result = _summary([{"gap_ns": b}, {"gap_ns": a}])
        self.assertEqual({"numerator": str(a + b), "denominator": 2}, result["median_ns"])


if __name__ == "__main__":
    unittest.main()
