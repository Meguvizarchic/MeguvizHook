#!/usr/bin/env python3
"""Check two reported B07 installation logs and collector manifests; never evaluate a gate."""

import argparse
import hashlib
import json
import re
from fractions import Fraction
from pathlib import Path

from verify_aa_pilot import (IncompleteRecord, InvalidRecord, identifier, iter_bench_events,
                             number, unique_fields)
from verify_b07_install import verify


MAX_NS = 2**63 - 1
INT_MAX = 2**31 - 1
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
TOP = {"schema_version", "record_type", "pair_id", "side", "pair_order", "run_id", "session_id",
       "pid", "tid", "host_uid", "process_start_ticks", "backend", "control", "workload", "warmup", "samples",
       "iterations", "measurement_kind", "artifacts", "device", "host_clock_token",
       "launch_monotonic_ns", "terminal_monotonic_ns", "raw_log_sha256"}
ARTIFACTS = {"host_apk_sha256", "module_apk_sha256", "workload_sha256", "feature_manifest_sha256"}
DEVICE = {"device_id_sha256", "build_fingerprint_sha256", "boot_id", "android_api", "abi", "android_user",
          "framework_name", "framework_version", "reported_api"}
PROVENANCE = ("raw logs and collector declarations cross-checked; device, artifacts, process-start "
              "ticks, boot and collector monotonic times are not independently authenticated")


def fields(value, expected, where):
    if not isinstance(value, dict):
        raise InvalidRecord(where + " must be an object")
    missing, extra = expected - set(value), set(value) - expected
    if extra:
        raise InvalidRecord(where + " has unknown fields: " + ", ".join(sorted(extra)))
    if missing:
        raise IncompleteRecord(where + " missing fields: " + ", ".join(sorted(missing)))


def digest(value, where):
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        raise InvalidRecord(where + " must be lowercase SHA-256 hex")


def label(value, where):
    if (not isinstance(value, str) or not value or value != value.strip() or len(value) > 256
            or value.lower() == "null" or any(ord(char) < 32 for char in value)):
        raise InvalidRecord("invalid " + where)


def validate_manifest(record):
    fields(record, TOP, "manifest")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise InvalidRecord("unsupported manifest schema_version")
    if record["record_type"] != "b07_install_probe_side":
        raise InvalidRecord("unsupported manifest record_type")
    for key in ("pair_id", "run_id", "session_id", "host_clock_token"):
        identifier(record[key], key)
    if len({record["pair_id"], record["run_id"], record["session_id"]}) != 3:
        raise InvalidRecord("run, pair and session identifiers must differ")
    if record["side"] not in ("A", "B") or record["pair_order"] not in ("AB", "BA"):
        raise InvalidRecord("invalid side or pair_order")
    if record["backend"] not in ("101", "102") or record["control"] != "A2" or record["workload"] != "B07":
        raise InvalidRecord("pair only supports B07 A2 on backend 101 or 102")
    for key, expected in (("warmup", 0), ("samples", 1), ("iterations", 1)):
        if type(record[key]) is not int or record[key] != expected:
            raise InvalidRecord("invalid B07 " + key)
    if record["measurement_kind"] != "install_wall_and_thread_cpu_ns":
        raise InvalidRecord("unsupported measurement_kind")
    for key in ("pid", "tid"):
        number(record[key], key, 1, INT_MAX)
    number(record["host_uid"], "host_uid", 1, INT_MAX)
    number(record["process_start_ticks"], "process_start_ticks", 1, MAX_NS)
    for key in ("launch_monotonic_ns", "terminal_monotonic_ns"):
        number(record[key], key, 0, MAX_NS)
    if record["terminal_monotonic_ns"] <= record["launch_monotonic_ns"]:
        raise InvalidRecord("terminal time must follow launch time")
    fields(record["artifacts"], ARTIFACTS, "artifacts")
    for key in ARTIFACTS:
        digest(record["artifacts"][key], "artifacts." + key)
    fields(record["device"], DEVICE, "device")
    for key in ("device_id_sha256", "build_fingerprint_sha256"):
        digest(record["device"][key], "device." + key)
    identifier(record["device"]["boot_id"], "device.boot_id")
    for key in ("framework_name", "framework_version", "abi"):
        label(record["device"][key], "device." + key)
    for key in ("android_api", "reported_api"):
        number(record["device"][key], "device." + key, 1, 10000)
    number(record["device"]["android_user"], "device.android_user", 0, INT_MAX)
    digest(record["raw_log_sha256"], "raw_log_sha256")
    return record


def _cross_check_framework(record, raw):
    # The single-side verifier validates the event sequence but does not return
    # configured metadata. Re-read these same bytes to bind it to this manifest.
    for _, prefix_pid, prefix_tid, event in iter_bench_events(raw):
        if event.get("run_id") != record["run_id"] or event.get("event") != "configured":
            continue
        if prefix_pid != record["pid"] or prefix_tid != record["tid"]:
            raise InvalidRecord("configured event process/thread mismatch")
        if event.get("status") == "fail":
            continue
        for key in ("framework_name", "framework_version", "reported_api"):
            if key in event and (type(event[key]) is not type(record["device"][key])
                                 or event[key] != record["device"][key]):
                raise InvalidRecord("configured event disagrees with device." + key)


def _side(record, raw):
    report = {"raw_log_sha256": hashlib.sha256(raw).hexdigest(), "install_observations": [],
              "device_authentication": "not_verified", "performance_gate": "not_evaluated"}
    if report["raw_log_sha256"] != record["raw_log_sha256"]:
        return {**report, "result": "invalid", "reason": "raw log SHA-256 mismatch",
                "collector_declaration": record}
    try:
        report = verify(raw, record["run_id"], record["pair_id"], record["session_id"], record["side"],
                        record["pid"], record["tid"], record["backend"])
        _cross_check_framework(record, raw)
    except (InvalidRecord, UnicodeError, RecursionError) as exc:
        report = {**report, **getattr(exc, "partial_report", {}),
                  "result": "invalid", "reason": str(exc)}
    report["collector_declaration"] = record
    return report


def _comparison(sides):
    result = {}
    for clock in ("wall", "cpu"):
        # Do not compare failed or partial measurements, even if both retained endpoints.
        a, b = (sides[side][clock]["elapsed_ns"] for side in ("A", "B"))
        row = {"A_elapsed_ns": a, "B_elapsed_ns": b, "B_minus_A_ns": b - a,
               "B_over_A": None}
        if a > 0 and b > 0:
            ratio = Fraction(b, a)
            row["B_over_A"] = {"numerator": str(ratio.numerator), "denominator": str(ratio.denominator)}
        result[clock] = row
    return result


def verify_pair(one, two, one_raw, two_raw, input_errors=None):
    """Check both available inputs even when one side fails; declarations are not authentication."""
    report = {"performance_gate": "not_evaluated", "device_authentication": "not_verified",
              "provenance": PROVENANCE, "sides": {}, "inputs": {},
              "scope": "two single-install wall/CPU envelopes; no stability, confidence interval or performance qualification"}
    errors = list(input_errors or [])
    records, side_reports = [], []
    for index, record, raw in (("one", one, one_raw), ("two", two, two_raw)):
        item = {"raw_log_sha256": hashlib.sha256(raw).hexdigest() if isinstance(raw, bytes) else None}
        report["inputs"][index] = item
        try:
            if record is None:
                raise IncompleteRecord("manifest unavailable")
            validate_manifest(record)
        except (InvalidRecord, IncompleteRecord) as exc:
            status = "incomplete" if isinstance(exc, IncompleteRecord) else "invalid"
            item.update(result=status, reason=str(exc))
            errors.append({"input": index + ".manifest", "result": status, "reason": str(exc)})
            continue
        records.append(record)
        if raw is None:
            side_report = {"result": "incomplete", "reason": "raw log unavailable",
                           "collector_declaration": record, "install_observations": []}
        elif not isinstance(raw, bytes):
            side_report = {"result": "invalid", "reason": "raw log must be bytes",
                           "collector_declaration": record, "install_observations": []}
        else:
            side_report = _side(record, raw)
        item["single_side_report"] = side_report
        side_reports.append(side_report)

    pair_errors = []
    if len(records) == 2:
        left, right = records
        report.update({"pair_id": left["pair_id"], "pair_order": left["pair_order"],
                       "workload": "B07", "control": "A2", "backend": left["backend"]})
        for key in ("pair_id", "pair_order", "backend", "control", "workload", "warmup", "samples",
                    "iterations", "measurement_kind", "artifacts", "device", "host_uid", "host_clock_token"):
            if type(left[key]) is not type(right[key]) or left[key] != right[key]:
                pair_errors.append("pair mismatch: " + key)
        if len({left["pair_id"], right["pair_id"], left["run_id"], right["run_id"],
                left["session_id"], right["session_id"]}) != 5:
            pair_errors.append("pair and both run/session IDs must be independent")
        if (left["pid"], left["process_start_ticks"]) == (right["pid"], right["process_start_ticks"]):
            pair_errors.append("paired records identify the same process")
        if {record["side"] for record in records} != {"A", "B"}:
            pair_errors.append("pair requires exactly one A and one B side")
        else:
            ordered = {record["side"]: record for record in records}
            report["sides"] = {record["side"]: side for record, side in zip(records, side_reports)}
            first, second = (ordered[side] for side in left["pair_order"])
            if first["process_start_ticks"] >= second["process_start_ticks"]:
                pair_errors.append("process start ticks contradict pair order")
            if first["terminal_monotonic_ns"] >= second["launch_monotonic_ns"]:
                pair_errors.append("collector monotonic times contradict pair order")
            first_report, second_report = (report["sides"][side] for side in left["pair_order"])
            if ("wall" in first_report and "wall" in second_report
                    and first_report["wall"]["end_ns"] >= second_report["wall"]["start_ns"]):
                pair_errors.append("raw device wall intervals contradict pair order")
    statuses = {side["result"] for side in side_reports} | {error["result"] for error in errors}
    if pair_errors or "invalid" in statuses:
        status, reason = "invalid", "; ".join(pair_errors) if pair_errors else "at least one input is invalid"
    elif "incomplete" in statuses or len(records) != 2:
        status, reason = "incomplete", "at least one input is incomplete or reported failure"
    elif "unresolved" in statuses:
        status, reason = "unresolved", "at least one side has a zero interval"
    elif statuses == {"install_record_consistent"}:
        status, reason = "install_pair_consistent", "both raw logs and collector declarations are consistent"
    else:
        status, reason = "invalid", "unexpected single-side result"
    report.update(result=status, reason=reason)
    if errors:
        report["input_errors"] = errors
    if pair_errors:
        report["pair_errors"] = pair_errors
    if status in ("install_pair_consistent", "unresolved"):
        report["descriptive_comparison"] = _comparison(report["sides"])
    return report


def _read(path, limit):
    with path.open("rb") as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise InvalidRecord("input exceeds limit: " + str(path))
    return data


def _json(raw):
    def invalid_constant(value):
        raise InvalidRecord("non-standard JSON number: " + value)
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields, parse_constant=invalid_constant)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest_one", type=Path)
    parser.add_argument("manifest_two", type=Path)
    parser.add_argument("--log-one", type=Path, required=True)
    parser.add_argument("--log-two", type=Path, required=True)
    args = parser.parse_args()
    manifest_hashes, errors, inputs = {}, [], {}
    for index, manifest, log_path in (("one", args.manifest_one, args.log_one),
                                      ("two", args.manifest_two, args.log_two)):
        record, raw = None, None
        try:
            data = _read(manifest, 256 * 1024)
            manifest_hashes[index] = hashlib.sha256(data).hexdigest()
            record = _json(data)
            if record is None:
                raise InvalidRecord("manifest must be an object")
        except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError, RecursionError) as exc:
            errors.append({"input": index + ".manifest", "result": "incomplete" if isinstance(exc, FileNotFoundError) else "invalid",
                           "reason": str(exc)})
        try:
            raw = _read(log_path, 16 * 1024 * 1024)
        except (InvalidRecord, OSError) as exc:
            errors.append({"input": index + ".log", "result": "incomplete" if isinstance(exc, FileNotFoundError) else "invalid",
                           "reason": str(exc)})
        inputs[index] = record, raw
    result = verify_pair(inputs["one"][0], inputs["two"][0], inputs["one"][1], inputs["two"][1], errors)
    result["manifest_sha256"] = manifest_hashes
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"install_pair_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    raise SystemExit(main())
