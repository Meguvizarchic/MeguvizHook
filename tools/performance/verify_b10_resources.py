#!/usr/bin/env python3
"""Check one B10 process-resource transaction; never attribute SDK bytes or evaluate budgets."""

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events, number


EVENTS = ("start", "resource_before", "installed", "resource_installed", "configured",
          "cleanup", "post_close", "resource_closed", "end")
RESOURCE_EVENTS = ("resource_before", "resource_installed", "resource_closed")
METRICS = (("rss_bytes", "rss_status", 0, 2**63 - 1),
           ("thread_count", "thread_status", 1, 2**31 - 1),
           ("fd_count", "fd_status", 0, 4096))
COUNTERS = ("before_count", "after_count", "original_count")
SDK_FIELDS = ("receipt_status", "generation", "target_count", "registration_state",
              "new_callbacks_rejected", "in_flight_at_close")
FORMAL_METRICS = ("additional_rss_bytes", "additional_apk_bytes", "additional_thread_count",
                  "additional_fd_count", "retired_resident_bytes")
MAX_RAW, MAX_NS, INT_MAX = 16 * 1024 * 1024, 2**63 - 1, 2**31 - 1
OBSERVATION_FIELDS = COUNTERS + SDK_FIELDS + tuple(k for row in METRICS for k in row[:2]) + (
    "resource_start_ns", "resource_end_ns", "result", "handle_returned", "outcome_unknown",
    "timed_out", "cleanup_confirmed", "install_source", "close_source", "entry_restoration",
    "physical_release", "retired_resident_bytes", "retired_bytes_status", "selected_registration_state")
ENUMS = {"rss_status": ("observed", "unavailable"), "thread_status": ("observed", "unavailable"),
         "fd_status": ("observed", "unavailable"), "install_source": ("framework_handle", "sdk_receipt"),
         "close_source": ("framework_unhook_contract", "sdk_close_report"),
         "entry_restoration": ("RESTORED", "NOT_RESTORED", "UNKNOWN"),
         "physical_release": ("RELEASED", "NOT_CLAIMED", "UNKNOWN"),
         "receipt_status": ("INSTALLED", "FAILED", "PARTIALLY_INSTALLED", "TEMPORARILY_UNAVAILABLE"),
         "registration_state": ("PREPARED", "INSTALLED", "RETIRING", "CLOSED", "RETIRED_RESIDENT"),
         "retired_bytes_status": ("not_attributable",),
         "selected_registration_state": ("UNOBSERVED", "CLOSED", "RETIRED_RESIDENT")}


def _finite(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise InvalidRecord("non-finite JSON number")
    if isinstance(value, dict):
        for child in value.values():
            _finite(child)
    elif isinstance(value, list):
        for child in value:
            _finite(child)


def _required(event, key, expected, missing):
    if key not in event:
        missing.append(str(event.get("event", "event")) + " missing " + key)
    elif type(event[key]) is not type(expected) or event[key] != expected:
        raise InvalidRecord(str(event.get("event")) + " contradicts " + key)


def _name(value, key):
    if (not isinstance(value, str) or not value or value != value.strip() or len(value) > 256
            or value.lower() == "null" or any(ord(c) < 32 for c in value)):
        raise InvalidRecord("invalid " + key)


def _observation(line, event):
    values = {key: event[key] for key in OBSERVATION_FIELDS if key in event
              and (event[key] is None or type(event[key]) in (int, float, bool))}
    for key, allowed in ENUMS.items():
        if isinstance(event.get(key), str) and event[key] in allowed:
            values[key] = event[key]
    return {"line": line, "event": event.get("event") if event.get("event") in EVENTS else None,
            "presence": {key: key in event for key in OBSERVATION_FIELDS}, "values": values}


def _snapshot(event, missing, failed):
    kind = event["event"]
    for key in ("resource_start_ns", "resource_end_ns"):
        if key not in event:
            missing.append(kind + " missing " + key)
        elif failed and (event[key] is None or type(event[key]) is int and event[key] < 0):
            missing.append(kind + " failed resource clock")
        else:
            number(event[key], key, 0, MAX_NS)
    if (not failed and "resource_start_ns" in event and "resource_end_ns" in event
            and event["resource_end_ns"] < event["resource_start_ns"]):
        raise InvalidRecord(kind + " resource clock moved backwards")
    for key, status_key, minimum, maximum in METRICS:
        if key not in event or status_key not in event:
            missing.append(kind + " missing " + key + " or status")
        status = event.get(status_key)
        if status_key in event and status not in ("observed", "unavailable"):
            raise InvalidRecord(kind + " invalid " + status_key)
        if key in event:
            if status == "unavailable":
                if event[key] is not None:
                    raise InvalidRecord(kind + " unavailable metric must be null")
                missing.append(kind + " " + key + " unavailable")
            elif event[key] is not None:
                number(event[key], key, minimum, maximum)
            elif status == "observed":
                raise InvalidRecord(kind + " observed metric must be an integer")
        reason = status_key.removesuffix("_status") + "_reason"
        if reason in event:
            _name(event[reason], reason)


def verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control):
    report = {"raw_log_sha256": hashlib.sha256(raw).hexdigest() if isinstance(raw, bytes) else None,
              "observations": [], "device_authentication": "not_verified",
              "performance_gate": "not_evaluated", "formal_metrics": {k: None for k in FORMAL_METRICS},
              "provenance": "raw events checked; resource values, counters, framework and process identity are producer reports"}
    try:
        return _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control, report)
    except (InvalidRecord, UnicodeError, RecursionError) as exc:
        exc.partial_report = report
        raise


def _verify(raw, run_id, pair_id, session_id, side, pid, tid, backend, control, report):
    if not isinstance(raw, bytes) or len(raw) > MAX_RAW:
        raise InvalidRecord("raw log must be bytes within 16 MiB bound")
    for key, value in (("run_id", run_id), ("pair_id", pair_id), ("session_id", session_id)):
        identifier(value, key)
    if len({run_id, pair_id, session_id}) != 3:
        raise InvalidRecord("run, pair and session identifiers must differ")
    if side not in ("A", "B") or backend not in ("101", "102") or control not in ("A0", "A2"):
        raise InvalidRecord("invalid selected side, backend or control")
    number(pid, "selected pid", 1, INT_MAX)
    number(tid, "selected tid", 1, INT_MAX)
    report.update(run_id=run_id, pair_id=pair_id, session_id=session_id, side=side,
                  pid=pid, controller_tid=tid, backend=backend, control=control)
    missing, seen, snapshots = [], set(), {}
    last, previous_end, close_state = -1, None, None
    for line, prefix_pid, prefix_tid, event in iter_bench_events(raw):
        _finite(event)
        if event.get("run_id") != run_id:
            if "run_id" not in event and prefix_pid == pid:
                missing.append("selected PID event missing run_id on line %d" % line)
                if prefix_tid != tid:
                    raise InvalidRecord("selected PID has conflicting TID")
                continue
            if prefix_pid == pid or (type(event.get("pid")) is int and event["pid"] == pid):
                raise InvalidRecord("conflicting run for selected PID")
            continue
        if prefix_pid != pid or prefix_tid != tid:
            raise InvalidRecord("threadtime controller PID/TID mismatch")
        for key, expected in (("schema", 1), ("run_id", run_id), ("pair_id", pair_id),
                              ("session_id", session_id), ("side", side), ("pid", pid),
                              ("workload", "B10"), ("control", control)):
            _required(event, key, expected, missing)
        if "tid" in event:
            _required(event, "tid", tid, missing)
        if "backend" in event:
            _required(event, "backend", backend, missing)
        kind = event.get("event")
        report["observations"].append(_observation(line, event))
        if kind is None:
            missing.append("event type missing")
            continue
        if kind not in EVENTS or kind in seen or EVENTS.index(kind) <= last:
            raise InvalidRecord("unknown, duplicate or out-of-order event")
        seen.add(kind)
        last = EVENTS.index(kind)
        status = event.get("status")
        failed = status == "fail"
        if failed:
            missing.append(kind + " reported failure")
        elif status is None:
            missing.append(kind + " missing status")
        elif status != ("start" if kind == "start" else "pass"):
            raise InvalidRecord(kind + " invalid status")
        if control == "A0" and any(key in event for key in SDK_FIELDS):
            raise InvalidRecord("A0 contains SDK-only proof fields")
        if any(key in event for key in ("failure_case", "after_outcome", "after_result",
                                       "after_identity_preserved", "identity_preserved")):
            raise InvalidRecord("B10 contains inapplicable failure-path fields")
        if any(key in event for key in FORMAL_METRICS[:-1]):
            raise InvalidRecord("raw snapshot must not assert attributable formal metrics")
        for key in ("outcome_unknown", "timed_out", "cleanup_confirmed"):
            if key in event:
                if type(event[key]) is not bool:
                    raise InvalidRecord("non-boolean " + key)
                if not failed:
                    _required(event, key, key == "cleanup_confirmed", missing)
        for key in COUNTERS:
            if key in event:
                number(event[key], key, 0, INT_MAX)
        if "result" in event:
            number(event["result"], "result", 0, MAX_NS)
        if "retired_resident_bytes" in event and event["retired_resident_bytes"] is not None:
            raise InvalidRecord("retired resident bytes cannot be attributed")
        if kind in RESOURCE_EVENTS:
            _snapshot(event, missing, failed)
            start, end = event.get("resource_start_ns"), event.get("resource_end_ns")
            if not failed and start is not None and previous_end is not None and start < previous_end:
                raise InvalidRecord("resource snapshot windows moved backwards")
            if not failed and end is not None:
                previous_end = end
            snapshots[kind] = event
        if failed:
            continue
        required = []
        if kind == "start":
            required = [("warmup", 0), ("samples", 1), ("iterations", 1),
                        ("measurement_kind", "process_resource_snapshots")]
        elif kind == "installed":
            if control == "A0":
                required = [("install_source", "framework_handle"), ("handle_returned", True)]
            else:
                required = [("install_source", "sdk_receipt"), ("receipt_status", "INSTALLED"),
                            ("target_count", 1)]
                if "generation" not in event:
                    missing.append("installed missing generation")
                else:
                    number(event["generation"], "generation", 1, MAX_NS)
        elif kind == "configured":
            required = [("backend", backend), ("reported_api", int(backend))]
            for key in ("framework_name", "framework_version"):
                if key not in event:
                    missing.append("configured missing " + key)
                else:
                    _name(event[key], key)
        elif kind == "cleanup":
            required = [("entry_restoration", "RESTORED")]
            if control == "A0":
                required.extend([("close_source", "framework_unhook_contract"),
                                 ("physical_release", "NOT_CLAIMED")])
            else:
                required.extend([("close_source", "sdk_close_report"),
                                 ("new_callbacks_rejected", True), ("in_flight_at_close", 0)])
                close_state = event.get("registration_state")
                if close_state is None:
                    missing.append("cleanup missing registration_state")
                elif close_state not in ("CLOSED", "RETIRED_RESIDENT"):
                    raise InvalidRecord("cleanup registration is not closed")
                required.append(("physical_release", "NOT_CLAIMED"))
        elif kind == "resource_closed":
            required = [("retired_resident_bytes", None), ("retired_bytes_status", "not_attributable")]
            state = "UNOBSERVED" if control == "A0" else close_state
            if state is None:
                missing.append("closed snapshot has no preceding close state")
            else:
                required.append(("selected_registration_state", state))
        elif kind == "end":
            required = [("detail", "completed"), ("cleanup_reported", True)]
        if kind in ("resource_before", "installed", "resource_installed", "cleanup", "post_close", "resource_closed"):
            counts = (0, 0, 0) if kind in ("resource_before", "installed") else (1, 1, 2 if kind in ("post_close", "resource_closed") else 1)
            required.extend(zip(COUNTERS, counts))
        if kind in ("resource_installed", "post_close", "resource_closed"):
            required.append(("result", 42))
        for key, expected in required:
            _required(event, key, expected, missing)
    for event in EVENTS:
        if event not in seen:
            missing.append("missing " + event)
    deltas = {}
    for phase in ("resource_installed", "resource_closed"):
        deltas[phase.removeprefix("resource_") + "_minus_before"] = {}
        for key, status_key, _, _ in METRICS:
            a, b = snapshots.get("resource_before", {}), snapshots.get(phase, {})
            value = b[key] - a[key] if (a.get(status_key) == b.get(status_key) == "observed"
                                                       and type(a.get(key)) is int and type(b.get(key)) is int) else None
            deltas[phase.removeprefix("resource_") + "_minus_before"][key] = value
    report.update(phase_deltas=deltas, delta_scope="within-process phase arithmetic; signed, not attributable SDK or retired bytes",
                  resource_sources={"rss_bytes": "/proc/self/status VmRSS kB * 1024",
                                    "thread_count": "/proc/self/status Threads",
                                    "fd_count": "/proc/self/fd entries still present after enumeration; not atomic"})
    return {**report, "result": "incomplete" if missing else "resource_record_consistent",
            "reason": "; ".join(dict.fromkeys(missing)) if missing else "reported resource transaction consistent; no budget evaluated"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    for name in ("run-id", "pair-id", "session-id", "side", "backend", "control"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--tid", type=int, required=True)
    args = parser.parse_args()
    raw_hash = None
    try:
        with args.log.open("rb") as source:
            raw = source.read(MAX_RAW + 1)
        raw_hash = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pair_id, args.session_id, args.side,
                        args.pid, args.tid, args.backend, args.control)
    except (InvalidRecord, OSError, UnicodeError, RecursionError) as exc:
        result = {**getattr(exc, "partial_report", {}), "result": "invalid", "reason": str(exc),
                  "raw_log_sha256": raw_hash, "device_authentication": "not_verified",
                  "performance_gate": "not_evaluated"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"resource_record_consistent": 0, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
