#!/usr/bin/env python3
"""Check a frozen-profile declaration and pinned local references; never freeze or authorize A/B."""

import argparse
import hashlib
import json
import math
import os
import re
import stat
import sys
from datetime import datetime
from pathlib import Path

from build_b07_pair_inputs import parse_capture, read_input


MAX_JSON, MAX_FILE, MAX_LINKS, MAX_TOTAL = 2 * 1024 * 1024, 64 * 1024 * 1024, 512, 256 * 1024 * 1024
PROFILE_ID = "meguvizhook-profile-draft-v1"
HASH = re.compile(r"[0-9a-f]{64}\Z")
METRICS = {"callback_cpu": "ns", "task_p95": "ns", "task_p99": "ns", "installation": "ms", "cold_start": "ms"}
BUDGETS = {"additional_rss_bytes", "additional_apk_bytes", "additional_fd_count", "additional_thread_count", "retired_resident_bytes"}
HARD = {"ordinary_hook_added_jni_calls", "ordinary_hook_added_ipc_calls", "native_disabled_added_so",
        "new_semantic_failures_allowed", "implicit_duplicate_original_execution_allowed"}
FREEZE = {"owner", "timestamp", "baseline_aa_evidence", "baseline_artifact_sha256", "candidate_artifact_sha256",
          "feature_manifest_sha256", "workload_sha256"}
TOP = {"schema_version", "project", "profile_id", "status", "release_qualified", "freeze", "release_profiles",
       "workloads", "device_cells", "statistics", "metrics", "budgets", "hard_constraints", "notes"}
CONTROLS = {"A0_direct_backend", "A1_existing_equivalent_facade", "A2_meguvizhook_same_semantics", "A3_optional_optimization"}


def issue(items, path, code, message):
    items.append({"path": path, "code": code, "message": message})


def text(value, limit=4096):
    return (isinstance(value, str) and 0 < len(value) <= limit and value == value.strip()
            and value.lower() != "null" and all(ord(c) >= 32 for c in value))


def finite(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def keys(value, required, path, items):
    if not isinstance(value, dict):
        issue(items, path, "object_required", "must be an object")
        return {}
    if set(value) - required:
        issue(items, path, "unknown_fields", "contains fields outside the supported profile schema")
    for name in sorted(required - set(value)):
        issue(items, path + "." + name, "missing", "required field is missing")
    return value


def standard(value, path, items):
    if isinstance(value, float) and not math.isfinite(value):
        issue(items, path, "nonfinite", "non-finite JSON numbers are not accepted")
    elif isinstance(value, dict):
        for name, child in value.items():
            if not isinstance(name, str):
                issue(items, path, "invalid_key", "JSON object keys must be strings")
            standard(child, path + "." + str(name), items)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            standard(child, path + "[%d]" % index, items)
    elif value is not None and type(value) not in (int, float, str, bool):
        issue(items, path, "invalid_json_type", "value is not an ordinary JSON value")


def pin(value, path, items):
    selected = keys(value, {"path", "bytes", "sha256"}, path, items)
    name = selected.get("path")
    if (not text(name) or name.startswith(("/", "\\")) or ":" in name
            or any(part in ("", ".", "..") for part in name.replace("\\", "/").split("/"))):
        issue(items, path + ".path", "relative_path_required", "must be a normalized workspace-relative file path")
    if type(selected.get("bytes")) is not int or selected["bytes"] < 0 or selected["bytes"] > MAX_FILE:
        issue(items, path + ".bytes", "invalid_size", "must be a non-negative integer within the 64 MiB file bound")
    if not isinstance(selected.get("sha256"), str) or not HASH.fullmatch(selected["sha256"]):
        issue(items, path + ".sha256", "invalid_sha256", "must be lowercase SHA-256 hex")
    return selected


def _validate(profile):
    items = []
    value = keys(profile, TOP, "profile", items)
    standard(value, "profile", items)
    for name, expected in (("schema_version", 1), ("project", "MeguvizHook"), ("profile_id", PROFILE_ID)):
        if type(value.get(name)) is not type(expected) or value.get(name) != expected:
            issue(items, "profile." + name, "unsupported_identity", "does not match the registered schema/project/profile identity")
    state = value.get("status")
    if state not in ("DRAFT_NOT_FROZEN", "FROZEN"):
        issue(items, "profile.status", "invalid_status", "must declare DRAFT_NOT_FROZEN or FROZEN")
    if type(value.get("release_qualified")) is not bool:
        issue(items, "profile.release_qualified", "boolean_required", "must be a boolean declaration")
    frozen = keys(value.get("freeze"), FREEZE, "profile.freeze", items)
    if not text(frozen.get("owner"), 256):
        issue(items, "profile.freeze.owner", "owner_missing", "non-empty producer-declared owner is required; this is not identity authentication")
    stamp = frozen.get("timestamp")
    try:
        if not isinstance(stamp, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", stamp):
            raise ValueError("timezone required")
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        if parsed.utcoffset() is None:
            raise ValueError("timezone required")
    except (ValueError, TypeError, OverflowError):
        issue(items, "profile.freeze.timestamp", "timezone_timestamp_required", "must be an ISO date-time with an explicit timezone")
    pin(frozen.get("baseline_aa_evidence"), "profile.freeze.baseline_aa_evidence", items)
    for name in FREEZE - {"owner", "timestamp", "baseline_aa_evidence"}:
        if not isinstance(frozen.get(name), str) or not HASH.fullmatch(frozen[name]):
            issue(items, "profile.freeze." + name, "invalid_sha256", "must be lowercase SHA-256 hex")
    cells = value.get("device_cells")
    if not isinstance(cells, list) or not cells:
        issue(items, "profile.device_cells", "device_cells_empty", "at least one explicitly declared device cell is required")
    else:
        seen = set()
        for index, cell in enumerate(cells):
            if not (text(cell) or isinstance(cell, dict) and bool(cell)):
                issue(items, "profile.device_cells[%d]" % index, "invalid_cell", "must be a non-empty cell label or object")
            encoded = json.dumps(cell, sort_keys=True, ensure_ascii=True, allow_nan=False)
            if encoded in seen:
                issue(items, "profile.device_cells", "duplicate_cell", "device cell declarations must be unique")
            seen.add(encoded)
    stats = keys(value.get("statistics"), {"independent_sessions", "samples_per_session", "warmup_policy",
                                          "randomization_policy", "analysis_method", "confidence_level"}, "profile.statistics", items)
    for name in ("independent_sessions", "samples_per_session"):
        if type(stats.get(name)) is not int or stats[name] <= 0:
            issue(items, "profile.statistics." + name, "positive_integer_required", "must be an explicit positive integer")
    for name in ("warmup_policy", "randomization_policy", "analysis_method"):
        policy = stats.get(name)
        if not (text(policy) or isinstance(policy, dict) and bool(policy)):
            issue(items, "profile.statistics." + name, "policy_missing", "must be an explicit non-empty policy string or object")
    if not finite(stats.get("confidence_level")) or not 0 < stats["confidence_level"] < 1:
        issue(items, "profile.statistics.confidence_level", "invalid_confidence", "must be finite and strictly between zero and one")
    metrics, found = value.get("metrics"), []
    if not isinstance(metrics, list):
        issue(items, "profile.metrics", "metric_list_required", "all five named metric declarations are required")
    else:
        for index, metric in enumerate(metrics):
            path = "profile.metrics[%d]" % index
            metric = keys(metric, {"name", "unit", "minimum_worthwhile_reduction_fraction", "maximum_allowed_increase_fraction"}, path, items)
            name = metric.get("name")
            if not isinstance(name, str) or name not in METRICS:
                issue(items, path + ".name", "unsupported_metric", "must identify one of the five registered metrics")
            else:
                found.append(name)
                if metric.get("unit") != METRICS[name]:
                    issue(items, path + ".unit", "wrong_unit", "metric unit differs from the registered metric")
            for key in ("minimum_worthwhile_reduction_fraction", "maximum_allowed_increase_fraction"):
                if not finite(metric.get(key)) or not 0 <= metric[key] <= 1:
                    issue(items, path + "." + key, "fraction_required", "must be an explicit finite fraction in [0, 1]; booleans are not numbers")
        if set(found) != set(METRICS) or len(found) != len(METRICS):
            issue(items, "profile.metrics", "metric_set_mismatch", "each of the five registered metrics must occur exactly once")
    budgets = keys(value.get("budgets"), BUDGETS, "profile.budgets", items)
    for name in sorted(BUDGETS):
        if type(budgets.get(name)) is not int or budgets[name] < 0:
            issue(items, "profile.budgets." + name, "nonnegative_integer_required", "must be an explicit non-negative integer")
    constraints = keys(value.get("hard_constraints"), HARD, "profile.hard_constraints", items)
    for name in sorted(HARD):
        if type(constraints.get(name)) is not int or constraints[name] != 0:
            issue(items, "profile.hard_constraints." + name, "zero_constraint_required", "must be integer zero, not a boolean")
    releases = value.get("release_profiles")
    if not isinstance(releases, list) or not releases:
        issue(items, "profile.release_profiles", "nonempty_list_required", "must retain a non-empty list of release profile labels")
    else:
        seen = set()
        for index, name in enumerate(releases):
            if not text(name, 256):
                issue(items, "profile.release_profiles[%d]" % index, "invalid_release_profile", "must be a normalized non-empty string")
            elif name in seen:
                issue(items, "profile.release_profiles", "duplicate_release_profile", "release profile labels must be unique")
            else:
                seen.add(name)
    workloads = value.get("workloads")
    if not isinstance(workloads, list) or not workloads:
        issue(items, "profile.workloads", "nonempty_list_required", "must retain a non-empty workload subset")
    else:
        seen = set()
        for index, row in enumerate(workloads):
            path = "profile.workloads[%d]" % index
            row = keys(row, {"id", "name", "description", "controls", "device_cells", "status"}, path, items)
            name = row.get("id")
            if not isinstance(name, str) or name not in {"B%02d" % i for i in range(1, 11)}:
                issue(items, path + ".id", "invalid_workload_id", "must identify B01 through B10")
            elif name in seen:
                issue(items, path + ".id", "duplicate_workload", "workload IDs must be unique")
            else:
                seen.add(name)
            for key in ("name", "description", "status"):
                if not text(row.get(key)):
                    issue(items, path + "." + key, "nonempty_string_required", "must be a normalized non-empty string")
            controls = row.get("controls")
            if not isinstance(controls, list) or not controls:
                issue(items, path + ".controls", "controls_required", "must be a non-empty list of defined controls")
            else:
                selected = set()
                for control in controls:
                    if not isinstance(control, str) or control not in CONTROLS:
                        issue(items, path + ".controls", "invalid_control", "control is outside the defined enum")
                    elif control in selected:
                        issue(items, path + ".controls", "duplicate_control", "controls must be unique")
                    else:
                        selected.add(control)
            if not isinstance(row.get("device_cells"), list):
                issue(items, path + ".device_cells", "cell_list_required", "must be a list of declarations, not device authentication")
    if not isinstance(value.get("notes"), list) or any(not text(note) for note in value.get("notes", [])):
        issue(items, "profile.notes", "notes_required", "must be a list of non-empty strings")
    if state == "DRAFT_NOT_FROZEN":
        issue(items, "profile.status", "not_frozen", "current declaration is draft; missing fields never upgrade it")
    return {"result": "blocked_not_frozen" if state == "DRAFT_NOT_FROZEN" else "invalid" if items else "frozen_declaration_structurally_valid",
            "diagnostics": items, "evidence_links_checked": False, "performance_gate": "not_evaluated",
            "ab_authorized": False, "release_qualified": False,
            "limits": "structure and producer declarations only; no owner authentication, baseline sufficiency, noise qualification, A/B authorization or GATE pass"}


def validate_profile(profile_dict):
    """Pure validation; never invent thresholds, mutate input, open files or authenticate an owner."""
    try:
        return _validate(profile_dict)
    except (TypeError, ValueError, OverflowError, RecursionError) as exc:
        return {"result": "blocked_not_frozen" if isinstance(profile_dict, dict) and profile_dict.get("status") == "DRAFT_NOT_FROZEN" else "invalid",
                "diagnostics": [{"path": "profile", "code": "invalid_json_structure", "message": str(exc)}],
                "evidence_links_checked": False, "performance_gate": "not_evaluated", "ab_authorized": False, "release_qualified": False}


def _inside(root, value):
    path = Path(value)
    if ".." in path.parts:
        raise ValueError("parent path components are not accepted")
    path = Path(os.path.abspath(os.fspath(path if path.is_absolute() else root / path)))
    path.relative_to(root)
    for part in (path, *path.parents):
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("symbolic links and reparse points are not accepted")
    return path


def _baseline(root, reference):
    path = _inside(root, reference["path"])
    raw = read_input(path, MAX_JSON)
    if len(raw) != reference["bytes"] or hashlib.sha256(raw).hexdigest() != reference["sha256"]:
        raise ValueError("baseline evidence bytes or SHA-256 differs")
    summary = parse_capture(raw)
    problems = []; standard(summary, "baseline", problems)
    if problems:
        raise ValueError("baseline contains non-standard JSON values")
    if type(summary.get("schema_version")) is not int or summary["schema_version"] != 1 or summary.get("status") != "batch_records_collected_reported_only":
        raise ValueError("unsupported baseline schema; expected the current round50-style same-control A/A summary")
    collection, pairs = summary.get("collection_evidence"), summary.get("pairs")
    if not isinstance(collection, dict) or not isinstance(pairs, list) or not pairs:
        raise ValueError("baseline same-control collection evidence or pairs missing")
    for name in ("same_control_pairs", "independent_processes", "owned_stops_confirmed", "samples"):
        if type(collection.get(name)) is not int or collection[name] <= 0:
            raise ValueError("baseline collection count missing or invalid: " + name)
    if (collection["same_control_pairs"] != len(pairs) or collection["independent_processes"] != 2 * len(pairs)
            or collection["owned_stops_confirmed"] != collection["independent_processes"]
            or collection["samples"] < collection["independent_processes"]
            or type(collection.get("outcome_unknown")) is not int or collection["outcome_unknown"] != 0
            or collection.get("all_pair_results") != "reported_only"):
        raise ValueError("baseline collection declarations contradict each other")
    for row in pairs:
        if (not isinstance(row, dict) or row.get("workload") not in tuple("B%02d" % i for i in range(1, 7))
                or row.get("control") not in ("A0", "A2") or row.get("order") not in ("AB", "BA")
                or not isinstance(row.get("sides"), list) or len(row["sides"]) != 2
                or {side.get("side") for side in row["sides"] if isinstance(side, dict)} != {"A", "B"}):
            raise ValueError("baseline pair declaration is invalid")
        for side in row["sides"]:
            required = {"plan", "capture", "result", "owned_stop", "raw_log", "session", "session_manifest", "single_verification"}
            if not required <= set(side):
                raise ValueError("baseline side lacks complete saved evidence references")
            for name in sorted(required):
                errors = []
                pin(side[name], "baseline side." + name, errors)
                if errors:
                    raise ValueError("invalid or incomplete baseline side evidence pin: " + name)
    references = []
    def visit(value):
        if isinstance(value, dict):
            if "path" in value and "sha256" in value:
                errors = []; pin(value, "baseline reference", errors)
                if errors:
                    raise ValueError("invalid baseline file reference")
                references.append(value)
            for child in value.values(): visit(child)
        elif isinstance(value, list):
            for child in value: visit(child)
    visit(summary)
    if not references or len(references) > MAX_LINKS:
        raise ValueError("baseline file reference count outside bounds")
    checked, total = {}, 0
    for ref in references:
        old = checked.get(ref["path"])
        expected = (ref["bytes"], ref["sha256"])
        if old is not None:
            if old != expected: raise ValueError("conflicting baseline references for one path")
            continue
        total += ref["bytes"]
        if total > MAX_TOTAL: raise ValueError("baseline reference bytes exceed 256 MiB bound")
        data = read_input(_inside(root, ref["path"]), MAX_FILE)
        if len(data) != ref["bytes"] or hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("linked baseline file bytes or SHA-256 differs: " + ref["path"])
        checked[ref["path"]] = expected
    return {"status": summary["status"], "same_control_pairs": len(pairs), "declared_processes": collection["independent_processes"],
            "linked_files_checked": len(checked), "pair_result": "reported_only", "noise_sufficiency": "not_evaluated",
            "measurement_hook_per_call": "not_verified", "scope": "direct file links and declaration relationships only; not a raw-pair revalidation or authenticity proof"}


def check_profile(root, path, expected_sha256):
    """Read only the supplied pinned profile and supported baseline links inside the workspace."""
    report = {"result": "invalid", "diagnostics": [], "evidence_links_checked": False,
              "performance_gate": "not_evaluated", "ab_authorized": False, "release_qualified": False}
    try:
        if not isinstance(expected_sha256, str) or not HASH.fullmatch(expected_sha256):
            raise ValueError("external profile SHA-256 must be lowercase hex")
        root = Path(os.path.abspath(os.fspath(root)))
        path = _inside(root, path)
        raw = read_input(path, MAX_JSON)
        actual = hashlib.sha256(raw).hexdigest()
        report["profile_sha256"] = actual
        if actual != expected_sha256:
            raise ValueError("profile original-byte SHA-256 differs from the external pin")
        profile = parse_capture(raw)
        report.update(validate_profile(profile), profile_sha256=actual)
        if report["result"] != "frozen_declaration_structurally_valid":
            return report
        report["baseline_evidence"] = _baseline(root, profile["freeze"]["baseline_aa_evidence"])
        report["evidence_links_checked"] = True
    except (OSError, TypeError, ValueError, OverflowError, RecursionError) as exc:
        report["result"] = "invalid"
        issue(report["diagnostics"], "profile_or_baseline", "file_or_evidence_invalid", str(exc))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--profile-sha256", required=True)
    args = parser.parse_args()
    report = check_profile(args.root, args.profile, args.profile_sha256)
    print(json.dumps(report, sort_keys=True, ensure_ascii=True, allow_nan=False))
    return {"frozen_declaration_structurally_valid": 0, "blocked_not_frozen": 2, "invalid": 1}[report["result"]]


if __name__ == "__main__":
    sys.exit(main())
