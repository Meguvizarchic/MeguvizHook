#!/usr/bin/env python3
"""Cross-check two saved B10 collections offline; never authenticate a device or evaluate a budget."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from build_b07_pair_inputs import json_bytes, parse_capture, read_input
from verify_aa_pilot import IncompleteRecord, InvalidRecord, identifier, iter_bench_events, number
from verify_b10_resources import verify


MAX_JSON, MAX_RAW, MAX_NS, INT_MAX = 2 * 1024 * 1024, 16 * 1024 * 1024, 2**63 - 1, 2**31 - 1
HOST = "io.github.jichuo1.meguviz.hook.benchmarkhost"
MODULES = {"101": "io.github.jichuo1.meguviz.hook.benchmark101",
           "102": "io.github.jichuo1.meguviz.hook.benchmark102"}
PARAMETERS = {"warmup": 0, "samples": 1, "iterations": 1, "measurement_mode": "unobserved"}
PLAN = {"schema_version", "record_type", "status", "created_utc", "evidence_root", "plan_path",
        "backend", "workload", "control", "side", "run_id", "pair_id", "session_id", "host_clock_token",
        "serial_sha256", "expected_framework", "parameters", "measurement_kind", "android_user", "artifacts",
        "artifact_audit", "workload_sha256", "feature_manifest_sha256", "pins", "adb", "timeout_seconds",
        "performance_gate", "device_authentication"}
CAPTURE = {"schema_version", "record_type", "status", "plan_sha256", "workload", "control", "backend",
           "parameters", "measurement_kind", "run_id", "pair_id", "session_id", "side", "host_clock_token",
           "serial_sha256", "process", "controller_tid", "device", "artifacts", "artifact_audit",
           "workload_sha256", "feature_manifest_sha256", "fixed_input_manifest_sha256", "launch_monotonic_ns",
           "terminal_monotonic_ns", "raw_log_sha256", "raw_log_bytes", "verification_sha256",
           "performance_gate", "device_authentication"}
RESULT = {"schema_version", "record_type", "result", "reason", "plan_sha256", "workload", "control", "backend",
          "verification", "owned", "stop_attempted", "stop_confirmed", "launch_monotonic_ns",
          "terminal_monotonic_ns", "performance_gate", "device_authentication"}
DEVICE = {"device_id_sha256", "build_fingerprint_sha256", "boot_id", "android_api", "abi", "android_user",
          "framework_name", "framework_version", "reported_api"}
PROCESS = {"pid", "uid", "start_ticks", "cmdline"}
PIN = {"path", "bytes", "sha256"}
ARTIFACT = PIN | {"package", "certificate_sha256"}
IDENTITIES = ("run_id", "pair_id", "session_id", "host_clock_token")
HASH = re.compile(r"[0-9a-f]{64}\Z")
PROVENANCE = ("saved plan bytes, collector declarations, owned-stop reports and raw logs cross-checked; "
              "a shared host_clock_token is a collector declaration, not authentication of a host clock; "
              "device routing, loaded code, installed artifacts and historical process ownership are not independently authenticated")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def same(actual, expected, where):
    # Canonical JSON deliberately distinguishes true, 1 and 1.0, including inside nested declarations.
    if type(actual) is not type(expected) or canonical(actual) != canonical(expected):
        raise InvalidRecord(where + " disagrees")


def fields(value, required, where):
    if value is None:
        raise IncompleteRecord(where + " unavailable")
    if not isinstance(value, dict):
        raise InvalidRecord(where + " must be an object")
    extra, missing = set(value) - required, required - set(value)
    if extra:
        raise InvalidRecord(where + " has unknown fields")
    if missing:
        raise IncompleteRecord(where + " missing " + ", ".join(sorted(missing)))


def label(value, where, limit=256):
    if (not isinstance(value, str) or not value or value != value.strip() or len(value) > limit
            or value.lower() == "null" or any(ord(char) < 32 for char in value)):
        raise InvalidRecord("invalid " + where)


def hash_value(value, where):
    if not isinstance(value, str) or not HASH.fullmatch(value):
        raise InvalidRecord(where + " must be lowercase SHA-256")


def pin(value, where, external=False):
    fields(value, PIN, where)
    label(value["path"], where + ".path", 4096)
    path = value["path"].replace("\\", "/")
    if not external and (path.startswith("/") or re.match(r"^[A-Za-z]:", path)
                         or any(part in ("", ".", "..") for part in path.split("/"))):
        raise InvalidRecord(where + " must declare a normalized relative path")
    number(value["bytes"], where + ".bytes", 0, 64 * 1024 * 1024)
    hash_value(value["sha256"], where + ".sha256")


def fixed_input_digest(plan):
    return digest(canonical({"pins": sorted(plan["pins"], key=lambda item: item["path"]),
                             "adb_sha256": plan["adb"]["sha256"]}))


def validate_plan(raw):
    if raw is None:
        raise IncompleteRecord("plan bytes unavailable")
    if not isinstance(raw, bytes) or len(raw) > MAX_JSON:
        raise InvalidRecord("plan must be original bytes within 2 MiB")
    plan = parse_capture(raw)
    fields(plan, PLAN, "plan")
    for key, value in (("schema_version", 1), ("record_type", "b10_resource_plan"),
                       ("status", "prepared_not_executed"), ("workload", "B10"),
                       ("parameters", PARAMETERS), ("measurement_kind", "process_resource_snapshots"),
                       ("android_user", 0), ("timeout_seconds", 60),
                       ("performance_gate", "not_evaluated"), ("device_authentication", "not_verified")):
        same(plan[key], value, "plan." + key)
    if plan["backend"] not in MODULES or plan["control"] not in ("A0", "A2") or plan["side"] not in ("A", "B"):
        raise InvalidRecord("invalid selected backend, control or side")
    for key in IDENTITIES:
        identifier(plan[key], "plan." + key)
    if len({plan[key] for key in IDENTITIES}) != 4:
        raise InvalidRecord("planned run/pair/session/clock IDs must differ")
    for key in ("created_utc", "evidence_root", "plan_path"):
        label(plan[key], "plan." + key, 4096)
    for key in ("serial_sha256", "workload_sha256", "feature_manifest_sha256"):
        hash_value(plan[key], "plan." + key)
    fields(plan["expected_framework"], {"framework_name", "framework_version", "reported_api"}, "expected framework")
    for key in ("framework_name", "framework_version"):
        label(plan["expected_framework"][key], key)
    same(plan["expected_framework"]["reported_api"], int(plan["backend"]), "framework API")
    pins = plan["pins"]
    if not isinstance(pins, list) or not pins:
        raise InvalidRecord("plan pins missing")
    names = set()
    for item in pins:
        pin(item, "input pin")
        if item["path"] in names:
            raise InvalidRecord("duplicate input pin path")
        names.add(item["path"])
    pin(plan["adb"], "ADB pin", external=True)
    number(plan["adb"]["bytes"], "ADB bytes", 1, 64 * 1024 * 1024)
    pin(plan["artifact_audit"], "artifact audit pin")
    if not any(canonical(item) == canonical(plan["artifact_audit"]) for item in pins):
        raise InvalidRecord("artifact audit is absent from input pins")
    for key in ("workload_sha256", "feature_manifest_sha256"):
        if not any(item["sha256"] == plan[key] for item in pins):
            raise InvalidRecord(key + " is absent from input pins")
    fields(plan["artifacts"], {"host", "module"}, "artifacts")
    for key, package in (("host", HOST), ("module", MODULES[plan["backend"]])):
        artifact = plan["artifacts"][key]
        fields(artifact, ARTIFACT, key + " artifact")
        plain = {name: artifact[name] for name in PIN}
        pin(plain, key + " APK pin")
        number(artifact["bytes"], key + " APK bytes", 1, 64 * 1024 * 1024)
        same(artifact["package"], package, key + " package")
        hash_value(artifact["certificate_sha256"], key + " certificate")
        if not any(canonical(item) == canonical(plain) for item in pins):
            raise InvalidRecord(key + " APK is absent from input pins")
    same(plan["artifacts"]["host"]["certificate_sha256"], plan["artifacts"]["module"]["certificate_sha256"],
         "test package certificates")
    return plan


def validate_capture(capture, plan, plan_raw):
    fields(capture, CAPTURE, "capture")
    for key, value in (("schema_version", 1), ("record_type", "b10_resource_capture"),
                       ("status", "collected_and_owned_stop_confirmed"),
                       ("plan_sha256", digest(plan_raw)), ("fixed_input_manifest_sha256", fixed_input_digest(plan))):
        same(capture[key], value, "capture." + key)
    for key in ("workload", "control", "backend", "parameters", "measurement_kind", *IDENTITIES, "side",
                "serial_sha256", "artifacts", "artifact_audit", "workload_sha256", "feature_manifest_sha256",
                "performance_gate", "device_authentication"):
        same(capture[key], plan[key], "capture." + key)
    process = capture["process"]
    fields(process, PROCESS, "process")
    for key in ("pid", "uid"):
        number(process[key], "process." + key, 1, INT_MAX)
    number(process["start_ticks"], "process.start_ticks", 1, MAX_NS)
    same(process["cmdline"], HOST, "process.cmdline")
    number(capture["controller_tid"], "controller_tid", 1, INT_MAX)
    fields(capture["device"], DEVICE, "device")
    device = capture["device"]
    for key in ("device_id_sha256", "build_fingerprint_sha256"):
        hash_value(device[key], "device." + key)
    same(device["device_id_sha256"], plan["serial_sha256"], "device selection")
    identifier(device["boot_id"], "device.boot_id")
    label(device["abi"], "device.abi")
    number(device["android_api"], "device.android_api", 1, 10000)
    same(device["android_user"], 0, "device.android_user")
    for key, value in plan["expected_framework"].items():
        same(device[key], value, "device." + key)
    for key in ("launch_monotonic_ns", "terminal_monotonic_ns"):
        number(capture[key], key, 0, MAX_NS)
    if capture["terminal_monotonic_ns"] <= capture["launch_monotonic_ns"]:
        raise InvalidRecord("terminal must follow launch")
    number(capture["raw_log_bytes"], "raw_log_bytes", 1, MAX_RAW)
    for key in ("raw_log_sha256", "verification_sha256"):
        hash_value(capture[key], key)


def _side(bundle):
    report = {"result": "incomplete", "device_authentication": "not_verified", "performance_gate": "not_evaluated"}
    plan = capture = None
    try:
        if not isinstance(bundle, dict):
            raise IncompleteRecord("side input bundle unavailable")
        plan_raw, raw = bundle.get("plan_bytes"), bundle.get("raw")
        report["source_sha256"] = {"plan": digest(plan_raw) if isinstance(plan_raw, bytes) else None,
                                    "raw": digest(raw) if isinstance(raw, bytes) else None}
        plan = validate_plan(plan_raw)
        capture = bundle.get("capture")
        validate_capture(capture, plan, plan_raw)
        report.update(side=capture["side"], run_id=capture["run_id"], session_id=capture["session_id"])
        if raw is None:
            raise IncompleteRecord("raw log unavailable")
        if not isinstance(raw, bytes) or len(raw) > MAX_RAW:
            raise InvalidRecord("raw log must be bytes within 16 MiB")
        same(capture["raw_log_sha256"], digest(raw), "raw log SHA-256")
        same(capture["raw_log_bytes"], len(raw), "raw log byte count")
        process = capture["process"]
        try:
            single = verify(raw, plan["run_id"], plan["pair_id"], plan["session_id"], plan["side"],
                            process["pid"], capture["controller_tid"], plan["backend"], plan["control"])
        except (InvalidRecord, UnicodeError, RecursionError) as exc:
            report["single_side_report"] = {**getattr(exc, "partial_report", {}), "result": "invalid", "reason": str(exc)}
            raise
        report["single_side_report"] = single
        if single["result"] != "resource_record_consistent":
            raise IncompleteRecord("raw resource transaction is not complete: " + single["result"])
        selected = [event for _, _, _, event in iter_bench_events(raw) if event.get("run_id") == plan["run_id"]]
        configured = next(event for event in selected if event.get("event") == "configured")
        for key, value in plan["expected_framework"].items():
            same(configured[key], value, "configured." + key)
        report["device_resource_window_ns"] = {"start": next(event["resource_start_ns"] for event in selected
                                                                if event["event"] == "resource_before"),
                                                "end": next(event["resource_end_ns"] for event in selected
                                                              if event["event"] == "resource_closed")}
        collection = bundle.get("result")
        fields(collection, RESULT, "collection result")
        for key, value in (("schema_version", 1), ("record_type", "b10_resource_collection_result"),
                           ("result", "resource_record_consistent"), ("stop_attempted", True), ("stop_confirmed", True)):
            same(collection[key], value, "collection." + key)
        label(collection["reason"], "collection.reason", 4096)
        for key in ("plan_sha256", "workload", "control", "backend", "launch_monotonic_ns", "terminal_monotonic_ns",
                    "performance_gate", "device_authentication"):
            same(collection[key], capture[key], "collection." + key)
        same(collection["owned"], process, "collection.owned")
        same(collection["verification"], single, "saved verification versus raw recomputation")
        same(capture["verification_sha256"], digest(json_bytes(collection["verification"])), "saved verification SHA-256")
        stop = bundle.get("owned_stop")
        fields(stop, {"confirmed", "owned", "scope"}, "owned stop")
        same(stop["confirmed"], True, "owned stop confirmation")
        same(stop["owned"], process, "owned stop identity")
        same(stop["scope"], "fixed benchmark host only", "owned stop scope")
        report.update(result="resource_record_consistent", reason="saved collection, raw transaction and owned stop agree")
    except (InvalidRecord, IncompleteRecord, UnicodeError, ValueError, TypeError, KeyError, RecursionError, StopIteration) as exc:
        report.update(result="incomplete" if isinstance(exc, IncompleteRecord) else "invalid", reason=str(exc))
    return report, plan, capture


def verify_pair(one, two, pair_order, input_errors=None):
    """Bundles contain plan_bytes, capture, result, owned_stop and raw; no path in a plan is opened."""
    report = {"schema_version": 1, "record_type": "b10_resource_pair_report", "workload": "B10",
              "performance_gate": "not_evaluated", "device_authentication": "not_verified",
              "provenance": PROVENANCE, "inputs": {}, "sides": {},
              "formal_metrics": {key: None for key in ("additional_rss_bytes", "additional_apk_bytes",
                                                       "additional_thread_count", "additional_fd_count", "retired_resident_bytes")}}
    errors = list(input_errors or [])
    records = []
    for name, bundle in (("one", one), ("two", two)):
        side_report, plan, capture = _side(bundle)
        report["inputs"][name] = side_report
        if plan is not None and isinstance(capture, dict) and side_report.get("side") in ("A", "B"):
            records.append((plan, capture, side_report))
    pair_errors = []
    if pair_order not in ("AB", "BA"):
        pair_errors.append("pair_order must be AB or BA")
    else:
        report["pair_order"] = pair_order
    if len(records) == 2:
        left, right = records
        for key in ("pair_id", "host_clock_token", "backend", "control", "workload", "parameters", "measurement_kind",
                    "serial_sha256", "expected_framework", "android_user", "artifacts", "artifact_audit",
                    "workload_sha256", "feature_manifest_sha256"):
            if canonical(left[0][key]) != canonical(right[0][key]):
                pair_errors.append("pair mismatch: " + key)
        if fixed_input_digest(left[0]) != fixed_input_digest(right[0]):
            pair_errors.append("pair mismatch: fixed input manifest")
        if canonical(left[1]["device"]) != canonical(right[1]["device"]):
            pair_errors.append("pair mismatch: device or framework")
        if left[1]["process"]["uid"] != right[1]["process"]["uid"]:
            pair_errors.append("pair mismatch: host UID")
        ids = [left[0][key] for key in ("pair_id", "host_clock_token", "run_id", "session_id")]
        ids += [right[0][key] for key in ("run_id", "session_id")]
        if len(set(ids)) != 6:
            pair_errors.append("both run/session IDs must differ from each other and pair/clock IDs")
        if {item[1]["side"] for item in records} != {"A", "B"}:
            pair_errors.append("pair requires exactly one A and one B")
        else:
            ordered = {item[1]["side"]: item for item in records}
            report["sides"] = {side: item[2] for side, item in ordered.items()}
            if pair_order in ("AB", "BA"):
                first, second = (ordered[side] for side in pair_order)
                if first[1]["process"]["start_ticks"] >= second[1]["process"]["start_ticks"]:
                    pair_errors.append("process start ticks contradict declared order")
                if first[1]["terminal_monotonic_ns"] >= second[1]["launch_monotonic_ns"]:
                    pair_errors.append("collector monotonic intervals overlap or contradict declared order")
                a, b = first[2].get("device_resource_window_ns"), second[2].get("device_resource_window_ns")
                if a is not None and b is not None and a["end"] >= b["start"]:
                    pair_errors.append("raw device resource windows overlap or contradict declared order")
        if all(left[1]["process"][key] == right[1]["process"][key] for key in ("pid", "start_ticks")):
            pair_errors.append("pair identifies the same process")
        report.update(pair_id=left[0]["pair_id"], host_clock_token=left[0]["host_clock_token"],
                      backend=left[0]["backend"], control=left[0]["control"])
    statuses = {item["result"] for item in report["inputs"].values()} | {item["result"] for item in errors}
    if pair_errors or "invalid" in statuses:
        status, reason = "invalid", "; ".join(pair_errors) if pair_errors else "at least one saved input is invalid"
    elif len(records) != 2 or statuses != {"resource_record_consistent"}:
        status, reason = "incomplete", "at least one collection is unavailable, incomplete or did not confirm success"
    else:
        status, reason = "resource_pair_consistent", "both complete raw collections and declarations agree"
    report.update(result=status, reason=reason)
    if errors:
        report["input_errors"] = errors
    if pair_errors:
        report["pair_errors"] = pair_errors
    if status == "resource_pair_consistent":
        a, b = (report["sides"][side]["single_side_report"]["phase_deltas"] for side in ("A", "B"))
        report["descriptive_comparison"] = {phase: {key: {"A": a[phase][key], "B": b[phase][key],
                                                                  "B_minus_A": b[phase][key] - a[phase][key]}
                                                      for key in a[phase]} for phase in a}
        report["comparison_scope"] = "signed arithmetic on two within-process phase deltas; not SDK overhead, retired bytes, statistical stability or budget qualification"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pair-order", choices=("AB", "BA"), required=True)
    for side in ("one", "two"):
        for name in ("plan", "capture", "result", "owned-stop", "log"):
            parser.add_argument("--" + name + "-" + side, type=Path, required=True)
    args = parser.parse_args()
    bundles, errors, sources = {}, [], {}
    keys = {"plan": "plan_bytes", "capture": "capture", "result": "result", "owned_stop": "owned_stop", "log": "raw"}
    for side in ("one", "two"):
        bundle, hashes = {}, {}
        for name, key in keys.items():
            path = getattr(args, name + "_" + side)
            try:
                raw = read_input(path, MAX_RAW if name == "log" else MAX_JSON)
                hashes[name] = digest(raw)
                bundle[key] = raw if name in ("plan", "log") else parse_capture(raw)
            except (OSError, ValueError, TypeError, UnicodeError, RecursionError) as exc:
                bundle[key] = None
                errors.append({"input": side + "." + name, "result": "incomplete" if isinstance(exc, FileNotFoundError)
                               else "invalid", "reason": str(exc)})
        bundles[side], sources[side] = bundle, hashes
    report = verify_pair(bundles["one"], bundles["two"], args.pair_order, errors)
    report["source_sha256"] = sources
    print(json.dumps(report, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"resource_pair_consistent": 0, "incomplete": 2, "invalid": 1}[report["result"]]


if __name__ == "__main__":
    sys.exit(main())
