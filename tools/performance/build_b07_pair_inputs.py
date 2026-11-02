#!/usr/bin/env python3
"""Build a new B07 evidence bundle from original logs and explicit capture declarations."""

import argparse
import hashlib
import json
import os
import stat
from pathlib import Path

from verify_aa_pilot import IncompleteRecord, InvalidRecord, unique_fields
from verify_b07_pair import fields, validate_manifest, verify_pair


CAPTURE_FIELDS = {"schema_version", "record_type", "pair_id", "run_id", "session_id", "side",
                  "pair_order", "backend", "pid", "tid", "host_uid", "process_start_ticks",
                  "artifacts", "device", "host_clock_token", "launch_monotonic_ns",
                  "terminal_monotonic_ns"}
INPUTS = (("one", "capture-one.json", "raw-one.log"),
          ("two", "capture-two.json", "raw-two.log"))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2, allow_nan=False)
            + "\n").encode("utf-8")


def parse_capture(raw):
    def invalid_constant(value):
        raise InvalidRecord("non-standard JSON number: " + value)
    capture = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields,
                         parse_constant=invalid_constant)
    if not isinstance(capture, dict):
        raise InvalidRecord("capture declaration must be an object")
    return capture


def capture_manifest(capture, raw):
    """Supply protocol constants and a digest, never infer external identity or execution order."""
    fields(capture, CAPTURE_FIELDS, "capture declaration")
    if type(capture["schema_version"]) is not int or capture["schema_version"] != 1:
        raise InvalidRecord("unsupported capture schema_version")
    if capture["record_type"] != "b07_install_capture_side":
        raise InvalidRecord("unsupported capture record_type")
    manifest = dict(capture)
    manifest.update(record_type="b07_install_probe_side", workload="B07", control="A2",
                    warmup=0, samples=1, iterations=1,
                    measurement_kind="install_wall_and_thread_cpu_ns",
                    raw_log_sha256=digest(raw) if isinstance(raw, bytes) else "0" * 64)
    # Validate the independent declaration even when its log is missing. The
    # placeholder digest is validation-only and is never returned or published.
    validate_manifest(manifest)
    if raw is None:
        raise IncompleteRecord("raw log unavailable")
    if not isinstance(raw, bytes):
        raise InvalidRecord("raw log must be bytes")
    return manifest


def build_inputs(captures, raws, input_errors=None):
    """Validate both inputs independently; the pair verifier also runs each single-log verifier."""
    errors = list(input_errors or [])
    manifests = {}
    for key in ("one", "two"):
        try:
            if captures.get(key) is None:
                raise IncompleteRecord("capture declaration unavailable")
            manifests[key] = capture_manifest(captures[key], raws.get(key))
        except (InvalidRecord, IncompleteRecord, UnicodeError, TypeError, RecursionError) as exc:
            errors.append({"input": key + ".capture", "result": "incomplete"
                           if isinstance(exc, IncompleteRecord) else "invalid", "reason": str(exc)})
    report = verify_pair(manifests.get("one"), manifests.get("two"),
                         raws.get("one"), raws.get("two"), errors)
    return manifests, report


def _identity(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def read_input(path, limit):
    """Read bounded regular bytes once and reject an observed concurrent modification."""
    before = path.lstat()
    if (not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode)
            or getattr(before, "st_file_attributes", 0) & 0x400 or before.st_size > limit):
        raise InvalidRecord("input must be a bounded ordinary file: " + str(path))
    with path.open("rb") as source:
        opened = os.fstat(source.fileno())
        data = source.read(limit + 1)
        ended = os.fstat(source.fileno())
    after = path.lstat()
    if (len(data) > limit or len(data) != opened.st_size
            or _identity(before) != _identity(opened) or _identity(opened) != _identity(ended)
            or _identity(ended) != _identity(after)):
        raise InvalidRecord("input changed during reading: " + str(path))
    return data


def _write_checked(path, data):
    with path.open("xb") as target:
        written = target.write(data)
        if written != len(data):
            raise OSError("short output write: " + path.name)
        target.flush()
        os.fsync(target.fileno())
    observed = read_input(path, len(data))
    if observed != data:
        raise OSError("output readback differs: " + path.name)
    return {"path": path.name, "bytes": len(data), "sha256": digest(data)}


def write_bundle(output, input_bytes, manifests, report):
    """Publish a verified completion marker last. Interrupted partial directories are retained."""
    output.mkdir()  # Exclusive, including races and an existing symbolic link.
    inventory = []
    for key, capture_name, raw_name in INPUTS:
        for kind, name in (("capture", capture_name), ("log", raw_name)):
            data = input_bytes.get(key + "." + kind)
            if data is not None:
                inventory.append(_write_checked(output / name, data))
    publish_manifests = report["result"] in ("install_pair_consistent", "unresolved")
    if publish_manifests:
        for key in ("one", "two"):
            inventory.append(_write_checked(output / ("manifest-" + key + ".json"),
                                            json_bytes(manifests[key])))
    inventory.append(_write_checked(output / "verification.json", json_bytes(report)))
    completion = {"schema_version": 1, "record_type": "b07_pair_input_bundle",
                  "bundle_complete": True, "result": report["result"],
                  "manifests_written": publish_manifests, "files": inventory,
                  "performance_gate": "not_evaluated", "device_authentication": "not_verified",
                  "scope": "complete evidence bundle; collector declarations, not device or performance certification"}
    # Keep the checked pending file. Linking is exclusive and does not replace a destination.
    # No further filesystem operation follows successful publication.
    pending = output / "result.pending.json"
    _write_checked(pending, json_bytes(completion))
    os.link(pending, output / "result.json")
    return completion


def generate(capture_paths, log_paths, output):
    """Create a new bundle, including failed input evidence, without overwriting any path."""
    output_path = output.resolve()
    if any(path.resolve() == output_path for path in (*capture_paths.values(), *log_paths.values())):
        raise InvalidRecord("output directory must differ from every input path")
    if output.exists() or output.is_symlink():
        raise FileExistsError("output directory already exists: " + str(output))
    captures, raws, input_bytes, errors = {}, {}, {}, []
    input_hashes = {}
    for key in ("one", "two"):
        for kind, path, limit in (("capture", capture_paths[key], 256 * 1024),
                                  ("log", log_paths[key], 16 * 1024 * 1024)):
            label = key + "." + kind
            try:
                data = read_input(path, limit)
                input_bytes[label] = data
                input_hashes[label] = digest(data)
                if kind == "capture":
                    captures[key] = parse_capture(data)
                else:
                    raws[key] = data
            except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError, RecursionError) as exc:
                errors.append({"input": label, "result": "incomplete"
                               if isinstance(exc, FileNotFoundError) else "invalid", "reason": str(exc)})
    manifests, report = build_inputs(captures, raws, errors)
    report["capture_input_sha256"] = input_hashes
    try:
        completion = write_bundle(output, input_bytes, manifests, report)
    except (OSError, ValueError, TypeError, RecursionError) as exc:
        exc.partial_report = report
        raise
    return {**report, "output": {"path": str(output), "bundle_complete": True,
                                  "manifests_written": completion["manifests_written"]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture_one", type=Path)
    parser.add_argument("capture_two", type=Path)
    parser.add_argument("--log-one", type=Path, required=True)
    parser.add_argument("--log-two", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="new evidence directory; must not exist")
    args = parser.parse_args()
    try:
        result = generate({"one": args.capture_one, "two": args.capture_two},
                          {"one": args.log_one, "two": args.log_two}, args.output)
    except (InvalidRecord, IncompleteRecord, OSError, UnicodeError, ValueError, TypeError, RecursionError) as exc:
        result = {**getattr(exc, "partial_report", {}), "result": "invalid", "reason": str(exc),
                  "output": {"path": str(args.output), "bundle_complete": False},
                  "performance_gate": "not_evaluated", "device_authentication": "not_verified"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, allow_nan=False))
    return {"install_pair_consistent": 0, "unresolved": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    raise SystemExit(main())
