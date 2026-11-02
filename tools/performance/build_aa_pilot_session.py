#!/usr/bin/env python3
"""Build one reported pilot session from a threadtime log and explicit external identity."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from verify_aa_pilot import (FORMAL_METRICS, OBSERVATION_FIELDS, OBSERVED_KIND, IncompleteRecord,
                             InvalidRecord, fields, identifier, iter_bench_events, unique_fields,
                             validate_session, verify_raw_log)


def required(event, field, where):
    if field not in event:
        raise IncompleteRecord(where + " missing " + field)
    return event[field]


def build_session(raw, run_id, manifest):
    """Extract observations; external identities are supplied, not inferred or authenticated."""
    identifier(run_id, "selected run_id")
    fields(manifest, ("schema_version", "data_origin", "backend", "process_start_token",
                      "pair_order", "artifacts", "device"), "manifest")
    if type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
        raise InvalidRecord("unsupported manifest schema_version")

    selected = []
    for line_number, pid, tid, event in iter_bench_events(raw):
        if event.get("run_id") == run_id:
            selected.append((pid, tid, event))

    if not selected:
        raise IncompleteRecord("raw log has no events for selected run")
    pid, tid, start = selected[0]
    if start.get("event") != "start":
        raise IncompleteRecord("selected run does not begin with a start event")
    end = selected[-1][2]
    if end.get("event") != "end":
        raise IncompleteRecord("selected run has no terminal end event")

    observed = required(start, "measurement_kind", "start") == OBSERVED_KIND
    sample_fields = ("index", "iterations", "thread_cpu_start_ns", "thread_cpu_end_ns", "checksum")
    if observed:
        sample_fields += OBSERVATION_FIELDS
    samples = []
    for _, _, event in selected:
        if event.get("event") == "sample":
            samples.append({field: required(event, field, "sample event") for field in sample_fields})

    record = {
        "schema_version": 1,
        "record_type": "aa_pilot_session",
        "data_origin": manifest["data_origin"],
        "pair_id": required(start, "pair_id", "start"),
        "side": required(start, "side", "start"),
        "run_id": run_id,
        "session_id": required(start, "session_id", "start"),
        "process": {"pid": pid, "start_token": manifest["process_start_token"]},
        "thread_id": tid,
        "workload": required(start, "workload", "start"),
        "control": required(start, "control", "start"),
        "backend": manifest["backend"],
        "config": {"warmup": required(start, "warmup", "start"),
                   "samples": required(start, "samples", "start"),
                   "iterations": required(start, "iterations", "start"),
                   "measurement_kind": required(start, "measurement_kind", "start"),
                   "pair_order": manifest["pair_order"]},
        "artifacts": manifest["artifacts"],
        "device": manifest["device"],
        "capture": {"raw_log_sha256": hashlib.sha256(raw).hexdigest(), "format": "threadtime",
                    "tag": "MeguvizHookBench", "header_pid": pid, "header_tid": tid},
        "preflight": {"status": "not_verified", "instrumented": None, "observed_return": None,
                      "original_calls": None, "hook_observed": None, "evidence_sha256": None},
        "execution_status": "completed" if end.get("status") == "pass" else "failed",
        "cleanup_reported": required(end, "cleanup_reported", "end"),
        "samples": samples,
        "formal_metrics": {field: None for field in FORMAL_METRICS},
    }
    if observed:
        warmups = [event for _, _, event in selected if event.get("event") == "warmup_observation"]
        if not warmups:
            raise IncompleteRecord("raw log is missing warmup observation")
        if len(warmups) != 1:
            raise InvalidRecord("raw log has duplicate warmup observations")
        record["warmup_observation"] = {field: required(warmups[0], field, "warmup observation")
                                        for field in ("iterations",) + OBSERVATION_FIELDS}
    validate_session(record)
    verify_raw_log(record, raw)
    return record


def generate_session(raw, run_id, manifest, output):
    """Validate before opening output, and create it exclusively to prevent silent overwrite."""
    record = build_session(raw, run_id, manifest)
    with output.open("x", encoding="utf-8", newline="\n") as destination:
        json.dump(record, destination, ensure_ascii=True, indent=2)
        destination.write("\n")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="unmodified threadtime log")
    parser.add_argument("--run-id", required=True, help="selected canonical run UUID")
    parser.add_argument("--manifest", type=Path, required=True, help="collector-provided identity JSON")
    parser.add_argument("--output", type=Path, required=True, help="new session JSON; must not exist")
    args = parser.parse_args()
    try:
        with args.manifest.open(encoding="utf-8") as source:
            manifest = json.load(source, object_pairs_hook=unique_fields)
        record = generate_session(args.log.read_bytes(), args.run_id, manifest, args.output)
        result = {"result": "written", "run_id": record["run_id"], "preflight": "not_verified",
                  "provenance": "external identities are collector-reported, not authenticated",
                  "performance_gate": "not_evaluated"}
        code = 0
    except IncompleteRecord as exc:
        result = {"result": "incomplete", "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 2
    except (InvalidRecord, OSError, UnicodeError, json.JSONDecodeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 1
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
