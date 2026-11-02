import copy
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_b07 as collector
from b07_adb_transport import CommandResult, TransportError
from test_verify_b07_install import events, log


SERIAL = "explicit-test-device"
BOOT = "721f4de7-706b-42b3-bb9e-91f23a8915a9"


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class Model:
    def __init__(self, plan):
        self.plan = plan
        self.commands = []
        self.factories = 0
        self.host = None
        self.launches = 0
        self.stops = 0
        self.launch_timeout = False
        self.stop_timeout = False
        self.bad_uid = False
        self.changed_start_ticks = False
        self.stat_reads = 0
        self.changed_apk = False
        self.bad_start = False
        self.no_end = False
        self.bad_final_hash = False
        self.early_capture_exit = False

    def factory(self, adb, serial, directory, token):
        self.factories += 1
        assert serial == SERIAL and token == self.plan["host_clock_token"]
        return FakeAdb(self, directory)


class Capture:
    def __init__(self, adb):
        self.adb = adb
        self.raw_path = adb.directory / "transport" / "stream.stdout"
        self.stderr_path = adb.directory / "transport" / "stream.stderr"
        self.raw_path.write_bytes(b"")
        self.stderr_path.write_bytes(b"")
        self.stopped = False
        self.summary = None

    def poll(self):
        if self.adb.model.early_capture_exit or (self.adb.model.no_end and self.adb.model.host is not None):
            return 0
        return None

    def stop(self, reason="completed"):
        if not self.stopped:
            self.stopped = True
            raw = self.raw_path.read_bytes()
            self.summary = {"stdout": {"path": self.raw_path.name, "bytes": len(raw),
                                       "sha256": "0" * 64 if self.adb.model.bad_final_hash else sha(raw)}}
        return self.summary


class FakeAdb:
    def __init__(self, model, directory):
        self.model, self.directory = model, directory
        (directory / "transport").mkdir()
        self.capture = None

    def start_logcat(self, label="capture"):
        assert label == "b07"
        self.capture = Capture(self)
        return self.capture

    def run(self, category, args, timeout=30, accepted=(0,)):
        args = list(args)
        self.model.commands.append((category, args))
        value = self.answer(args)
        return CommandResult(value, b"", 0, len(self.model.commands))

    def answer(self, args):
        m = self.model
        if args == ["get-serialno"]: return SERIAL.encode() + b"\n"
        if args == ["get-state"]: return b"device\n"
        if args == ["shell", "am", "get-current-user"]: return b"0\n"
        if args == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]: return BOOT.encode() + b"\n"
        if args == ["shell", "getprop", "ro.build.version.sdk"]: return b"33\n"
        if args == ["shell", "getprop", "ro.build.fingerprint"]: return b"test-fingerprint\n"
        if args == ["shell", "getprop", "ro.product.cpu.abi"]: return b"arm64-v8a\n"
        if args == ["shell", "dumpsys", "battery"]: return b"Current Battery Service state:\n  temperature: 280\n"
        if args == ["shell", "dumpsys", "thermalservice"]: return b"Thermal Status: 0\n"
        if args == ["shell", "pidof", collector.HOST]:
            return (str(m.host["pid"]).encode() + b"\n") if m.host else b""
        if args[:3] == ["shell", "pm", "path"]:
            return ("package:/data/app/pinned/" + args[-1] + "/base.apk\n").encode()
        if args[:4] == ["shell", "pm", "list", "packages"]:
            uid = 10123 if args[-1] == collector.HOST else 10124
            return ("package:" + args[-1] + " uid:" + str(uid) + "\n").encode()
        if args[:3] == ["shell", "am", "start"]:
            m.launches += 1
            m.host = {"pid": 1233 + m.launches, "ticks": 10 * m.launches}
            extra = {args[i + 1]: args[i + 2] for i in range(len(args) - 2) if args[i] == "--es"}
            side = extra["side"]
            selected = events(m.plan["backend"])
            for event in selected:
                event.update(run_id=extra["run_id"], pair_id=extra["pair_id"],
                             session_id=extra["session_id"], side=side, pid=m.host["pid"])
            rank = m.plan["pair_order"].index(side)
            selected[1].update(wall_start_ns=100 + 1000 * rank, wall_end_ns=150 + 1000 * rank)
            if m.bad_start: selected[0]["samples"] = 2
            if m.no_end: selected.pop()
            self.capture.raw_path.write_bytes(log(selected, m.host["pid"], 5678 + m.launches))
            if m.launch_timeout: raise TransportError("launch timeout", True)
            return b"Status: ok\n"
        if args == ["shell", "am", "force-stop", "--user", "0", collector.HOST]:
            m.stops += 1
            if m.stop_timeout: raise TransportError("stop timeout", True)
            m.host = None
            return b""
        if args[:2] == ["exec-out", "cat"]:
            return collector.HOST.encode() + b"\0"
        if len(args) == 2 and args[0] == "shell" and args[1].startswith("su -c '"):
            script = args[1][7:-1]
            if script.startswith("stat -c ") or script.startswith("sha256sum "):
                path = script.split()[-1]
                item = next(x for x in m.plan["artifacts"].values() if "/" + x["package"] + "/" in path)
                if script.startswith("stat "):
                    return ("10 100 " + str(item["bytes"]) + "\n").encode()
                value = "0" * 64 if m.changed_apk and m.host else item["sha256"]
                return (value + "  " + path + "\n").encode()
            if script.endswith("/status"):
                uid = 99999 if m.bad_uid else 10123
                return ("Uid:\t" + "\t".join([str(uid)] * 4) + "\n").encode()
            if script.endswith("/stat"):
                m.stat_reads += 1
                tick = m.host["ticks"] + (1 if m.changed_start_ticks and m.stat_reads > 1 else 0)
                tail = ["S"] + ["0"] * 18 + [str(tick)]
                return (str(m.host["pid"]) + " (" + collector.HOST + ") " + " ".join(tail) + "\n").encode()
        raise AssertionError("unrecognized simulated command " + repr(args))


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        tools = self.root / "tools/performance";tools.mkdir(parents=True)
        for name in collector.TOOLS:
            shutil.copyfile(Path(collector.__file__).with_name(name), tools / name)
        self.adb = self.root / "fake-adb.exe";self.adb.write_bytes(b"not executable; FakeAdb only")
        rows = []
        for label, package in (("host", collector.HOST), ("101", collector.MODULES["101"]),
                               ("102", collector.MODULES["102"])):
            data = ("synthetic artifact " + label).encode(); p = self.root / (label + ".apk");p.write_bytes(data)
            rows.append({"package": package, "variant": "deviceTest", "apk": p.name,
                         "sha256": sha(data), "bytes": len(data), "signature_verified": True,
                         "scheme_v2_verified": True, "runtime_isolation_gate": "passed",
                         "certificate_sha256": "f" * 64})
        verification = {}
        for key in ("build_log", "signature_checks", "runtime_checks", "static_audit",
                    "static_evidence_manifest", "api101_reference_supplement"):
            p = self.root / (key + ".json");p.write_bytes(b"{}\n")
            verification[key] = p.name;verification[key + "_sha256"] = sha(p.read_bytes())
        prepared = {}
        for path_key, hash_key in (("artifact_record", "artifact_record_sha256"),
                                   ("workload_sources", "workload_sha256"),
                                   ("feature_manifest", "feature_manifest_sha256")):
            p = self.root / (path_key + ".json");p.write_bytes(b"{}\n")
            prepared[path_key] = p.name;prepared[hash_key] = sha(p.read_bytes())
        self.audit = self.root / "audit.json"
        self.audit.write_text(json.dumps({"status": "signed_artifacts_locally_verified",
                                         "artifacts": rows, "verification": verification,
                                         "prepared_inputs": prepared}), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def plan(self, backend="102", order="AB"):
        result = collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb,
                                   backend, sha(SERIAL.encode()), order, "test", "1", int(backend),
                                   self.root / ("plan-" + backend + order))
        path = Path(result["plan"])
        return path, result["plan_sha256"], json.loads(path.read_text(encoding="utf-8"))

    def execute(self, path, digest, model):
        return collector.execute(path, digest, SERIAL, True, True, model.factory)

    def test_prepare_is_offline_and_full_pair_executes_in_both_orders_and_generations(self):
        for backend, order in (("101", "AB"), ("102", "BA")):
            path, digest, plan = self.plan(backend, order);model = Model(plan)
            result = self.execute(path, digest, model)
            self.assertEqual("install_pair_consistent", result["result"])
            self.assertEqual(2, model.launches);self.assertEqual(2, model.stops)
            self.assertIsNone(model.host)
            run = path.parent / "execution"
            for side in ("A", "B"):
                self.assertTrue((run / ("side-" + side) / "raw-threadtime.log").is_file())
                self.assertTrue((run / ("side-" + side) / "owned-stop.json").is_file())
            self.assertEqual("install_pair_consistent", json.loads((run / "pair-inputs/result.json").read_text())["result"])
            with self.assertRaises(FileExistsError): self.execute(path, digest, model)
            self.assertEqual(2, model.launches)

    def test_confirmations_serial_and_pins_fail_before_any_transport(self):
        path, digest, plan = self.plan();model = Model(plan)
        for idle, scope in ((False, True), (True, False), (False, False)):
            with self.assertRaises(collector.InvalidRecord):
                collector.execute(path, digest, SERIAL, idle, scope, model.factory)
        with self.assertRaises(collector.InvalidRecord):
            collector.execute(path, digest, "different-device", True, True, model.factory)
        (self.root / "102.apk").write_bytes(b"changed")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_loaded_tool_pins_and_framework_cannot_be_bypassed(self):
        path, digest, plan = self.plan();model = Model(plan)
        (self.root / "tools/performance/verify_b07_pair.py").write_bytes(b"different source")
        with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)
        original = Path(collector.__file__).with_name("verify_b07_pair.py").read_bytes()
        (self.root / "tools/performance/verify_b07_pair.py").write_bytes(original)
        unexpected = self.root / "different-loaded-source.py";unexpected.write_bytes(b"not the pinned module")
        with patch.object(collector.sys.modules["verify_b07_pair"], "__file__", str(unexpected)):
            with self.assertRaises(collector.InvalidRecord): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)
        for value in ("null", "NULL", " "):
            with self.assertRaises(collector.InvalidRecord):
                collector.prepare(self.root, self.audit, sha(self.audit.read_bytes()), self.adb,
                                  "102", sha(SERIAL.encode()), "AB", value, "1", 102, self.root / "bad")

    def test_preexisting_host_is_never_launched_or_stopped(self):
        path, digest, plan = self.plan();model = Model(plan);model.host = {"pid": 900, "ticks": 1}
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual(0, model.launches);self.assertEqual(0, model.stops)

    def test_launch_timeout_has_no_replay_or_unproven_cleanup(self):
        path, digest, plan = self.plan();model = Model(plan);model.launch_timeout = True
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual(1, model.launches);self.assertEqual(0, model.stops)
        self.assertTrue((path.parent / "execution/side-A/raw-threadtime.log").is_file())

    def test_wrong_start_or_uid_does_not_establish_ownership(self):
        for mode in ("bad_start", "bad_uid"):
            with self.subTest(mode=mode):
                path, digest, plan = self.plan("101" if mode == "bad_start" else "102")
                model = Model(plan);setattr(model, mode, True)
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"])
                self.assertEqual(1, model.launches);self.assertEqual(0, model.stops)

    def test_changed_proc_start_time_never_becomes_owned(self):
        path, digest, plan = self.plan();model = Model(plan);model.changed_start_ticks = True
        result = self.execute(path, digest, model)
        self.assertEqual("collection_failed", result["result"])
        self.assertEqual(1, model.launches);self.assertEqual(0, model.stops)

    def test_copied_plan_and_missing_audit_pin_are_rejected_without_transport(self):
        path, digest, plan = self.plan();model = Model(plan)
        elsewhere = self.root / "copied";elsewhere.mkdir();copy_path = elsewhere / "plan.json"
        copy_path.write_bytes(path.read_bytes())
        with self.assertRaises(collector.InvalidRecord):
            collector.execute(copy_path, digest, SERIAL, True, True, model.factory)
        changed = copy.deepcopy(plan)
        changed["pins"] = [pin for pin in changed["pins"] if pin["path"] != "static_audit.json"]
        path.write_bytes(collector.json_bytes(changed))
        with self.assertRaises(collector.InvalidRecord):
            collector.execute(path, sha(path.read_bytes()), SERIAL, True, True, model.factory)
        self.assertEqual(0, model.factories)

    def test_apk_change_refuses_stop_and_stop_timeout_is_not_retried(self):
        for mode in ("changed_apk", "stop_timeout"):
            with self.subTest(mode=mode):
                path, digest, plan = self.plan("101" if mode == "changed_apk" else "102")
                model = Model(plan);setattr(model, mode, True)
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"])
                self.assertEqual(1, model.launches)
                self.assertEqual(0 if mode == "changed_apk" else 1, model.stops)

    def test_terminal_is_structured_and_stream_digest_must_match(self):
        for mode in ("no_end", "bad_final_hash"):
            with self.subTest(mode=mode):
                path, digest, plan = self.plan("101" if mode == "no_end" else "102")
                model = Model(plan);setattr(model, mode, True)
                result = self.execute(path, digest, model)
                self.assertEqual("collection_failed", result["result"])
                self.assertEqual(1, model.launches)
                self.assertFalse((path.parent / "execution/side-A/capture.json").exists())

    def test_initialization_or_claim_failure_never_sends_a_device_command(self):
        path, digest, plan = self.plan();model = Model(plan)
        with patch.object(collector, "write_json", side_effect=OSError("claim not persisted")):
            with self.assertRaises(OSError): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)
        with self.assertRaises(FileExistsError): self.execute(path, digest, model)
        self.assertEqual(0, model.factories)

    def test_live_prefix_allows_append_but_rejects_replacement_and_parent_apk_path(self):
        path = self.root / "growing.log";path.write_bytes(b"first\n")
        raw, identity = collector.live_log_prefix(path)
        with path.open("ab") as f:f.write(b"second\n")
        current, same = collector.live_log_prefix(path, identity)
        self.assertEqual(b"first\n", raw);self.assertEqual(b"first\nsecond\n", current)
        self.assertEqual(identity, same)
        with self.assertRaises(collector.CollectionError): collector.live_log_prefix(path, (0, 0))
        class Unsafe:
            def run(self, *args, **kwargs):
                return CommandResult(b"package:/data/app/../outside/base.apk\n", b"", 0, 1)
        with self.assertRaises(collector.CollectionError):
            collector.installed(Unsafe(), {"package": collector.HOST})


if __name__ == "__main__":
    unittest.main()
