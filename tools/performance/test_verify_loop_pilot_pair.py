import copy
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from build_aa_pilot_session import build_session
from build_b07_pair_inputs import json_bytes, parse_capture
from test_verify_aa_pilot import observed_session, raw_bytes, raw_events, session
from test_verify_b10_pair import fixture as resource_fixture
from verify_loop_pilot_pair import (digest, fixed_input_digest, main, session_manifest,
                                    single_verification, verify_pair)


def fixture(order="AB", backend="102", control="A2", workload="B01", mode="observed"):
    bundles = resource_fixture(order, backend, control)
    for index, bundle in enumerate(bundles):
        plan = parse_capture(bundle["plan_bytes"])
        capture = bundle["capture"]
        side = plan["side"]
        model = (observed_session if mode == "observed" else session)(side, workload, control)
        model.update(backend=backend, run_id=plan["run_id"], session_id=plan["session_id"], pair_id=plan["pair_id"])
        model["config"]["pair_order"] = order
        model["process"]["pid"] = capture["process"]["pid"]
        model["thread_id"] = capture["controller_tid"]
        # The later process deliberately has a lower absolute thread CPU clock.
        shift = 1000000 if order.index(side) == 0 else 0
        for sample in model["samples"]:
            sample["thread_cpu_start_ns"] += shift
            sample["thread_cpu_end_ns"] += shift + index * 10
        parameters = {key: model["config"][key] for key in ("warmup", "samples", "iterations")}
        parameters["measurement_mode"] = mode
        plan.update(record_type="loop_pilot_plan", workload=workload, parameters=parameters,
                    measurement_kind=model["config"]["measurement_kind"], timeout_seconds=120, pair_order=order)
        bundle["plan_bytes"] = json_bytes(plan)
        capture.update(record_type="loop_pilot_capture", plan_sha256=digest(bundle["plan_bytes"]),
                       workload=workload, parameters=parameters, measurement_kind=plan["measurement_kind"], pair_order=order,
                       fixed_input_manifest_sha256=fixed_input_digest(plan))
        manifest = session_manifest(capture)
        model["device"] = copy.deepcopy(manifest["device"])
        model["artifacts"] = copy.deepcopy(manifest["artifacts"])
        bundle["raw"] = raw_bytes(model, raw_events(model))
        bundle["session_manifest"] = manifest
        bundle["result"].update(record_type="loop_pilot_collection_result", collection_status="collected",
                                 plan_sha256=capture["plan_sha256"], workload=workload)
        rebuild(bundle)
    return bundles


def rebuild(bundle, change=None):
    capture = bundle["capture"]
    if change:
        events = [json.loads(line.split(b"MeguvizHookBench: ", 1)[1]) for line in bundle["raw"].splitlines()]
        change(events)
        model = {"process": {"pid": capture["process"]["pid"]}, "thread_id": capture["controller_tid"], "capture": {}}
        bundle["raw"] = raw_bytes(model, events)
    bundle["session_manifest"] = session_manifest(capture)
    manifest = bundle["session_manifest"]
    saved = build_session(bundle["raw"], capture["run_id"], manifest)
    bundle["session"] = saved
    bundle["session_bytes"] = json_bytes(saved)
    bundle["session_manifest_bytes"] = json_bytes(manifest)
    single = single_verification(saved, bundle["raw"])
    capture.update(raw_log_sha256=digest(bundle["raw"]), raw_log_bytes=len(bundle["raw"]),
                   session_sha256=digest(bundle["session_bytes"]),
                   session_manifest_sha256=digest(bundle["session_manifest_bytes"]),
                   verification_sha256=digest(json_bytes(single)))
    bundle["result"].update(result=single["result"], verification=single)


def refresh_plan(bundle, change):
    plan = parse_capture(bundle["plan_bytes"])
    change(plan)
    bundle["plan_bytes"] = json_bytes(plan)
    bundle["capture"].update(plan_sha256=digest(bundle["plan_bytes"]), fixed_input_manifest_sha256=fixed_input_digest(plan))
    bundle["result"]["plan_sha256"] = bundle["capture"]["plan_sha256"]


class LoopPairTest(unittest.TestCase):
    def test_workloads_modes_controls_backends_orders_and_input_positions(self):
        cases = [("B01", mode, control, backend, order)
                 for mode in ("observed", "unobserved") for control in ("A0", "A2")
                 for backend in ("101", "102") for order in ("AB", "BA")]
        cases.extend((workload, "observed", "A2", "102", "AB") for workload in ("B02", "B03", "B04", "B05", "B06"))
        for workload, mode, control, backend, order in cases:
            bundles = fixture(order, backend, control, workload, mode)
            before = copy.deepcopy(bundles)
            for inputs in (bundles, list(reversed(bundles))):
                report = verify_pair(*inputs, order)
                self.assertEqual("observed_pilot_data_complete" if mode == "observed" else "reported_only", report["result"], report["reason"])
                self.assertEqual("collected", report["collection_status"])
                self.assertEqual("not_verified", report["sides"]["A"]["session"]["preflight"]["status"])
                self.assertTrue(all(value is None for value in report["formal_metrics"].values()))
                self.assertEqual("not_evaluated", report["performance_gate"])
                self.assertEqual(2, len(report["sides"]["A"]["session"]["samples"]))
                self.assertGreater(report["descriptive_comparison"]["B_minus_A_ns_per_call"], 0)
            self.assertEqual(before, bundles)

    def test_thread_cpu_is_not_compared_across_processes_but_local_backwards_is_invalid(self):
        bundles = fixture()
        self.assertGreater(bundles[0]["session"]["samples"][0]["thread_cpu_start_ns"],
                           bundles[1]["session"]["samples"][-1]["thread_cpu_end_ns"])
        self.assertEqual("observed_pilot_data_complete", verify_pair(*bundles, "AB")["result"])
        raw = bundles[1]["raw"].replace(b'"thread_cpu_end_ns": 130', b'"thread_cpu_end_ns": 99', 1)
        bundles[1]["raw"] = raw
        bundles[1]["capture"].update(raw_log_sha256=digest(raw), raw_log_bytes=len(raw))
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])

    def test_zero_samples_are_retained_and_all_pair_ratios_unavailable(self):
        for all_zero in (False, True):
            bundles = fixture()
            def zero(events):
                samples = [event for event in events if event["event"] == "sample"]
                for event in samples if all_zero else samples[:1]:
                    event["thread_cpu_end_ns"] = event["thread_cpu_start_ns"]
            rebuild(bundles[0], zero)
            report = verify_pair(*bundles, "AB")
            self.assertEqual("unresolved", report["result"])
            self.assertIsNone(report["descriptive_comparison"]["B_over_A_ratio"])
            self.assertIsNone(report["descriptive_comparison"]["B_vs_A_percent"])
            self.assertEqual(2 if all_zero else 1, report["sides"]["A"]["summary"]["zero_sample_count"])
            self.assertEqual(0, report["sides"]["A"]["summary"]["sample_cpu_elapsed_ns"][0])

    def test_raw_saved_session_manifest_and_single_report_are_independently_bound(self):
        for mode in ("raw", "session", "manifest", "session_bytes", "single", "session_sha"):
            bundles = fixture(); b = bundles[0]
            if mode == "raw": b["raw"] += b"unrelated changed bytes\n"
            elif mode == "session": b["session"]["preflight"].update(status="reported_pass", instrumented=True)
            elif mode == "manifest": b["session_manifest"]["process_start_token"] = "invented"
            elif mode == "session_bytes": b["session_bytes"] += b"\n"
            elif mode == "single": b["result"]["verification"]["timing"]["intervals_ns"][0] += 1
            else: b["capture"]["session_sha256"] = "f" * 64
            report = verify_pair(*bundles, "AB")
            self.assertEqual("invalid", report["result"], mode)
            self.assertEqual("observed_session_consistent", report["inputs"]["two"]["result"])
            self.assertNotIn("descriptive_comparison", report)

    def test_same_control_only_and_config_pin_boot_and_framework_mismatches_fail(self):
        # Independently valid A0 and A2 sides still cannot form a formal A/B pair.
        left = fixture(control="A0")[0]; right = fixture(control="A2")[1]
        self.assertEqual("invalid", verify_pair(left, right, "AB")["result"])
        for mode in ("pin", "boot", "framework", "config"):
            bundles = fixture(); b = bundles[1]
            if mode == "pin": refresh_plan(b, lambda p: p["pins"][-1].update(sha256="f" * 64))
            elif mode == "boot":
                b["capture"]["device"]["boot_id"] = "9755607f-1368-44a6-bb40-797613a73dee"
                rebuild(b)
            elif mode == "framework": b["capture"]["device"]["framework_version"] = "different"
            else: refresh_plan(b, lambda p: p["parameters"].update(iterations=4))
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"], mode)

    def test_stop_success_markers_and_ownership_cannot_be_omitted_or_forged(self):
        for part, key, value in (("capture", "status", "pending"), ("result", "collection_status", "failed"),
                                 ("result", "stop_confirmed", False), ("owned_stop", "confirmed", False),
                                 ("result", "result", "pilot_data_complete")):
            bundles = fixture(); bundles[0][part][key] = value
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])
        bundles = fixture(); bundles[0]["owned_stop"] = None
        self.assertEqual("incomplete", verify_pair(*bundles, "AB")["result"])
        bundles = fixture(); bundles[0]["owned_stop"]["owned"] = dict(bundles[0]["owned_stop"]["owned"], start_ticks=999)
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])

    def test_id_reuse_pid_reuse_and_host_order(self):
        bundles = fixture(); b = bundles[1]; run = bundles[0]["capture"]["run_id"]
        refresh_plan(b, lambda p: p.update(run_id=run)); b["capture"]["run_id"] = run
        rebuild(b, lambda events: [event.update(run_id=run) for event in events])
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])
        bundles = fixture(); b = bundles[1]
        process = dict(b["capture"]["process"], pid=bundles[0]["capture"]["process"]["pid"])
        b["capture"]["process"] = process; b["result"]["owned"] = process; b["owned_stop"]["owned"] = process
        rebuild(b, lambda events: [event.update(pid=process["pid"]) for event in events])
        self.assertEqual("observed_pilot_data_complete", verify_pair(*bundles, "AB")["result"])
        b["capture"]["launch_monotonic_ns"] = bundles[0]["capture"]["terminal_monotonic_ns"]
        b["result"]["launch_monotonic_ns"] = b["capture"]["launch_monotonic_ns"]
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])
        self.assertEqual("invalid", verify_pair(*fixture(), "BA")["result"])

    def test_unknown_operation_partial_raw_and_strict_bounded_parameters(self):
        bundles = fixture()
        rebuild(bundles[0], lambda events: events[3].update(outcome_unknown=True))
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])
        bundles = fixture(); b = bundles[0]
        b["raw"] = b"\n".join(b["raw"].splitlines()[:-1]) + b"\n"
        b["capture"].update(raw_log_sha256=digest(b["raw"]), raw_log_bytes=len(b["raw"]))
        report = verify_pair(*bundles, "AB")
        self.assertEqual("incomplete", report["result"])
        self.assertEqual("observed_session_consistent", report["inputs"]["two"]["result"])
        for values in (dict(samples=True), dict(warmup=1000001), dict(samples=200, iterations=1000000)):
            bundles = fixture(); refresh_plan(bundles[0], lambda p: p["parameters"].update(values))
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])

    def test_selected_nested_nonfinite_extra_fields_cannot_form_a_complete_pair(self):
        for value in (float("nan"), float("inf"), -float("inf")):
            bundles = fixture()
            rebuild(bundles[0], lambda events: events[3].update(extra={"nested": [value]}))
            report = verify_pair(*bundles, "AB")
            self.assertEqual("invalid", report["result"])
            self.assertIn("non-finite", report["inputs"]["one"]["reason"])
            self.assertEqual("observed_session_consistent", report["inputs"]["two"]["result"])

    def test_selected_failure_case_and_nonboolean_or_true_unknown_flags_are_rejected(self):
        for fields in ([{"failure_case": value} for value in (None, "before")]
                       + [{key: value} for key in ("outcome_unknown", "timed_out")
                          for value in (True, 0, 1, None, "false")]):
            bundles = fixture()
            rebuild(bundles[0], lambda events: events[3].update(fields))
            report = verify_pair(*bundles, "AB")
            self.assertEqual("invalid", report["result"], fields)
            self.assertEqual("failed", report["collection_status"])
            self.assertIn("source_sha256", report["inputs"]["one"])
            self.assertEqual("observed_session_consistent", report["inputs"]["two"]["result"])
            self.assertNotIn("descriptive_comparison", report)
        bundles = fixture()
        rebuild(bundles[0], lambda events: events[3].update(outcome_unknown=False, timed_out=False))
        self.assertEqual("observed_pilot_data_complete", verify_pair(*bundles, "AB")["result"])

    def test_cli_reads_only_explicit_files_and_preserves_reported_only_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            for mode, expected in (("observed", 0), ("unobserved", 2)):
                bundles = fixture(mode=mode); args = ["verify_loop_pilot_pair.py", "--pair-order", "AB"]
                for side, bundle in zip(("one", "two"), bundles):
                    for name, key in (("plan", "plan_bytes"), ("capture", "capture"), ("result", "result"),
                                      ("owned-stop", "owned_stop"), ("log", "raw"), ("session", "session"),
                                      ("session-manifest", "session_manifest")):
                        path = Path(directory)/(mode+"-"+side+"-"+name)
                        path.write_bytes(bundle[key] if key in ("plan_bytes", "raw") else json_bytes(bundle[key]))
                        args.extend(["--"+name+"-"+side,str(path)])
                out = io.StringIO()
                with patch("sys.argv", args), redirect_stdout(out):
                    self.assertEqual(expected, main())
                report = json.loads(out.getvalue())
                self.assertEqual("reported_only" if mode == "unobserved" else "observed_pilot_data_complete", report["result"])
                self.assertIn("source_sha256", report)


if __name__ == "__main__":
    unittest.main()
