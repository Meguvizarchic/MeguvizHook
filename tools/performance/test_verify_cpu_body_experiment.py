import hashlib
import json
import os
import subprocess
import sys
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import verify_cpu_body_experiment as experiment
from verify_cpu_body_pair import InvalidRecord


class ExperimentTest(unittest.TestCase):
    def fixture(self):
        # Keep fixtures for inspection; never delete pre-existing evidence.
        root = Path(__file__).resolve().parents[2] / "build" / ("experiment-test-" + uuid.uuid4().hex)
        root.mkdir(parents=True)
        return root

    def test_schedule_has_complete_balanced_blocks_and_seed(self):
        for seed in (0, 17, 2**64 - 1):
            rows = experiment.schedule(seed)
            self.assertEqual(rows, experiment.schedule(seed))
            self.assertEqual(list(range(1, 17)), [r["position"] for r in rows])
            for block in range(1, 5):
                self.assertEqual(set(experiment.GROUPS), {(r["workload"], r["control"]) for r in rows if r["block"] == block})
            for group in experiment.GROUPS:
                self.assertEqual(["AB", "AB", "BA", "BA"], sorted(r["pair_order"] for r in rows
                                                                   if (r["workload"], r["control"]) == group))
        for invalid in (-1, 2**64, True, "1"):
            with self.assertRaises(InvalidRecord):
                experiment.schedule(invalid)

    def test_zero_samples_and_medians_remain_unresolved_with_all_pairs(self):
        report = {"warmup": 1, "samples": 3, "readings": [
            {"ordinal": i, "before_cpu_start_ns": 100, "before_cpu_end_ns": 100 + delta}
            for i, delta in enumerate((50, 0, 0, 8))]}
        stats = experiment.sample_stats(report, "before")
        self.assertEqual([0, 0, 8], stats["intervals_ns"])
        self.assertEqual(2, stats["zero_count"])
        self.assertEqual(0, stats["median_ns"])
        def pair(index, a, b, status="body_cpu_pair_consistent"):
            return {"pair_id": str(index), "pair_order": ("AB", "AB", "BA", "BA")[index], "result": status,
                    "sides": {"A": {"stages": {"before": {"median_ns": a}}},
                              "B": {"stages": {"before": {"median_ns": b}}}}}
        pairs = [pair(0, 0, 10), pair(1, 10, 0), pair(2, 10, 20), pair(3, 10, 10, "unresolved")]
        result = experiment.summarize({("B02", "A0"): pairs})[0]
        self.assertEqual("unresolved", result["stages"]["before"]["result"])
        self.assertIsNone(result["stages"]["before"]["median_percent"])
        self.assertEqual([None, None, 100, None], [p["B_over_A_minus_one_percent"]
                                                   for p in result["stages"]["before"]["pairs"]])
        self.assertEqual(pairs, result["pairs"])

    def test_paths_reject_traversal_and_windows_reparse_points(self):
        root = self.fixture()
        (root / "ok").write_bytes(b"ok")
        self.assertEqual((root / "ok").resolve(), experiment.ref_path(root, "ok"))
        for path in ("../ok", "a/../ok", "/ok", "a//ok", "a\\ok", "C:ok"):
            with self.subTest(path=path), self.assertRaises(InvalidRecord):
                experiment.ref_path(root, path)
        with patch.object(Path, "lstat", return_value=SimpleNamespace(st_mode=0o100600, st_file_attributes=0x400)):
            with self.assertRaises(InvalidRecord):
                experiment.check_reparse(root / "ok")
        try:
            os.symlink(root / "ok", root / "link")
        except (OSError, NotImplementedError):
            return  # Creating links may require Windows privileges.
        with self.assertRaises(InvalidRecord):
            experiment.ref_path(root, "link")

    def test_inventory_is_exact_and_digests_are_binding(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        (run / "data").write_bytes(b"abc")
        inv = root / "inventory.json"
        data = {"schema_version": 1, "scope": "test", "files_count": 1,
                "files": [{"path": "run/data", "bytes": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}]}
        raw = json.dumps(data).encode()
        inv.write_bytes(raw)
        digest = hashlib.sha256(raw).hexdigest()
        self.assertEqual({"run/data": data["files"][0]["sha256"]}, experiment.verify_inventory(root, run, inv, digest))
        with self.assertRaises(InvalidRecord):
            experiment.verify_inventory(root, run, inv, "0" * 64)
        (run / "extra").write_bytes(b"extra")
        with self.assertRaises(InvalidRecord):
            experiment.verify_inventory(root, run, inv, digest)

    def test_many_commands_cache_is_bounded_and_final_check_detects_tamper(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        records = []
        for index in range(1, 1201):
            path = run / ("adb-%04d.json" % index)
            data = ("record-%04d" % index).encode()
            path.write_bytes(data)
            records.append({"path": path.relative_to(root).as_posix(), "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
        raw = json.dumps({"schema_version": 1, "scope": "test", "files_count": len(records), "files": records}).encode()
        inventory_path = root / "inventory.json"
        inventory_path.write_bytes(raw)
        with patch.object(experiment, "MAX_COMMAND_CACHE", 100):
            inventory = experiment.verify_inventory(root, run, inventory_path, hashlib.sha256(raw).hexdigest())
            self.assertEqual(1200, len(inventory))
            self.assertLessEqual(inventory.command_bytes, 100)
            self.assertGreater(len(inventory.commands), 0)
            self.assertLess(len(inventory.commands), len(inventory))
            inventory.finish()
            target = root / next(iter(inventory.commands))
            before = target.stat()
            target.write_bytes(b"x" * before.st_size)
            os.utime(target, ns=(before.st_atime_ns, before.st_mtime_ns))
            with self.assertRaises(InvalidRecord):
                inventory.finish()

    def test_inventory_final_check_rejects_new_reparse_point(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        file = run / "data"
        file.write_bytes(b"abc")
        raw = json.dumps({"schema_version": 1, "scope": "test", "files_count": 1,
                          "files": [{"path": "run/data", "bytes": 3,
                                     "sha256": hashlib.sha256(b"abc").hexdigest()}]}).encode()
        inventory_path = root / "inventory.json"
        inventory_path.write_bytes(raw)
        inventory = experiment.verify_inventory(root, run, inventory_path, hashlib.sha256(raw).hexdigest())
        original = Path.lstat
        def changed(path, *args, **kwargs):
            info = original(path, *args, **kwargs)
            if path == file:
                return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0x400)
            return info
        with patch.object(Path, "lstat", autospec=True, side_effect=changed):
            with self.assertRaises(InvalidRecord):
                inventory.finish()

    def test_consumed_and_final_bytes_bind_non_cached_inventory_files(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        data = b"initial-content"
        names = ("ordinary.json", "adb-0001.json")
        records = []
        for name in names:
            path = run / name
            path.write_bytes(data)
            records.append({"path": path.relative_to(root).as_posix(), "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
        inv_path = root / "inventory.json"
        raw = json.dumps({"schema_version": 1, "scope": "test", "files_count": 2, "files": records}).encode()
        inv_path.write_bytes(raw)
        with patch.object(experiment, "MAX_COMMAND_CACHE", 0):
            inventory = experiment.verify_inventory(root, run, inv_path, hashlib.sha256(raw).hexdigest())
        self.assertEqual({}, inventory.commands)
        for name in names:
            target = run / name
            old = target.stat()
            target.write_bytes(b"x" * len(data))
            os.utime(target, ns=(old.st_atime_ns, old.st_mtime_ns))
            root_token = experiment.ACTIVE_ROOT.set(root)
            inventory_token = experiment.ACTIVE_INVENTORY.set(inventory)
            try:
                with self.assertRaises(InvalidRecord):
                    experiment.read_stable(target)
            finally:
                experiment.ACTIVE_INVENTORY.reset(inventory_token)
                experiment.ACTIVE_ROOT.reset(root_token)
            with self.assertRaises(InvalidRecord):
                inventory.finish()
            target.write_bytes(data)
            os.utime(target, ns=(old.st_atime_ns, old.st_mtime_ns))
        unexpected = run / "unexpected.json"
        unexpected.write_bytes(b"not indexed")
        root_token = experiment.ACTIVE_ROOT.set(root)
        inventory_token = experiment.ACTIVE_INVENTORY.set(inventory)
        try:
            with self.assertRaises(InvalidRecord):
                experiment.read_stable(unexpected)
        finally:
            experiment.ACTIVE_INVENTORY.reset(inventory_token)
            experiment.ACTIVE_ROOT.reset(root_token)

    def test_external_pinned_bytes_are_verified_before_parsing(self):
        root = self.fixture()
        path = root / "smoke.json"
        data = b'{"phase":"smoke"}'
        path.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual(data, experiment.read_verified(path, digest))
        old = path.stat()
        path.write_bytes(b'{"phase":"other"}')
        os.utime(path, ns=(old.st_atime_ns, old.st_mtime_ns))
        with self.assertRaises(InvalidRecord):
            experiment.read_verified(path, digest)

    def test_parent_reparse_swap_is_rejected_before_next_file_open(self):
        root = self.fixture()
        run = root / "run"
        parent = run / "nested"
        parent.mkdir(parents=True)
        for name in ("first", "second"):
            (parent / name).write_bytes(b"abc")
        inventory = experiment.EvidenceInventory(root, run, root / "inventory.json", "0" * 64)
        item = lambda name: {"path": "run/nested/" + name, "bytes": 3,
                             "sha256": hashlib.sha256(b"abc").hexdigest()}
        inventory.add(item("first"))
        original = Path.lstat
        def changed(path, *args, **kwargs):
            info = original(path, *args, **kwargs)
            if path == parent:
                return SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0x400)
            return info
        with patch.object(Path, "lstat", autospec=True, side_effect=changed):
            with patch.object(experiment, "read_stable_info") as read:
                with self.assertRaises(InvalidRecord):
                    inventory.add(item("second"))
                read.assert_not_called()

    def test_boundary_readings_require_expected_fields(self):
        root = self.fixture()
        battery = root / "battery.txt"
        battery.write_bytes(b"temperature: 371\r\n")
        self.assertEqual(371, experiment.boundary_value(battery, experiment.TEMP))
        battery.write_bytes(b"status: unknown\n")
        with self.assertRaises(InvalidRecord):
            experiment.boundary_value(battery, experiment.TEMP)

    def test_command_chain_binds_observations_and_rejects_tamper(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        host, apk, boot = "example.cpu.host", "a" * 64, "fdc77040-7eb2-4a96-9cc3-5535619c63da"
        identity = {"serial_sha256": hashlib.sha256(b"device-123").hexdigest(),
                    "fingerprint_sha256": hashlib.sha256(b"fingerprint").hexdigest(),
                    "boot_id": boot, "api": 33, "abi": "arm64-v8a"}
        initial = {"identity": identity, "lspd_pid": 3147,
                   "daemon_mapping_identity": {"major": 0, "minor": 8, "inode": 123, "mapped_segments": 1}}
        entries = []
        def record(args, output=b"", exit=0):
            entries.append({"args": args, "exit": exit, "stdout_hex": output.hex(), "stderr_hex": ""})
        daemon = "/data/adb/modules/zygisk_lsposed/daemon.apk"
        for _ in range(66):
            for args, value in ((["get-serialno"], b"device-123"),
                                (["shell", "getprop", "ro.build.fingerprint"], b"fingerprint"),
                                (["shell", "cat", "/proc/sys/kernel/random/boot_id"], boot.encode()),
                                (["shell", "getprop", "ro.build.version.sdk"], b"33"),
                                (["shell", "getprop", "ro.product.cpu.abi"], b"arm64-v8a"),
                                (["shell", "am", "get-current-user"], b"0")):
                record(args, value)
            record(["shell", "su -c 'grep -F " + daemon + " /proc/3147/maps'"],
                   ("1000-2000 r--p 0000 00:08 123 " + daemon).encode())
            record(["shell", "su -c 'stat -c \"%d %i\" " + daemon + "'"], b"8 123")
        processes = {}
        for n in range(32):
            pid, tick, uid = 1001 + n, str(2001 + n), 10222
            processes[(pid, tick)] = {"pid": pid, "uid": uid, "start_ticks": tick, "boot_id": boot}
            path = "/data/app/example/base.apk"
            record(["shell", "pidof", host], str(pid).encode())
            record(["exec-out", "cat", "/proc/%d/cmdline" % pid], host.encode() + b"\0")
            record(["shell", "pm", "list", "packages", "--user", "0", "-U", host],
                   ("package:" + host + " uid:" + str(uid)).encode())
            record(["shell", "su -c 'grep ^Uid: /proc/%d/status'" % pid], b"Uid: 10222 10222 10222 10222")
            record(["shell", "su -c 'cat /proc/%d/stat'" % pid],
                   (str(pid) + " (host) " + " ".join(["S"] + ["0"] * 18 + [tick])).encode())
            record(["shell", "pm", "path", "--user", "0", host], ("package:" + path).encode())
            record(["shell", "su -c 'sha256sum " + path + "'"], (apk + "  " + path).encode())
            record(["shell", "am", "force-stop", "--user", "0", host])
            record(["shell", "pidof", host], exit=1)
        inventory = {}
        for index, entry in enumerate(entries, 1):
            path = run / ("adb-%04d.json" % index)
            raw = json.dumps(entry).encode()
            path.write_bytes(raw)
            inventory[path.relative_to(root).as_posix()] = hashlib.sha256(raw).hexdigest()
        summary = experiment.check_adb_records(root, run, inventory, processes, host, apk, initial)
        self.assertEqual(32, summary["stops"])
        self.assertEqual(66, summary["daemon_mapping_observations"])
        missing = dict(inventory)
        del missing["run/adb-0002.json"]
        with self.assertRaises(InvalidRecord):
            experiment.check_adb_records(root, run, missing, processes, host, apk, initial)
        first = run / "adb-0001.json"
        first.write_bytes(b"{}")
        with self.assertRaises(InvalidRecord):
            experiment.check_adb_records(root, run, inventory, processes, host, apk, initial)
        restored = json.dumps(entries[0]).encode()
        first.write_bytes(restored)
        stop_index = next(i for i, entry in enumerate(entries) if "force-stop" in entry["args"])
        entries[stop_index]["args"][-1] = "example.other.app"
        stop_path = run / ("adb-%04d.json" % (stop_index + 1))
        changed = json.dumps(entries[stop_index]).encode()
        stop_path.write_bytes(changed)
        inventory[stop_path.relative_to(root).as_posix()] = hashlib.sha256(changed).hexdigest()
        with self.assertRaises(InvalidRecord):
            experiment.check_adb_records(root, run, inventory, processes, host, apk, initial)
        entries[6]["stdout_hex"] = ("1000-2000 r--p 0000 00:08 123 " + daemon + " (deleted)").encode().hex()
        with self.assertRaises(InvalidRecord):
            experiment.check_daemon_records(entries, initial)

    def test_helper_serial_is_statically_pinned_without_execution(self):
        root = self.fixture()
        helper = root / "work/evidence/local/physical-clock-collector/collect.py"
        helper.parent.mkdir(parents=True)
        source = b"SERIAL = 'device-123'\nraise RuntimeError('must not execute')\n"
        helper.write_bytes(source)
        sha = hashlib.sha256(source).hexdigest()
        serial = hashlib.sha256(b"device-123").hexdigest()
        experiment.check_clock_helper(root, sha, serial)
        with self.assertRaises(InvalidRecord):
            experiment.check_clock_helper(root, sha, "0" * 64)

    def test_cli_distinguishes_missing_input_and_invalid_inventory_without_writes(self):
        root = self.fixture()
        run = root / "run"
        run.mkdir()
        inv = root / "inventory.json"
        raw = json.dumps({"schema_version": 1, "scope": "test", "files_count": 0, "files": []}).encode()
        inv.write_bytes(raw)
        command = [sys.executable, str(Path(experiment.__file__)), str(run), "--evidence-root", str(root),
                   "--plan-sha256", "0" * 64, "--inventory", str(inv), "--inventory-sha256"]
        completed = subprocess.run(command + [hashlib.sha256(raw).hexdigest()], capture_output=True, text=True)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("incomplete", json.loads(completed.stdout)["result"])
        rejected = subprocess.run(command + ["0" * 64], capture_output=True, text=True)
        self.assertEqual(1, rejected.returncode)
        self.assertEqual("invalid", json.loads(rejected.stdout)["result"])
        self.assertEqual([], list(run.iterdir()))


if __name__ == "__main__":
    unittest.main()
