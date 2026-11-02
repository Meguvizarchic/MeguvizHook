#!/usr/bin/env python3
"""Compare explicitly selected local files with one reported pilot session's SHA-256s.

This does not authenticate the device, its installed APKs, or the experiment. It checks
only the bytes read from the supplied files and does not evaluate a performance gate.
"""

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

from verify_aa_pilot import IncompleteRecord, InvalidRecord, fields, unique_fields, validate_session


ARTIFACT_FIELDS = ("host_apk_sha256", "module_apk_sha256", "workload_sha256", "feature_manifest_sha256")


def _identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _shared_identity(info):
    # On Windows fstat() and stat(path) may expose different st_ctime semantics for the same file.
    # Compare ctime within each source separately; use their common file ID and content metadata
    # for handle-to-path comparison.
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def _read_stable(path, label, retain_bytes=False):
    """Read one regular file and reject observed changes during the read."""
    with path.open("rb") as source:
        path_before = path.stat()
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or not stat.S_ISREG(path_before.st_mode):
            raise InvalidRecord(label + " must be a regular file")
        if not before.st_ino or not path_before.st_ino:
            raise InvalidRecord(label + " file identity is unavailable")
        if _shared_identity(before) != _shared_identity(path_before):
            raise InvalidRecord(label + " changed during the read")
        digest = hashlib.sha256()
        total = 0
        chunks = [] if retain_bytes else None
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
            if chunks is not None:
                chunks.append(chunk)
        after = os.fstat(source.fileno())
        # Compare the path too, so replacing it while the original handle is open is rejected.
        current_path = path.stat()
    if (_identity(before) != _identity(after) or _identity(path_before) != _identity(current_path)
            or _shared_identity(after) != _shared_identity(current_path) or total != before.st_size):
        raise InvalidRecord(label + " changed during the read")
    return digest.hexdigest(), b"".join(chunks) if chunks is not None else None


def verify_local_artifacts(record, paths):
    """Validate one completed session and compare all four user-selected files."""
    validate_session(record)
    fields(paths, ARTIFACT_FIELDS, "local artifact paths")
    actual = {}
    for key in ARTIFACT_FIELDS:
        actual[key], _ = _read_stable(paths[key], key)
        if actual[key] != record["artifacts"][key]:
            raise InvalidRecord("local file SHA-256 mismatch: " + key)
    return {
        "result": "local_hashes_match",
        "run_id": record["run_id"],
        "checked_sha256": actual,
        "scope": "bytes read from the four supplied local files only",
        "device_identity_verified": False,
        "installed_artifacts_verified": False,
        "performance_gate": "not_evaluated",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path, help="completed pilot session JSON")
    parser.add_argument("--host-apk", type=Path, required=True)
    parser.add_argument("--module-apk", type=Path, required=True)
    parser.add_argument("--workload-file", type=Path, required=True)
    parser.add_argument("--feature-manifest", type=Path, required=True)
    args = parser.parse_args()
    try:
        _, session_bytes = _read_stable(args.session, "session", retain_bytes=True)
        record = json.loads(session_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
        paths = {"host_apk_sha256": args.host_apk, "module_apk_sha256": args.module_apk,
                 "workload_sha256": args.workload_file, "feature_manifest_sha256": args.feature_manifest}
        result = verify_local_artifacts(record, paths)
        code = 0
    except IncompleteRecord as exc:
        result = {"result": "incomplete", "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 2
    except (InvalidRecord, OSError, UnicodeError, json.JSONDecodeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated"}
        code = 1
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
