#!/usr/bin/env python3
"""Analyze adjacent sampled CPU-clock gaps after strict CPU-body pair verification.

Gaps include work outside the measured body interval. For B01 the prior after
ends after the controlled original/proceed; for B02 the prior before ends before
them. They are not dispatcher-only costs, wall time, or a performance gate.
Inputs and their provenance are not authenticated.
"""

import argparse
import hashlib
import json
from pathlib import Path

from verify_cpu_body_probe import InvalidRecord, unique_fields
from verify_cpu_body_pair import verify_pair


def _summary(rows):
    """Return an exact rational median; do not round large nanosecond integers."""
    values = sorted(row["gap_ns"] for row in rows)
    if not values:
        return {"count": 0, "min_ns": None, "median_ns": None, "max_ns": None}
    middle = len(values) // 2
    if len(values) % 2:
        numerator, denominator = values[middle], 1
    else:
        numerator, denominator = values[middle - 1] + values[middle], 2
    return {"count": len(values), "min_ns": values[0],
            "median_ns": {"numerator": str(numerator), "denominator": denominator},
            "max_ns": values[-1]}


def _sample_gaps(report, workload, warmup, samples):
    readings = report["readings"]
    selected = [row for row in readings if row["ordinal"] >= warmup]
    if len(selected) != samples or [row["ordinal"] for row in selected] != list(range(warmup, warmup + samples)):
        raise InvalidRecord("sample ordinal sequence does not match verified request")
    prior_phase = "after" if workload == "B01" else "before"
    prior_key = prior_phase + "_cpu_end_ns"
    result = []
    for current, following in zip(selected, selected[1:]):
        prior = current[prior_key]
        subsequent = following["before_cpu_start_ns"]
        if type(prior) is not int or type(subsequent) is not int or prior < 0 or subsequent < prior:
            raise InvalidRecord("invalid sampled CPU gap")
        result.append({"from_ordinal": current["ordinal"], "to_ordinal": following["ordinal"],
                       "prior_phase": prior_phase, "prior_end_ns": prior,
                       "next_before_start_ns": subsequent, "gap_ns": subsequent - prior})
    return result


def analyze(left, right, left_raw, right_raw):
    """Verify both original logs first, then describe only adjacent sample gaps."""
    paired = verify_pair(left, right, left_raw, right_raw)
    controlled_work = ("B01: the prior controlled proceed and original occur before the after endpoint "
                       "and are outside this gap under the validated ledger" if paired["workload"] == "B01"
                       else "B02: the prior controlled proceed and original occur after the before endpoint "
                            "and are inside this gap under the validated ledger")
    outcome = {"pair_id": paired["pair_id"], "backend": paired["backend"],
               "control": paired["control"], "workload": paired["workload"],
               "pair_order": paired["pair_order"], "warmup": paired["warmup"],
               "samples": paired["samples"], "pair_result": paired["result"],
               "performance_gate": "not_evaluated", "device_authentication": "not_verified",
               "provenance": paired["provenance"],
               "scope": "same-thread CPU time between adjacent instrumented sample callbacks; "
                        "excludes the warmup-to-sample boundary; includes unmeasured ledger, loop, "
                        "framework and probe work; not dispatcher-only cost, wall time, callback CPU or a gate",
               "controlled_proceed_and_original": controlled_work}
    if paired["result"] != "body_cpu_pair_consistent":
        return {**outcome, "result": paired["result"], "reason": paired["reason"], "sides": {}}

    sides = {}
    for name in ("A", "B"):
        report = paired["sides"][name]
        gaps = _sample_gaps(report, paired["workload"], paired["warmup"], paired["samples"])
        sides[name] = {"run_id": report["run_id"], "pid": report["pid"], "tid": report["tid"],
                       "raw_log_sha256": report["raw_log_sha256"],
                       "gaps": gaps, "summary": _summary(gaps)}
    if not sides["A"]["gaps"] or not sides["B"]["gaps"]:
        return {**outcome, "result": "unresolved", "reason": "at least two samples are required", "sides": sides}
    return {**outcome, "result": "gaps_described", "reason": "verified adjacent sampled CPU gaps", "sides": sides}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest_one", type=Path)
    parser.add_argument("manifest_two", type=Path)
    parser.add_argument("--log-one", type=Path, required=True)
    parser.add_argument("--log-two", type=Path, required=True)
    args = parser.parse_args()
    try:
        one_bytes, two_bytes = args.manifest_one.read_bytes(), args.manifest_two.read_bytes()
        left = json.loads(one_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
        right = json.loads(two_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
        result = analyze(left, right, args.log_one.read_bytes(), args.log_two.read_bytes())
        result["manifest_sha256"] = {"one": hashlib.sha256(one_bytes).hexdigest(),
                                     "two": hashlib.sha256(two_bytes).hexdigest()}
    except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError, KeyError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {"gaps_described": 0, "unresolved": 2, "incomplete": 2,
            "diagnostic_failure": 1, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    raise SystemExit(main())
