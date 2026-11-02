#!/usr/bin/env python3
"""Collect one existing loop pilot with pinned inputs and owned-process guards, without a gate."""

import argparse
import json
import math
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import collect_b07 as common
import collect_b10 as guards
from b07_adb_transport import TargetAdb
from build_aa_pilot_session import build_session
from verify_aa_pilot import IncompleteRecord, InvalidRecord, identifier, iter_bench_events


HOST, MODULES = common.HOST, common.MODULES
WORKLOADS = ("B01", "B02", "B03", "B04", "B05", "B06")
KINDS = {"unobserved": "thread_cpu_envelope_ns", "observed": "thread_cpu_envelope_observed_ns"}
LIMITS = {"warmup_max": 1_000_000, "samples_max": 200, "iterations_max": 1_000_000,
          "total_target_calls_max": 10_000_000,
          "workload_call_multiplier": {workload: 4 if workload == "B06" else 1 for workload in WORKLOADS}}
TOOLS = ("collect_loop_pilot.py", "build_aa_pilot_session.py", *guards.TOOLS)
TIMEOUT = 120
PLAN_KEYS = guards.PLAN_KEYS | {"pair_order"}
fixed_input_manifest_sha256 = guards.fixed_input_manifest_sha256
process_identity = guards.process_identity


def same(left, right):
    return common.json_bytes(left) == common.json_bytes(right)


def parameters(workload, value):
    if workload not in WORKLOADS or not isinstance(value, dict) or set(value) != {"warmup", "samples", "iterations", "measurement_mode"}:
        raise InvalidRecord("invalid loop workload or parameters")
    for key, minimum, maximum in (("warmup", 0, LIMITS["warmup_max"]), ("samples", 1, LIMITS["samples_max"]),
                                  ("iterations", 1, LIMITS["iterations_max"])):
        if type(value[key]) is not int or not minimum <= value[key] <= maximum:
            raise InvalidRecord("loop parameter outside host bounds: " + key)
    if type(value["measurement_mode"]) is not str or value["measurement_mode"] not in KINDS:
        raise InvalidRecord("invalid loop measurement mode")
    if (value["warmup"] + value["samples"] * value["iterations"]) * LIMITS["workload_call_multiplier"][workload] > LIMITS["total_target_calls_max"]:
        raise InvalidRecord("loop request exceeds the host total target-call budget")
    return KINDS[value["measurement_mode"]]


def loaded_tools(pins):
    by_path = {pin["path"]: pin for pin in pins}
    for name in TOOLS:
        loaded = Path(__file__) if name == "collect_loop_pilot.py" else Path(sys.modules[name[:-3]].__file__)
        key = "tools/performance/" + name
        if key not in by_path or common.digest(common.read_input(common.plain_path(loaded), 2 * 1024 * 1024)) != by_path[key]["sha256"]:
            raise InvalidRecord("loaded loop collector dependency differs from pinned source")


def audit_inputs(root, path, expected_sha, backend, workload, control, mode):
    common.valid_hash(expected_sha)
    path = common.inside(root, path)
    audit, raw = common.read_json(path)
    if common.digest(raw) != expected_sha or audit.get("status") != "signed_artifacts_locally_verified":
        raise InvalidRecord("signed loop artifact audit changed or is not verified")
    support = audit.get("loop_support")
    domains = {"workloads": WORKLOADS, "controls": ("A0", "A2"), "measurement_modes": tuple(KINDS), "backends": tuple(MODULES)}
    if not isinstance(support, dict) or set(support) != set(domains):
        raise InvalidRecord("signed audit must explicitly declare loop support")
    for key, allowed in domains.items():
        values = support[key]
        if (type(values) is not list or not values or any(type(value) is not str or value not in allowed for value in values)
                or len(values) != len(set(values))):
            raise InvalidRecord("invalid loop support list: " + key)
    if any(value not in support[key] for key, value in (("workloads", workload), ("controls", control),
                                                       ("measurement_modes", mode), ("backends", backend))):
        raise InvalidRecord("selected loop scenario is not declared by signed audit")
    if not same(audit.get("loop_limits"), LIMITS) or not same(audit.get("loop_measurement_kinds"), KINDS):
        raise InvalidRecord("signed audit loop limits or measurement kinds differ from host protocol")
    if not isinstance(audit.get("artifacts"), list) or not all(isinstance(row, dict) for row in audit["artifacts"]):
        raise InvalidRecord("invalid loop artifact audit rows")
    pins, artifacts = [common.file_pin(root, path, expected_sha)], {}
    for label, package in (("host", HOST), ("module", MODULES[backend])):
        rows = [row for row in audit["artifacts"] if row.get("package") == package]
        if len(rows) != 1:
            raise InvalidRecord("audit must contain exactly one selected loop package")
        row = rows[0]
        if (row.get("variant") != "deviceTest" or row.get("signature_verified") is not True
                or row.get("scheme_v2_verified") is not True or row.get("runtime_isolation_gate") != "passed"):
            raise InvalidRecord("selected loop package lacks signed deviceTest evidence")
        common.valid_hash(row.get("certificate_sha256")); common.valid_hash(row.get("sha256"))
        pin = common.file_pin(root, row["apk"], row["sha256"])
        if type(row.get("bytes")) is not int or row["bytes"] <= 0 or row["bytes"] != pin["bytes"]:
            raise InvalidRecord("selected loop artifact size differs")
        pins.append(pin)
        artifacts[label] = {**pin, "package": package, "certificate_sha256": row["certificate_sha256"]}
    if artifacts["host"]["certificate_sha256"] != artifacts["module"]["certificate_sha256"]:
        raise InvalidRecord("selected loop test certificates differ")
    verification, prepared = audit.get("verification"), audit.get("prepared_inputs")
    if not isinstance(verification, dict) or not isinstance(prepared, dict):
        raise InvalidRecord("loop audit references are missing")
    refs = list(guards.VERIFY_REFS)
    if "api101_reference_supplement" in verification:
        refs.append("api101_reference_supplement")
    for key in refs:
        common.valid_hash(verification[key + "_sha256"])
        pins.append(common.file_pin(root, verification[key], verification[key + "_sha256"]))
    for path_key, hash_key in guards.PREPARED_REFS:
        common.valid_hash(prepared[hash_key])
        pins.append(common.file_pin(root, prepared[path_key], prepared[hash_key]))
    feature, _ = common.read_json(common.inside(root, prepared["feature_manifest"]))
    for key in ("loop_support", "loop_limits", "loop_measurement_kinds"):
        if not same(feature.get(key), audit[key]):
            raise InvalidRecord("feature manifest disagrees with signed loop declaration: " + key)
    return artifacts, pins, prepared


def prepare(root, audit_path, audit_sha256, adb_path, backend, serial_sha256, workload, control, mode,
            warmup, samples, iterations, side, pair_order, framework_name, framework_version, reported_api,
            output, pair_id=None, host_clock_token=None):
    if backend not in MODULES or control not in ("A0", "A2") or side not in ("A", "B") or pair_order not in ("AB", "BA"):
        raise InvalidRecord("invalid loop backend, control, side, or pair order")
    config = {"warmup": warmup, "samples": samples, "iterations": iterations, "measurement_mode": mode}
    kind = parameters(workload, config)
    if (pair_id is None) != (host_clock_token is None):
        raise InvalidRecord("shared pair and host clock identifiers must be provided together")
    if pair_id is not None:
        identifier(pair_id, "shared loop pair identifier"); identifier(host_clock_token, "shared loop clock identifier")
        if pair_id == host_clock_token:
            raise InvalidRecord("shared loop identifiers must differ")
    root = common.plain_path(root); common.valid_hash(serial_sha256)
    expected = {"framework_name": framework_name, "framework_version": framework_version, "reported_api": reported_api}
    guards.framework(expected, backend)
    artifacts, pins, prepared = audit_inputs(root, audit_path, audit_sha256, backend, workload, control, mode)
    pins.extend(common.file_pin(root, "tools/performance/" + name) for name in TOOLS)
    if len({pin["path"] for pin in pins}) != len(pins):
        raise InvalidRecord("duplicate loop input pins")
    loaded_tools(pins)
    adb = common.file_pin(root, adb_path, external=True)
    ids = {"run_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4()),
           "pair_id": str(uuid.uuid4()) if pair_id is None else pair_id,
           "host_clock_token": str(uuid.uuid4()) if host_clock_token is None else host_clock_token}
    if len(set(ids.values())) != 4:
        raise InvalidRecord("single-loop identifiers must be independent")
    output = Path(os.path.abspath(os.fspath(output))); common.plain_path(output.parent)
    try:
        location = (output / "plan.json").relative_to(root).as_posix()
    except ValueError as error:
        raise InvalidRecord("loop plan output escapes evidence root") from error
    plan = {"schema_version": 1, "record_type": "loop_pilot_plan", "status": "prepared_not_executed",
            "created_utc": datetime.now(timezone.utc).isoformat(), "evidence_root": str(root), "plan_path": location,
            "backend": backend, "workload": workload, "control": control, "side": side, "pair_order": pair_order, **ids,
            "serial_sha256": serial_sha256, "expected_framework": expected, "parameters": config, "measurement_kind": kind,
            "android_user": 0, "artifacts": artifacts, "artifact_audit": pins[0], "pins": pins, "adb": adb,
            "workload_sha256": prepared["workload_sha256"], "feature_manifest_sha256": prepared["feature_manifest_sha256"],
            "timeout_seconds": TIMEOUT, "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    output.mkdir(); common.write_json(output / "plan.pending.json", plan)
    os.link(output / "plan.pending.json", output / "plan.json")
    return {"result": "prepared_not_executed", "plan": str(output / "plan.json"),
            "plan_sha256": common.digest(common.json_bytes(plan)), "performance_gate": "not_evaluated"}


def check_plan(path, expected_sha):
    common.valid_hash(expected_sha); path = common.plain_path(path)
    plan, raw = common.read_json(path)
    if common.digest(raw) != expected_sha or set(plan) != PLAN_KEYS:
        raise InvalidRecord("loop plan bytes or fields changed")
    if (type(plan["schema_version"]) is not int or plan["schema_version"] != 1
            or plan["record_type"] != "loop_pilot_plan" or plan["status"] != "prepared_not_executed"
            or plan["backend"] not in MODULES or plan["control"] not in ("A0", "A2")
            or plan["side"] not in ("A", "B") or plan["pair_order"] not in ("AB", "BA")
            or type(plan["android_user"]) is not int or plan["android_user"] != 0
            or type(plan["timeout_seconds"]) is not int or plan["timeout_seconds"] != TIMEOUT
            or plan["performance_gate"] != "not_evaluated" or plan["device_authentication"] != "not_verified"):
        raise InvalidRecord("invalid loop pilot plan")
    if parameters(plan["workload"], plan["parameters"]) != plan["measurement_kind"]:
        raise InvalidRecord("loop measurement kind differs from mode")
    guards.framework(plan["expected_framework"], plan["backend"])
    root = common.plain_path(plan["evidence_root"])
    if common.inside(root, plan["plan_path"]) != path:
        raise InvalidRecord("loop plan copied away from its publication path")
    common.valid_hash(plan["serial_sha256"])
    ids = [plan[key] for key in ("run_id", "pair_id", "session_id", "host_clock_token")]
    for value in ids: identifier(value, "planned loop identifier")
    if len(set(ids)) != 4: raise InvalidRecord("single-loop identifiers must be independent")
    pins = plan["pins"]
    if not isinstance(pins, list) or not pins: raise InvalidRecord("loop input pins missing")
    for pin in pins:
        if (not isinstance(pin, dict) or set(pin) != {"path", "bytes", "sha256"}
                or type(pin["bytes"]) is not int or pin["bytes"] < 0):
            raise InvalidRecord("invalid loop input pin")
        common.valid_hash(pin["sha256"])
        if not same(common.file_pin(root, pin["path"], pin["sha256"]), pin): raise InvalidRecord("loop input pin changed")
    names = [pin["path"] for pin in pins]
    if len(names) != len(set(names)) or not {"tools/performance/" + name for name in TOOLS} <= set(names):
        raise InvalidRecord("loop tool pins missing or duplicated")
    loaded_tools(pins)
    audit = plan["artifact_audit"]
    if not isinstance(audit, dict) or not any(same(audit, pin) for pin in pins): raise InvalidRecord("loop audit is not pinned")
    artifacts, required, prepared = audit_inputs(root, audit["path"], audit["sha256"], plan["backend"], plan["workload"],
                                               plan["control"], plan["parameters"]["measurement_mode"])
    if not same(artifacts, plan["artifacts"]) or any(not any(same(pin, actual) for actual in pins) for pin in required):
        raise InvalidRecord("loop plan artifacts or audit dependencies differ")
    for key in ("workload_sha256", "feature_manifest_sha256"):
        if plan[key] != prepared[key]: raise InvalidRecord("loop prepared-input digest differs")
    adb = plan["adb"]
    if (not isinstance(adb, dict) or set(adb) != {"path", "bytes", "sha256"}
            or type(adb["bytes"]) is not int or adb["bytes"] <= 0): raise InvalidRecord("invalid loop ADB pin")
    common.valid_hash(adb["sha256"])
    if not same(common.file_pin(root, adb["path"], adb["sha256"], external=True), adb): raise InvalidRecord("loop ADB changed")
    return root, plan


def selected_terminal(raw, plan, pid):
    starts, ends, tid = 0, 0, None
    for _, prefix_pid, prefix_tid, event in iter_bench_events(raw[:raw.rfind(b"\n") + 1]):
        if event.get("run_id") != plan["run_id"]: continue
        finite_event(event)
        for key in ("outcome_unknown", "timed_out"):
            if key in event and (type(event[key]) is not bool or event[key] is not False):
                raise InvalidRecord("selected loop event " + key + " must be false boolean")
        expected = {"schema": 1, "pair_id": plan["pair_id"], "session_id": plan["session_id"], "side": plan["side"],
                    "pid": pid, "workload": plan["workload"], "control": plan["control"]}
        if "failure_case" in event or any(type(event.get(key)) is not type(value) or event[key] != value for key, value in expected.items()):
            raise common.CollectionError("selected loop transaction identity disagrees")
        if prefix_pid != pid or prefix_tid <= 0 or (tid is not None and tid != prefix_tid):
            raise common.CollectionError("selected loop controller identity changed")
        if "tid" in event and (type(event["tid"]) is not int or event["tid"] != prefix_tid):
            raise common.CollectionError("selected loop JSON TID disagrees")
        if tid is None and event.get("event") != "start": raise common.CollectionError("loop transaction does not begin with start")
        tid = prefix_tid
        if event.get("event") == "start":
            start = {key: plan["parameters"][key] for key in ("warmup", "samples", "iterations")}
            start.update(status="start", measurement_kind=plan["measurement_kind"])
            if any(type(event.get(key)) is not type(value) or event[key] != value for key, value in start.items()):
                raise common.CollectionError("selected loop start configuration disagrees")
            starts += 1
        if event.get("event") == "end": ends += 1
    if starts > 1 or ends > 1 or (ends and not starts): raise common.CollectionError("duplicate or unowned loop terminal record")
    return (tid if starts else None), bool(ends)


def finite_event(value):
    if isinstance(value, float) and not math.isfinite(value):
        raise InvalidRecord("selected loop event contains a non-finite JSON number")
    if isinstance(value, dict):
        for child in value.values(): finite_event(child)
    elif isinstance(value, list):
        for child in value: finite_event(child)


def session_manifest(plan, initial, owned):
    device = {**initial["device"], **plan["expected_framework"]}
    return {"schema_version": 1, "data_origin": "device_measurement", "backend": plan["backend"],
            "process_start_token": device["boot_id"] + ":" + str(owned["pid"]) + ":" + str(owned["start_ticks"]),
            "pair_order": plan["pair_order"],
            "artifacts": {"host_apk_sha256": plan["artifacts"]["host"]["sha256"],
                          "module_apk_sha256": plan["artifacts"]["module"]["sha256"],
                          "workload_sha256": plan["workload_sha256"], "feature_manifest_sha256": plan["feature_manifest_sha256"]},
            "device": {key: device[key] for key in ("device_id_sha256", "build_fingerprint_sha256", "android_api", "abi",
                                                    "framework_name", "framework_version", "reported_api")}}


def session_verification(raw, session):
    intervals = [sample["thread_cpu_end_ns"] - sample["thread_cpu_start_ns"] for sample in session["samples"]]
    zero = intervals.count(0)
    observed = session["config"]["measurement_kind"] == KINDS["observed"]
    result = "unresolved" if zero == len(intervals) else "observed_session_consistent" if observed else "reported_only"
    reason = ("all sampled CPU intervals are zero; raw endpoints retained" if result == "unresolved" else
              "observed loop session fields and raw events consistent; counters affect the CPU envelope" if observed else
              "loop session fields and raw events consistent; measurement hook remains not_verified")
    return {"schema_version": 1, "record_type": "loop_pilot_single_verification", "result": result, "reason": reason,
            "raw_log_sha256": common.digest(raw), "run_id": session["run_id"], "preflight": "not_verified",
            "timing": {"sample_count": len(intervals), "zero_intervals": zero, "positive_intervals": len(intervals) - zero,
                       "intervals_ns": intervals}, "performance_gate": "not_evaluated", "device_authentication": "not_verified"}


def partial_report(raw, plan, error):
    observations = []
    try:
        for line, pid, tid, event in iter_bench_events(raw):
            if event.get("run_id") != plan["run_id"]: continue
            if len(observations) == 256: break
            values = {key: event[key] for key in ("index", "iterations", "thread_cpu_start_ns", "thread_cpu_end_ns",
                      "original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls", "outcome_unknown", "timed_out")
                      if key in event and (event[key] is None or type(event[key]) in (int, bool))}
            observations.append({"line": line, "pid": pid, "tid": tid,
                                 "event": event.get("event") if event.get("event") in ("start", "preflight", "preflight_post_close",
                                 "configured", "warmup_observation", "sample", "cleanup", "end") else None, "values": values})
    except (InvalidRecord, UnicodeError, RecursionError):
        pass
    return {"schema_version": 1, "record_type": "loop_pilot_single_verification",
            "result": "incomplete" if isinstance(error, IncompleteRecord) else "invalid", "reason": str(error),
            "raw_log_sha256": common.digest(raw), "run_id": plan["run_id"], "preflight": "not_verified",
            "observations": observations, "performance_gate": "not_evaluated", "device_authentication": "not_verified"}


def stop_owned(adb, serial, plan_path, plan_sha, plan, initial, owned, directory):
    check_plan(plan_path, plan_sha)
    if getattr(adb, "poisoned", False): raise common.CollectionError("transport poisoned; no loop stop may be sent")
    if common.target_state(adb, serial, plan) != initial or process_identity(adb, owned["uid"]) != owned:
        raise common.CollectionError("identity changed; refusing to stop loop host")
    adb.run("stop_owned_host", ["shell", "am", "force-stop", "--user", "0", HOST])
    if common.host_pid(adb) is not None: raise common.CollectionError("owned loop host stop not confirmed; no retry")
    common.write_json(directory / "owned-stop.json", {"confirmed": True, "owned": owned, "scope": "fixed benchmark host only"})


def execute(plan_path, plan_sha256, serial, idle_confirmed=False, scope_confirmed=False, transport_factory=TargetAdb):
    if idle_confirmed is not True or scope_confirmed is not True: raise InvalidRecord("explicit idle and fixed user0 scope declarations required")
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial): raise InvalidRecord("invalid explicit serial")
    _, plan = check_plan(plan_path, plan_sha256)
    if common.digest(serial.encode()) != plan["serial_sha256"]: raise InvalidRecord("selected serial differs from prepared loop target")
    directory = Path(plan_path).parent / "execution"; directory.mkdir()
    common.write_json(directory / "claim.json", {"schema_version": 1, "record_type": "loop_pilot_execution_claim",
                      "plan_sha256": plan_sha256, "serial_sha256": plan["serial_sha256"], "no_retry": True,
                      "host_clock_token": plan["host_clock_token"], "idle_confirmation": "caller-reported",
                      "scope_confirmation": "caller-reported fixed module/host/user0"})
    adb = capture = owned = initial = report = session = manifest = None
    tid = launch_ns = terminal_ns = None
    failure = None
    stop_attempted = stop_confirmed = saved_raw = False
    try:
        adb = transport_factory(Path(plan["adb"]["path"]), serial, directory, plan["host_clock_token"])
        initial = common.target_state(adb, serial, plan); common.write_json(directory / "target-before.json", initial)
        if common.host_pid(adb) is not None: raise common.CollectionError("pre-existing host; no loop launch or stop allowed")
        baseline = common.observe_power(adb, directory, "before")
        capture = adb.start_logcat("loop_pilot")
        if capture.poll() is not None: raise common.CollectionError("logcat exited before loop launch")
        launch_ns = time.monotonic_ns(); common.write_json(directory / "launch-attempt.json", {"run_id": plan["run_id"], "host_monotonic_ns": launch_ns})
        args = ["shell", "am", "start", "-W", "--user", "0", "-n", HOST + "/.MainActivity"]
        for key in ("run_id", "pair_id", "session_id", "side", "workload", "control"): args += ["--es", key, plan[key]]
        for key in ("warmup", "samples", "iterations"): args += ["--ei", key, str(plan["parameters"][key])]
        args += ["--es", "measurement_mode", plan["parameters"]["measurement_mode"]]
        if b"Status: ok" not in adb.run("launch_loop_pilot", args).stdout: raise common.CollectionError("loop launch not confirmed; no replay")
        candidate = process_identity(adb, initial["host"]["uid"]); common.write_json(directory / "process-observed.json", candidate)
        deadline, log_identity = time.monotonic() + TIMEOUT, None
        while time.monotonic() < deadline:
            raw, log_identity = common.live_log_prefix(capture.raw_path, log_identity)
            observed_tid, ended = selected_terminal(raw, plan, candidate["pid"])
            if observed_tid is not None:
                if owned is None:
                    if process_identity(adb, initial["host"]["uid"]) != candidate: raise common.CollectionError("loop process changed before start binding")
                    common.write_json(directory / "owned-start.json", {**candidate, "tid": observed_tid, "run_id": plan["run_id"], "boot_id": initial["device"]["boot_id"]})
                owned, tid = candidate, observed_tid
            if ended: terminal_ns = time.monotonic_ns(); break
            if capture.poll() is not None: raise common.CollectionError("logcat exited before loop terminal")
            time.sleep(0.1)
        if terminal_ns is None: raise common.CollectionError("loop terminal deadline expired; no replay")
        raw = common.retain_capture(capture, directory, capture.stop("completed")); saved_raw = True
        try:
            final_tid, final_end = selected_terminal(raw, plan, owned["pid"])
            if final_tid != tid or not final_end: raise InvalidRecord("final loop stream lost its selected start/end")
            manifest = session_manifest(plan, initial, owned)
            session = build_session(raw, plan["run_id"], manifest)
            report = session_verification(raw, session)
        except (IncompleteRecord, InvalidRecord, UnicodeError, RecursionError) as error:
            report = partial_report(raw, plan, error)
        common.write_json(directory / "single-verification.json", report)
        if report["result"] in ("invalid", "incomplete"): raise common.CollectionError("loop raw verification failed: " + report["result"])
        after = common.target_state(adb, serial, plan); common.write_json(directory / "target-after.json", after)
        if after != initial or process_identity(adb, owned["uid"]) != owned: raise common.CollectionError("loop target or owned process changed")
        common.observe_power(adb, directory, "after", baseline)
        stop_attempted = True; stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, directory); stop_confirmed = True
        common.write_json(directory / "session-manifest.json", manifest)
        common.write_json(directory / "session.json", session)
        common.write_json(directory / "capture.json", {"schema_version": 1, "record_type": "loop_pilot_capture",
                          "status": "collected_and_owned_stop_confirmed", "plan_sha256": plan_sha256,
                          **{key: plan[key] for key in ("workload", "control", "backend", "parameters", "measurement_kind", "run_id", "pair_id",
                            "session_id", "side", "pair_order", "host_clock_token", "serial_sha256", "artifacts", "artifact_audit",
                            "workload_sha256", "feature_manifest_sha256")},
                          "process": owned, "controller_tid": tid, "device": {**initial["device"], **plan["expected_framework"]},
                          "fixed_input_manifest_sha256": fixed_input_manifest_sha256(plan), "launch_monotonic_ns": launch_ns,
                          "terminal_monotonic_ns": terminal_ns, "raw_log_sha256": common.digest(raw), "raw_log_bytes": len(raw),
                          "verification_sha256": common.digest(common.read_input(directory / "single-verification.json", 2 * 1024 * 1024)),
                          "session_sha256": common.digest(common.read_input(directory / "session.json", 2 * 1024 * 1024)),
                          "session_manifest_sha256": common.digest(common.read_input(directory / "session-manifest.json", 2 * 1024 * 1024)),
                          "performance_gate": "not_evaluated", "device_authentication": "not_verified"})
    except BaseException as error:
        failure = error
    finally:
        if capture is not None:
            try:
                summary = capture.stop("collector_finally")
                if not saved_raw:
                    raw = common.retain_capture(capture, directory, summary); saved_raw = True
            except BaseException as error:
                if failure is None: failure = error
        if failure is not None and report is None and saved_raw:
            try:
                report = partial_report(raw, plan, failure)
                common.write_json(directory / "single-verification.json", report)
            except BaseException:
                pass  # Preserve the original failure and continue the guarded owned-host cleanup.
        if failure is not None and owned is not None and not stop_attempted:
            stop_attempted = True
            try:
                stop_owned(adb, serial, Path(plan_path), plan_sha256, plan, initial, owned, directory); stop_confirmed = True
            except BaseException as error:
                try:
                    common.write_json(directory / "cleanup-refused-or-failed.json", {"reason": str(error), "owned": owned,
                                      "retry": False, "outcome_unknown": bool(getattr(error, "outcome_unknown", False))})
                except OSError: pass
    result = {"schema_version": 1, "record_type": "loop_pilot_collection_result", "collection_status": "failed" if failure else "collected",
              "result": "collection_failed" if failure else report["result"], "reason": str(failure) if failure else report["reason"],
              "plan_sha256": plan_sha256, "workload": plan["workload"], "control": plan["control"], "backend": plan["backend"],
              "verification": report, "owned": owned, "stop_attempted": stop_attempted, "stop_confirmed": stop_confirmed,
              "launch_monotonic_ns": launch_ns, "terminal_monotonic_ns": terminal_ns,
              "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    common.write_json(directory / "result.json", result)
    if isinstance(failure, (KeyboardInterrupt, SystemExit)): raise failure
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    for name in ("root", "audit", "adb", "output"): prep.add_argument("--" + name, type=Path, required=True)
    for name in ("audit-sha256", "serial-sha256", "framework-name", "framework-version"): prep.add_argument("--" + name, required=True)
    prep.add_argument("--backend", choices=tuple(MODULES), required=True); prep.add_argument("--workload", choices=WORKLOADS, required=True)
    prep.add_argument("--control", choices=("A0", "A2"), required=True); prep.add_argument("--measurement-mode", choices=tuple(KINDS), required=True)
    for name in ("warmup", "samples", "iterations", "reported-api"): prep.add_argument("--" + name, type=int, required=True)
    prep.add_argument("--side", choices=("A", "B"), required=True); prep.add_argument("--pair-order", choices=("AB", "BA"), required=True)
    prep.add_argument("--pair-id"); prep.add_argument("--host-clock-token")
    run = commands.add_parser("execute"); run.add_argument("plan", type=Path); run.add_argument("--plan-sha256", required=True)
    run.add_argument("--serial", required=True); run.add_argument("--idle-confirmed", action="store_true"); run.add_argument("--scope-confirmed", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            result = prepare(args.root, args.audit, args.audit_sha256, args.adb, args.backend, args.serial_sha256,
                             args.workload, args.control, args.measurement_mode, args.warmup, args.samples, args.iterations, args.side,
                             args.pair_order, args.framework_name, args.framework_version, args.reported_api, args.output, args.pair_id, args.host_clock_token)
        else: result = execute(args.plan, args.plan_sha256, args.serial, args.idle_confirmed, args.scope_confirmed)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as error:
        result = {"result": "invalid", "reason": str(error), "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 0 if result["result"] in ("prepared_not_executed", "observed_session_consistent") else 2 if result["result"] in ("reported_only", "unresolved") else 1


if __name__ == "__main__":
    raise SystemExit(main())
