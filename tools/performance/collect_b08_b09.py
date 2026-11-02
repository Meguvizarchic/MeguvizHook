#!/usr/bin/env python3
"""Prepare and collect one B08 or B09 transaction, with explicit target and no replay."""

import argparse
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import collect_b07 as common
import verify_b08_close
import verify_b09_failure
from b07_adb_transport import TargetAdb
from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events


HOST, MODULES = common.HOST, common.MODULES
TOOLS = ("collect_b08_b09.py", "collect_b07.py", "b07_adb_transport.py",
         "build_b07_pair_inputs.py", "verify_b07_pair.py", "verify_b07_install.py",
         "verify_aa_pilot.py", "verify_b08_close.py", "verify_b09_failure.py")
VERIFY_REFS = ("build_log", "signature_checks", "runtime_checks", "static_audit",
               "static_evidence_manifest")
PREPARED_REFS = (("artifact_record", "artifact_record_sha256"),
                 ("workload_sources", "workload_sha256"),
                 ("feature_manifest", "feature_manifest_sha256"))
KINDS = {"B08": "close_with_inflight_wall_ns", "B09": "failure_call_wall_and_thread_cpu_ns"}
PARAMETERS = {"warmup": 0, "samples": 1, "iterations": 1, "measurement_mode": "unobserved"}
PLAN_KEYS = {"schema_version", "record_type", "status", "created_utc", "evidence_root", "plan_path",
             "backend", "workload", "control", "failure_case", "side", "run_id", "pair_id", "session_id",
             "host_clock_token", "serial_sha256", "expected_framework", "parameters", "measurement_kind",
             "android_user", "artifacts", "artifact_audit", "workload_sha256", "feature_manifest_sha256",
             "pins", "adb", "timeout_seconds", "performance_gate", "device_authentication"}


def scenario(workload, control, failure_case):
    if workload == "B08":
        if control != "A2" or failure_case is not None:
            raise InvalidRecord("B08 requires A2 and no failure case")
        return 90
    if workload == "B09" and control in ("A0", "A2") and failure_case in ("before", "after", "original"):
        return 60
    raise InvalidRecord("unsupported fixed diagnostic scenario")


def framework(value):
    if not isinstance(value, dict) or set(value) != {"framework_name", "framework_version", "reported_api"}:
        raise InvalidRecord("expected framework declaration is incomplete")
    for key in ("framework_name", "framework_version"):
        text = value[key]
        if (not isinstance(text, str) or not text or text != text.strip() or len(text) > 256
                or text.lower() == "null" or any(ord(c) < 32 for c in text)):
            raise InvalidRecord("invalid expected framework declaration")
    if type(value["reported_api"]) is not int or not 1 <= value["reported_api"] <= 10000:
        raise InvalidRecord("invalid expected framework API")


def loaded_tools(pins):
    by_path = {pin["path"]: pin for pin in pins}
    for name in TOOLS:
        loaded = Path(__file__) if name == "collect_b08_b09.py" else Path(sys.modules[name[:-3]].__file__)
        key = "tools/performance/" + name
        if key not in by_path or common.digest(common.read_input(common.plain_path(loaded), 2 * 1024 * 1024)) != by_path[key]["sha256"]:
            raise InvalidRecord("loaded diagnostic tool differs from fixed input")


def audit_inputs(root, path, expected_sha, backend, workload, control, failure_case):
    """Read the explicitly pinned local attestation; do not execute a signer or a device command."""
    common.valid_hash(expected_sha)
    path = common.inside(root, path)
    audit, raw = common.read_json(path)
    if common.digest(raw) != expected_sha or audit.get("status") != "signed_artifacts_locally_verified":
        raise InvalidRecord("signed artifact audit changed or is not verified")
    support = audit.get("scenario_support")
    if not isinstance(support, dict) or not isinstance(support.get(workload), dict):
        raise InvalidRecord("artifact audit does not declare this scenario")
    selected = support[workload]
    if set(selected) != {"controls", "failure_cases"}:
        raise InvalidRecord("invalid scenario support declaration")
    controls, cases = selected["controls"], selected["failure_cases"]
    allowed_controls = {"A2"} if workload == "B08" else {"A0", "A2"}
    if (not isinstance(controls, list) or not controls
            or any(type(x) is not str or x not in allowed_controls for x in controls)
            or len(controls) != len(set(controls)) or not isinstance(cases, list)
            or any(type(x) is not str or x not in ("before", "after", "original") for x in cases)
            or len(cases) != len(set(cases)) or (workload == "B08" and cases)
            or (workload == "B09" and not cases)):
        raise InvalidRecord("invalid scenario controls or failure cases")
    if control not in controls or (workload == "B09" and failure_case not in cases):
        raise InvalidRecord("artifact audit does not support selected control/case")
    if not isinstance(audit.get("artifacts"), list) or not all(isinstance(x, dict) for x in audit["artifacts"]):
        raise InvalidRecord("invalid artifact audit rows")
    pins = [common.file_pin(root, path, expected_sha)]
    artifacts = {}
    for label, package in (("host", HOST), ("module", MODULES[backend])):
        rows = [x for x in audit["artifacts"] if x.get("package") == package]
        if len(rows) != 1:
            raise InvalidRecord("audit must contain exactly one selected package")
        row = rows[0]
        if (row.get("variant") != "deviceTest" or row.get("signature_verified") is not True
                or row.get("scheme_v2_verified") is not True or row.get("runtime_isolation_gate") != "passed"):
            raise InvalidRecord("selected package lacks signed deviceTest evidence")
        common.valid_hash(row.get("certificate_sha256"))
        pin = common.file_pin(root, row["apk"], row["sha256"])
        if type(row.get("bytes")) is not int or row["bytes"] <= 0 or pin["bytes"] != row["bytes"]:
            raise InvalidRecord("selected artifact size changed")
        pins.append(pin)
        artifacts[label] = {**pin, "package": package, "certificate_sha256": row["certificate_sha256"]}
    if artifacts["host"]["certificate_sha256"] != artifacts["module"]["certificate_sha256"]:
        raise InvalidRecord("selected test certificates differ")
    verification, prepared = audit.get("verification"), audit.get("prepared_inputs")
    if not isinstance(verification, dict) or not isinstance(prepared, dict):
        raise InvalidRecord("audit references are missing")
    refs = list(VERIFY_REFS)
    if "api101_reference_supplement" in verification:
        refs.append("api101_reference_supplement")
    for key in refs:
        pins.append(common.file_pin(root, verification[key], verification[key + "_sha256"]))
    for path_key, hash_key in PREPARED_REFS:
        pins.append(common.file_pin(root, prepared[path_key], prepared[hash_key]))
    feature, _ = common.read_json(common.inside(root, prepared["feature_manifest"]))
    parameters = feature.get("fixed_parameters")
    if (feature.get("scenario_support") != support or not isinstance(parameters, dict)
            or set(parameters) != set(PARAMETERS) or parameters != PARAMETERS
            or any(type(parameters[k]) is not type(v) for k, v in PARAMETERS.items())
            or feature.get("measurement_kinds") != KINDS):
        raise InvalidRecord("feature manifest disagrees with the diagnostic protocol")
    return artifacts, pins, prepared


def prepare(root, audit_path, audit_sha256, adb_path, backend, serial_sha256, workload, control,
            failure_case, side, framework_name, framework_version, reported_api, output):
    timeout = scenario(workload, control, failure_case)
    if backend not in MODULES or side not in ("A", "B"):
        raise InvalidRecord("invalid backend or side")
    root = common.plain_path(root)
    common.valid_hash(serial_sha256)
    expected = {"framework_name": framework_name, "framework_version": framework_version, "reported_api": reported_api}
    framework(expected)
    artifacts, pins, prepared = audit_inputs(root, audit_path, audit_sha256, backend, workload, control, failure_case)
    for name in TOOLS:
        pins.append(common.file_pin(root, "tools/performance/" + name))
    if len({x["path"] for x in pins}) != len(pins):
        raise InvalidRecord("duplicate fixed input paths")
    loaded_tools(pins)
    adb = common.file_pin(root, adb_path, external=True)
    ids = [str(uuid.uuid4()) for _ in range(4)]
    if len(set(ids)) != 4:
        raise InvalidRecord("generated identifiers are not independent")
    output = Path(os.path.abspath(os.fspath(output)))
    common.plain_path(output.parent)
    try:
        location = (output / "plan.json").relative_to(root).as_posix()
    except ValueError as exc:
        raise InvalidRecord("plan output escapes evidence root") from exc
    plan = {"schema_version": 1, "record_type": "b08_b09_single_scenario_plan", "status": "prepared_not_executed",
            "created_utc": datetime.now(timezone.utc).isoformat(), "evidence_root": str(root), "plan_path": location,
            "backend": backend, "workload": workload, "control": control, "failure_case": failure_case,
            "side": side, "run_id": ids[0], "pair_id": ids[1], "session_id": ids[2], "host_clock_token": ids[3],
            "serial_sha256": serial_sha256, "expected_framework": expected, "parameters": dict(PARAMETERS),
            "measurement_kind": KINDS[workload], "android_user": 0, "artifacts": artifacts,
            "artifact_audit": pins[0], "workload_sha256": prepared["workload_sha256"],
            "feature_manifest_sha256": prepared["feature_manifest_sha256"], "pins": pins, "adb": adb,
            "timeout_seconds": timeout, "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    output.mkdir()
    common.write_json(output / "plan.pending.json", plan)
    os.link(output / "plan.pending.json", output / "plan.json")
    return {"result": "prepared_not_executed", "plan": str(output / "plan.json"),
            "plan_sha256": common.digest(common.json_bytes(plan)), "performance_gate": "not_evaluated"}


def check_plan(path, expected_sha):
    common.valid_hash(expected_sha)
    path = common.plain_path(path)
    plan, raw = common.read_json(path)
    if common.digest(raw) != expected_sha or set(plan) != PLAN_KEYS:
        raise InvalidRecord("plan bytes or fields changed")
    if (type(plan["schema_version"]) is not int or plan["schema_version"] != 1
            or plan["record_type"] != "b08_b09_single_scenario_plan" or plan["status"] != "prepared_not_executed"
            or plan["backend"] not in MODULES or plan["side"] not in ("A", "B")
            or type(plan["android_user"]) is not int or plan["android_user"] != 0
            or plan["performance_gate"] != "not_evaluated" or plan["device_authentication"] != "not_verified"):
        raise InvalidRecord("invalid fixed diagnostic plan")
    timeout = scenario(plan["workload"], plan["control"], plan["failure_case"])
    if type(plan["timeout_seconds"]) is not int or plan["timeout_seconds"] != timeout:
        raise InvalidRecord("invalid fixed transaction deadline")
    if (not isinstance(plan["parameters"], dict) or set(plan["parameters"]) != set(PARAMETERS)
            or plan["parameters"] != PARAMETERS
            or any(type(plan["parameters"][k]) is not type(v) for k, v in PARAMETERS.items())
            or plan["measurement_kind"] != KINDS[plan["workload"]]):
        raise InvalidRecord("diagnostic parameters changed")
    root = common.plain_path(plan["evidence_root"])
    if common.inside(root, plan["plan_path"]) != path:
        raise InvalidRecord("plan copied away from fixed publication path")
    framework(plan["expected_framework"])
    common.valid_hash(plan["serial_sha256"])
    ids = [plan[k] for k in ("run_id", "pair_id", "session_id", "host_clock_token")]
    for value in ids:
        identifier(value, "planned identifier")
    if len(set(ids)) != 4:
        raise InvalidRecord("planned identifiers must be independent")
    pins = plan["pins"]
    if not isinstance(pins, list) or not pins:
        raise InvalidRecord("input pins missing")
    names = []
    for pin in pins:
        if (not isinstance(pin, dict) or set(pin) != {"path", "bytes", "sha256"}
                or type(pin["bytes"]) is not int or pin["bytes"] < 0):
            raise InvalidRecord("invalid input pin")
        actual = common.file_pin(root, pin["path"], pin["sha256"])
        if actual != pin:
            raise InvalidRecord("input pin changed")
        names.append(pin["path"])
    if len(names) != len(set(names)) or not {"tools/performance/" + n for n in TOOLS} <= set(names):
        raise InvalidRecord("required tool pins missing or duplicated")
    loaded_tools(pins)
    audit_pin = plan["artifact_audit"]
    if audit_pin not in pins:
        raise InvalidRecord("artifact audit is not pinned")
    artifacts, required, prepared = audit_inputs(root, audit_pin["path"], audit_pin["sha256"], plan["backend"],
                                               plan["workload"], plan["control"], plan["failure_case"])
    if artifacts != plan["artifacts"] or any(x not in pins for x in required):
        raise InvalidRecord("plan artifacts or audit dependencies differ")
    if (plan["workload_sha256"] != prepared["workload_sha256"]
            or plan["feature_manifest_sha256"] != prepared["feature_manifest_sha256"]):
        raise InvalidRecord("workload configuration differs from pinned audit")
    adb = plan["adb"]
    if (not isinstance(adb, dict) or set(adb) != {"path", "bytes", "sha256"}
            or common.file_pin(root, adb["path"], adb["sha256"], external=True) != adb):
        raise InvalidRecord("ADB executable pin changed")
    return root, plan


def selected_terminal(raw, plan, pid):
    """Only complete matching start records establish ownership; substring matches never do."""
    complete = raw[:raw.rfind(b"\n") + 1]
    starts, ends, tid = 0, 0, None
    for _, prefix_pid, prefix_tid, event in iter_bench_events(complete):
        if event.get("run_id") != plan["run_id"]:
            continue
        if plan["workload"] == "B08" and "failure_case" in event:
            raise common.CollectionError("B08 record contains a failure-case field")
        values = {"schema": 1, "pair_id": plan["pair_id"], "session_id": plan["session_id"],
                  "side": plan["side"], "pid": pid, "workload": plan["workload"], "control": plan["control"]}
        if plan["workload"] == "B09":
            values["failure_case"] = plan["failure_case"]
        for key, value in values.items():
            if type(event.get(key)) is not type(value) or event[key] != value:
                raise common.CollectionError("selected transaction identity disagrees")
        if prefix_pid != pid or prefix_tid <= 0 or (tid is not None and prefix_tid != tid):
            raise common.CollectionError("selected controller identity changed")
        if "tid" in event and (type(event["tid"]) is not int or event["tid"] != prefix_tid):
            raise common.CollectionError("selected JSON TID disagrees")
        if tid is None and event.get("event") != "start":
            raise common.CollectionError("selected transaction does not begin with start")
        tid = prefix_tid
        if event.get("event") == "start":
            expected = {"status": "start", "warmup": 0, "samples": 1, "iterations": 1,
                        "measurement_kind": plan["measurement_kind"]}
            for key, value in expected.items():
                if type(event.get(key)) is not type(value) or event[key] != value:
                    raise common.CollectionError("selected start configuration disagrees")
            starts += 1
        if event.get("event") == "end":
            ends += 1
    if starts > 1 or ends > 1 or (ends and not starts):
        raise common.CollectionError("duplicate or unowned terminal record")
    return (tid if starts else None), bool(ends)


def verify_raw(raw, plan, owned, tid):
    args = (raw, plan["run_id"], plan["pair_id"], plan["session_id"], plan["side"], owned["pid"], tid,
            plan["backend"])
    if plan["workload"] == "B08":
        return verify_b08_close.verify(*args)
    return verify_b09_failure.verify(*args, plan["control"], plan["failure_case"])


def stop_owned(adb, serial, plan_path, plan_sha, plan, initial, owned, directory):
    check_plan(plan_path, plan_sha)
    if common.target_state(adb, serial, plan) != initial or common.process_identity(adb, owned["uid"]) != owned:
        raise common.CollectionError("identity changed; refusing to stop diagnostic host")
    adb.run("stop_owned_host", ["shell", "am", "force-stop", "--user", "0", HOST])
    if common.host_pid(adb) is not None:
        raise common.CollectionError("owned host stop not confirmed; no retry")
    common.write_json(directory / "owned-stop.json", {"confirmed": True, "owned": owned,
                                                       "scope": "fixed benchmark host only"})


def execute(plan_path, plan_sha256, serial, idle_confirmed=False, scope_confirmed=False,
            transport_factory=TargetAdb):
    if idle_confirmed is not True or scope_confirmed is not True:
        raise InvalidRecord("explicit idle and fixed module/host user0 scope declarations required")
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial):
        raise InvalidRecord("invalid explicit serial")
    root, plan = check_plan(plan_path, plan_sha256)
    if common.digest(serial.encode()) != plan["serial_sha256"]:
        raise InvalidRecord("selected serial differs from prepared target")
    execution = Path(plan_path).parent / "execution"
    execution.mkdir()
    common.write_json(execution / "claim.json", {"plan_sha256": plan_sha256, "no_retry": True,
                      "serial_sha256": plan["serial_sha256"], "idle_confirmation": "caller-reported",
                      "scope_confirmation": "caller-reported fixed module/host/user0"})
    adb = capture = owned = report = initial = None
    tid = launch_ns = terminal_ns = None
    failure = None
    stopped = False
    final_raw_saved = False
    try:
        adb = transport_factory(Path(plan["adb"]["path"]), serial, execution, plan["host_clock_token"])
        initial = common.target_state(adb, serial, plan)
        common.write_json(execution / "target-before.json", initial)
        if common.host_pid(adb) is not None:
            raise common.CollectionError("pre-existing host; no start or stop is allowed")
        baseline = common.observe_power(adb, execution, "before")
        capture = adb.start_logcat("diagnostic")
        if capture.poll() is not None:
            raise common.CollectionError("logcat exited before launch")
        launch_ns = time.monotonic_ns()
        common.write_json(execution / "launch-attempt.json", {"run_id": plan["run_id"], "host_monotonic_ns": launch_ns})
        args = ["shell", "am", "start", "-W", "--user", "0", "-n", HOST + "/.MainActivity",
                "--es", "run_id", plan["run_id"], "--es", "pair_id", plan["pair_id"],
                "--es", "session_id", plan["session_id"], "--es", "side", plan["side"],
                "--es", "workload", plan["workload"], "--es", "control", plan["control"],
                "--ei", "warmup", "0", "--ei", "samples", "1", "--ei", "iterations", "1",
                "--es", "measurement_mode", "unobserved"]
        if plan["workload"] == "B09":
            args += ["--es", "failure_case", plan["failure_case"]]
        launched = adb.run("launch_diagnostic", args)
        if b"Status: ok" not in launched.stdout:
            raise common.CollectionError("activity launch did not report success; no replay")
        candidate = common.process_identity(adb, initial["host"]["uid"])
        common.write_json(execution / "process-observed.json", candidate)
        deadline, log_identity = time.monotonic() + plan["timeout_seconds"], None
        while time.monotonic() < deadline:
            raw, log_identity = common.live_log_prefix(capture.raw_path, log_identity)
            observed_tid, ended = selected_terminal(raw, plan, candidate["pid"])
            if observed_tid is not None:
                if owned is None:
                    if common.process_identity(adb, initial["host"]["uid"]) != candidate:
                        raise common.CollectionError("process changed before its start was bound")
                    common.write_json(execution / "owned-start.json", {**candidate, "tid": observed_tid,
                                      "run_id": plan["run_id"], "boot_id": initial["device"]["boot_id"]})
                owned, tid = candidate, observed_tid
            if ended:
                terminal_ns = time.monotonic_ns()
                break
            if capture.poll() is not None:
                raise common.CollectionError("logcat exited before terminal record")
            time.sleep(0.1)
        if terminal_ns is None:
            raise common.CollectionError("diagnostic terminal deadline expired; no replay")
        raw = common.retain_capture(capture, execution, capture.stop("completed"))
        final_raw_saved = True
        try:
            report = verify_raw(raw, plan, owned, tid)
        except (InvalidRecord, UnicodeError, RecursionError) as error:
            report = {**getattr(error, "partial_report", {}), "result": "invalid", "reason": str(error),
                      "raw_log_sha256": common.digest(raw), "performance_gate": "not_evaluated"}
        common.write_json(execution / "single-verification.json", report)
        expected = "close_record_consistent" if plan["workload"] == "B08" else "failure_record_consistent"
        if report["result"] not in (expected, "unresolved"):
            raise common.CollectionError("diagnostic raw record failed: " + report["result"])
        for _, _, _, event in iter_bench_events(raw):
            if event.get("run_id") == plan["run_id"] and event.get("event") == "configured":
                for key, value in plan["expected_framework"].items():
                    if type(event.get(key)) is not type(value) or event[key] != value:
                        raise common.CollectionError("framework report differs from prepared expectation")
        after = common.target_state(adb, serial, plan)
        common.write_json(execution / "target-after.json", after)
        if after != initial or common.process_identity(adb, owned["uid"]) != owned:
            raise common.CollectionError("device, installed artifact or owned process changed")
        common.observe_power(adb, execution, "after", baseline)
        common.write_json(execution / "capture.json", {"schema_version": 1, "record_type": "b08_b09_capture",
                          "plan_sha256": plan_sha256, "workload": plan["workload"], "control": plan["control"],
                          "failure_case": plan["failure_case"], "run_id": plan["run_id"], "pair_id": plan["pair_id"],
                          "session_id": plan["session_id"], "side": plan["side"], "backend": plan["backend"],
                          "process": owned, "controller_tid": tid, "device": {**initial["device"], **plan["expected_framework"]},
                          "artifacts": plan["artifacts"], "launch_monotonic_ns": launch_ns,
                          "terminal_monotonic_ns": terminal_ns, "raw_log_sha256": common.digest(raw),
                          "device_authentication": "not_verified", "performance_gate": "not_evaluated"})
        stopped = True
        stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, execution)
    except BaseException as error:
        failure = error
    finally:
        if capture is not None:
            try:
                final = capture.stop("collector_finally")
                if not final_raw_saved:
                    common.retain_capture(capture, execution, final)
            except BaseException as error:
                if failure is None:
                    failure = error
        if failure is not None and owned is not None and not stopped:
            stopped = True
            try:
                stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, execution)
            except BaseException as error:
                try:
                    common.write_json(execution / "cleanup-refused-or-failed.json", {"reason": str(error), "owned": owned,
                                      "retry": False})
                except OSError:
                    pass
    result = {"result": "collection_failed" if failure is not None else report["result"],
              "reason": str(failure) if failure is not None else "single diagnostic and ownership boundaries checked",
              "plan_sha256": plan_sha256, "workload": plan["workload"], "control": plan["control"],
              "failure_case": plan["failure_case"], "verification": report, "owned": owned,
              "stop_attempted": stopped, "launch_monotonic_ns": launch_ns, "terminal_monotonic_ns": terminal_ns,
              "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    common.write_json(execution / "result.json", result)
    if isinstance(failure, (KeyboardInterrupt, SystemExit)):
        raise failure
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    for name in ("root", "audit", "adb", "output"):
        prep.add_argument("--" + name, type=Path, required=True)
    for name in ("audit-sha256", "serial-sha256", "framework-name", "framework-version"):
        prep.add_argument("--" + name, required=True)
    prep.add_argument("--backend", choices=tuple(MODULES), required=True)
    prep.add_argument("--workload", choices=tuple(KINDS), required=True)
    prep.add_argument("--control", choices=("A0", "A2"), required=True)
    prep.add_argument("--failure-case", choices=("before", "after", "original"))
    prep.add_argument("--side", choices=("A", "B"), default="A")
    prep.add_argument("--reported-api", type=int, required=True)
    run = commands.add_parser("execute")
    run.add_argument("plan", type=Path)
    run.add_argument("--plan-sha256", required=True)
    run.add_argument("--serial", required=True)
    run.add_argument("--idle-confirmed", action="store_true")
    run.add_argument("--scope-confirmed", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.root, args.audit, args.audit_sha256, args.adb, args.backend,
                             args.serial_sha256, args.workload, args.control, args.failure_case, args.side,
                             args.framework_name, args.framework_version, args.reported_api, args.output)
        else:
            result = execute(args.plan, args.plan_sha256, args.serial, args.idle_confirmed, args.scope_confirmed)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as error:
        result = {"result": "invalid", "reason": str(error), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 0 if result["result"] in ("prepared_not_executed", "close_record_consistent", "failure_record_consistent") else 2 if result["result"] in ("unresolved", "incomplete") else 1


if __name__ == "__main__":
    raise SystemExit(main())
