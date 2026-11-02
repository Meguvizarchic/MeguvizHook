import copy
import json
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import collect_b10 as collector
from b07_adb_transport import TransportError
from test_collect_b07 import Model as BaseModel, FakeAdb as BaseAdb, Capture as BaseCapture, SERIAL, sha
from test_verify_b10_resources import events, log


SUPPORT = {"B10": {"controls": ["A0", "A2"], "failure_cases": []}}
KINDS = {"B10": collector.KIND}


class Model(BaseModel):
    def __init__(self, plan):
        super().__init__(plan)
        self.poisoned = False
        self.cmdline_reads = 0
        self.cmdline_padding = 54
        self.extra_argv_from = None
        self.identity_shift_at = None
        self.shift_pid = False
        self.wrong_framework = False
        self.failure_case_field = False
        self.metric_unavailable = None
        self.metric_mismatch = False
        self.reported_failure = False
        self.zero_metrics = False
        self.retired = False
        self.deadline_only = False
        self.other_workload = False
        self.a0_sdk_field = False
        self.poison_after_terminal = False
        self.launch_not_ok = False
        self.capture_stops = 0
        self.stop_had_capture = None

    def factory(self, adb, serial, directory, token):
        self.factories += 1
        assert serial == SERIAL and token == self.plan["host_clock_token"]
        return FakeAdb(self, directory)


class Capture(BaseCapture):
    def poll(self):
        return None if self.adb.model.deadline_only else super().poll()

    def stop(self, reason="completed"):
        if not self.stopped:
            self.adb.model.capture_stops += 1
        summary = super().stop(reason)
        if reason == "completed" and self.adb.model.poison_after_terminal:
            self.adb.model.poisoned = True
        return summary


class FakeAdb(BaseAdb):
    @property
    def poisoned(self):
        return self.model.poisoned

    def start_logcat(self, label="capture"):
        assert label == "b10"
        self.capture = Capture(self)
        return self.capture

    def run(self, category, args, timeout=30, accepted=(0,)):
        if self.poisoned:
            raise TransportError("simulated poisoned transport", True)
        try:
            return super().run(category, args, timeout, accepted)
        except TransportError:
            self.model.poisoned = True
            raise

    def answer(self, args):
        m = self.model
        if args[:2] == ["exec-out", "cat"] and args[-1].endswith("/cmdline"):
            m.cmdline_reads += 1
            if m.identity_shift_at == m.cmdline_reads:
                m.host["ticks"] += 1
                if m.shift_pid:
                    m.host["pid"] += 100
            if m.extra_argv_from is not None and m.cmdline_reads >= m.extra_argv_from:
                return collector.HOST.encode() + b"\0--unexpected-argument\0"
            return collector.HOST.encode() + b"\0" * m.cmdline_padding
        if args == ["shell", "am", "force-stop", "--user", "0", collector.HOST]:
            m.stop_had_capture = (self.directory / "capture.json").exists()
            return super().answer(args)
        if args[:3] != ["shell", "am", "start"]:
            return super().answer(args)
        expected = ["shell", "am", "start", "-W", "--user", "0", "-n", collector.HOST + "/.MainActivity",
                    "--es", "run_id", m.plan["run_id"], "--es", "pair_id", m.plan["pair_id"],
                    "--es", "session_id", m.plan["session_id"], "--es", "side", m.plan["side"],
                    "--es", "workload", "B10", "--es", "control", m.plan["control"],
                    "--ei", "warmup", "0", "--ei", "samples", "1", "--ei", "iterations", "1",
                    "--es", "measurement_mode", "unobserved"]
        assert args == expected
        m.launches += 1
        m.host = {"pid": 1233 + m.launches, "ticks": 10 * m.launches}
        selected = events(m.plan["control"], m.plan["backend"])
        for event in selected:
            event.update(run_id=m.plan["run_id"], pair_id=m.plan["pair_id"], session_id=m.plan["session_id"],
                         side=m.plan["side"], pid=m.host["pid"])
            if m.failure_case_field:
                event["failure_case"] = "before"
            if m.other_workload:
                event["workload"] = "B09"
            if event["event"] == "configured" and m.wrong_framework:
                event["framework_version"] = "unexpected"
            if event["event"] in ("resource_before", "resource_installed", "resource_closed"):
                if m.zero_metrics:
                    event.update(rss_bytes=0, fd_count=0, resource_end_ns=event["resource_start_ns"])
                if m.metric_unavailable and event["event"] == m.metric_unavailable[0]:
                    key, status = m.metric_unavailable[1:]
                    event.update({key: 0 if m.metric_mismatch else None, status: "unavailable"})
            if m.retired and event["event"] == "cleanup":
                event["registration_state"] = "RETIRED_RESIDENT"
            if m.retired and event["event"] == "resource_closed":
                event["selected_registration_state"] = "RETIRED_RESIDENT"
            if m.a0_sdk_field and event["event"] == "cleanup":
                event["registration_state"] = "CLOSED"
            if m.reported_failure and event["event"] in ("cleanup", "end"):
                event.update(status="fail", outcome_unknown=True)
        if m.bad_start:
            selected[0]["samples"] = 2
        if m.no_end or m.deadline_only:
            selected.pop()
        self.capture.raw_path.write_bytes(log(selected, m.host["pid"], 5679))
        if m.launch_timeout:
            raise TransportError("simulated launch timeout; outcome unknown", True)
        return b"Status: error\n" if m.launch_not_ok else b"Status: ok\n"


class B10CollectorTest(unittest.TestCase):
    def setUp(self):
        # Every execute call below also injects Model.factory; a mistaken subprocess cannot reach ADB.
        self.popen = patch.object(subprocess, "Popen", side_effect=AssertionError("no real subprocess in B10 fake tests"))
        self.popen.start(); self.addCleanup(self.popen.stop)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.counter = 0
        tools = self.root / "tools/performance"; tools.mkdir(parents=True)
        for name in collector.TOOLS:
            shutil.copyfile(Path(collector.__file__).with_name(name), tools / name)
        self.adb = self.root / "fake-adb.exe"; self.adb.write_bytes(b"not executable: injected transport only")
        artifacts = []
        for label, package in (("host", collector.HOST), ("101", collector.MODULES["101"]), ("102", collector.MODULES["102"])):
            path = self.root / (label + ".apk"); path.write_bytes(("synthetic B10 " + label).encode())
            artifacts.append({"package": package, "variant": "deviceTest", "apk": path.name,
                              "sha256": sha(path.read_bytes()), "bytes": path.stat().st_size,
                              "signature_verified": True, "scheme_v2_verified": True,
                              "runtime_isolation_gate": "passed", "certificate_sha256": "f" * 64})
        verification = {}
        for name in collector.VERIFY_REFS:
            path = self.root / (name + ".json"); path.write_bytes(b"{}\n")
            verification[name] = path.name; verification[name + "_sha256"] = sha(path.read_bytes())
        prepared = {}
        for name, hash_key in collector.PREPARED_REFS:
            path = self.root / (name + ".json")
            value = {"scenario_support": SUPPORT, "fixed_parameters": collector.PARAMETERS, "measurement_kinds": KINDS} if name == "feature_manifest" else {}
            path.write_text(json.dumps(value), encoding="utf-8")
            prepared[name] = path.name; prepared[hash_key] = sha(path.read_bytes())
        self.audit = self.root / "audit.json"
        self.audit.write_text(json.dumps({"status": "signed_artifacts_locally_verified", "artifacts": artifacts,
                                         "verification": verification, "prepared_inputs": prepared,
                                         "scenario_support": SUPPORT, "fixed_parameters": collector.PARAMETERS,
                                         "measurement_kinds": KINDS}), encoding="utf-8")

    def plan(self, backend="102", control="A2", side="A", **ids):
        self.counter += 1
        result = collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb, backend,
                                   sha(SERIAL.encode()), control, side, "test", "1", int(backend),
                                   self.root / ("plan-%02d" % self.counter), **ids)
        path = Path(result["plan"])
        return path, result["plan_sha256"], json.loads(path.read_text(encoding="utf-8"))

    def execute(self, path, digest, model):
        return collector.execute(path, digest, SERIAL, True, True, model.factory)

    def rewrite_plan(self, path, plan):
        raw = collector.common.json_bytes(plan); path.write_bytes(raw)
        return sha(raw)

    def test_offline_prepare_to_execute_matrix_and_capture_after_confirmed_stop(self):
        for backend in ("101", "102"):
            for control in ("A0", "A2"):
                for side in (("A",) if backend == "101" else ("B",)):
                    with self.subTest(backend=backend, control=control, side=side):
                        path, digest, plan = self.plan(backend, control, side); model = Model(plan)
                        self.assertEqual("b10_resource_plan", plan["record_type"])
                        self.assertEqual(4, len({plan[key] for key in ("run_id", "pair_id", "session_id", "host_clock_token")}))
                        result = self.execute(path, digest, model)
                        self.assertEqual("resource_record_consistent", result["result"])
                        self.assertEqual(1, model.launches); self.assertEqual(1, model.stops); self.assertIsNone(model.host)
                        self.assertTrue(result["stop_confirmed"]); self.assertFalse(model.stop_had_capture)
                        self.assertEqual(1, model.capture_stops)
                        directory = path.parent / "execution"
                        capture = json.loads((directory / "capture.json").read_text())
                        self.assertEqual("b10_resource_capture", capture["record_type"])
                        self.assertEqual("collected_and_owned_stop_confirmed", capture["status"])
                        self.assertEqual(plan["host_clock_token"], capture["host_clock_token"])
                        self.assertEqual(collector.fixed_input_manifest_sha256(plan), capture["fixed_input_manifest_sha256"])
                        self.assertEqual(sha((directory / "raw-threadtime.log").read_bytes()), capture["raw_log_sha256"])
                        self.assertEqual(sha((directory / "single-verification.json").read_bytes()), capture["verification_sha256"])
                        self.assertTrue(json.loads((directory / "owned-stop.json").read_text())["confirmed"])
                        with self.assertRaises(FileExistsError): self.execute(path, digest, model)
                        self.assertEqual(1, model.launches)

    def test_shared_pair_clock_produces_independent_single_process_plans(self):
        pair, clock = str(uuid.uuid4()), str(uuid.uuid4())
        one = self.plan(side="A", pair_id=pair, host_clock_token=clock)[2]
        two = self.plan(side="B", pair_id=pair, host_clock_token=clock)[2]
        self.assertEqual(pair, one["pair_id"]); self.assertEqual(pair, two["pair_id"])
        self.assertEqual(clock, one["host_clock_token"]); self.assertEqual(clock, two["host_clock_token"])
        self.assertEqual(4, len({one["run_id"], one["session_id"], two["run_id"], two["session_id"]}))
        self.assertEqual(collector.fixed_input_manifest_sha256(one), collector.fixed_input_manifest_sha256(two))

    def test_shared_identifiers_require_both_canonical_distinct_uuid_values(self):
        good, other = "6abefc46-11fe-4f53-8aa6-fba235cb679c", "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
        for ids in ({"pair_id": good}, {"host_clock_token": other}, {"pair_id": good, "host_clock_token": good},
                    {"pair_id": True, "host_clock_token": other}, {"pair_id": good, "host_clock_token": 12},
                    {"pair_id": good.upper(), "host_clock_token": other}, {"pair_id": good, "host_clock_token": "not-a-uuid"}):
            with self.subTest(ids=ids), self.assertRaises(collector.InvalidRecord):
                self.plan(**ids)
        with patch.object(collector.uuid, "uuid4", return_value=uuid.UUID(good)):
            with self.assertRaises(collector.InvalidRecord): self.plan(pair_id=good, host_clock_token=other)

    def test_flags_serial_artifact_and_adb_pins_fail_before_transport(self):
        path, digest, plan = self.plan(); model = Model(plan)
        for idle, scope in ((False, True), (True, False), (1, True)):
            with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, SERIAL, idle, scope, model.factory)
        with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, "other-device", True, True, model.factory)
        old = self.adb.read_bytes(); self.adb.write_bytes(b"changed executable")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.adb.write_bytes(old); (self.root / "host.apk").write_bytes(b"changed")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_old_round46_style_audit_and_unsupported_control_never_prepare(self):
        original = json.loads(self.audit.read_text())
        for key in ("scenario_support", "fixed_parameters", "measurement_kinds"):
            changed = copy.deepcopy(original); changed.pop(key); self.audit.write_text(json.dumps(changed))
            with self.subTest(key=key), self.assertRaises(collector.InvalidRecord): self.plan()
        self.audit.write_text(json.dumps(original))
        for backend, control in (("103", "A2"), ("102", "A1"), ("101", "A0; bad")):
            with self.assertRaises(collector.InvalidRecord): self.plan(backend, control)

    def test_support_parameter_and_feature_declarations_are_typed_and_consistent(self):
        original = json.loads(self.audit.read_text())
        for key, invalid in (("controls", "A0"), ("controls", ["A2", "A2"]), ("controls", ["A1"]),
                             ("failure_cases", ""), ("failure_cases", ["before"])):
            changed = copy.deepcopy(original); changed["scenario_support"]["B10"][key] = invalid
            self.audit.write_text(json.dumps(changed))
            with self.subTest(key=key, invalid=invalid), self.assertRaises(collector.InvalidRecord): self.plan()
        for invalid in (True, 2, "1"):
            changed = copy.deepcopy(original); changed["fixed_parameters"]["samples"] = invalid
            self.audit.write_text(json.dumps(changed))
            with self.assertRaises(collector.InvalidRecord): self.plan()
        self.audit.write_text(json.dumps(original))
        feature = self.root / original["prepared_inputs"]["feature_manifest"]
        changed = json.loads(feature.read_text()); changed["measurement_kinds"]["B10"] = "wrong"
        feature.write_text(json.dumps(changed)); original["prepared_inputs"]["feature_manifest_sha256"] = sha(feature.read_bytes())
        self.audit.write_text(json.dumps(original))
        with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_copied_plan_required_dependencies_and_loaded_tool_bytes_cannot_be_bypassed(self):
        path, digest, plan = self.plan(); model = Model(plan)
        copied = self.root / "copied-plan.json"; shutil.copyfile(path, copied)
        with self.assertRaises(collector.InvalidRecord): self.execute(copied, digest, model)
        changed = copy.deepcopy(plan); changed["pins"] = [pin for pin in changed["pins"] if pin["path"] != "static_audit.json"]
        with self.assertRaises(collector.InvalidRecord): self.execute(path, self.rewrite_plan(path, changed), model)
        self.rewrite_plan(path, plan)
        other = self.root / "different-loaded-source.py"; other.write_bytes(b"not pinned B10 verifier")
        with patch.object(collector.verify_b10_resources, "__file__", str(other)):
            with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        (self.root / "tools/performance/verify_b10_resources.py").write_bytes(b"changed copied source")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_plan_fixed_fields_and_parameters_reject_type_or_scenario_changes(self):
        path, _, plan = self.plan(); model = Model(plan)
        for key, value in (("workload", "B09"), ("record_type", "b08_b09_single_scenario_plan"),
                           ("timeout_seconds", True), ("control", "A1"), ("host_clock_token", plan["run_id"])):
            changed = copy.deepcopy(plan); changed[key] = value
            with self.subTest(key=key), self.assertRaises(collector.InvalidRecord):
                self.execute(path, self.rewrite_plan(path, changed), model)
        changed = copy.deepcopy(plan); changed["parameters"]["samples"] = True
        with self.assertRaises(collector.InvalidRecord): self.execute(path, self.rewrite_plan(path, changed), model)
        self.assertEqual(0, model.factories)

    def test_optional_audit_supplement_is_pinned_and_null_reference_hash_is_rejected(self):
        audit = json.loads(self.audit.read_text()); supplement = self.root / "supplement.json"; supplement.write_bytes(b"{}\n")
        audit["verification"].update(api101_reference_supplement=supplement.name, api101_reference_supplement_sha256=sha(supplement.read_bytes()))
        self.audit.write_text(json.dumps(audit)); path, digest, plan = self.plan("101"); model = Model(plan)
        self.assertIn(supplement.name, [pin["path"] for pin in plan["pins"]])
        supplement.write_bytes(b"changed")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        audit["verification"]["build_log_sha256"] = None; self.audit.write_text(json.dumps(audit))
        with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_preexisting_host_launch_unknown_and_non_ok_launch_are_not_stopped(self):
        for mode in ("preexisting", "launch_timeout", "launch_not_ok"):
            path, digest, plan = self.plan(); model = Model(plan)
            if mode == "preexisting": model.host = {"pid": 900, "ticks": 1}
            else: setattr(model, mode, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertIsNone(result["owned"])
            self.assertEqual(0 if mode == "preexisting" else 1, model.launches); self.assertEqual(0, model.stops)
            self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_full_cmdline_rejects_extra_arguments_and_accepts_only_nul_padding(self):
        for padding in (1, 54):
            path, digest, plan = self.plan(); model = Model(plan); model.cmdline_padding = padding
            self.assertEqual("resource_record_consistent", self.execute(path, digest, model)["result"])
        for padding, extra in ((0, None), (1, 1)):
            path, digest, plan = self.plan(); model = Model(plan); model.cmdline_padding = padding; model.extra_argv_from = extra
            result = self.execute(path, digest, model); self.assertEqual("collection_failed", result["result"])
            self.assertIsNone(result["owned"]); self.assertEqual(0, model.stops)

    def test_full_cmdline_guard_rechecks_pid_ticks_and_refuses_late_cmdline_change(self):
        for shift_pid in (False, True):
            path, digest, plan = self.plan(); model = Model(plan); model.identity_shift_at = 2; model.shift_pid = shift_pid
            result = self.execute(path, digest, model); self.assertEqual("collection_failed", result["result"])
            self.assertIsNone(result["owned"]); self.assertEqual(0, model.stops)
        path, digest, plan = self.plan(); model = Model(plan); model.extra_argv_from = 10
        result = self.execute(path, digest, model); self.assertEqual("collection_failed", result["result"])
        self.assertIsNotNone(result["owned"]); self.assertEqual(0, model.stops)
        self.assertFalse(result["stop_confirmed"]); self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_bad_start_uid_ticks_or_scenario_cannot_establish_ownership(self):
        for flag in ("bad_start", "bad_uid", "changed_start_ticks", "failure_case_field", "other_workload"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, flag, True)
            with self.subTest(flag=flag):
                result = self.execute(path, digest, model); self.assertEqual("collection_failed", result["result"])
                self.assertIsNone(result["owned"]); self.assertEqual(0, model.stops)

    def test_all_unavailable_resource_metrics_preserve_null_and_never_publish_capture(self):
        for phase, metric, status in (("resource_before", "rss_bytes", "rss_status"),
                                      ("resource_installed", "thread_count", "thread_status"),
                                      ("resource_closed", "fd_count", "fd_status")):
            path, digest, plan = self.plan(); model = Model(plan); model.metric_unavailable = (phase, metric, status)
            with self.subTest(phase=phase, metric=metric):
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"]); self.assertEqual("incomplete", result["verification"]["result"])
                self.assertTrue(result["stop_confirmed"]); self.assertEqual(1, model.stops)
                self.assertFalse((path.parent / "execution/capture.json").exists())
                raw = (path.parent / "execution/raw-threadtime.log").read_bytes()
                self.assertIn(('"' + metric + '": null').encode(), raw)

    def test_real_zero_values_and_retired_state_remain_observations_without_attribution(self):
        for mode in ("zero_metrics", "retired"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, mode, True)
            result = self.execute(path, digest, model); self.assertEqual("resource_record_consistent", result["result"])
            self.assertTrue(all(value is None for value in result["verification"]["formal_metrics"].values()))
            self.assertEqual("not_evaluated", result["performance_gate"])

    def test_invalid_metric_sdk_proof_failure_and_framework_mismatch_are_not_success(self):
        for mode in ("metric_mismatch", "a0_sdk_field", "reported_failure", "wrong_framework"):
            path, digest, plan = self.plan(control="A0" if mode == "a0_sdk_field" else "A2"); model = Model(plan)
            setattr(model, mode, True)
            if mode == "metric_mismatch": model.metric_unavailable = ("resource_before", "rss_bytes", "rss_status")
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertEqual(1, model.stops)
            self.assertFalse((path.parent / "execution/capture.json").exists())
            self.assertTrue((path.parent / "execution/single-verification.json").exists())

    def test_missing_terminal_deadline_final_hash_and_early_stream_fail_without_replay(self):
        for mode in ("no_end", "deadline_only", "bad_final_hash", "early_capture_exit"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, mode, True)
            with self.subTest(mode=mode):
                if mode == "deadline_only":
                    with patch.object(collector.time, "monotonic", side_effect=[0, 0, 61]), patch.object(collector.time, "sleep"):
                        result = self.execute(path, digest, model)
                else:
                    result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"])
                self.assertEqual(0 if mode == "early_capture_exit" else 1, model.launches)
                self.assertFalse((path.parent / "execution/capture.json").exists())
                self.assertEqual(1, model.capture_stops)

    def test_changed_apk_poisoned_transport_and_unknown_stop_never_retry_or_publish_capture(self):
        for mode in ("changed_apk", "poison_after_terminal", "stop_timeout"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, mode, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"])
            self.assertEqual(1, model.launches); self.assertEqual(1 if mode == "stop_timeout" else 0, model.stops)
            self.assertFalse(result["stop_confirmed"]); self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_claim_write_failure_and_factory_failure_leave_one_shot_claim(self):
        path, digest, plan = self.plan(); model = Model(plan)
        with patch.object(collector.common, "write_json", side_effect=OSError("claim write failed")):
            with self.assertRaises(OSError): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)
        with self.assertRaises(FileExistsError): self.execute(path, digest, model)
        path, digest, _ = self.plan()
        def broken(*args): raise OSError("transport construction failed")
        result = collector.execute(path, digest, SERIAL, True, True, broken)
        self.assertEqual("collection_failed", result["result"])
        self.assertTrue((path.parent / "execution/result.json").exists())


if __name__ == "__main__":
    unittest.main()
