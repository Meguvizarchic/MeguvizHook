#!/usr/bin/env python3
"""Prepare and execute one bounded B07 A/A pair; never install or certify a device."""

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from b07_adb_transport import TargetAdb
from build_b07_pair_inputs import _write_checked, generate, json_bytes, parse_capture, read_input
from verify_aa_pilot import InvalidRecord, identifier, iter_bench_events
from verify_b07_install import verify


HOST = "io.github.jichuo1.meguviz.hook.benchmarkhost"
MODULES = {"101": "io.github.jichuo1.meguviz.hook.benchmark101",
           "102": "io.github.jichuo1.meguviz.hook.benchmark102"}
TOOLS = ("collect_b07.py", "b07_adb_transport.py", "build_b07_pair_inputs.py",
         "verify_b07_pair.py", "verify_b07_install.py", "verify_aa_pilot.py")
HEX = re.compile(r"[0-9a-f]{64}\Z")
APK_PATH = re.compile(r"/data/app/[A-Za-z0-9._~+/=\-]+/base\.apk\Z")
MAX_LOG = 16 * 1024 * 1024
PLAN_KEYS = {"schema_version", "record_type", "status", "created_utc", "evidence_root", "backend",
             "pair_order", "pair_id", "sides", "host_clock_token", "serial_sha256", "expected_framework",
             "workload", "control", "warmup", "samples", "iterations", "measurement_kind", "android_user",
             "artifacts", "workload_sha256", "feature_manifest_sha256", "adb", "pins", "artifact_audit",
             "side_timeout_seconds", "performance_gate", "device_authentication", "plan_path"}


class CollectionError(RuntimeError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    return _write_checked(path, json_bytes(value))


def plain_path(path):
    """Check existing components, including an explicitly supplied external adb path."""
    path = Path(os.path.abspath(os.fspath(path)))
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise InvalidRecord("symbolic links/reparse points are not accepted: " + str(item))
    return path


def inside(root, value):
    path = Path(value)
    if ".." in path.parts:
        raise InvalidRecord("parent path components are not accepted")
    path = path if path.is_absolute() else root / path
    path = plain_path(path)
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise InvalidRecord("input escapes evidence root") from exc
    return path


def read_json(path, limit=2 * 1024 * 1024):
    raw = read_input(path, limit)
    return parse_capture(raw), raw


def file_pin(root, path, expected=None, external=False):
    path = plain_path(path) if external else inside(root, path)
    raw = read_input(path, 64 * 1024 * 1024)
    value = digest(raw)
    if expected is not None and value != expected:
        raise InvalidRecord("pinned input hash mismatch: " + str(path))
    return {"path": str(path) if external else path.relative_to(root).as_posix(),
            "bytes": len(raw), "sha256": value}


def valid_hash(value):
    if not isinstance(value, str) or not HEX.fullmatch(value):
        raise InvalidRecord("expected a lowercase SHA-256")


def check_loaded_tools(pins):
    by_path = {pin["path"]: pin for pin in pins}
    for name in TOOLS:
        loaded = Path(__file__) if name == "collect_b07.py" else Path(sys.modules[name[:-3]].__file__)
        if digest(read_input(plain_path(loaded), 2 * 1024 * 1024)) != by_path["tools/performance/" + name]["sha256"]:
            raise InvalidRecord("loaded collector/verifier code differs from pinned tool")


def prepare(root, audit_path, audit_sha256, adb_path, backend, serial_sha256, pair_order,
            framework_name, framework_version, reported_api, output):
    """Offline only. All plan timestamps and artifact attestations remain local declarations."""
    root = plain_path(root)
    for value in (audit_sha256, serial_sha256):
        valid_hash(value)
    if backend not in MODULES or pair_order not in ("AB", "BA"):
        raise InvalidRecord("invalid backend or pair order")
    for value in (framework_name, framework_version):
        if (not isinstance(value, str) or not value or value != value.strip()
                or value.lower() == "null" or len(value) > 256 or any(ord(c) < 32 for c in value)):
            raise InvalidRecord("invalid expected framework name/version")
    if type(reported_api) is not int or not 1 <= reported_api <= 10000:
        raise InvalidRecord("invalid expected framework API")
    audit_path = inside(root, audit_path)
    audit, raw = read_json(audit_path)
    if digest(raw) != audit_sha256 or audit.get("status") != "signed_artifacts_locally_verified":
        raise InvalidRecord("signed artifact audit does not match expected digest/status")
    if (not isinstance(audit.get("artifacts"), list)
            or not all(isinstance(item, dict) for item in audit["artifacts"])
            or not isinstance(audit.get("verification"), dict)
            or not isinstance(audit.get("prepared_inputs"), dict)):
        raise InvalidRecord("invalid signed artifact audit structure")
    pins = [file_pin(root, audit_path, audit_sha256)]
    artifacts = {}
    for label, package in (("host", HOST), ("module", MODULES[backend])):
        matches = [x for x in audit.get("artifacts", []) if x.get("package") == package]
        if len(matches) != 1:
            raise InvalidRecord("audit must contain exactly one expected package")
        row = matches[0]
        if (row.get("variant") != "deviceTest" or row.get("signature_verified") is not True
                or row.get("scheme_v2_verified") is not True
                or row.get("runtime_isolation_gate") != "passed"):
            raise InvalidRecord("artifact is not the verified signed deviceTest variant")
        valid_hash(row.get("certificate_sha256"))
        pin = file_pin(root, row["apk"], row["sha256"])
        if type(row.get("bytes")) is not int or row["bytes"] <= 0 or pin["bytes"] != row["bytes"]:
            raise InvalidRecord("artifact size disagrees with audit")
        pins.append(pin)
        artifacts[label] = {**pin, "package": package,
                            "certificate_sha256": row["certificate_sha256"]}
    if artifacts["host"]["certificate_sha256"] != artifacts["module"]["certificate_sha256"]:
        raise InvalidRecord("test artifacts have different signing certificates")
    verification = audit["verification"]
    for key in ("build_log", "signature_checks", "runtime_checks", "static_audit",
                "static_evidence_manifest", "api101_reference_supplement"):
        pins.append(file_pin(root, verification[key], verification[key + "_sha256"]))
    prepared = audit["prepared_inputs"]
    for path_key, hash_key in (("artifact_record", "artifact_record_sha256"),
                               ("workload_sources", "workload_sha256"),
                               ("feature_manifest", "feature_manifest_sha256")):
        pins.append(file_pin(root, prepared[path_key], prepared[hash_key]))
    for name in TOOLS:
        pins.append(file_pin(root, "tools/performance/" + name))
    check_loaded_tools(pins)
    adb = file_pin(root, adb_path, external=True)
    ids = [str(uuid.uuid4()) for _ in range(6)]
    plan = {"schema_version": 1, "record_type": "b07_install_pair_plan",
            "status": "prepared_not_executed", "created_utc": datetime.now(timezone.utc).isoformat(),
            "evidence_root": str(root), "backend": backend, "pair_order": pair_order,
            "pair_id": ids[0], "sides": {"A": {"run_id": ids[1], "session_id": ids[2]},
                                         "B": {"run_id": ids[3], "session_id": ids[4]}},
            "host_clock_token": ids[5], "serial_sha256": serial_sha256,
            "expected_framework": {"framework_name": framework_name,
                                   "framework_version": framework_version, "reported_api": reported_api},
            "workload": "B07", "control": "A2", "warmup": 0, "samples": 1, "iterations": 1,
            "measurement_kind": "install_wall_and_thread_cpu_ns", "android_user": 0,
            "artifacts": artifacts, "workload_sha256": prepared["workload_sha256"],
            "feature_manifest_sha256": prepared["feature_manifest_sha256"],
            "adb": adb, "pins": pins, "artifact_audit": pins[0], "side_timeout_seconds": 60,
            "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    output = Path(os.path.abspath(os.fspath(output)))
    plain_path(output.parent)
    try:
        output.relative_to(root)
    except ValueError as exc:
        raise InvalidRecord("plan output must be inside evidence root") from exc
    plan["plan_path"] = (output / "plan.json").relative_to(root).as_posix()
    output.mkdir()
    pending = output / "plan.pending.json"
    write_json(pending, plan)
    os.link(pending, output / "plan.json")
    return {"result": "prepared_not_executed", "plan": str(output / "plan.json"),
            "plan_sha256": digest(json_bytes(plan)), "performance_gate": "not_evaluated"}


def check_plan(plan_path, expected_sha):
    valid_hash(expected_sha)
    plan_path = plain_path(plan_path)
    plan, raw = read_json(plan_path)
    if digest(raw) != expected_sha:
        raise InvalidRecord("plan digest changed")
    if set(plan) != PLAN_KEYS:
        raise InvalidRecord("unexpected or missing plan fields")
    root = plain_path(plan["evidence_root"])
    inside(root, plan_path)
    if inside(root, plan["plan_path"]) != plan_path:
        raise InvalidRecord("plan copied away from its fixed publication path")
    if (type(plan.get("schema_version")) is not int or plan.get("schema_version") != 1
            or plan.get("record_type") != "b07_install_pair_plan"
            or plan.get("status") != "prepared_not_executed" or plan.get("backend") not in MODULES
            or plan.get("pair_order") not in ("AB", "BA")
            or type(plan.get("android_user")) is not int or plan.get("android_user") != 0
            or (plan.get("workload"), plan.get("control"), plan.get("warmup"), plan.get("samples"),
                plan.get("iterations"), plan.get("measurement_kind"))
            != ("B07", "A2", 0, 1, 1, "install_wall_and_thread_cpu_ns")
            or plan.get("side_timeout_seconds") != 60 or plan.get("performance_gate") != "not_evaluated"):
        raise InvalidRecord("invalid fixed B07 plan")
    for key in ("warmup", "samples", "iterations", "side_timeout_seconds"):
        if type(plan[key]) is not int:
            raise InvalidRecord("invalid integer in fixed plan")
    if not isinstance(plan.get("pins"), list) or not plan["pins"]:
        raise InvalidRecord("missing fixed input pins")
    pinned_names = [x.get("path") for x in plan["pins"] if isinstance(x, dict)]
    if len(pinned_names) != len(plan["pins"]) or len(pinned_names) != len(set(pinned_names)):
        raise InvalidRecord("invalid or repeated input pins")
    if not {"tools/performance/" + name for name in TOOLS} <= set(pinned_names):
        raise InvalidRecord("collector and verifier tool pins are required")
    expected = plan.get("expected_framework")
    if not isinstance(expected, dict) or set(expected) != {"framework_name", "framework_version", "reported_api"}:
        raise InvalidRecord("missing expected framework declaration")
    for key in ("framework_name", "framework_version"):
        value = expected[key]
        if (not isinstance(value, str) or not value or value != value.strip()
                or value.lower() == "null" or len(value) > 256 or any(ord(c) < 32 for c in value)):
            raise InvalidRecord("invalid expected framework declaration")
    if type(expected["reported_api"]) is not int or not 1 <= expected["reported_api"] <= 10000:
        raise InvalidRecord("invalid expected framework API")
    identifiers = [plan["pair_id"], plan["host_clock_token"]]
    if set(plan["sides"]) != {"A", "B"}:
        raise InvalidRecord("plan requires A and B")
    for side in ("A", "B"):
        identifiers += [plan["sides"][side]["run_id"], plan["sides"][side]["session_id"]]
    for value in identifiers:
        identifier(value, "planned identifier")
    if len(set(identifiers)) != 6:
        raise InvalidRecord("planned identifiers must be independent")
    valid_hash(plan["serial_sha256"])
    for pin in plan["pins"]:
        if (set(pin) != {"path", "bytes", "sha256"} or type(pin["bytes"]) is not int
                or pin["bytes"] < 0):
            raise InvalidRecord("invalid input pin")
        valid_hash(pin["sha256"])
        actual = file_pin(root, pin["path"], pin["sha256"])
        if actual["bytes"] != pin["bytes"]:
            raise InvalidRecord("pinned input size changed")
    check_loaded_tools(plan["pins"])
    audit_ref = plan.get("artifact_audit")
    if audit_ref not in plan["pins"]:
        raise InvalidRecord("artifact audit is not pinned")
    audit, _ = read_json(inside(root, audit_ref["path"]))
    if audit.get("status") != "signed_artifacts_locally_verified":
        raise InvalidRecord("artifact audit status changed")
    by_path = {pin["path"]: pin for pin in plan["pins"]}
    for key in ("build_log", "signature_checks", "runtime_checks", "static_audit",
                "static_evidence_manifest", "api101_reference_supplement"):
        path = audit["verification"][key]
        if path not in by_path or by_path[path]["sha256"] != audit["verification"][key + "_sha256"]:
            raise InvalidRecord("plan omits pinned audit dependency")
    for path_key, hash_key in (("artifact_record", "artifact_record_sha256"),
                               ("workload_sources", "workload_sha256"),
                               ("feature_manifest", "feature_manifest_sha256")):
        path = audit["prepared_inputs"][path_key]
        if path not in by_path or by_path[path]["sha256"] != audit["prepared_inputs"][hash_key]:
            raise InvalidRecord("plan omits pinned prepared input")
    adb = file_pin(root, plan["adb"]["path"], plan["adb"]["sha256"], external=True)
    if adb["bytes"] != plan["adb"]["bytes"]:
        raise InvalidRecord("ADB executable size changed")
    for label, package in (("host", HOST), ("module", MODULES[plan["backend"]])):
        item = plan["artifacts"][label]
        if item["package"] != package:
            raise InvalidRecord("unexpected selected package")
        actual = file_pin(root, item["path"], item["sha256"])
        if actual["bytes"] != item["bytes"]:
            raise InvalidRecord("artifact size changed")
        if actual not in plan["pins"]:
            raise InvalidRecord("selected artifact lacks a complete pin")
        matches = [x for x in audit.get("artifacts", []) if x.get("package") == package]
        if (len(matches) != 1 or matches[0].get("sha256") != item["sha256"]
                or matches[0].get("bytes") != item["bytes"] or matches[0].get("apk") != item["path"]
                or matches[0].get("certificate_sha256") != item.get("certificate_sha256")):
            raise InvalidRecord("plan artifact differs from pinned audit")
    if (plan["workload_sha256"] != audit["prepared_inputs"]["workload_sha256"]
            or plan["feature_manifest_sha256"] != audit["prepared_inputs"]["feature_manifest_sha256"]):
        raise InvalidRecord("plan workload/configuration differs from pinned audit")
    return root, plan


def adb_text(adb, category, args, accepted=(0,)):
    return adb.run(category, args, accepted=accepted).stdout.decode("utf-8", "strict").strip()


def root_text(adb, script):
    if "'" in script or "\n" in script:
        raise InvalidRecord("unsafe fixed device script")
    return adb_text(adb, "device_read", ["shell", "su -c '" + script + "'"])


def package_uid(adb, package):
    text = adb_text(adb, "package_read", ["shell", "pm", "list", "packages", "--user", "0", "-U", package])
    matches = re.findall(r"(?m)^package:" + re.escape(package) + r"\s+uid:(\d+)\s*$", text)
    if len(matches) != 1 or int(matches[0]) <= 0:
        raise CollectionError("package UID unavailable or ambiguous")
    return int(matches[0])


def device_identity(adb, serial):
    if adb_text(adb, "identity_read", ["get-serialno"]) != serial:
        raise CollectionError("selected serial observation disagrees")
    if adb_text(adb, "identity_read", ["get-state"]) != "device":
        raise CollectionError("selected device is not ready")
    if adb_text(adb, "identity_read", ["shell", "am", "get-current-user"]) != "0":
        raise CollectionError("current Android user is not zero")
    boot = adb_text(adb, "identity_read", ["shell", "cat", "/proc/sys/kernel/random/boot_id"])
    identifier(boot, "observed boot")
    api = adb_text(adb, "identity_read", ["shell", "getprop", "ro.build.version.sdk"])
    if not api.isdigit() or not 1 <= int(api) <= 10000:
        raise CollectionError("invalid observed Android API")
    fingerprint = adb.run("identity_read", ["shell", "getprop", "ro.build.fingerprint"]).stdout.strip()
    if not fingerprint:
        raise CollectionError("empty fingerprint")
    abi = adb_text(adb, "identity_read", ["shell", "getprop", "ro.product.cpu.abi"])
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", abi):
        raise CollectionError("invalid observed ABI")
    return {"device_id_sha256": digest(serial.encode()), "build_fingerprint_sha256": digest(fingerprint),
            "boot_id": boot, "android_api": int(api), "abi": abi, "android_user": 0}


def installed(adb, artifact):
    package = artifact["package"]
    args = ["shell", "pm", "path", "--user", "0", package]
    raw = adb_text(adb, "package_read", args).splitlines()
    if (len(raw) != 1 or not raw[0].startswith("package:") or not APK_PATH.fullmatch(raw[0][8:])
            or any(part in (".", "..", "") for part in raw[0][9:].split("/"))):
        raise CollectionError("expected one safe installed base APK")
    path = raw[0][8:]
    first = root_text(adb, 'stat -c "%d %i %s" ' + path)
    if not re.fullmatch(r"\d+ \d+ \d+", first):
        raise CollectionError("invalid installed APK stat")
    hashed = root_text(adb, "sha256sum " + path).split()
    if len(hashed) != 2 or hashed[0] != artifact["sha256"] or hashed[1] != path:
        raise CollectionError("installed APK bytes do not match prepared artifact")
    if int(first.split()[2]) != artifact["bytes"]:
        raise CollectionError("installed APK size differs")
    if first != root_text(adb, 'stat -c "%d %i %s" ' + path) or raw != adb_text(adb, "package_read", args).splitlines():
        raise CollectionError("installed APK changed during observation")
    return {"package": package, "path": path, "stat": first, "sha256": hashed[0],
            "uid": package_uid(adb, package)}


def target_state(adb, serial, plan):
    return {"device": device_identity(adb, serial),
            "host": installed(adb, plan["artifacts"]["host"]),
            "module": installed(adb, plan["artifacts"]["module"])}


def observe_power(adb, directory, label, baseline=None):
    battery = adb.run("power_read", ["shell", "dumpsys", "battery"]).stdout
    thermal = adb.run("power_read", ["shell", "dumpsys", "thermalservice"]).stdout
    _write_checked(directory / (label + "-battery.txt"), battery)
    _write_checked(directory / (label + "-thermalservice.txt"), thermal)
    match = re.search(rb"(?m)^\s*temperature:\s*(\d+)\s*\r?$", battery)
    if match is None:
        raise CollectionError("boundary battery temperature unavailable")
    value = int(match.group(1))
    if value > 420 or (baseline is not None and value - baseline >= 50):
        raise CollectionError("boundary temperature exceeded the 42 C / 5 C rise stop rule")
    return value


def host_pid(adb):
    raw = adb_text(adb, "process_read", ["shell", "pidof", HOST], accepted=(0, 1))
    if not raw:
        return None
    if not re.fullmatch(r"[1-9]\d*", raw):
        raise CollectionError("host process is not unique")
    return int(raw)


def process_identity(adb, expected_uid):
    pid = host_pid(adb)
    if pid is None:
        raise CollectionError("launched host process is absent")
    if pid > 2**31 - 1:
        raise CollectionError("invalid host PID")
    def start_ticks():
        raw_stat = root_text(adb, "cat /proc/%d/stat" % pid)
        if not raw_stat.startswith(str(pid) + " (") or raw_stat.rfind(")") <= raw_stat.find("("):
            raise CollectionError("invalid proc stat identity")
        tail = raw_stat[raw_stat.rfind(")") + 1:].split()
        if len(tail) < 20 or not tail[19].isdigit() or int(tail[19]) <= 0:
            raise CollectionError("host process start ticks unavailable")
        return int(tail[19])
    first_ticks = start_ticks()
    cmd = adb.run("process_read", ["exec-out", "cat", "/proc/%d/cmdline" % pid]).stdout
    if cmd.split(b"\0", 1)[0] != HOST.encode():
        raise CollectionError("host command line changed")
    status = root_text(adb, "cat /proc/%d/status" % pid)
    match = re.search(r"(?m)^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$", status)
    if match is None or any(int(x) != expected_uid for x in match.groups()) or package_uid(adb, HOST) != expected_uid:
        raise CollectionError("host UID does not match package and all proc UID fields")
    if start_ticks() != first_ticks or host_pid(adb) != pid:
        raise CollectionError("host process changed during identity observation")
    return {"pid": pid, "uid": expected_uid, "start_ticks": first_ticks, "cmdline": HOST}


def selected_terminal(raw, ids, pair_id, side, pid):
    """Only complete structured events can establish run ownership or its terminal observation."""
    complete = raw[:raw.rfind(b"\n") + 1]
    starts, ends, tid = [], [], None
    for _, prefix_pid, prefix_tid, event in iter_bench_events(complete):
        if event.get("run_id") != ids["run_id"]:
            continue
        for key, value in (("schema", 1), ("pair_id", pair_id), ("session_id", ids["session_id"]),
                           ("side", side), ("pid", pid), ("workload", "B07"), ("control", "A2")):
            if type(event.get(key)) is not type(value) or event[key] != value:
                raise CollectionError("selected run event identity/configuration disagrees")
        if prefix_pid != pid or prefix_tid <= 0 or (tid is not None and tid != prefix_tid):
            raise CollectionError("selected run threadtime identity changed")
        if "tid" in event and (type(event["tid"]) is not int or event["tid"] != prefix_tid):
            raise CollectionError("selected run JSON thread identity changed")
        if tid is None and event.get("event") != "start":
            raise CollectionError("selected run does not begin with its start event")
        tid = prefix_tid
        if event.get("event") == "start":
            for key, value in (("status", "start"), ("warmup", 0), ("samples", 1), ("iterations", 1),
                               ("measurement_kind", "install_wall_and_thread_cpu_ns")):
                if type(event.get(key)) is not type(value) or event[key] != value:
                    raise CollectionError("selected start configuration disagrees")
            starts.append(event)
        if event.get("event") == "end":
            ends.append(event)
    if len(starts) > 1 or len(ends) > 1 or (ends and not starts):
        raise CollectionError("duplicate or unowned terminal event")
    return (tid if starts else None), bool(ends)


def live_log_prefix(path, expected_identity=None):
    """An active stream may append. Bind its inode and reject replacement or size overflow."""
    before = path.lstat()
    if (not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode)
            or getattr(before, "st_file_attributes", 0) & 0x400 or before.st_size > MAX_LOG):
        raise CollectionError("active log is not a bounded ordinary file")
    identity = before.st_dev, before.st_ino
    if expected_identity is not None and identity != expected_identity:
        raise CollectionError("active log file was replaced")
    with path.open("rb") as source:
        opened = os.fstat(source.fileno())
        raw = source.read(MAX_LOG + 1)
    after = path.lstat()
    if ((opened.st_dev, opened.st_ino) != identity or (after.st_dev, after.st_ino) != identity
            or after.st_size > MAX_LOG or len(raw) > MAX_LOG or after.st_size < before.st_size):
        raise CollectionError("active log changed identity, shrank or exceeded capture limit")
    return raw, identity


def retain_capture(capture, directory, summary):
    """Only consume finalized bytes bound to this transport's terminal ledger."""
    raw = read_input(capture.raw_path, MAX_LOG)
    observed = summary.get("stdout") if isinstance(summary, dict) else None
    if (not isinstance(observed, dict) or observed.get("bytes") != len(raw)
            or observed.get("sha256") != digest(raw)):
        raise CollectionError("final log bytes differ from transport terminal record")
    _write_checked(directory / "raw-threadtime.log", raw)
    return raw


def stop_owned(adb, serial, plan, initial, owned, plan_path, plan_sha, directory):
    check_plan(plan_path, plan_sha)
    if target_state(adb, serial, plan) != initial or process_identity(adb, owned["uid"]) != owned:
        raise CollectionError("identity changed; refusing to stop host")
    # Fixed package only, with a new complete ownership observation immediately before the command.
    adb.run("stop_owned_host", ["shell", "am", "force-stop", "--user", "0", HOST])
    if host_pid(adb) is not None:
        raise CollectionError("owned host stop not confirmed; no retry")
    write_json(directory / "owned-stop.json", {"confirmed": True, "owned": owned,
                                               "scope": "fixed benchmark host only"})


def collect_side(adb, serial, plan_path, plan_sha, plan, initial, baseline, side, directory):
    ids = plan["sides"][side]
    owned, capture, stop_attempted = None, None, False
    launched_ns = terminal_ns = tid = None
    failure = None
    result = None
    final_raw_saved = False
    log_identity = None
    try:
        check_plan(plan_path, plan_sha)
        before = target_state(adb, serial, plan)
        write_json(directory / "target-before.json", before)
        if before != initial or host_pid(adb) is not None:
            raise CollectionError("target changed or a pre-existing host is running")
        observe_power(adb, directory, "before", baseline)
        capture = adb.start_logcat("b07")
        if capture.poll() is not None:
            raise CollectionError("logcat exited before launch")
        launched_ns = time.monotonic_ns()
        write_json(directory / "launch-attempt.json", {"run_id": ids["run_id"], "host_monotonic_ns": launched_ns})
        launch = adb.run("launch_b07", ["shell", "am", "start", "-W", "--user", "0", "-n", HOST + "/.MainActivity",
                               "--es", "run_id", ids["run_id"], "--es", "pair_id", plan["pair_id"],
                               "--es", "session_id", ids["session_id"], "--es", "side", side,
                               "--es", "workload", "B07", "--es", "control", "A2", "--ei", "warmup", "0",
                               "--ei", "samples", "1", "--ei", "iterations", "1",
                               "--es", "measurement_mode", "unobserved"])
        if b"Status: ok" not in launch.stdout:
            raise CollectionError("activity launch did not report success; no replay")
        candidate = process_identity(adb, before["host"]["uid"])
        write_json(directory / "process-observed.json", candidate)
        deadline = time.monotonic() + plan["side_timeout_seconds"]
        while time.monotonic() < deadline:
            raw, log_identity = live_log_prefix(capture.raw_path, log_identity)
            observed_tid, ended = selected_terminal(raw, ids, plan["pair_id"], side, candidate["pid"])
            if observed_tid is not None:
                if owned is None:
                    if process_identity(adb, before["host"]["uid"]) != candidate:
                        raise CollectionError("process changed before selected start was bound")
                    write_json(directory / "owned-start.json", {**candidate, "tid": observed_tid,
                               "run_id": ids["run_id"], "boot_id": before["device"]["boot_id"]})
                owned, tid = candidate, observed_tid
            if ended:
                terminal_ns = time.monotonic_ns()
                break
            if capture.poll() is not None:
                raise CollectionError("logcat exited before terminal event")
            time.sleep(0.1)
        if terminal_ns is None:
            raise CollectionError("B07 terminal deadline expired; no replay")
        summary = capture.stop("completed")
        raw = retain_capture(capture, directory, summary)
        final_raw_saved = True
        result = verify(raw, ids["run_id"], plan["pair_id"], ids["session_id"], side,
                        owned["pid"], tid, plan["backend"])
        write_json(directory / "single-verification.json", result)
        if result["result"] not in ("install_record_consistent", "unresolved"):
            raise CollectionError("B07 single-side record failed: " + result["result"])
        for _, _, _, event in iter_bench_events(raw):
            if event.get("run_id") == ids["run_id"] and event.get("event") == "configured":
                for key, expected in plan["expected_framework"].items():
                    if type(event.get(key)) is not type(expected) or event[key] != expected:
                        raise CollectionError("framework report disagrees with prepared expectation")
        after = target_state(adb, serial, plan)
        write_json(directory / "target-after.json", after)
        if after != initial or process_identity(adb, owned["uid"]) != owned:
            raise CollectionError("target/process changed during B07")
        observe_power(adb, directory, "after", baseline)
        declaration = {"schema_version": 1, "record_type": "b07_install_capture_side",
                       "pair_id": plan["pair_id"], **ids, "side": side, "pair_order": plan["pair_order"],
                       "backend": plan["backend"], "pid": owned["pid"], "tid": tid,
                       "host_uid": owned["uid"], "process_start_ticks": owned["start_ticks"],
                       "artifacts": {"host_apk_sha256": plan["artifacts"]["host"]["sha256"],
                                     "module_apk_sha256": plan["artifacts"]["module"]["sha256"],
                                     "workload_sha256": plan["workload_sha256"],
                                     "feature_manifest_sha256": plan["feature_manifest_sha256"]},
                       "device": {**initial["device"], **plan["expected_framework"]},
                       "host_clock_token": plan["host_clock_token"], "launch_monotonic_ns": launched_ns,
                       "terminal_monotonic_ns": terminal_ns}
        write_json(directory / "capture.json", declaration)
        stop_attempted = True
        stop_owned(adb, serial, plan, initial, owned, plan_path, plan_sha, directory)
    except BaseException as exc:
        failure = exc
    finally:
        if capture is not None:
            try:
                summary = capture.stop("collector_finally")
                if not final_raw_saved:
                    retain_capture(capture, directory, summary)
                    final_raw_saved = True
            except BaseException as exc:
                if failure is None:
                    failure = exc
        if failure is not None and owned is not None and not stop_attempted:
            stop_attempted = True
            try:
                stop_owned(adb, serial, plan, initial, owned, plan_path, plan_sha, directory)
            except BaseException as cleanup:
                try:
                    write_json(directory / "cleanup-refused-or-failed.json",
                               {"reason": str(cleanup), "owned": owned, "retry": False})
                except OSError:
                    pass
        write_json(directory / "side-result.json", {"side": side, "run_id": ids["run_id"],
                   "result": "failed" if failure is not None else result["result"],
                   "reason": str(failure) if failure is not None else "record and ownership boundaries checked",
                   "launch_monotonic_ns": launched_ns, "terminal_monotonic_ns": terminal_ns,
                   "owned": owned, "stop_attempted": stop_attempted, "performance_gate": "not_evaluated"})
    if failure is not None:
        raise failure
    return result


def execute(plan_path, plan_sha256, serial, idle_confirmed=False, scope_confirmed=False,
            transport_factory=TargetAdb):
    """Device operations are opt-in and occur only after pins and an exclusive execution claim."""
    if idle_confirmed is not True or scope_confirmed is not True:
        raise InvalidRecord("explicit idle and selected module/host user-0 scope confirmations required")
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,255}", serial):
        raise InvalidRecord("invalid explicit device serial")
    root, plan = check_plan(plan_path, plan_sha256)
    if digest(serial.encode()) != plan["serial_sha256"]:
        raise InvalidRecord("selected serial differs from prepared target")
    execution = Path(plan_path).parent / "execution"
    execution.mkdir()  # Exclusive one-shot claim, even if a prior run failed or was interrupted.
    write_json(execution / "claim.json", {"plan_sha256": plan_sha256,
               "serial_sha256": plan["serial_sha256"], "idle_confirmation": "caller-reported",
               "scope_confirmation": "caller-reported fixed host/selected module/user0", "no_retry": True})
    initial_dir = execution / "initial"; initial_dir.mkdir()
    first_adb = transport_factory(Path(plan["adb"]["path"]), serial, initial_dir, plan["host_clock_token"])
    failure = None
    bundle = None
    side_paths = {}
    try:
        initial = target_state(first_adb, serial, plan)
        write_json(initial_dir / "target.json", initial)
        if host_pid(first_adb) is not None:
            raise CollectionError("pre-existing host; refusing to start or stop it")
        baseline = observe_power(first_adb, initial_dir, "baseline")
        for side in plan["pair_order"]:
            directory = execution / ("side-" + side);directory.mkdir()
            adb = transport_factory(Path(plan["adb"]["path"]), serial, directory, plan["host_clock_token"])
            side_paths[side] = directory
            collect_side(adb, serial, Path(plan_path), plan_sha256, plan, initial, baseline, side, directory)
        check_plan(plan_path, plan_sha256)
    except BaseException as exc:
        failure = exc
    # Preserve all available evidence, including a missing second side after a failure.
    for side in ("A", "B"):
        side_paths.setdefault(side, execution / ("side-" + side))
    try:
        bundle = generate({"one": side_paths["A"] / "capture.json", "two": side_paths["B"] / "capture.json"},
                          {"one": side_paths["A"] / "raw-threadtime.log", "two": side_paths["B"] / "raw-threadtime.log"},
                          execution / "pair-inputs")
    except BaseException as exc:
        if failure is None:
            failure = exc
    result = {"result": "collection_failed" if failure is not None else bundle["result"],
              "reason": str(failure) if failure is not None else "both sides collected and offline checked",
              "plan_sha256": plan_sha256, "pair_report": bundle,
              "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    write_json(execution / "result.json", result)
    if isinstance(failure, (KeyboardInterrupt, SystemExit)):
        raise failure
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    prep.add_argument("--root", type=Path, required=True)
    prep.add_argument("--audit", type=Path, required=True)
    prep.add_argument("--audit-sha256", required=True)
    prep.add_argument("--adb", type=Path, required=True)
    prep.add_argument("--backend", choices=tuple(MODULES), required=True)
    prep.add_argument("--serial-sha256", required=True)
    prep.add_argument("--pair-order", choices=("AB", "BA"), required=True)
    prep.add_argument("--framework-name", required=True)
    prep.add_argument("--framework-version", required=True)
    prep.add_argument("--reported-api", type=int, required=True)
    prep.add_argument("--output", type=Path, required=True)
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
                             args.serial_sha256, args.pair_order, args.framework_name, args.framework_version,
                             args.reported_api, args.output)
        else:
            result = execute(args.plan, args.plan_sha256, args.serial, args.idle_confirmed, args.scope_confirmed)
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, RecursionError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return 0 if result["result"] in ("prepared_not_executed", "install_pair_consistent") else 2 if result["result"] in ("unresolved", "incomplete") else 1


if __name__ == "__main__":
    raise SystemExit(main())
