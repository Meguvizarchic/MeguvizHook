import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_b08_b09 as collector
from test_collect_b07 import Model as BaseModel, FakeAdb as BaseAdb, Capture, SERIAL, sha
from test_verify_b08_close import events as close_events, log
from test_verify_b09_failure import events as failure_events


SUPPORT = {"B08": {"controls": ["A2"], "failure_cases": []},
           "B09": {"controls": ["A0", "A2"], "failure_cases": ["before", "after", "original"]}}


class Model(BaseModel):
    def __init__(self, plan):
        super().__init__(plan)
        self.reported_failure = False
        self.bad_identity = False
        self.wrong_framework = False
        self.b08_case_field = False

    def factory(self, adb, serial, directory, token):
        self.factories += 1
        assert serial == SERIAL and token == self.plan["host_clock_token"]
        return FakeAdb(self, directory)


class FakeAdb(BaseAdb):
    def start_logcat(self, label="capture"):
        assert label == "diagnostic"
        self.capture = Capture(self)
        return self.capture

    def answer(self, args):
        if args[:3] != ["shell", "am", "start"]:
            return super().answer(args)
        m = self.model
        m.launches += 1
        m.host = {"pid": 1233 + m.launches, "ticks": 10 * m.launches}
        extra = {args[i + 1]: args[i + 2] for i in range(len(args) - 2) if args[i] == "--es"}
        assert extra["workload"] == m.plan["workload"]
        assert extra["control"] == m.plan["control"] and extra["measurement_mode"] == "unobserved"
        if m.plan["workload"] == "B08":
            assert "failure_case" not in extra
            selected = close_events(m.plan["backend"], m.plan["side"])
        else:
            assert extra["failure_case"] == m.plan["failure_case"]
            selected = failure_events(m.plan["failure_case"], m.plan["control"], m.plan["backend"])
        for event in selected:
            event.update(run_id=extra["run_id"], pair_id=extra["pair_id"], session_id=extra["session_id"],
                         side=extra["side"], pid=m.host["pid"])
            if m.b08_case_field: event["failure_case"] = "before"
            if m.wrong_framework and event["event"] == "configured": event["framework_version"] = "other"
            if m.reported_failure and event["event"] in ("cleanup", "end"):
                event.update(status="fail", detail="cleanup_unconfirmed")
                if event["event"] == "cleanup": event.update(worker_alive=True, cleanup_confirmed=False)
            if m.bad_identity and event["event"] == "failure_call": event["identity_preserved"] = False
        if m.bad_start: selected[0]["samples"] = 2
        if m.no_end: selected.pop()
        self.capture.raw_path.write_bytes(log(selected, m.host["pid"], 5679))
        if m.launch_timeout:
            raise collector.common.CollectionError("simulated launch timeout; outcome unknown")
        return b"Status: ok\n"


class DiagnosticCollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.counter = 0
        tools = self.root / "tools/performance"; tools.mkdir(parents=True)
        for name in collector.TOOLS:
            shutil.copyfile(Path(collector.__file__).with_name(name), tools / name)
        self.adb = self.root / "fake-adb.exe"; self.adb.write_bytes(b"not executable: injected transport only")
        artifacts = []
        for label, package in (("host", collector.HOST), ("101", collector.MODULES["101"]),
                               ("102", collector.MODULES["102"])):
            p = self.root / (label + ".apk"); p.write_bytes(("synthetic " + label).encode())
            artifacts.append({"package": package, "variant": "deviceTest", "apk": p.name,
                              "sha256": sha(p.read_bytes()), "bytes": p.stat().st_size,
                              "signature_verified": True, "scheme_v2_verified": True,
                              "runtime_isolation_gate": "passed", "certificate_sha256": "f" * 64})
        verification = {}
        for name in collector.VERIFY_REFS:
            p = self.root / (name + ".json"); p.write_bytes(b"{}\n")
            verification[name] = p.name; verification[name + "_sha256"] = sha(p.read_bytes())
        prepared = {}
        for name, hash_key in collector.PREPARED_REFS:
            p = self.root / (name + ".json")
            data = {"scenario_support": SUPPORT, "fixed_parameters": collector.PARAMETERS,
                    "measurement_kinds": collector.KINDS} if name == "feature_manifest" else {}
            p.write_text(json.dumps(data), encoding="utf-8")
            prepared[name] = p.name; prepared[hash_key] = sha(p.read_bytes())
        self.audit = self.root / "audit.json"
        self.audit.write_text(json.dumps({"status": "signed_artifacts_locally_verified", "artifacts": artifacts,
                                         "verification": verification, "prepared_inputs": prepared,
                                         "scenario_support": SUPPORT}), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def plan(self, workload="B08", control="A2", case=None, backend="102"):
        self.counter += 1
        result = collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb, backend,
                                   sha(SERIAL.encode()), workload, control, case, "A", "test", "1", int(backend),
                                   self.root / ("plan-%02d" % self.counter))
        path = Path(result["plan"])
        return path, result["plan_sha256"], json.loads(path.read_text(encoding="utf-8"))

    def execute(self, path, digest, model):
        return collector.execute(path, digest, SERIAL, True, True, model.factory)

    def test_offline_prepare_and_complete_scenario_matrix(self):
        scenarios = [("B08", "A2", None)] + [("B09", control, case) for control in ("A0", "A2")
                    for case in ("before", "after", "original")]
        for backend in ("101", "102"):
            for workload, control, case in scenarios:
                with self.subTest(backend=backend, workload=workload, control=control, case=case):
                    path, digest, plan = self.plan(workload, control, case, backend)
                    self.assertEqual(90 if workload == "B08" else 60, plan["timeout_seconds"])
                    model = Model(plan); result = self.execute(path, digest, model)
                    self.assertEqual("close_record_consistent" if workload == "B08" else "failure_record_consistent", result["result"])
                    self.assertEqual(1, model.launches); self.assertEqual(1, model.stops); self.assertIsNone(model.host)
                    execution = path.parent / "execution"
                    self.assertTrue(json.loads((execution / "owned-stop.json").read_text())["confirmed"])
                    saved = json.loads((execution / "capture.json").read_text())
                    self.assertEqual(workload, saved["workload"]); self.assertEqual(case, saved["failure_case"])
                    self.assertEqual(sha((execution / "raw-threadtime.log").read_bytes()), saved["raw_log_sha256"])
                    with self.assertRaises(FileExistsError): self.execute(path, digest, model)
                    self.assertEqual(1, model.launches)

    def test_flags_serial_and_changed_pins_fail_before_transport(self):
        path, digest, plan = self.plan(); model = Model(plan)
        with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, SERIAL, False, True, model.factory)
        with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, "wrong-device", True, True, model.factory)
        self.assertEqual(0, model.factories)
        (self.root / "host.apk").write_bytes(b"changed")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_copied_plan_missing_dependency_and_loaded_tool_mismatch(self):
        path, digest, plan = self.plan(); model = Model(plan)
        copy_path = self.root / "copied-plan.json"; shutil.copyfile(path, copy_path)
        with self.assertRaises(collector.InvalidRecord): self.execute(copy_path, digest, model)
        changed = copy.deepcopy(plan)
        changed["pins"] = [x for x in changed["pins"] if x["path"] != "static_audit.json"]
        path.write_bytes(collector.common.json_bytes(changed))
        with self.assertRaises(collector.InvalidRecord): self.execute(path, sha(path.read_bytes()), model)
        self.assertEqual(0, model.factories)
        other = self.root / "other-loaded.py"; other.write_bytes(b"different code")
        with patch.object(collector.verify_b08_close, "__file__", str(other)):
            with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_old_b07_audit_or_unsupported_scenarios_never_prepare(self):
        for workload, control, case in (("B08", "A0", None), ("B08", "A2", "before"),
                                        ("B09", "A2", None), ("B07", "A2", None)):
            with self.assertRaises(collector.InvalidRecord): self.plan(workload, control, case)
        audit = json.loads(self.audit.read_text()); audit.pop("scenario_support")
        self.audit.write_text(json.dumps(audit))
        with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_support_types_duplicates_and_feature_parameter_types_are_strict(self):
        original = json.loads(self.audit.read_text())
        for key, bad in (("controls", "A2"), ("controls", ["A2", "A2"]),
                         ("failure_cases", "before"), ("failure_cases", ["before", "before"])):
            value = copy.deepcopy(original); value["scenario_support"]["B09"][key] = bad
            self.audit.write_text(json.dumps(value))
            with self.subTest(key=key, value=bad), self.assertRaises(collector.InvalidRecord):
                self.plan("B09", "A2", "before")
        self.audit.write_text(json.dumps(original))
        feature = self.root / original["prepared_inputs"]["feature_manifest"]
        data = json.loads(feature.read_text()); data["fixed_parameters"]["samples"] = True
        feature.write_text(json.dumps(data)); original["prepared_inputs"]["feature_manifest_sha256"] = sha(feature.read_bytes())
        self.audit.write_text(json.dumps(original))
        with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_preexisting_host_never_started_or_stopped(self):
        path, digest, plan = self.plan(); model = Model(plan); model.host = {"pid": 1234, "ticks": 10}
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual(0, model.launches); self.assertEqual(0, model.stops)

    def test_launch_unknown_never_replayed_or_claimed(self):
        path, digest, plan = self.plan("B09", "A0", "before"); model = Model(plan); model.launch_timeout = True
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual(1, model.launches); self.assertEqual(0, model.stops); self.assertIsNone(result["owned"])
        self.assertTrue((path.parent / "execution/raw-threadtime.log").exists())

    def test_bad_start_uid_or_start_ticks_cannot_establish_ownership(self):
        for mode in ("bad_start", "bad_uid", "changed_start_ticks", "b08_case_field"):
            with self.subTest(mode=mode):
                path, digest, plan = self.plan(); model = Model(plan); setattr(model, mode, True)
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"])
                self.assertIsNone(result["owned"]); self.assertEqual(0, model.stops)

    def test_failed_b08_with_live_helper_is_stopped_only_as_owned_host(self):
        path, digest, plan = self.plan(); model = Model(plan); model.reported_failure = True
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual("incomplete", result["verification"]["result"])
        self.assertEqual(1, model.launches); self.assertEqual(1, model.stops)
        self.assertFalse((path.parent / "execution/capture.json").exists())
        self.assertTrue((path.parent / "execution/raw-threadtime.log").exists())

    def test_b09_wrong_exception_and_framework_are_not_success(self):
        for flag in ("bad_identity", "wrong_framework"):
            path, digest, plan = self.plan("B09", "A2", "original"); model = Model(plan); setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"])
            self.assertEqual(1, model.stops)
            self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_changed_apk_refuses_stop_and_stop_unknown_never_replays(self):
        for flag in ("changed_apk", "stop_timeout"):
            path, digest, plan = self.plan("B09", "A0", "after"); model = Model(plan); setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"])
            self.assertEqual(1, model.launches)
            self.assertEqual(0 if flag == "changed_apk" else 1, model.stops)

    def test_missing_terminal_or_mismatching_stream_digest_is_failure(self):
        for flag in ("no_end", "bad_final_hash"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"])
            self.assertEqual(1, model.launches)
            self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_claim_write_failure_or_factory_error_retains_single_attempt(self):
        path, digest, plan = self.plan(); model = Model(plan)
        with patch.object(collector.common, "write_json", side_effect=OSError("claim write failed")):
            with self.assertRaises(OSError): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)
        with self.assertRaises(FileExistsError): self.execute(path, digest, model)
        path, digest, plan = self.plan()
        def broken(*args): raise OSError("transport construction failed")
        result = collector.execute(path, digest, SERIAL, True, True, broken)
        self.assertEqual("collection_failed", result["result"])
        self.assertTrue((path.parent / "execution/result.json").exists())


if __name__ == "__main__":
    unittest.main()
