import copy
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from build_b07_pair_inputs import json_bytes, parse_capture
from test_verify_b10_resources import events, log
from verify_b10_pair import digest, fixed_input_digest, main, verify_pair
from verify_b10_resources import verify


PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
CLOCK = "9a6bd7a5-c99d-4e27-8594-c0486158f92b"
BOOT = "721f4de7-706b-42b3-bb9e-91f23a8915a9"
IDS = {"A": ("6abefc46-11fe-4f53-8aa6-fba235cb679c", "132ef2bf-a65d-4ca5-885a-e118b487f003"),
       "B": ("9755607f-1368-44a6-bb40-797613a73dee", "ade89657-0bea-4f5f-a56c-b89034b437a3")}
HOST = "io.github.jichuo1.meguviz.hook.benchmarkhost"


def _pin(path, sha, size=100):
    return dict(path=path, bytes=size, sha256=sha * 64)


def fixture(order="AB", backend="102", control="A2"):
    bundles = []
    for index, side in enumerate(("A", "B")):
        rank = order.index(side)
        host, module = _pin("artifacts/host.apk", "1", 1234), _pin("artifacts/module.apk", "2", 5678)
        audit, workload, feature = _pin("work/audit.json", "3"), _pin("work/workload.json", "4"), _pin("work/feature.json", "5")
        pins = [host, module, audit, workload, feature]
        pins.extend(_pin("tools/performance/" + name, "6") for name in
                    ("collect_b10.py", "collect_b07.py", "b07_adb_transport.py", "build_b07_pair_inputs.py",
                     "verify_b07_pair.py", "verify_b07_install.py", "verify_aa_pilot.py", "verify_b10_resources.py"))
        artifacts = {"host": dict(host, package=HOST, certificate_sha256="7" * 64),
                     "module": dict(module, package="io.github.jichuo1.meguviz.hook.benchmark" + backend,
                                    certificate_sha256="7" * 64)}
        framework = dict(framework_name="test", framework_version="1", reported_api=int(backend))
        parameters = dict(warmup=0, samples=1, iterations=1, measurement_mode="unobserved")
        plan = dict(schema_version=1, record_type="b10_resource_plan", status="prepared_not_executed",
                    created_utc="2026-11-02T00:00:00+00:00", evidence_root="D:/offline-fixture",
                    plan_path="work/" + side + "/plan.json", backend=backend, workload="B10", control=control,
                    side=side, run_id=IDS[side][0], pair_id=PAIR, session_id=IDS[side][1], host_clock_token=CLOCK,
                    serial_sha256="8" * 64, expected_framework=framework, parameters=parameters,
                    measurement_kind="process_resource_snapshots", android_user=0, artifacts=artifacts,
                    artifact_audit=audit, workload_sha256=workload["sha256"], feature_manifest_sha256=feature["sha256"],
                    pins=pins, adb=_pin("C:/sdk/adb.exe", "9", 1000), timeout_seconds=60,
                    performance_gate="not_evaluated", device_authentication="not_verified")
        process = dict(pid=1234 + index, uid=10123, start_ticks=10 + rank * 20, cmdline=HOST)
        selected = events(control, backend)
        for event in selected:
            event.update(run_id=plan["run_id"], pair_id=PAIR, session_id=plan["session_id"], side=side, pid=process["pid"])
        for event in selected:
            if "resource_start_ns" in event:
                event["resource_start_ns"] += rank * 1000
                event["resource_end_ns"] += rank * 1000
        if side == "B":
            selected[3]["rss_bytes"] += 1024
            selected[7]["fd_count"] -= 1
        raw = log(selected, process["pid"], 5678 + index)
        device = dict(device_id_sha256=plan["serial_sha256"], build_fingerprint_sha256="a" * 64,
                      boot_id=BOOT, android_api=33, abi="arm64-v8a", android_user=0, **framework)
        capture = dict(schema_version=1, record_type="b10_resource_capture", status="collected_and_owned_stop_confirmed",
                       plan_sha256=digest(json_bytes(plan)), workload="B10", control=control, backend=backend,
                       parameters=parameters, measurement_kind=plan["measurement_kind"], run_id=plan["run_id"],
                       pair_id=PAIR, session_id=plan["session_id"], side=side, host_clock_token=CLOCK,
                       serial_sha256=plan["serial_sha256"], process=process, controller_tid=5678 + index,
                       device=device, artifacts=artifacts, artifact_audit=audit, workload_sha256=plan["workload_sha256"],
                       feature_manifest_sha256=plan["feature_manifest_sha256"], fixed_input_manifest_sha256=fixed_input_digest(plan),
                       launch_monotonic_ns=100 + rank * 1000, terminal_monotonic_ns=500 + rank * 1000,
                       raw_log_sha256=digest(raw), raw_log_bytes=len(raw), verification_sha256="0" * 64,
                       performance_gate="not_evaluated", device_authentication="not_verified")
        collection = dict(schema_version=1, record_type="b10_resource_collection_result", result="resource_record_consistent",
                          reason="fixture collected and stopped", plan_sha256=capture["plan_sha256"], workload="B10",
                          control=control, backend=backend, verification=None, owned=process, stop_attempted=True,
                          stop_confirmed=True, launch_monotonic_ns=capture["launch_monotonic_ns"],
                          terminal_monotonic_ns=capture["terminal_monotonic_ns"], performance_gate="not_evaluated",
                          device_authentication="not_verified")
        bundle = dict(plan_bytes=json_bytes(plan), capture=capture, result=collection,
                      owned_stop=dict(confirmed=True, owned=process, scope="fixed benchmark host only"), raw=raw)
        refresh_raw(bundle)
        bundles.append(bundle)
    return bundles


def refresh_raw(bundle, mutate=None):
    capture = bundle["capture"]
    if mutate:
        selected = [json.loads(line.split(b"MeguvizHookBench: ", 1)[1]) for line in bundle["raw"].splitlines()]
        mutate(selected)
        bundle["raw"] = log(selected, capture["process"]["pid"], capture["controller_tid"])
    raw = bundle["raw"]
    single = verify(raw, capture["run_id"], capture["pair_id"], capture["session_id"], capture["side"],
                    capture["process"]["pid"], capture["controller_tid"], capture["backend"], capture["control"])
    capture.update(raw_log_sha256=digest(raw), raw_log_bytes=len(raw), verification_sha256=digest(json_bytes(single)))
    bundle["result"]["verification"] = single


def refresh_plan(bundle, mutate):
    plan = parse_capture(bundle["plan_bytes"])
    mutate(plan)
    bundle["plan_bytes"] = json_bytes(plan)
    bundle["capture"].update(plan_sha256=digest(bundle["plan_bytes"]), fixed_input_manifest_sha256=fixed_input_digest(plan))
    bundle["result"]["plan_sha256"] = bundle["capture"]["plan_sha256"]
    return plan


class B10PairTest(unittest.TestCase):
    def test_backends_controls_orders_swapped_inputs_and_signed_arithmetic(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                for order in ("AB", "BA"):
                    bundles = fixture(order, backend, control)
                    saved = copy.deepcopy(bundles)
                    for inputs in (bundles, list(reversed(bundles))):
                        report = verify_pair(*inputs, order)
                        self.assertEqual("resource_pair_consistent", report["result"], report["reason"])
                        comparison = report["descriptive_comparison"]
                        self.assertEqual(1024, comparison["installed_minus_before"]["rss_bytes"]["B_minus_A"])
                        self.assertEqual(-1, comparison["closed_minus_before"]["fd_count"]["B_minus_A"])
                        self.assertEqual(0, comparison["installed_minus_before"]["thread_count"]["B_minus_A"])
                        self.assertTrue(all(value is None for value in report["formal_metrics"].values()))
                        self.assertEqual("not_verified", report["device_authentication"])
                        self.assertEqual("not_evaluated", report["performance_gate"])
                    self.assertEqual(saved, bundles)

    def test_raw_bytes_and_saved_verification_cannot_be_replaced_by_success_marker(self):
        for mode in ("raw", "bytes", "verification", "verification_sha", "plan_bytes"):
            bundles = fixture(); b = bundles[0]
            if mode == "raw": b["raw"] += b"unrelated changed bytes\n"
            elif mode == "bytes": b["capture"]["raw_log_bytes"] += 1
            elif mode == "verification": b["result"]["verification"]["phase_deltas"]["installed_minus_before"]["rss_bytes"] += 1
            elif mode == "verification_sha": b["capture"]["verification_sha256"] = "f" * 64
            else: b["plan_bytes"] += b"\n"
            report = verify_pair(*bundles, "AB")
            self.assertEqual("invalid", report["result"], mode)
            self.assertEqual("resource_record_consistent", report["inputs"]["two"]["result"])
            self.assertNotIn("descriptive_comparison", report)

    def test_unavailable_resources_and_missing_raw_end_keep_partial_without_pair_pass(self):
        for change in (lambda selected: selected[3].update(rss_bytes=None, rss_status="unavailable"),
                       lambda selected: selected.pop()):
            bundles = fixture()
            refresh_raw(bundles[0], change)
            report = verify_pair(*bundles, "AB")
            self.assertEqual("incomplete", report["result"])
            self.assertEqual("incomplete", report["inputs"]["one"]["single_side_report"]["result"])
            self.assertEqual("resource_record_consistent", report["inputs"]["two"]["result"])
            self.assertNotIn("descriptive_comparison", report)

    def test_success_capture_result_and_owned_stop_are_all_required(self):
        for part, key, value in (("capture", "status", "pending"), ("result", "result", "collection_failed"),
                                 ("result", "stop_attempted", False), ("result", "stop_confirmed", False),
                                 ("owned_stop", "confirmed", False), ("owned_stop", "scope", "all applications")):
            bundles = fixture(); bundles[0][part][key] = value
            self.assertNotEqual("resource_pair_consistent", verify_pair(*bundles, "AB")["result"])
        for part, key in (("capture", "status"), ("result", "stop_confirmed"), ("owned_stop", "confirmed")):
            bundles = fixture(); del bundles[0][part][key]
            self.assertEqual("incomplete", verify_pair(*bundles, "AB")["result"])
        bundles = fixture(); bundles[0]["owned_stop"] = None
        self.assertEqual("incomplete", verify_pair(*bundles, "AB")["result"])

    def test_owned_stop_and_result_process_identity_must_match_capture(self):
        for part in ("owned_stop", "result"):
            bundles = fixture(); bundles[0][part]["owned"] = dict(bundles[0][part]["owned"], start_ticks=999)
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])

    def test_artifacts_framework_and_full_input_manifest_cannot_drift(self):
        for mode in ("artifact", "framework", "pins", "adb", "audit"):
            bundles = fixture(); b = bundles[1]
            if mode == "artifact": b["capture"]["artifacts"] = copy.deepcopy(b["capture"]["artifacts"]); b["capture"]["artifacts"]["module"]["sha256"] = "b" * 64
            elif mode == "framework":
                for bundle in bundles:
                    bundle["capture"]["device"]["framework_version"] = "different"
                    refresh_plan(bundle, lambda p: p["expected_framework"].update(framework_version="different"))
            elif mode == "pins": refresh_plan(b, lambda p: p["pins"][-1].update(sha256="b" * 64))
            elif mode == "adb": refresh_plan(b, lambda p: p["adb"].update(sha256="b" * 64))
            else: b["capture"]["artifact_audit"] = dict(b["capture"]["artifact_audit"], sha256="b" * 64)
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"], mode)

    def test_id_reuse_and_duplicate_sides_never_pass(self):
        for key, value in (("run_id", IDS["A"][0]), ("session_id", IDS["A"][0]),
                           ("host_clock_token", PAIR), ("pair_id", CLOCK), ("side", "A")):
            bundles = fixture(); b = bundles[1]
            refresh_plan(b, lambda p: p.update({key: value}))
            b["capture"][key] = value
            if key in ("run_id", "session_id", "pair_id", "side"):
                refresh_raw(b, lambda selected: [event.update({key: value}) for event in selected])
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"], key)

    def test_all_three_order_checks_and_equal_boundaries(self):
        for mode in ("declared", "ticks", "host", "device"):
            bundles = fixture(); b = bundles[1]
            if mode == "declared": order = "BA"
            else:
                order = "AB"
                if mode == "ticks":
                    new = dict(b["capture"]["process"], start_ticks=bundles[0]["capture"]["process"]["start_ticks"])
                    b["capture"]["process"] = new; b["result"]["owned"] = new; b["owned_stop"]["owned"] = new
                elif mode == "host":
                    b["capture"]["launch_monotonic_ns"] = bundles[0]["capture"]["terminal_monotonic_ns"]
                    b["result"]["launch_monotonic_ns"] = b["capture"]["launch_monotonic_ns"]
                else: refresh_raw(b, lambda selected: selected[1].update(resource_start_ns=310, resource_end_ns=320))
            self.assertEqual("invalid", verify_pair(*bundles, order)["result"], mode)

    def test_same_pid_with_new_ordered_start_ticks_is_a_distinct_process(self):
        bundles = fixture(); b = bundles[1]
        new = dict(b["capture"]["process"], pid=bundles[0]["capture"]["process"]["pid"])
        b["capture"]["process"] = new; b["result"]["owned"] = new; b["owned_stop"]["owned"] = new
        refresh_raw(b, lambda selected: [event.update(pid=new["pid"]) for event in selected])
        self.assertEqual("resource_pair_consistent", verify_pair(*bundles, "AB")["result"])

    def test_strict_nested_types_duplicate_pins_and_json_keys_are_rejected(self):
        for change in (lambda p: p["parameters"].update(samples=True),
                       lambda p: p["pins"].append(dict(p["pins"][0]))):
            bundles = fixture(); refresh_plan(bundles[0], change)
            self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])
        bundles = fixture()
        bundles[0]["plan_bytes"] = bundles[0]["plan_bytes"].replace(b'"schema_version": 1', b'"schema_version": 1, "schema_version": 1')
        self.assertEqual("invalid", verify_pair(*bundles, "AB")["result"])

    def test_cli_reads_only_supplied_files_and_returns_complete_partial_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); bundles = fixture(); args = ["verify_b10_pair.py", "--pair-order", "AB"]
            paths = {}
            for side, bundle in zip(("one", "two"), bundles):
                for name, key in (("plan", "plan_bytes"), ("capture", "capture"), ("result", "result"),
                                  ("owned-stop", "owned_stop"), ("log", "raw")):
                    path = root / (side + "-" + name)
                    path.write_bytes(bundle[key] if key in ("plan_bytes", "raw") else json_bytes(bundle[key]))
                    paths[(side, name)] = path; args.extend(["--" + name + "-" + side, str(path)])
            for expected in (0, 2, 1):
                if expected == 2: paths[("one", "owned-stop")].unlink()
                if expected == 1: paths[("two", "capture")].write_bytes(b'{"schema_version": 1, "schema_version": 1}')
                out = io.StringIO()
                with patch("sys.argv", args), redirect_stdout(out):
                    self.assertEqual(expected, main())
                report = json.loads(out.getvalue())
                self.assertIn("two", report["inputs"])
                self.assertIn("source_sha256", report)
                self.assertEqual("not_evaluated", report["performance_gate"])


if __name__ == "__main__":
    unittest.main()
