#!/usr/bin/env python3
"""Check saved same-control loop A/A collections offline, without a performance qualification."""

import argparse
import json
import math
import statistics
import sys
from fractions import Fraction
from pathlib import Path

from build_aa_pilot_session import build_session
from build_b07_pair_inputs import json_bytes, parse_capture, read_input
from verify_aa_pilot import (FORMAL_METRICS, LEGACY_KIND, OBSERVED_KIND, WORKLOAD_CALLS, IncompleteRecord,
                             InvalidRecord, identifier, iter_bench_events, number, verify_pilot)
from verify_b10_pair import (ARTIFACT, CAPTURE as B10_CAPTURE, DEVICE, HOST, IDENTITIES, INT_MAX,
                             MAX_JSON, MAX_NS, MAX_RAW, MODULES, PIN, PLAN as B10_PLAN, PROCESS,
                             RESULT as B10_RESULT, canonical, digest, fields, fixed_input_digest,
                             hash_value, label, pin, same)


PLAN = B10_PLAN | {"pair_order"}
CAPTURE = B10_CAPTURE | {"pair_order", "session_sha256", "session_manifest_sha256"}
RESULT = B10_RESULT | {"collection_status"}
MANIFEST = {"schema_version", "data_origin", "backend", "process_start_token", "pair_order", "artifacts", "device"}
SESSION_DEVICE = {"device_id_sha256", "build_fingerprint_sha256", "android_api", "abi",
                  "framework_name", "framework_version", "reported_api"}
BOUND = ("workload", "control", "backend", "pair_order", "parameters", "measurement_kind", *IDENTITIES,
         "side", "serial_sha256", "artifacts", "artifact_audit", "workload_sha256", "feature_manifest_sha256",
         "performance_gate", "device_authentication")
PROVENANCE = ("saved plans, collector declarations, sessions, raw logs and owned-stop reports cross-checked; "
              "device, scope, loaded code, framework and historical process identity are not independently authenticated; "
              "a shared host_clock_token is a collector declaration, not authentication of a clock")


def _finite(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise InvalidRecord("selected loop event contains a non-finite JSON number")
    if isinstance(value, dict):
        for child in value.values():
            _finite(child)
    elif isinstance(value, list):
        for child in value:
            _finite(child)


def validate_plan(raw):
    if raw is None:
        raise IncompleteRecord("plan bytes unavailable")
    if not isinstance(raw, bytes) or len(raw) > MAX_JSON:
        raise InvalidRecord("plan must be original bytes within 2 MiB")
    plan = parse_capture(raw)
    fields(plan, PLAN, "plan")
    for key, value in (("schema_version", 1), ("record_type", "loop_pilot_plan"),
                       ("status", "prepared_not_executed"), ("android_user", 0), ("timeout_seconds", 120),
                       ("performance_gate", "not_evaluated"), ("device_authentication", "not_verified")):
        same(plan[key], value, "plan." + key)
    if (plan["backend"] not in MODULES or plan["workload"] not in WORKLOAD_CALLS
            or plan["control"] not in ("A0", "A2") or plan["side"] not in ("A", "B")
            or plan["pair_order"] not in ("AB", "BA")):
        raise InvalidRecord("invalid loop workload, backend, control, side or order")
    parameters = plan["parameters"]
    fields(parameters, {"warmup", "samples", "iterations", "measurement_mode"}, "parameters")
    for key, low, high in (("warmup", 0, 1000000), ("samples", 1, 200), ("iterations", 1, 1000000)):
        number(parameters[key], "parameters." + key, low, high)
    if (parameters["warmup"] + parameters["samples"] * parameters["iterations"]) * WORKLOAD_CALLS[plan["workload"]] > 10000000:
        raise InvalidRecord("loop exceeds host total iteration limit")
    if parameters["measurement_mode"] not in ("observed", "unobserved"):
        raise InvalidRecord("invalid loop measurement mode")
    same(plan["measurement_kind"], OBSERVED_KIND if parameters["measurement_mode"] == "observed" else LEGACY_KIND,
         "measurement kind")
    for key in IDENTITIES:
        identifier(plan[key], "plan." + key)
    if len({plan[key] for key in IDENTITIES}) != 4:
        raise InvalidRecord("single-process run/pair/session/clock IDs must differ")
    for key in ("created_utc", "evidence_root", "plan_path"):
        label(plan[key], key, 4096)
    for key in ("serial_sha256", "workload_sha256", "feature_manifest_sha256"):
        hash_value(plan[key], key)
    fields(plan["expected_framework"], {"framework_name", "framework_version", "reported_api"}, "framework")
    for key in ("framework_name", "framework_version"):
        label(plan["expected_framework"][key], key)
    same(plan["expected_framework"]["reported_api"], int(plan["backend"]), "framework API")
    pins = plan["pins"]
    if not isinstance(pins, list) or not pins:
        raise InvalidRecord("input pins missing")
    names = set()
    for item in pins:
        pin(item, "input pin")
        if item["path"] in names:
            raise InvalidRecord("duplicate input pin path")
        names.add(item["path"])
    pin(plan["adb"], "ADB pin", external=True)
    number(plan["adb"]["bytes"], "ADB bytes", 1, 64 * 1024 * 1024)
    pin(plan["artifact_audit"], "artifact audit")
    if not any(canonical(item) == canonical(plan["artifact_audit"]) for item in pins):
        raise InvalidRecord("artifact audit is not pinned")
    for key in ("workload_sha256", "feature_manifest_sha256"):
        if not any(item["sha256"] == plan[key] for item in pins):
            raise InvalidRecord(key + " is not pinned")
    fields(plan["artifacts"], {"host", "module"}, "artifacts")
    for key, package in (("host", HOST), ("module", MODULES[plan["backend"]])):
        item = plan["artifacts"][key]
        fields(item, ARTIFACT, key + " artifact")
        selected = {name: item[name] for name in PIN}
        pin(selected, key + " APK")
        number(item["bytes"], key + " APK bytes", 1, 64 * 1024 * 1024)
        same(item["package"], package, key + " package")
        hash_value(item["certificate_sha256"], key + " certificate")
        if not any(canonical(value) == canonical(selected) for value in pins):
            raise InvalidRecord(key + " APK is not pinned")
    same(plan["artifacts"]["host"]["certificate_sha256"], plan["artifacts"]["module"]["certificate_sha256"], "certificates")
    return plan


def validate_capture(capture, plan, plan_raw):
    fields(capture, CAPTURE, "capture")
    for key, value in (("schema_version", 1), ("record_type", "loop_pilot_capture"),
                       ("status", "collected_and_owned_stop_confirmed"), ("plan_sha256", digest(plan_raw)),
                       ("fixed_input_manifest_sha256", fixed_input_digest(plan))):
        same(capture[key], value, "capture." + key)
    for key in BOUND:
        same(capture[key], plan[key], "capture." + key)
    fields(capture["process"], PROCESS, "process")
    for key in ("pid", "uid"):
        number(capture["process"][key], "process." + key, 1, INT_MAX)
    number(capture["process"]["start_ticks"], "process.start_ticks", 1, MAX_NS)
    same(capture["process"]["cmdline"], HOST, "process.cmdline")
    number(capture["controller_tid"], "controller_tid", 1, INT_MAX)
    fields(capture["device"], DEVICE, "device")
    device = capture["device"]
    for key in ("device_id_sha256", "build_fingerprint_sha256"):
        hash_value(device[key], key)
    same(device["device_id_sha256"], plan["serial_sha256"], "selected device")
    identifier(device["boot_id"], "boot_id")
    label(device["abi"], "abi")
    number(device["android_api"], "android_api", 1, 10000)
    same(device["android_user"], 0, "Android user")
    for key, value in plan["expected_framework"].items():
        same(device[key], value, "device." + key)
    for key in ("launch_monotonic_ns", "terminal_monotonic_ns"):
        number(capture[key], key, 0, MAX_NS)
    if capture["terminal_monotonic_ns"] <= capture["launch_monotonic_ns"]:
        raise InvalidRecord("terminal must follow launch")
    number(capture["raw_log_bytes"], "raw_log_bytes", 1, MAX_RAW)
    for key in ("raw_log_sha256", "verification_sha256", "session_sha256", "session_manifest_sha256"):
        hash_value(capture[key], key)


def session_manifest(capture):
    """Derive only collector declarations; the original builder leaves preflight not_verified."""
    process, device = capture["process"], capture["device"]
    return {"schema_version": 1, "data_origin": "device_measurement", "backend": capture["backend"],
            "process_start_token": device["boot_id"] + ":" + str(process["pid"]) + ":" + str(process["start_ticks"]),
            "pair_order": capture["pair_order"],
            "artifacts": {"host_apk_sha256": capture["artifacts"]["host"]["sha256"],
                          "module_apk_sha256": capture["artifacts"]["module"]["sha256"],
                          "workload_sha256": capture["workload_sha256"],
                          "feature_manifest_sha256": capture["feature_manifest_sha256"]},
            "device": {key: device[key] for key in SESSION_DEVICE}}


def single_verification(session, raw):
    intervals = [sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"] for sample in session["samples"]]
    zeros = intervals.count(0)
    if zeros == len(intervals):
        result, reason = "unresolved", "all sampled CPU intervals are zero; raw endpoints retained"
    elif session["config"]["measurement_kind"] == OBSERVED_KIND:
        result, reason = "observed_session_consistent", "observed loop session fields and raw events consistent; counters affect the CPU envelope"
    else:
        result, reason = "reported_only", "loop session fields and raw events consistent; measurement hook remains not_verified"
    return {"schema_version": 1, "record_type": "loop_pilot_single_verification", "result": result, "reason": reason,
            "raw_log_sha256": digest(raw), "run_id": session["run_id"], "preflight": "not_verified",
            "timing": {"sample_count": len(intervals), "zero_intervals": zeros,
                       "positive_intervals": len(intervals) - zeros, "intervals_ns": intervals},
            "performance_gate": "not_evaluated", "device_authentication": "not_verified"}


def _summary(session):
    intervals = [sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"] for sample in session["samples"]]
    values = [Fraction(value, sample["iterations"]) for value, sample in zip(intervals, session["samples"])]
    return {"sample_cpu_elapsed_ns": intervals, "sample_ns_per_call": [float(value) for value in values],
            "median_ns_per_call": float(statistics.median(values)), "zero_sample_count": intervals.count(0)}


def _side(bundle):
    report = {"result": "incomplete", "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    plan = capture = None
    try:
        if not isinstance(bundle, dict):
            raise IncompleteRecord("side bundle unavailable")
        plan_raw, raw = bundle.get("plan_bytes"), bundle.get("raw")
        report["source_sha256"] = {"plan": digest(plan_raw) if isinstance(plan_raw, bytes) else None,
                                    "raw": digest(raw) if isinstance(raw, bytes) else None}
        plan = validate_plan(plan_raw)
        capture = bundle.get("capture")
        validate_capture(capture, plan, plan_raw)
        report.update(side=plan["side"], run_id=plan["run_id"], session_id=plan["session_id"])
        if raw is None:
            raise IncompleteRecord("raw log unavailable")
        if not isinstance(raw, bytes) or len(raw) > MAX_RAW:
            raise InvalidRecord("raw log must be bytes within 16 MiB")
        same(capture["raw_log_sha256"], digest(raw), "raw SHA-256")
        same(capture["raw_log_bytes"], len(raw), "raw byte count")
        for _, _, _, event in iter_bench_events(raw):
            if event.get("run_id") == plan["run_id"]:
                _finite(event)
                if "failure_case" in event:
                    raise InvalidRecord("selected loop event contains an inapplicable failure case")
                for key in ("outcome_unknown", "timed_out"):
                    if key in event:
                        same(event[key], False, "raw " + key)
        manifest = bundle.get("session_manifest")
        fields(manifest, MANIFEST, "session manifest")
        same(manifest, session_manifest(capture), "session manifest versus collector identity")
        for name, value in (("session_manifest", manifest), ("session", bundle.get("session"))):
            if value is None:
                raise IncompleteRecord(name + " unavailable")
            same(capture[name + "_sha256"], digest(json_bytes(value)), name + " canonical SHA-256")
            source = bundle.get(name + "_bytes")
            if source is not None:
                if not isinstance(source, bytes) or len(source) > MAX_JSON:
                    raise InvalidRecord(name + " original bytes invalid")
                same(digest(source), capture[name + "_sha256"], name + " original SHA-256")
                same(parse_capture(source), value, name + " bytes versus parsed declaration")
        rebuilt = build_session(raw, plan["run_id"], manifest)
        report["session"] = rebuilt
        same(bundle["session"], rebuilt, "saved session versus raw reconstruction")
        for key in ("pair_id", "run_id", "session_id", "side", "workload", "control", "backend"):
            same(rebuilt[key], capture[key], "session." + key)
        same(rebuilt["process"]["pid"], capture["process"]["pid"], "session PID")
        same(rebuilt["thread_id"], capture["controller_tid"], "session controller TID")
        same(rebuilt["config"], {**{key: plan["parameters"][key] for key in ("warmup", "samples", "iterations")},
                                  "measurement_kind": plan["measurement_kind"], "pair_order": plan["pair_order"]}, "session config")
        single = single_verification(rebuilt, raw)
        report["single_side_report"] = single
        report["summary"] = _summary(rebuilt)
        collection = bundle.get("result")
        fields(collection, RESULT, "collection result")
        for key, value in (("schema_version", 1), ("record_type", "loop_pilot_collection_result"),
                           ("collection_status", "collected"), ("result", single["result"]),
                           ("stop_attempted", True), ("stop_confirmed", True)):
            same(collection[key], value, "collection." + key)
        label(collection["reason"], "collection.reason", 4096)
        for key in ("plan_sha256", "workload", "control", "backend", "launch_monotonic_ns", "terminal_monotonic_ns",
                    "performance_gate", "device_authentication"):
            same(collection[key], capture[key], "collection." + key)
        same(collection["owned"], capture["process"], "collection ownership")
        same(collection["verification"], single, "saved single verification versus reconstruction")
        same(capture["verification_sha256"], digest(json_bytes(single)), "verification SHA-256")
        stop = bundle.get("owned_stop")
        fields(stop, {"confirmed", "owned", "scope"}, "owned stop")
        same(stop["confirmed"], True, "stop confirmation")
        same(stop["owned"], capture["process"], "stop ownership")
        same(stop["scope"], "fixed benchmark host only", "stop scope")
        report.update(result=single["result"], collection_status="collected", reason="raw session, collection and owned stop agree")
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError) as exc:
        report.update(result="incomplete" if isinstance(exc, IncompleteRecord) else "invalid",
                      collection_status="failed", reason=str(exc))
    return report, plan, capture


def verify_pair(one, two, pair_order, input_errors=None):
    """Bundles carry original plan/log bytes and capture/result/owned_stop/session/session_manifest JSON."""
    report = {"schema_version": 1, "record_type": "loop_pilot_pair_report", "inputs": {}, "sides": {},
              "provenance": PROVENANCE, "performance_gate": "not_evaluated", "device_authentication": "not_verified",
              "formal_metrics": {key: None for key in FORMAL_METRICS}}
    records, errors, pair_errors = [], list(input_errors or []), []
    for name, bundle in (("one", one), ("two", two)):
        side_report, plan, capture = _side(bundle)
        report["inputs"][name] = side_report
        if side_report.get("side") in ("A", "B"):
            records.append((plan, capture, side_report, bundle))
    if pair_order not in ("AB", "BA"):
        pair_errors.append("pair_order must be AB or BA")
    else:
        report["pair_order"] = pair_order
    if len(records) == 2:
        a, b = records
        for key in ("pair_id", "host_clock_token", "pair_order", "workload", "control", "backend", "parameters",
                    "measurement_kind", "expected_framework", "serial_sha256", "android_user", "artifacts", "artifact_audit",
                    "workload_sha256", "feature_manifest_sha256"):
            if canonical(a[0][key]) != canonical(b[0][key]):
                pair_errors.append("same-control A/A pair mismatch: " + key)
        if a[0]["pair_order"] != pair_order or b[0]["pair_order"] != pair_order:
            pair_errors.append("plan order disagrees with explicit pair order")
        if fixed_input_digest(a[0]) != fixed_input_digest(b[0]):
            pair_errors.append("fixed input manifest differs")
        if canonical(a[1]["device"]) != canonical(b[1]["device"]):
            pair_errors.append("device/boot/user/framework differs")
        if a[1]["process"]["uid"] != b[1]["process"]["uid"]:
            pair_errors.append("host UID differs")
        ids = [a[0][key] for key in ("pair_id", "host_clock_token", "run_id", "session_id")]
        ids += [b[0][key] for key in ("run_id", "session_id")]
        if len(set(ids)) != 6:
            pair_errors.append("both run/session IDs must differ from pair/clock and each other")
        if {item[1]["side"] for item in records} != {"A", "B"}:
            pair_errors.append("pair needs one A and one B")
        else:
            ordered = {item[1]["side"]: item for item in records}
            report["sides"] = {side: item[2] for side, item in ordered.items()}
            if pair_order in ("AB", "BA"):
                first, second = (ordered[side] for side in pair_order)
                if first[1]["process"]["start_ticks"] >= second[1]["process"]["start_ticks"]:
                    pair_errors.append("process start ticks contradict order")
                if first[1]["terminal_monotonic_ns"] >= second[1]["launch_monotonic_ns"]:
                    pair_errors.append("host collection intervals overlap or contradict order")
            # Thread CPU clocks belong to separate threads/processes: never compare their absolute endpoints here.
        if (a[1]["process"]["pid"], a[1]["process"]["start_ticks"]) == (b[1]["process"]["pid"], b[1]["process"]["start_ticks"]):
            pair_errors.append("same process reused")
        report.update(pair_id=a[0]["pair_id"], host_clock_token=a[0]["host_clock_token"], workload=a[0]["workload"],
                      control=a[0]["control"], backend=a[0]["backend"], mode=a[0]["parameters"]["measurement_mode"])
    statuses = {item["result"] for item in report["inputs"].values()} | {item["result"] for item in errors}
    if pair_errors or "invalid" in statuses:
        status, reason = "invalid", "; ".join(pair_errors) if pair_errors else "at least one input is invalid"
    elif len(records) != 2 or "incomplete" in statuses:
        status, reason = "incomplete", "at least one collection or raw session is incomplete"
    elif not all(item[2].get("collection_status") == "collected" for item in records):
        status, reason = "incomplete", "collection or owned stop is not confirmed"
    else:
        try:
            original = verify_pilot(records[0][2]["session"], records[1][2]["session"], records[0][3]["raw"], records[1][3]["raw"])
            report["pilot_verification"] = original
            zero = any(item[2]["summary"]["zero_sample_count"] for item in records)
            status = "unresolved" if zero else original["result"]
            reason = "zero CPU samples retained; ratios unavailable" if zero else "same-control raw A/A records agree; no performance gate evaluated"
            left, right = (report["sides"][side] for side in ("A", "B"))
            medians = []
            for side in (left, right):
                medians.append(statistics.median([Fraction(sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"], sample["iterations"])
                                                 for sample in side["session"]["samples"]]))
            ratio = None if zero or medians[0] == 0 else medians[1] / medians[0]
            report["descriptive_comparison"] = {"A_median_ns_per_call": float(medians[0]), "B_median_ns_per_call": float(medians[1]),
                                                "B_minus_A_ns_per_call": float(medians[1] - medians[0]),
                                                "B_over_A_ratio": None if ratio is None else {"numerator": str(ratio.numerator), "denominator": str(ratio.denominator)},
                                                "B_vs_A_percent": None if ratio is None else float((ratio - 1) * 100),
                                                "scope": "descriptive thread CPU loop envelopes, not callback CPU, SDK improvement, stability or formal metrics"}
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError) as exc:
            status, reason = ("incomplete" if isinstance(exc, IncompleteRecord) else "invalid"), str(exc)
    report.update(result=status, reason=reason, collection_status="failed" if status in ("invalid", "incomplete") else "collected")
    if pair_errors:
        report["pair_errors"] = pair_errors
    if errors:
        report["input_errors"] = errors
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pair-order", choices=("AB", "BA"), required=True)
    names = {"plan": "plan_bytes", "capture": "capture", "result": "result", "owned-stop": "owned_stop",
             "log": "raw", "session": "session", "session-manifest": "session_manifest"}
    for side in ("one", "two"):
        for name in names:
            parser.add_argument("--" + name + "-" + side, type=Path, required=True)
    args = parser.parse_args()
    bundles, sources, errors = {}, {}, []
    for side in ("one", "two"):
        bundle, hashes = {}, {}
        for name, key in names.items():
            try:
                raw = read_input(getattr(args, name.replace("-", "_") + "_" + side), MAX_RAW if key == "raw" else MAX_JSON)
                hashes[key] = digest(raw)
                bundle[key] = raw if key in ("raw", "plan_bytes") else parse_capture(raw)
                if key in ("session", "session_manifest"):
                    bundle[key + "_bytes"] = raw
            except (OSError, ValueError, TypeError, UnicodeError, RecursionError) as exc:
                bundle[key] = None
                errors.append({"input": side + "." + key, "result": "incomplete" if isinstance(exc, FileNotFoundError) else "invalid", "reason": str(exc)})
        bundles[side], sources[side] = bundle, hashes
    report = verify_pair(bundles["one"], bundles["two"], args.pair_order, errors)
    report["source_sha256"] = sources
    print(json.dumps(report, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 1 if report["result"] == "invalid" else 2 if report["result"] in ("incomplete", "unresolved", "reported_only") else 0


if __name__ == "__main__":
    sys.exit(main())
