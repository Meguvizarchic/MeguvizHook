import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import b07_adb_transport as transport


CLOCK = "9a6bd7a5-c99d-4e27-8594-c0486158f92b"
SERIAL = "test-device-123"


class FakeProcess:
    def __init__(self, returncode=0):
        self.pid = 4321
        self.returncode = returncode
        self.terminated = 0
        self.killed = 0

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated += 1
        self.returncode = -15

    def kill(self):
        self.killed += 1
        self.returncode = -9

    def wait(self, timeout):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake-child", timeout)
        return self.returncode


class B07AdbTransportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.adb = self.root / "fake-adb.exe"
        self.adb.write_bytes(b"fake executable identity, never executed")
        self.target = transport.TargetAdb(self.adb, SERIAL, self.root, CLOCK)

    def tearDown(self):
        self.temporary.cleanup()

    def factory(self, process=None, stdout=b"ok\n", stderr=b""):
        process = FakeProcess() if process is None else process

        def create(argv, **kwargs):
            self.assertEqual([str(self.adb.resolve()), "-s", SERIAL], argv[:3])
            kwargs["stdout"].write(stdout)
            kwargs["stdout"].flush()
            kwargs["stderr"].write(stderr)
            kwargs["stderr"].flush()
            return process
        return create

    def read_record(self, suffix, index=1):
        return json.loads((self.target.ledger_dir / ("adb-%04d.%s.json" % (index, suffix))).read_text())

    def test_explicit_selector_intent_and_same_returned_bytes(self):
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(stdout=b"exact\x00bytes")) as popen:
            result = self.target.run("identity", ["get-state"])
        self.assertEqual(b"exact\x00bytes", result.stdout)
        self.assertEqual(b"", result.stderr)
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, popen.call_count)
        intent, final = self.read_record("intent"), self.read_record("result")
        self.assertEqual("-s", intent["selector"]["option"])
        self.assertTrue(intent["selector"]["explicit"])
        self.assertEqual(hashlib.sha256(SERIAL.encode()).hexdigest(), intent["selector"]["serial_sha256"])
        self.assertNotIn(SERIAL, json.dumps(intent))
        self.assertEqual(intent["argv_sha256"], final["argv_sha256"])
        self.assertEqual(hashlib.sha256(result.stdout).hexdigest(), final["stdout"]["sha256"])
        self.assertEqual("completed", final["status"])
        self.assertFalse(final["outcome_unknown"])
        self.assertLessEqual(final["start_monotonic_ns"], final["end_monotonic_ns"])

    def test_intent_failure_never_spawns_or_allows_later_command(self):
        with patch.object(transport, "_write", side_effect=OSError("disk full")), \
                patch.object(transport.subprocess, "Popen") as popen:
            with self.assertRaises(transport.TransportError) as raised:
                self.target.run("identity", ["get-state"])
        self.assertFalse(raised.exception.outcome_unknown)
        self.assertFalse(popen.called)
        self.assertTrue(self.target.poisoned)
        with patch.object(transport.subprocess, "Popen") as later:
            with self.assertRaises(transport.TransportError):
                self.target.run("identity", ["get-state"])
            self.assertFalse(later.called)

    def test_result_record_failure_poisoned_after_one_command(self):
        real_write = transport._write

        def write(path, value):
            if path.name.endswith("result.json"):
                raise OSError("disk full after command")
            return real_write(path, value)

        with patch.object(transport, "_write", side_effect=write), \
                patch.object(transport.subprocess, "Popen", side_effect=self.factory()) as popen:
            with self.assertRaises(transport.TransportError) as raised:
                self.target.run("identity", ["get-state"])
        self.assertTrue(raised.exception.outcome_unknown)
        self.assertEqual(1, popen.call_count)
        self.assertTrue(self.target.poisoned)
        self.assertEqual(b"ok\n", (self.target.ledger_dir / "adb-0001.stdout").read_bytes())

    def test_timeout_stops_only_owned_child_and_never_replays(self):
        child = FakeProcess(None)
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(child)) as popen, \
                patch.object(transport.time, "monotonic", side_effect=[10.0, 12.0]):
            with self.assertRaises(transport.TransportError) as raised:
                self.target.run("launch", ["shell", "am", "start", "fixed-target"], timeout=1)
        self.assertTrue(raised.exception.outcome_unknown)
        self.assertEqual(1, child.terminated)
        self.assertEqual(0, child.killed)
        self.assertEqual(1, popen.call_count)
        self.assertEqual("timeout_outcome_unknown", self.read_record("result")["status"])

    def test_nonzero_exit_is_retained_and_poisoned(self):
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(FakeProcess(7), stderr=b"failure")):
            with self.assertRaises(transport.TransportError) as raised:
                self.target.run("identity", ["get-state"])
        self.assertTrue(raised.exception.outcome_unknown)
        self.assertEqual(7, self.read_record("result")["returncode"])
        self.assertEqual(b"failure", (self.target.ledger_dir / "adb-0001.stderr").read_bytes())

    def test_spawn_failure_records_no_child_and_is_not_retried(self):
        with patch.object(transport.subprocess, "Popen", side_effect=OSError("cannot execute")) as popen:
            with self.assertRaises(transport.TransportError) as raised:
                self.target.run("identity", ["get-state"])
        self.assertFalse(raised.exception.outcome_unknown)
        self.assertEqual(1, popen.call_count)
        row = self.read_record("result")
        self.assertIsNone(row["host_pid"])
        self.assertEqual("spawn_failed", row["status"])

    def test_explicitly_accepted_exit_retains_actual_returncode(self):
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(FakeProcess(1))):
            result = self.target.run("presence", ["shell", "pidof", "fixed-package"], accepted=(0, 1))
        self.assertEqual(1, result.returncode)
        self.assertEqual(1, self.read_record("result")["returncode"])
        self.assertFalse(self.target.poisoned)

    def test_forbidden_global_commands_and_bad_arguments_do_not_spawn(self):
        with patch.object(transport.subprocess, "Popen") as popen:
            for args in ([], ["-s", "other", "get-state"], ["devices"], ["kill-server"],
                         ["connect", "other"], ["logcat", "-c"], ["shell", "bad\x00arg"]):
                with self.subTest(args=args), self.assertRaises(ValueError):
                    self.target.run("invalid", args)
        self.assertFalse(popen.called)
        self.assertEqual([], list(self.target.ledger_dir.iterdir()))

    def test_changed_executable_is_rejected_before_spawn(self):
        self.adb.write_bytes(b"changed fake executable")
        with patch.object(transport.subprocess, "Popen") as popen:
            with self.assertRaises(transport.TransportError):
                self.target.run("identity", ["get-state"])
        self.assertFalse(popen.called)
        self.assertTrue(self.target.poisoned)

    def test_output_read_failure_is_recorded_unknown(self):
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory()), \
                patch.object(transport, "_stable_bytes", side_effect=OSError("changed output")):
            with self.assertRaises(transport.TransportError):
                self.target.run("identity", ["get-state"])
        row = self.read_record("result")
        self.assertEqual("capture_failure_outcome_unknown", row["status"])
        self.assertTrue(row["outcome_unknown"])
        self.assertTrue(self.target.poisoned)

    def test_return_does_not_re_read_bytes_after_ledger_hashing(self):
        real_result = self.target._result

        def record(*args, **kwargs):
            result = real_result(*args, **kwargs)
            (self.target.ledger_dir / "adb-0001.stdout").write_bytes(b"later external change")
            return result

        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(stdout=b"captured")), \
                patch.object(self.target, "_result", side_effect=record):
            result = self.target.run("identity", ["get-state"])
        self.assertEqual(b"captured", result.stdout)
        self.assertEqual(hashlib.sha256(result.stdout).hexdigest(), self.read_record("result")["stdout"]["sha256"])

    def test_fast_command_output_limit_keeps_original_bytes(self):
        with patch.object(transport, "MAX_OUTPUT", 4), \
                patch.object(transport.subprocess, "Popen", side_effect=self.factory(stdout=b"12345")):
            with self.assertRaises(transport.TransportError):
                self.target.run("identity", ["get-state"])
        self.assertEqual(b"12345", (self.target.ledger_dir / "adb-0001.stdout").read_bytes())
        self.assertEqual("capture_limit_outcome_unknown", self.read_record("result")["status"])

    def test_stream_fixed_command_and_idempotent_owned_stop(self):
        child = FakeProcess(None)
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(child)) as popen:
            capture = self.target.start_logcat("side-A")
            self.assertIsNone(capture.poll())
            summary = capture.stop()
            self.assertEqual(summary, capture.stop())
        self.assertEqual(list(transport.LOGCAT_ARGS), popen.call_args.args[0][3:])
        self.assertNotIn("-c", popen.call_args.args[0])
        self.assertEqual(1, child.terminated)
        self.assertEqual("terminated_by_owner", summary["status"])
        self.assertFalse(summary["outcome_unknown"])
        self.assertEqual(b"ok\n", capture.raw_path.read_bytes())
        self.assertEqual(child.pid, self.read_record("started")["host_pid"])

    def test_stream_start_record_failure_closes_owned_child(self):
        child = FakeProcess(None)
        real_write = transport._write

        def write(path, value):
            if path.name.endswith("started.json"):
                raise OSError("cannot publish start")
            return real_write(path, value)

        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(child)), \
                patch.object(transport, "_write", side_effect=write):
            with self.assertRaises(transport.TransportError):
                self.target.start_logcat()
        self.assertEqual(1, child.terminated)
        self.assertTrue(self.target.poisoned)
        self.assertTrue(self.read_record("result")["outcome_unknown"])

    def test_stream_limit_and_early_exit_are_not_success(self):
        for content, child in ((b"12345", FakeProcess(None)), (b"ok", FakeProcess(0))):
            with self.subTest(content=content):
                with tempfile.TemporaryDirectory() as directory:
                    target = transport.TargetAdb(self.adb, SERIAL, Path(directory), CLOCK)
                    with patch.object(transport, "MAX_OUTPUT", 4), \
                            patch.object(transport.subprocess, "Popen", side_effect=self.factory(child, stdout=content)):
                        capture = target.start_logcat()
                        with self.assertRaises(transport.TransportError):
                            capture.stop()
                    self.assertTrue(target.poisoned)
                    self.assertEqual(content, capture.raw_path.read_bytes())
                    row = json.loads((target.ledger_dir / "adb-0001.result.json").read_text())
                    self.assertTrue(row["outcome_unknown"])

    def test_poison_does_not_prevent_stopping_existing_local_capture(self):
        child = FakeProcess(None)
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(child)):
            capture = self.target.start_logcat()
            self.target._poisoned = True
            capture.stop("collector_failure")
        self.assertEqual(1, child.terminated)
        self.assertTrue(self.target.poisoned)

    def test_logical_collection_failure_can_close_healthy_capture_without_poison(self):
        child = FakeProcess(None)
        with patch.object(transport.subprocess, "Popen", side_effect=self.factory(child)):
            capture = self.target.start_logcat()
            summary = capture.stop("collector_finally")
        self.assertEqual("terminated_by_owner", summary["status"])
        self.assertEqual("collector_finally", summary["detail"])
        self.assertFalse(summary["outcome_unknown"])
        self.assertFalse(self.target.poisoned)


if __name__ == "__main__":
    unittest.main()
