#!/usr/bin/env python3
"""Run a preregistered same-control loop A/A batch once, stopping on the first uncertainty."""

import argparse
import json
import re
import sys
import uuid
from collections import Counter, defaultdict
from pathlib import Path

import analyze_pilot_aa_noise as noise
import collect_loop_pilot as collector
import verify_b10_pair as pair_helper
import verify_loop_pilot_pair as pair


common = collector.common
InvalidRecord = collector.InvalidRecord
MAX_PAIRS, MAX_SIDES = 16, 32
BATCH_FIELDS = {"artifact_audit", "backend", "blocks", "created_device_operations", "device_serial_sha256",
                "expected_framework", "expected_framework_provenance", "noise_analyzer", "pair_verifier", "pairs",
                "parameters", "performance_gate", "record_type", "schema_version", "scope", "seed", "stop_policy"}
EXTRA_TOOLS = ("collect_loop_pilot_batch.py", "verify_loop_pilot_pair.py", "verify_b10_pair.py", "analyze_pilot_aa_noise.py")
_BASE_STATE, _BASE_POWER = common.target_state, common.observe_power


def require(value, message):
    if not value: raise InvalidRecord(message)


def check_runtime(root, batch, first, wrapper_sha256, pair_helper_sha256):
    """Pin all actually imported project sources, including the two absent from the old batch schema."""
    common.valid_hash(wrapper_sha256); common.valid_hash(pair_helper_sha256)
    expected = {item["path"]: item for item in first["pins"]}
    for field, name in (("pair_verifier", "verify_loop_pilot_pair.py"), ("noise_analyzer", "analyze_pilot_aa_noise.py")):
        ref = batch[field]
        require(isinstance(ref, dict) and set(ref) == {"path", "bytes", "sha256"}
                and ref["path"] == "tools/performance/" + name and type(ref["bytes"]) is int, "invalid batch tool pin")
        common.valid_hash(ref["sha256"])
        expected[ref["path"]] = ref
    for name, digest in (("collect_loop_pilot_batch.py", wrapper_sha256), ("verify_b10_pair.py", pair_helper_sha256)):
        path = "tools/performance/" + name
        expected[path] = common.file_pin(root, path, digest)
    result = []
    for name in (*collector.TOOLS, *EXTRA_TOOLS):
        key = "tools/performance/" + name; pin = expected[key]
        require(collector.same(common.file_pin(root, key, pin["sha256"]), pin), "batch runtime source pin changed")
        loaded = Path(__file__) if name == "collect_loop_pilot_batch.py" else Path(sys.modules[name[:-3]].__file__)
        require(common.digest(common.read_input(common.plain_path(loaded), 2 * 1024 * 1024)) == pin["sha256"],
                "actually loaded batch tool differs from pinned source")
        result.append(pin)
    return result


def check_batch(root, batch_path, batch_sha256, wrapper_sha256, pair_helper_sha256):
    root = common.plain_path(root); common.valid_hash(batch_sha256)
    batch_path = common.inside(root, batch_path)
    batch, raw = common.read_json(batch_path)
    require(common.digest(raw) == batch_sha256 and set(batch) == BATCH_FIELDS, "batch bytes or schema changed")
    for key, value in (("schema_version", 1), ("record_type", "loop_pilot_aa_offline_batch_plan"),
                       ("created_device_operations", False), ("performance_gate", "not_evaluated")):
        require(collector.same(batch[key], value), "invalid batch declaration: " + key)
    require(type(batch["blocks"]) is int and batch["blocks"] in (2, 4), "batch supports two or four balanced blocks only")
    require(type(batch["seed"]) is int and 0 <= batch["seed"] < 2**63, "invalid registered batch seed")
    for key in ("scope", "stop_policy", "expected_framework_provenance"):
        require(isinstance(batch[key], str) and 0 < len(batch[key]) <= 4096, "missing batch scope or provenance")
    common.valid_hash(batch["device_serial_sha256"])
    require(batch["backend"] in collector.MODULES, "invalid batch backend")
    collector.guards.framework(batch["expected_framework"], batch["backend"])
    rows = batch["pairs"]
    require(type(rows) is list and len(rows) == batch["blocks"] * 4 <= MAX_PAIRS, "batch pair count exceeds applicable bounds")
    require(not (batch_path.parent / "batch-execution").exists(), "batch already claimed; no replay")
    sequence, plans, identifiers, paths = [], [], [], set()
    groups, blocks = defaultdict(Counter), defaultdict(set)
    for position, row in enumerate(rows, 1):
        require(isinstance(row, dict) and set(row) == {"block", "control", "host_clock_token", "order", "pair_id", "plans", "position", "workload"},
                "invalid batch pair row")
        require(type(row["position"]) is int and row["position"] == position and type(row["block"]) is int
                and row["block"] == (position - 1) // 4 + 1 and row["order"] in ("AB", "BA"), "batch position/block/order changed")
        require(row["workload"] in collector.WORKLOADS and row["control"] in ("A0", "A2"), "unsupported batch group")
        require(isinstance(row["plans"], dict) and set(row["plans"]) == {"A", "B"}, "batch pair needs A and B plans")
        for key in ("pair_id", "host_clock_token"): collector.identifier(row[key], "batch " + key)
        identifiers.extend((row["pair_id"], row["host_clock_token"]))
        group = (row["workload"], row["control"]); groups[group][row["order"]] += 1; blocks[row["block"]].add(group)
        side_plans = {}
        for side in "AB":
            ref = row["plans"][side]
            require(isinstance(ref, dict) and set(ref) == {"path", "run_id", "session_id", "sha256"}, "invalid single-plan reference")
            common.valid_hash(ref["sha256"])
            path = common.inside(root, ref["path"])
            require(path.is_relative_to(batch_path.parent) and str(path) not in paths, "side plan escapes batch directory or repeats")
            paths.add(str(path))
            require(not (path.parent / "execution").exists(), "side already executed; batch cannot resume")
            _, plan = collector.check_plan(path, ref["sha256"])
            pair.validate_plan(common.read_input(path, 2 * 1024 * 1024))
            expected = {"side": side, "pair_order": row["order"], "pair_id": row["pair_id"], "host_clock_token": row["host_clock_token"],
                        "workload": row["workload"], "control": row["control"], "backend": batch["backend"],
                        "run_id": ref["run_id"], "session_id": ref["session_id"], "parameters": batch["parameters"],
                        "serial_sha256": batch["device_serial_sha256"], "expected_framework": batch["expected_framework"],
                        "artifact_audit": batch["artifact_audit"]}
            require(all(collector.same(plan[key], value) for key, value in expected.items()), "single-plan identity/input disagrees with batch")
            identifiers.extend((plan["run_id"], plan["session_id"])); plans.append(plan); side_plans[side] = plan
        for side in row["order"]:
            sequence.append({"position": position, "side": side, "plan": row["plans"][side], "configuration": side_plans[side], "status": "not_executed"})
    require(len(identifiers) == len(set(identifiers)) == len(rows) * 6, "batch UUIDs are reused across pairs/processes")
    workloads = {key[0] for key in groups}
    expected_groups = {(workload, control) for workload in workloads for control in ("A0", "A2")}
    require(len(workloads) == 2 and set(groups) == expected_groups and len(groups) == 4, "batch requires exactly four same-control groups")
    require(all(value == Counter(AB=batch["blocks"] // 2, BA=batch["blocks"] // 2) for value in groups.values()), "AB/BA group balance changed")
    require(all(value == expected_groups for value in blocks.values()), "each registered block must contain each group once")
    first = plans[0]
    require(all(collector.fixed_input_manifest_sha256(plan) == collector.fixed_input_manifest_sha256(first) for plan in plans),
            "fixed inputs differ across batch")
    runtime = check_runtime(root, batch, first, wrapper_sha256, pair_helper_sha256)
    return root, batch_path, batch, sequence, runtime


def check_preflight(root, path, expected_sha, batch_sha, first):
    common.valid_hash(expected_sha); path = common.inside(root, path)
    pre, raw = common.read_json(path)
    require(common.digest(raw) == expected_sha, "preflight evidence hash changed")
    for key, value in (("schema", 1), ("record_type", "loop_pilot_batch_preflight"), ("batch_sha256", batch_sha),
                       ("read_only", True), ("host_process_absent", True)):
        require(key in pre and collector.same(pre[key], value), "invalid preflight declaration: " + key)
    common.valid_hash(pre.get("framework_sha256"))
    require(type(pre.get("battery_deci_celsius")) is int and 0 <= pre["battery_deci_celsius"] <= 420, "invalid preflight battery temperature")
    state = pre.get("host_and_module")
    require(isinstance(state, dict) and set(state) == {"device", "host", "module"}, "preflight target state missing")
    device = state["device"]
    require(isinstance(device, dict) and set(device) == {"device_id_sha256", "build_fingerprint_sha256", "boot_id", "android_api", "abi", "android_user"},
            "invalid preflight device fields")
    require(device["device_id_sha256"] == first["serial_sha256"] and type(device["android_user"]) is int and device["android_user"] == 0,
            "preflight device/user differs")
    common.valid_hash(device["device_id_sha256"]); common.valid_hash(device["build_fingerprint_sha256"])
    collector.identifier(device["boot_id"], "preflight boot")
    require(type(device["android_api"]) is int and 1 <= device["android_api"] <= 10000
            and isinstance(device["abi"], str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", device["abi"]), "invalid preflight API/ABI")
    for key in ("host", "module"):
        item, artifact = state[key], first["artifacts"][key]
        require(isinstance(item, dict) and set(item) == {"package", "path", "stat", "sha256", "uid"}, "invalid preflight installed-package fields")
        require(item["package"] == artifact["package"] and item["sha256"] == artifact["sha256"]
                and type(item["uid"]) is int and 0 < item["uid"] < 100000, "preflight installed identity differs")
        require(isinstance(item["path"], str) and common.APK_PATH.fullmatch(item["path"])
                and all(part not in ("", ".", "..") for part in item["path"][1:].split("/")), "unsafe preflight APK path")
        require(isinstance(item["stat"], str) and re.fullmatch(r"\d+ \d+ \d+", item["stat"])
                and int(item["stat"].split()[2]) == artifact["bytes"], "preflight installed APK length differs")
    return pre, common.file_pin(root, path, expected_sha)


def bundle(root, reference):
    path = common.inside(root, reference["path"]); directory = path.parent / "execution"
    blobs = {"plan_bytes": common.read_input(path, 2 * 1024 * 1024),
             "raw": common.read_input(common.plain_path(directory / "raw-threadtime.log"), common.MAX_LOG)}
    for name, key in (("capture.json", "capture"), ("result.json", "result"), ("owned-stop.json", "owned_stop"),
                      ("session.json", "session"), ("session-manifest.json", "session_manifest")):
        raw = common.read_input(common.plain_path(directory / name), 2 * 1024 * 1024)
        blobs[key] = common.parse_capture(raw)
        if key in ("session", "session_manifest"): blobs[key + "_bytes"] = raw
    require(common.digest(blobs["plan_bytes"]) == reference["sha256"], "executed plan bytes changed")
    return blobs


def run(root, batch_path, batch_sha256, wrapper_sha256, pair_helper_sha256, serial, preflight_path, preflight_sha256,
        idle_confirmed=False, scope_confirmed=False, execute_fn=collector.execute, transport_factory=collector.TargetAdb):
    require(idle_confirmed is True and scope_confirmed is True, "explicit coordinated idle and scope declarations required")
    require(isinstance(serial, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial), "invalid explicit batch serial")
    root, batch_path, batch, sequence, runtime = check_batch(root, batch_path, batch_sha256, wrapper_sha256, pair_helper_sha256)
    require(common.digest(serial.encode()) == batch["device_serial_sha256"], "batch serial differs from planned target")
    first = sequence[0]["configuration"]
    pre, preflight_pin = check_preflight(root, preflight_path, preflight_sha256, batch_sha256, first)
    require(common.target_state is _BASE_STATE and common.observe_power is _BASE_POWER, "collector boundary functions already wrapped")
    execution_id = str(uuid.uuid4())
    require(execution_id not in {step["configuration"][key] for step in sequence for key in ("pair_id", "host_clock_token", "run_id", "session_id")}, "batch execution identifier collision")
    directory = batch_path.parent / "batch-execution"; directory.mkdir()
    steps = [{"position": step["position"], "side": step["side"], "plan": step["plan"], "status": "not_executed"} for step in sequence]
    common.write_json(directory / "claim.json", {"schema_version": 1, "record_type": "loop_pilot_batch_claim", "execution_id": execution_id,
                      "batch": common.file_pin(root, batch_path, batch_sha256), "preflight": preflight_pin, "runtime_tools": runtime,
                      "wrapper_sha256": wrapper_sha256, "pair_helper_sha256": pair_helper_sha256, "serial_sha256": batch["device_serial_sha256"],
                      "global_baseline_deci_celsius": pre["battery_deci_celsius"], "framework_sha256": pre["framework_sha256"],
                      "idle_confirmation": "caller-reported", "scope_confirmation": "caller-reported fixed host/module/user0", "no_retry": True,
                      "limits": {"pairs": MAX_PAIRS, "sides": MAX_SIDES, "raw_per_side": common.MAX_LOG, "total_noise_raw": noise.MAX_TOTAL_INPUT},
                      "steps": steps, "injected_execution": execute_fn is not collector.execute,
                      "wrappers": {"collect_b07.target_state": "compare every boundary with pinned preflight",
                                   "collect_b07.observe_power": "original guard plus batch rise from T0 < 5 C"},
                      "provenance": "this pinned wrapper changes these two function bindings; source pins do not authenticate all runtime memory or device state"})
    reports, noise_inputs, transports = [], [], []
    failure = None
    total_raw = 0
    def checked_state(adb, selected_serial, plan):
        require(selected_serial == serial, "unexpected target selector inside batch")
        value = _BASE_STATE(adb, selected_serial, plan)
        if not collector.same(value, pre["host_and_module"]): raise common.CollectionError("live batch target differs from pinned preflight")
        return value
    def checked_power(adb, side_directory, label, baseline=None):
        value = _BASE_POWER(adb, side_directory, label, baseline)
        if value - pre["battery_deci_celsius"] >= 50: raise common.CollectionError("global loop batch temperature rise reached 5 C")
        return value
    def tracked_factory(adb_path, selected_serial, side_directory, token):
        require(selected_serial == serial and str(adb_path) == first["adb"]["path"], "transport selector/executable differs")
        require(token == step["configuration"]["host_clock_token"]
                and Path(side_directory) == common.inside(root, step["plan"]["path"]).parent / "execution", "transport plan binding differs")
        value = transport_factory(adb_path, selected_serial, side_directory, token); transports.append(value)
        return value
    common.target_state, common.observe_power = checked_state, checked_power
    try:
        for index, step in enumerate(sequence):
            # Recheck all registered plan bytes and loaded tools before another side can issue ADB.
            require(common.digest(common.read_input(batch_path, 2 * 1024 * 1024)) == batch_sha256, "batch changed during execution")
            check_runtime(root, batch, first, wrapper_sha256, pair_helper_sha256)
            require(collector.same(common.file_pin(root, preflight_pin["path"], preflight_pin["sha256"]), preflight_pin), "preflight evidence drifted")
            require(not any(getattr(value, "poisoned", False) for value in transports), "prior side transport poisoned; batch cannot continue")
            for pending in sequence:
                require(common.digest(common.read_input(common.inside(root, pending["plan"]["path"]), 2 * 1024 * 1024)) == pending["plan"]["sha256"], "registered side plan drifted")
            reference = step["plan"]; path = common.inside(root, reference["path"])
            common.write_json(directory / ("side-%02d-attempt.json" % (index + 1)), {"position": step["position"], "side": step["side"], "plan": reference, "no_retry": True})
            steps[index]["status"] = "attempted"
            count = len(transports)
            result = execute_fn(path, reference["sha256"], serial, idle_confirmed=True, scope_confirmed=True, transport_factory=tracked_factory)
            steps[index].update(status="returned", result=result)
            require(len(transports) == count + 1 and not getattr(transports[-1], "poisoned", False), "side transport absent, repeated, or poisoned")
            expected = "reported_only" if batch["parameters"]["measurement_mode"] == "unobserved" else "observed_session_consistent"
            detail = result.get("reason", "missing side reason") if isinstance(result, dict) else "invalid side result"
            require(isinstance(result, dict) and result.get("result") == expected and result.get("collection_status") == "collected"
                    and result.get("stop_confirmed") is True and result.get("stop_attempted") is True, "side failed, unresolved, or owned stop unconfirmed: " + str(detail))
            require(all(key not in result or type(result[key]) is bool and result[key] is False for key in ("outcome_unknown", "timed_out")), "side outcome unknown or timed out")
            timing = result.get("verification", {}).get("timing", {})
            require(type(timing.get("zero_intervals")) is int and timing["zero_intervals"] == 0, "zero CPU sample retained; batch unresolved and stopped")
            saved = bundle(root, reference)
            require(collector.same(saved["result"], result), "returned result differs from saved side result")
            require(collector.same(saved["capture"]["device"], {**pre["host_and_module"]["device"], **batch["expected_framework"]})
                    and saved["capture"]["process"]["uid"] == pre["host_and_module"]["host"]["uid"], "capture differs from preflight device/UID")
            side_report, _, _ = pair._side(saved)
            common.write_json(directory / ("side-%02d-verification.json" % (index + 1)), side_report)
            require(side_report["result"] == expected and side_report.get("collection_status") == "collected"
                    and side_report.get("summary", {}).get("zero_sample_count") == 0, "saved side verification failed or has zero CPU samples")
            steps[index]["status"] = "collected"
            if (index + 1) % 2: continue
            row = batch["pairs"][step["position"] - 1]
            one, two = bundle(root, row["plans"]["A"]), bundle(root, row["plans"]["B"])
            report = pair.verify_pair(one, two, row["order"])
            common.write_json(directory / ("pair-%02d-verification.json" % row["position"]), report)
            reports.append({"position": row["position"], "workload": row["workload"], "control": row["control"], "order": row["order"], "result": report["result"]})
            require(report["result"] == ("reported_only" if expected == "reported_only" else "observed_pilot_data_complete"), "same-control pair did not verify or contains zero samples")
            total_raw += len(one["raw"]) + len(two["raw"])
            require(total_raw <= noise.MAX_TOTAL_INPUT, "batch raw inputs exceed bounded noise input budget")
            noise_inputs.append({"left": one["session"], "right": two["session"], "left_log": one["raw"], "right_log": two["raw"]})
        description = noise.analyze_pairs(noise_inputs)
        common.write_json(directory / "noise-description.json", description)
    except BaseException as error:
        failure = error
    finally:
        common.target_state, common.observe_power = _BASE_STATE, _BASE_POWER
    result = {"schema_version": 1, "record_type": "loop_pilot_batch_result", "batch_sha256": batch_sha256,
              "result": "batch_stopped" if failure is not None else "batch_records_collected",
              "reason": str(failure) if failure is not None else "all registered same-control records collected; descriptive noise only",
              "steps": steps, "pairs": reports, "not_executed": [item for item in steps if item["status"] == "not_executed"],
              "not_replayed": True, "global_baseline_deci_celsius": pre["battery_deci_celsius"],
              "performance_gate": "not_evaluated", "noise_qualification": "not_evaluated", "device_authentication": "not_verified"}
    common.write_json(directory / "result.json", result)
    if isinstance(failure, (KeyboardInterrupt, SystemExit)): raise failure
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "batch"): parser.add_argument("--" + name, type=Path, required=True)
    for name in ("batch-sha256", "wrapper-sha256", "pair-helper-sha256"): parser.add_argument("--" + name, required=True)
    parser.add_argument("--execute", action="store_true"); parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--serial"); parser.add_argument("--preflight", type=Path); parser.add_argument("--preflight-sha256")
    parser.add_argument("--idle-confirmed", action="store_true"); parser.add_argument("--scope-confirmed", action="store_true")
    args = parser.parse_args()
    try:
        require(not (args.execute and args.dry_run), "choose dry-run or execute")
        if not args.execute:
            _, _, batch, sequence, runtime = check_batch(args.root, args.batch, args.batch_sha256, args.wrapper_sha256, args.pair_helper_sha256)
            result = {"result": "dry_run_checked", "pairs": len(batch["pairs"]), "sides": len(sequence), "runtime_tools": runtime,
                      "device_operations": False, "performance_gate": "not_evaluated"}
        else:
            require(args.preflight is not None and args.preflight_sha256 is not None, "pinned read-only preflight required")
            result = run(args.root, args.batch, args.batch_sha256, args.wrapper_sha256, args.pair_helper_sha256,
                         args.serial, args.preflight, args.preflight_sha256, args.idle_confirmed, args.scope_confirmed)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as error:
        result = {"result": "invalid", "reason": str(error), "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 0 if result["result"] in ("dry_run_checked", "batch_records_collected") else 1


if __name__ == "__main__":
    raise SystemExit(main())
