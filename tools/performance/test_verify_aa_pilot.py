import copy
import hashlib
import json
import unittest

from verify_aa_pilot import (FORMAL_METRICS, OBSERVED_KIND, IncompleteRecord,
                             InvalidRecord, validate_observation, validate_session, verify_pair, verify_pilot)


PAIR = "a5ac5ec9-755e-4c85-8aca-3dd7352eabf0"
RESULTS = {"B01": 42, "B02": 7, "B03": 42, "B04": 9, "B05": 42, "B06": 4}
POST_CLOSE_RESULTS = {"B01": 42, "B02": 7, "B03": 42, "B04": 7, "B05": 42, "B06": 4}
AFTER = {"B01": True, "B02": False, "B03": True, "B04": False, "B05": True, "B06": True}
CALLS = {"B01": 1, "B02": 1, "B03": 1, "B04": 1, "B05": 1, "B06": 4}


def session(side, workload="B01", control="A0"):
    is_a = side == "A"
    result = RESULTS[workload]
    return {
        "schema_version": 1,
        "record_type": "aa_pilot_session",
        "data_origin": "device_measurement",
        "pair_id": PAIR,
        "side": side,
        "run_id": "a5ac5ec9-755e-4c85-8aca-3dd7352eabf1" if is_a else "a5ac5ec9-755e-4c85-8aca-3dd7352eabf2",
        "session_id": "a5ac5ec9-755e-4c85-8aca-3dd7352eabf3" if is_a else "a5ac5ec9-755e-4c85-8aca-3dd7352eabf4",
        "process": {"pid": 100 if is_a else 200, "start_token": "process-a" if is_a else "process-b"},
        "thread_id": 101 if is_a else 201,
        "workload": workload,
        "control": control,
        "backend": "102",
        "config": {"warmup": 5, "samples": 2, "iterations": 3,
                   "measurement_kind": "thread_cpu_envelope_ns", "pair_order": "BA"},
        "artifacts": {"host_apk_sha256": "a" * 64, "module_apk_sha256": "b" * 64,
                      "workload_sha256": "c" * 64, "feature_manifest_sha256": "d" * 64},
        "device": {"device_id_sha256": "e" * 64, "build_fingerprint_sha256": "f" * 64,
                   "android_api": 33, "abi": "arm64-v8a", "framework_name": "fixture-framework",
                   "framework_version": "fixture-version", "reported_api": 102},
        "capture": {"raw_log_sha256": "1" * 64, "format": "threadtime", "tag": "MeguvizHookBench",
                    "header_pid": 100 if is_a else 200, "header_tid": 101 if is_a else 201},
        "preflight": {"status": "reported_pass", "instrumented": True, "observed_return": result,
                      "original_calls": CALLS[workload], "hook_observed": True, "evidence_sha256": "2" * 64},
        "execution_status": "completed",
        "cleanup_reported": True,
        "samples": [{"index": i, "iterations": 3, "thread_cpu_start_ns": 100 + i * 30,
                     "thread_cpu_end_ns": 120 + i * 30, "checksum": "%016x" % (result * 3)}
                    for i in range(2)],
        "formal_metrics": {key: None for key in FORMAL_METRICS},
    }


def observed_session(side, workload="B01", control="A0"):
    record = session(side, workload, control)
    record["config"]["measurement_kind"] = OBSERVED_KIND
    record["preflight"] = dict.fromkeys(record["preflight"])
    record["preflight"]["status"] = "not_verified"

    def observation(iterations):
        count = iterations * CALLS[workload]
        return {"original_calls": count, "hook_before_calls": count,
                "hook_after_calls": count if AFTER[workload] else 0,
                "off_thread_calls": 0}

    warmup = record["config"]["warmup"]
    record["warmup_observation"] = dict(iterations=warmup, **observation(warmup))
    for item in record["samples"]:
        item.update(observation(item["iterations"]))
    return record


def raw_events(record):
    common = {"schema": 1, "run_id": record["run_id"], "pair_id": record["pair_id"],
              "session_id": record["session_id"], "pid": record["process"]["pid"], "side": record["side"],
              "workload": record["workload"], "control": record["control"]}
    config = record["config"]
    start = dict(common, event="start", status="start", warmup=config["warmup"], samples=config["samples"],
                 iterations=config["iterations"], measurement_kind=config["measurement_kind"])
    diagnostic = dict(common, event="preflight", status="pass", hook_count=CALLS[record["workload"]],
                      result=RESULTS[record["workload"]], original_count="unverified",
                      hook_scope="separate_preflight", measurement_hook_verified=False,
                      preflight_restoration="RESTORED")
    post_close = dict(common, event="preflight_post_close", status="pass", hook_count=CALLS[record["workload"]],
                      result=POST_CLOSE_RESULTS[record["workload"]], entry_restoration="RESTORED")
    configured = dict(common, event="configured", status="pass", backend=record["backend"],
                      framework_name=record["device"]["framework_name"],
                      framework_version=record["device"]["framework_version"],
                      reported_api=record["device"]["reported_api"])
    samples = [dict(common, event="sample", status="pass", **sample) for sample in record["samples"]]
    cleanup = dict(common, event="cleanup", status="pass", entry_restoration="RESTORED",
                   evidence_source="framework_unhook_contract" if record["control"] == "A0" else "sdk_close_report",
                   physical_release="NOT_CLAIMED")
    end = dict(common, event="end", status="pass", detail="completed", cleanup_reported=True)
    warmup = ([dict(common, event="warmup_observation", status="pass", **record["warmup_observation"])]
              if config["measurement_kind"] == OBSERVED_KIND else [])
    return [start, diagnostic, post_close, configured, *warmup, *samples, cleanup, end]


def raw_bytes(record, events, pid=None, tid=None):
    prefix_pid = record["process"]["pid"] if pid is None else pid
    prefix_tid = record["thread_id"] if tid is None else tid
    text = "".join("11-01 10:20:30.123 %5d %5d I MeguvizHookBench: " % (prefix_pid, prefix_tid)
                   + json.dumps(event) + "\n" for event in events)
    raw = text.encode("utf-8")
    record["capture"]["raw_log_sha256"] = hashlib.sha256(raw).hexdigest()
    return raw


class AaPilotTest(unittest.TestCase):
    def test_raw_log_allows_invalid_utf8_in_unrelated_lines_only(self):
        for observed in (False, True):
            with self.subTest(observed=observed):
                make = observed_session if observed else session
                first, second = make("A"), make("B")
                left = raw_bytes(first, raw_events(first))
                right = (b"11-01 10:20:30.000 9999 9999 I Weather: unrelated \xff\n"
                         b"11-01 10:20:30.000 9999 9999 I Wea\xffther: unrelated\n"
                         + raw_bytes(second, raw_events(second)))
                second["capture"]["raw_log_sha256"] = hashlib.sha256(right).hexdigest()
                report = verify_pilot(first, second, left, right)
                self.assertEqual("observed_pilot_data_complete" if observed else "pilot_data_complete",
                                 report["result"])
                self.assertEqual("not_evaluated", report["performance_gate"])

    def test_raw_log_rejects_undecodable_or_malformed_target_lines(self):
        first, second = observed_session("A"), observed_session("B")
        left = raw_bytes(first, raw_events(first))
        original = raw_bytes(second, raw_events(second))
        for corrupt in (
                original.replace(b"fixture-framework", b"fixture-\xffframework", 1),
                original.replace(b"MeguvizHookBench:", b"MeguvizHook\xffBench:", 1),
                original + b"11-01 10:20:30.000 200 201 I MeguvizHookBenc\xff: {}\n",
                original + b"11-01 10:20:30.000 200 201 I MeguvizHookBench: \xff\n",
                original + b"11-01 10:20:30.000 200 201 I MeguvizHookBench \xff\n",
                original + b"11-01 10:20:30.000 200 201 I MeguvizHookBench malformed\n"):
            with self.subTest(corrupt=corrupt[-90:]):
                second["capture"]["raw_log_sha256"] = hashlib.sha256(corrupt).hexdigest()
                with self.assertRaises(InvalidRecord):
                    verify_pilot(first, second, left, corrupt)

    def test_observed_pair_records_instrumented_envelope_without_formal_metrics(self):
        for workload in ("B01", "B02", "B03", "B04", "B05"):
            for control in ("A0", "A2"):
                with self.subTest(workload=workload, control=control):
                    first, second = observed_session("A", workload, control), observed_session("B", workload, control)
                    report = verify_pilot(first, second, raw_bytes(first, raw_events(first)),
                                          raw_bytes(second, raw_events(second)))
                    self.assertEqual("observed_pilot_data_complete", report["result"])
                    self.assertEqual("not_verified", report["preflight"])
                    self.assertEqual("not_evaluated", report["performance_gate"])
                    self.assertTrue(all(v is None for v in report["formal_metrics"].values()))

    def test_observed_record_rejects_wrong_or_missing_counts_and_mixed_modes(self):
        for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
            with self.subTest(field=key):
                left, right = observed_session("A"), observed_session("B")
                right["samples"][0][key] += 1
                with self.assertRaises(InvalidRecord):
                    verify_pair(left, right)
        left, right = observed_session("A", "B02"), observed_session("B", "B02")
        right["warmup_observation"]["hook_after_calls"] = right["config"]["warmup"]
        with self.assertRaises(InvalidRecord):
            verify_pair(left, right)
        left, right = observed_session("A"), observed_session("B")
        del right["samples"][0]["original_calls"]
        with self.assertRaises(IncompleteRecord):
            verify_pair(left, right)
        with self.assertRaises(InvalidRecord):
            verify_pair(observed_session("A"), session("B"))

    def test_b03_rejects_bad_counts_in_warmup_and_each_sample(self):
        for phase in ("warmup", "sample"):
            for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
                with self.subTest(phase=phase, field=key):
                    left, right = observed_session("A", "B03"), observed_session("B", "B03")
                    record = right["warmup_observation"] if phase == "warmup" else right["samples"][0]
                    record[key] += 1
                    with self.assertRaises(InvalidRecord):
                        verify_pair(left, right)

    def test_b03_rejects_wrong_result_and_checksum(self):
        for change in ("preflight", "checksum", "raw_preflight", "raw_post_close"):
            with self.subTest(change=change):
                left, right = session("A", "B03"), session("B", "B03")
                left_log = raw_bytes(left, raw_events(left))
                events = raw_events(right)
                if change == "preflight":
                    right["preflight"]["observed_return"] = 7
                elif change == "checksum":
                    right["samples"][0]["checksum"] = "%016x" % (7 * 3)
                    events = raw_events(right)
                elif change == "raw_preflight":
                    events[1]["result"] = 7
                else:
                    events[2]["result"] = 7
                with self.assertRaises(InvalidRecord):
                    verify_pilot(left, right, left_log, raw_bytes(right, events))

    def test_b04_reports_written_and_restored_results_for_both_backends_and_modes(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                for observed in (False, True):
                    with self.subTest(backend=backend, control=control, observed=observed):
                        make = observed_session if observed else session
                        left, right = make("A", "B04", control), make("B", "B04", control)
                        for record in (left, right):
                            record["backend"] = backend
                            record["device"]["reported_api"] = int(backend)
                        left_events, right_events = raw_events(left), raw_events(right)
                        self.assertEqual(9, left_events[1]["result"])
                        self.assertEqual(7, left_events[2]["result"])
                        report = verify_pilot(left, right, raw_bytes(left, left_events),
                                              raw_bytes(right, right_events))
                        self.assertEqual("observed_pilot_data_complete" if observed else "pilot_data_complete",
                                         report["result"])
                        self.assertEqual("not_evaluated", report["performance_gate"])
                        self.assertTrue(all(value is None for value in report["formal_metrics"].values()))

    def test_b04_rejects_confused_results_checksums_counts_and_missing_events(self):
        for change in ("reported_preflight", "preflight", "post_close", "checksum", "missing_post_close",
                       "warmup_before", "warmup_after", "warmup_original", "warmup_offthread",
                       "sample_before", "sample_after", "sample_original", "sample_offthread"):
            with self.subTest(change=change):
                observed = change.startswith("warmup_") or change.startswith("sample_")
                make = observed_session if observed else session
                left, right = make("A", "B04"), make("B", "B04")
                left_log = raw_bytes(left, raw_events(left))
                events = raw_events(right)
                if change == "reported_preflight":
                    right["preflight"]["observed_return"] = 7
                elif change == "preflight":
                    events[1]["result"] = 7
                elif change == "post_close":
                    events[2]["result"] = 9
                elif change == "checksum":
                    right["samples"][0]["checksum"] = "%016x" % (7 * 3)
                    events = raw_events(right)
                elif change == "missing_post_close":
                    events.pop(2)
                else:
                    phase, field = change.split("_")
                    key = {"before": "hook_before_calls", "after": "hook_after_calls",
                           "original": "original_calls", "offthread": "off_thread_calls"}[field]
                    record = right["warmup_observation"] if phase == "warmup" else right["samples"][0]
                    record[key] += 1
                    events = raw_events(right)
                with self.assertRaises((InvalidRecord, IncompleteRecord)):
                    verify_pilot(left, right, left_log, raw_bytes(right, events))

    def test_b04_cannot_pair_different_workload_or_measurement_mode(self):
        for right in (session("B", "B02"), observed_session("B", "B04")):
            with self.assertRaises(InvalidRecord):
                verify_pair(session("A", "B04"), right)

    def test_b05_result_and_counts_for_both_backends_and_modes(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                for observed in (False, True):
                    with self.subTest(backend=backend, control=control, observed=observed):
                        make = observed_session if observed else session
                        left, right = make("A", "B05", control), make("B", "B05", control)
                        for record in (left, right):
                            record["backend"] = backend
                            record["device"]["reported_api"] = int(backend)
                        first, second = raw_events(left), raw_events(right)
                        self.assertEqual(42, first[1]["result"])
                        self.assertEqual(42, first[2]["result"])
                        report = verify_pilot(left, right, raw_bytes(left, first), raw_bytes(right, second))
                        self.assertEqual("observed_pilot_data_complete" if observed else "pilot_data_complete",
                                         report["result"])
                        self.assertEqual("not_evaluated", report["performance_gate"])
                        self.assertTrue(all(value is None for value in report["formal_metrics"].values()))

    def test_b05_rejects_bad_counts_result_and_missing_events(self):
        for phase in ("warmup", "sample"):
            for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
                with self.subTest(phase=phase, field=key):
                    left, right = observed_session("A", "B05"), observed_session("B", "B05")
                    item = right["warmup_observation"] if phase == "warmup" else right["samples"][0]
                    item[key] += 1
                    with self.assertRaises(InvalidRecord):
                        verify_pair(left, right)
        for change in ("preflight", "post_close", "checksum", "missing_warmup", "failed"):
            with self.subTest(change=change):
                left, right = observed_session("A", "B05"), observed_session("B", "B05")
                left_log = raw_bytes(left, raw_events(left))
                events = raw_events(right)
                if change == "preflight":
                    events[1]["result"] = 7
                elif change == "post_close":
                    events[2]["result"] = 7
                elif change == "checksum":
                    right["samples"][0]["checksum"] = "%016x" % (7 * 3)
                    events = raw_events(right)
                elif change == "missing_warmup":
                    events.pop(4)
                else:
                    events[5]["status"] = "fail"
                with self.assertRaises((InvalidRecord, IncompleteRecord)):
                    verify_pilot(left, right, left_log, raw_bytes(right, events))

    def test_b05_cannot_pair_different_workload_or_measurement_mode(self):
        for right in (session("B", "B01"), observed_session("B", "B05")):
            with self.assertRaises(InvalidRecord):
                verify_pair(session("A", "B05"), right)

    def test_b06_four_layers_for_both_backends_controls_and_modes(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                for observed in (False, True):
                    with self.subTest(backend=backend, control=control, observed=observed):
                        make = observed_session if observed else session
                        left, right = make("A", "B06", control), make("B", "B06", control)
                        for record in (left, right):
                            record["backend"] = backend
                            record["device"]["reported_api"] = int(backend)
                        first, second = raw_events(left), raw_events(right)
                        self.assertEqual(4, first[1]["hook_count"])
                        self.assertEqual(4, first[2]["hook_count"])
                        self.assertEqual(4, first[1]["result"])
                        self.assertEqual(4, first[2]["result"])
                        report = verify_pilot(left, right, raw_bytes(left, first), raw_bytes(right, second))
                        self.assertEqual("observed_pilot_data_complete" if observed else "pilot_data_complete",
                                         report["result"])
                        self.assertEqual("not_evaluated", report["performance_gate"])

    def test_b06_rejects_single_layer_counts_and_corrupt_events(self):
        for phase in ("warmup", "sample"):
            for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
                with self.subTest(phase=phase, field=key):
                    left, right = observed_session("A", "B06"), observed_session("B", "B06")
                    item = right["warmup_observation"] if phase == "warmup" else right["samples"][0]
                    item[key] = 1 if key == "off_thread_calls" else item["iterations"]
                    with self.assertRaises(InvalidRecord):
                        verify_pair(left, right)
        for event_index in (1, 2):
            left, right = observed_session("A", "B06"), observed_session("B", "B06")
            events = raw_events(right)
            events[event_index]["hook_count"] = 1
            with self.assertRaises(InvalidRecord):
                verify_pilot(left, right, raw_bytes(left, raw_events(left)), raw_bytes(right, events))
        left, right = session("A", "B06"), session("B", "B06")
        right["preflight"]["original_calls"] = 1
        with self.assertRaises(InvalidRecord):
            verify_pair(left, right)

    def test_b06_budget_counts_four_calls_per_outer_iteration(self):
        record = session("A", "B06")
        record["config"].update(warmup=500000, samples=2, iterations=1000000)
        for sample in record["samples"]:
            sample["iterations"] = 1000000
            sample["checksum"] = "%016x" % 4000000
        validate_session(record)  # Exactly ten million target calls.
        record["config"]["warmup"] += 1
        with self.assertRaises(InvalidRecord):
            validate_session(record)

    def test_b06_rejects_wrong_result_missing_event_or_other_mode(self):
        for change in ("preflight_result", "post_close_result", "checksum", "missing_warmup"):
            left, right = observed_session("A", "B06"), observed_session("B", "B06")
            events = raw_events(right)
            if change == "preflight_result":
                events[1]["result"] = 3
            elif change == "post_close_result":
                events[2]["result"] = 3
            elif change == "checksum":
                right["samples"][0]["checksum"] = "%016x" % 9
                events = raw_events(right)
            else:
                events.pop(4)
            with self.subTest(change=change), self.assertRaises((InvalidRecord, IncompleteRecord)):
                verify_pilot(left, right, raw_bytes(left, raw_events(left)), raw_bytes(right, events))
        with self.assertRaises(InvalidRecord):
            verify_pair(session("A", "B06"), observed_session("B", "B06"))

    def test_unknown_workload_never_uses_b02_observation_rules(self):
        left, right = observed_session("A", "B02"), observed_session("B", "B02")
        left["workload"] = right["workload"] = "B07"
        with self.assertRaises(InvalidRecord):
            verify_pair(left, right)
        with self.assertRaises(InvalidRecord):
            validate_observation({"original_calls": 3, "hook_before_calls": 3,
                                  "hook_after_calls": 0, "off_thread_calls": 0}, "B07", 3, "test")

    def test_observed_zero_warmup_still_requires_its_zero_count_event(self):
        left, right = observed_session("A"), observed_session("B")
        for record in (left, right):
            record["config"]["warmup"] = 0
            record["warmup_observation"] = {"iterations": 0, "original_calls": 0,
                                            "hook_before_calls": 0, "hook_after_calls": 0,
                                            "off_thread_calls": 0}
        left_log = raw_bytes(left, raw_events(left))
        right_log = raw_bytes(right, raw_events(right))
        self.assertEqual("observed_pilot_data_complete", verify_pilot(left, right, left_log, right_log)["result"])
        events = raw_events(right)
        events.pop(4)
        with self.assertRaises(IncompleteRecord):
            verify_pilot(left, right, left_log, raw_bytes(right, events))

    def test_observed_raw_log_rejects_warmup_or_sample_changes(self):
        first, second = observed_session("A"), observed_session("B")
        left_log = raw_bytes(first, raw_events(first))
        for change in ("missing_warmup", "duplicate_warmup", "warmup_count", "sample_count", "failure"):
            with self.subTest(change=change):
                right = observed_session("B")
                items = raw_events(right)
                if change == "missing_warmup":
                    items.pop(4)
                elif change == "duplicate_warmup":
                    items.insert(5, copy.deepcopy(items[4]))
                elif change == "warmup_count":
                    items[4]["original_calls"] -= 1
                elif change == "sample_count":
                    items[5]["hook_before_calls"] -= 1
                else:
                    items[5]["status"] = "fail"
                with self.assertRaises((InvalidRecord, IncompleteRecord)):
                    verify_pilot(first, right, left_log, raw_bytes(right, items))

    def test_independent_matching_pairs_have_only_integrity_outcome(self):
        for workload in ("B01", "B02", "B03", "B04", "B05"):
            for control in ("A0", "A2"):
                with self.subTest(workload=workload, control=control):
                    first, second = session("B", workload, control), session("A", workload, control)
                    report = verify_pilot(first, second, raw_bytes(first, raw_events(first)),
                                          raw_bytes(second, raw_events(second)))
                    self.assertEqual("pilot_data_complete", report["result"])
                    self.assertEqual("not_evaluated", report["performance_gate"])
                    self.assertIsNone(report["formal_metrics"]["callback_cpu_ns"])

    def test_unverified_preflight_is_only_reported(self):
        first, second = session("A"), session("B")
        second["preflight"] = dict.fromkeys(second["preflight"])
        second["preflight"]["status"] = "not_verified"
        self.assertEqual("reported_only", verify_pilot(first, second, raw_bytes(first, raw_events(first)),
                                                        raw_bytes(second, raw_events(second)))["result"])

    def test_pair_cannot_mix_control_artifact_device_or_parameters(self):
        for key, value in (("control", "A2"), ("backend", "101")):
            with self.subTest(field=key):
                first, second = session("A"), session("B")
                second[key] = value
                with self.assertRaises(InvalidRecord):
                    verify_pair(first, second)
        for parent, key, value in (("artifacts", "host_apk_sha256", "4" * 64),
                                   ("device", "abi", "x86_64"),
                                   ("config", "warmup", 6)):
            with self.subTest(field=parent + "." + key):
                first, second = session("A"), session("B")
                second[parent][key] = value
                with self.assertRaises(InvalidRecord):
                    verify_pair(first, second)

    def test_identity_reuse_or_mismatched_log_header_is_rejected(self):
        first, second = session("A"), session("B")
        second["session_id"] = first["session_id"]
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)
        second = session("B")
        second["process"] = copy.deepcopy(first["process"])
        second["capture"]["header_pid"] = first["process"]["pid"]
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)
        second = session("B")
        second["capture"]["header_tid"] = 999
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)

    def test_failed_interrupted_unfinished_or_missing_data_never_completes(self):
        for state in ("failed", "interrupted"):
            first, second = session("A"), session("B")
            second["execution_status"] = state
            with self.subTest(state=state), self.assertRaises(IncompleteRecord):
                verify_pair(first, second)
        first, second = session("A"), session("B")
        second["cleanup_reported"] = False
        with self.assertRaises(IncompleteRecord):
            verify_pair(first, second)
        second = session("B")
        second["samples"].pop()
        with self.assertRaises(IncompleteRecord):
            verify_pair(first, second)
        second = session("B")
        second["preflight"]["status"] = "failed"
        with self.assertRaises(IncompleteRecord):
            verify_pair(first, second)

    def test_counterfeit_sample_and_formal_metrics_are_rejected(self):
        for change in ("checksum", "clock", "index", "formal"):
            with self.subTest(change=change):
                first, second = session("A"), session("B")
                if change == "checksum":
                    second["samples"][0]["checksum"] = "0000000000000000"
                elif change == "clock":
                    second["samples"][1]["thread_cpu_start_ns"] = 119
                elif change == "index":
                    second["samples"][0]["index"] = 1
                else:
                    second["formal_metrics"]["callback_cpu_ns"] = 20
                with self.assertRaises(InvalidRecord):
                    verify_pair(first, second)

    def test_bad_preflight_or_fabricated_origin_is_rejected(self):
        first, second = session("A"), session("B")
        second["preflight"]["original_calls"] = 2
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)
        second = session("B")
        second["data_origin"] = "host_model"
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)
        second = session("B")
        second["preflight"]["status"] = "not_verified"
        with self.assertRaises(InvalidRecord):
            verify_pair(first, second)

    def test_source_log_hash_and_pid_are_checked_independently(self):
        first, second = session("A"), session("B")
        left_log = raw_bytes(first, raw_events(first))
        right_log = raw_bytes(second, raw_events(second), pid=999)
        with self.assertRaises(InvalidRecord):
            verify_pilot(first, second, left_log, right_log)
        second = session("B")
        right_log = raw_bytes(second, raw_events(second)) + b"tampered"
        with self.assertRaises(InvalidRecord):
            verify_pilot(first, second, left_log, right_log)

    def test_raw_log_failure_truncation_or_unexpected_events_cannot_pass(self):
        first = session("A")
        left_log = raw_bytes(first, raw_events(first))
        for change in ("failed_preflight", "missing_cleanup", "wrong_sample", "extra_event"):
            with self.subTest(change=change):
                second = session("B")
                entries = raw_events(second)
                if change == "failed_preflight":
                    entries[1]["status"] = "fail"
                elif change == "missing_cleanup":
                    entries.pop(-2)
                elif change == "wrong_sample":
                    entries[4]["thread_cpu_end_ns"] += 1
                else:
                    entries.append(copy.deepcopy(entries[-1]))
                right_log = raw_bytes(second, entries)
                with self.assertRaises((InvalidRecord, IncompleteRecord)):
                    verify_pilot(first, second, left_log, right_log)

    def test_preflight_post_close_must_be_present_and_match_actual_observations(self):
        first = session("A", "B02", "A2")
        left_log = raw_bytes(first, raw_events(first))
        for change in ("missing", "bad_result", "bad_count", "bad_restoration", "missing_count", "failed"):
            with self.subTest(change=change):
                second = session("B", "B02", "A2")
                entries = raw_events(second)
                if change == "missing":
                    entries.pop(2)
                elif change == "bad_result":
                    entries[2]["result"] = 42
                elif change == "bad_count":
                    entries[2]["hook_count"] = 2
                elif change == "bad_restoration":
                    entries[2]["entry_restoration"] = "UNKNOWN"
                elif change == "missing_count":
                    del entries[2]["hook_count"]
                else:
                    entries[2]["status"] = "fail"
                with self.assertRaises((InvalidRecord, IncompleteRecord)):
                    verify_pilot(first, second, left_log, raw_bytes(second, entries))

    def test_selected_worker_cannot_hide_events_with_missing_or_invalid_run_id(self):
        first = session("A")
        left_log = raw_bytes(first, raw_events(first))
        for malformed in (None, "", "not-a-uuid", 7, "A5AC5EC9-755E-4C85-8ACA-3DD7352EABF2"):
            with self.subTest(run_id=malformed):
                second = session("B")
                entries = raw_events(second)
                hidden = {"event": "sample", "status": "fail"}
                if malformed is not None:
                    hidden["run_id"] = malformed
                entries.insert(2, hidden)
                with self.assertRaises(InvalidRecord):
                    verify_pilot(first, second, left_log, raw_bytes(second, entries))

    def test_unrelated_canonical_run_can_be_skipped(self):
        first, second = session("A"), session("B")
        entries = raw_events(second)
        entries.insert(2, {"run_id": "54bf4a91-a240-4d45-9ab1-25f66d409129",
                           "event": "sample", "status": "fail"})
        report = verify_pilot(first, second, raw_bytes(first, raw_events(first)), raw_bytes(second, entries))
        self.assertEqual("pilot_data_complete", report["result"])
if __name__ == "__main__":
    unittest.main()
