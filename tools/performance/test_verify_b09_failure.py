import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from verify_b09_failure import InvalidRecord, main, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
SESSION = "132ef2bf-a65d-4ca5-885a-e118b487f003"
OTHER = "9755607f-1368-44a6-bb40-797613a73dee"
PID, TID = 1234, 5678


def events(case="before", control="A2", backend="102"):
    common = {"schema": 1, "run_id": RUN, "pair_id": PAIR, "session_id": SESSION,
              "side": "A", "pid": PID, "workload": "B09", "control": control, "failure_case": case}
    before, after, original = (1, 0, 0) if case == "before" else (1, 1, 1)
    installed = {"install_source": "framework_handle", "handle_returned": True} if control == "A0" else {
        "install_source": "sdk_receipt", "receipt_status": "INSTALLED", "generation": 1, "target_count": 1}
    call = {"outcome": "threw", "identity_preserved": True, "before_count": before,
            "after_count": after, "original_count": original, "wall_start_ns": 100,
            "cpu_start_ns": 10, "cpu_end_ns": 30, "wall_end_ns": 150,
            "after_outcome": {"before": "not_called", "after": "returned", "original": "threw"}[case]}
    if case == "after": call["after_result"] = 42
    if case == "original": call["after_identity_preserved"] = True
    cleanup = {"close_source": "framework_unhook_contract", "entry_restoration": "RESTORED",
               "physical_release": "NOT_CLAIMED"} if control == "A0" else {
        "close_source": "sdk_close_report", "entry_restoration": "RESTORED", "physical_release": "NOT_CLAIMED",
        "new_callbacks_rejected": True, "in_flight_at_close": 0, "registration_state": "CLOSED"}
    post = {"before_count": before, "after_count": after, "original_count": original + 1}
    if case == "original": post.update(outcome="threw", identity_preserved=True)
    else: post.update(outcome="returned", result=42)
    return [dict(common, event="start", status="start", warmup=0, samples=1, iterations=1,
                 measurement_kind="failure_call_wall_and_thread_cpu_ns"),
            dict(common, event="installed", status="pass", **installed),
            dict(common, event="failure_call", status="pass", **call),
            dict(common, event="configured", status="pass", backend=backend,
                 framework_name="test", framework_version="1", reported_api=int(backend)),
            dict(common, event="cleanup", status="pass", **cleanup),
            dict(common, event="post_close", status="pass", **post),
            dict(common, event="end", status="pass", detail="completed", cleanup_reported=True)]


def log(items, pid=PID, tid=TID):
    return b"".join(("11-02 10:20:30.123 %5d %5d I MeguvizHookBench: " % (pid, tid)
                     + json.dumps(item, ensure_ascii=True) + "\n").encode("utf-8") for item in items)


def checked(raw, case="before", control="A2", backend="102"):
    return verify(raw, RUN, PAIR, SESSION, "A", PID, TID, backend, control, case)


class B09FailureTest(unittest.TestCase):
    def test_complete_three_cases_controls_and_generations(self):
        for case in ("before", "after", "original"):
            for control in ("A0", "A2"):
                for backend in ("101", "102"):
                    with self.subTest(case=case, control=control, backend=backend):
                        raw = b"unrelated \xff\n" + log(events(case, control, backend))
                        result = checked(raw, case, control, backend)
                        self.assertEqual("failure_record_consistent", result["result"])
                        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
                        self.assertEqual(50, result["wall"]["elapsed_ns"])
                        self.assertEqual(20, result["cpu"]["elapsed_ns"])
                        self.assertEqual("not_evaluated", result["performance_gate"])
                        self.assertEqual("not_verified", result["device_authentication"])

    def test_expected_exception_is_pass_but_reported_diagnostic_failure_is_incomplete(self):
        sample = events("after")
        self.assertEqual("failure_record_consistent", checked(log(sample), "after")["result"])
        sample[2].update(status="fail", identity_preserved=False)
        result = checked(log(sample), "after")
        self.assertEqual("incomplete", result["result"])
        self.assertEqual(False, result["observations"][2]["values"]["identity_preserved"])
        self.assertEqual(20, result["cpu"]["elapsed_ns"])

    def test_after_observation_must_match_the_case(self):
        for case, key, value in (("before", "after_outcome", "returned"),
                                 ("after", "after_outcome", "not_called"),
                                 ("after", "after_result", 41),
                                 ("original", "after_outcome", "returned"),
                                 ("original", "after_identity_preserved", False)):
            sample = events(case); sample[2][key] = value
            with self.subTest(case=case, key=key), self.assertRaises(InvalidRecord) as raised:
                checked(log(sample), case)
            self.assertEqual(100, raised.exception.partial_report["observations"][2]["values"]["wall_start_ns"])

    def test_inapplicable_success_results_cannot_hide_exception_paths(self):
        for case, index, fields in (("before", 2, {"result": 42}),
                                    ("before", 2, {"after_result": 42}),
                                    ("after", 2, {"after_identity_preserved": True}),
                                    ("original", 2, {"after_result": 42}),
                                    ("original", 5, {"result": 42}),
                                    ("after", 5, {"identity_preserved": True})):
            sample = events(case); sample[index].update(fields)
            with self.subTest(case=case, index=index), self.assertRaises(InvalidRecord):
                checked(log(sample), case)

    def test_callback_or_original_count_mismatch_and_replay_are_invalid(self):
        for case, index, key, value in (("before", 2, "original_count", 1),
                                      ("before", 2, "after_count", 1),
                                      ("after", 2, "original_count", 2),
                                      ("original", 2, "after_count", 0),
                                      ("after", 5, "before_count", 2),
                                      ("original", 5, "original_count", 1)):
            sample = events(case); sample[index][key] = value
            with self.subTest(case=case, index=index, key=key), self.assertRaises(InvalidRecord):
                checked(log(sample), case)

    def test_post_close_original_still_throws_and_other_cases_return(self):
        for case in ("before", "after", "original"):
            sample = events(case)
            sample[5]["outcome"] = "returned" if case == "original" else "threw"
            with self.subTest(case=case), self.assertRaises(InvalidRecord): checked(log(sample), case)
        sample = events("original"); sample[5]["identity_preserved"] = False
        with self.assertRaises(InvalidRecord): checked(log(sample), "original")

    def test_a0_never_accepts_sdk_only_proof_fields(self):
        for key, value in (("generation", 1), ("receipt_status", "INSTALLED"),
                           ("new_callbacks_rejected", True), ("in_flight_at_close", 0),
                           ("registration_state", "CLOSED")):
            sample = events(control="A0"); sample[4][key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample), control="A0")
        for index, key, value in ((1, "install_source", "sdk_receipt"),
                                  (1, "target_count", 2),
                                  (4, "close_source", "sdk_close_report"),
                                  (4, "physical_release", "RELEASED")):
            sample = events(control="A0"); sample[index][key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample), control="A0")

    def test_a2_requires_real_sdk_receipt_and_close_fields(self):
        for index, key, value in ((1, "receipt_status", "ALREADY_INSTALLED"), (1, "generation", 0),
                                  (1, "target_count", 2), (4, "new_callbacks_rejected", False),
                                  (4, "in_flight_at_close", 1), (4, "registration_state", "RETIRING"),
                                  (4, "entry_restoration", "UNKNOWN")):
            sample = events(); sample[index][key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): checked(log(sample))
        for state in ("CLOSED", "RETIRED_RESIDENT"):
            for physical in ("RELEASED", "NOT_CLAIMED", "UNKNOWN"):
                sample = events(); sample[4].update(registration_state=state, physical_release=physical)
                self.assertEqual("failure_record_consistent", checked(log(sample))["result"])

    def test_zero_intervals_stay_unresolved_and_other_clock_retained(self):
        for clock in ("wall", "cpu"):
            sample = events(); sample[2][clock + "_end_ns"] = sample[2][clock + "_start_ns"]
            result = checked(log(sample))
            self.assertEqual("unresolved", result["result"])
            self.assertEqual(0, result[clock]["elapsed_ns"])
            self.assertEqual("observed", result["cpu" if clock == "wall" else "wall"]["status"])

    def test_bad_clock_even_on_failure_keeps_partial_values(self):
        for key, value in (("wall_start_ns", -1), ("cpu_end_ns", 9),
                           ("wall_end_ns", 2**63), ("cpu_start_ns", True), ("cpu_end_ns", 1.0)):
            sample = events(); sample[2].update(status="fail"); sample[2][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord) as raised:
                checked(log(sample))
            row = raised.exception.partial_report["observations"][2]
            self.assertTrue(row["presence"][key])
            self.assertEqual(hashlib.sha256(log(sample)).hexdigest(), raised.exception.partial_report["raw_log_sha256"])

    def test_missing_fields_events_or_failed_stages_are_incomplete(self):
        for index in range(7):
            sample = events(); del sample[index]
            self.assertEqual("incomplete", checked(log(sample))["result"])
        for index in range(1, 7):
            sample = events(); sample[index]["status"] = "fail"
            self.assertEqual("incomplete", checked(log(sample))["result"])
        for case, key in (("after", "after_result"), ("original", "after_identity_preserved"),
                          ("before", "cpu_end_ns")):
            sample = events(case); del sample[2][key]
            self.assertEqual("incomplete", checked(log(sample), case)["result"])

    def test_duplicate_out_of_order_and_conflicting_common_identity_are_invalid(self):
        for modify in (lambda x: x.insert(3, copy.deepcopy(x[2])),
                       lambda x: x.__setitem__(slice(2, 4), [x[3], x[2]]),
                       lambda x: x[4].update(run_id=OTHER),
                       lambda x: x[3].update(failure_case="after"),
                       lambda x: x[3].update(control="A0"),
                       lambda x: x[3].update(pid=PID + 1),
                       lambda x: x[3].update(backend="101")):
            sample = events(); modify(sample)
            with self.assertRaises(InvalidRecord): checked(log(sample))
        for pid, tid in ((PID + 1, TID), (PID, TID + 1)):
            with self.assertRaises(InvalidRecord): checked(log(events(), pid, tid))
        with self.assertRaises(InvalidRecord):
            verify(log(events()), RUN, RUN, SESSION, "A", PID, TID, "102", "A2", "before")

    def test_success_cannot_claim_unknown_or_timeout_or_unconfirmed_cleanup(self):
        for key, values in (("outcome_unknown", (True, 0, "false")),
                            ("timed_out", (True, None)), ("cleanup_confirmed", (False, 1))):
            for value in values:
                sample = events(); sample[4][key] = value
                with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord): checked(log(sample))
        sample = events()
        for event in sample[:4]: event["cleanup_confirmed"] = False
        self.assertEqual("failure_record_consistent", checked(log(sample))["result"])

    def test_failure_partial_keeps_known_enums_and_omits_unknown_values(self):
        sample = events("original"); sample[4].update(status="fail", registration_state="RETIRING",
                entry_restoration="UNKNOWN", physical_release="UNKNOWN", private_note="do-not-echo")
        result = checked(log(sample), "original")
        self.assertEqual("incomplete", result["result"])
        row = result["observations"][4]
        self.assertEqual("RETIRING", row["values"]["registration_state"])
        self.assertEqual("UNKNOWN", row["values"]["entry_restoration"])
        self.assertNotIn("do-not-echo", str(result))
        sample[2].update(status="fail"); del sample[2]["cpu_end_ns"]
        result = checked(log(sample), "original")
        self.assertFalse(result["observations"][2]["presence"]["cpu_end_ns"])
        self.assertNotIn("cpu_elapsed_ns", result["observations"][2])

    def test_later_clock_contradiction_and_raw_parse_error_retain_prior_observation(self):
        sample = events(); sample[5]["cpu_end_ns"] = 31
        with self.assertRaises(InvalidRecord) as raised: checked(log(sample))
        self.assertEqual(30, raised.exception.partial_report["observations"][2]["values"]["cpu_end_ns"])
        raw = log(events()) + ("11-02 10:20:30.123 %5d %5d I MeguvizHookBench: {bad}\n" % (PID, TID)).encode()
        with self.assertRaises(InvalidRecord) as raised: checked(raw)
        self.assertEqual(7, len(raised.exception.partial_report["observations"]))
        deep = ("11-02 10:20:30.123 %5d %5d I MeguvizHookBench: " % (PID, TID)).encode() + b'{"deep":' + b'[' * 1100 + b'0' + b']' * 1100 + b'}\n'
        with self.assertRaises(RecursionError) as raised: checked(log(events()) + deep)
        self.assertEqual(7, len(raised.exception.partial_report["observations"]))

    def test_unknown_cases_workloads_controls_and_invalid_json_values_are_rejected(self):
        for field, value in (("workload", "B08"), ("failure_case", "unknown"), ("control", "A1"),
                             ("schema", True)):
            sample = events(); sample[0][field] = value
            with self.subTest(field=field), self.assertRaises(InvalidRecord): checked(log(sample))
        sample = events(); sample[2]["before_count"] = True
        with self.assertRaises(InvalidRecord): checked(log(sample))
        sample = events(); sample[2]["extra"] = float("nan")
        with self.assertRaises(InvalidRecord): checked(log(sample))
        raw = log(events()).replace(b'"schema": 1', b'"schema": 1, "schema": 1', 1)
        with self.assertRaises(InvalidRecord): checked(raw)

    def test_cli_real_files_exit_codes_and_input_preservation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.log"
            variants = [(events("after", "A0", "101"), 0, "failure_record_consistent"),
                        (events("after", "A0", "101"), 2, "unresolved"),
                        (events("after", "A0", "101"), 2, "incomplete"),
                        (events("after", "A0", "101"), 1, "invalid")]
            variants[1][0][2]["cpu_end_ns"] = 10
            del variants[2][0][4]
            variants[3][0][2]["identity_preserved"] = False
            for sample, code, status in variants:
                raw = log(sample); path.write_bytes(raw); output = io.StringIO()
                args = ["verify_b09_failure.py", str(path), "--run-id", RUN, "--pair-id", PAIR,
                        "--session-id", SESSION, "--side", "A", "--pid", str(PID), "--tid", str(TID),
                        "--backend", "101", "--control", "A0", "--failure-case", "after"]
                with patch("sys.argv", args), redirect_stdout(output): self.assertEqual(code, main())
                result = json.loads(output.getvalue())
                self.assertEqual(status, result["result"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
                self.assertEqual(raw, path.read_bytes())
                if status == "invalid":
                    self.assertEqual(100, result["observations"][2]["values"]["wall_start_ns"])


if __name__ == "__main__":
    unittest.main()
