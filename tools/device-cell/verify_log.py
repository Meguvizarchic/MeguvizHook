#!/usr/bin/env python3
"""Check one explicitly selected device-cell run from captured logcat text.

This validates the event protocol, not the authenticity of logcat or the runtime
framework generation. Keep the raw capture and independent device metadata.
"""

import argparse
import json
import re
import sys
import uuid
from pathlib import Path

TAG = "MeguvizHookCell"
THREADTIME_EVENT = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"(?P<pid>\d+)\s+\d+\s+[VDIWEF]\s+" + TAG + r"\s*:\s*(?P<json>\{.*\})\s*$"
)
TAGGED_LINE = re.compile(r"(?:^|\s)" + TAG + r"\s*(?:\(\s*\d+\s*\))?\s*:")
# Recognize a target in the threadtime tag slot even if its delimiter is malformed.
THREADTIME_TAG_START = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+" + TAG + r"(?=$|[\s:(])"
)
THREADTIME_TAG_FIELD = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+(?P<tag>[^\s:]+)"
)
INVALID_UTF8 = re.compile(r"[\udc80-\udcff]")
CHECKS = {
    "101": ("baseline", "install", "installed_call", "replace_unsupported",
            "after_rejected_replace", "close", "restored_call"),
    "102": ("baseline", "install", "installed_call", "replace", "replaced_call",
            "old_close", "after_old_close", "close", "restored_call"),
}
CALLS = {
    "101": {
        "baseline": (5, 1, 0, 0),
        "installed_call": (10, 0, 1, 0),
        "after_rejected_replace": (10, 0, 2, 0),
        "restored_call": (5, 1, 2, 0),
    },
    "102": {
        "baseline": (5, 1, 0, 0),
        "installed_call": (10, 0, 1, 0),
        "replaced_call": (20, 0, 1, 1),
        "after_old_close": (20, 0, 1, 2),
        "restored_call": (5, 1, 1, 2),
    },
}
# The original API101 protocol remains valid only as historical rejection evidence.
# API101 probe_version=2 exercises the SDK channel switch using the 102-shaped checks.
CHECKS_101_REPLACEMENT = CHECKS["102"]
CALLS_101_REPLACEMENT = CALLS["102"]
OTHER_DETAILS = {
    "install": "status=INSTALLED",
    "replace_unsupported": "status=UNSUPPORTED_CAPABILITY",
    "replace": "status=INSTALLED",
    "old_close": "entry=NOT_RESTORED",
    "close": "entry=RESTORED",
}


def expected_detail(backend, name, probe_version=1):
    calls_for_probe = (CALLS_101_REPLACEMENT if backend == "101" and probe_version == 2
                       else CALLS[backend])
    if name not in calls_for_probe:
        return OTHER_DETAILS[name]
    value, calls, old, new = calls_for_probe[name]
    return ("value=%d,calls=%d,expected=%d,expected_calls=%d,"
            "old_callbacks=%d,new_callbacks=%d,expected_old_callbacks=%d,expected_new_callbacks=%d"
            % (value, calls, value, calls, old, new, old, new))


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def is_tagged_line(line):
    return bool(TAGGED_LINE.search(line) or THREADTIME_TAG_START.search(line))


def is_corrupted_target_tag(line):
    """Fail closed when invalid tag bytes could have been the target tag."""
    match = THREADTIME_TAG_FIELD.match(line)
    if not match or not INVALID_UTF8.search(match.group("tag")):
        return False
    positions = {0}
    for char in match.group("tag"):
        if "\udc80" <= char <= "\udcff":
            # The invalid byte may have been inserted or replaced one ASCII byte.
            positions |= {index + 1 for index in positions if index < len(TAG)}
        else:
            positions = {index + 1 for index in positions
                         if index < len(TAG) and TAG[index] == char}
        if not positions:
            return False
    return len(TAG) in positions


def verify(lines, backend, run_id, pid):
    events = []
    for line_number, line in enumerate(lines, 1):
        # Surrogate escape preserves undecodable bytes from unrelated logcat lines.
        # Remove them only for tag detection, so an invalid byte inserted into a
        # tag or its delimiter cannot hide a malformed target line.
        if INVALID_UTF8.search(line):
            if is_tagged_line(INVALID_UTF8.sub("", line)) or is_corrupted_target_tag(line):
                return "fail", "invalid UTF-8 on tagged line %d" % line_number, None
            continue
        if not is_tagged_line(line):
            continue
        match = THREADTIME_EVENT.match(line)
        if not match:
            return "fail", "tagged line %d is not valid threadtime" % line_number, None
        try:
            event = json.loads(match.group("json"), object_pairs_hook=unique_fields)
        except (ValueError, TypeError):
            return "fail", "invalid JSON on tagged line %d" % line_number, None
        if not isinstance(event, dict):
            return "fail", "event is not an object on line %d" % line_number, None
        if int(match.group("pid")) == pid:
            candidate = event.get("run_id")
            try:
                if not isinstance(candidate, str) or str(uuid.UUID(candidate)) != candidate:
                    raise ValueError("run ID must be a canonical UUID")
            except (ValueError, TypeError, AttributeError):
                return "fail", "missing or invalid run ID on selected PID line %d" % line_number, None
        if event.get("run_id") == run_id:
            events.append((int(match.group("pid")), event))

    if not events:
        return "incomplete", "no events for the selected run", None
    for line_pid, event in events:
        if (type(event.get("schema")) is not int or event["schema"] != 1
                or event.get("backend") != backend
                or type(event.get("pid")) is not int or event["pid"] != pid
                or line_pid != pid
                or not isinstance(event.get("detail"), str)
                or len(event["detail"]) > 512):
            return "fail", "invalid schema, backend, pid or detail", None
        if event.get("status") == "fail":
            return "fail", "device reported failure at %s" % event.get("name"), None

    start = events[0][1]
    if (start.get("event"), start.get("status"), start.get("name")) != ("start", "start", "run"):
        return "fail", "first event is not start", None
    if (not isinstance(start.get("framework_name"), str)
            or not start["framework_name"].strip() or start["framework_name"].strip().lower() == "null"
            or not isinstance(start.get("framework_version"), str)
            or not start["framework_version"].strip() or start["framework_version"].strip().lower() == "null"
            or type(start.get("reported_api")) is not int):
        return "fail", "framework metadata missing or invalid", None
    probe_version = start.get("probe_version", 1)
    if (type(probe_version) is not int or probe_version not in (1, 2)
            or (backend != "101" and probe_version != 1)):
        return "fail", "unsupported probe version", None
    checks = CHECKS_101_REPLACEMENT if backend == "101" and probe_version == 2 else CHECKS[backend]
    expected = [("start", "start", "run")]
    expected.extend(("check", "pass", name) for name in checks)
    expected.append(("end", "pass", "run"))
    actual = [(e.get("event"), e.get("status"), e.get("name")) for _, e in events]
    if actual != expected[:len(actual)]:
        return "fail", "unexpected, duplicated or out-of-order event", None
    for _, event in events:
        if event["event"] == "check":
            if event["detail"] != expected_detail(backend, event["name"], probe_version):
                return "fail", "unexpected result at %s" % event["name"], None
        elif event["detail"]:
            return "fail", "unexpected detail on start or end", None
    if len(actual) < len(expected):
        return "incomplete", "missing checks or successful end event", None
    return "pass", "all expected events present", {
        "framework_name": start["framework_name"],
        "framework_version": start["framework_version"],
        "reported_api": start["reported_api"],
        "probe_version": probe_version,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="captured logcat text")
    parser.add_argument("--backend", choices=sorted(CHECKS), required=True)
    parser.add_argument("--run-id", required=True, help="exact UUID from the start event")
    parser.add_argument("--pid", type=int, required=True, help="host process ID for this run")
    args = parser.parse_args()
    try:
        if str(uuid.UUID(args.run_id)) != args.run_id or args.pid <= 0:
            raise ValueError("run ID must be a canonical UUID and PID must be positive")
        with args.log.open(encoding="utf-8", errors="surrogateescape") as source:
            status, reason, metadata = verify(source, args.backend, args.run_id, args.pid)
    except (OSError, UnicodeError, ValueError) as exc:
        print(json.dumps({"status": "fail", "reason": str(exc)}, ensure_ascii=True))
        return 1
    print(json.dumps({"status": status, "reason": reason, "backend": args.backend,
                      "run_id": args.run_id, "pid": args.pid, "framework": metadata}, ensure_ascii=True))
    return {"pass": 0, "fail": 1, "incomplete": 2}[status]


if __name__ == "__main__":
    sys.exit(main())
