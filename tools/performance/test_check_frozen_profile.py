import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from build_b07_pair_inputs import json_bytes
from check_frozen_profile import BUDGETS, HARD, check_profile, main, validate_profile


ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / "work/performance/performance_profile.json"
sha = lambda raw: hashlib.sha256(raw).hexdigest()


def frozen():
    value = json.loads(PROFILE.read_bytes())
    value["status"] = "FROZEN"
    value["freeze"] = dict(owner="synthetic fixture, not an authenticated owner", timestamp="2026-11-02T18:00:00+08:00",
                           baseline_aa_evidence=dict(path="baseline.json", bytes=1, sha256="a" * 64),
                           baseline_artifact_sha256="1" * 64, candidate_artifact_sha256="2" * 64,
                           feature_manifest_sha256="3" * 64, workload_sha256="4" * 64)
    value["device_cells"] = ["synthetic-cell"]
    value["statistics"] = dict(independent_sessions=2, samples_per_session=2,
                               warmup_policy={"calls": 3}, randomization_policy="synthetic AB/BA schedule",
                               analysis_method={"method": "synthetic fixture only"}, confidence_level=0.95)
    for metric in value["metrics"]:
        # Fixture-only values; never read a proposal or write production policy.
        metric.update(minimum_worthwhile_reduction_fraction=0.25, maximum_allowed_increase_fraction=0.125)
    value["budgets"] = {key: 0 for key in BUDGETS}
    return value


def saved_fixture(root):
    payload = b"synthetic linked evidence, not genuine measurements\n"
    (root / "evidence.txt").write_bytes(payload)
    ref = dict(path="evidence.txt", bytes=len(payload), sha256=sha(payload))
    names = ("plan", "capture", "result", "owned_stop", "raw_log", "session", "session_manifest", "single_verification")
    baseline = dict(schema_version=1, status="batch_records_collected_reported_only",
                    collection_evidence=dict(same_control_pairs=1, independent_processes=2,
                                             owned_stops_confirmed=2, samples=2, outcome_unknown=0,
                                             all_pair_results="reported_only"),
                    pairs=[dict(workload="B01", control="A0", order="AB", verification=ref,
                                sides=[dict(side=side, **{name: ref for name in names}) for side in "AB"])])
    baseline_raw = json_bytes(baseline)
    (root / "baseline.json").write_bytes(baseline_raw)
    value = frozen()
    value["freeze"]["baseline_aa_evidence"] = dict(path="baseline.json", bytes=len(baseline_raw), sha256=sha(baseline_raw))
    raw = json_bytes(value)
    path = root / "profile.json"; path.write_bytes(raw)
    return value, path, raw


class FrozenProfileTest(unittest.TestCase):
    def test_current_draft_and_incomplete_draft_are_blocked_without_mutation(self):
        raw = PROFILE.read_bytes(); value = json.loads(raw); saved = copy.deepcopy(value)
        report = validate_profile(value)
        self.assertEqual("blocked_not_frozen", report["result"])
        self.assertFalse(report["ab_authorized"])
        self.assertGreater(len(report["diagnostics"]), 10)
        self.assertEqual(saved, value)
        report = validate_profile({"status": "DRAFT_NOT_FROZEN"})
        self.assertEqual("blocked_not_frozen", report["result"])
        self.assertEqual("blocked_not_frozen", check_profile(ROOT, PROFILE, sha(raw))["result"])
        self.assertEqual(raw, PROFILE.read_bytes())

    def test_frozen_marker_alone_or_missing_required_fields_is_invalid(self):
        self.assertEqual("invalid", validate_profile({"status": "FROZEN"})["result"])
        for group, key in (("freeze", "owner"), ("freeze", "baseline_artifact_sha256"),
                           ("statistics", "analysis_method"), ("budgets", "additional_fd_count")):
            value = frozen(); del value[group][key]
            self.assertEqual("invalid", validate_profile(value)["result"])
        value = frozen(); value["device_cells"] = []
        self.assertEqual("invalid", validate_profile(value)["result"])

    def test_complete_structure_is_only_a_producer_declaration(self):
        value = frozen(); before = copy.deepcopy(value); report = validate_profile(value)
        self.assertEqual("frozen_declaration_structurally_valid", report["result"], report["diagnostics"])
        self.assertFalse(report["evidence_links_checked"])
        self.assertFalse(report["ab_authorized"])
        self.assertFalse(report["release_qualified"])
        self.assertEqual("not_evaluated", report["performance_gate"])
        self.assertEqual(before, value)

    def test_release_profiles_and_workload_rows_are_strict_but_valid_subsets_are_allowed(self):
        for releases in ([None], [False], [""], [" core-101"], ["core-101", "core-101"]):
            value = frozen(); value["release_profiles"] = releases
            self.assertEqual("invalid", validate_profile(value)["result"])
        for bad in (False, None, {}, {"id": "B01"}):
            value = frozen(); value["workloads"] = [bad]
            self.assertEqual("invalid", validate_profile(value)["result"])
        for fields in (dict(id="B11"), dict(name=""), dict(description=None), dict(status=False),
                       dict(controls=[]), dict(controls=[None]), dict(controls=["unknown"]),
                       dict(controls=["A0_direct_backend", "A0_direct_backend"]), dict(device_cells=None)):
            value = frozen(); value["workloads"][0].update(fields)
            self.assertEqual("invalid", validate_profile(value)["result"], fields)
        value = frozen(); value["workloads"] = [value["workloads"][0], copy.deepcopy(value["workloads"][0])]
        self.assertEqual("invalid", validate_profile(value)["result"])
        value = frozen(); value["workloads"] = value["workloads"][:2]
        self.assertEqual("frozen_declaration_structurally_valid", validate_profile(value)["result"])

    def test_numeric_booleans_nonfinite_values_and_wrong_ranges_are_invalid(self):
        for target, key, bad in (("statistics", "independent_sessions", True), ("statistics", "samples_per_session", 0),
                                 ("statistics", "confidence_level", True), ("statistics", "confidence_level", 1),
                                 ("statistics", "confidence_level", float("nan")),
                                 ("budgets", "additional_rss_bytes", False), ("budgets", "additional_fd_count", -1),
                                 ("hard_constraints", next(iter(HARD)), False)):
            value = frozen(); value[target][key] = bad
            self.assertEqual("invalid", validate_profile(value)["result"])
        for bad in (True, -0.1, 1.1, float("nan"), float("inf"), -float("inf")):
            value = frozen(); value["metrics"][0]["minimum_worthwhile_reduction_fraction"] = bad
            self.assertEqual("invalid", validate_profile(value)["result"])
        value = frozen(); value["metrics"][1] = copy.deepcopy(value["metrics"][0])
        self.assertEqual("invalid", validate_profile(value)["result"])

    def test_identity_timestamp_hash_and_baseline_pin_are_strict(self):
        for key, bad in (("schema_version", True), ("project", "other"), ("profile_id", "other"), ("status", "APPROVED")):
            value = frozen(); value[key] = bad
            self.assertEqual("invalid", validate_profile(value)["result"])
        for key, bad in (("timestamp", "2026-11-02T18:00:00"), ("timestamp", "2026-99-02T18:00:00Z"),
                         ("candidate_artifact_sha256", "A" * 64), ("owner", True),
                         ("baseline_aa_evidence", {"path": "baseline.json", "sha256": "a" * 64})):
            value = frozen(); value["freeze"][key] = bad
            self.assertEqual("invalid", validate_profile(value)["result"])

    def test_actual_profile_and_baseline_links_are_pinned_but_never_authorized(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); _, path, raw = saved_fixture(root)
            report = check_profile(root, path, sha(raw))
            self.assertEqual("frozen_declaration_structurally_valid", report["result"], report["diagnostics"])
            self.assertTrue(report["evidence_links_checked"])
            self.assertEqual("reported_only", report["baseline_evidence"]["pair_result"])
            self.assertEqual("not_evaluated", report["baseline_evidence"]["noise_sufficiency"])
            self.assertFalse(report["ab_authorized"])
            self.assertEqual("invalid", check_profile(root, path, "0" * 64)["result"])
            (root / "evidence.txt").write_bytes(b"changed")
            self.assertEqual("invalid", check_profile(root, path, sha(raw))["result"])

    def test_missing_baseline_unsupported_content_and_path_escape_are_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); value, path, raw = saved_fixture(root)
            baseline = root / "baseline.json"
            baseline.unlink()
            self.assertEqual("invalid", check_profile(root, path, sha(raw))["result"])
            baseline.write_bytes(b"{}")
            value["freeze"]["baseline_aa_evidence"].update(bytes=2, sha256=sha(b"{}"))
            raw = json_bytes(value); path.write_bytes(raw)
            self.assertEqual("invalid", check_profile(root, path, sha(raw))["result"])
            value["freeze"]["baseline_aa_evidence"]["path"] = "../outside.json"
            raw = json_bytes(value); path.write_bytes(raw)
            self.assertEqual("invalid", check_profile(root, path, sha(raw))["result"])

    def test_every_required_side_reference_must_be_a_complete_pin_and_draft_still_skips_baseline(self):
        names = ("plan", "capture", "result", "owned_stop", "raw_log", "session", "session_manifest", "single_verification")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); value, path, _ = saved_fixture(root)
            baseline_path = root / "baseline.json"
            original = json.loads(baseline_path.read_bytes())
            valid_pin = original["pairs"][0]["sides"][0]["raw_log"]
            invalid_pins = [None, {}] + [{key: item for key, item in valid_pin.items() if key != missing}
                                        for missing in ("path", "bytes", "sha256")]
            for name in names:
                for invalid in invalid_pins:
                    with self.subTest(evidence=name, invalid=invalid):
                        baseline = copy.deepcopy(original)
                        baseline["pairs"][0]["sides"][0][name] = invalid
                        baseline_raw = json_bytes(baseline); baseline_path.write_bytes(baseline_raw)
                        value["freeze"]["baseline_aa_evidence"].update(bytes=len(baseline_raw), sha256=sha(baseline_raw))
                        raw = json_bytes(value); path.write_bytes(raw)
                        report = check_profile(root, path, sha(raw))
                        self.assertEqual("invalid", report["result"])
                        self.assertFalse(report["evidence_links_checked"])
                        self.assertIn(name, report["diagnostics"][-1]["message"])
            value["status"] = "DRAFT_NOT_FROZEN"
            raw = json_bytes(value); path.write_bytes(raw)
            with patch("check_frozen_profile._baseline", side_effect=AssertionError("draft must not read baseline")) as reader:
                report = check_profile(root, path, sha(raw))
            reader.assert_not_called()
            self.assertEqual("blocked_not_frozen", report["result"])
            self.assertFalse(report["evidence_links_checked"])

    def test_cli_emits_draft_block_and_rejects_nonstandard_json(self):
        raw = PROFILE.read_bytes(); args = ["check_frozen_profile.py", str(PROFILE), "--root", str(ROOT), "--profile-sha256", sha(raw)]
        output = io.StringIO()
        with patch("sys.argv", args), redirect_stdout(output):
            self.assertEqual(2, main())
        self.assertEqual("blocked_not_frozen", json.loads(output.getvalue())["result"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); bad = b'{"status":"FROZEN","extra":NaN}'
            path = root / "bad.json"; path.write_bytes(bad)
            self.assertEqual("invalid", check_profile(root, path, sha(bad))["result"])


if __name__ == "__main__":
    unittest.main()
