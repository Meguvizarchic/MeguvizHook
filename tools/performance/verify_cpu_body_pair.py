#!/usr/bin/env python3
"""Verify one paired instrumented callback-body diagnostic; never evaluate a gate.

The manifests, device identity, process start ticks and collector monotonic times
are supplied by the collector and are not authenticated by this tool. Each raw
log is independently checked by verify_cpu_body_probe without modifying inputs.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path

from verify_cpu_body_probe import InvalidRecord, canonical_uuid, unique_fields, verify


HEX64 = re.compile(r"[0-9a-f]{64}\Z")
LONG_MAX = 2**63 - 1
INT_MAX = 2**31 - 1
TOP = {"schema_version", "record_type", "pair_id", "side", "pair_order", "run_id", "pid", "tid",
       "backend", "control", "workload", "warmup", "samples", "clock", "measurement_kind",
       "artifacts", "device", "process_start_ticks", "host_clock_token", "launch_monotonic_ns",
       "terminal_monotonic_ns", "raw_log_sha256"}
ARTIFACTS = {"host_apk_sha256", "module_apk_sha256"}
DEVICE = {"device_id_sha256", "build_fingerprint_sha256", "boot_id", "framework_name", "framework_version"}


def fields(value, expected, where):
    if not isinstance(value, dict) or set(value) != expected:
        raise InvalidRecord("unexpected or missing fields in " + where)


def integer(value, minimum, maximum, where):
    if type(value) is not int or not minimum <= value <= maximum:
        raise InvalidRecord("invalid integer for " + where)


def identifier(value, where):
    if not canonical_uuid(value):
        raise InvalidRecord("invalid canonical UUID for " + where)


def sha(value, where):
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        raise InvalidRecord("invalid SHA-256 for " + where)


def label(value, where):
    if (not isinstance(value, str) or len(value) > 128 or not value.strip()
            or value.strip().lower() == "null" or any(ord(c) < 32 for c in value)):
        raise InvalidRecord("invalid value for " + where)


def validate_manifest(record):
    fields(record, TOP, "manifest")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise InvalidRecord("unsupported manifest schema_version")
    if record["record_type"] != "cpu_body_probe_side":
        raise InvalidRecord("unsupported record_type")
    for key in ("pair_id", "run_id", "host_clock_token"):
        identifier(record[key], key)
    if record["side"] not in ("A", "B") or record["pair_order"] not in ("AB", "BA"):
        raise InvalidRecord("invalid side or pair_order")
    if record["backend"] not in ("101", "102") or record["control"] not in ("A0", "A2"):
        raise InvalidRecord("unsupported backend or control")
    if record["workload"] not in ("B01", "B02"):
        raise InvalidRecord("unsupported workload")
    if (record["clock"] != "thread_cpu_ns"
            or record["measurement_kind"] != "instrumented_callback_body_cpu_interval"):
        raise InvalidRecord("unsupported clock or measurement kind")
    integer(record["pid"], 1, INT_MAX, "pid")
    integer(record["tid"], 1, INT_MAX, "tid")
    integer(record["warmup"], 0, 100, "warmup")
    integer(record["samples"], 1, 200, "samples")
    integer(record["process_start_ticks"], 1, LONG_MAX, "process_start_ticks")
    integer(record["launch_monotonic_ns"], 0, LONG_MAX, "launch_monotonic_ns")
    integer(record["terminal_monotonic_ns"], 0, LONG_MAX, "terminal_monotonic_ns")
    if record["terminal_monotonic_ns"] <= record["launch_monotonic_ns"]:
        raise InvalidRecord("terminal time must follow launch time")
    fields(record["artifacts"], ARTIFACTS, "artifacts")
    for key in ARTIFACTS:
        sha(record["artifacts"][key], "artifacts." + key)
    fields(record["device"], DEVICE, "device")
    for key in ("device_id_sha256", "build_fingerprint_sha256"):
        sha(record["device"][key], "device." + key)
    identifier(record["device"]["boot_id"], "device.boot_id")
    for key in ("framework_name", "framework_version"):
        label(record["device"][key], "device." + key)
    sha(record["raw_log_sha256"], "raw_log_sha256")
    return record


def verify_pair(left, right, left_raw, right_raw):
    """Check two collector manifests and the original raw log bytes, without attesting provenance."""
    left, right = validate_manifest(left), validate_manifest(right)
    if {left["side"], right["side"]} != {"A", "B"}:
        raise InvalidRecord("pair requires exactly one A side and one B side")
    for key in ("pair_id", "pair_order", "backend", "control", "workload", "warmup", "samples",
                "clock", "measurement_kind", "artifacts", "device", "host_clock_token"):
        if type(left[key]) is not type(right[key]) or left[key] != right[key]:
            raise InvalidRecord("pair mismatch: " + key)
    if len({left["pair_id"], left["run_id"], right["run_id"]}) != 3:
        raise InvalidRecord("pair and run IDs must be independent")
    if (left["pid"], left["process_start_ticks"]) == (right["pid"], right["process_start_ticks"]):
        raise InvalidRecord("paired records identify the same process")
    sides = {left["side"]: left, right["side"]: right}
    first, second = (sides[side] for side in left["pair_order"])
    if first["process_start_ticks"] >= second["process_start_ticks"]:
        raise InvalidRecord("process start ticks contradict pair order")
    if first["terminal_monotonic_ns"] >= second["launch_monotonic_ns"]:
        raise InvalidRecord("collector monotonic times contradict pair order")

    reports = {}
    for record, raw in ((left, left_raw), (right, right_raw)):
        if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != record["raw_log_sha256"]:
            raise InvalidRecord("raw log SHA-256 mismatch for side " + record["side"])
        report = verify(raw, record["run_id"], record["pid"], record["tid"],
                        record["backend"], record["control"], record["workload"])
        for key in ("warmup", "samples"):
            if key in report and (type(report[key]) is not int or report[key] != record[key]):
                raise InvalidRecord("log disagrees with manifest " + key)
        reports[record["side"]] = report

    statuses = {report["result"] for report in reports.values()}
    if "diagnostic_failure" in statuses:
        status, reason = "diagnostic_failure", "at least one side reported diagnostic failure"
    elif "incomplete" in statuses:
        status, reason = "incomplete", "at least one side is incomplete"
    elif "unresolved" in statuses:
        status, reason = "unresolved", "at least one side has unresolved sampled intervals"
    elif statuses == {"observed"}:
        status, reason = "body_cpu_pair_consistent", "both raw logs and collector manifests are consistent"
    else:
        raise InvalidRecord("unexpected single-side result")
    return {"result": status, "reason": reason, "pair_id": left["pair_id"],
            "pair_order": left["pair_order"], "backend": left["backend"], "control": left["control"],
            "workload": left["workload"], "warmup": left["warmup"], "samples": left["samples"],
            "sides": reports, "performance_gate": "not_evaluated", "device_authentication": "not_verified",
            "provenance": "raw logs checked; device, artifacts, process-start ticks and host monotonic times are collector-reported and not independently authenticated",
            "scope": "instrumented callback-body CPU intervals with clock/probe overhead; not pure callback CPU, task p95/p99, formal A/A qualification or a performance gate"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest_one", type=Path)
    parser.add_argument("manifest_two", type=Path)
    parser.add_argument("--log-one", type=Path, required=True)
    parser.add_argument("--log-two", type=Path, required=True)
    args = parser.parse_args()
    try:
        one_bytes, two_bytes = args.manifest_one.read_bytes(), args.manifest_two.read_bytes()
        one = json.loads(one_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
        two = json.loads(two_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
        result = verify_pair(one, two, args.log_one.read_bytes(), args.log_two.read_bytes())
        result["manifest_sha256"] = {"one": hashlib.sha256(one_bytes).hexdigest(),
                                     "two": hashlib.sha256(two_bytes).hexdigest()}
    except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {"body_cpu_pair_consistent": 0, "unresolved": 2, "incomplete": 2,
            "diagnostic_failure": 1, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    raise SystemExit(main())
