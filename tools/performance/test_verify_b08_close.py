import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from verify_b08_close import InvalidRecord, main, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
SESSION = "132ef2bf-a65d-4ca5-885a-e118b487f003"
OTHER = "9755607f-1368-44a6-bb40-797613a73dee"
PID, TID = 1234, 5678
WORKER, CLOSER, POST = 5680, 5681, 5682


def events(backend="102", side="A", state="CLOSED"):
    common = {"schema": 1, "run_id": RUN, "pair_id": PAIR, "session_id": SESSION,
              "pid": PID, "side": side, "workload": "B08", "control": "A2"}
    return [
        dict(common, event="start", status="start", warmup=0, samples=1, iterations=1,
             measurement_kind="close_with_inflight_wall_ns"),
        dict(common, event="installed", status="pass", receipt_status="INSTALLED", generation=1, target_count=1),
        dict(common, event="old_entered", status="pass", before_count=1, after_count=0,
             old_original=1, new_original=0, worker_tid=WORKER, worker_completed=False, gate_released=False,
             worker_alive=True),
        dict(common, event="close_returned", status="pass", close_wall_start_ns=100, close_wall_end_ns=150,
             closer_tid=CLOSER, registration_state=state, new_callbacks_rejected=True,
             entry_restoration="RESTORED", in_flight_at_close=1, physical_release="NOT_CLAIMED",
             worker_completed=False, gate_released=False, worker_alive=True,
             before_count=1, after_count=0, old_original=1, new_original=0),
        dict(common, event="post_close", status="pass", result=42, before_count=1, after_count=0,
             old_original=1, new_original=1, post_tid=POST, worker_completed=False, gate_released=False,
             worker_alive=True),
        dict(common, event="old_completed", status="pass", result=42, before_count=1, after_count=1,
             old_original=1, new_original=1, worker_completed=True, gate_released=True,
             worker_alive=False, closer_alive=False, post_alive=False),
        dict(common, event="configured", status="pass", backend=backend, framework_name="test",
             framework_version="1", reported_api=int(backend)),
        dict(common, event="cleanup", status="pass", worker_alive=False, closer_alive=False,
             post_alive=False, cleanup_alive=False, cleanup_confirmed=True),
        dict(common, event="end", status="pass", detail="completed", cleanup_reported=True),
    ]


def log(items, pid=PID, tid=TID):
    return b"".join(("11-02 10:20:30.123 %5d %5d I MeguvizHookBench: " % (pid, tid)
                     + json.dumps(item, ensure_ascii=True) + "\n").encode("utf-8") for item in items)


def checked(raw, backend="102", side="A"):
    return verify(raw, RUN, PAIR, SESSION, side, PID, TID, backend)


class B08CloseTest(unittest.TestCase):
    def test_both_backends_sides_and_allowed_close_states(self):
        for backend in ("101", "102"):
            for side in ("A", "B"):
                for state in ("CLOSED", "RETIRED_RESIDENT"):
                    raw = b"unrelated non-target bytes \xff\n" + log(events(backend, side, state))
                    result = checked(raw, backend, side)
                    self.assertEqual("close_record_consistent", result["result"])
                    self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
                    self.assertEqual(50, result["close_wall"]["elapsed_ns"])
                    self.assertEqual(state, result["registration_state_reported"])
                    self.assertEqual("not_evaluated", result["performance_gate"])
                    self.assertEqual("not_verified", result["device_authentication"])

    def test_zero_clock_remains_unresolved_and_old_snapshot_stays_one(self):
        sample = events()
        sample[3]["close_wall_end_ns"] = sample[3]["close_wall_start_ns"]
        # Repeating the original close report is lawful; it is not a live lease query.
        sample[7]["in_flight_at_close"] = 1
        result = checked(log(sample))
        self.assertEqual("unresolved", result["result"])
        self.assertEqual(0, result["close_wall"]["elapsed_ns"])
        self.assertNotIn("cpu", result)

    def test_close_report_and_held_gate_cannot_claim_success_in_wrong_state(self):
        mutations = ((3, "registration_state", "RETIRING"), (3, "entry_restoration", "UNKNOWN"),
                     (3, "new_callbacks_rejected", False), (3, "in_flight_at_close", 0),
                     (3, "gate_released", True), (3, "worker_completed", True),
                     (4, "worker_completed", True), (4, "gate_released", True),
                     (5, "worker_completed", False), (5, "gate_released", False),
                     (2, "worker_alive", False), (3, "worker_alive", False), (4, "worker_alive", False),
                     (5, "worker_alive", True), (5, "closer_alive", True), (5, "post_alive", True))
        for index, key, value in mutations:
            sample = events(); sample[index][key] = value
            with self.subTest(index=index, key=key), self.assertRaises(InvalidRecord):
                checked(log(sample))

    def test_counts_and_results_detect_replay_missing_after_or_new_sdk_entry(self):
        for index, key, value in ((2, "before_count", 0), (2, "old_original", 2),
                                  (3, "before_count", 2), (3, "after_count", 1),
                                  (3, "old_original", 2), (3, "new_original", 1),
                                  (4, "before_count", 2), (4, "after_count", 1),
                                  (4, "new_original", 0), (4, "result", 43),
                                  (5, "old_original", 2), (5, "after_count", 0), (5, "result", 43)):
            sample = events(); sample[index][key] = value
            with self.subTest(index=index, key=key), self.assertRaises(InvalidRecord):
                checked(log(sample))

    def test_threads_must_not_be_the_controller_or_still_held_worker(self):
        for index, key, value in ((2, "worker_tid", TID), (3, "closer_tid", TID),
                                  (3, "closer_tid", WORKER), (4, "post_tid", TID),
                                  (4, "post_tid", WORKER), (2, "worker_tid", True)):
            sample = events(); sample[index][key] = value
            with self.subTest(index=index, key=key), self.assertRaises(InvalidRecord):
                checked(log(sample))
        sample = events(); sample[4]["post_tid"] = CLOSER
        self.assertEqual("close_record_consistent", checked(log(sample))["result"])

    def test_cleanup_alive_unconfirmed_or_unknown_outcome_never_passes(self):
        for key in ("worker_alive", "closer_alive", "post_alive", "cleanup_alive"):
            sample = events(); sample[7][key] = True
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample))
        for index, key in ((7, "cleanup_confirmed"), (8, "cleanup_reported")):
            sample = events(); sample[index][key] = False
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample))
        for value in (True, 0, 1, "false", None):
            sample = events(); sample[3]["outcome_unknown"] = value
            with self.subTest(value=value), self.assertRaises(InvalidRecord): checked(log(sample))
        sample = events(); sample[3]["timed_out"] = True
        with self.assertRaises(InvalidRecord): checked(log(sample))

    def test_missing_events_or_fields_are_incomplete_and_failures_retained(self):
        for index in range(9):
            sample = events(); del sample[index]
            self.assertEqual("incomplete", checked(log(sample))["result"])
        for index in range(1, 9):
            sample = events(); sample[index]["status"] = "fail"
            self.assertEqual("incomplete", checked(log(sample))["result"])
        for index, key in ((1, "generation"), (2, "worker_tid"), (3, "closer_tid"),
                           (3, "close_wall_end_ns"), (4, "post_tid"), (7, "cleanup_alive")):
            sample = events(); del sample[index][key]
            self.assertEqual("incomplete", checked(log(sample))["result"])

    def test_raw_invalid_or_partial_clocks_are_not_discarded(self):
        for key, value in (("close_wall_start_ns", -1), ("close_wall_end_ns", 99),
                           ("close_wall_start_ns", True), ("close_wall_end_ns", 1.0),
                           ("close_wall_end_ns", 2**63)):
            sample = events(); sample[3].update(status="fail"); sample[3][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord) as raised:
                checked(log(sample))
            observed = raised.exception.partial_report["observations"][3]
            self.assertTrue(observed["presence"][key])
            if type(value) in (int, bool):
                self.assertIn(key, observed["values"])
        sample = events(); sample[3].update(status="fail"); del sample[3]["close_wall_end_ns"]
        result = checked(log(sample)); observed = result["observations"][3]
        self.assertEqual("incomplete", result["result"])
        self.assertFalse(observed["presence"]["close_wall_end_ns"])
        self.assertEqual(100, observed["values"]["close_wall_start_ns"])
        self.assertNotIn("close_wall_elapsed_ns", observed)

    def test_later_failure_retains_prior_clock_and_does_not_echo_unknown_data(self):
        sample = events(); sample[3]["unknown_secret"] = "not echoed"; sample[5]["after_count"] = 0
        raw = log(sample)
        with self.assertRaises(InvalidRecord) as raised: checked(raw)
        partial = raised.exception.partial_report
        self.assertEqual(hashlib.sha256(raw).hexdigest(), partial["raw_log_sha256"])
        self.assertEqual(50, partial["observations"][3]["close_wall_elapsed_ns"])
        self.assertNotIn("not echoed", json.dumps(partial))

    def test_failed_close_retains_only_defined_report_enums(self):
        sample = events()
        sample[3].update(status="fail", registration_state="RETIRING",
                         entry_restoration="UNKNOWN", physical_release="UNKNOWN")
        result = checked(log(sample))
        self.assertEqual("incomplete", result["result"])
        values = result["observations"][3]["values"]
        self.assertEqual("RETIRING", values["registration_state"])
        self.assertEqual("UNKNOWN", values["entry_restoration"])
        self.assertEqual("UNKNOWN", values["physical_release"])
        self.assertEqual(1, values["in_flight_at_close"])
        for key in ("registration_state", "entry_restoration", "physical_release"):
            damaged = copy.deepcopy(sample); damaged[3][key] = "arbitrary secret"
            report = checked(log(damaged))
            self.assertTrue(report["observations"][3]["presence"][key])
            self.assertNotIn(key, report["observations"][3]["values"])
            self.assertNotIn("arbitrary secret", json.dumps(report))

    def test_duplicate_order_and_changed_observations_are_invalid(self):
        samples = []
        value = events(); value.insert(4, copy.deepcopy(value[3])); samples.append(value)
        value = events(); value[2], value[3] = value[3], value[2]; samples.append(value)
        value = events(); value[4]["worker_tid"] = WORKER + 99; samples.append(value)
        value = events(); value[5]["close_wall_end_ns"] = 151; samples.append(value)
        for value in samples:
            with self.assertRaises(InvalidRecord): checked(log(value))

    def test_explicit_identities_and_threadtime_headers_are_bound(self):
        for key, value in (("pair_id", OTHER), ("session_id", OTHER), ("pid", PID + 1),
                           ("workload", "B07"), ("control", "A0"), ("schema", True)):
            sample = events(); sample[3][key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample))
        with self.assertRaises(InvalidRecord): checked(log(events(), tid=TID + 1))
        with self.assertRaises(InvalidRecord): verify(log(events()), RUN, RUN, SESSION, "A", PID, TID, "102")
        sample = events(); sample[3]["run_id"] = OTHER
        with self.assertRaises(InvalidRecord): checked(log(sample))

    def test_bad_json_nonfinite_and_invalid_utf8_fail_closed(self):
        raw = log(events()).replace(b'"generation": 1', b'"generation": 1, "generation": 1', 1)
        with self.assertRaises(InvalidRecord): checked(raw)
        sample = events(); sample[3]["unknown"] = float("nan")
        with self.assertRaises(InvalidRecord): checked(log(sample))
        with self.assertRaises(InvalidRecord): checked(log(events()).replace(b'MeguvizHookBench', b'MeguvizHookBench\xff', 1))

    def test_physical_release_is_only_a_reported_enum(self):
        for value in ("RELEASED", "NOT_CLAIMED", "UNKNOWN"):
            sample = events(); sample[3]["physical_release"] = value
            result = checked(log(sample))
            self.assertEqual(value, result["physical_release_reported"])
            self.assertEqual("not_verified", result["device_authentication"])
        sample = events(); sample[3]["physical_release"] = "guaranteed"
        with self.assertRaises(InvalidRecord): checked(log(sample))

    def test_cli_actual_file_success_zero_invalid_and_preserved_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.log"
            argv = ["verify_b08_close.py", str(path), "--run-id", RUN, "--pair-id", PAIR,
                    "--session-id", SESSION, "--side", "A", "--pid", str(PID), "--tid", str(TID),
                    "--backend", "102"]
            for mode, code, status in (("pass", 0, "close_record_consistent"),
                                       ("zero", 2, "unresolved"), ("fail", 2, "incomplete"),
                                       ("invalid", 1, "invalid")):
                sample = events()
                if mode == "zero": sample[3]["close_wall_end_ns"] = 100
                if mode == "fail": sample[7]["status"] = "fail"
                if mode == "invalid": sample[5]["after_count"] = 0
                raw = log(sample); path.write_bytes(raw); output = io.StringIO()
                with patch("sys.argv", argv), redirect_stdout(output): actual = main()
                result = json.loads(output.getvalue())
                self.assertEqual(code, actual); self.assertEqual(status, result["result"])
                self.assertEqual(raw, path.read_bytes())
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
                if mode == "invalid": self.assertEqual(50, result["observations"][3]["close_wall_elapsed_ns"])


if __name__ == "__main__":
    unittest.main()
