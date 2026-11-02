#!/usr/bin/env python3
"""Check one reported B07 installation diagnostic; never evaluate a performance gate."""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from verify_aa_pilot import InvalidRecord, iter_bench_events, identifier, number


EVENTS = ("start", "install", "hook_check", "configured", "cleanup", "post_close", "end")
MAX_NS = 2**63 - 1
CLOCK_FIELDS = ("wall_start_ns", "cpu_start_ns", "cpu_end_ns", "wall_end_ns")


def _no_nonfinite(value):
    """The shared decoder detects duplicate fields; also reject non-standard JSON numbers."""
    if isinstance(value, float) and not math.isfinite(value):
        raise InvalidRecord("non-finite JSON number")
    if isinstance(value, dict):
        for child in value.values():
            _no_nonfinite(child)
    elif isinstance(value, list):
        for child in value:
            _no_nonfinite(child)


def _value(event, key, expected, where):
    if key not in event:
        return False
    actual = event[key]
    if type(actual) is not type(expected) or actual != expected:
        raise InvalidRecord(where + " contradicts " + key)
    return True


def _required_integer(event, key, where, minimum=0, maximum=MAX_NS):
    if key not in event:
        return False
    number(event[key], where + "." + key, minimum, maximum)
    return True


def _required_name(event, key, where):
    if key not in event:
        return False
    value = event[key]
    if (not isinstance(value, str) or not value.strip() or value != value.strip()
            or value.lower() == "null" or len(value) > 256):
        raise InvalidRecord(where + " has invalid " + key)
    return True


def _install_observation(line, event):
    """Retain only known integer endpoints; never infer a missing or invalid interval."""
    presence = {key: key in event for key in CLOCK_FIELDS}
    endpoints = {key: event[key] for key in CLOCK_FIELDS if type(event.get(key)) is int}
    result = {"line": line, "presence": presence, "endpoints_ns": endpoints}
    for clock in ("wall", "cpu"):
        start, end = clock + "_start_ns", clock + "_end_ns"
        if (start in endpoints and end in endpoints
                and 0 <= endpoints[start] <= endpoints[end] <= MAX_NS):
            result[clock + "_elapsed_ns"] = endpoints[end] - endpoints[start]
    return result


def verify(raw, run_id, pair_id, session_id, side, pid, tid, backend):
    """Check raw bytes; invalid exceptions retain safely selected partial evidence."""
    report = {"raw_log_sha256": hashlib.sha256(raw).hexdigest(), "install_observations": [],
              "device_authentication": "not_verified", "performance_gate": "not_evaluated"}
    try:
        return _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, report)
    except InvalidRecord as exc:
        exc.partial_report = report
        raise


def _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, report):
    """Reported process and device identity remain unverified."""
    for key, value in (("run_id", run_id), ("pair_id", pair_id), ("session_id", session_id)):
        identifier(value, key)
    if len({run_id, pair_id, session_id}) != 3:
        raise InvalidRecord("run, pair and session identifiers must differ")
    if side not in ("A", "B") or backend not in ("101", "102"):
        raise InvalidRecord("invalid side or backend")
    number(pid, "selected pid", 1, 2**31 - 1)
    number(tid, "selected tid", 1, 2**31 - 1)
    report.update({"run_id": run_id, "pair_id": pair_id, "session_id": session_id, "side": side,
                   "pid": pid, "tid": tid, "backend": backend,
                   "scope": "one reported install() wall and calling-thread CPU envelope; no performance qualification",
                   "provenance": "raw log and reported identities checked; device, process, framework and artifacts not independently authenticated"})
    selected, incomplete = [], []
    for line, prefix_pid, prefix_tid, event in iter_bench_events(raw):
        _no_nonfinite(event)
        if event.get("run_id") != run_id:
            if "run_id" not in event and prefix_pid == pid:
                if prefix_tid != tid:
                    raise InvalidRecord("threadtime TID mismatch on line %d" % line)
                if "pid" in event:
                    _value(event, "pid", pid, "event on line %d" % line)
                incomplete.append("selected PID event missing run_id on line %d" % line)
                continue
            if prefix_pid == pid or (type(event.get("pid")) is int and event["pid"] == pid):
                raise InvalidRecord("conflicting run for selected PID on line %d" % line)
            continue
        if prefix_pid != pid or prefix_tid != tid:
            raise InvalidRecord("threadtime PID/TID mismatch on line %d" % line)
        for key, expected in (("schema", 1), ("run_id", run_id), ("pair_id", pair_id),
                              ("session_id", session_id), ("pid", pid), ("side", side),
                              ("workload", "B07"), ("control", "A2")):
            if not _value(event, key, expected, "event on line %d" % line):
                incomplete.append("event on line %d missing %s" % (line, key))
        if "tid" in event:
            _value(event, "tid", tid, "event on line %d" % line)
        if "backend" in event:
            _value(event, "backend", backend, "event on line %d" % line)
        if event.get("event") == "install" and type(event.get("pid")) is int and event["pid"] == pid:
            report["install_observations"].append(_install_observation(line, event))
        selected.append((line, event))

    if not selected:
        return {**report, "result": "incomplete", "reason": "no selected events"}
    seen, last = set(), -1
    install = None
    for line, event in selected:
        kind = event.get("event")
        if kind is None:
            incomplete.append("event on line %d missing event type" % line)
            continue
        if kind not in EVENTS:
            raise InvalidRecord("unknown event on line %d" % line)
        position = EVENTS.index(kind)
        if kind in seen or position <= last:
            raise InvalidRecord("duplicate or out-of-order event on line %d" % line)
        seen.add(kind)
        last = position
        status = event.get("status")
        if status == "fail":
            incomplete.append(kind + " reported failure")
        elif status != ("start" if kind == "start" else "pass"):
            if status is None:
                incomplete.append(kind + " missing status")
            else:
                raise InvalidRecord("invalid status on line %d" % line)

        # Failure events can omit success-only fields. Still check any clock values
        # they do contain below, so an invalid clock is never treated as success.
        required = []
        if kind == "start":
            required = [("warmup", 0), ("samples", 1), ("iterations", 1),
                        ("measurement_kind", "install_wall_and_thread_cpu_ns")]
        elif kind == "install":
            install = event
            required = [("receipt_status", "INSTALLED"), ("target_count", 1)]
            if status == "pass" and "outcome_unknown" in event:
                _value(event, "outcome_unknown", False, kind)
            if not _required_integer(event, "generation", kind, 1):
                incomplete.append("install missing generation")
            for key in CLOCK_FIELDS:
                if not _required_integer(event, key, kind):
                    incomplete.append("install missing " + key)
        elif kind == "hook_check":
            required = [("result", 42), ("before_count", 1), ("original_count", 1)]
        elif kind == "configured":
            required = [("backend", backend)]
            for key in ("framework_name", "framework_version"):
                if not _required_name(event, key, kind):
                    incomplete.append("configured missing " + key)
            if not _required_integer(event, "reported_api", kind, 1, 10000):
                incomplete.append("configured missing reported_api")
        elif kind == "cleanup":
            required = [("entry_restoration", "RESTORED"), ("new_callbacks_rejected", True),
                        ("in_flight", 0)]
            if status != "fail":
                if "physical_release" not in event:
                    incomplete.append("cleanup missing physical_release")
                elif event["physical_release"] not in ("RELEASED", "NOT_CLAIMED", "UNKNOWN"):
                    raise InvalidRecord("invalid reported physical_release")
                else:
                    report["physical_release_reported"] = event["physical_release"]
        elif kind == "post_close":
            required = [("result", 42), ("before_count", 1), ("original_count", 2)]
        elif kind == "end":
            required = [("detail", "completed"), ("cleanup_reported", True)]
        if status != "fail":
            for key, expected in required:
                if not _value(event, key, expected, kind):
                    incomplete.append(kind + " missing " + key)

    if install is not None:
        for clock in ("wall", "cpu"):
            a, b = clock + "_start_ns", clock + "_end_ns"
            if a in install and b in install:
                if install[b] < install[a]:
                    raise InvalidRecord(clock + " clock moved backwards")
                report[clock] = {"start_ns": install[a], "end_ns": install[b],
                                 "elapsed_ns": install[b] - install[a],
                                 "status": "unresolved" if install[b] == install[a] else "observed"}
    missing = [kind for kind in EVENTS if kind not in seen]
    if missing:
        incomplete.append("missing events: " + ", ".join(missing))
    if incomplete:
        return {**report, "result": "incomplete", "reason": "; ".join(incomplete)}
    if any(report[clock]["status"] == "unresolved" for clock in ("wall", "cpu")):
        return {**report, "result": "unresolved", "reason": "at least one interval is zero"}
    return {**report, "result": "install_record_consistent", "reason": "reported single install log consistent"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    for name in ("run-id", "pair-id", "session-id", "side", "backend"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--tid", type=int, required=True)
    args = parser.parse_args()
    raw_hash = None
    try:
        raw = args.log.read_bytes()
        raw_hash = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pair_id, args.session_id, args.side,
                        args.pid, args.tid, args.backend)
    except (InvalidRecord, OSError, UnicodeError, RecursionError) as exc:
        result = {**getattr(exc, "partial_report", {}),
                  "result": "invalid", "reason": str(exc), "raw_log_sha256": raw_hash,
                  "device_authentication": "not_verified", "performance_gate": "not_evaluated"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"install_record_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
