#!/usr/bin/env python3
"""Observe saved, pinned test APK bytes; never measure a device or an SDK size delta."""

import argparse
import hashlib
import io
import json
import os
import re
import stat
import sys
import zipfile
import zlib
from pathlib import Path

from build_b07_pair_inputs import _write_checked, json_bytes, parse_capture, read_input
from verify_aa_pilot import InvalidRecord


HOST = "io.github.jichuo1.meguviz.hook.benchmarkhost"
MODULES = {"101": "io.github.jichuo1.meguviz.hook.benchmark101",
           "102": "io.github.jichuo1.meguviz.hook.benchmark102"}
HEX = re.compile(r"[0-9a-f]{64}\Z")
DEX = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex\Z")
MAX_APK_BYTES = 64 * 1024 * 1024
MAX_AUDIT_BYTES = 2 * 1024 * 1024
MAX_ENTRIES = 4096
MAX_ENTRY_BYTES = 32 * 1024 * 1024
MAX_EXPANDED_BYTES = 128 * 1024 * 1024
VERIFY_REFS = ("build_log", "signature_checks", "runtime_checks", "static_audit",
               "static_evidence_manifest")
PREPARED_REFS = (("artifact_record", "artifact_record_sha256"),
                 ("workload_sources", "workload_sha256"),
                 ("feature_manifest", "feature_manifest_sha256"))
CATEGORIES = ("dex", "native_so", "android_manifest", "resources", "assets",
              "xposed_metadata", "other_metadata", "other", "directory")
RUNTIME_METRICS = ("additional_rss_bytes", "additional_thread_count", "additional_fd_count",
                   "retired_resident_bytes")


def _digest(raw):
    return hashlib.sha256(raw).hexdigest()


def _hash(value):
    if not isinstance(value, str) or not HEX.fullmatch(value):
        raise InvalidRecord("expected a lowercase SHA-256")


def _plain_path(path, missing_leaf=False):
    path = Path(os.path.abspath(os.fspath(path)))
    for index, item in enumerate((path, *path.parents)):
        try:
            info = item.lstat()
        except FileNotFoundError:
            if missing_leaf and index == 0:
                continue
            raise
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise InvalidRecord("symbolic links/reparse points are not accepted: " + str(item))
    return path


def _inside(root, value, missing_leaf=False):
    if not isinstance(value, (str, os.PathLike)) or not os.fspath(value):
        raise InvalidRecord("input path is missing")
    path = Path(value)
    if ".." in path.parts:
        raise InvalidRecord("parent path components are not accepted")
    path = Path(os.path.abspath(os.fspath(path if path.is_absolute() else root / path)))
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise InvalidRecord("input escapes evidence root") from exc
    return _plain_path(path, missing_leaf)


def _read_pinned(root, value, expected, limit=MAX_APK_BYTES):
    _hash(expected)
    path = _inside(root, value)
    raw = read_input(path, limit)
    actual = _digest(raw)
    if actual != expected:
        raise InvalidRecord("pinned input SHA-256 mismatch: " + str(path))
    return {"path": path.relative_to(root).as_posix(), "bytes": len(raw), "sha256": actual}, raw


def _entry_name(entry):
    name = entry.filename
    if (not name or name != entry.orig_filename or name.startswith("/") or "\\" in name
            or ":" in name or any(ord(char) < 32 or ord(char) == 127 for char in name)):
        raise InvalidRecord("unsafe or noncanonical ZIP entry name")
    parts = (name[:-1] if name.endswith("/") else name).split("/")
    if any(not part or part in (".", "..") or part != part.strip() or part.endswith(".") for part in parts):
        raise InvalidRecord("unsafe or noncanonical ZIP entry path: " + name)
    if name.lower().endswith(".dex") and not DEX.fullmatch(name):
        raise InvalidRecord("noncanonical APK DEX entry: " + name)
    return name


def _category(name):
    if name.endswith("/"):
        return "directory"
    if DEX.fullmatch(name):
        return "dex"
    if name.lower().endswith(".so"):
        return "native_so"
    if name == "AndroidManifest.xml":
        return "android_manifest"
    if name == "resources.arsc" or name.startswith("res/"):
        return "resources"
    if name.startswith("assets/"):
        return "assets"
    if name.startswith("META-INF/xposed/"):
        return "xposed_metadata"
    if name.startswith("META-INF/"):
        return "other_metadata"
    return "other"


def zip_composition(raw, expected_dex):
    """Read ZIP contents from the same bytes that established the APK pin; extract nothing."""
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_APK_BYTES:
        raise InvalidRecord("APK byte limit exceeded or APK is empty")
    if not isinstance(expected_dex, list) or not expected_dex:
        raise InvalidRecord("audited DEX SHA-256 list is missing")
    for value in expected_dex:
        _hash(value)
    totals = {key: {"entries": 0, "compressed_bytes": 0, "uncompressed_bytes": 0} for key in CATEGORIES}
    rows, names, dex = [], set(), []
    expanded, compressed = 0, 0
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entries = archive.infolist()
            if not 0 < len(entries) <= MAX_ENTRIES:
                raise InvalidRecord("ZIP entry count limit exceeded or ZIP is empty")
            # Preflight all advertised sizes before decompressing any entry.
            for entry in entries:
                name = _entry_name(entry)
                canonical = name.rstrip("/")
                if canonical in names:
                    raise InvalidRecord("duplicate ZIP entry: " + name)
                names.add(canonical)
                if entry.flag_bits & 1 or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise InvalidRecord("encrypted or unsupported ZIP compression: " + name)
                if (not 0 <= entry.file_size <= MAX_ENTRY_BYTES
                        or not 0 <= entry.compress_size <= len(raw)
                        or not 0 <= entry.header_offset < len(raw)
                        or (entry.is_dir() and entry.file_size != 0)):
                    raise InvalidRecord("ZIP entry size or offset limit exceeded: " + name)
                expanded += entry.file_size
                compressed += entry.compress_size
            if expanded > MAX_EXPANDED_BYTES or compressed > len(raw):
                raise InvalidRecord("ZIP total size limit exceeded")
            files = {entry.filename for entry in entries if not entry.is_dir()}
            for name in names:
                parts = name.split("/")
                if any("/".join(parts[:index]) in files for index in range(1, len(parts))):
                    raise InvalidRecord("conflicting ZIP file/directory paths: " + name)
            for entry in entries:
                digest, actual = hashlib.sha256(), 0
                with archive.open(entry) as source:
                    while True:
                        chunk = source.read(64 * 1024)
                        if not chunk:
                            break
                        actual += len(chunk)
                        if actual > entry.file_size or actual > MAX_ENTRY_BYTES:
                            raise InvalidRecord("expanded ZIP entry exceeds declared limit")
                        digest.update(chunk)
                if actual != entry.file_size:
                    raise InvalidRecord("expanded ZIP entry size disagrees")
                category = _category(entry.filename)
                row = {"name": entry.filename, "category": category,
                       "compression_method": "stored" if entry.compress_type == zipfile.ZIP_STORED else "deflate",
                       "compressed_bytes": entry.compress_size, "uncompressed_bytes": actual,
                       "sha256": digest.hexdigest()}
                rows.append(row)
                total = totals[category]
                total["entries"] += 1
                total["compressed_bytes"] += entry.compress_size
                total["uncompressed_bytes"] += actual
                if category == "dex":
                    dex.append(row)
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError, EOFError, zlib.error, UnicodeError) as exc:
        raise InvalidRecord("invalid APK ZIP contents: " + str(exc)) from exc
    dex_names = {row["name"] for row in dex}
    canonical = {"classes.dex"} | {"classes%d.dex" % index for index in range(2, len(dex) + 1)}
    if not dex or dex_names != canonical:
        raise InvalidRecord("APK DEX entries are missing or noncontiguous")
    if sorted(row["sha256"] for row in dex) != sorted(expected_dex):
        raise InvalidRecord("APK DEX SHA-256 list disagrees with audited producer declaration")
    return {"entry_count": len(rows), "entries": sorted(rows, key=lambda row: row["name"]),
            "categories": totals, "compressed_entry_payload_bytes": compressed,
            "uncompressed_entry_payload_bytes": expanded,
            "container_residual_bytes": len(raw) - compressed,
            "container_residual_definition": "apk_bytes minus sum of ZIP entry compressed_bytes; includes container/signing/alignment material, not SDK or signature attribution",
            "dex_validation": "canonical entry names and content SHA-256 against audit; not a full DEX verifier"}


def observe(root, audit_path, audit_sha256, backend, certificate_sha256):
    """Return a local byte observation for the fixed host and one backend's module."""
    root = _plain_path(root)
    if not root.is_dir() or backend not in MODULES:
        raise InvalidRecord("invalid evidence root or backend")
    _hash(certificate_sha256)
    audit_pin, raw = _read_pinned(root, audit_path, audit_sha256, MAX_AUDIT_BYTES)
    audit = parse_capture(raw)
    if (type(audit.get("schema_version")) is not int or audit["schema_version"] != 1
            or audit.get("status") != "signed_artifacts_locally_verified"):
        raise InvalidRecord("audit is not a supported signed-artifact producer declaration")
    rows = audit.get("artifacts")
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise InvalidRecord("invalid audit artifact rows")
    pins = {}
    verification, prepared = audit.get("verification"), audit.get("prepared_inputs")
    if not isinstance(verification, dict) or not isinstance(prepared, dict):
        raise InvalidRecord("audit evidence references are missing")
    references = [(key, verification, key + "_sha256") for key in VERIFY_REFS]
    if "api101_reference_supplement" in verification:
        references.append(("api101_reference_supplement", verification, "api101_reference_supplement_sha256"))
    references += [(key, prepared, hash_key) for key, hash_key in PREPARED_REFS]
    for key, source, hash_key in references:
        pins[key], _ = _read_pinned(root, source.get(key), source.get(hash_key))
    artifacts = {}
    for label, module, package in (("host", "device-benchmark-host", HOST),
                                   ("module", "device-benchmark-module-" + backend, MODULES[backend])):
        matches = [row for row in rows if row.get("package") == package]
        if len(matches) != 1:
            raise InvalidRecord("audit must declare exactly one selected package: " + package)
        row = matches[0]
        if (row.get("module") != module or row.get("variant") != "deviceTest"
                or row.get("certificate_sha256") != certificate_sha256
                or row.get("signature_verified") is not True or row.get("scheme_v2_verified") is not True
                or row.get("runtime_isolation_gate") != "passed"):
            raise InvalidRecord("selected package lacks the expected test identity/verified producer declarations")
        pin, apk = _read_pinned(root, row.get("apk"), row.get("sha256"))
        if type(row.get("bytes")) is not int or row["bytes"] <= 0 or row["bytes"] != len(apk):
            raise InvalidRecord("APK bytes disagree with audit")
        artifacts[label] = {**pin, "module": module, "package": package, "variant": "deviceTest",
                            "certificate_sha256": certificate_sha256,
                            "identity_and_signature_status": "audited_producer_declaration",
                            "apk_bytes": len(apk), "zip": zip_composition(apk, row.get("dex_sha256"))}
    if artifacts["host"]["path"] == artifacts["module"]["path"]:
        raise InvalidRecord("host and module must reference distinct saved APK paths")
    return {"schema_version": 1, "record_type": "b10_saved_apk_composition_observation",
            "workload": "B10", "backend": backend, "result": "local_apk_bytes_observed",
            "measurement_kind": "saved_apk_zip_composition", "artifact_audit": audit_pin,
            "evidence_pins": pins, "artifacts": artifacts,
            "a0_a2_comparison": {"artifact_relation": "same_artifact_not_sdk_delta",
                                 "sdk_delta_status": "unknown", "additional_apk_bytes": None,
                                 "reason": "no separately built controlled paired APKs; A0/A2 select paths within the same saved artifacts"},
            "runtime_observation": {"status": "not_observed", **{key: None for key in RUNTIME_METRICS}},
            "signature_verification": "audited_producer_declaration; no signer was executed",
            "runtime_dependency_gate": "audited_producer_declaration; Gradle graph isolation, not process resource measurement",
            "device_identity_verified": False, "installed_artifacts_verified": False,
            "budget_evaluation": "not_evaluated", "performance_gate": "not_evaluated", "release_qualified": False,
            "limitations": ["Package and signer identity are taken from the explicitly pinned producer audit; this tool does not parse Android binary manifests or verify cryptographic signatures.",
                            "Only local saved APK snapshots are observed; ZIP expanded bytes are not installed storage or RSS.",
                            "Container residual is not attributed to the SDK, signatures, or one source component.",
                            "No SDK size delta, runtime resource budget, device behavior or production release qualification is established."]}


def write_observation(root, audit_path, audit_sha256, backend, certificate_sha256, output):
    """Create one new JSON file under root; its parent must already exist."""
    root = _plain_path(root)
    output = _inside(root, output, missing_leaf=True)
    if output.exists():
        raise FileExistsError("observation output already exists: " + str(output))
    report = observe(root, audit_path, audit_sha256, backend, certificate_sha256)
    output = _inside(root, output, missing_leaf=True)
    pin = _write_checked(output, json_bytes(report))
    return {"result": report["result"], "output": output.relative_to(root).as_posix(),
            "bytes": pin["bytes"], "sha256": pin["sha256"], "performance_gate": "not_evaluated"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="existing local evidence root")
    parser.add_argument("--audit", type=Path, required=True, help="signed producer audit under root")
    parser.add_argument("--audit-sha256", required=True)
    parser.add_argument("--backend", choices=tuple(MODULES), required=True)
    parser.add_argument("--certificate-sha256", required=True,
                        help="independently pinned lowercase SHA-256 expected for both signed test APKs")
    parser.add_argument("--out", type=Path, required=True, help="new JSON file under root; existing parent required")
    args = parser.parse_args(argv)
    try:
        result = write_observation(args.root, args.audit, args.audit_sha256, args.backend,
                                   args.certificate_sha256, args.out)
        code = 0
    except (InvalidRecord, OSError, UnicodeError, ValueError, TypeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 1
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
