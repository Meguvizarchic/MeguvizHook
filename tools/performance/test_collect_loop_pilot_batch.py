import copy
import json
import shutil
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import collect_loop_pilot_batch as batch
import test_collect_loop_pilot as fixtures
from test_collect_b07 import BOOT, SERIAL, sha


class FakeAdb(fixtures.FakeAdb):
    def answer(self, args):
        if args == ["shell", "dumpsys", "battery"]:
            value = self.model.temperatures.pop(0) if self.model.temperatures else 280
            return ("temperature: %d\n" % value).encode()
        return super().answer(args)


class BatchTest(unittest.TestCase):
    def setUp(self):
        # Existing fixture also forbids subprocess.Popen; execute below is explicitly injected.
        fixtures.LoopCollectorTest.setUp(self)
        for name in batch.EXTRA_TOOLS:
            shutil.copyfile(Path(batch.__file__).with_name(name), self.root / "tools/performance" / name)
        self.batch_counter = 0
        self.calls, self.models = [], []
        self.failure_at = None
        self.failure_mode = None
        self.power = {}
        self.wrapper_sha = sha(Path(batch.__file__).read_bytes())
        self.helper_sha = sha(Path(batch.pair_helper.__file__).read_bytes())

    def make_batch(self):
        self.batch_counter += 1
        directory = self.root / ("batch-%02d" % self.batch_counter); directory.mkdir()
        rows = []
        for block in (1, 2):
            for workload in ("B01", "B02"):
                for control in ("A0", "A2"):
                    position = len(rows) + 1; pair_id, clock = str(uuid.uuid4()), str(uuid.uuid4())
                    order = "AB" if block == 1 else "BA"
                    parent = directory / ("pair-%02d" % position); parent.mkdir()
                    plans = {}
                    for side in "AB":
                        value = batch.collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb, "102", sha(SERIAL.encode()),
                                                        workload, control, "unobserved", 5, 2, 3, side, order, "test", "1", 102,
                                                        parent / side, pair_id, clock)
                        path = Path(value["plan"]); plan = json.loads(path.read_text())
                        plans[side] = dict(path=path.relative_to(self.root).as_posix(), run_id=plan["run_id"],
                                           session_id=plan["session_id"], sha256=value["plan_sha256"])
                    rows.append(dict(position=position, block=block, workload=workload, control=control, order=order,
                                     pair_id=pair_id, host_clock_token=clock, plans=plans))
        first = json.loads((self.root / rows[0]["plans"]["A"]["path"]).read_text())
        value = dict(schema_version=1, record_type="loop_pilot_aa_offline_batch_plan", backend="102", blocks=2,
                     created_device_operations=False, device_serial_sha256=sha(SERIAL.encode()), expected_framework=first["expected_framework"],
                     expected_framework_provenance="test expectation only", artifact_audit=first["artifact_audit"], pairs=rows,
                     parameters=first["parameters"], performance_gate="not_evaluated", scope="same-control exploratory A/A",
                     seed=1, stop_policy="stop on first failure, no replay",
                     pair_verifier=batch.common.file_pin(self.root, "tools/performance/verify_loop_pilot_pair.py"),
                     noise_analyzer=batch.common.file_pin(self.root, "tools/performance/analyze_pilot_aa_noise.py"))
        path = directory / "batch-plan.json"; path.write_bytes(batch.common.json_bytes(value))
        digest = sha(path.read_bytes())
        device = dict(device_id_sha256=sha(SERIAL.encode()), build_fingerprint_sha256=sha(b"test-fingerprint"),
                      boot_id=BOOT, android_api=33, abi="arm64-v8a", android_user=0)
        state = dict(device=device)
        for label in ("host", "module"):
            item = first["artifacts"][label]
            state[label] = dict(package=item["package"], path="/data/app/pinned/" + item["package"] + "/base.apk",
                                stat="10 100 " + str(item["bytes"]), sha256=item["sha256"], uid=10123 if label == "host" else 10124)
        preflight = dict(schema=1, record_type="loop_pilot_batch_preflight", batch_sha256=digest, read_only=True,
                         host_process_absent=True, host_and_module=state, battery_deci_celsius=280, framework_sha256="e" * 64)
        pre_path = directory / "preflight.json"; pre_path.write_bytes(batch.common.json_bytes(preflight))
        return path, digest, value, pre_path, sha(pre_path.read_bytes())

    def transport(self, adb, serial, directory, token):
        plan = json.loads((directory.parent / "plan.json").read_text())
        model = fixtures.Model(plan)
        model.launches = len(self.calls) * 100  # Distinct fake PID/start ticks for each new process, in host order.
        model.temperatures = list(self.power.get(len(self.calls), []))
        if self.failure_at == len(self.calls) and self.failure_mode != "raise": setattr(model, self.failure_mode, True)
        self.models.append(model)
        return FakeAdb(model, directory)

    def fake_execute(self, path, digest, serial, **kwargs):
        self.calls.append(str(path))
        if self.failure_at == len(self.calls) and self.failure_mode == "raise": raise RuntimeError("controlled execute failure")
        return batch.collector.execute(path, digest, serial, **kwargs)

    def run_batch(self, prepared, **kwargs):
        path, digest, _, pre_path, pre_sha = prepared
        return batch.run(self.root, path, digest, self.wrapper_sha, self.helper_sha, SERIAL, pre_path, pre_sha,
                         idle_confirmed=True, scope_confirmed=True, execute_fn=self.fake_execute, transport_factory=self.transport, **kwargs)

    def test_registered_order_reported_only_continues_and_claim_never_replays(self):
        prepared = self.make_batch(); result = self.run_batch(prepared)
        self.assertEqual("batch_records_collected", result["result"])
        expected = [str(self.root / row["plans"][side]["path"]) for row in prepared[2]["pairs"] for side in row["order"]]
        self.assertEqual(expected, self.calls); self.assertEqual(16, len(self.models))
        self.assertTrue(all(item["result"] == "reported_only" for item in result["pairs"]))
        self.assertEqual([], result["not_executed"]); self.assertEqual("not_evaluated", result["performance_gate"])
        self.assertTrue((prepared[0].parent / "batch-execution/noise-description.json").exists())
        with self.assertRaises(batch.InvalidRecord): self.run_batch(prepared)
        self.assertEqual(16, len(self.calls))

    def test_failure_unknown_stop_poison_or_zero_stops_without_next_side(self):
        for mode in ("raise", "stop_timeout", "poison_after_terminal", "zero_samples"):
            prepared = self.make_batch(); self.calls.clear(); self.models.clear()
            self.failure_at = 1; self.failure_mode = mode
            if mode == "zero_samples":
                original = self.transport
                def zero(adb, serial, directory, token):
                    value = original(adb, serial, directory, token); value.model.zero_samples = "all"; return value
                with patch.object(self, "transport", side_effect=zero): result = self.run_batch(prepared)
            else: result = self.run_batch(prepared)
            self.assertEqual("batch_stopped", result["result"], mode); self.assertEqual(1, len(self.calls))
            self.assertEqual(15, len(result["not_executed"])); self.assertTrue(all(model.stops <= 1 for model in self.models))

    def test_global_temperature_baseline_is_enforced_inside_collector_cleanup(self):
        prepared = self.make_batch(); pre = json.loads(prepared[3].read_text()); pre["battery_deci_celsius"] = 240
        prepared[3].write_bytes(batch.common.json_bytes(pre)); prepared = (*prepared[:4], sha(prepared[3].read_bytes()))
        self.power[1] = [280, 290]  # Per-side rise is only 1 C; from batch T0 it reaches 5 C.
        result = self.run_batch(prepared)
        self.assertEqual("batch_stopped", result["result"]); self.assertEqual(1, len(self.calls))
        self.assertIn("temperature rise", result["reason"]); self.assertEqual(1, self.models[0].stops)
        self.assertTrue(result["steps"][0]["result"]["stop_confirmed"])

    def test_preflight_hash_device_and_installed_bytes_mismatch_prevent_execution(self):
        for key in ("hash", "device", "installed"):
            prepared = self.make_batch(); pre = json.loads(prepared[3].read_text())
            if key == "device": pre["host_and_module"]["device"]["device_id_sha256"] = "0" * 64
            elif key == "installed": pre["host_and_module"]["host"]["sha256"] = "0" * 64
            else: pre["framework_sha256"] = "0" * 64
            prepared[3].write_bytes(batch.common.json_bytes(pre))
            if key != "hash": prepared = (*prepared[:4], sha(prepared[3].read_bytes()))
            with self.assertRaises(batch.InvalidRecord): self.run_batch(prepared)
        self.assertEqual([], self.calls)

    def test_permissions_serial_and_both_external_source_hashes_prevent_execution(self):
        prepared = self.make_batch(); path, digest, _, pre, pre_sha = prepared
        for idle, scope, serial, wrapper, helper in ((False, True, SERIAL, self.wrapper_sha, self.helper_sha),
                (True, False, SERIAL, self.wrapper_sha, self.helper_sha), (True, True, "other", self.wrapper_sha, self.helper_sha),
                (True, True, SERIAL, "0" * 64, self.helper_sha), (True, True, SERIAL, self.wrapper_sha, "0" * 64)):
            with self.assertRaises(batch.InvalidRecord):
                batch.run(self.root, path, digest, wrapper, helper, serial, pre, pre_sha, idle, scope,
                          execute_fn=self.fake_execute, transport_factory=self.transport)
        self.assertEqual([], self.calls); self.assertFalse((path.parent / "batch-execution").exists())

    def test_plan_drift_unbalanced_order_or_loaded_tool_mismatch_prevent_execution(self):
        for mode in ("plan", "balance", "loaded"):
            prepared = self.make_batch()
            if mode == "plan":
                target = self.root / prepared[2]["pairs"][-1]["plans"]["B"]["path"]; target.write_bytes(target.read_bytes() + b" ")
                with self.assertRaises(batch.InvalidRecord): self.run_batch(prepared)
            elif mode == "balance":
                value = copy.deepcopy(prepared[2]); value["pairs"][-1]["order"] = "AB"
                prepared[0].write_bytes(batch.common.json_bytes(value))
                changed = (prepared[0], sha(prepared[0].read_bytes()), value, prepared[3], prepared[4])
                with self.assertRaises(batch.InvalidRecord): self.run_batch(changed)
            else:
                other = self.root / "wrong-loaded.py"; other.write_bytes(b"wrong loaded pair helper")
                with patch.object(batch.pair_helper, "__file__", str(other)):
                    with self.assertRaises(batch.InvalidRecord): self.run_batch(prepared)
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
