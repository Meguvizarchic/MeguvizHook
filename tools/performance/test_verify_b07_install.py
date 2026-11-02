import copy
import hashlib
import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from verify_b07_install import InvalidRecord, main, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
SESSION = "132ef2bf-a65d-4ca5-885a-e118b487f003"
OTHER = "9755607f-1368-44a6-bb40-797613a73dee"
PID, TID = 1234, 5678


def events(backend="102"):
    common = {"schema": 1, "run_id": RUN, "pair_id": PAIR, "session_id": SESSION,
              "pid": PID, "side": "A", "workload": "B07", "control": "A2"}
    return [
        dict(common, event="start", status="start", warmup=0, samples=1, iterations=1,
             measurement_kind="install_wall_and_thread_cpu_ns"),
        dict(common, event="install", status="pass", wall_start_ns=100, cpu_start_ns=10,
             cpu_end_ns=30, wall_end_ns=150, receipt_status="INSTALLED", generation=1, target_count=1),
        dict(common, event="hook_check", status="pass", result=42, before_count=1, original_count=1),
        dict(common, event="configured", status="pass", backend=backend, framework_name="test",
             framework_version="1", reported_api=int(backend)),
        dict(common, event="cleanup", status="pass", entry_restoration="RESTORED",
             new_callbacks_rejected=True, in_flight=0, physical_release="NOT_CLAIMED"),
        dict(common, event="post_close", status="pass", result=42, before_count=1, original_count=2),
        dict(common, event="end", status="pass", detail="completed", cleanup_reported=True),
    ]


def log(items, pid=PID, tid=TID):
    return b"".join(("11-01 10:20:30.123 %5d %5d I MeguvizHookBench: " % (pid, tid)
                     + json.dumps(item, ensure_ascii=True) + "\n").encode("utf-8") for item in items)


def checked(raw, backend="102"):
    return verify(raw, RUN, PAIR, SESSION, "A", PID, TID, backend)


class B07InstallTest(unittest.TestCase):
    def test_complete_single_install_and_raw_hash(self):
        for backend in ("101", "102"):
            raw = b"unrelated log \xff\n" + log(events(backend))
            result = checked(raw, backend)
            self.assertEqual("install_record_consistent", result["result"])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
            self.assertEqual({"start_ns": 100, "end_ns": 150, "elapsed_ns": 50, "status": "observed"}, result["wall"])
            self.assertEqual({"start_ns": 10, "end_ns": 30, "elapsed_ns": 20, "status": "observed"}, result["cpu"])
            self.assertEqual("not_evaluated", result["performance_gate"])
            self.assertEqual("not_verified", result["device_authentication"])

    def test_zero_interval_unresolved_with_other_clock_retained(self):
        for clock in ("wall", "cpu"):
            sample = events()
            sample[1][clock + "_end_ns"] = sample[1][clock + "_start_ns"]
            result = checked(log(sample))
            self.assertEqual("unresolved", result["result"])
            self.assertEqual(0, result[clock]["elapsed_ns"])
            self.assertEqual("observed", result["cpu" if clock == "wall" else "wall"]["status"])

    def test_negative_backward_and_non_integer_clocks_are_invalid(self):
        for key, value in (("cpu_start_ns", -1), ("cpu_end_ns", 9), ("wall_end_ns", 99),
                           ("wall_start_ns", True), ("cpu_end_ns", 1.0), ("cpu_end_ns", 2**63)):
            sample = events()
            sample[1][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord):
                checked(log(sample))

    def test_missing_or_reported_failure_is_incomplete(self):
        for index in range(7):
            sample = events()
            del sample[index]
            self.assertEqual("incomplete", checked(log(sample))["result"])
        for index in range(1, 7):
            sample = events()
            sample[index] = {**sample[index], "status": "fail", "detail": "failed"}
            self.assertEqual("incomplete", checked(log(sample))["result"])
        sample = events()
        del sample[1]["cpu_start_ns"]
        self.assertEqual("incomplete", checked(log(sample))["result"])

    def test_failure_does_not_hide_bad_clock(self):
        sample = events()
        sample[1].update(status="fail", cpu_start_ns=-1)
        with self.assertRaises(InvalidRecord) as raised:
            checked(log(sample))
        row = raised.exception.partial_report["install_observations"][0]
        self.assertTrue(row["presence"]["cpu_start_ns"])
        self.assertEqual(-1, row["endpoints_ns"]["cpu_start_ns"])
        self.assertEqual(50, row["wall_elapsed_ns"])
        self.assertNotIn("cpu_elapsed_ns", row)

    def test_success_cannot_report_unknown_install_outcome(self):
        sample = events()
        sample[1]["outcome_unknown"] = False
        self.assertEqual("install_record_consistent", checked(log(sample))["result"])
        for value in (True, 0, 1, "false", None):
            sample = events()
            sample[1]["outcome_unknown"] = value
            with self.subTest(value=value), self.assertRaises(InvalidRecord) as raised:
                checked(log(sample))
            row = raised.exception.partial_report["install_observations"][0]
            self.assertEqual(100, row["endpoints_ns"]["wall_start_ns"])
            self.assertEqual(30, row["endpoints_ns"]["cpu_end_ns"])

        sample = events()
        sample[1].update(status="fail", outcome_unknown=True)
        result = checked(log(sample))
        self.assertEqual("incomplete", result["result"])
        self.assertEqual(50, result["install_observations"][0]["wall_elapsed_ns"])

    def test_failure_with_partial_endpoints_retains_only_known_values(self):
        sample = events()
        sample[1].update(status="fail", secret_unknown="do not echo")
        del sample[1]["cpu_end_ns"]
        result = checked(log(sample))
        self.assertEqual("incomplete", result["result"])
        row = result["install_observations"][0]
        self.assertFalse(row["presence"]["cpu_end_ns"])
        self.assertEqual(10, row["endpoints_ns"]["cpu_start_ns"])
        self.assertNotIn("cpu_elapsed_ns", row)
        self.assertEqual(50, row["wall_elapsed_ns"])
        self.assertNotIn("secret_unknown", str(result))

    def test_backward_and_later_contradiction_keep_endpoints(self):
        for kind in ("backward", "later"):
            sample = events()
            if kind == "backward":
                sample[1]["wall_end_ns"] = 99
            else:
                sample[5]["original_count"] = 1
            with self.subTest(kind=kind), self.assertRaises(InvalidRecord) as raised:
                checked(log(sample))
            row = raised.exception.partial_report["install_observations"][0]
            self.assertEqual(100, row["endpoints_ns"]["wall_start_ns"])
            self.assertEqual(99 if kind == "backward" else 150, row["endpoints_ns"]["wall_end_ns"])
            self.assertEqual(20, row["cpu_elapsed_ns"])
            if kind == "backward":
                self.assertNotIn("wall_elapsed_ns", row)
            else:
                self.assertEqual(50, row["wall_elapsed_ns"])

    def test_invalid_cli_includes_partial_endpoints_and_original_hash(self):
        sample = events()
        sample[4]["in_flight"] = 1
        raw = log(sample)
        argv = ["verify_b07_install.py", "ignored.log", "--run-id", RUN, "--pair-id", PAIR,
                "--session-id", SESSION, "--side", "A", "--pid", str(PID), "--tid", str(TID),
                "--backend", "102"]
        output = io.StringIO()
        with patch("sys.argv", argv), patch("pathlib.Path.read_bytes", return_value=raw), redirect_stdout(output):
            self.assertEqual(1, main())
        result = json.loads(output.getvalue())
        self.assertEqual("invalid", result["result"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
        self.assertEqual(150, result["install_observations"][0]["endpoints_ns"]["wall_end_ns"])

    def test_late_parse_and_identity_failure_keep_prior_matched_endpoints(self):
        base = log(events()[:2])
        malformed = b'11-01 10:20:30.123 1234 5678 I MeguvizHookBench: {broken}\n'
        wrong = events()[2]
        wrong["side"] = "B"
        for raw in (base + malformed, base + log([wrong])):
            with self.subTest(raw=raw[-80:]), self.assertRaises(InvalidRecord) as raised:
                checked(raw)
            rows = raised.exception.partial_report["install_observations"]
            self.assertEqual(1, len(rows))
            self.assertEqual(30, rows[0]["endpoints_ns"]["cpu_end_ns"])

    def test_unmatched_or_duplicate_installs_never_get_combined(self):
        sample = events()
        duplicate = copy.deepcopy(sample[1])
        duplicate["cpu_start_ns"] = 777
        sample.insert(2, duplicate)
        with self.assertRaises(InvalidRecord) as raised:
            checked(log(sample))
        rows = raised.exception.partial_report["install_observations"]
        self.assertEqual([10, 777], [r["endpoints_ns"]["cpu_start_ns"] for r in rows])
        self.assertNotIn("cpu_elapsed_ns", rows[1])
        wrong_pid = log(events(), PID + 1, TID)
        with self.assertRaises(InvalidRecord) as raised:
            checked(wrong_pid)
        self.assertEqual([], raised.exception.partial_report["install_observations"])

    def test_duplicate_reordered_and_extra_events_are_invalid(self):
        for variant in ("duplicate", "reordered", "after_end", "unknown"):
            sample = events()
            if variant == "duplicate":
                sample.insert(2, copy.deepcopy(sample[1]))
            elif variant == "reordered":
                sample[1], sample[2] = sample[2], sample[1]
            elif variant == "after_end":
                sample.append(copy.deepcopy(sample[-1]))
            else:
                sample[2]["event"] = "sample"
            with self.subTest(variant=variant), self.assertRaises(InvalidRecord):
                checked(log(sample))

    def test_false_receipts_and_close_observations_are_invalid(self):
        cases = ((1, "receipt_status", "FAILED"), (1, "generation", 0), (1, "target_count", 2),
                 (2, "before_count", 0), (2, "original_count", 2), (3, "backend", "101"),
                 (4, "new_callbacks_rejected", False), (4, "in_flight", 1),
                 (4, "physical_release", "CERTIFIED"), (5, "before_count", 2),
                 (5, "original_count", 1), (6, "cleanup_reported", False))
        for index, field, value in cases:
            sample = events()
            sample[index][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(InvalidRecord):
                checked(log(sample))

    def test_threadtime_identity_and_declared_ids_must_agree(self):
        for pid, tid in ((PID + 1, TID), (PID, TID + 1)):
            with self.subTest(pid=pid, tid=tid), self.assertRaises(InvalidRecord):
                checked(log(events(), pid, tid))
        for field, value in (("schema", True), ("pid", PID + 1), ("side", "B"),
                             ("workload", "B01"), ("control", "A0"), ("pair_id", OTHER)):
            sample = events()
            sample[2][field] = value
            with self.subTest(field=field), self.assertRaises(InvalidRecord):
                checked(log(sample))
        sample = events()
        del sample[2]["session_id"]
        self.assertEqual("incomplete", checked(log(sample))["result"])

    def test_same_pid_another_run_is_not_ignored(self):
        sample = copy.deepcopy(events()[2])
        sample["run_id"] = OTHER
        with self.assertRaises(InvalidRecord):
            checked(log(events() + [sample]))
        sample["pid"] = 9999
        self.assertEqual("install_record_consistent", checked(log(events()) + log([sample], 9999, 8888))["result"])

    def test_duplicate_keys_malformed_utf8_and_nonfinite_are_invalid(self):
        raw = log(events())
        for damaged in (raw.replace(b'"schema": 1', b'"schema": 1, "schema": 1', 1),
                        raw.replace(b'"INSTALLED"', b'"INSTALL\xffED"', 1),
                        raw.replace(b'"generation": 1', b'"generation": NaN', 1)):
            with self.subTest(damaged=damaged[-100:]), self.assertRaises(InvalidRecord):
                checked(damaged)

    def test_additional_fields_are_tolerated_without_attestation(self):
        sample = events()
        sample[1]["extra_source"] = {"arbitrary": "unverified"}
        result = checked(log(sample))
        self.assertEqual("install_record_consistent", result["result"])
        self.assertNotIn("extra_source", result)

    def test_all_physical_release_reports_are_accepted_without_attestation(self):
        for value in ("RELEASED", "NOT_CLAIMED", "UNKNOWN"):
            sample = events()
            sample[4]["physical_release"] = value
            result = checked(log(sample))
            self.assertEqual("install_record_consistent", result["result"])
            self.assertEqual(value, result["physical_release_reported"])
            self.assertEqual("not_verified", result["device_authentication"])


if __name__ == "__main__":
    unittest.main()
