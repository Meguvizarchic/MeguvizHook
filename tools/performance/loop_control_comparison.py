#!/usr/bin/env python3
"""Prepare and verify a frozen, descriptive A0/A2 loop-envelope experiment offline; never execute ADB."""

import argparse
import json
import statistics
import sys
import uuid
from datetime import datetime, timezone
from fractions import Fraction
from pathlib import Path

import analyze_pilot_aa_noise as noise
import check_frozen_profile as profile_checker
import collect_loop_pilot as collector
import verify_b10_pair
import verify_loop_pilot_pair as sides
from verify_aa_pilot import FORMAL_METRICS


common = collector.common
InvalidRecord = collector.InvalidRecord
PROFILE_PATH = "work/performance/performance_profile.json"
METHOD = "median_pairwise_loop_envelope_fraction_v1"
TOOLS = (*collector.TOOLS, "verify_loop_pilot_pair.py", "verify_b10_pair.py", "analyze_pilot_aa_noise.py",
         "check_frozen_profile.py", "loop_control_comparison.py")
MAX_PAIRS = 32
FREEZE_FIELDS = {"schema_version", "record_type", "status", "freeze_id", "frozen_utc", "scope", "formal_gate_enabled",
                 "profile_pin", "controls", "workloads", "backend", "parameters", "device", "artifacts", "artifact_audit",
                 "workload_sha256", "feature_manifest_sha256", "fixed_input_manifest_sha256", "source_aa_inputs",
                 "source_aa_noise_report", "pairs_per_workload", "analysis_method", "aa_stability_rule", "randomization_policy", "thresholds"}
PLAN_FIELDS = {"schema_version", "record_type", "status", "created_utc", "comparison_id", "evidence_root", "plan_path",
               "freeze", "profile", "inputs", "scope", "formal_gate_enabled", "controls", "pairs", "tools"}
BUNDLE_NAMES = {"capture": "capture", "result": "result", "owned_stop": "owned_stop", "raw": "raw",
                "session": "session", "session_manifest": "session_manifest"}


class FrozenProfileBlocked(InvalidRecord):
    def __init__(self, report):
        super().__init__("global performance profile is not a complete, linked frozen declaration")
        self.report = report


def require(value, message):
    if not value: raise InvalidRecord(message)


def fields(value, names, where):
    require(isinstance(value, dict) and set(value) == set(names), where + " fields differ from contract")


def utc(value):
    require(isinstance(value, str), "UTC timestamp must be a string")
    try: result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error: raise InvalidRecord("invalid UTC timestamp") from error
    require(result.tzinfo is not None and result.utcoffset() is not None, "timestamp must include an explicit timezone")
    return result.astimezone(timezone.utc)


def pin(root, value, limit=2 * 1024 * 1024):
    fields(value, {"path", "bytes", "sha256"}, "file pin")
    common.valid_hash(value["sha256"])
    require(type(value["bytes"]) is int and 0 <= value["bytes"] <= limit, "pinned file size exceeds bound")
    path = common.inside(root, value["path"]); raw = common.read_input(path, limit)
    require(len(raw) == value["bytes"] and common.digest(raw) == value["sha256"], "pinned file bytes changed")
    return raw


def validate_freeze(raw):
    require(isinstance(raw, bytes) and len(raw) <= 2 * 1024 * 1024, "freeze must be bounded original bytes")
    value = common.parse_capture(raw); fields(value, FREEZE_FIELDS, "loop freeze")
    for key, expected in (("schema_version", 1), ("record_type", "loop_control_comparison_freeze"), ("status", "FROZEN"),
                          ("scope", "loop_cpu_envelope_only"), ("formal_gate_enabled", False),
                          ("controls", {"baseline": "A0", "candidate": "A2"})):
        require(collector.same(value[key], expected), "invalid loop freeze declaration: " + key)
    collector.identifier(value["freeze_id"], "freeze identifier"); utc(value["frozen_utc"])
    workloads = value["workloads"]
    require(type(workloads) is list and workloads and all(type(item) is str and item in ("B01", "B02") for item in workloads)
            and len(workloads) == len(set(workloads)), "freeze only supports unique B01/B02 workloads")
    require(value["backend"] in collector.MODULES, "invalid frozen backend")
    for workload in workloads: collector.parameters(workload, value["parameters"])
    require(type(value["pairs_per_workload"]) is int and value["pairs_per_workload"] >= 2
            and value["pairs_per_workload"] % 2 == 0 and value["pairs_per_workload"] * len(workloads) <= MAX_PAIRS,
            "freeze requires bounded, balanced new-process pairs")
    method = value["analysis_method"]
    require(method == METHOD if isinstance(method, str) else isinstance(method, dict) and method.get("method") == METHOD,
            "unsupported externally frozen analysis method")
    require(bool(value["aa_stability_rule"]) and type(value["aa_stability_rule"]) in (str, dict),
            "A/A stability rule must be an explicit frozen declaration, without inferred thresholds")
    if isinstance(method, dict) and "aa_stability_rule" in method:
        require(collector.same(method["aa_stability_rule"], value["aa_stability_rule"]), "explicit analysis stability rule differs from freeze")
    require(bool(value["randomization_policy"]) and type(value["randomization_policy"]) in (str, dict), "randomization policy missing")
    for key in ("aa_stability_rule", "randomization_policy"):
        policy = value[key]
        if isinstance(policy, str): require(profile_checker.text(policy), "invalid explicit policy string: " + key)
        else:
            require(type(policy) is dict and bool(policy), "empty explicit policy: " + key)
            json.dumps(policy, ensure_ascii=True, allow_nan=False)
    fields(value["thresholds"], {"minimum_worthwhile_reduction_fraction", "maximum_allowed_increase_fraction"}, "loop thresholds")
    for fraction in value["thresholds"].values():
        fields(fraction, {"numerator", "denominator"}, "exact loop threshold")
        n, d = fraction["numerator"], fraction["denominator"]
        require(isinstance(n, str) and len(n) <= 20 and n.isascii() and n.isdigit() and str(int(n)) == n
                and type(d) is int and 1 <= d <= 2**63 - 1 and 0 <= int(n) <= d, "loop threshold must be an exact rational in 0..1")
        normalized = Fraction(int(n), d)
        require(normalized.numerator == int(n) and normalized.denominator == d, "loop threshold rational must be normalized")
    for key in ("workload_sha256", "feature_manifest_sha256", "fixed_input_manifest_sha256"): common.valid_hash(value[key])
    require(isinstance(value["profile_pin"], dict) and value["profile_pin"].get("path") == PROFILE_PATH,
            "loop freeze cannot replace the global profile with a separate draft")
    return value


def check_freeze(root, reference):
    value = validate_freeze(pin(root, reference))
    profile_raw = pin(root, value["profile_pin"])
    profile = common.parse_capture(profile_raw)
    checked = profile_checker.check_profile(root, root / PROFILE_PATH, value["profile_pin"]["sha256"])
    if checked.get("result") != "frozen_declaration_structurally_valid" or checked.get("evidence_links_checked") is not True:
        raise FrozenProfileBlocked(checked)
    require(any(collector.same(cell, value["device"]) for cell in profile["device_cells"]), "loop device cell is not explicitly frozen in the global profile")
    require("core-" + value["backend"] in profile["release_profiles"], "loop backend is not frozen in global release profiles")
    workload_scope = {item["id"]: item for item in profile["workloads"]}
    for workload in value["workloads"]:
        declared = workload_scope.get(workload)
        require(declared is not None and {"A0_direct_backend", "A2_meguvizhook_same_semantics"} <= set(declared["controls"]),
                "global profile does not freeze both controls for the selected workload")
        require(not declared["device_cells"] or any(collector.same(cell, value["device"]) for cell in declared["device_cells"]),
                "selected workload device scope differs from the frozen loop device")
    require(utc(profile["freeze"]["timestamp"]) <= utc(value["frozen_utc"]), "loop freeze predates actual profile freeze")
    stats = profile["statistics"]
    require(collector.same(stats["analysis_method"], value["analysis_method"])
            and collector.same(stats["randomization_policy"], value["randomization_policy"]), "loop analysis/order policy differs from frozen profile")
    require(type(stats["independent_sessions"]) is int and stats["independent_sessions"] == value["pairs_per_workload"]
            and stats["samples_per_session"] == value["parameters"]["samples"], "loop session counts differ from frozen profile")
    expected_warmup = {"warmup": value["parameters"]["warmup"], "iterations": value["parameters"]["iterations"],
                       "measurement_mode": value["parameters"]["measurement_mode"]}
    require(collector.same(stats["warmup_policy"], expected_warmup), "loop warmup/mode policy differs from frozen profile")
    check_source_aa(root, value, profile)
    pin(root, value["artifact_audit"])
    fields(value["artifacts"], {"host", "module"}, "frozen artifacts")
    for label, package in (("host", collector.HOST), ("module", collector.MODULES[value["backend"]])):
        artifact = value["artifacts"][label]
        fields(artifact, {"path", "bytes", "sha256", "package", "certificate_sha256"}, "frozen APK")
        require(artifact["package"] == package, "frozen package differs")
        common.valid_hash(artifact["certificate_sha256"])
        pin(root, {key: artifact[key] for key in ("path", "bytes", "sha256")}, 64 * 1024 * 1024)
    module_sha = value["artifacts"]["module"]["sha256"]
    require(profile["freeze"]["baseline_artifact_sha256"] == profile["freeze"]["candidate_artifact_sha256"] == module_sha,
            "profile controls must bind the actual shared module APK bytes")
    for key in ("workload_sha256", "feature_manifest_sha256"):
        require(profile["freeze"][key] == value[key], "profile workload/feature binding differs")
    return value, checked


def check_source_aa(root, value, profile):
    require(collector.same(value["source_aa_inputs"], profile["freeze"]["baseline_aa_evidence"]),
            "loop A/A source must be the global profile's exact baseline summary pin")
    baseline = common.parse_capture(pin(root, value["source_aa_inputs"]))
    require(collector.same(baseline.get("noise_description"), value["source_aa_noise_report"]),
            "loop noise report must be the exact report linked by the frozen baseline summary")
    aa = common.parse_capture(pin(root, value["source_aa_noise_report"]))
    require(aa.get("record_type") == "pilot_aa_noise_description" and aa.get("result") == "descriptive_only"
            and type(aa.get("groups")) is list, "source A/A report must retain its descriptive qualification")
    expected = {(workload, control) for workload in value["workloads"] for control in ("A0", "A2")}
    seen, noise_pairs = set(), {}
    config = {key: value["parameters"][key] for key in ("warmup", "samples", "iterations")}
    config["measurement_kind"] = collector.KINDS[value["parameters"]["measurement_mode"]]
    hashes = {"host_apk_sha256": value["artifacts"]["host"]["sha256"], "module_apk_sha256": value["artifacts"]["module"]["sha256"],
              "workload_sha256": value["workload_sha256"], "feature_manifest_sha256": value["feature_manifest_sha256"]}
    legacy_device = {key: value["device"][key] for key in sides.SESSION_DEVICE}
    for group in aa["groups"]:
        identity = group.get("identity", {}); key = (identity.get("workload"), identity.get("control"))
        require(key[0] in collector.WORKLOADS and key[1] in ("A0", "A2"), "source A/A noise contains an unsupported workload/control")
        if key not in expected: continue
        require(key not in seen, "source A/A noise has repeated selected groups")
        seen.add(key)
        require(identity.get("backend") == value["backend"] and collector.same(identity.get("config"), config)
                and collector.same(identity.get("artifacts"), hashes) and collector.same(identity.get("device"), legacy_device),
                "source A/A noise identity/config/artifacts/device differs from freeze")
        pairs = group.get("pairs")
        require(type(group.get("unresolved_ratio_count")) is int and group["unresolved_ratio_count"] == 0
                and type(pairs) is list and pairs and type(group.get("pair_count")) is int and group["pair_count"] == len(pairs),
                "source A/A noise has missing or unresolved pairs")
        pair_ids = set()
        for row in pairs:
            pair_id = row.get("pair_id"); collector.identifier(pair_id, "source noise pair")
            require(pair_id not in pair_ids, "source A/A noise repeats a pair identifier"); pair_ids.add(pair_id)
            for side in ("A", "B"):
                counts = row.get(side, {})
                require(type(counts.get("zero_samples")) is int and counts["zero_samples"] == 0
                        and counts.get("sample_count") == value["parameters"]["samples"], "source A/A has zero or missing sample declarations")
        noise_pairs[key] = pair_ids
    require(seen == expected, "source A/A noise does not cover every frozen workload/control group")
    captures, baseline_pairs = set(), {}
    for row in baseline.get("pairs", []):
        key = (row.get("workload"), row.get("control"))
        require(key[0] in collector.WORKLOADS and key[1] in ("A0", "A2"), "baseline summary contains unsupported workload/control groups")
        if key not in expected: continue
        side_rows = row.get("sides")
        require(type(side_rows) is list and len(side_rows) == 2 and {item.get("side") for item in side_rows} == {"A", "B"}, "baseline pair must declare both sides")
        shared_pair = None
        for side in side_rows:
            capture = common.parse_capture(pin(root, side["capture"]))
            aa_plan = common.parse_capture(pin(root, side["plan"]))
            require(type(aa_plan.get("schema_version")) is int and aa_plan["schema_version"] == 1
                    and aa_plan.get("record_type") == "loop_pilot_plan", "baseline original side plan is not a loop plan")
            require(capture.get("plan_sha256") == side["plan"]["sha256"]
                    and all(key in aa_plan and key in capture and collector.same(aa_plan[key], capture[key])
                            for key in ("side", "run_id", "session_id", "pair_id", "workload", "control")),
                    "baseline original plan and capture identities differ")
            require(utc(aa_plan.get("created_utc")) <= utc(profile["freeze"]["timestamp"]),
                    "baseline side plan was registered after the profile freeze declaration")
            require(collector.same(capture.get("device"), value["device"])
                    and collector.same(capture.get("artifacts"), value["artifacts"])
                    and collector.same(capture.get("parameters"), value["parameters"])
                    and capture.get("workload") == key[0] and capture.get("control") == key[1]
                    and capture.get("backend") == value["backend"], "baseline capture boot/user/device/artifact/config differs")
            collector.identifier(capture.get("pair_id"), "baseline capture pair")
            require(capture.get("side") == side["side"] and (shared_pair is None or capture["pair_id"] == shared_pair), "baseline A/B capture pair/side differs")
            shared_pair = capture["pair_id"]
            captures.add(key)
        pair_ids = baseline_pairs.setdefault(key, set())
        require(shared_pair not in pair_ids, "baseline repeats a pair identifier"); pair_ids.add(shared_pair)
    require(captures == expected, "baseline captures do not cover every frozen group")
    require(baseline_pairs == noise_pairs, "source noise pair IDs differ from the baseline capture pair IDs")


def tools(root, expected=None):
    observed = []
    for name in TOOLS:
        item = common.file_pin(root, "tools/performance/" + name)
        loaded = Path(__file__) if name == "loop_control_comparison.py" else Path(sys.modules[name[:-3]].__file__)
        require(common.digest(common.read_input(common.plain_path(loaded), 2 * 1024 * 1024)) == item["sha256"], "loaded comparison tool differs")
        observed.append(item)
    if expected is not None: require(collector.same(expected, observed), "comparison tool sources changed")
    return observed


def check_pairs(root, rows, freeze, created=None, unexecuted=False):
    require(type(rows) is list and len(rows) == len(freeze["workloads"]) * freeze["pairs_per_workload"] <= MAX_PAIRS, "comparison pair count differs")
    groups, ids, paths = {}, set(), set()
    for position, row in enumerate(rows, 1):
        fields(row, {"position", "workload", "order", "plans"}, "comparison row")
        require(type(row["position"]) is int and row["position"] == position and row["workload"] in freeze["workloads"]
                and row["order"] in ("AB", "BA"), "comparison position/workload/order differs")
        fields(row["plans"], {"A", "B"}, "comparison slots")
        pair_values = []
        for side, control in (("A", "A0"), ("B", "A2")):
            reference = row["plans"][side]; raw = pin(root, reference)
            path = common.inside(root, reference["path"])
            require(str(path) not in paths, "comparison side plan reused"); paths.add(str(path))
            _, plan = collector.check_plan(path, reference["sha256"])
            sides.validate_plan(raw)
            if unexecuted: require(not (path.parent / "execution").exists(), "old captures cannot become a new frozen experiment")
            required = {"workload": row["workload"], "control": control, "side": side, "pair_order": row["order"],
                        "backend": freeze["backend"], "parameters": freeze["parameters"], "artifacts": freeze["artifacts"],
                        "artifact_audit": freeze["artifact_audit"], "workload_sha256": freeze["workload_sha256"],
                        "feature_manifest_sha256": freeze["feature_manifest_sha256"], "serial_sha256": freeze["device"]["device_id_sha256"]}
            require(all(collector.same(plan[key], value) for key, value in required.items()), "cross-control side differs from explicit frozen identity")
            require(collector.fixed_input_manifest_sha256(plan) == freeze["fixed_input_manifest_sha256"], "cross-control fixed inputs differ")
            require(all(collector.same(plan["expected_framework"][key], freeze["device"][key]) for key in ("framework_name", "framework_version", "reported_api")), "frozen framework differs")
            require(utc(freeze["frozen_utc"]) <= utc(plan["created_utc"]), "side plan was created before experiment freeze")
            if created is not None: require(utc(plan["created_utc"]) <= utc(created), "side plan created after comparison publication")
            for key in ("run_id", "session_id"):
                require(plan[key] not in ids, "cross-control run/session reused"); ids.add(plan[key])
            pair_values.append(plan)
        first, second = pair_values
        require(first["pair_id"] == second["pair_id"] and first["host_clock_token"] == second["host_clock_token"], "comparison pair/clock declarations differ")
        for key in ("pair_id", "host_clock_token"):
            require(first[key] not in ids, "cross-control pair/clock ID reused"); ids.add(first[key])
        group = groups.setdefault(row["workload"], {"AB": 0, "BA": 0}); group[row["order"]] += 1
    require(set(groups) == set(freeze["workloads"]) and all(counts["AB"] == counts["BA"] == freeze["pairs_per_workload"] // 2 for counts in groups.values()),
            "control order must be balanced within every workload")


def prepare(root, freeze_path, freeze_sha256, inputs_path, inputs_sha256, output):
    root = common.plain_path(root)
    reference = common.file_pin(root, freeze_path, freeze_sha256)
    freeze, _ = check_freeze(root, reference)
    inputs_ref = common.file_pin(root, inputs_path, inputs_sha256)
    inputs = common.parse_capture(pin(root, inputs_ref))
    fields(inputs, {"schema_version", "record_type", "randomization_policy", "pairs"}, "comparison inputs")
    require(type(inputs["schema_version"]) is int and inputs["schema_version"] == 1
            and inputs["record_type"] == "loop_control_comparison_inputs"
            and collector.same(inputs["randomization_policy"], freeze["randomization_policy"]), "comparison registration differs from frozen order policy")
    created = datetime.now(timezone.utc).isoformat()
    require(utc(freeze["frozen_utc"]) <= utc(created), "experiment freeze is in the future")
    check_pairs(root, inputs["pairs"], freeze, created, unexecuted=True)
    source = tools(root)
    output = Path(output).absolute(); common.plain_path(output.parent)
    require(output.is_relative_to(root), "comparison output escapes root")
    plan = {"schema_version": 1, "record_type": "loop_control_comparison_plan", "status": "prepared_after_freeze",
            "created_utc": created, "comparison_id": str(uuid.uuid4()), "evidence_root": str(root),
            "plan_path": (output / "plan.json").relative_to(root).as_posix(), "freeze": reference, "profile": freeze["profile_pin"],
            "inputs": inputs_ref, "scope": "loop_cpu_envelope_only", "formal_gate_enabled": False,
            "controls": {"A": "A0", "B": "A2"}, "pairs": inputs["pairs"], "tools": source}
    output.mkdir(); common.write_json(output / "plan.pending.json", plan); __import__("os").link(output / "plan.pending.json", output / "plan.json")
    return {"result": "comparison_plan_prepared_after_freeze", "plan": str(output / "plan.json"),
            "plan_sha256": common.digest(common.json_bytes(plan)), "performance_gate": "not_evaluated", "ab_authorized": False}


def check_plan(root, path, expected_sha):
    path = common.inside(root, path); plan, raw = common.read_json(path)
    require(common.digest(raw) == expected_sha, "comparison plan changed"); fields(plan, PLAN_FIELDS, "comparison plan")
    for key, value in (("schema_version", 1), ("record_type", "loop_control_comparison_plan"), ("status", "prepared_after_freeze"),
                       ("scope", "loop_cpu_envelope_only"), ("formal_gate_enabled", False), ("controls", {"A": "A0", "B": "A2"})):
        require(collector.same(plan[key], value), "invalid comparison plan: " + key)
    collector.identifier(plan["comparison_id"], "comparison identifier")
    require(common.plain_path(plan["evidence_root"]) == root and common.inside(root, plan["plan_path"]) == path, "comparison plan publication path differs")
    freeze, checked = check_freeze(root, plan["freeze"])
    require(collector.same(plan["profile"], freeze["profile_pin"]), "comparison profile pin differs")
    inputs = common.parse_capture(pin(root, plan["inputs"]))
    require(collector.same(inputs["pairs"], plan["pairs"]) and collector.same(inputs["randomization_policy"], freeze["randomization_policy"]), "registered comparison inputs changed")
    require(utc(freeze["frozen_utc"]) <= utc(plan["created_utc"]), "comparison plan predates freeze")
    tools(root, plan["tools"]); check_pairs(root, plan["pairs"], freeze, plan["created_utc"])
    return plan, freeze, checked


def integer(value, where):
    require(type(value) is int and 0 <= value <= 2**63 - 1, "invalid monotonic clock: " + where)
    return value


def rational(value):
    return None if value is None else {"numerator": str(value.numerator), "denominator": value.denominator}


def verify(root, plan_path, plan_sha256, evidence_path, evidence_sha256):
    root = common.plain_path(root)
    plan, freeze, checked = check_plan(root, plan_path, plan_sha256)
    evidence = common.parse_capture(pin(root, common.file_pin(root, evidence_path, evidence_sha256)))
    fields(evidence, {"schema_version", "record_type", "comparison_plan_sha256", "claim", "pairs"}, "comparison evidence")
    require(type(evidence["schema_version"]) is int and evidence["schema_version"] == 1
            and evidence["record_type"] == "loop_control_comparison_evidence" and evidence["comparison_plan_sha256"] == plan_sha256,
            "comparison evidence registration differs")
    claim = common.parse_capture(pin(root, evidence["claim"]))
    claim_keys = {"schema_version", "record_type", "comparison_id", "comparison_plan_sha256", "freeze_sha256", "profile_sha256",
                  "created_utc", "host_clock_id", "freeze_checked_monotonic_ns", "profile_checked_monotonic_ns", "claim_monotonic_ns", "no_retry"}
    fields(claim, claim_keys, "comparison execution claim")
    for key, value in (("schema_version", 1), ("record_type", "loop_control_comparison_claim"), ("comparison_id", plan["comparison_id"]),
                       ("comparison_plan_sha256", plan_sha256), ("freeze_sha256", plan["freeze"]["sha256"]),
                       ("profile_sha256", plan["profile"]["sha256"]), ("no_retry", True)):
        require(collector.same(claim[key], value), "comparison claim binding differs: " + key)
    collector.identifier(claim["host_clock_id"], "comparison host clock")
    require(utc(plan["created_utc"]) <= utc(claim["created_utc"]), "claim predates comparison registration")
    claim_ns = integer(claim["claim_monotonic_ns"], "claim")
    require(integer(claim["freeze_checked_monotonic_ns"], "freeze check") <= claim_ns
            and integer(claim["profile_checked_monotonic_ns"], "profile check") <= claim_ns, "freeze/profile were not checked before claim")
    require(type(evidence["pairs"]) is list and len(evidence["pairs"]) == len(plan["pairs"]), "missing registered comparisons")
    observations, groups, previous_return, total_raw = [], {}, claim_ns, 0
    for registered, actual in zip(plan["pairs"], evidence["pairs"]):
        fields(actual, {"position", "sides"}, "comparison evidence pair")
        require(actual["position"] == registered["position"], "comparison evidence order differs")
        fields(actual["sides"], {"A", "B"}, "comparison side evidence")
        summaries = {}
        for side in registered["order"]:
            refs = actual["sides"][side]; fields(refs, {*BUNDLE_NAMES, "receipt"}, "side evidence pins")
            bundle = {"plan_bytes": pin(root, registered["plans"][side])}
            for name, key in BUNDLE_NAMES.items():
                raw = pin(root, refs[name], common.MAX_LOG if name == "raw" else 2 * 1024 * 1024)
                if name == "raw":
                    total_raw += len(raw)
                    require(total_raw <= noise.MAX_TOTAL_INPUT, "comparison total raw inputs exceed 128 MiB")
                bundle[key] = raw if name == "raw" else common.parse_capture(raw)
                if name in ("session", "session_manifest"): bundle[name + "_bytes"] = raw
            report, side_plan, capture = sides._side(bundle)
            require(report.get("collection_status") == "collected" and report["result"] in ("reported_only", "observed_session_consistent", "unresolved"), "side raw evidence is incomplete or contradictory")
            require(collector.same(capture["device"], freeze["device"]), "comparison device/boot/user/framework differs from freeze")
            receipt = common.parse_capture(pin(root, refs["receipt"]))
            receipt_keys = {"schema_version", "record_type", "comparison_plan_sha256", "claim_sha256", "freeze_sha256", "profile_sha256",
                            "side_plan_sha256", "run_id", "session_id", "side", "control", "host_clock_id", "host_clock_token",
                            "freeze_checked_monotonic_ns", "profile_checked_monotonic_ns", "gate_monotonic_ns", "collector_return_monotonic_ns"}
            fields(receipt, receipt_keys, "prelaunch freeze receipt")
            expected = {"schema_version": 1, "record_type": "loop_control_comparison_side_receipt", "comparison_plan_sha256": plan_sha256,
                        "claim_sha256": evidence["claim"]["sha256"], "freeze_sha256": plan["freeze"]["sha256"], "profile_sha256": plan["profile"]["sha256"],
                        "side_plan_sha256": registered["plans"][side]["sha256"], "host_clock_id": claim["host_clock_id"],
                        **{key: side_plan[key] for key in ("run_id", "session_id", "side", "control", "host_clock_token")}}
            require(all(collector.same(receipt[key], value) for key, value in expected.items()), "prelaunch receipt binding differs")
            gate = integer(receipt["gate_monotonic_ns"], "launch gate")
            require(previous_return <= integer(receipt["freeze_checked_monotonic_ns"], "freeze recheck") <= gate
                    and previous_return <= integer(receipt["profile_checked_monotonic_ns"], "profile recheck") <= gate
                    and gate <= capture["launch_monotonic_ns"] < capture["terminal_monotonic_ns"]
                    <= integer(receipt["collector_return_monotonic_ns"], "collector return"), "freeze/order/owned-stop timing contradicts registration")
            previous_return = receipt["collector_return_monotonic_ns"]
            values = [Fraction(sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"], sample["iterations"]) for sample in report["session"]["samples"]]
            summaries[side] = {"control": side_plan["control"], "process": capture["process"], "median": statistics.median(values),
                               "zero": any(value == 0 for value in values), "samples": [rational(value) for value in values],
                               "preflight": "not_verified", "source_result": report["result"]}
        require(summaries["A"]["process"]["uid"] == summaries["B"]["process"]["uid"], "control host UID differs")
        first, second = (summaries[side]["process"] for side in registered["order"])
        require(first["start_ticks"] < second["start_ticks"] and (first["pid"], first["start_ticks"]) != (second["pid"], second["start_ticks"]), "control processes reused or out of order")
        a, b = summaries["A"], summaries["B"]
        zero = a["zero"] or b["zero"]
        effect = None if zero or a["median"] == 0 else b["median"] / a["median"] - 1
        item = {"position": registered["position"], "workload": registered["workload"], "order": registered["order"],
                "A0_median_ns_per_top_level_call": rational(a["median"]), "A2_median_ns_per_top_level_call": rational(b["median"]),
                "A2_minus_A0_ns_per_top_level_call": rational(b["median"] - a["median"]), "A2_over_A0_minus_one": rational(effect),
                "source_side_results": {"A0": a["source_result"], "A2": b["source_result"]},
                "result": "unresolved" if effect is None else "descriptive_only", "samples": {"A0": a["samples"], "A2": b["samples"]}}
        observations.append(item); groups.setdefault(registered["workload"], []).append(effect)
    unresolved = any(value is None for values in groups.values() for value in values)
    return {"schema_version": 1, "record_type": "loop_control_comparison_report", "result": "unresolved" if unresolved else "comparison_descriptive_only",
            "comparison_plan_sha256": plan_sha256, "freeze_sha256": plan["freeze"]["sha256"], "profile_check": checked,
            "pairs": observations, "group_median_A2_over_A0_minus_one": {key: None if any(value is None for value in values) else rational(statistics.median(values)) for key, values in groups.items()},
            "confidence_interval": "not_evaluated", "threshold_evaluation": "not_evaluated", "benefit_conclusion": "not_evaluated",
            "frozen_loop_thresholds": freeze["thresholds"],
            "performance_gate": "not_evaluated", "ab_authorized": False, "release_qualified": False,
            "formal_metrics": {key: None for key in FORMAL_METRICS}, "device_authentication": "not_verified",
            "provenance": "pinned global frozen declaration, claims, prelaunch receipts and original side logs cross-checked; producer clocks and freeze precedence are not independently authenticated; full loop envelopes, not callback CPU"}


def main():
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="command", required=True)
    for command in ("prepare", "verify"):
        child = commands.add_parser(command); child.add_argument("--root", type=Path, required=True)
        if command == "prepare":
            for name in ("freeze", "inputs", "output"): child.add_argument("--" + name, type=Path, required=True)
            for name in ("freeze-sha256", "inputs-sha256"): child.add_argument("--" + name, required=True)
        else:
            for name in ("plan", "evidence"): child.add_argument("--" + name, type=Path, required=True)
            for name in ("plan-sha256", "evidence-sha256"): child.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.root, args.freeze, args.freeze_sha256, args.inputs, args.inputs_sha256, args.output) if args.command == "prepare" else verify(args.root, args.plan, args.plan_sha256, args.evidence, args.evidence_sha256)
    except FrozenProfileBlocked as error:
        result = {"result": "blocked_not_frozen", "profile_check": error.report, "performance_gate": "not_evaluated", "ab_authorized": False}
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as error:
        result = {"result": "invalid", "reason": str(error), "performance_gate": "not_evaluated", "ab_authorized": False}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 2 if result["result"] in ("blocked_not_frozen", "unresolved") else 0 if result["result"] in ("comparison_plan_prepared_after_freeze", "comparison_descriptive_only") else 1


if __name__ == "__main__":
    raise SystemExit(main())
