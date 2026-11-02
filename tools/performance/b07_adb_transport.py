#!/usr/bin/env python3
"""Explicit-target ADB transport for the B07 collector; no implicit device or replay."""

import hashlib
import json
import os
import re
import stat
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path


MAX_OUTPUT = 16 * 1024 * 1024
MAX_STDERR = 1024 * 1024
POLL_SECONDS = 0.05
ALLOWED_COMMANDS = {"get-state", "get-serialno", "shell", "exec-out"}
LOGCAT_ARGS = ("logcat", "-v", "threadtime", "-T", "1", "MeguvizHookBench:I", "*:S")


class TransportError(RuntimeError):
    def __init__(self, message, outcome_unknown=False, command_index=None):
        super().__init__(message)
        self.outcome_unknown = outcome_unknown
        self.command_index = command_index


@dataclass(frozen=True)
class CommandResult:
    stdout: bytes
    stderr: bytes
    returncode: int
    command_index: int


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _json(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2, allow_nan=False)
            + "\n").encode("utf-8")


def _write(path, value):
    data = _json(value)
    with path.open("xb") as target:
        if target.write(data) != len(data):
            raise OSError("short transport record write")
        target.flush()
        os.fsync(target.fileno())
    if path.read_bytes() != data:
        raise OSError("transport record readback mismatch")


def _ordinary(path, directory=False):
    info = path.lstat()
    if (stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400
            or not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))):
        raise ValueError("transport path is not an ordinary " + ("directory" if directory else "file"))
    return info


def _file_record(path):
    if not path.exists():
        return None
    before = _ordinary(path)
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    after = _ordinary(path)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise OSError("transport evidence changed during hashing")
    return {"path": path.name, "bytes": after.st_size, "sha256": digest.hexdigest()}


def _stable_bytes(path, limit):
    before = _ordinary(path)
    with path.open("rb") as source:
        opened = os.fstat(source.fileno())
        data = source.read(limit + 1)
        ended = os.fstat(source.fileno())
    after = _ordinary(path)
    identity = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
    if (len(data) > limit or len(data) != opened.st_size
            or identity(before) != identity(opened) or identity(opened) != identity(ended)
            or identity(ended) != identity(after)):
        raise OSError("transport output changed while reading")
    return data


def _stop_owned(process):
    """Stop only this Popen child. Device command side effects remain unknown."""
    if process.poll() is not None:
        return process.returncode
    try:
        process.terminate()
    except OSError:
        if process.poll() is None:
            raise
    try:
        return process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        return process.wait(timeout=5)


class TargetAdb:
    def __init__(self, adb_path, serial, run_dir, host_clock_token):
        self.adb_path = Path(adb_path)
        _ordinary(self.adb_path)
        self.adb_path = self.adb_path.resolve(strict=True)
        if (not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial)):
            raise ValueError("an explicit bounded device serial is required")
        if not isinstance(host_clock_token, str) or str(uuid.UUID(host_clock_token)) != host_clock_token:
            raise ValueError("host_clock_token must be a canonical UUID")
        self.serial = serial
        self.serial_sha256 = _sha(serial.encode("utf-8"))
        self.host_clock_token = host_clock_token
        self.run_dir = Path(run_dir)
        _ordinary(self.run_dir, directory=True)
        self.run_dir = self.run_dir.resolve(strict=True)
        self.ledger_dir = self.run_dir / "transport"
        self.ledger_dir.mkdir()
        self.adb_sha256 = _file_record(self.adb_path)["sha256"]
        self._index = 0
        self._lock = threading.Lock()
        self._poisoned = False

    @property
    def poisoned(self):
        return self._poisoned

    def _reserve(self, category, args, mode, timeout=None):
        if (not isinstance(category, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", category)):
            raise ValueError("invalid transport category")
        args = tuple(args)
        if not args or any(not isinstance(arg, str) or "\0" in arg for arg in args):
            raise ValueError("ADB arguments must be nonempty strings without NUL")
        if (mode == "command" and args[0] not in ALLOWED_COMMANDS) or (mode == "stream" and args != LOGCAT_ARGS):
            raise ValueError("unsupported B07 ADB command")
        with self._lock:
            if self._poisoned:
                raise TransportError("transport is poisoned; no device command may be replayed", True)
            try:
                executable_hash = _file_record(self.adb_path)["sha256"]
            except (OSError, ValueError, TypeError) as exc:
                self._poisoned = True
                raise TransportError("ADB executable cannot be rechecked", False) from exc
            if executable_hash != self.adb_sha256:
                self._poisoned = True
                raise TransportError("ADB executable changed", False)
            self._index += 1
            index = self._index
            argv = [str(self.adb_path), "-s", self.serial, *args]
            record = {"schema_version": 1, "record_type": "b07_adb_intent", "index": index,
                      "category": category, "mode": mode, "host_clock_token": self.host_clock_token,
                      "start_monotonic_ns": time.monotonic_ns(), "timeout_seconds": timeout,
                      "selector": {"option": "-s", "explicit": True, "serial_sha256": self.serial_sha256},
                      "adb_name": self.adb_path.name, "adb_sha256": self.adb_sha256,
                      "argv_sha256": _sha(json.dumps(argv, ensure_ascii=True, separators=(",", ":")).encode("utf-8")),
                      "argv_redacted": [self.adb_path.name, "-s", "<serial-sha256:" + self.serial_sha256 + ">", *args]}
            try:
                _write(self.ledger_dir / ("adb-%04d.intent.json" % index), record)
            except (OSError, ValueError) as exc:
                self._poisoned = True
                raise TransportError("intent record failed; command was not started", False, index) from exc
        return index, argv, record

    def _result(self, index, intent, process, status, unknown, stdout, stderr, detail=None,
                stdout_data=None, stderr_data=None):
        result = {"schema_version": 1, "record_type": "b07_adb_result", "index": index,
                  "category": intent["category"], "mode": intent["mode"],
                  "host_clock_token": self.host_clock_token, "start_monotonic_ns": intent["start_monotonic_ns"],
                  "end_monotonic_ns": time.monotonic_ns(), "selector": intent["selector"],
                  "argv_sha256": intent["argv_sha256"], "adb_sha256": self.adb_sha256,
                  "status": status, "outcome_unknown": unknown,
                  "host_pid": process.pid if process is not None else None,
                  "returncode": process.poll() if process is not None else None,
                  "stdout": ({"path": stdout.name, "bytes": len(stdout_data), "sha256": _sha(stdout_data)}
                             if stdout_data is not None else _file_record(stdout)),
                  "stderr": ({"path": stderr.name, "bytes": len(stderr_data), "sha256": _sha(stderr_data)}
                             if stderr_data is not None else _file_record(stderr))}
        if detail is not None:
            result["detail"] = detail
        _write(self.ledger_dir / ("adb-%04d.result.json" % index), result)
        return result

    def run(self, category, args, timeout=30, accepted=(0,)):
        if type(timeout) not in (int, float) or not 0 < timeout <= 120:
            raise ValueError("bounded positive timeout required")
        if not accepted or any(type(code) is not int for code in accepted):
            raise ValueError("accepted exit codes must be integers")
        index, argv, intent = self._reserve(category, args, "command", timeout)
        stdout = self.ledger_dir / ("adb-%04d.stdout" % index)
        stderr = self.ledger_dir / ("adb-%04d.stderr" % index)
        process = None
        status, unknown, failure = "spawn_failed", False, None
        try:
            with stdout.open("xb") as output, stderr.open("xb") as errors:
                process = subprocess.Popen(argv, stdout=output, stderr=errors,
                                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                deadline = time.monotonic() + timeout
                while process.poll() is None:
                    if stdout.stat().st_size > MAX_OUTPUT or stderr.stat().st_size > MAX_STDERR:
                        status, unknown = "capture_limit_outcome_unknown", True
                        break
                    if time.monotonic() >= deadline:
                        status, unknown = "timeout_outcome_unknown", True
                        break
                    time.sleep(POLL_SECONDS)
                else:
                    status = "completed" if process.returncode in accepted else "exit_failure"
                    unknown = status != "completed"
                if unknown:
                    self._poisoned = True
                    _stop_owned(process)
        except BaseException as exc:
            failure = exc
            unknown = process is not None
            self._poisoned = True
            if process is not None:
                try:
                    _stop_owned(process)
                except BaseException:
                    status, unknown = "host_stop_outcome_unknown", True
            if unknown and status != "host_stop_outcome_unknown":
                status = "capture_failure_outcome_unknown"
        # Catch fast-exit output overflow too; retain bytes without truncation.
        if process is not None and (stdout.stat().st_size > MAX_OUTPUT or stderr.stat().st_size > MAX_STDERR):
            status, unknown, self._poisoned = "capture_limit_outcome_unknown", True, True
        stdout_data = stderr_data = None
        if status == "completed" and failure is None:
            try:
                stdout_data = _stable_bytes(stdout, MAX_OUTPUT)
                stderr_data = _stable_bytes(stderr, MAX_STDERR)
            except (OSError, ValueError) as exc:
                failure = exc
                status, unknown, self._poisoned = "capture_failure_outcome_unknown", True, True
        try:
            self._result(index, intent, process, status, unknown, stdout, stderr,
                         type(failure).__name__ if failure is not None else None,
                         stdout_data, stderr_data)
        except (OSError, ValueError) as exc:
            self._poisoned = True
            raise TransportError("result record failed; no command may be replayed", process is not None, index) from exc
        if failure is not None and not isinstance(failure, Exception):
            raise failure
        if status != "completed" or failure is not None:
            self._poisoned = True
            raise TransportError("ADB " + category + " failed: " + status, unknown, index) from failure
        return CommandResult(stdout_data, stderr_data, process.returncode, index)

    def start_logcat(self, label="capture"):
        index, argv, intent = self._reserve(label, LOGCAT_ARGS, "stream")
        return OwnedCapture(self, index, argv, intent)


class OwnedCapture:
    def __init__(self, transport, index, argv, intent):
        self.transport, self.index, self.intent = transport, index, intent
        self.raw_path = transport.ledger_dir / ("adb-%04d.stdout" % index)
        self.stderr_path = transport.ledger_dir / ("adb-%04d.stderr" % index)
        self._lock = threading.RLock()
        self._stop_event = threading.Event()
        self._stopped = False
        self._summary = None
        self._error = None
        self._process = None
        self._output = self._errors = None
        try:
            self._output = self.raw_path.open("xb")
            self._errors = self.stderr_path.open("xb")
            self._process = subprocess.Popen(argv, stdout=self._output, stderr=self._errors,
                                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            _write(transport.ledger_dir / ("adb-%04d.started.json" % index),
                   {"schema_version": 1, "record_type": "b07_adb_stream_started", "index": index,
                    "host_pid": self._process.pid, "host_clock_token": transport.host_clock_token,
                    "started_monotonic_ns": time.monotonic_ns(), "selector": intent["selector"],
                    "argv_sha256": intent["argv_sha256"]})
        except BaseException as exc:
            transport._poisoned = True
            try:
                self.stop("start_failed")
            except BaseException:
                pass
            if not isinstance(exc, Exception):
                raise
            raise TransportError("logcat start or start record failed", self._process is not None, index) from exc
        self._monitor = threading.Thread(target=self._watch, name="B07-logcat-limit", daemon=True)
        self._monitor.start()

    def _watch(self):
        while not self._stop_event.wait(POLL_SECONDS):
            try:
                with self._lock:
                    if self._stopped:
                        return
                    if self.raw_path.stat().st_size > MAX_OUTPUT or self.stderr_path.stat().st_size > MAX_STDERR:
                        self._error = TransportError("logcat size limit exceeded", True, self.index)
                        self.transport._poisoned = True
                        self.stop("capture_limit")
                        return
                    if self._process.poll() is not None:
                        return
            except (OSError, TransportError) as exc:
                self.transport._poisoned = True
                self._error = exc if isinstance(exc, TransportError) else TransportError("logcat monitor failed", True, self.index)
                try:
                    self.stop("monitor_failed")
                except BaseException:
                    pass
                return

    def poll(self):
        with self._lock:
            if self._error is not None:
                raise self._error
            return self._process.poll()

    def stop(self, reason="completed"):
        if not isinstance(reason, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", reason):
            raise ValueError("invalid capture stop reason")
        with self._lock:
            if self._stopped:
                if self._error is not None:
                    raise self._error
                return self._summary
            self._stopped = True
            self._stop_event.set()
            failure = None
            was_live = self._process is not None and self._process.poll() is None
            try:
                if self._process is not None:
                    _stop_owned(self._process)
            except BaseException as exc:
                failure = exc
            for handle in (self._output, self._errors):
                if handle is not None:
                    try:
                        handle.close()
                    except BaseException as exc:
                        if failure is None:
                            failure = exc
            try:
                exceeded = ((self.raw_path.exists() and self.raw_path.stat().st_size > MAX_OUTPUT)
                            or (self.stderr_path.exists() and self.stderr_path.stat().st_size > MAX_STDERR))
            except OSError as exc:
                exceeded = False
                if failure is None:
                    failure = exc
            if exceeded and self._error is None:
                self._error = TransportError("logcat size limit exceeded", True, self.index)
            if self._process is not None and not was_live and self._error is None:
                self._error = TransportError("logcat exited before owner stop", True, self.index)
            status = "terminated_by_owner" if was_live else "exited_before_owner_stop"
            unknown = (failure is not None or self._error is not None or self._process is None
                       or reason in ("start_failed", "capture_limit", "monitor_failed"))
            if unknown:
                self.transport._poisoned = True
            try:
                self._summary = self.transport._result(self.index, self.intent, self._process,
                                                      status if not unknown else "stream_failure_outcome_unknown",
                                                      unknown, self.raw_path, self.stderr_path, reason)
            except (OSError, ValueError) as exc:
                self.transport._poisoned = True
                self._error = TransportError("logcat result record failed", self._process is not None, self.index)
                raise self._error from exc
            if failure is not None:
                self._error = TransportError("owned logcat stop failed", True, self.index)
                raise self._error from failure
            if self._error is not None:
                raise self._error
            return self._summary
