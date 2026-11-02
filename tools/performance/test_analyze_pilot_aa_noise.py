import copy
import hashlib
import io
import json
import tempfile
import unittest
import uuid
from contextlib import redirect_stdout
from pathlib import Path

from analyze_pilot_aa_noise import InvalidRecord, analyze_pairs, main
from test_verify_aa_pilot import observed_session, raw_bytes, raw_events, session


def make_pair(index=1, control="A0", observed=False, workload="B01"):
    make = observed_session if observed else session
    left, right = make("A", workload, control), make("B", workload, control)
    pair = str(uuid.UUID(int=index * 10 + 1))
    for offset, item in ((2, left), (4, right)):
        item["pair_id"] = pair
        item["run_id"] = str(uuid.UUID(int=index * 10 + offset))
        item["session_id"] = str(uuid.UUID(int=index * 10 + offset + 1))
    a, b = raw_bytes(left, raw_events(left)), raw_bytes(right, raw_events(right))
    return {"left": left, "right": right, "left_log": a, "right_log": b}


def refresh(row, side):
    record = row[side]
    row[side + "_log"] = raw_bytes(record, raw_events(record))


class PilotNoiseAnalysisTest(unittest.TestCase):
    def test_exact_signed_delta_and_no_formal_metrics(self):
        row = make_pair()
        for sample in row["right"]["samples"]:
            sample["thread_cpu_end_ns"] -= 5
        refresh(row, "right")
        report = analyze_pairs([row])
        pair = report["groups"][0]["pairs"][0]
        self.assertEqual({"numerator": "-5", "denominator": "3"}, pair["B_minus_A_ns_per_call"])
        self.assertEqual({"numerator": "-1", "denominator": "4"}, pair["B_minus_A_over_A"])
        self.assertEqual("not_evaluated", report["noise_qualification"])
        self.assertTrue(all(value is None for value in report["formal_metrics"].values()))

    def test_zero_sample_is_retained_and_makes_ratio_unresolved(self):
        row = make_pair()
        row["right"]["samples"][0]["thread_cpu_end_ns"] = row["right"]["samples"][0]["thread_cpu_start_ns"]
        refresh(row, "right")
        group = analyze_pairs([row])["groups"][0]
        sample = group["pairs"][0]
        self.assertEqual({"numerator": "0", "denominator": "1"}, sample["B"]["sample_ns_per_call"][0])
        self.assertEqual(1, sample["B"]["zero_samples"])
        self.assertIsNone(sample["B_minus_A_over_A"])
        self.assertEqual("zero_sample_unresolved", sample["ratio_status"])
        self.assertEqual(1, group["unresolved_ratio_count"])

    def test_drift_uses_nonoverlapping_first_and_last_five(self):
        row = make_pair()
        for side in ("left", "right"):
            record = row[side]
            record["config"]["samples"] = 10
            template = record["samples"][0]
            record["samples"] = []
            for index in range(10):
                sample = copy.deepcopy(template)
                sample.update(index=index, thread_cpu_start_ns=index * 100,
                              thread_cpu_end_ns=index * 100 + (30 if index < 5 else 60))
                record["samples"].append(sample)
            refresh(row, side)
        pair = analyze_pairs([row])["groups"][0]["pairs"][0]
        self.assertEqual({"numerator": "2", "denominator": "1"}, pair["A"]["last5_over_first5"])
        self.assertEqual("observed", pair["A"]["drift_status"])

    def test_groups_separate_control_mode_and_workload(self):
        report = analyze_pairs([make_pair(1, "A0"), make_pair(2, "A2"),
                                make_pair(3, "A0", observed=True), make_pair(4, "A0", workload="B02")])
        self.assertEqual(4, len(report["groups"]))
        self.assertTrue(all(group["pair_count"] == 1 for group in report["groups"]))
        self.assertIn("observed_pilot_data_complete", [g["pairs"][0]["source_verification"] for g in report["groups"]])

    def test_unobserved_reported_only_is_not_promoted(self):
        row = make_pair()
        for side in ("left", "right"):
            preflight = dict.fromkeys(row[side]["preflight"])
            preflight["status"] = "not_verified"
            row[side]["preflight"] = preflight
        report = analyze_pairs([row])
        self.assertEqual("reported_only", report["groups"][0]["pairs"][0]["source_verification"])
        self.assertEqual("descriptive_only", report["result"])

    def test_mixed_controls_reused_ids_and_bad_raw_are_rejected(self):
        row = make_pair(); row["right"]["control"] = "A2"; refresh(row, "right")
        with self.assertRaises(InvalidRecord): analyze_pairs([row])
        row = make_pair()
        with self.assertRaises(InvalidRecord): analyze_pairs([row, copy.deepcopy(row)])
        row = make_pair(); row["left_log"] += b"tampering"
        with self.assertRaises(InvalidRecord): analyze_pairs([row])

    def test_missing_inputs_and_size_limits_are_rejected(self):
        for pairs in ([], [{}], [make_pair()] * 257):
            with self.assertRaises(InvalidRecord): analyze_pairs(pairs)
        row = make_pair(); row["left_log"] = None
        with self.assertRaises(InvalidRecord): analyze_pairs([row])

    def test_nonfinite_number_in_unused_target_event_is_not_silently_ignored(self):
        row = make_pair()
        raw = row["left_log"].replace(b'"status": "pass"', b'"unused": NaN, "status": "pass"', 1)
        row["left_log"] = raw
        row["left"]["capture"]["raw_log_sha256"] = hashlib.sha256(raw).hexdigest()
        with self.assertRaises(InvalidRecord): analyze_pairs([row])

    def test_unknown_timeout_and_failure_case_extra_fields_are_rejected(self):
        for fields in (b'"outcome_unknown": true, ', b'"outcome_unknown": 0, ',
                       b'"timed_out": true, ', b'"failure_case": "before", '):
            row = make_pair()
            raw = row["left_log"].replace(b'"status": "pass"', fields + b'"status": "pass"', 1)
            row["left_log"] = raw
            row["left"]["capture"]["raw_log_sha256"] = hashlib.sha256(raw).hexdigest()
            with self.subTest(fields=fields), self.assertRaises(InvalidRecord): analyze_pairs([row])

    def test_cli_rejects_changed_pinned_file_without_output_artifacts(self):
        base = Path(__file__).resolve().parents[2] / "build" / "pilot-noise-tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as directory:
            root = Path(directory); row = make_pair(); pins = {}
            for key in ("left", "right", "left_log", "right_log"):
                data = row[key] if key.endswith("_log") else json.dumps(row[key]).encode()
                path = root / (key + ".dat"); path.write_bytes(data)
                pins[key] = {"path": path.name, "sha256": hashlib.sha256(data).hexdigest()}
            manifest = json.dumps({"schema_version": 1, "record_type": "pilot_aa_noise_inputs", "pairs": [pins]}).encode()
            (root / "input.json").write_bytes(manifest)
            args = ["--root", str(root), "--manifest", "input.json", "--manifest-sha256", hashlib.sha256(manifest).hexdigest()]
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(0, main(args))
            self.assertEqual("descriptive_only", json.loads(out.getvalue())["result"])
            (root / "right.dat").write_bytes(b"changed")
            with redirect_stdout(io.StringIO()) as out:
                self.assertEqual(1, main(args))
            self.assertEqual("invalid", json.loads(out.getvalue())["result"])


if __name__ == "__main__":
    unittest.main()
