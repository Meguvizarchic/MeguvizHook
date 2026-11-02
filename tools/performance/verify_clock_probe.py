#!/usr/bin/env python3
"""Verify one raw Android thread-CPU clock diagnostic; never evaluate a performance gate.

The emitted pairs contain the costs of the clock calls and surrounding work. The
smallest positive interval observed is not a claim about clock resolution, accuracy,
or callback CPU. Keep the original log and separately verify device provenance.
"""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path


TAG = "MeguvizHookClock"
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
COMMON = {"schema", "run_id", "pid", "tid", "event", "status"}
START = COMMON | {"warmup", "samples", "clock", "wall_clock"}
READING = COMMON | {"index", "cpu_before_ns", "cpu_after_ns", "wall_before_ns", "wall_after_ns", "reason"}
END = COMMON | {"reason"}
LONG_MIN = -(2**63)
LONG_MAX = 2**63 - 1


class InvalidRecord(ValueError):
    """Raw data contradicts the diagnostic protocol."""


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
    """Decode each original line without altering raw bytes or hiding damaged target lines."""
    for line_number, line_bytes in enumerate(raw.split(b"\n"), 1):
        line = line_bytes.decode("utf-8", errors="surrogateescape")
        if line.endswith("\r"):
            line = line[:-1]
        if INVALID_UTF8.search(line):
            if is_tagged(INVALID_UTF8.sub("", line)) or corrupted_tag(line):
                raise InvalidRecord("invalid UTF-8 on clock-tagged line %d" % line_number)
            continue
        if not is_tagged(line):
            continue
        match = THREADTIME.fullmatch(line)
        if not match:
            raise InvalidRecord("clock-tagged line %d is not threadtime JSON" % line_number)
        try:
            event = json.loads(match.group("json"), object_pairs_hook=unique_fields)
        except (ValueError, TypeError) as exc:
            raise InvalidRecord("invalid clock JSON on line %d" % line_number) from exc
        if not isinstance(event, dict) or not canonical_uuid(event.get("run_id")):
            raise InvalidRecord("missing or invalid clock run ID on line %d" % line_number)
        yield line_number, int(match.group("pid")), int(match.group("tid")), event


def exact_fields(value, expected, where):
    if set(value) != expected:
        raise InvalidRecord("unexpected or missing fields in " + where)


def bounded_integer(value, minimum, maximum, where):
    if type(value) is not int or not minimum <= value <= maximum:
        raise InvalidRecord("invalid integer for " + where)


def reading_error(event, previous_cpu, previous_wall):
    """Use the same failure priority as the producer; equality remains legal."""
    cb, ca = event["cpu_before_ns"], event["cpu_after_ns"]
    wb, wa = event["wall_before_ns"], event["wall_after_ns"]
    if cb < 0 or ca < 0:
        return "cpu_unavailable"
    if wb < 0 or wa < 0:
        return "wall_unavailable"
    if ca < cb or (previous_cpu is not None and cb < previous_cpu):
        return "cpu_backward"
    if wa < wb or (previous_wall is not None and wb < previous_wall):
        return "wall_backward"
    return ""


def interval_summary(readings, clock):
    deltas = [x[clock + "_after_ns"] - x[clock + "_before_ns"]
              for x in readings if x["phase"] == "sample" and x["status"] == "pass"]
    positives = [x for x in deltas if x > 0]
    return {"increment_observation": "observed" if positives else "unresolved",
            "sample_count": len(deltas), "zero_interval_count": len(deltas) - len(positives),
            "positive_interval_count": len(positives),
            "smallest_positive_interval_ns": min(positives) if positives else None}


def verify(raw, run_id, pid, tid):
    """Return a diagnostic with original paired readings; no device attestation."""
    if not canonical_uuid(run_id):
        raise InvalidRecord("run ID must be a canonical lowercase UUID")
    bounded_integer(pid, 1, 2**31 - 1, "selected pid")
    bounded_integer(tid, 1, 2**31 - 1, "selected tid")
    selected = []
    for line, line_pid, line_tid, event in events_from_raw(raw):
        if event.get("run_id") != run_id:
            # The producer accepts only one diagnostic run per process. PID reuse
            # or mixed logs cannot be distinguished, so do not silently discard
            # a conflicting run that claims or is logged under the selected PID.
            if line_pid == pid or (type(event.get("pid")) is int and event["pid"] == pid):
                raise InvalidRecord("conflicting run for selected PID on line %d" % line)
            continue
        if (type(event.get("pid")) is not int or type(event.get("tid")) is not int
                or event["pid"] != pid or event["tid"] != tid
                or line_pid != pid or line_tid != tid):
            raise InvalidRecord("clock PID/TID mismatch on line %d" % line)
        selected.append((line, event))
    result = {"run_id": run_id, "pid": pid, "tid": tid,
              "raw_log_sha256": hashlib.sha256(raw).hexdigest(), "readings": [],
              "performance_gate": "not_evaluated", "device_authentication": "not_verified",
              "scope": "consecutive clock reads plus call overhead; not clock accuracy or callback CPU"}
    if not selected:
        return {**result, "result": "incomplete", "reason": "no events for selected run"}
    line, start = selected[0]
    exact_fields(start, START, "start")
    if (type(start["schema"]) is not int or start["schema"] != 1
            or start["event"] != "start" or start["status"] != "start"
            or start["clock"] != "thread_cpu_ns" or start["wall_clock"] != "elapsed_realtime_ns"):
        raise InvalidRecord("invalid start event on line %d" % line)
    bounded_integer(start["warmup"], 0, 100, "warmup")
    bounded_integer(start["samples"], 1, 200, "samples")
    result["warmup"] = start["warmup"]
    result["samples"] = start["samples"]
    expected = ([('warmup', i) for i in range(start["warmup"])]
                + [('sample', i) for i in range(start["samples"])])
    previous_cpu = previous_wall = None
    index = 0
    failure = None
    ended = False
    for line, event in selected[1:]:
        if ended:
            raise InvalidRecord("event after end on line %d" % line)
        if type(event.get("schema")) is not int or event.get("schema") != 1:
            raise InvalidRecord("invalid event schema on line %d" % line)
        if event.get("event") == "end":
            exact_fields(event, END, "end")
            if failure is not None:
                if event["status"] != "fail" or event["reason"] != failure:
                    raise InvalidRecord("failure end contradicts preceding reading")
            elif event["status"] != "pass" or event["reason"] != "" or index != len(expected):
                raise InvalidRecord("end claims success without all valid readings")
            ended = True
            continue
        if failure is not None:
            raise InvalidRecord("reading after a reported clock failure")
        exact_fields(event, READING, "reading")
        if index >= len(expected) or (event.get("event"), event.get("index")) != expected[index]:
            raise InvalidRecord("out-of-order or duplicate reading on line %d" % line)
        bounded_integer(event["index"], 0, 199, "index")
        for key in ("cpu_before_ns", "cpu_after_ns", "wall_before_ns", "wall_after_ns"):
            bounded_integer(event[key], LONG_MIN, LONG_MAX, key)
        observed_error = reading_error(event, previous_cpu, previous_wall)
        row = {"line": line, "phase": event["event"], "index": event["index"],
               "status": event["status"], "reason": event["reason"],
               **{key: event[key] for key in ("cpu_before_ns", "cpu_after_ns", "wall_before_ns", "wall_after_ns")}}
        result["readings"].append(row)
        if observed_error:
            if event["status"] != "fail" or event["reason"] != observed_error:
                raise InvalidRecord("reading status or reason contradicts invalid clock values")
            failure = observed_error
        elif event["status"] != "pass" or event["reason"] != "":
            raise InvalidRecord("valid reading incorrectly marked as failure")
        else:
            previous_cpu = event["cpu_after_ns"]
            previous_wall = event["wall_after_ns"]
        index += 1
    if failure is not None:
        return {**result, "result": "clock_failure", "reason": failure, "terminal_event_present": ended}
    if not ended:
        return {**result, "result": "incomplete", "reason": "missing successful end or readings"}
    result["cpu"] = interval_summary(result["readings"], "cpu")
    result["wall"] = interval_summary(result["readings"], "wall")
    if "unresolved" in (result["cpu"]["increment_observation"], result["wall"]["increment_observation"]):
        return {**result, "result": "unresolved", "reason": "at least one clock has only zero sample intervals"}
    return {**result, "result": "observed", "reason": "positive intervals observed for both clocks"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    parser.add_argument("--run-id", required=True, help="selected canonical lowercase UUID")
    parser.add_argument("--pid", type=int, required=True, help="selected process ID")
    parser.add_argument("--tid", type=int, required=True, help="selected worker thread ID")
    args = parser.parse_args()
    raw_sha256 = None
    try:
        raw = args.log.read_bytes()
        raw_sha256 = hashlib.sha256(raw).hexdigest()
        result = verify(raw, args.run_id, args.pid, args.tid)
    except (InvalidRecord, OSError, UnicodeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified", "raw_log_sha256": raw_sha256}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {"observed": 0, "unresolved": 2, "incomplete": 2,
            "clock_failure": 1, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
