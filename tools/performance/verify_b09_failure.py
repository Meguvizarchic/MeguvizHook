#!/usr/bin/env python3
"""Check one reported B09 expected-exception transaction; never evaluate a performance gate."""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events, number


EVENTS = ("start", "installed", "failure_call", "configured", "cleanup", "post_close", "end")
CASES = ("before", "after", "original")
COUNTS = {"before": (1, 0, 0), "after": (1, 1, 1), "original": (1, 1, 1)}
COUNTER_FIELDS = ("before_count", "after_count", "original_count")
CLOCK_FIELDS = ("wall_start_ns", "cpu_start_ns", "cpu_end_ns", "wall_end_ns")
BOOLEAN_FIELDS = ("identity_preserved", "after_identity_preserved", "handle_returned",
                  "new_callbacks_rejected", "cleanup_confirmed", "outcome_unknown", "timed_out")
SDK_FIELDS = ("receipt_status", "generation", "new_callbacks_rejected",
              "in_flight_at_close", "registration_state")
MAX_NS, INT_MAX = 2**63 - 1, 2**31 - 1
MAX_RAW = 16 * 1024 * 1024
ENUMS = {
    "outcome": ("returned", "threw", "not_called"),
    "after_outcome": ("returned", "threw", "not_called"),
    "install_source": ("framework_handle", "sdk_receipt"),
    "close_source": ("framework_unhook_contract", "sdk_close_report"),
    "receipt_status": ("INSTALLED", "PARTIALLY_INSTALLED", "ALREADY_INSTALLED", "CONFLICT",
                       "UNSUPPORTED_CAPABILITY", "AMBIGUOUS_TARGET", "TARGET_MISSING", "STALE_IDENTITY",
                       "FILTERED", "TEMPORARILY_UNAVAILABLE", "FAILED"),
    "entry_restoration": ("RESTORED", "NOT_RESTORED", "UNKNOWN"),
    "physical_release": ("RELEASED", "NOT_CLAIMED", "UNKNOWN"),
    "registration_state": ("PREPARED", "INSTALLED", "RETIRING", "CLOSED", "RETIRED_RESIDENT"),
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


def _forbid(event, keys, where):
    if any(key in event for key in keys):
        raise InvalidRecord(where + " contains an inapplicable outcome or SDK field")


def _snapshot(line, event):
    scalars = CLOCK_FIELDS + COUNTER_FIELDS + BOOLEAN_FIELDS + (
        "result", "after_result", "generation", "target_count", "in_flight_at_close")
    values = {key: event[key] for key in scalars if type(event.get(key)) in (int, bool)}
    for key, allowed in ENUMS.items():
        if isinstance(event.get(key), str) and event[key] in allowed:
            values[key] = event[key]
    result = {"line": line, "event": event.get("event") if event.get("event") in EVENTS else None,
              "presence": {key: key in event for key in scalars + tuple(ENUMS)}, "values": values}
    for clock in ("wall", "cpu"):
        start, end = clock + "_start_ns", clock + "_end_ns"
        if (type(values.get(start)) is int and type(values.get(end)) is int
                and 0 <= values[start] <= values[end] <= MAX_NS):
            result[clock + "_elapsed_ns"] = values[end] - values[start]
    return result


def verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control, failure_case):
    """Check saved bytes; selected identities and exception comparison remain producer reports."""
    report = {"raw_log_sha256": hashlib.sha256(raw).hexdigest() if isinstance(raw, bytes) else None,
              "observations": [], "device_authentication": "not_verified",
              "performance_gate": "not_evaluated"}
    try:
        if not isinstance(raw, bytes):
            raise InvalidRecord("raw log must be bytes")
        return _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control, failure_case, report)
    except (InvalidRecord, UnicodeError, RecursionError) as exc:
        exc.partial_report = report
        raise


def _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control, failure_case, report):
    for key, value in (("run_id", run_id), ("pair_id", pair_id), ("session_id", session_id)):
        identifier(value, key)
    if len({run_id, pair_id, session_id}) != 3:
        raise InvalidRecord("run, pair and session identifiers must differ")
    if side not in ("A", "B") or backend not in ("101", "102") or control not in ("A0", "A2") or failure_case not in CASES:
        raise InvalidRecord("invalid side, backend, control or failure case")
    number(pid, "selected pid", 1, INT_MAX)
    number(tid, "selected controller tid", 1, INT_MAX)
    report.update(run_id=run_id, pair_id=pair_id, session_id=session_id, side=side, pid=pid,
                  controller_tid=tid, backend=backend, control=control, failure_case=failure_case,
                  scope="one B09 preallocated RuntimeException call envelope and cleanup transaction; no performance qualification",
                  provenance="raw events checked; process, framework, exception identity and counters are producer reports, not independently authenticated")
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
                              ("workload", "B09"), ("control", control), ("failure_case", failure_case)):
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

    seen, last, clock_seen, call = set(), -1, {}, None
    before, after, original = COUNTS[failure_case]
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
        seen.add(kind); last = position
        status = event.get("status")
        if status == "fail":
            incomplete.append(kind + " reported failure")
        elif status != ("start" if kind == "start" else "pass"):
            if status is None:
                incomplete.append(kind + " missing status")
            else:
                raise InvalidRecord("invalid status on line %d" % line)
        if control == "A0":
            _forbid(event, SDK_FIELDS, kind)
        for key in CLOCK_FIELDS:
            if key in event:
                _integer(event, key, kind)
                if key in clock_seen and clock_seen[key] != event[key]:
                    raise InvalidRecord("reported call clock endpoint changed")
                clock_seen[key] = event[key]
        for clock in ("wall", "cpu"):
            start, end = clock + "_start_ns", clock + "_end_ns"
            if start in event and end in event and event[end] < event[start]:
                raise InvalidRecord(clock + " clock moved backwards")
        for key in COUNTER_FIELDS + ("result", "after_result", "target_count", "in_flight_at_close"):
            if key in event:
                _integer(event, key, kind, 0, INT_MAX)
        for key in BOOLEAN_FIELDS:
            if key in event and type(event[key]) is not bool:
                raise InvalidRecord(kind + " has non-boolean " + key)
        if status != "fail":
            for key in ("outcome_unknown", "timed_out"):
                if key in event:
                    _value(event, key, False, kind)
            if kind in ("cleanup", "post_close") and "cleanup_confirmed" in event:
                _value(event, "cleanup_confirmed", True, kind)
            if "target_count" in event:
                _value(event, "target_count", 1, kind)

        required = []
        if kind == "start":
            required = [("warmup", 0), ("samples", 1), ("iterations", 1),
                        ("measurement_kind", "failure_call_wall_and_thread_cpu_ns")]
        elif kind == "installed":
            if control == "A0":
                required = [("install_source", "framework_handle"), ("handle_returned", True)]
            else:
                required = [("install_source", "sdk_receipt"), ("receipt_status", "INSTALLED"), ("target_count", 1)]
                if status != "fail" and not _integer(event, "generation", kind, 1):
                    incomplete.append("installed missing generation")
        elif kind == "failure_call":
            call = event
            required = [("outcome", "threw"), ("identity_preserved", True),
                        ("before_count", before), ("after_count", after), ("original_count", original)]
            if status != "fail":
                _forbid(event, ("result",), kind)
                for key in CLOCK_FIELDS:
                    if key not in event:
                        incomplete.append("failure_call missing " + key)
                after_outcome = {"before": "not_called", "after": "returned", "original": "threw"}[failure_case]
                required.append(("after_outcome", after_outcome))
                if failure_case == "before":
                    _forbid(event, ("after_result", "after_identity_preserved"), kind)
                elif failure_case == "after":
                    required.append(("after_result", 42))
                    _forbid(event, ("after_identity_preserved",), kind)
                else:
                    required.append(("after_identity_preserved", True))
                    _forbid(event, ("after_result",), kind)
        elif kind == "configured":
            required = [("backend", backend)]
            for key in ("framework_name", "framework_version"):
                if status != "fail" and not _name(event, key, kind):
                    incomplete.append("configured missing " + key)
            if status != "fail" and not _integer(event, "reported_api", kind, 1, 10000):
                incomplete.append("configured missing reported_api")
        elif kind == "cleanup":
            required = [("entry_restoration", "RESTORED")]
            if control == "A0":
                required += [("close_source", "framework_unhook_contract"), ("physical_release", "NOT_CLAIMED")]
            else:
                required += [("close_source", "sdk_close_report"), ("new_callbacks_rejected", True),
                             ("in_flight_at_close", 0)]
                if status != "fail":
                    for key, allowed in (("registration_state", ("CLOSED", "RETIRED_RESIDENT")),
                                         ("physical_release", ENUMS["physical_release"])):
                        if key not in event:
                            incomplete.append("cleanup missing " + key)
                        elif event[key] not in allowed:
                            raise InvalidRecord("invalid reported " + key)
                        else:
                            report[key + "_reported"] = event[key]
        elif kind == "post_close":
            required = [("before_count", before), ("after_count", after), ("original_count", original + 1)]
            if failure_case == "original":
                required += [("outcome", "threw"), ("identity_preserved", True)]
                if status != "fail":
                    _forbid(event, ("result",), kind)
            else:
                required += [("outcome", "returned"), ("result", 42)]
                if status != "fail":
                    _forbid(event, ("identity_preserved",), kind)
        elif kind == "end":
            required = [("detail", "completed"), ("cleanup_reported", True)]
        if status != "fail":
            for key, expected in required:
                if not _value(event, key, expected, kind):
                    incomplete.append(kind + " missing " + key)
    if call is not None:
        for clock in ("wall", "cpu"):
            start, end = clock + "_start_ns", clock + "_end_ns"
            if start in call and end in call:
                report[clock] = {"start_ns": call[start], "end_ns": call[end],
                                 "elapsed_ns": call[end] - call[start],
                                 "status": "unresolved" if call[start] == call[end] else "observed"}
    missing = [kind for kind in EVENTS if kind not in seen]
    if missing:
        incomplete.append("missing events: " + ", ".join(missing))
    if incomplete:
        return {**report, "result": "incomplete", "reason": "; ".join(incomplete)}
    if any(report[clock]["status"] == "unresolved" for clock in ("wall", "cpu")):
        return {**report, "result": "unresolved", "reason": "at least one call interval is zero"}
    return {**report, "result": "failure_record_consistent", "reason": "reported expected-exception transaction consistent"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    for name in ("run-id", "pair-id", "session-id", "side", "backend", "control", "failure-case"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--tid", type=int, required=True)
    args = parser.parse_args()
    raw_hash = None
    try:
        with args.log.open("rb") as source:
            raw = source.read(MAX_RAW + 1)
        if len(raw) > MAX_RAW:
            raise InvalidRecord("raw log exceeds 16 MiB bound")
        raw_hash = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pair_id, args.session_id, args.side,
                        args.pid, args.tid, args.backend, args.control, args.failure_case)
    except (InvalidRecord, OSError, UnicodeError, RecursionError) as exc:
        result = {**getattr(exc, "partial_report", {}), "result": "invalid", "reason": str(exc),
                  "raw_log_sha256": raw_hash, "device_authentication": "not_verified",
                  "performance_gate": "not_evaluated"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"failure_record_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
