#!/usr/bin/env python3
"""Check one reported B08 in-flight close transaction; never evaluate a performance gate."""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events, number


EVENTS = ("start", "installed", "old_entered", "close_returned", "post_close",
          "old_completed", "configured", "cleanup", "end")
MAX_NS = 2**63 - 1
INT_MAX = 2**31 - 1
CLOCK_FIELDS = ("close_wall_start_ns", "close_wall_end_ns")
COUNTERS = ("before_count", "after_count", "old_original", "new_original")
ALIVE_FIELDS = ("worker_alive", "closer_alive", "post_alive", "cleanup_alive")
THREAD_FIELDS = ("worker_tid", "closer_tid", "post_tid")
STATES = ("CLOSED", "RETIRED_RESIDENT")
PHYSICAL = ("RELEASED", "NOT_CLAIMED", "UNKNOWN")
REPORTED_ENUMS = {
    "registration_state": ("PREPARED", "INSTALLED", "RETIRING", "CLOSED", "RETIRED_RESIDENT"),
    "entry_restoration": ("RESTORED", "NOT_RESTORED", "UNKNOWN"),
    "physical_release": PHYSICAL,
}


def _no_nonfinite(value):
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
    if type(event[key]) is not type(expected) or event[key] != expected:
        raise InvalidRecord(where + " contradicts " + key)
    return True


def _integer(event, key, where, minimum=0, maximum=MAX_NS):
    if key not in event:
        return False
    number(event[key], where + "." + key, minimum, maximum)
    return True


def _name(event, key, where):
    if key not in event:
        return False
    value = event[key]
    if (not isinstance(value, str) or not value or value != value.strip() or len(value) > 256
            or value.lower() == "null" or any(ord(char) < 32 for char in value)):
        raise InvalidRecord(where + " has invalid " + key)
    return True


def _snapshot(line, event):
    """Retain only known scalar observations; never echo arbitrary event values."""
    keys = CLOCK_FIELDS + COUNTERS + THREAD_FIELDS + ALIVE_FIELDS + (
        "worker_completed", "gate_released", "cleanup_confirmed", "in_flight_at_close",
        "new_callbacks_rejected", "outcome_unknown", "timed_out") + tuple(REPORTED_ENUMS)
    values = {key: event[key] for key in keys
              if key not in REPORTED_ENUMS and type(event.get(key)) in (int, bool)}
    for key, allowed in REPORTED_ENUMS.items():
        if type(event.get(key)) is str and event[key] in allowed:
            values[key] = event[key]
    result = {"line": line, "event": event.get("event") if event.get("event") in EVENTS else None,
              "presence": {key: key in event for key in keys}, "values": values}
    start, end = CLOCK_FIELDS
    if (type(values.get(start)) is int and type(values.get(end)) is int
            and 0 <= values[start] <= values[end] <= MAX_NS):
        result["close_wall_elapsed_ns"] = values[end] - values[start]
    return result


def verify(raw, run_id, pair_id, session_id, side, pid, tid, backend):
    """Check saved bytes against explicit identities; errors retain safely selected observations."""
    report = {"raw_log_sha256": hashlib.sha256(raw).hexdigest(), "observations": [],
              "device_authentication": "not_verified", "performance_gate": "not_evaluated"}
    try:
        return _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, report)
    except (InvalidRecord, UnicodeError, RecursionError) as exc:
        exc.partial_report = report
        raise


def _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, report):
    for key, value in (("run_id", run_id), ("pair_id", pair_id), ("session_id", session_id)):
        identifier(value, key)
    if len({run_id, pair_id, session_id}) != 3:
        raise InvalidRecord("run, pair and session identifiers must differ")
    if side not in ("A", "B") or backend not in ("101", "102"):
        raise InvalidRecord("invalid side or backend")
    number(pid, "selected pid", 1, INT_MAX)
    number(tid, "selected controller tid", 1, INT_MAX)
    report.update(run_id=run_id, pair_id=pair_id, session_id=session_id, side=side, pid=pid,
                  controller_tid=tid, backend=backend,
                  scope="one normal B08 close transaction with one held original call; no performance qualification",
                  provenance="saved events checked; process, thread, framework, counters and cleanup are producer reports, not independent authentication")
    selected, incomplete = [], []
    for line, prefix_pid, prefix_tid, event in iter_bench_events(raw):
        _no_nonfinite(event)
        if event.get("run_id") != run_id:
            if "run_id" not in event and prefix_pid == pid:
                if prefix_tid != tid:
                    raise InvalidRecord("controller TID mismatch on line %d" % line)
                if "pid" in event:
                    _value(event, "pid", pid, "event on line %d" % line)
                incomplete.append("selected PID event missing run_id on line %d" % line)
                continue
            if prefix_pid == pid or (type(event.get("pid")) is int and event["pid"] == pid):
                raise InvalidRecord("conflicting run for selected PID on line %d" % line)
            continue
        if prefix_pid != pid or prefix_tid != tid:
            raise InvalidRecord("threadtime controller PID/TID mismatch on line %d" % line)
        for key, expected in (("schema", 1), ("run_id", run_id), ("pair_id", pair_id),
                              ("session_id", session_id), ("side", side), ("pid", pid),
                              ("workload", "B08"), ("control", "A2")):
            if not _value(event, key, expected, "event on line %d" % line):
                incomplete.append("event on line %d missing %s" % (line, key))
        if "tid" in event:
            _value(event, "tid", tid, "event on line %d" % line)
        if "backend" in event:
            _value(event, "backend", backend, "event on line %d" % line)
        report["observations"].append(_snapshot(line, event))
        selected.append((line, event))

    if not selected:
        return {**report, "result": "incomplete", "reason": "no selected events"}
    seen, last, worker_tid = set(), -1, None
    clock_seen, threads_seen = {}, {}
    close = None
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
        # Even failed events must not hide malformed or backwards raw clocks.
        for key in CLOCK_FIELDS:
            if key in event:
                _integer(event, key, kind)
                if key in clock_seen and clock_seen[key] != event[key]:
                    raise InvalidRecord("reported close clock endpoint changed")
                clock_seen[key] = event[key]
        if all(key in event for key in CLOCK_FIELDS) and event[CLOCK_FIELDS[1]] < event[CLOCK_FIELDS[0]]:
            raise InvalidRecord("close wall clock moved backwards")
        for key in COUNTERS:
            if key in event:
                _integer(event, key, kind, 0, INT_MAX)
        for key in THREAD_FIELDS:
            if key in event:
                _integer(event, key, kind, 1, INT_MAX)
                if key in threads_seen and threads_seen[key] != event[key]:
                    raise InvalidRecord("reported transaction thread changed")
                threads_seen[key] = event[key]
        for key in ALIVE_FIELDS + ("worker_completed", "gate_released", "cleanup_confirmed",
                                  "new_callbacks_rejected", "outcome_unknown", "timed_out"):
            if key in event and type(event[key]) is not bool:
                raise InvalidRecord(kind + " has non-boolean " + key)
        if status != "fail":
            for key in ("outcome_unknown", "timed_out"):
                if key in event:
                    _value(event, key, False, kind)
        if "worker_tid" in event:
            if event["worker_tid"] == tid:
                raise InvalidRecord("worker must differ from controller")
            if worker_tid is not None and worker_tid != event["worker_tid"]:
                raise InvalidRecord("reported worker thread changed")
            worker_tid = event["worker_tid"]
        for key in ("closer_tid", "post_tid"):
            if key in event and event[key] in (tid, worker_tid):
                raise InvalidRecord(key + " must differ from controller and held worker")

        required = []
        if kind == "start":
            required = [("warmup", 0), ("samples", 1), ("iterations", 1),
                        ("measurement_kind", "close_with_inflight_wall_ns")]
        elif kind == "installed":
            required = [("receipt_status", "INSTALLED"), ("target_count", 1)]
            if status != "fail" and not _integer(event, "generation", kind, 1):
                incomplete.append("installed missing generation")
        elif kind == "old_entered":
            required = [("before_count", 1), ("after_count", 0), ("old_original", 1),
                        ("new_original", 0), ("worker_completed", False), ("gate_released", False),
                        ("worker_alive", True)]
            if status != "fail" and "worker_tid" not in event:
                incomplete.append("old_entered missing worker_tid")
        elif kind == "close_returned":
            close = event
            required = [("new_callbacks_rejected", True), ("entry_restoration", "RESTORED"),
                        ("in_flight_at_close", 1), ("worker_completed", False), ("gate_released", False),
                        ("worker_alive", True), ("before_count", 1), ("after_count", 0),
                        ("old_original", 1), ("new_original", 0)]
            if status != "fail":
                for key in CLOCK_FIELDS + ("closer_tid",):
                    if key not in event:
                        incomplete.append("close_returned missing " + key)
                for key, allowed in (("registration_state", STATES), ("physical_release", PHYSICAL)):
                    if key not in event:
                        incomplete.append("close_returned missing " + key)
                    elif event[key] not in allowed:
                        raise InvalidRecord("invalid reported " + key)
                    else:
                        report[key + "_reported"] = event[key]
        elif kind == "post_close":
            required = [("result", 42), ("before_count", 1), ("after_count", 0),
                        ("old_original", 1), ("new_original", 1),
                        ("worker_completed", False), ("gate_released", False), ("worker_alive", True)]
            if status != "fail" and "post_tid" not in event:
                incomplete.append("post_close missing post_tid")
        elif kind == "old_completed":
            required = [("result", 42), ("before_count", 1), ("after_count", 1),
                        ("old_original", 1), ("new_original", 1),
                        ("worker_completed", True), ("gate_released", True),
                        ("worker_alive", False), ("closer_alive", False), ("post_alive", False)]
        elif kind == "configured":
            required = [("backend", backend)]
            for key in ("framework_name", "framework_version"):
                if status != "fail" and not _name(event, key, kind):
                    incomplete.append("configured missing " + key)
            if status != "fail" and not _integer(event, "reported_api", kind, 1, 10000):
                incomplete.append("configured missing reported_api")
        elif kind == "cleanup":
            required = [(key, False) for key in ALIVE_FIELDS] + [("cleanup_confirmed", True)]
        elif kind == "end":
            required = [("detail", "completed"), ("cleanup_reported", True)]
        if status != "fail":
            for key, expected in required:
                if not _value(event, key, expected, kind):
                    incomplete.append(kind + " missing " + key)

    if close is not None and all(key in close for key in CLOCK_FIELDS):
        start, end = (close[key] for key in CLOCK_FIELDS)
        report["close_wall"] = {"start_ns": start, "end_ns": end, "elapsed_ns": end - start,
                                "status": "unresolved" if start == end else "observed"}
    missing = [kind for kind in EVENTS if kind not in seen]
    if missing:
        incomplete.append("missing events: " + ", ".join(missing))
    if incomplete:
        return {**report, "result": "incomplete", "reason": "; ".join(incomplete)}
    if report["close_wall"]["status"] == "unresolved":
        return {**report, "result": "unresolved", "reason": "close wall interval is zero"}
    return {**report, "result": "close_record_consistent", "reason": "reported B08 close transaction consistent"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    for name in ("run-id", "pair-id", "session-id", "side", "backend"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--tid", type=int, required=True, help="controller threadtime TID")
    args = parser.parse_args()
    raw_hash = None
    try:
        raw = args.log.read_bytes()
        raw_hash = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pair_id, args.session_id, args.side,
                        args.pid, args.tid, args.backend)
    except (InvalidRecord, OSError, UnicodeError, RecursionError) as exc:
        result = {**getattr(exc, "partial_report", {}), "result": "invalid", "reason": str(exc),
                  "raw_log_sha256": raw_hash, "device_authentication": "not_verified",
                  "performance_gate": "not_evaluated"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"close_record_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
