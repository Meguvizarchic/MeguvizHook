#!/usr/bin/env python3
"""Prepare and collect one B10 resource transaction on an explicit target, without replay."""

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
import verify_b10_resources
from b07_adb_transport import TargetAdb
from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events


HOST, MODULES = common.HOST, common.MODULES
TOOLS = ("collect_b10.py", "collect_b07.py", "b07_adb_transport.py", "build_b07_pair_inputs.py",
         "verify_b07_pair.py", "verify_b07_install.py", "verify_aa_pilot.py", "verify_b10_resources.py")
VERIFY_REFS = ("build_log", "signature_checks", "runtime_checks", "static_audit", "static_evidence_manifest")
PREPARED_REFS = (("artifact_record", "artifact_record_sha256"),
                 ("workload_sources", "workload_sha256"), ("feature_manifest", "feature_manifest_sha256"))
PARAMETERS = {"warmup": 0, "samples": 1, "iterations": 1, "measurement_mode": "unobserved"}
KIND = "process_resource_snapshots"
TIMEOUT = 60
PLAN_KEYS = {"schema_version", "record_type", "status", "created_utc", "evidence_root", "plan_path",
             "backend", "workload", "control", "side", "run_id", "pair_id", "session_id", "host_clock_token",
             "serial_sha256", "expected_framework", "parameters", "measurement_kind", "android_user",
             "artifacts", "artifact_audit", "workload_sha256", "feature_manifest_sha256", "pins", "adb",
             "timeout_seconds", "performance_gate", "device_authentication"}


def fixed_parameters(value):
    if (not isinstance(value, dict) or set(value) != set(PARAMETERS) or value != PARAMETERS
            or any(type(value[key]) is not type(expected) for key, expected in PARAMETERS.items())):
        raise InvalidRecord("B10 requires fixed unobserved 0/1/1 parameters")


def framework(value, backend):
    if not isinstance(value, dict) or set(value) != {"framework_name", "framework_version", "reported_api"}:
        raise InvalidRecord("expected framework declaration is incomplete")
    for key in ("framework_name", "framework_version"):
        text = value[key]
        if (not isinstance(text, str) or not text or text != text.strip() or len(text) > 256
                or text.lower() == "null" or any(ord(c) < 32 for c in text)):
            raise InvalidRecord("invalid expected framework declaration")
    if type(value["reported_api"]) is not int or value["reported_api"] != int(backend):
        raise InvalidRecord("expected framework API must match selected B10 backend")


def loaded_tools(pins):
    by_path = {pin["path"]: pin for pin in pins}
    for name in TOOLS:
        loaded = Path(__file__) if name == "collect_b10.py" else Path(sys.modules[name[:-3]].__file__)
        key = "tools/performance/" + name
        if key not in by_path or common.digest(common.read_input(common.plain_path(loaded), 2 * 1024 * 1024)) != by_path[key]["sha256"]:
            raise InvalidRecord("loaded B10 tool differs from pinned source")


def audit_inputs(root, path, expected_sha, backend, control):
    """Check a pinned signed B10 attestation; no signing, build, or device command is run."""
    common.valid_hash(expected_sha)
    path = common.inside(root, path)
    audit, raw = common.read_json(path)
    if common.digest(raw) != expected_sha or audit.get("status") != "signed_artifacts_locally_verified":
        raise InvalidRecord("signed artifact audit changed or is not verified")
    support, kinds = audit.get("scenario_support"), audit.get("measurement_kinds")
    selected = support.get("B10") if isinstance(support, dict) else None
    if not isinstance(selected, dict) or set(selected) != {"controls", "failure_cases"}:
        raise InvalidRecord("signed audit must explicitly declare B10 support")
    controls, cases = selected["controls"], selected["failure_cases"]
    if (not isinstance(controls, list) or not controls
            or any(type(value) is not str or value not in ("A0", "A2") for value in controls)
            or len(controls) != len(set(controls)) or type(cases) is not list or cases
            or control not in controls):
        raise InvalidRecord("invalid or unsupported B10 control declaration")
    fixed_parameters(audit.get("fixed_parameters"))
    if not isinstance(kinds, dict) or kinds.get("B10") != KIND:
        raise InvalidRecord("signed audit must declare the B10 measurement kind")
    if not isinstance(audit.get("artifacts"), list) or not all(isinstance(row, dict) for row in audit["artifacts"]):
        raise InvalidRecord("invalid artifact audit rows")
    pins, artifacts = [common.file_pin(root, path, expected_sha)], {}
    for label, package in (("host", HOST), ("module", MODULES[backend])):
        rows = [row for row in audit["artifacts"] if row.get("package") == package]
        if len(rows) != 1:
            raise InvalidRecord("audit must contain exactly one selected package")
        row = rows[0]
        if (row.get("variant") != "deviceTest" or row.get("signature_verified") is not True
                or row.get("scheme_v2_verified") is not True or row.get("runtime_isolation_gate") != "passed"):
            raise InvalidRecord("selected package lacks signed deviceTest evidence")
        common.valid_hash(row.get("certificate_sha256"))
        common.valid_hash(row.get("sha256"))
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
        common.valid_hash(verification[key + "_sha256"])
        pins.append(common.file_pin(root, verification[key], verification[key + "_sha256"]))
    for path_key, hash_key in PREPARED_REFS:
        common.valid_hash(prepared[hash_key])
        pins.append(common.file_pin(root, prepared[path_key], prepared[hash_key]))
    feature, _ = common.read_json(common.inside(root, prepared["feature_manifest"]))
    fixed_parameters(feature.get("fixed_parameters"))
    if feature.get("scenario_support") != support or feature.get("measurement_kinds") != kinds:
        raise InvalidRecord("feature manifest disagrees with the B10 audit declarations")
    return artifacts, pins, prepared


def prepare(root, audit_path, audit_sha256, adb_path, backend, serial_sha256, control, side,
            framework_name, framework_version, reported_api, output, pair_id=None, host_clock_token=None):
    if backend not in MODULES or control not in ("A0", "A2") or side not in ("A", "B"):
        raise InvalidRecord("invalid B10 backend, control, or side")
    if (pair_id is None) != (host_clock_token is None):
        raise InvalidRecord("shared pair and host clock identifiers must be provided together")
    if pair_id is not None:
        identifier(pair_id, "shared pair identifier")
        identifier(host_clock_token, "shared host clock identifier")
        if pair_id == host_clock_token:
            raise InvalidRecord("shared pair and host clock identifiers must differ")
    root = common.plain_path(root)
    common.valid_hash(serial_sha256)
    expected = {"framework_name": framework_name, "framework_version": framework_version, "reported_api": reported_api}
    framework(expected, backend)
    artifacts, pins, prepared = audit_inputs(root, audit_path, audit_sha256, backend, control)
    for name in TOOLS:
        pins.append(common.file_pin(root, "tools/performance/" + name))
    if len({pin["path"] for pin in pins}) != len(pins):
        raise InvalidRecord("duplicate fixed input paths")
    loaded_tools(pins)
    adb = common.file_pin(root, adb_path, external=True)
    ids = {"run_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4()),
           "pair_id": str(uuid.uuid4()) if pair_id is None else pair_id,
           "host_clock_token": str(uuid.uuid4()) if host_clock_token is None else host_clock_token}
    if len(set(ids.values())) != 4:
        raise InvalidRecord("B10 identifiers must be independent within a single plan")
    output = Path(os.path.abspath(os.fspath(output)))
    common.plain_path(output.parent)
    try:
        location = (output / "plan.json").relative_to(root).as_posix()
    except ValueError as exc:
        raise InvalidRecord("plan output escapes evidence root") from exc
    plan = {"schema_version": 1, "record_type": "b10_resource_plan", "status": "prepared_not_executed",
            "created_utc": datetime.now(timezone.utc).isoformat(), "evidence_root": str(root), "plan_path": location,
            "backend": backend, "workload": "B10", "control": control, "side": side, **ids,
            "serial_sha256": serial_sha256, "expected_framework": expected, "parameters": dict(PARAMETERS),
            "measurement_kind": KIND, "android_user": 0, "artifacts": artifacts, "artifact_audit": pins[0],
            "workload_sha256": prepared["workload_sha256"], "feature_manifest_sha256": prepared["feature_manifest_sha256"],
            "pins": pins, "adb": adb, "timeout_seconds": TIMEOUT,
            "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
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
        raise InvalidRecord("B10 plan bytes or fields changed")
    if (type(plan["schema_version"]) is not int or plan["schema_version"] != 1
            or plan["record_type"] != "b10_resource_plan" or plan["status"] != "prepared_not_executed"
            or plan["backend"] not in MODULES or plan["workload"] != "B10"
            or plan["control"] not in ("A0", "A2") or plan["side"] not in ("A", "B")
            or type(plan["android_user"]) is not int or plan["android_user"] != 0
            or type(plan["timeout_seconds"]) is not int or plan["timeout_seconds"] != TIMEOUT
            or plan["measurement_kind"] != KIND or plan["performance_gate"] != "not_evaluated"
            or plan["device_authentication"] != "not_verified"):
        raise InvalidRecord("invalid fixed B10 resource plan")
    fixed_parameters(plan["parameters"])
    framework(plan["expected_framework"], plan["backend"])
    root = common.plain_path(plan["evidence_root"])
    if common.inside(root, plan["plan_path"]) != path:
        raise InvalidRecord("B10 plan copied away from its publication path")
    common.valid_hash(plan["serial_sha256"])
    ids = [plan[key] for key in ("run_id", "pair_id", "session_id", "host_clock_token")]
    for value in ids:
        identifier(value, "planned B10 identifier")
    if len(set(ids)) != 4:
        raise InvalidRecord("B10 identifiers must be independent within a single plan")
    pins = plan["pins"]
    if not isinstance(pins, list) or not pins:
        raise InvalidRecord("input pins missing")
    names = []
    for pin in pins:
        if (not isinstance(pin, dict) or set(pin) != {"path", "bytes", "sha256"}
                or type(pin["bytes"]) is not int or pin["bytes"] < 0):
            raise InvalidRecord("invalid input pin")
        common.valid_hash(pin["sha256"])
        if common.file_pin(root, pin["path"], pin["sha256"]) != pin:
            raise InvalidRecord("input pin changed")
        names.append(pin["path"])
    if len(names) != len(set(names)) or not {"tools/performance/" + name for name in TOOLS} <= set(names):
        raise InvalidRecord("required tool pins missing or duplicated")
    loaded_tools(pins)
    audit_pin = plan["artifact_audit"]
    if not isinstance(audit_pin, dict) or audit_pin not in pins:
        raise InvalidRecord("artifact audit is not pinned")
    artifacts, required, prepared = audit_inputs(root, audit_pin["path"], audit_pin["sha256"], plan["backend"], plan["control"])
    if (not isinstance(plan["artifacts"], dict) or set(plan["artifacts"]) != {"host", "module"}
            or any(not isinstance(item, dict) or type(item.get("bytes")) is not int for item in plan["artifacts"].values())
            or artifacts != plan["artifacts"] or any(pin not in pins for pin in required)):
        raise InvalidRecord("plan artifacts or audit dependencies differ")
    if (plan["workload_sha256"] != prepared["workload_sha256"]
            or plan["feature_manifest_sha256"] != prepared["feature_manifest_sha256"]):
        raise InvalidRecord("B10 workload configuration differs from pinned audit")
    adb = plan["adb"]
    if (not isinstance(adb, dict) or set(adb) != {"path", "bytes", "sha256"}
            or type(adb["bytes"]) is not int or adb["bytes"] <= 0):
        raise InvalidRecord("invalid ADB executable pin")
    common.valid_hash(adb["sha256"])
    if common.file_pin(root, adb["path"], adb["sha256"], external=True) != adb:
        raise InvalidRecord("ADB executable pin changed")
    return root, plan


def fixed_input_manifest_sha256(plan):
    value = {"pins": sorted(plan["pins"], key=lambda pin: pin["path"]), "adb_sha256": plan["adb"]["sha256"]}
    raw = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return common.digest(raw)


def process_identity(adb, expected_uid):
    """Bind the full cmdline byte shape between two complete PID/UID/start-tick observations."""
    first = common.process_identity(adb, expected_uid)
    cmdline = adb.run("process_read", ["exec-out", "cat", "/proc/%d/cmdline" % first["pid"]]).stdout
    name = HOST.encode("utf-8")
    padding = cmdline[len(name):]
    if not cmdline.startswith(name) or not padding or any(padding):
        raise common.CollectionError("full host cmdline differs from package plus NUL padding")
    if common.process_identity(adb, expected_uid) != first:
        raise common.CollectionError("host process changed around full cmdline observation")
    return first


def selected_terminal(raw, plan, pid):
    """Only a complete structured start with all fixed identities establishes ownership."""
    complete = raw[:raw.rfind(b"\n") + 1]
    starts, ends, tid = 0, 0, None
    for _, prefix_pid, prefix_tid, event in iter_bench_events(complete):
        if event.get("run_id") != plan["run_id"]:
            continue
        if "failure_case" in event:
            raise common.CollectionError("B10 record contains an inapplicable failure case")
        values = {"schema": 1, "pair_id": plan["pair_id"], "session_id": plan["session_id"],
                  "side": plan["side"], "pid": pid, "workload": "B10", "control": plan["control"]}
        for key, value in values.items():
            if type(event.get(key)) is not type(value) or event[key] != value:
                raise common.CollectionError("selected B10 transaction identity disagrees")
        if prefix_pid != pid or prefix_tid <= 0 or (tid is not None and prefix_tid != tid):
            raise common.CollectionError("selected B10 controller identity changed")
        if "tid" in event and (type(event["tid"]) is not int or event["tid"] != prefix_tid):
            raise common.CollectionError("selected B10 JSON TID disagrees")
        if tid is None and event.get("event") != "start":
            raise common.CollectionError("selected B10 transaction does not begin with start")
        tid = prefix_tid
        if event.get("event") == "start":
            for key, value in {"status": "start", "warmup": 0, "samples": 1, "iterations": 1, "measurement_kind": KIND}.items():
                if type(event.get(key)) is not type(value) or event[key] != value:
                    raise common.CollectionError("selected B10 start configuration disagrees")
            starts += 1
        if event.get("event") == "end":
            ends += 1
    if starts > 1 or ends > 1 or (ends and not starts):
        raise common.CollectionError("duplicate or unowned B10 terminal record")
    return (tid if starts else None), bool(ends)


def stop_owned(adb, serial, plan_path, plan_sha, plan, initial, owned, directory):
    check_plan(plan_path, plan_sha)
    if getattr(adb, "poisoned", False):
        raise common.CollectionError("transport is poisoned; refusing an owned-host stop")
    if common.target_state(adb, serial, plan) != initial or process_identity(adb, owned["uid"]) != owned:
        raise common.CollectionError("identity changed; refusing to stop B10 host")
    adb.run("stop_owned_host", ["shell", "am", "force-stop", "--user", "0", HOST])
    if common.host_pid(adb) is not None:
        raise common.CollectionError("owned host stop not confirmed; no retry")
    common.write_json(directory / "owned-stop.json", {"confirmed": True, "owned": owned, "scope": "fixed benchmark host only"})


def execute(plan_path, plan_sha256, serial, idle_confirmed=False, scope_confirmed=False, transport_factory=TargetAdb):
    if idle_confirmed is not True or scope_confirmed is not True:
        raise InvalidRecord("explicit idle and fixed module/host user0 scope declarations required")
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial):
        raise InvalidRecord("invalid explicit serial")
    root, plan = check_plan(plan_path, plan_sha256)
    if common.digest(serial.encode()) != plan["serial_sha256"]:
        raise InvalidRecord("selected serial differs from prepared target")
    execution = Path(plan_path).parent / "execution"
    execution.mkdir()
    common.write_json(execution / "claim.json", {"schema_version": 1, "record_type": "b10_resource_execution_claim",
                      "plan_sha256": plan_sha256, "no_retry": True, "serial_sha256": plan["serial_sha256"],
                      "host_clock_token": plan["host_clock_token"], "idle_confirmation": "caller-reported",
                      "scope_confirmation": "caller-reported fixed module/host/user0"})
    adb = capture = owned = report = initial = None
    tid = launch_ns = terminal_ns = None
    failure = None
    stop_attempted = stop_confirmed = final_raw_saved = False
    try:
        adb = transport_factory(Path(plan["adb"]["path"]), serial, execution, plan["host_clock_token"])
        initial = common.target_state(adb, serial, plan)
        common.write_json(execution / "target-before.json", initial)
        if common.host_pid(adb) is not None:
            raise common.CollectionError("pre-existing host; no start or stop is allowed")
        baseline = common.observe_power(adb, execution, "before")
        capture = adb.start_logcat("b10")
        if capture.poll() is not None:
            raise common.CollectionError("logcat exited before B10 launch")
        launch_ns = time.monotonic_ns()
        common.write_json(execution / "launch-attempt.json", {"run_id": plan["run_id"], "host_monotonic_ns": launch_ns})
        args = ["shell", "am", "start", "-W", "--user", "0", "-n", HOST + "/.MainActivity",
                "--es", "run_id", plan["run_id"], "--es", "pair_id", plan["pair_id"],
                "--es", "session_id", plan["session_id"], "--es", "side", plan["side"],
                "--es", "workload", "B10", "--es", "control", plan["control"],
                "--ei", "warmup", "0", "--ei", "samples", "1", "--ei", "iterations", "1",
                "--es", "measurement_mode", "unobserved"]
        launched = adb.run("launch_b10", args)
        if b"Status: ok" not in launched.stdout:
            raise common.CollectionError("B10 activity launch did not report success; no replay")
        candidate = process_identity(adb, initial["host"]["uid"])
        common.write_json(execution / "process-observed.json", candidate)
        deadline, log_identity = time.monotonic() + TIMEOUT, None
        while time.monotonic() < deadline:
            raw, log_identity = common.live_log_prefix(capture.raw_path, log_identity)
            observed_tid, ended = selected_terminal(raw, plan, candidate["pid"])
            if observed_tid is not None:
                if owned is None:
                    if process_identity(adb, initial["host"]["uid"]) != candidate:
                        raise common.CollectionError("process changed before its B10 start was bound")
                    common.write_json(execution / "owned-start.json", {**candidate, "tid": observed_tid,
                                      "run_id": plan["run_id"], "boot_id": initial["device"]["boot_id"]})
                owned, tid = candidate, observed_tid
            if ended:
                terminal_ns = time.monotonic_ns()
                break
            if capture.poll() is not None:
                raise common.CollectionError("logcat exited before B10 terminal record")
            time.sleep(0.1)
        if terminal_ns is None:
            raise common.CollectionError("B10 terminal deadline expired; no replay")
        raw = common.retain_capture(capture, execution, capture.stop("completed"))
        final_raw_saved = True
        try:
            report = verify_b10_resources.verify(raw, plan["run_id"], plan["pair_id"], plan["session_id"],
                                               plan["side"], owned["pid"], tid, plan["backend"], plan["control"])
        except (InvalidRecord, UnicodeError, RecursionError) as error:
            report = {**getattr(error, "partial_report", {}), "result": "invalid", "reason": str(error),
                      "raw_log_sha256": common.digest(raw), "performance_gate": "not_evaluated",
                      "device_authentication": "not_verified"}
        common.write_json(execution / "single-verification.json", report)
        if report["result"] != "resource_record_consistent":
            raise common.CollectionError("B10 raw resource record failed: " + report["result"])
        for _, _, _, event in iter_bench_events(raw):
            if event.get("run_id") == plan["run_id"] and event.get("event") == "configured":
                for key, value in plan["expected_framework"].items():
                    if type(event.get(key)) is not type(value) or event[key] != value:
                        raise common.CollectionError("framework report differs from prepared B10 expectation")
        after = common.target_state(adb, serial, plan)
        common.write_json(execution / "target-after.json", after)
        if after != initial or process_identity(adb, owned["uid"]) != owned:
            raise common.CollectionError("device, installed artifact, or owned B10 process changed")
        common.observe_power(adb, execution, "after", baseline)
        stop_attempted = True
        stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, execution)
        stop_confirmed = True
        # Successful capture is published only after the guarded owned stop is confirmed.
        common.write_json(execution / "capture.json", {"schema_version": 1, "record_type": "b10_resource_capture",
                          "status": "collected_and_owned_stop_confirmed", "plan_sha256": plan_sha256,
                          "workload": "B10", "control": plan["control"], "backend": plan["backend"],
                          "parameters": plan["parameters"], "measurement_kind": KIND,
                          "run_id": plan["run_id"], "pair_id": plan["pair_id"], "session_id": plan["session_id"],
                          "side": plan["side"], "host_clock_token": plan["host_clock_token"],
                          "serial_sha256": plan["serial_sha256"], "process": owned, "controller_tid": tid,
                          "device": {**initial["device"], **plan["expected_framework"]}, "artifacts": plan["artifacts"],
                          "artifact_audit": plan["artifact_audit"], "workload_sha256": plan["workload_sha256"],
                          "feature_manifest_sha256": plan["feature_manifest_sha256"],
                          "fixed_input_manifest_sha256": fixed_input_manifest_sha256(plan),
                          "launch_monotonic_ns": launch_ns, "terminal_monotonic_ns": terminal_ns,
                          "raw_log_sha256": common.digest(raw), "raw_log_bytes": len(raw),
                          "verification_sha256": common.digest(common.read_input(execution / "single-verification.json", 2 * 1024 * 1024)),
                          "device_authentication": "not_verified", "performance_gate": "not_evaluated"})
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
        if failure is not None and owned is not None and not stop_attempted:
            stop_attempted = True
            try:
                stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, execution)
                stop_confirmed = True
            except BaseException as error:
                try:
                    common.write_json(execution / "cleanup-refused-or-failed.json", {"reason": str(error), "owned": owned,
                                      "retry": False, "outcome_unknown": bool(getattr(error, "outcome_unknown", False))})
                except OSError:
                    pass
    result = {"schema_version": 1, "record_type": "b10_resource_collection_result",
              "result": "collection_failed" if failure is not None else report["result"],
              "reason": str(failure) if failure is not None else "B10 resources and ownership boundaries checked; owned stop confirmed",
              "plan_sha256": plan_sha256, "workload": "B10", "control": plan["control"], "backend": plan["backend"],
              "verification": report, "owned": owned, "stop_attempted": stop_attempted, "stop_confirmed": stop_confirmed,
              "launch_monotonic_ns": launch_ns, "terminal_monotonic_ns": terminal_ns,
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
    prep.add_argument("--control", choices=("A0", "A2"), required=True)
    prep.add_argument("--side", choices=("A", "B"), default="A")
    prep.add_argument("--reported-api", type=int, required=True)
    prep.add_argument("--pair-id")
    prep.add_argument("--host-clock-token")
    run = commands.add_parser("execute")
    run.add_argument("plan", type=Path)
    run.add_argument("--plan-sha256", required=True)
    run.add_argument("--serial", required=True)
    run.add_argument("--idle-confirmed", action="store_true")
    run.add_argument("--scope-confirmed", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.root, args.audit, args.audit_sha256, args.adb, args.backend, args.serial_sha256,
                             args.control, args.side, args.framework_name, args.framework_version, args.reported_api,
                             args.output, args.pair_id, args.host_clock_token)
        else:
            result = execute(args.plan, args.plan_sha256, args.serial, args.idle_confirmed, args.scope_confirmed)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as error:
        result = {"result": "invalid", "reason": str(error), "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 0 if result["result"] in ("prepared_not_executed", "resource_record_consistent") else 1


if __name__ == "__main__":
    raise SystemExit(main())
