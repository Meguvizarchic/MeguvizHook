#!/usr/bin/env python3
"""Validate one instrumented callback-body diagnostic, not a performance gate.

The two CPU timestamps bound work inside a diagnostic callback. They do not
include framework/dispatcher overhead and cannot establish pure callback CPU.
The original log and external device provenance must be retained separately.
"""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path


TAG = "MeguvizHookCpuBody"
TAGGED_LINE = re.compile(r"(?:^|\s)" + TAG + r"\s*(?:\(\s*\d+\s*\))?\s*:")
THREADTIME_TAG_START = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+" + TAG + r"(?=$|[\s:(])"
)
THREADTIME_TAG_FIELD = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+(?P<tag>[^\s:]+)"
)
THREADTIME = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+[VDIWEF]\s+" + TAG + r"\s*:\s*(?P<json>\{.*\})\s*$"
)
INVALID_UTF8 = re.compile(r"[\udc80-\udcff]")
FAILURE_REASON = re.compile(r"[a-z0-9_]{1,64}\Z")
COMMON = {"schema", "run_id", "pid", "tid", "event", "status"}
START = COMMON | {"backend", "control", "workload", "warmup", "samples", "clock", "measurement_kind"}
CALL = COMMON | {"ordinal", "before_count", "original_count", "after_count", "before_cpu_start_ns",
                 "before_cpu_end_ns", "after_cpu_start_ns", "after_cpu_end_ns", "reason"}
END = COMMON | {"before_calls", "original_calls", "after_calls", "reason", "cleanup_reported"}
LONG_MIN, LONG_MAX = -(2**63), 2**63 - 1
INT_MAX = 2**31 - 1


class InvalidRecord(ValueError):
    """Input contradicts the diagnostic protocol."""


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidRecord("duplicate JSON field: " + key)
        result[key] = value
    return result


def canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def is_tagged(line):
    return bool(TAGGED_LINE.search(line) or THREADTIME_TAG_START.search(line))


def corrupted_tag(line):
    """Recognize undecodable inserted/replaced bytes in a threadtime target tag."""
    match = THREADTIME_TAG_FIELD.match(line)
    if not match or not INVALID_UTF8.search(match.group("tag")):
        return False
    positions = {0}
    for char in match.group("tag"):
        if "\udc80" <= char <= "\udcff":
            positions |= {index + 1 for index in positions if index < len(TAG)}
        else:
            positions = {index + 1 for index in positions if index < len(TAG) and TAG[index] == char}
        if not positions:
            return False
    return len(TAG) in positions


def events_from_raw(raw):
    """Use original bytes for identity and reject any recognizable damaged tag."""
    for line_number, line_bytes in enumerate(raw.split(b"\n"), 1):
        line = line_bytes.decode("utf-8", errors="surrogateescape")
        if line.endswith("\r"):
            line = line[:-1]
        if INVALID_UTF8.search(line):
            if is_tagged(INVALID_UTF8.sub("", line)) or corrupted_tag(line):
                raise InvalidRecord("invalid UTF-8 on CPU-body tagged line %d" % line_number)
            continue
        if not is_tagged(line):
            continue
        match = THREADTIME.fullmatch(line)
        if not match:
            raise InvalidRecord("CPU-body tagged line %d is not threadtime JSON" % line_number)
        try:
            event = json.loads(match.group("json"), object_pairs_hook=unique_fields)
        except (ValueError, TypeError) as exc:
            raise InvalidRecord("invalid CPU-body JSON on line %d" % line_number) from exc
        if not isinstance(event, dict) or not canonical_uuid(event.get("run_id")):
            raise InvalidRecord("missing or invalid run ID on line %d" % line_number)
        yield line_number, int(match.group("pid")), int(match.group("tid")), event


def exact_fields(value, expected, where):
    if set(value) != expected:
        raise InvalidRecord("unexpected or missing fields in " + where)


def bounded_integer(value, minimum, maximum, where):
    if type(value) is not int or not minimum <= value <= maximum:
        raise InvalidRecord("invalid integer for " + where)


def failure_reason(value, where):
    if not isinstance(value, str) or not FAILURE_REASON.fullmatch(value):
        raise InvalidRecord("invalid failure reason in " + where)


def interval_summary(readings, stage, warmup):
    intervals = [x[stage + "_cpu_end_ns"] - x[stage + "_cpu_start_ns"]
                 for x in readings if x["ordinal"] >= warmup]
    positive = [value for value in intervals if value > 0]
    return {"increment_observation": "observed" if positive else "unresolved",
            "sample_count": len(intervals), "zero_interval_count": len(intervals) - len(positive),
            "positive_interval_count": len(positive),
            "smallest_positive_interval_ns": min(positive) if positive else None}


def verify(raw, run_id, pid, tid, backend, control, workload):
    """Return the unmodified CPU readings; caller must establish device provenance."""
    if not canonical_uuid(run_id):
        raise InvalidRecord("run ID must be a canonical lowercase UUID")
    bounded_integer(pid, 1, INT_MAX, "selected pid")
    bounded_integer(tid, 1, INT_MAX, "selected tid")
    if backend not in ("101", "102") or control not in ("A0", "A2") or workload not in ("B01", "B02"):
        raise InvalidRecord("unsupported backend, control or workload")

    selected = []
    for line, line_pid, line_tid, event in events_from_raw(raw):
        if event.get("run_id") != run_id:
            if line_pid == pid or (type(event.get("pid")) is int and event["pid"] == pid):
                raise InvalidRecord("conflicting run for selected PID on line %d" % line)
            continue
        if (type(event.get("pid")) is not int or type(event.get("tid")) is not int
                or event["pid"] != pid or event["tid"] != tid or line_pid != pid or line_tid != tid):
            raise InvalidRecord("CPU-body PID/TID mismatch on line %d" % line)
        selected.append((line, event))

    result = {"run_id": run_id, "pid": pid, "tid": tid, "backend": backend, "control": control,
              "workload": workload, "raw_log_sha256": hashlib.sha256(raw).hexdigest(), "readings": [],
              "performance_gate": "not_evaluated", "device_authentication": "not_verified",
              "scope": "instrumented callback-body CPU intervals; excludes proceed/original/dispatcher; "
                       "not pure callback CPU, clock accuracy or a performance gate"}
    if not selected:
        return {**result, "result": "incomplete", "reason": "no events for selected run"}

    line, start = selected[0]
    exact_fields(start, START, "start")
    if (type(start["schema"]) is not int or start["schema"] != 1 or start["event"] != "start"
            or start["status"] != "start" or start["backend"] != backend or start["control"] != control
            or start["workload"] != workload or start["clock"] != "thread_cpu_ns"
            or start["measurement_kind"] != "instrumented_callback_body_cpu_interval"):
        raise InvalidRecord("invalid or mismatched start on line %d" % line)
    bounded_integer(start["warmup"], 0, 100, "warmup")
    bounded_integer(start["samples"], 1, 200, "samples")
    total = start["warmup"] + start["samples"]
    result["warmup"], result["samples"] = start["warmup"], start["samples"]
    previous_end = None
    failed = None
    ended = False
    for line, event in selected[1:]:
        if ended:
            raise InvalidRecord("event after end on line %d" % line)
        if type(event.get("schema")) is not int or event.get("schema") != 1:
            raise InvalidRecord("invalid event schema on line %d" % line)
        if event.get("event") == "end":
            exact_fields(event, END, "end")
            for key in ("before_calls", "original_calls", "after_calls"):
                bounded_integer(event[key], 0, INT_MAX, key)
            if type(event["cleanup_reported"]) is not bool:
                raise InvalidRecord("invalid cleanup_reported")
            if event["status"] == "fail":
                failure_reason(event["reason"], "end")
                failed = failed or event["reason"]
            elif event["status"] == "pass":
                expected_after = total if workload == "B01" else 0
                if (failed is not None or event["reason"] != "" or not event["cleanup_reported"]
                        or len(result["readings"]) != total or event["before_calls"] != total
                        or event["original_calls"] != total or event["after_calls"] != expected_after):
                    raise InvalidRecord("end claims success without all calls and cleanup")
            else:
                raise InvalidRecord("invalid end status")
            result["end"] = {key: event[key] for key in ("status", "before_calls", "original_calls",
                                                          "after_calls", "reason", "cleanup_reported")}
            ended = True
            continue
        if failed is not None:
            raise InvalidRecord("call after a reported failure")
        exact_fields(event, CALL, "call")
        if event["event"] != "call":
            raise InvalidRecord("unexpected event on line %d" % line)
        bounded_integer(event["ordinal"], 0, total - 1, "ordinal")
        if event["ordinal"] != len(result["readings"]):
            raise InvalidRecord("out-of-order or duplicate ordinal on line %d" % line)
        for key in ("before_count", "original_count", "after_count"):
            bounded_integer(event[key], 0, INT_MAX, key)
        time_keys = ("before_cpu_start_ns", "before_cpu_end_ns", "after_cpu_start_ns", "after_cpu_end_ns")
        for key in time_keys:
            if event[key] is not None:
                bounded_integer(event[key], LONG_MIN, LONG_MAX, key)
        if workload == "B02" and (event["after_cpu_start_ns"] is not None or event["after_cpu_end_ns"] is not None):
            raise InvalidRecord("B02 must not contain after timestamps")
        row = {"line": line, **{key: event[key] for key in
               ("ordinal", "status", "before_count", "original_count", "after_count", *time_keys, "reason")}}
        result["readings"].append(row)
        if event["status"] == "fail":
            failure_reason(event["reason"], "call")
            failed = event["reason"]
            continue
        if event["status"] != "pass" or event["reason"] != "":
            raise InvalidRecord("invalid call status or reason")
        expected_after = 1 if workload == "B01" else 0
        if (event["before_count"], event["original_count"], event["after_count"]) != (1, 1, expected_after):
            raise InvalidRecord("call counts contradict its success status")
        stages = ("before", "after") if workload == "B01" else ("before",)
        for stage in stages:
            begin, end = event[stage + "_cpu_start_ns"], event[stage + "_cpu_end_ns"]
            if begin is None or end is None or begin < 0 or end < 0:
                raise InvalidRecord("successful call has missing or unavailable CPU values")
            if end < begin or (previous_end is not None and begin < previous_end):
                raise InvalidRecord("successful CPU readings moved backward")
            previous_end = end

    if failed is not None:
        return {**result, "result": "diagnostic_failure", "reason": failed, "terminal_event_present": ended}
    if not ended:
        return {**result, "result": "incomplete", "reason": "missing successful end or calls"}
    result["before"] = interval_summary(result["readings"], "before", start["warmup"])
    if workload == "B01":
        result["after"] = interval_summary(result["readings"], "after", start["warmup"])
    stages = [result["before"]] + ([result["after"]] if workload == "B01" else [])
    if any(value["increment_observation"] == "unresolved" for value in stages):
        return {**result, "result": "unresolved", "reason": "at least one sampled stage has only zero intervals"}
    return {**result, "result": "observed", "reason": "positive intervals observed for all sampled stages"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    parser.add_argument("--run-id", required=True, help="selected canonical lowercase UUID")
    parser.add_argument("--pid", type=int, required=True, help="selected process ID")
    parser.add_argument("--tid", type=int, required=True, help="selected worker thread ID")
    parser.add_argument("--backend", choices=("101", "102"), required=True)
    parser.add_argument("--control", choices=("A0", "A2"), required=True)
    parser.add_argument("--workload", choices=("B01", "B02"), required=True)
    args = parser.parse_args()
    raw_sha256 = None
    try:
        raw = args.log.read_bytes()
        raw_sha256 = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pid, args.tid, args.backend, args.control, args.workload)
    except (InvalidRecord, OSError, UnicodeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified", "raw_log_sha256": raw_sha256}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {"observed": 0, "unresolved": 2, "incomplete": 2,
            "diagnostic_failure": 1, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
