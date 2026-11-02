import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from verify_b10_resources import InvalidRecord, main, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
SESSION = "132ef2bf-a65d-4ca5-885a-e118b487f003"
OTHER = "9755607f-1368-44a6-bb40-797613a73dee"
PID, TID = 1234, 5678


def events(control="A2", backend="102"):
    common = dict(schema=1, run_id=RUN, pair_id=PAIR, session_id=SESSION, side="A",
                  pid=PID, workload="B10", control=control)
    def event(kind, **extra):
        return dict(common, event=kind, status="pass", **extra)
    def counts(original=1):
        return dict(before_count=1, after_count=1, original_count=original)
    def resource(start, rss, threads, fds):
        return dict(resource_start_ns=start, resource_end_ns=start + 10,
                    rss_bytes=rss, rss_status="observed", thread_count=threads,
                    thread_status="observed", fd_count=fds, fd_status="observed")
    install = dict(install_source="framework_handle", handle_returned=True) if control == "A0" else dict(
        install_source="sdk_receipt", receipt_status="INSTALLED", generation=1, target_count=1)
    close = dict(close_source="framework_unhook_contract", entry_restoration="RESTORED",
                 physical_release="NOT_CLAIMED") if control == "A0" else dict(
        close_source="sdk_close_report", entry_restoration="RESTORED", physical_release="NOT_CLAIMED",
        registration_state="CLOSED", in_flight_at_close=0, new_callbacks_rejected=True)
    return [dict(common, event="start", status="start", warmup=0, samples=1, iterations=1,
                 measurement_kind="process_resource_snapshots"),
            event("resource_before", before_count=0, after_count=0, original_count=0,
                  **resource(100, 4096, 10, 12)),
            event("installed", before_count=0, after_count=0, original_count=0, **install),
            event("resource_installed", result=42, **counts(), **resource(200, 8192, 11, 14)),
            event("configured", backend=backend, reported_api=int(backend),
                  framework_name="test", framework_version="1"),
            event("cleanup", **counts(), **close),
            event("post_close", result=42, **counts(2)),
            event("resource_closed", result=42, **counts(2), **resource(300, 3072, 9, 11),
                  retired_resident_bytes=None, retired_bytes_status="not_attributable",
                  selected_registration_state="UNOBSERVED" if control == "A0" else "CLOSED"),
            event("end", detail="completed", cleanup_reported=True)]


def log(items, pid=PID, tid=TID):
    return b"".join(("11-02 10:20:30.123 %5d %5d I MeguvizHookBench: " % (pid, tid)
                     + json.dumps(item) + "\n").encode() for item in items)


def checked(raw, control="A2", backend="102"):
    return verify(raw, RUN, PAIR, SESSION, "A", PID, TID, backend, control)


class B10ResourceTest(unittest.TestCase):
    def test_two_backends_controls_and_negative_phase_deltas(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                raw = b"unrelated \xff\n" + log(events(control, backend))
                report = checked(raw, control, backend)
                self.assertEqual("resource_record_consistent", report["result"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), report["raw_log_sha256"])
                self.assertEqual(dict(rss_bytes=-1024, thread_count=-1, fd_count=-1),
                                 report["phase_deltas"]["closed_minus_before"])
                self.assertTrue(all(value is None for value in report["formal_metrics"].values()))
                self.assertEqual("not_evaluated", report["performance_gate"])

    def test_unavailable_metric_keeps_other_deltas_and_cannot_be_pass(self):
        for index in (1, 3, 7):
            items = events()
            items[index].update(rss_bytes=None, rss_status="unavailable", rss_reason="missing")
            report = checked(log(items))
            self.assertEqual("incomplete", report["result"])
            self.assertIn("rss_bytes unavailable", report["reason"])
            self.assertEqual(-1, report["phase_deltas"]["closed_minus_before"]["fd_count"])
            self.assertIsNone(report["phase_deltas"]["installed_minus_before"]["rss_bytes"] if index != 7
                              else report["phase_deltas"]["closed_minus_before"]["rss_bytes"])

    def test_status_value_mismatch_malformed_types_and_overflow_are_invalid(self):
        for fields in (dict(rss_status="unavailable", rss_bytes=0), dict(rss_bytes=None),
                       dict(thread_count=0), dict(fd_count=4097), dict(rss_bytes=True),
                       dict(fd_count=-1), dict(rss_bytes=1.5), dict(rss_bytes=2**63),
                       dict(fd_status="unknown")):
            items = events(); items[1].update(fields)
            with self.subTest(fields=fields), self.assertRaises(InvalidRecord): checked(log(items))

    def test_read_zero_values_are_distinct_from_unavailable(self):
        items = events()
        for index in (1, 3, 7):
            items[index].update(fd_count=0, rss_bytes=0, resource_end_ns=items[index]["resource_start_ns"])
        report = checked(log(items))
        self.assertEqual("resource_record_consistent", report["result"])
        self.assertEqual(0, report["phase_deltas"]["installed_minus_before"]["rss_bytes"])

    def test_missing_event_or_resource_fields_and_reported_failure_are_incomplete(self):
        for index, key in ((1, "rss_status"), (3, "resource_start_ns"), (7, "retired_resident_bytes"),
                           (0, "measurement_kind"), (8, "cleanup_reported")):
            items = events(); del items[index][key]
            self.assertEqual("incomplete", checked(log(items))["result"])
        for index in range(9):
            items = events(); items.pop(index)
            self.assertEqual("incomplete", checked(log(items))["result"])
        items = events(); items[5].update(status="fail", outcome_unknown=True, entry_restoration="UNKNOWN")
        self.assertEqual("incomplete", checked(log(items))["result"])

    def test_reordered_or_duplicate_stages_and_reversed_windows_are_invalid(self):
        for mutate in (lambda x: x.insert(4, copy.deepcopy(x[3])),
                       lambda x: x.__setitem__(slice(1, 3), list(reversed(x[1:3]))),
                       lambda x: x[1].update(resource_end_ns=99),
                       lambda x: x[7].update(resource_start_ns=205)):
            items = events(); mutate(items)
            with self.assertRaises(InvalidRecord): checked(log(items))

    def test_counts_result_replay_and_cleanup_contradictions_are_invalid(self):
        for index, fields in ((1, dict(original_count=1)), (2, dict(before_count=1)),
                              (3, dict(original_count=2)), (6, dict(after_count=2)),
                              (7, dict(original_count=1)), (6, dict(result=41)),
                              (5, dict(new_callbacks_rejected=False)),
                              (8, dict(cleanup_reported=False)), (5, dict(outcome_unknown=True))):
            items = events(); items[index].update(fields)
            with self.subTest(index=index, fields=fields), self.assertRaises(InvalidRecord): checked(log(items))

    def test_a0_cannot_claim_sdk_close_or_attributed_resources(self):
        for key, value in (("generation", 1), ("registration_state", "CLOSED"),
                           ("in_flight_at_close", 0), ("additional_rss_bytes", 0),
                           ("retired_resident_bytes", 0)):
            items = events("A0"); items[5][key] = value
            with self.assertRaises(InvalidRecord): checked(log(items), "A0")
        items = events("A0"); items[7]["selected_registration_state"] = "CLOSED"
        with self.assertRaises(InvalidRecord): checked(log(items), "A0")

    def test_sdk_retired_state_is_retained_but_is_never_converted_to_bytes(self):
        items = events(); items[5]["registration_state"] = "RETIRED_RESIDENT"
        items[7]["selected_registration_state"] = "RETIRED_RESIDENT"
        self.assertEqual("resource_record_consistent", checked(log(items))["result"])
        items[7]["selected_registration_state"] = "CLOSED"
        with self.assertRaises(InvalidRecord): checked(log(items))

    def test_other_scenario_and_unsupported_physical_release_claims_are_rejected(self):
        for index, fields in ((5, dict(physical_release="RELEASED")), (5, dict(physical_release="UNKNOWN")),
                              (0, dict(failure_case="before")), (3, dict(after_outcome="returned"))):
            items = events(); items[index].update(fields)
            with self.assertRaises(InvalidRecord): checked(log(items))

    def test_failed_clock_endpoints_are_retained_and_extra_strings_are_not_echoed(self):
        for endpoints in (dict(resource_start_ns=None), dict(resource_start_ns=-1),
                          dict(resource_start_ns=220, resource_end_ns=210)):
            items = events(); items[3].update(status="fail", **endpoints, private_text="do not echo")
            report = checked(log(items))
            self.assertEqual("incomplete", report["result"])
            self.assertNotIn("do not echo", json.dumps(report))
            for key, value in endpoints.items():
                self.assertEqual(value, report["observations"][3]["values"][key])

    def test_process_thread_run_and_request_identity_conflicts_are_invalid(self):
        for raw in (log(events(), pid=PID+1), log(events(), tid=TID+1)):
            with self.assertRaises(InvalidRecord): checked(raw)
        for fields in (dict(pair_id=OTHER), dict(workload="B09"), dict(pid=PID+1), dict(control="A0")):
            items = events(); items[3].update(fields)
            with self.assertRaises(InvalidRecord): checked(log(items))
        items = events(); items[3]["run_id"] = OTHER
        with self.assertRaises(InvalidRecord): checked(log(items))
        with self.assertRaises(InvalidRecord):
            verify(log(events()), RUN, RUN, SESSION, "A", PID, TID, "102", "A2")

    def test_corrupted_json_duplicate_fields_nonfinite_and_oversized_input_are_invalid(self):
        raw = log(events())
        for bad in (raw.replace(b'"schema": 1', b'"schema": 1, "schema": 1', 1),
                    raw.replace(b'"rss_bytes": 4096', b'"rss_bytes": NaN', 1),
                    raw.replace(b'"event": "start"', b'"event": "star\xfft"', 1),
                    b'x' * (16 * 1024 * 1024 + 1)):
            with self.assertRaises(InvalidRecord): checked(bad)

    def test_invalid_report_retains_actual_observations(self):
        items = events(); items[3]["rss_bytes"] = -1
        with self.assertRaises(InvalidRecord) as raised: checked(log(items))
        self.assertEqual(-1, raised.exception.partial_report["observations"][-1]["values"]["rss_bytes"])
        for kind in (3, {}, []):
            items = events(); items[1]["event"] = kind; del items[1]["control"]
            with self.assertRaises(InvalidRecord): checked(log(items))

    def test_cli_distinguishes_complete_partial_and_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"raw.log"
            args = ["verify_b10_resources.py", str(path), "--run-id", RUN, "--pair-id", PAIR,
                    "--session-id", SESSION, "--side", "A", "--pid", str(PID), "--tid", str(TID),
                    "--backend", "102", "--control", "A2"]
            for expected, items in ((0, events()), (2, events()[:-1]), (1, events()+[events()[0]])):
                path.write_bytes(log(items)); out = io.StringIO()
                with patch("sys.argv", args), redirect_stdout(out):
                    self.assertEqual(expected, main())
                self.assertEqual("not_evaluated", json.loads(out.getvalue())["performance_gate"])


if __name__ == "__main__":
    unittest.main()
