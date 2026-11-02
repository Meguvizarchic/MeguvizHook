#!/usr/bin/env python3
"""Describe same-control A/A loop noise from checked sessions and raw logs, without setting a gate."""

import argparse
import hashlib
import json
import statistics
import sys
from fractions import Fraction
from pathlib import Path

from build_b07_pair_inputs import parse_capture, read_input
from collect_b07 import inside, plain_path
from verify_aa_pilot import FORMAL_METRICS, IncompleteRecord, InvalidRecord, iter_bench_events, verify_pilot


MAX_JSON = 2 * 1024 * 1024
MAX_RAW = 16 * 1024 * 1024
MAX_PAIRS = 256
MAX_TOTAL_INPUT = 128 * 1024 * 1024


def rational(value):
    """Keep exact arithmetic and JSON-safe integers, including zero and negative values."""
    return None if value is None else {"numerator": str(value.numerator), "denominator": str(value.denominator)}


def durations(session):
    iterations = session["config"]["iterations"]
    return [Fraction(sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"], iterations)
            for sample in session["samples"]]


def side_summary(session):
    values = durations(session)
    median = statistics.median(values)
    result = {"median_ns_per_call": rational(median), "sample_ns_per_call": [rational(v) for v in values],
              "zero_samples": sum(v == 0 for v in values), "sample_count": len(values),
              "last5_over_first5": None, "drift_status": "insufficient_samples"}
    if len(values) >= 10:
        first, last = statistics.median(values[:5]), statistics.median(values[-5:])
        result.update(first5_median_ns_per_call=rational(first), last5_median_ns_per_call=rational(last),
                      last5_over_first5=rational(last / first) if first else None,
                      drift_status="observed" if first else "zero_first_median_unresolved")
    return result, median


def group_identity(session):
    config = dict(session["config"])
    config.pop("pair_order")
    return {"workload": session["workload"], "control": session["control"], "backend": session["backend"],
            "config": config, "artifacts": session["artifacts"], "device": session["device"]}


def reject_invalid_selected(raw, run_id):
    for _, _, _, event in iter_bench_events(raw):
        if event.get("run_id") == run_id:
            if "failure_case" in event:
                raise InvalidRecord("loop event contains an inapplicable failure case")
            for key in ("outcome_unknown", "timed_out"):
                if key in event and (type(event[key]) is not bool or event[key]):
                    raise InvalidRecord("selected loop event has unknown outcome or invalid " + key)
            try:
                json.dumps(event, ensure_ascii=True, allow_nan=False)
            except (ValueError, TypeError, RecursionError) as exc:
                raise InvalidRecord("selected run contains a non-standard JSON number") from exc


def analyze_pairs(pairs):
    """Each input has two sessions and two original logs; checks data but not capture ownership."""
    if not isinstance(pairs, list) or not 1 <= len(pairs) <= MAX_PAIRS:
        raise InvalidRecord("one to 256 explicit A/A pairs are required")
    total_raw = sum(len(row[key]) for row in pairs if isinstance(row, dict)
                    for key in ("left_log", "right_log") if isinstance(row.get(key), bytes))
    if total_raw > MAX_TOTAL_INPUT:
        raise InvalidRecord("total A/A raw input bytes exceed bound")
    groups, identifiers = {}, set()
    for row in pairs:
        if not isinstance(row, dict) or set(row) != {"left", "right", "left_log", "right_log"}:
            raise InvalidRecord("each pair must contain both sessions and both raw logs")
        left, right = row["left"], row["right"]
        if not isinstance(row["left_log"], bytes) or not isinstance(row["right_log"], bytes):
            raise InvalidRecord("both raw logs must be bytes")
        if max(len(row["left_log"]), len(row["right_log"])) > MAX_RAW:
            raise InvalidRecord("raw log exceeds bound")
        for record, raw in ((left, row["left_log"]), (right, row["right_log"])):
            if isinstance(record, dict):
                reject_invalid_selected(raw, record.get("run_id"))
        verified = verify_pilot(left, right, row["left_log"], row["right_log"])
        ordered = {left["side"]: left, right["side"]: right}
        a, b = ordered["A"], ordered["B"]
        pair_ids = [a["pair_id"], a["run_id"], a["session_id"], b["run_id"], b["session_id"]]
        if any(value in identifiers for value in pair_ids):
            raise InvalidRecord("pair/run/session identifier reused across input pairs")
        identifiers.update(pair_ids)
        key = json.dumps(group_identity(a), sort_keys=True, ensure_ascii=True, separators=(",", ":"), allow_nan=False)
        group = groups.setdefault(key, {"identity": group_identity(a), "pairs": [], "ratios": []})
        a_summary, a_median = side_summary(a)
        b_summary, b_median = side_summary(b)
        difference = b_median - a_median
        ratio = difference / a_median if a_median and not (a_summary["zero_samples"] or b_summary["zero_samples"]) else None
        group["pairs"].append({"pair_id": a["pair_id"], "pair_order": a["config"]["pair_order"],
                               "source_verification": verified["result"], "A": a_summary, "B": b_summary,
                               "B_minus_A_ns_per_call": rational(difference),
                               "B_minus_A_over_A": rational(ratio),
                               "ratio_status": "observed" if ratio is not None else "zero_sample_unresolved"})
        if ratio is not None:
            group["ratios"].append(ratio)
    output = []
    for key in sorted(groups):
        group = groups[key]
        ratios = group.pop("ratios")
        group["pair_count"] = len(group["pairs"])
        group["unresolved_ratio_count"] = len(group["pairs"]) - len(ratios)
        group["B_minus_A_over_A_median"] = rational(statistics.median(ratios)) if ratios else None
        group["B_minus_A_over_A_min"] = rational(min(ratios)) if ratios else None
        group["B_minus_A_over_A_max"] = rational(max(ratios)) if ratios else None
        group["orders"] = {order: sum(item["pair_order"] == order for item in group["pairs"])
                           for order in ("AB", "BA")}
        output.append(group)
    return {"schema_version": 1, "record_type": "pilot_aa_noise_description", "result": "descriptive_only",
            "groups": output, "pairs": len(pairs), "performance_gate": "not_evaluated",
            "noise_qualification": "not_evaluated", "device_authentication": "not_verified",
            "formal_metrics": {key: None for key in FORMAL_METRICS},
            "limitations": ["A/A sessions and raw event fields are checked; process ownership, device boot and AB/BA order remain collector declarations.",
                            "Values are thread CPU loop envelopes, not isolated callback CPU or individual call latency.",
                            "No control-group A/B comparison, threshold freeze, confidence interval or performance qualification is performed."]}


def read_pin(root, pin, limit):
    if not isinstance(pin, dict) or set(pin) != {"path", "sha256"}:
        raise InvalidRecord("each input must declare exactly path and SHA-256")
    if not isinstance(pin["sha256"], str) or len(pin["sha256"]) != 64:
        raise InvalidRecord("invalid pinned SHA-256")
    path = inside(root, pin["path"])
    raw = read_input(path, limit)
    if hashlib.sha256(raw).hexdigest() != pin["sha256"]:
        raise InvalidRecord("input SHA-256 mismatch: " + str(path))
    return raw


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    args = parser.parse_args(argv)
    try:
        root = plain_path(args.root)
        manifest_raw = read_pin(root, {"path": str(args.manifest), "sha256": args.manifest_sha256}, MAX_JSON)
        manifest = parse_capture(manifest_raw)
        if set(manifest) != {"schema_version", "record_type", "pairs"} or type(manifest["schema_version"]) is not int \
                or manifest["schema_version"] != 1 or manifest["record_type"] != "pilot_aa_noise_inputs":
            raise InvalidRecord("invalid A/A noise input manifest")
        if not isinstance(manifest["pairs"], list) or not 1 <= len(manifest["pairs"]) <= MAX_PAIRS:
            raise InvalidRecord("invalid input pair count")
        pairs, total = [], len(manifest_raw)
        for item in manifest["pairs"]:
            if not isinstance(item, dict) or set(item) != {"left", "right", "left_log", "right_log"}:
                raise InvalidRecord("invalid pair input pins")
            blobs = {key: read_pin(root, item[key], MAX_RAW if key.endswith("_log") else MAX_JSON)
                     for key in ("left", "right", "left_log", "right_log")}
            total += sum(len(blob) for blob in blobs.values())
            if total > MAX_TOTAL_INPUT:
                raise InvalidRecord("total A/A input bytes exceed bound")
            pairs.append({"left": parse_capture(blobs["left"]), "right": parse_capture(blobs["right"]),
                          "left_log": blobs["left_log"], "right_log": blobs["right_log"]})
        result = analyze_pairs(pairs)
        result["input_manifest_sha256"] = hashlib.sha256(manifest_raw).hexdigest()
        code = 0
    except (IncompleteRecord, InvalidRecord, OSError, UnicodeError, ValueError, TypeError, RecursionError) as exc:
        result = {"result": "incomplete" if isinstance(exc, IncompleteRecord) else "invalid",
                  "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 2 if isinstance(exc, IncompleteRecord) else 1
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
