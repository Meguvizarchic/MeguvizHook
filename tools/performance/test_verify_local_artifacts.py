import contextlib
import copy
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from test_verify_aa_pilot import session
from verify_aa_pilot import IncompleteRecord, InvalidRecord
from verify_local_artifacts import ARTIFACT_FIELDS, _read_stable, main, verify_local_artifacts


def fixture():
    # Test artifacts remain in ignored build/, preserving unrelated workspace files.
    root = Path(__file__).resolve().parents[2] / "build" / "pilot-local-hash-tests"
    root.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="case-", dir=str(root)))
    record = session("A")
    paths = {}
    for index, key in enumerate(ARTIFACT_FIELDS):
        path = directory / (key + ".bin")
        data = ("artifact-%d\n" % index).encode("utf-8")
        path.write_bytes(data)
        paths[key] = path
        record["artifacts"][key] = hashlib.sha256(data).hexdigest()
    return directory, record, paths


class LocalArtifactVerificationTest(unittest.TestCase):
    def test_matches_all_four_without_claiming_device_or_gate(self):
        _, record, paths = fixture()
        before = {key: path.read_bytes() for key, path in paths.items()}
        result = verify_local_artifacts(record, paths)
        self.assertEqual("local_hashes_match", result["result"])
        self.assertEqual(record["artifacts"], result["checked_sha256"])
        self.assertFalse(result["device_identity_verified"])
        self.assertFalse(result["installed_artifacts_verified"])
        self.assertEqual("not_evaluated", result["performance_gate"])
        self.assertEqual(before, {key: path.read_bytes() for key, path in paths.items()})

    def test_each_mismatch_is_rejected(self):
        _, record, paths = fixture()
        for key in ARTIFACT_FIELDS:
            with self.subTest(key=key):
                altered = copy.deepcopy(record)
                altered["artifacts"][key] = "0" * 64
                with self.assertRaisesRegex(InvalidRecord, key):
                    verify_local_artifacts(altered, paths)

    def test_missing_field_or_file_is_not_success(self):
        directory, record, paths = fixture()
        missing = copy.deepcopy(record)
        del missing["artifacts"]["workload_sha256"]
        with self.assertRaises(IncompleteRecord):
            verify_local_artifacts(missing, paths)
        missing_paths = dict(paths)
        missing_paths["module_apk_sha256"] = directory / "absent.apk"
        with self.assertRaises(FileNotFoundError):
            verify_local_artifacts(record, missing_paths)

    def test_changed_file_metadata_during_read_is_rejected(self):
        _, record, paths = fixture()
        before = os.stat(paths["host_apk_sha256"])
        changed = SimpleNamespace(**{name: getattr(before, name) for name in
                                    ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")})
        changed.st_size += 1
        with patch("verify_local_artifacts.os.fstat", side_effect=[before, changed]):
            with self.assertRaisesRegex(InvalidRecord, "changed during the read"):
                verify_local_artifacts(record, paths)

    def test_handle_and_path_ctime_can_differ_on_windows(self):
        _, _, paths = fixture()
        path = paths["host_apk_sha256"]
        current = os.stat(path)
        # Some Windows Python builds expose handle ctime as last-write time and path ctime as
        # creation time. Both snapshots are individually stable and describe the same file ID.
        path_view = SimpleNamespace(**{name: getattr(current, name) for name in
                                      ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns", "st_mode")})
        path_view.st_ctime_ns += 1000000000
        with patch.object(Path, "stat", side_effect=[path_view, path_view]):
            actual, _ = _read_stable(path, "host")
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), actual)

    def test_path_ctime_change_and_path_replacement_are_rejected(self):
        _, _, paths = fixture()
        path = paths["host_apk_sha256"]
        current = os.stat(path)
        fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns", "st_mode")
        for changed_field in ("st_ctime_ns", "st_ino"):
            with self.subTest(field=changed_field):
                changed = SimpleNamespace(**{name: getattr(current, name) for name in fields})
                setattr(changed, changed_field, getattr(changed, changed_field) + 1)
                with patch.object(Path, "stat", side_effect=[current, changed]):
                    with self.assertRaisesRegex(InvalidRecord, "changed during the read"):
                        _read_stable(path, "host")

    def test_file_without_stable_inode_is_rejected(self):
        _, _, paths = fixture()
        path = paths["host_apk_sha256"]
        current = os.stat(path)
        fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns", "st_mode")
        missing_id = SimpleNamespace(**{name: getattr(current, name) for name in fields})
        missing_id.st_ino = 0
        with patch.object(Path, "stat", return_value=missing_id):
            with self.assertRaisesRegex(InvalidRecord, "identity is unavailable"):
                _read_stable(path, "host")

    def test_cli_reports_mismatch_and_never_modifies_session(self):
        directory, record, paths = fixture()
        record_path = directory / "session.json"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        original = record_path.read_bytes()
        paths["feature_manifest_sha256"].write_bytes(b"different")
        argv = ["verify_local_artifacts.py", str(record_path), "--host-apk", str(paths["host_apk_sha256"]),
                "--module-apk", str(paths["module_apk_sha256"]), "--workload-file", str(paths["workload_sha256"]),
                "--feature-manifest", str(paths["feature_manifest_sha256"])]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, main())
        result = json.loads(output.getvalue())
        self.assertEqual("invalid", result["result"])
        self.assertEqual("not_evaluated", result["performance_gate"])
        self.assertEqual(original, record_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
