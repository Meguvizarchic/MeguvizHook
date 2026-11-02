#!/usr/bin/env python3
"""Verify one completed preregistered balanced CPU-body diagnostic, read-only.

Only the existing balanced-v1 collector layout is supported.  The caller supplies
the expected plan and inventory digests.  These checks establish internal evidence
consistency, not the authenticity of a device, framework, clock or collector.
"""

import argparse
import ast
import hashlib
import itertools
import json
import os
import random
import re
import stat
import statistics
import sys
import uuid
from contextvars import ContextVar
from datetime import datetime
from pathlib import Path, PurePosixPath

# Running the CLI must not create bytecode files beside the source verifiers.
sys.dont_write_bytecode = True
from verify_cpu_body_pair import InvalidRecord, unique_fields, validate_manifest, verify_pair
from verify_cpu_body_probe import verify as verify_single

HEX64 = re.compile(r"[0-9a-f]{64}\Z")
GROUPS = tuple(itertools.product(("B01", "B02"), ("A0", "A2")))
REPARSE = 0x400  # FILE_ATTRIBUTE_REPARSE_POINT, including Windows junctions.
MAX_FILE = 128 * 1024 * 1024
MAX_FILES = 100000
MAX_COMMAND_CACHE = 16 * 1024 * 1024
MAX_CACHED_COMMAND = 64 * 1024
TEMP = re.compile(rb"(?m)^\s*temperature:\s*(\d+)\s*\r?$")
THERMAL = re.compile(rb"(?m)^Thermal Status:\s*(\d+)\s*\r?$")
ACTIVE_ROOT = ContextVar("evidence_root", default=None)
ACTIVE_INVENTORY = ContextVar("evidence_inventory", default=None)


class Incomplete(ValueError):
    """A required, not-yet-completed capture is absent."""


def fail(message):
    raise InvalidRecord(message)


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha_value(value, label):
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        fail("invalid SHA-256: " + label)


def check_reparse(path):
    try:
        info = path.lstat()
    except FileNotFoundError as exc:
        raise Incomplete("missing input: " + str(path)) from exc
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & REPARSE:
        fail("symlink or reparse point is not permitted: " + str(path))
    return info


def root_path(path):
    """Resolve the explicit trust root, refusing links in its existing components."""
    path = Path(os.path.abspath(os.fspath(path)))
    for part in (path, *path.parents):
        check_reparse(part)
    if not path.is_dir():
        fail("evidence root must be a directory")
    return path.resolve(strict=True)


def within(root, value):
    """Resolve an input, reject parent escapes, links, junctions and root escapes."""
    candidate = Path(value)
    if ".." in candidate.parts:
        fail("parent path components are not permitted")
    candidate = candidate if candidate.is_absolute() else root / candidate
    candidate = Path(os.path.abspath(os.fspath(candidate)))
    try:
        parts = candidate.relative_to(root).parts
    except ValueError as exc:
        raise InvalidRecord("input is outside the evidence root") from exc
    current = root
    for part in parts:
        current = current / part
        check_reparse(current)
    resolved = current.resolve(strict=True)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise InvalidRecord("resolved input escapes the evidence root") from exc
    return resolved


def validate_ref(value):
    """Evidence indices use portable, strict, root-relative forward-slash paths."""
    if (not isinstance(value, str) or not value or "\\" in value or ":" in value
            or value.startswith("/") or any(x in ("", ".", "..") for x in value.split("/"))
            or PurePosixPath(value).is_absolute()):
        fail("invalid indexed relative path")


def ref_path(root, value):
    validate_ref(value)
    return within(root, value)


def identity(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def read_stable_info(path, limit=MAX_FILE, expected_sha=None):
    root = ACTIVE_ROOT.get()
    if root is not None:
        path = within(root, path)
    before_path = check_reparse(path)
    if not stat.S_ISREG(before_path.st_mode) or before_path.st_size > limit:
        fail("input must be a bounded regular file: " + str(path))
    with path.open("rb") as source:
        before = os.fstat(source.fileno())
        data = source.read(limit + 1)
        after = os.fstat(source.fileno())
    after_path = check_reparse(path)
    if (len(data) > limit or identity(before) != identity(after) or identity(before_path) != identity(after_path)
            or identity(before) != identity(before_path) or len(data) != before.st_size):
        fail("input changed while reading: " + str(path))
    inventory = ACTIVE_INVENTORY.get()
    if inventory is not None:
        try:
            path.relative_to(inventory.run)
        except ValueError:
            pass
        else:
            reference = path.relative_to(inventory.root).as_posix()
            registered = inventory.get(reference)
            if registered is None:
                fail("experiment input is absent from inventory: " + reference)
            if expected_sha is not None and expected_sha != registered:
                fail("conflicting expected input hashes: " + reference)
            expected_sha = registered
    if expected_sha is not None and digest_bytes(data) != expected_sha:
        fail("input bytes differ from registered hash: " + str(path))
    return data, identity(after_path)


def read_stable(path, limit=MAX_FILE):
    return read_stable_info(path, limit)[0]


def read_verified(path, expected_sha):
    sha_value(expected_sha, "input")
    return read_stable_info(path, expected_sha=expected_sha)[0]


def parse_json(data, label):
    try:
        def reject_constant(value):
            fail("non-standard JSON constant: " + value)
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_fields,
                          parse_constant=reject_constant)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise InvalidRecord("invalid JSON: " + str(label)) from exc


def load_json(path):
    return parse_json(read_stable(path), path)


def digest_file(path):
    return digest_bytes(read_stable(path))


def uuid_value(value, label):
    try:
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("not canonical")
    except (ValueError, AttributeError, TypeError) as exc:
        raise InvalidRecord("invalid UUID: " + label) from exc


def time_value(value):
    if not isinstance(value, str):
        fail("missing timestamp")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise InvalidRecord("invalid timestamp") from exc
    if result.utcoffset() is None:
        fail("timestamp must have an offset")
    return result


def schedule(seed):
    if type(seed) is not int or not 0 <= seed < 2**64:
        fail("invalid seed")
    groups = list(GROUPS)
    rng = random.Random(seed)
    first, third = set(rng.sample(groups, 2)), set(rng.sample(groups, 2))
    rows = []
    for block in range(4):
        shuffled = groups.copy()
        rng.shuffle(shuffled)
        for within_block, (workload, control) in enumerate(shuffled, 1):
            chosen = (workload, control) in (first if block < 2 else third)
            if block % 2:
                chosen = not chosen
            rows.append({"position": len(rows) + 1, "block": block + 1, "block_position": within_block,
                         "workload": workload, "control": control, "pair_order": "AB" if chosen else "BA"})
    return rows


def list_regular_tree(root, run):
    files, todo = set(), [run]
    while todo:
        directory = within(root, todo.pop())
        check_reparse(directory)
        with os.scandir(directory) as entries:
            for entry in entries:
                path = Path(entry.path)
                info = check_reparse(path)
                if stat.S_ISDIR(info.st_mode):
                    todo.append(path)
                elif stat.S_ISREG(info.st_mode):
                    files.add(path.relative_to(root).as_posix())
                    if len(files) > MAX_FILES:
                        fail("too many evidence files")
                else:
                    fail("non-regular evidence entry: " + str(path))
    return files


class EvidenceInventory(dict):
    """Validated inventory plus bounded command bytes and end-of-read identities."""

    def __init__(self, root, run, inventory_path, inventory_sha):
        super().__init__()
        self.root, self.run = root, run
        self.inventory_path, self.inventory_sha = inventory_path, inventory_sha
        self.directories, self.files, self.commands = {}, {}, {}
        self.command_bytes = 0

    def path(self, reference):
        path = ref_path(self.root, reference)
        try:
            path.relative_to(self.run)
        except ValueError as exc:
            raise InvalidRecord("inventory entry is outside experiment") from exc
        parent = path.parent
        while parent != self.root:
            if parent not in self.directories:
                self.directories[parent] = identity(check_reparse(parent))
            parent = parent.parent
        return path

    def add(self, item):
        path = self.path(item["path"])
        data, file_identity = read_stable_info(path)
        if len(data) != item["bytes"] or digest_bytes(data) != item["sha256"]:
            fail("inventory evidence changed: " + item["path"])
        self[item["path"]] = item["sha256"]
        self.files[path] = file_identity
        if (path.parent == self.run and re.fullmatch(r"adb-\d{4,}\.json", path.name)
                and len(data) <= MAX_CACHED_COMMAND and self.command_bytes + len(data) <= MAX_COMMAND_CACHE):
            self.commands[item["path"]] = data
            self.command_bytes += len(data)

    def finish(self):
        if root_path(self.root) != self.root:
            fail("evidence root changed")
        for directory, expected in self.directories.items():
            info = check_reparse(directory)
            if not stat.S_ISDIR(info.st_mode) or identity(info) != expected or directory.resolve(strict=True) != directory:
                fail("inventory directory changed: " + str(directory))
        for path, expected in self.files.items():
            safe_path = within(self.root, path)
            reference = path.relative_to(self.root).as_posix()
            _, current = read_stable_info(safe_path, expected_sha=self[reference])
            if current != expected:
                fail("inventory file changed: " + str(path))
        if set(self) != list_regular_tree(self.root, self.run):
            fail("inventory does not exactly cover the experiment directory")
        if digest_file(within(self.root, self.inventory_path)) != self.inventory_sha:
            fail("inventory changed during verification")


def verify_inventory(root, run, inventory_path, expected_sha):
    sha_value(expected_sha, "inventory")
    raw = read_stable(inventory_path)
    if digest_bytes(raw) != expected_sha:
        fail("inventory SHA-256 mismatch")
    try:
        inv = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise InvalidRecord("invalid inventory JSON") from exc
    if (not isinstance(inv, dict) or set(inv) != {"schema_version", "scope", "files_count", "files"}
            or type(inv["schema_version"]) is not int or inv["schema_version"] != 1
            or not isinstance(inv["scope"], str) or not isinstance(inv["files"], list)
            or type(inv["files_count"]) is not int or inv["files_count"] != len(inv["files"])
            or len(inv["files"]) > MAX_FILES):
        fail("invalid inventory")
    known = EvidenceInventory(root, run, inventory_path, expected_sha)
    for item in inv["files"]:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            fail("invalid inventory entry")
        sha_value(item["sha256"], "inventory entry")
        if (type(item["bytes"]) is not int or item["bytes"] < 0 or item["bytes"] > MAX_FILE
                or item["path"] in known):
            fail("invalid or duplicate inventory entry")
        known.add(item)
    if set(known) != list_regular_tree(root, run):
        fail("inventory does not exactly cover the experiment directory")
    return known


def sample_stats(report, stage):
    rows = [r for r in report["readings"] if r["ordinal"] >= report["warmup"]]
    if len(rows) != report["samples"]:
        fail("sample count differs from verifier report")
    values = [r[stage + "_cpu_end_ns"] - r[stage + "_cpu_start_ns"] for r in rows]
    first, last = statistics.median(values[:20]), statistics.median(values[-20:])
    return {"intervals_ns": values, "sample_count": len(values), "zero_count": values.count(0),
            "min_ns": min(values), "median_ns": statistics.median(values), "max_ns": max(values),
            "first_window_median_ns": first, "last_window_median_ns": last,
            "window_size": min(20, len(values)),
            "last_over_first": None if first == 0 else last / first}


def boundary_value(path, pattern):
    match = pattern.search(read_stable(path))
    if match is None:
        fail("missing boundary reading: " + str(path))
    return int(match.group(1))


def check_clock_helper(root, expected_hash, serial_hash):
    path = ref_path(root, "work/evidence/local/physical-clock-collector/collect.py")
    source = read_stable(path)
    if digest_bytes(source) != expected_hash:
        fail("clock helper differs from pinned experiment")
    tree = ast.parse(source, filename=str(path))
    assignments = [node for node in tree.body if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == "SERIAL" for target in node.targets)]
    if (len(assignments) != 1 or not isinstance(assignments[0].value, ast.Constant)
            or not isinstance(assignments[0].value.value, str)
            or digest_bytes(assignments[0].value.value.encode("utf-8")) != serial_hash):
        fail("clock helper serial declaration differs from observations")


def summarize(groups):
    output = []
    for (workload, control), pairs in groups.items():
        if len(pairs) != 4 or sorted(p["pair_order"] for p in pairs) != ["AB", "AB", "BA", "BA"]:
            fail("each group must contain two AB and two BA pairs")
        stages = {}
        for stage in (("before", "after") if workload == "B01" else ("before",)):
            pairs_for_stage = []
            for pair in pairs:
                a, b = pair["sides"]["A"]["stages"][stage], pair["sides"]["B"]["stages"][stage]
                am, bm = a["median_ns"], b["median_ns"]
                value = None if pair["result"] == "unresolved" or am == 0 or bm == 0 else (bm / am - 1) * 100
                pairs_for_stage.append({"pair_id": pair["pair_id"], "pair_order": pair["pair_order"],
                                        "B_over_A_minus_one_percent": value})
            values = [p["B_over_A_minus_one_percent"] for p in pairs_for_stage]
            stages[stage] = {"result": "unresolved" if None in values else "descriptive",
                             "median_percent": None if None in values else statistics.median(values),
                             "range_percent": None if None in values else [min(values), max(values)],
                             "pairs": pairs_for_stage}
        output.append({"workload": workload, "control": control, "stages": stages, "pairs": pairs})
    return output


def verify_smoke(root, plan, initial):
    """Recheck the prior smoke without attributing it to the new collector."""
    prior = plan.get("prior_smoke")
    if not isinstance(prior, dict) or set(prior) != {"path", "sha256", "prior_collector_sha256"}:
        fail("invalid prior smoke reference")
    sha_value(prior["sha256"], "prior smoke")
    smoke = ref_path(root, prior["path"])
    if (prior["sha256"] != plan.get("prior_smoke_sha256")
            or prior["prior_collector_sha256"] != plan.get("prior_collector_sha256")):
        fail("prior smoke hash mismatch")
    summary = parse_json(read_verified(smoke, prior["sha256"]), smoke)
    expected_inputs = {"inputs": plan["inputs"], "collector_sha256": plan["prior_collector_sha256"],
                       "clock_helper_sha256": plan["clock_helper_sha256"],
                       "single_verifier_sha256": plan["single_verifier_sha256"],
                       "pair_verifier_sha256": plan["pair_verifier_sha256"]}
    if (not isinstance(summary, dict) or summary.get("phase") != "smoke"
            or summary.get("status") != "observed" or summary.get("inputs") != expected_inputs):
        fail("prior smoke is not bound to preregistered inputs")
    old_plan_path = within(root, smoke.parent / "plan.json")
    old_plan = parse_json(read_verified(old_plan_path, summary.get("plan_sha256")), old_plan_path)
    if old_plan.get("phase") != "smoke":
        fail("prior smoke plan mismatch")
    items = summary.get("evidence")
    if not isinstance(items, list) or len(items) != 4:
        fail("prior smoke evidence incomplete")
    refs = {}
    for entry in items:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
            fail("invalid prior smoke evidence reference")
        sha_value(entry["sha256"], "smoke evidence")
        path = ref_path(root, entry["path"])
        try:
            path.relative_to(smoke.parent)
        except ValueError as exc:
            raise InvalidRecord("prior smoke evidence is outside its run") from exc
        if path.name in refs:
            fail("prior smoke evidence duplicated")
        refs[path.name] = (path, entry["sha256"])
    if set(refs) != {"raw-threadtime.log", "single-verify.json", "manifest.json", "process.json"}:
        fail("unexpected prior smoke evidence")
    def smoke_bytes(name):
        path, expected = refs[name]
        return read_verified(path, expected)
    def smoke_json(name):
        return parse_json(smoke_bytes(name), refs[name][0])
    manifest = validate_manifest(smoke_json("manifest.json"))
    pairs = old_plan.get("pairs")
    if not isinstance(pairs, list) or len(pairs) != 1 or not isinstance(pairs[0], dict):
        fail("invalid prior smoke plan")
    pair = pairs[0]
    if (manifest["pair_id"] != pair.get("pair_id") or manifest["run_id"] != pair.get("sides", {}).get("A")
            or manifest["side"] != "A" or manifest["control"] != "A0" or manifest["workload"] != "B01"
            or (manifest["warmup"], manifest["samples"]) != (2, 3)
            or manifest["host_clock_token"] != old_plan.get("host_clock_token")):
        fail("prior smoke manifest disagrees with its plan")
    identity = initial["identity"]
    device = manifest["device"]
    if (device["boot_id"] != identity["boot_id"] or device["device_id_sha256"] != identity["serial_sha256"]
            or device["build_fingerprint_sha256"] != identity["fingerprint_sha256"]):
        fail("prior smoke device differs from experiment")
    raw = smoke_bytes("raw-threadtime.log")
    if digest_bytes(raw) != manifest["raw_log_sha256"]:
        fail("prior smoke raw log hash mismatch")
    report = verify_single(raw, manifest["run_id"], manifest["pid"], manifest["tid"],
                           manifest["backend"], manifest["control"], manifest["workload"])
    process = smoke_json("process.json")
    if (report.get("result") != "observed" or report != smoke_json("single-verify.json")
            or type(process.get("pid")) is not int or process["pid"] != manifest["pid"]
            or not isinstance(process.get("start_ticks"), str) or not process["start_ticks"].isdigit()
            or int(process["start_ticks"]) != manifest["process_start_ticks"]
            or process.get("boot_id") != identity["boot_id"]):
        fail("prior smoke report or process identity mismatch")
    return {"path": prior["path"], "sha256": prior["sha256"], "scope": "collector-reported"}, manifest


def evidence_index(root, run, result, inventory):
    entries = result.get("evidence")
    if not isinstance(entries, list):
        fail("result evidence index missing")
    indexed = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
            fail("invalid result evidence reference")
        validate_ref(entry["path"])
        if entry["path"] in indexed or inventory.get(entry["path"]) != entry["sha256"]:
            fail("result evidence missing, duplicated or changed")
        indexed[entry["path"]] = entry["sha256"]
    return indexed


def recorded_output(item):
    value = item.get("stdout_hex")
    if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9a-fA-F]{2})*", value):
        fail("invalid command output encoding")
    return bytes.fromhex(value)


def check_daemon_records(items, initial):
    pid = initial.get("lspd_pid")
    expected = initial.get("daemon_mapping_identity")
    if type(pid) is not int or pid <= 0 or not isinstance(expected, dict):
        fail("missing daemon identity")
    pattern = re.compile(r"shell\Z")
    command = re.compile(r"su -c 'grep -F (/data/[A-Za-z0-9_./-]+/daemon\.apk) /proc/(\d+)/maps'\Z")
    count, daemon_path = 0, None
    for index, item in enumerate(items):
        args = item["args"]
        if len(args) != 2 or not pattern.fullmatch(args[0]):
            continue
        match = command.fullmatch(args[1])
        if not match:
            continue
        path, observed_pid = match.groups()
        if observed_pid != str(pid) or (daemon_path is not None and path != daemon_path):
            fail("daemon path or PID changed")
        daemon_path = path
        if (item.get("exit") != 0 or index + 1 >= len(items)
                or items[index + 1]["args"] != ["shell", "su -c 'stat -c \"%d %i\" " + path + "'"]
                or items[index + 1].get("exit") != 0):
            fail("daemon maps/stat observation incomplete")
        maps = recorded_output(item).decode("utf-8").splitlines()
        fields = recorded_output(items[index + 1]).decode("ascii").split()
        if len(fields) != 2 or not all(x.isdigit() for x in fields) or not maps:
            fail("invalid daemon maps/stat")
        device, inode = map(int, fields)
        major = ((device >> 8) & 0xfff) | ((device >> 32) & 0xfffff000)
        minor = (device & 0xff) | ((device >> 12) & 0xffffff00)
        if (device <= 0 or inode <= 0 or (major, minor, inode) !=
                (expected.get("major"), expected.get("minor"), expected.get("inode"))
                or len(maps) != expected.get("mapped_segments")):
            fail("daemon stat changed")
        for line in maps:
            parts = line.split(maxsplit=5)
            if len(parts) != 6 or parts[5] != path or "(deleted)" in line:
                fail("daemon mapping deleted or unexpected")
            dev = parts[3].split(":")
            if len(dev) != 2 or (int(dev[0], 16), int(dev[1], 16), int(parts[4])) != (major, minor, inode):
                fail("daemon mapped identity changed")
        count += 1
    if count != 66:
        fail("daemon mapping observations missing or duplicated")
    return count


def check_adb_records(root, run, inventory, processes, host, host_hash, initial):
    """Recheck saved command outputs; per-command serial routing is not recorded."""
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", host):
        fail("invalid host package name")
    prefix = run.relative_to(root).as_posix() + "/"
    names = sorted(p for p in inventory if p.startswith(prefix) and
                   re.fullmatch(r"adb-\d{4,}\.json", p[len(prefix):]))
    if not names or any(p != prefix + ("adb-%04d.json" % i) for i, p in enumerate(names, 1)):
        fail("command evidence sequence is missing or has gaps")
    items = []
    for name in names:
        raw = inventory.commands.get(name) if isinstance(inventory, EvidenceInventory) else None
        if raw is None:
            path = ref_path(root, name)
            raw = read_stable(path)
        else:
            path = root.joinpath(*name.split("/"))
        if digest_bytes(raw) != inventory[name]:
            fail("command evidence changed")
        item = parse_json(raw, path)
        if (not isinstance(item, dict) or not isinstance(item.get("args"), list)
                or any(not isinstance(arg, str) for arg in item["args"])):
            fail("invalid command evidence")
        items.append(item)
    identity = initial["identity"]
    identity_args = {("get-serialno",): "serial", ("shell", "getprop", "ro.build.fingerprint"): "fingerprint",
                     ("shell", "cat", "/proc/sys/kernel/random/boot_id"): "boot",
                     ("shell", "getprop", "ro.build.version.sdk"): "api",
                     ("shell", "getprop", "ro.product.cpu.abi"): "abi"}
    expected_values = {"serial": identity["serial_sha256"], "fingerprint": identity["fingerprint_sha256"],
                       "boot": identity["boot_id"], "api": str(identity["api"]), "abi": identity["abi"]}
    counts = {key: 0 for key in expected_values}
    users, stops = 0, []
    for i, item in enumerate(items):
        args = item["args"]
        key = identity_args.get(tuple(args))
        if key:
            if item.get("exit") != 0:
                fail("device identity command failed")
            output = recorded_output(item).strip()
            actual = digest_bytes(output) if key in ("serial", "fingerprint") else output.decode("utf-8")
            if actual != expected_values[key]:
                fail("device identity command mismatch: " + key)
            counts[key] += 1
        if args == ["shell", "am", "get-current-user"]:
            if item.get("exit") != 0 or recorded_output(item).strip() != b"0":
                fail("current-user observation changed")
            users += 1
        if any(re.search(r"(?<![A-Za-z0-9_-])(install|uninstall|clear)(?![A-Za-z0-9_-])", arg) for arg in args):
            fail("unexpected package modification command")
        if not any("force-stop" in arg for arg in args):
            continue
        if args != ["shell", "am", "force-stop", "--user", "0", host] or item.get("exit") != 0 or i < 7 or i + 1 >= len(items):
            fail("unexpected, failed or incomplete force-stop")
        prev, after = items[i - 7:i], items[i + 1]
        if prev[0]["args"] != ["shell", "pidof", host] or prev[0].get("exit") != 0:
            fail("missing pre-stop PID")
        pid_text = recorded_output(prev[0]).decode("ascii").strip()
        if not pid_text.isdigit():
            fail("pre-stop PID not unique")
        pid = int(pid_text)
        commands = [["exec-out", "cat", "/proc/%d/cmdline" % pid],
                    ["shell", "pm", "list", "packages", "--user", "0", "-U", host],
                    ["shell", "su -c 'grep ^Uid: /proc/%d/status'" % pid],
                    ["shell", "su -c 'cat /proc/%d/stat'" % pid],
                    ["shell", "pm", "path", "--user", "0", host]]
        if any(record["args"] != command or record.get("exit") != 0 for record, command in zip(prev[1:6], commands)):
            fail("pre-stop process identity command mismatch")
        if recorded_output(prev[1]).split(b"\0", 1)[0] != host.encode():
            fail("pre-stop process cmdline mismatch")
        stat_text = recorded_output(prev[4]).decode("utf-8").strip()
        if not stat_text.startswith(str(pid) + " (") or stat_text.rfind(")") < 0:
            fail("malformed process stat")
        tail = stat_text[stat_text.rfind(")") + 1:].split()
        if len(tail) < 20 or (pid, tail[19]) not in processes:
            fail("pre-stop process identity not captured")
        proc = processes[(pid, tail[19])]
        uid = proc["uid"]
        if (not re.fullmatch(r"package:" + re.escape(host) + r"\s+uid:" + str(uid), recorded_output(prev[2]).decode().strip())
                or not re.fullmatch(r"Uid:\s+" + str(uid) + r"\s+\d+\s+\d+\s+\d+", recorded_output(prev[3]).decode().strip())):
            fail("pre-stop UID mismatch")
        installed = recorded_output(prev[5]).decode("utf-8").strip()
        if not installed.startswith("package:/data/app/"):
            fail("pre-stop installed path missing")
        path = installed[len("package:"):]
        if (prev[6]["args"] != ["shell", "su -c 'sha256sum " + path + "'"] or prev[6].get("exit") != 0
                or recorded_output(prev[6]).decode("utf-8").split()[:1] != [host_hash]):
            fail("pre-stop APK hash mismatch")
        if after["args"] != ["shell", "pidof", host] or after.get("exit") not in (0, 1) or recorded_output(after).strip():
            fail("post-stop process absence not confirmed")
        stops.append((pid, tail[19]))
    if len(stops) != 32 or len(set(stops)) != 32 or set(stops) != set(processes):
        fail("not exactly one stop per captured process")
    if users < 66 or any(count < 66 for count in counts.values()):
        fail("insufficient identity or current-user observations")
    return {"identity_observations": counts, "current_user_observations": users,
            "daemon_mapping_observations": check_daemon_records(items, initial), "stops": len(stops),
            "serial_routing": "per-command serial not present in saved records; serial observations agree"}


def verify_experiment(root, experiment, plan_sha256, inventory, inventory_sha256):
    root = root_path(root)
    token = ACTIVE_ROOT.set(root)
    inventory_token = ACTIVE_INVENTORY.set(None)
    try:
        return _verify_experiment(root, experiment, plan_sha256, inventory, inventory_sha256)
    finally:
        ACTIVE_INVENTORY.reset(inventory_token)
        ACTIVE_ROOT.reset(token)


def _verify_experiment(root, experiment, plan_sha256, inventory, inventory_sha256):
    """Read-only balanced-v1 evidence check, with external input digests required."""
    root = root_path(root)
    run = within(root, experiment)
    inv_path = within(root, inventory)
    if not run.is_dir() or not inv_path.is_file():
        fail("experiment must be a directory and inventory a file")
    try:
        inv_path.relative_to(run)
    except ValueError:
        pass
    else:
        fail("inventory must be outside the experiment directory")
    sha_value(plan_sha256, "plan")
    indexed_files = verify_inventory(root, run, inv_path, inventory_sha256)
    ACTIVE_INVENTORY.set(indexed_files)
    plan_path = within(root, run / "plan.json")
    if digest_file(plan_path) != plan_sha256:
        fail("plan SHA-256 mismatch")
    if read_stable(within(root, run / "plan.sha256")).decode("ascii").strip() != plan_sha256:
        fail("plan sidecar mismatch")
    plan = load_json(plan_path)
    started = load_json(within(root, run / "started.json"))
    result = load_json(within(root, run / "result.json"))
    if within(root, run).joinpath("failure.json").exists():
        fail("capture recorded failure")
    plan_keys = {"schema_version", "phase", "created_utc", "seed", "host_clock_token", "scope_confirmation",
                 "warmup", "samples", "pairs", "prior_smoke", "inputs", "collector_sha256",
                 "clock_helper_sha256", "single_verifier_sha256", "pair_verifier_sha256",
                 "prior_collector_sha256", "prior_smoke_sha256", "performance_gate"}
    if (not isinstance(plan, dict) or set(plan) != plan_keys or type(plan["schema_version"]) is not int
            or plan["schema_version"] != 1 or plan["phase"] != "balanced"
            or plan["scope_confirmation"] != "user-reported only" or plan["performance_gate"] != "not_evaluated"
            or type(plan["warmup"]) is not int or not 0 <= plan["warmup"] <= 100
            or type(plan["samples"]) is not int or not 1 <= plan["samples"] <= 200):
        fail("unsupported balanced-v1 plan")
    for key in ("collector_sha256", "clock_helper_sha256", "single_verifier_sha256", "pair_verifier_sha256",
                "prior_collector_sha256", "prior_smoke_sha256"):
        sha_value(plan[key], key)
    for key, path in (("single_verifier_sha256", "tools/performance/verify_cpu_body_probe.py"),
                      ("pair_verifier_sha256", "tools/performance/verify_cpu_body_pair.py")):
        if digest_file(ref_path(root, path)) != plan[key]:
            fail("current verifier differs from pinned experiment: " + key)
    if (not isinstance(started, dict) or set(started) != {"plan_sha256", "utc", "host_monotonic_ns", "policy"}
            or started["plan_sha256"] != plan_sha256 or type(started["host_monotonic_ns"]) is not int
            or started["host_monotonic_ns"] < 0 or time_value(plan["created_utc"]) > time_value(started["utc"])):
        fail("plan/start records disagree")
    if (not isinstance(result, dict) or set(result) != {"schema_version", "phase", "status", "plan_sha256",
            "results", "evidence", "device_authentication", "scope_confirmation", "performance_gate", "limitations"}
            or type(result["schema_version"]) is not int or result["schema_version"] != 1
            or result["phase"] != "balanced" or result["status"] != "observed"
            or result["plan_sha256"] != plan_sha256 or result["performance_gate"] != "not_evaluated"
            or result["scope_confirmation"] != "user-reported only"):
        fail("capture result is failed, incomplete or contradicts plan")
    pairs, outcomes = plan["pairs"], result["results"]
    if not isinstance(pairs, list) or len(pairs) != 16 or not isinstance(outcomes, list) or len(outcomes) != 16:
        raise Incomplete("expected all 16 planned pairs")
    initial = load_json(within(root, run / "target-initial.json"))
    identity = initial.get("identity") if isinstance(initial, dict) else None
    inputs = plan.get("inputs")
    if not isinstance(identity, dict) or not isinstance(inputs, dict) or not isinstance(inputs.get("installed_identity"), dict):
        fail("missing initial device or installed identity")
    installed = inputs["installed_identity"]
    packages = inputs.get("expected_packages")
    if (not isinstance(packages, dict) or len(packages) < 2
            or any(not isinstance(k, str) or not k or len(k) > 255 for k in packages)):
        fail("invalid expected package snapshot")
    for value in packages.values():
        sha_value(value, "package artifact")
    if (identity.get("fingerprint_sha256") != installed.get("fingerprint_sha256")
            or identity.get("boot_id") != installed.get("boot_id") or identity.get("api") != installed.get("android_api")
            or identity.get("abi") != installed.get("abi") or initial.get("lspd_pid") != installed.get("lspd_pid")
            or initial.get("packages") != inputs.get("expected_packages")):
        fail("initial device or package snapshot contradicts plan")
    sha_value(identity.get("serial_sha256"), "device ID")
    sha_value(identity.get("fingerprint_sha256"), "device fingerprint")
    uuid_value(identity.get("boot_id"), "boot ID")
    uuid_value(plan["host_clock_token"], "host clock token")
    check_clock_helper(root, plan["clock_helper_sha256"], identity["serial_sha256"])
    smoke, smoke_manifest = verify_smoke(root, plan, initial)
    indexed = evidence_index(root, run, result, indexed_files)
    required, ids, processes = set(), {plan["host_clock_token"]}, {}
    groups = {key: [] for key in GROUPS}
    baseline = boundary_value(within(root, run / "baseline-battery.txt"), TEMP)
    batteries = [baseline]
    thermals = [boundary_value(within(root, run / "baseline-thermalservice.txt"), THERMAL)]
    last_tick, last_terminal = -1, started["host_monotonic_ns"]
    context = None
    for pair, expected_row, outcome in zip(pairs, schedule(plan["seed"]), outcomes):
        if (not isinstance(pair, dict) or set(pair) != set(expected_row) | {"pair_id", "sides"}
                or any(type(pair.get(k)) is not type(v) or pair.get(k) != v for k, v in expected_row.items())
                or not isinstance(pair.get("sides"), dict) or set(pair["sides"]) != {"A", "B"}):
            fail("pair does not match preregistered seeded schedule")
        for token in (pair["pair_id"], pair["sides"]["A"], pair["sides"]["B"]):
            uuid_value(token, "pair or run")
            if token in ids:
                fail("pair/run/clock UUID reused")
            ids.add(token)
        if (not isinstance(outcome, dict) or set(outcome) != {"position", "block", "workload", "control",
                "pair_id", "pair_order", "result"} or outcome.get("result") not in ("body_cpu_pair_consistent", "unresolved")
                or any(type(outcome.get(k)) is not type(pair[k]) or outcome.get(k) != pair[k]
                       for k in ("position", "block", "workload", "control", "pair_id", "pair_order"))):
            fail("result does not match the planned pair")
        group = within(root, run / ("pair-%02d" % pair["position"]))
        sides = {}
        for side in pair["pair_order"]:
            directory = within(root, group / ("side-" + side + "-" + pair["sides"][side]))
            names = ("raw-threadtime.log", "single-verify.json", "manifest.json", "process.json", "target-before.json",
                     "target-after.json", "before-battery.txt", "after-battery.txt", "before-thermalservice.txt",
                     "after-thermalservice.txt", "launch-attempt.json", "terminal-observed.json", "completed-stop.stdout")
            paths = {name: within(root, directory / name) for name in names}
            required.update(path.relative_to(root).as_posix() for path in paths.values())
            manifest = validate_manifest(load_json(paths["manifest.json"]))
            if (manifest["side"] != side or manifest["run_id"] != pair["sides"][side]
                    or manifest["pair_id"] != pair["pair_id"] or manifest["pair_order"] != pair["pair_order"]
                    or manifest["workload"] != pair["workload"] or manifest["control"] != pair["control"]
                    or (manifest["warmup"], manifest["samples"]) != (plan["warmup"], plan["samples"])
                    or manifest["host_clock_token"] != plan["host_clock_token"]):
                fail("side manifest disagrees with plan")
            dev = manifest["device"]
            if (dev["device_id_sha256"] != identity["serial_sha256"]
                    or dev["build_fingerprint_sha256"] != identity["fingerprint_sha256"]
                    or dev["boot_id"] != identity["boot_id"] or dev["framework_name"] != initial.get("framework_name")
                    or dev["framework_version"] != initial.get("framework_version")):
                fail("side device declaration differs from initial snapshot")
            packages = inputs.get("expected_packages")
            if (not isinstance(packages, dict) or manifest["artifacts"]["host_apk_sha256"] not in packages.values()
                    or manifest["artifacts"]["module_apk_sha256"] not in packages.values()
                    or manifest["artifacts"]["host_apk_sha256"] == manifest["artifacts"]["module_apk_sha256"]):
                fail("side artifact declarations are absent from installed package snapshot")
            this_context = {key: manifest[key] for key in
                            ("backend", "clock", "measurement_kind", "artifacts", "device", "host_clock_token")}
            if context is None:
                context = this_context
                if any(manifest[key] != smoke_manifest[key] for key in ("backend", "artifacts", "device")):
                    fail("prior smoke backend, artifact or device differs")
            elif context != this_context:
                fail("experiment sides differ in backend, clock, artifact or device")
            if load_json(paths["target-before.json"]) != initial or load_json(paths["target-after.json"]) != initial:
                fail("device boundary snapshots differ")
            for label in ("before", "after"):
                temperature = boundary_value(paths[label + "-battery.txt"], TEMP)
                if temperature > 420 or temperature - baseline >= 50:
                    fail("recorded stop-temperature threshold exceeded")
                batteries.append(temperature)
                thermals.append(boundary_value(paths[label + "-thermalservice.txt"], THERMAL))
            process = load_json(paths["process.json"])
            if (type(process.get("pid")) is not int or type(process.get("uid")) is not int
                    or process["pid"] != manifest["pid"] or process.get("boot_id") != identity["boot_id"]
                    or not isinstance(process.get("start_ticks"), str) or not process["start_ticks"].isdigit()
                    or int(process["start_ticks"]) != manifest["process_start_ticks"]):
                fail("process record contradicts manifest")
            proc = (process["pid"], process["start_ticks"])
            if proc in processes or int(process["start_ticks"]) <= last_tick:
                fail("process reused or global start order contradicted")
            processes[proc] = process
            last_tick = int(process["start_ticks"])
            launch, terminal = load_json(paths["launch-attempt.json"]), load_json(paths["terminal-observed.json"])
            if (launch.get("run_id") != manifest["run_id"]
                    or type(launch.get("host_monotonic_ns")) is not int or type(terminal.get("host_monotonic_ns")) is not int
                    or launch["host_monotonic_ns"] != manifest["launch_monotonic_ns"]
                    or terminal["host_monotonic_ns"] != manifest["terminal_monotonic_ns"]
                    or launch["host_monotonic_ns"] <= last_terminal
                    or terminal["host_monotonic_ns"] <= launch["host_monotonic_ns"]):
                fail("collector clock order contradicted")
            last_terminal = terminal["host_monotonic_ns"]
            sides[side] = {"manifest": manifest, "raw": read_stable(paths["raw-threadtime.log"]), "paths": paths}
        verified = verify_pair(sides["A"]["manifest"], sides["B"]["manifest"], sides["A"]["raw"], sides["B"]["raw"])
        pair_path = within(root, group / "pair-verify.json")
        required.add(pair_path.relative_to(root).as_posix())
        if (verified != load_json(pair_path) or verified["result"] != outcome["result"]
                or verified["result"] not in ("body_cpu_pair_consistent", "unresolved")):
            fail("pair verifier output differs or pair did not complete")
        item = {"pair_id": pair["pair_id"], "pair_order": pair["pair_order"], "block": pair["block"],
                "result": verified["result"], "sides": {}}
        for side in ("A", "B"):
            report = verified["sides"][side]
            if report != load_json(sides[side]["paths"]["single-verify.json"]):
                fail("single-side verifier output differs from raw")
            stages = ("before", "after") if pair["workload"] == "B01" else ("before",)
            item["sides"][side] = {"raw_log": sides[side]["paths"]["raw-threadtime.log"].relative_to(root).as_posix(),
                                   "raw_sha256": sides[side]["manifest"]["raw_log_sha256"],
                                   "readings": report["readings"],
                                   "stages": {stage: sample_stats(report, stage) for stage in stages}}
        groups[(pair["workload"], pair["control"])].append(item)
    if len(ids) != 49 or len(processes) != 32 or set(indexed) != required:
        fail("experiment evidence index is incomplete or contains unexpected entries")
    host_hash = context["artifacts"]["host_apk_sha256"]
    hosts = [name for name, value in packages.items() if value == host_hash]
    if len(hosts) != 1:
        fail("host artifact must identify exactly one installed package")
    command_evidence = check_adb_records(root, run, indexed_files, processes, hosts[0], host_hash, initial)
    indexed_files.finish()
    output_groups = summarize(groups)
    unresolved = any(stage["result"] == "unresolved" for g in output_groups for stage in g["stages"].values())
    return {"result": "unresolved" if unresolved else "experiment_records_consistent",
            "plan_sha256": plan_sha256, "inventory_sha256": inventory_sha256, "prior_smoke": smoke,
            "pairs": 16, "processes": 32, "groups": output_groups, "performance_gate": "not_evaluated",
            "clock_gate": "not_evaluated", "probe_gate": "not_evaluated",
            "command_evidence": command_evidence,
            "environment": {"battery_deci_celsius_min": min(batteries), "battery_deci_celsius_max": max(batteries),
                            "battery_readings": len(batteries), "thermal_status_min": min(thermals),
                            "thermal_status_max": max(thermals), "thermal_readings": len(thermals)},
            "device_authentication": "not_verified",
            "provenance": "raw logs and indexed snapshots internally checked; device, framework, scope, "
                          "process-start and collector clock are reported, not independently authenticated",
            "scope": "instrumented callback-body CPU intervals; not pure callback CPU, dispatcher overhead, "
                     "formal A/A qualification, a confidence interval or a performance gate",
            "limitations": ["boundary snapshots and inventory are not continuous locks",
                            "pre-registration timestamps are collector-reported, not a trusted timestamp",
                            "four pairs per group do not establish stability or a confidence interval",
                            "all raw evidence remains in its original files; no input is modified"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", type=Path, help="completed balanced-v1 experiment directory")
    parser.add_argument("--evidence-root", type=Path, required=True, help="explicit root containing all inputs")
    parser.add_argument("--plan-sha256", required=True, help="externally retained preregistered plan digest")
    parser.add_argument("--inventory", type=Path, required=True, help="complete experiment file inventory")
    parser.add_argument("--inventory-sha256", required=True, help="externally retained inventory digest")
    args = parser.parse_args()
    try:
        result = verify_experiment(args.evidence_root, args.experiment, args.plan_sha256,
                                   args.inventory, args.inventory_sha256)
    except Incomplete as exc:
        result = {"result": "incomplete", "reason": str(exc), "performance_gate": "not_evaluated",
                  "clock_gate": "not_evaluated", "probe_gate": "not_evaluated", "device_authentication": "not_verified"}
    except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError, KeyError, SyntaxError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated",
                  "clock_gate": "not_evaluated", "probe_gate": "not_evaluated", "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"experiment_records_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
