import contextlib
import copy
import hashlib
import io
import json
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import observe_b10_apk as observer

TEST_CERTIFICATE = "a" * 64


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def apk_bytes(entries, method=zipfile.ZIP_DEFLATED):
    output = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(output, "w", compression=method) as archive:
            for name, data in entries:
                archive.writestr(name, data)
    return output.getvalue()


class ApkObservationTest(unittest.TestCase):
    def setUp(self):
        # Synthetic artifacts are isolated from saved workspace evidence and are never installed.
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dex = b"synthetic DEX content; this tool hashes entries, it is not a DEX parser"
        self.entries = [("classes.dex", self.dex), ("AndroidManifest.xml", b"synthetic manifest"),
                        ("resources.arsc", b"resources"), ("res/raw/x.bin", b"resource"),
                        ("assets/a.bin", b"asset"), ("META-INF/xposed/module.prop", b"test metadata"),
                        ("META-INF/NOTICE", b"notice"), ("lib/x86_64/libtest.so", b"synthetic native"),
                        ("other.bin", b"other")]
        rows = []
        for module, package in (("device-benchmark-host", observer.HOST),
                                ("device-benchmark-module-101", observer.MODULES["101"]),
                                ("device-benchmark-module-102", observer.MODULES["102"])):
            path = self.root / (module + ".apk")
            raw = apk_bytes(self.entries)
            path.write_bytes(raw)
            rows.append({"module": module, "package": package, "variant": "deviceTest", "apk": path.name,
                         "sha256": sha(raw), "bytes": len(raw), "certificate_sha256": TEST_CERTIFICATE,
                         "signature_verified": True, "scheme_v2_verified": True,
                         "runtime_isolation_gate": "passed", "dex_sha256": [sha(self.dex)]})
        verification, prepared = {}, {}
        for key in observer.VERIFY_REFS:
            path = self.root / (key + ".json")
            path.write_bytes(b"{}\n")
            verification[key] = path.name
            verification[key + "_sha256"] = sha(path.read_bytes())
        for key, hash_key in observer.PREPARED_REFS:
            path = self.root / (key + ".json")
            path.write_bytes(b"{}\n")
            prepared[key] = path.name
            prepared[hash_key] = sha(path.read_bytes())
        self.audit = {"schema_version": 1, "status": "signed_artifacts_locally_verified",
                      "artifacts": rows, "verification": verification, "prepared_inputs": prepared}
        self.audit_path = self.root / "audit.json"
        self.save_audit()

    def save_audit(self):
        self.audit_path.write_text(json.dumps(self.audit), encoding="utf-8")
        return sha(self.audit_path.read_bytes())

    def observe(self, backend="102"):
        return observer.observe(self.root, self.audit_path, self.save_audit(), backend, TEST_CERTIFICATE)

    def replace_apk(self, raw, index=0):
        row = self.audit["artifacts"][index]
        (self.root / row["apk"]).write_bytes(raw)
        row.update(sha256=sha(raw), bytes=len(raw))

    def test_both_backends_report_bytes_and_keep_delta_and_runtime_unknown(self):
        for backend in ("101", "102"):
            with self.subTest(backend=backend):
                report = self.observe(backend)
                self.assertEqual(observer.MODULES[backend], report["artifacts"]["module"]["package"])
                host = report["artifacts"]["host"]
                composition = host["zip"]
                self.assertEqual(len(self.entries), composition["entry_count"])
                self.assertEqual(host["apk_bytes"], composition["compressed_entry_payload_bytes"]
                                 + composition["container_residual_bytes"])
                self.assertEqual(sum(len(data) for _, data in self.entries), composition["uncompressed_entry_payload_bytes"])
                self.assertEqual(len(self.dex), composition["categories"]["dex"]["uncompressed_bytes"])
                self.assertEqual(1, composition["categories"]["native_so"]["entries"])
                self.assertEqual(2, composition["categories"]["resources"]["entries"])
                self.assertEqual(len(self.entries), sum(row["entries"] for row in composition["categories"].values()))
                self.assertEqual({name: sha(data) for name, data in self.entries},
                                 {row["name"]: row["sha256"] for row in composition["entries"]})
                self.assertEqual("same_artifact_not_sdk_delta", report["a0_a2_comparison"]["artifact_relation"])
                self.assertEqual("unknown", report["a0_a2_comparison"]["sdk_delta_status"])
                self.assertIsNone(report["a0_a2_comparison"]["additional_apk_bytes"])
                self.assertEqual("not_observed", report["runtime_observation"]["status"])
                self.assertTrue(all(report["runtime_observation"][key] is None for key in observer.RUNTIME_METRICS))
                self.assertEqual("audited_producer_declaration", host["identity_and_signature_status"])
                self.assertEqual("not_evaluated", report["budget_evaluation"])
                self.assertEqual("not_evaluated", report["performance_gate"])
                self.assertFalse(report["installed_artifacts_verified"])
                self.assertFalse(report["release_qualified"])

    def test_stored_entries_multidex_and_empty_directory(self):
        second = b"second synthetic DEX"
        self.replace_apk(apk_bytes(self.entries + [("classes2.dex", second), ("assets/empty/", b"")], zipfile.ZIP_STORED))
        self.audit["artifacts"][0]["dex_sha256"].append(sha(second))
        result = self.observe()["artifacts"]["host"]["zip"]
        self.assertEqual(2, result["categories"]["dex"]["entries"])
        self.assertEqual(1, result["categories"]["directory"]["entries"])
        self.assertTrue(all(row["compression_method"] == "stored" for row in result["entries"]))

    def test_audit_hash_duplicate_keys_and_nonstandard_json_numbers_are_rejected(self):
        with self.assertRaisesRegex(observer.InvalidRecord, "SHA-256 mismatch"):
            observer.observe(self.root, self.audit_path, "0" * 64, "102", TEST_CERTIFICATE)
        for raw in (b'{"schema_version":1,"schema_version":1}', b'{"value":NaN}'):
            with self.subTest(raw=raw):
                self.audit_path.write_bytes(raw)
                with self.assertRaises(observer.InvalidRecord):
                    observer.observe(self.root, self.audit_path, sha(raw), "102", TEST_CERTIFICATE)

    def test_apk_hash_or_size_mismatch_is_rejected(self):
        row = self.audit["artifacts"][0]
        original = copy.deepcopy(row)
        for key, value in (("sha256", "0" * 64), ("bytes", row["bytes"] + 1), ("bytes", True)):
            with self.subTest(key=key, value=value):
                row.update(original)
                row[key] = value
                with self.assertRaises(observer.InvalidRecord):
                    self.observe()

    def test_fixed_package_certificate_variant_and_verified_flags_are_required(self):
        original = copy.deepcopy(self.audit["artifacts"][2])
        for key, value in (("package", "other.package"), ("module", "other-module"),
                           ("certificate_sha256", "f" * 64), ("variant", "release"),
                           ("signature_verified", 1), ("scheme_v2_verified", False),
                           ("runtime_isolation_gate", "failed")):
            with self.subTest(key=key):
                self.audit["artifacts"][2] = {**original, key: value}
                with self.assertRaises(observer.InvalidRecord):
                    self.observe()

    def test_caller_certificate_is_mandatory_validated_and_matches_both_artifacts(self):
        audit_hash = self.save_audit()
        for invalid in ("", "A" * 64, "x" * 64, "a" * 63, None, "b" * 64):
            with self.subTest(certificate=invalid):
                with self.assertRaises(observer.InvalidRecord):
                    observer.observe(self.root, self.audit_path, audit_hash, "102", invalid)
        self.audit["artifacts"][0]["certificate_sha256"] = "b" * 64
        with self.assertRaises(observer.InvalidRecord):
            self.observe()
        for row in self.audit["artifacts"]:
            row["certificate_sha256"] = "b" * 64
        report = observer.observe(self.root, self.audit_path, self.save_audit(), "102", "b" * 64)
        self.assertEqual("b" * 64, report["artifacts"]["host"]["certificate_sha256"])
        self.assertEqual("b" * 64, report["artifacts"]["module"]["certificate_sha256"])

    def test_selected_package_must_be_unique(self):
        self.audit["artifacts"].append(copy.deepcopy(self.audit["artifacts"][0]))
        with self.assertRaisesRegex(observer.InvalidRecord, "exactly one"):
            self.observe()

    def test_evidence_references_must_be_present_and_match(self):
        for group, key in (("verification", "signature_checks"), ("prepared_inputs", "workload_sources")):
            with self.subTest(key=key):
                original = self.audit[group][key]
                self.audit[group][key] = "missing.json"
                with self.assertRaises(FileNotFoundError):
                    self.observe()
                self.audit[group][key] = original
        (self.root / self.audit["verification"]["static_audit"]).write_bytes(b"changed")
        with self.assertRaisesRegex(observer.InvalidRecord, "SHA-256 mismatch"):
            self.observe()

    def test_path_escape_and_parent_alias_are_rejected(self):
        for value in ("../outside.apk", "sub/../host.apk", str(self.root.parent / "outside.apk")):
            with self.subTest(value=value):
                self.audit["artifacts"][0]["apk"] = value
                with self.assertRaises((observer.InvalidRecord, FileNotFoundError)):
                    self.observe()

    def test_duplicate_and_unsafe_zip_names_are_rejected(self):
        for name in ("classes.dex", "../escape", "/absolute", "assets/../alias", "assets//alias",
                     "assets\\alias", "C:alias", "assets/./alias", "assets/name.", "assets/ name",
                     "assets/line\nname"):
            with self.subTest(name=name):
                # Windows ZipInfo normalizes backslashes while writing. Replace both persisted
                # name fields afterwards so this case reaches the observer with the unsafe name.
                written_name = name.replace("\\", "/")
                raw = apk_bytes(self.entries + [(written_name, b"bad")])
                if "\\" in name:
                    self.assertEqual(len(written_name.encode("utf-8")), len(name.encode("utf-8")))
                    self.assertEqual(2, raw.count(written_name.encode("utf-8")))
                    raw = raw.replace(written_name.encode("utf-8"), name.encode("utf-8"))
                    self.assertEqual(2, raw.count(name.encode("utf-8")))
                    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
                        self.assertIn(name, [entry.orig_filename for entry in archive.infolist()])
                self.replace_apk(raw)
                with self.assertRaises(observer.InvalidRecord):
                    self.observe()

    def test_nul_names_and_file_directory_aliases_are_rejected(self):
        # ZipFile's writer truncates NUL names, so patch both persisted name fields instead.
        raw = apk_bytes(self.entries + [("assets/aXhidden", b"bad")])
        self.replace_apk(raw.replace(b"assets/aXhidden", b"assets/a\x00hidden"))
        with self.assertRaises(observer.InvalidRecord):
            self.observe()
        for entries in (self.entries + [("assets", b"file conflicting with assets/a.bin")],
                        self.entries + [("other.bin/", b"")]):
            with self.subTest(names=[name for name, _ in entries]):
                self.replace_apk(apk_bytes(entries))
                with self.assertRaises(observer.InvalidRecord):
                    self.observe()

    def test_missing_noncanonical_gapped_or_unreported_dex_is_rejected(self):
        for entries in ([item for item in self.entries if item[0] != "classes.dex"],
                        self.entries + [("classes1.dex", b"bad")],
                        self.entries + [("classes03.dex", b"bad")],
                        self.entries + [("nested/classes.dex", b"bad")],
                        self.entries + [("classes3.dex", b"bad")],
                        self.entries + [("classes2.dex", b"not in producer audit")]):
            with self.subTest(names=[name for name, _ in entries]):
                self.replace_apk(apk_bytes(entries))
                with self.assertRaises(observer.InvalidRecord):
                    self.observe()

    def test_zip_crc_corruption_is_rejected(self):
        raw = bytearray(apk_bytes(self.entries, zipfile.ZIP_STORED))
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            entry = archive.getinfo("classes.dex")
            offset = entry.header_offset
        name_size, extra_size = struct.unpack_from("<HH", raw, offset + 26)
        raw[offset + 30 + name_size + extra_size] ^= 1
        self.replace_apk(bytes(raw))
        with self.assertRaisesRegex(observer.InvalidRecord, "invalid APK ZIP"):
            self.observe()

    def test_zip_entry_and_expansion_limits_reject_before_decompression(self):
        raw = (self.root / self.audit["artifacts"][0]["apk"]).read_bytes()
        for limit, value in (("MAX_ENTRIES", 1), ("MAX_ENTRY_BYTES", 1),
                             ("MAX_EXPANDED_BYTES", 1), ("MAX_APK_BYTES", 1)):
            with self.subTest(limit=limit), patch.object(observer, limit, value):
                with patch.object(zipfile.ZipFile, "open", side_effect=AssertionError("must reject before expansion")):
                    with self.assertRaises(observer.InvalidRecord):
                        observer.zip_composition(raw, [sha(self.dex)])

    def test_bad_zip_and_unsupported_compression_are_rejected(self):
        for raw in (b"not a ZIP", apk_bytes(self.entries, zipfile.ZIP_BZIP2)):
            self.replace_apk(raw)
            with self.assertRaises(observer.InvalidRecord):
                self.observe()

    def test_apk_is_read_once_and_zip_uses_the_pinned_snapshot(self):
        original_read = observer.read_input
        host_path = self.root / self.audit["artifacts"][0]["apk"]
        count = []

        def read_then_replace(path, limit):
            raw = original_read(path, limit)
            if path == host_path:
                count.append(path)
                # Mutation after the stable read cannot substitute bytes into the ZIP observation.
                host_path.write_bytes(b"later unrelated bytes")
            return raw

        with patch.object(observer, "read_input", side_effect=read_then_replace):
            report = self.observe()
        self.assertEqual([host_path], count)
        self.assertEqual(self.audit["artifacts"][0]["sha256"], report["artifacts"]["host"]["sha256"])
        self.assertEqual(sha(self.dex), next(row["sha256"] for row in report["artifacts"]["host"]["zip"]["entries"]
                                          if row["name"] == "classes.dex"))

    def test_cli_creates_only_new_output_and_never_overwrites(self):
        output = self.root / "observation.json"
        argv = ["--root", str(self.root), "--audit", str(self.audit_path),
                "--audit-sha256", self.save_audit(), "--backend", "102",
                "--certificate-sha256", TEST_CERTIFICATE, "--out", str(output)]
        before = {path.name: path.read_bytes() for path in self.root.iterdir()}
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            self.assertEqual(0, observer.main(argv))
        result = json.loads(printed.getvalue())
        saved = output.read_bytes()
        self.assertEqual(sha(saved), result["sha256"])
        self.assertEqual("b10_saved_apk_composition_observation", json.loads(saved)["record_type"])
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.root.iterdir() if path != output})
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            self.assertEqual(1, observer.main(argv))
        self.assertEqual("invalid", json.loads(printed.getvalue())["result"])
        self.assertEqual(saved, output.read_bytes())

    def test_invalid_input_never_publishes_output(self):
        output = self.root / "absent-output.json"
        with self.assertRaises(observer.InvalidRecord):
            observer.write_observation(self.root, self.audit_path, "0" * 64, "102", TEST_CERTIFICATE, output)
        self.assertFalse(output.exists())
        with self.assertRaises(FileNotFoundError):
            observer.write_observation(self.root, self.audit_path, self.save_audit(), "102", TEST_CERTIFICATE,
                                       self.root / "absent-parent" / "output.json")
        self.assertFalse((self.root / "absent-parent").exists())


if __name__ == "__main__":
    unittest.main()
