import copy
import json
import shutil
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import collect_loop_pilot as collector
import test_collect_b10 as b10_fixtures
from b07_adb_transport import TransportError
from test_collect_b07 import SERIAL, sha
from test_verify_aa_pilot import session, raw_events, raw_bytes, RESULTS, CALLS, AFTER


SUPPORT = {"workloads": list(collector.WORKLOADS), "controls": ["A0", "A2"],
           "measurement_modes": ["observed", "unobserved"], "backends": ["101", "102"]}


class Model(b10_fixtures.Model):
    def __init__(self, plan):
        super().__init__(plan)
        self.zero_samples = None
        self.missing_clock = self.bad_count = self.bad_checksum = False
        self.stop_had_session = None
        self.nonfinite = None
        self.event_flags = {}
        self.staged_stream = False
        self.pending_raw = None

    def factory(self, adb, serial, directory, token):
        self.factories += 1
        assert serial == SERIAL and token == self.plan["host_clock_token"]
        return FakeAdb(self, directory)


class Capture(b10_fixtures.Capture):
    def poll(self):
        observed = super().poll()
        model = self.adb.model
        if model.host is not None and model.pending_raw is not None:
            self.raw_path.write_bytes(model.pending_raw)
            model.pending_raw = None
        return observed


class FakeAdb(b10_fixtures.FakeAdb):
    def start_logcat(self, label="capture"):
        assert label == "loop_pilot"
        self.capture = Capture(self)
        return self.capture

    def answer(self, args):
        m, plan = self.model, self.model.plan
        if args == ["shell", "am", "force-stop", "--user", "0", collector.HOST]:
            m.stop_had_session = (self.directory / "session.json").exists()
        if args[:3] != ["shell", "am", "start"]:
            return super().answer(args)
        expected = ["shell", "am", "start", "-W", "--user", "0", "-n", collector.HOST + "/.MainActivity"]
        for key in ("run_id", "pair_id", "session_id", "side", "workload", "control"): expected += ["--es", key, plan[key]]
        for key in ("warmup", "samples", "iterations"): expected += ["--ei", key, str(plan["parameters"][key])]
        expected += ["--es", "measurement_mode", plan["parameters"]["measurement_mode"]]
        assert args == expected
        m.launches += 1; m.host = {"pid": 1233 + m.launches, "ticks": 10 * m.launches}
        record = session(plan["side"], plan["workload"], plan["control"])
        record.update(**{key: plan[key] for key in ("run_id", "pair_id", "session_id", "backend")})
        record["process"]["pid"] = m.host["pid"]; record["thread_id"] = 5679
        record["device"].update(plan["expected_framework"])
        config = plan["parameters"]
        record["config"] = {key: config[key] for key in ("warmup", "samples", "iterations")}
        record["config"].update(measurement_kind=plan["measurement_kind"], pair_order=plan["pair_order"])
        def counts(iterations):
            total = iterations * CALLS[plan["workload"]]
            return dict(original_calls=total, hook_before_calls=total,
                        hook_after_calls=total if AFTER[plan["workload"]] else 0, off_thread_calls=0)
        observed = config["measurement_mode"] == "observed"
        record["samples"] = []
        for index in range(config["samples"]):
            start = 100 + index * 30
            item = dict(index=index, iterations=config["iterations"], thread_cpu_start_ns=start,
                        thread_cpu_end_ns=start if m.zero_samples == "all" or m.zero_samples == index else start + 20,
                        checksum="%016x" % (RESULTS[plan["workload"]] * config["iterations"]))
            if observed: item.update(counts(config["iterations"]))
            record["samples"].append(item)
        if observed: record["warmup_observation"] = dict(iterations=config["warmup"], **counts(config["warmup"]))
        selected = raw_events(record)
        if m.nonfinite is not None: selected[-1]["extra"] = {"nested": [m.nonfinite]}
        selected[-1].update(m.event_flags)
        for event in selected:
            if event["event"] == "sample":
                if m.missing_clock: event.pop("thread_cpu_end_ns")
                if m.bad_count and observed: event["original_calls"] += 1
                if m.bad_checksum: event["checksum"] = "0000000000000000"
            if event["event"] == "configured" and m.wrong_framework: event["framework_version"] = "unexpected"
        if m.bad_start: selected[0]["samples"] += 1
        if m.no_end or m.deadline_only: selected.pop()
        complete = raw_bytes(record, selected)
        if m.staged_stream:
            m.pending_raw = complete
            self.capture.raw_path.write_bytes(raw_bytes(record, selected[:1]))
        else:
            self.capture.raw_path.write_bytes(complete)
        if m.launch_timeout: raise TransportError("simulated unknown loop launch", True)
        return b"Status: ok\n"


class LoopCollectorTest(unittest.TestCase):
    def setUp(self):
        # Reuse the existing isolated fixture, including its Popen prohibition; no inherited tests.
        b10_fixtures.B10CollectorTest.setUp(self)
        tools = self.root / "tools/performance"
        for name in collector.TOOLS:
            if not (tools / name).exists(): shutil.copyfile(Path(collector.__file__).with_name(name), tools / name)
        audit = json.loads(self.audit.read_text())
        feature_path = self.root / audit["prepared_inputs"]["feature_manifest"]
        feature = json.loads(feature_path.read_text())
        declarations = {"loop_support": SUPPORT, "loop_limits": collector.LIMITS, "loop_measurement_kinds": collector.KINDS}
        audit.update(declarations); feature.update(declarations)
        feature_path.write_text(json.dumps(feature)); audit["prepared_inputs"]["feature_manifest_sha256"] = sha(feature_path.read_bytes())
        self.audit.write_text(json.dumps(audit))

    def plan(self, workload="B01", control="A2", mode="observed", backend="102", side="A", order="AB",
             warmup=5, samples=2, iterations=3, **ids):
        self.counter += 1
        result = collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb, backend, sha(SERIAL.encode()),
                                   workload, control, mode, warmup, samples, iterations, side, order, "test", "1", int(backend),
                                   self.root / ("loop-plan-%02d" % self.counter), **ids)
        path = Path(result["plan"])
        return path, result["plan_sha256"], json.loads(path.read_text())

    def execute(self, path, digest, model):
        return collector.execute(path, digest, SERIAL, True, True, model.factory)

    def test_all_workloads_and_modes_reuse_builder_and_preserve_preflight(self):
        for index, workload in enumerate(collector.WORKLOADS):
            for mode in collector.KINDS:
                control = ("A0", "A2")[(index + (mode == "observed")) % 2]
                path, digest, plan = self.plan(workload, control, mode, "101" if index % 2 else "102")
                model = Model(plan); result = self.execute(path, digest, model)
                self.assertEqual("observed_session_consistent" if mode == "observed" else "reported_only", result["result"])
                self.assertEqual("collected", result["collection_status"]); self.assertTrue(result["stop_confirmed"])
                self.assertEqual(1, model.launches); self.assertEqual(1, model.stops)
                self.assertFalse(model.stop_had_capture); self.assertFalse(model.stop_had_session)
                directory = path.parent / "execution"; saved = json.loads((directory / "session.json").read_text())
                self.assertEqual("not_verified", saved["preflight"]["status"])
                self.assertTrue(all(value is None for value in saved["formal_metrics"].values()))
                capture = json.loads((directory / "capture.json").read_text())
                self.assertEqual("loop_pilot_capture", capture["record_type"])
                self.assertEqual(sha((directory / "session.json").read_bytes()), capture["session_sha256"])
                self.assertEqual(sha((directory / "session-manifest.json").read_bytes()), capture["session_manifest_sha256"])
                self.assertEqual(sha((directory / "single-verification.json").read_bytes()), capture["verification_sha256"])
                with self.assertRaises(FileExistsError): self.execute(path, digest, model)

    def test_host_parameter_bounds_and_b06_multiplier_are_exact(self):
        self.plan("B01", warmup=1_000_000, samples=9, iterations=1_000_000)
        self.plan("B06", warmup=500_000, samples=2, iterations=1_000_000)
        for changes in (dict(warmup=True), dict(samples=0), dict(iterations=1_000_001), dict(mode="unknown"),
                        dict(workload="B07"), dict(workload="B01", warmup=1_000_000, samples=10, iterations=1_000_000),
                        dict(workload="B06", warmup=500_001, samples=2, iterations=1_000_000)):
            with self.subTest(changes=changes), self.assertRaises(collector.InvalidRecord): self.plan(**changes)

    def test_shared_pair_clock_and_order_are_pinned_but_run_session_stay_independent(self):
        pair, clock = str(uuid.uuid4()), str(uuid.uuid4())
        one = self.plan(side="A", order="BA", pair_id=pair, host_clock_token=clock)[2]
        two = self.plan(side="B", order="BA", pair_id=pair, host_clock_token=clock)[2]
        self.assertEqual(one["pair_id"], two["pair_id"]); self.assertEqual(one["host_clock_token"], two["host_clock_token"])
        self.assertEqual(4, len({one["run_id"], two["run_id"], one["session_id"], two["session_id"]}))
        self.assertEqual(collector.fixed_input_manifest_sha256(one), collector.fixed_input_manifest_sha256(two))
        for ids in (dict(pair_id=pair), dict(host_clock_token=clock), dict(pair_id=pair, host_clock_token=pair),
                    dict(pair_id=True, host_clock_token=clock)):
            with self.assertRaises(collector.InvalidRecord): self.plan(**ids)

    def test_b10_only_or_malformed_loop_audit_and_feature_never_prepare(self):
        original = json.loads(self.audit.read_text())
        for key in ("loop_support", "loop_limits", "loop_measurement_kinds"):
            audit = copy.deepcopy(original); audit.pop(key); self.audit.write_text(json.dumps(audit))
            with self.assertRaises(collector.InvalidRecord): self.plan()
        audit = copy.deepcopy(original); audit["loop_support"]["controls"] = "A2"; self.audit.write_text(json.dumps(audit))
        with self.assertRaises(collector.InvalidRecord): self.plan()
        self.audit.write_text(json.dumps(original)); feature = self.root / original["prepared_inputs"]["feature_manifest"]
        data = json.loads(feature.read_text()); data["loop_limits"]["workload_call_multiplier"]["B01"] = True
        feature.write_text(json.dumps(data)); original["prepared_inputs"]["feature_manifest_sha256"] = sha(feature.read_bytes())
        self.audit.write_text(json.dumps(original))
        with self.assertRaises(collector.InvalidRecord): self.plan()

    def test_missing_flags_serial_changed_pin_loaded_source_or_copied_plan_prevents_transport(self):
        path, digest, plan = self.plan(); model = Model(plan)
        with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, SERIAL, False, True, model.factory)
        with self.assertRaises(collector.InvalidRecord): collector.execute(path, digest, "other", True, True, model.factory)
        copied = self.root / "copied.json"; shutil.copyfile(path, copied)
        with self.assertRaises(collector.InvalidRecord): self.execute(copied, digest, model)
        other = self.root / "other.py"; other.write_bytes(b"different loaded helper")
        with patch.object(collector.guards, "__file__", str(other)):
            with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        (self.root / "host.apk").write_bytes(b"changed")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_unknown_launch_preexisting_host_and_unowned_start_are_not_stopped(self):
        for flag in ("launch_timeout", "preexisting", "bad_start", "bad_uid", "extra_argv"):
            path, digest, plan = self.plan(); model = Model(plan)
            if flag == "preexisting": model.host = dict(pid=900, ticks=1)
            elif flag == "extra_argv": model.extra_argv_from = 1
            else: setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertIsNone(result["owned"])
            self.assertEqual(0, model.stops)

    def test_missing_clock_counts_checksum_and_framework_leave_partial_without_session(self):
        for flag in ("missing_clock", "bad_count", "bad_checksum", "wrong_framework"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertEqual(1, model.stops)
            self.assertIn(result["verification"]["result"], ("invalid", "incomplete"))
            self.assertTrue(result["verification"]["observations"])
            self.assertTrue((path.parent / "execution/raw-threadtime.log").exists())
            self.assertFalse((path.parent / "execution/session.json").exists())

    def test_all_zero_and_partial_zero_cpu_intervals_are_preserved_without_ratios(self):
        for zero in ("all", 0):
            path, digest, plan = self.plan(); model = Model(plan); model.zero_samples = zero
            result = self.execute(path, digest, model)
            self.assertEqual("unresolved" if zero == "all" else "observed_session_consistent", result["result"])
            timing = result["verification"]["timing"]
            self.assertEqual(2 if zero == "all" else 1, timing["zero_intervals"]); self.assertEqual(0, timing["intervals_ns"][0])
            self.assertNotIn("ratio", timing); self.assertTrue(result["stop_confirmed"])

    def test_unknown_stop_or_changed_apk_never_retries_or_publishes_session(self):
        for flag in ("stop_timeout", "changed_apk"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, flag, True)
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertEqual(1, model.launches)
            self.assertEqual(1 if flag == "stop_timeout" else 0, model.stops); self.assertFalse(result["stop_confirmed"])
            self.assertFalse((path.parent / "execution/session.json").exists())
            self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_terminal_timeout_and_final_stream_hash_failure_keep_single_attempt(self):
        for flag in ("deadline_only", "bad_final_hash"):
            path, digest, plan = self.plan(); model = Model(plan); setattr(model, flag, True)
            if flag == "deadline_only":
                with patch.object(collector.time, "monotonic", side_effect=[0, 0, 121]), patch.object(collector.time, "sleep"):
                    result = self.execute(path, digest, model)
            else: result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertEqual(1, model.launches)
            self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_nested_nonfinite_extra_fields_never_establish_owned_or_publish_capture(self):
        for invalid in (float("nan"), float("inf"), float("-inf")):
            path, digest, plan = self.plan(); model = Model(plan); model.nonfinite = invalid
            result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertIsNone(result["owned"])
            self.assertEqual(0, model.stops); self.assertFalse((path.parent / "execution/capture.json").exists())
            self.assertTrue((path.parent / "execution/raw-threadtime.log").exists())

    def test_unknown_or_timeout_event_fields_require_false_boolean_and_keep_partial(self):
        path, digest, plan = self.plan(); model = Model(plan)
        model.event_flags = dict(outcome_unknown=False, timed_out=False)
        self.assertEqual("observed_session_consistent", self.execute(path, digest, model)["result"])
        for key in ("outcome_unknown", "timed_out"):
            for value in (True, 0, "false", None):
                path, digest, plan = self.plan(); model = Model(plan); model.event_flags = {key: value}
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"]); self.assertIsNone(result["owned"])
                self.assertEqual(0, model.stops); self.assertEqual("invalid", result["verification"]["result"])
                self.assertIn(key, result["verification"]["reason"]); self.assertTrue(result["verification"]["observations"])
                self.assertTrue((path.parent / "execution/raw-threadtime.log").exists())
                self.assertFalse((path.parent / "execution/capture.json").exists())

    def test_late_unknown_or_timeout_flag_keeps_partial_and_stops_prior_owned_host_once(self):
        for key in ("outcome_unknown", "timed_out"):
            path, digest, plan = self.plan(); model = Model(plan)
            model.staged_stream = True; model.event_flags = {key: True}
            with patch.object(collector.time, "sleep"):
                result = self.execute(path, digest, model)
            self.assertEqual("collection_failed", result["result"]); self.assertIsNotNone(result["owned"])
            self.assertTrue(result["stop_confirmed"]); self.assertEqual(1, model.launches); self.assertEqual(1, model.stops)
            self.assertEqual("invalid", result["verification"]["result"])
            self.assertTrue(any(item["values"].get(key) is True for item in result["verification"]["observations"]))
            self.assertTrue((path.parent / "execution/owned-start.json").exists())
            self.assertTrue((path.parent / "execution/owned-stop.json").exists())
            self.assertFalse((path.parent / "execution/capture.json").exists())
            self.assertFalse((path.parent / "execution/session.json").exists())
            with self.assertRaises(FileExistsError): self.execute(path, digest, model)
            self.assertEqual(1, model.stops)


if __name__ == "__main__":
    unittest.main()
