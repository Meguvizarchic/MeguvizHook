import copy
import json
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import loop_control_comparison as comparison
import test_check_frozen_profile as profile_fixtures
import test_verify_loop_pilot_pair as pair_fixtures


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        blocked = patch.object(subprocess, "Popen", side_effect=AssertionError("offline comparison tests cannot start a process"))
        blocked.start(); self.addCleanup(blocked.stop)
        self.freeze = self.make_freeze()

    def write(self, name, value):
        path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True)
        raw = value if isinstance(value, bytes) else comparison.common.json_bytes(value)
        path.write_bytes(raw)
        return dict(path=path.relative_to(self.root).as_posix(), bytes=len(raw), sha256=comparison.common.digest(raw))

    def make_freeze(self):
        artifacts = {}
        for label, package in (("host", comparison.collector.HOST), ("module", comparison.collector.MODULES["102"])):
            artifacts[label] = dict(self.write(label + ".apk", ("synthetic " + label).encode()), package=package, certificate_sha256="f" * 64)
        return dict(schema_version=1, record_type="loop_control_comparison_freeze", status="FROZEN", freeze_id=str(uuid.uuid4()),
                    frozen_utc="2026-11-02T19:00:00+08:00", scope="loop_cpu_envelope_only", formal_gate_enabled=False,
                    profile_pin=dict(path=comparison.PROFILE_PATH, bytes=1, sha256="a" * 64), controls=dict(baseline="A0", candidate="A2"),
                    workloads=["B01", "B02"], backend="102", parameters=dict(warmup=3, samples=2, iterations=5, measurement_mode="unobserved"),
                    device=dict(device_id_sha256="1" * 64, build_fingerprint_sha256="2" * 64,
                                boot_id="721f4de7-706b-42b3-bb9e-91f23a8915a9", android_api=33, abi="arm64-v8a", android_user=0,
                                framework_name="fixture", framework_version="1", reported_api=102),
                    artifacts=artifacts, artifact_audit=self.write("audit.json", {"scope": "synthetic fixture, not signature proof"}),
                    workload_sha256="3" * 64, feature_manifest_sha256="4" * 64, fixed_input_manifest_sha256="5" * 64,
                    source_aa_inputs=dict(path="baseline.json", bytes=1, sha256="a" * 64), source_aa_noise_report=dict(path="noise.json", bytes=1, sha256="b" * 64),
                    pairs_per_workload=2, analysis_method=comparison.METHOD, aa_stability_rule="explicit synthetic declaration; sufficiency not evaluated",
                    randomization_policy="externally registered balanced AB/BA order",
                    thresholds=dict(minimum_worthwhile_reduction_fraction=dict(numerator="1", denominator=3),
                                    maximum_allowed_increase_fraction=dict(numerator="1", denominator=7)))

    def sources(self):
        value = self.freeze
        groups, rows = [], []
        config = dict(warmup=3, samples=2, iterations=5, measurement_kind="thread_cpu_envelope_ns")
        hashes = dict(host_apk_sha256=value["artifacts"]["host"]["sha256"], module_apk_sha256=value["artifacts"]["module"]["sha256"],
                      workload_sha256=value["workload_sha256"], feature_manifest_sha256=value["feature_manifest_sha256"])
        device = {key: value["device"][key] for key in comparison.sides.SESSION_DEVICE}
        payload = self.write("evidence.json", {"scope": "synthetic linked declaration"})
        for workload in ("B01", "B02"):
            for control in ("A0", "A2"):
                pair_id = str(uuid.uuid4())
                counts = dict(zero_samples=0, sample_count=2)
                groups.append(dict(identity=dict(workload=workload, control=control, backend="102", config=config, artifacts=hashes, device=device),
                                   pair_count=1, unresolved_ratio_count=0, pairs=[dict(pair_id=pair_id, A=counts, B=counts)]))
                sides = []
                for side in "AB":
                    identity = dict(side=side, run_id=str(uuid.uuid4()), session_id=str(uuid.uuid4()), pair_id=pair_id,
                                    workload=workload, control=control)
                    plan = self.write(workload + control + side + "-plan.json", dict(schema_version=1, record_type="loop_pilot_plan",
                                      created_utc="1990-01-01T00:00:00Z", **identity))
                    capture = self.write(workload + control + side + ".json", dict(device=value["device"], artifacts=value["artifacts"],
                                         parameters=value["parameters"], backend="102", plan_sha256=plan["sha256"], **identity))
                    refs = {key: payload for key in ("result", "owned_stop", "raw_log", "session", "session_manifest", "single_verification")}
                    sides.append(dict(side=side, capture=capture, plan=plan, **refs))
                rows.append(dict(workload=workload, control=control, order="AB", verification=payload, sides=sides))
        noise = dict(record_type="pilot_aa_noise_description", result="descriptive_only", groups=groups)
        value["source_aa_noise_report"] = self.write("noise.json", noise)
        baseline = dict(schema_version=1, status="batch_records_collected_reported_only", noise_description=value["source_aa_noise_report"],
                        collection_evidence=dict(same_control_pairs=4, independent_processes=8, owned_stops_confirmed=8,
                                                 samples=16, outcome_unknown=0, all_pair_results="reported_only"), pairs=rows)
        value["source_aa_inputs"] = self.write("baseline.json", baseline)
        return baseline, noise

    def profile(self):
        profile = profile_fixtures.frozen()
        profile["freeze"].update(timestamp="2026-11-02T18:00:00+08:00", baseline_aa_evidence=self.freeze["source_aa_inputs"],
                                baseline_artifact_sha256=self.freeze["artifacts"]["module"]["sha256"],
                                candidate_artifact_sha256=self.freeze["artifacts"]["module"]["sha256"],
                                workload_sha256=self.freeze["workload_sha256"], feature_manifest_sha256=self.freeze["feature_manifest_sha256"])
        profile["device_cells"] = [self.freeze["device"]]
        profile["statistics"].update(independent_sessions=2, samples_per_session=2, analysis_method=self.freeze["analysis_method"],
                                     randomization_policy=self.freeze["randomization_policy"], warmup_policy=dict(warmup=3, iterations=5, measurement_mode="unobserved"))
        self.freeze["profile_pin"] = self.write(comparison.PROFILE_PATH, profile)
        return profile

    def test_draft_global_profile_blocks_before_inputs_or_output_without_mutation(self):
        draft = profile_fixtures.PROFILE.read_bytes()
        self.freeze["profile_pin"] = self.write(comparison.PROFILE_PATH, draft)
        reference = self.write("freeze.json", self.freeze)
        with self.assertRaises(comparison.FrozenProfileBlocked) as blocked:
            comparison.prepare(self.root, self.root / reference["path"], reference["sha256"], self.root / "missing-inputs.json", "0" * 64, self.root / "output")
        self.assertEqual("blocked_not_frozen", blocked.exception.report["result"])
        self.assertEqual(draft, (self.root / comparison.PROFILE_PATH).read_bytes()); self.assertFalse((self.root / "output").exists())

    def test_freeze_method_types_32_pair_bound_and_exact_loop_thresholds(self):
        self.freeze["pairs_per_workload"] = 16
        self.assertEqual(16, comparison.validate_freeze(comparison.common.json_bytes(self.freeze))["pairs_per_workload"])
        self.freeze["analysis_method"] = dict(method=comparison.METHOD)
        comparison.validate_freeze(comparison.common.json_bytes(self.freeze))
        for change in (dict(pairs_per_workload=18), dict(analysis_method="unsupported"), dict(formal_gate_enabled=True)):
            value = dict(self.freeze, **change)
            with self.assertRaises(comparison.InvalidRecord): comparison.validate_freeze(comparison.common.json_bytes(value))
        for fraction in (None, dict(numerator="2", denominator=2), dict(numerator="01", denominator=3), dict(numerator="1", denominator=True), dict(numerator="4", denominator=3)):
            value = copy.deepcopy(self.freeze); value["thresholds"]["minimum_worthwhile_reduction_fraction"] = fraction
            with self.assertRaises(comparison.InvalidRecord): comparison.validate_freeze(comparison.common.json_bytes(value))
        for invalid in (" ", "null", " NULL ", "untrimmed ", "bad\nrule"):
            value = copy.deepcopy(self.freeze); value["aa_stability_rule"] = invalid
            with self.assertRaises(comparison.InvalidRecord): comparison.validate_freeze(comparison.common.json_bytes(value))

    def test_timezone_order_accepts_explicit_offsets_and_rejects_naive(self):
        self.assertEqual(comparison.utc("2026-11-02T19:00:00+08:00"), comparison.utc("2026-11-02T11:00:00Z"))
        self.assertLess(comparison.utc("2026-11-02T18:00:00+08:00"), comparison.utc("2026-11-02T11:00:00Z"))
        with self.assertRaises(comparison.InvalidRecord): comparison.utc("2026-11-02T11:00:00")

    def test_real_file_profile_and_baseline_links_then_b01_subset_are_accepted_as_declarations_only(self):
        self.sources(); self.profile(); reference = self.write("freeze.json", self.freeze)
        value, checked = comparison.check_freeze(self.root, reference)
        self.assertEqual(self.freeze, value); self.assertTrue(checked["evidence_links_checked"]); self.assertFalse(checked["ab_authorized"])
        self.freeze["workloads"] = ["B01"]; reference = self.write("freeze.json", self.freeze)
        comparison.check_freeze(self.root, reference)

    def test_different_baseline_noise_missing_group_and_illegal_control_are_rejected(self):
        baseline, source_noise = self.sources(); profile = self.profile()
        for kind in ("baseline", "noise", "missing", "illegal"):
            value = copy.deepcopy(self.freeze)
            if kind == "baseline": value["source_aa_inputs"] = self.write("other-baseline.json", baseline)
            elif kind == "noise": value["source_aa_noise_report"] = self.write("other-noise.json", source_noise)
            else:
                noise = copy.deepcopy(source_noise)
                if kind == "missing": noise["groups"].pop()
                else: noise["groups"][0]["identity"]["control"] = "A1"
                value["source_aa_noise_report"] = self.write("changed-noise.json", noise)
                changed = copy.deepcopy(baseline); changed["noise_description"] = value["source_aa_noise_report"]
                value["source_aa_inputs"] = self.write("changed-baseline.json", changed)
            frozen_profile = copy.deepcopy(profile)
            if kind in ("missing", "illegal"): frozen_profile["freeze"]["baseline_aa_evidence"] = value["source_aa_inputs"]
            with self.subTest(kind=kind), self.assertRaises(comparison.InvalidRecord): comparison.check_source_aa(self.root, value, frozen_profile)

    def test_pair_id_zero_config_or_capture_boot_contradictions_are_rejected(self):
        baseline, source_noise = self.sources(); profile = self.profile()
        for kind in ("pair", "zero", "config", "boot"):
            value = copy.deepcopy(self.freeze); noise = copy.deepcopy(source_noise); changed = copy.deepcopy(baseline)
            if kind == "pair": noise["groups"][0]["pairs"][0]["pair_id"] = str(uuid.uuid4())
            elif kind == "zero": noise["groups"][0]["pairs"][0]["A"]["zero_samples"] = 1
            elif kind == "config": noise["groups"][0]["identity"]["config"]["iterations"] += 1
            else:
                old = changed["pairs"][0]["sides"][0]["capture"]
                capture = json.loads((self.root / old["path"]).read_bytes()); capture["device"]["boot_id"] = str(uuid.uuid4())
                changed["pairs"][0]["sides"][0]["capture"] = self.write("changed-capture.json", capture)
            value["source_aa_noise_report"] = self.write("changed-noise.json", noise); changed["noise_description"] = value["source_aa_noise_report"]
            value["source_aa_inputs"] = self.write("changed-baseline.json", changed)
            frozen_profile = copy.deepcopy(profile); frozen_profile["freeze"]["baseline_aa_evidence"] = value["source_aa_inputs"]
            with self.subTest(kind=kind), self.assertRaises(comparison.InvalidRecord): comparison.check_source_aa(self.root, value, frozen_profile)

    def test_global_workload_control_backend_and_local_device_scope_are_required(self):
        self.sources(); original = self.profile()
        for kind in ("workload", "control", "backend", "device"):
            profile = copy.deepcopy(original)
            if kind == "workload": profile["workloads"] = [row for row in profile["workloads"] if row["id"] != "B02"]
            elif kind == "control":
                next(row for row in profile["workloads"] if row["id"] == "B02")["controls"].remove("A2_meguvizhook_same_semantics")
            elif kind == "backend": profile["release_profiles"].remove("core-102")
            else: next(row for row in profile["workloads"] if row["id"] == "B02")["device_cells"] = ["different synthetic cell"]
            self.freeze["profile_pin"] = self.write(comparison.PROFILE_PATH, profile)
            reference = self.write("freeze.json", self.freeze)
            with self.subTest(kind=kind), self.assertRaises(comparison.InvalidRecord): comparison.check_freeze(self.root, reference)

    def test_baseline_plan_declared_after_profile_freeze_is_rejected(self):
        self.sources(); profile = self.profile()
        profile["freeze"]["timestamp"] = "1980-01-01T00:00:00Z"
        with self.assertRaises(comparison.InvalidRecord): comparison.check_source_aa(self.root, self.freeze, profile)

    def test_complete_synthetic_prepare_then_verify_reports_exact_envelope_points_only(self):
        # Every artifact, frozen declaration, clock, and observation below is synthetic and local.
        tools = self.root / "tools/performance"; tools.mkdir(parents=True)
        for name in comparison.TOOLS:
            shutil.copyfile(Path(comparison.__file__).with_name(name), tools / name)
        adb = self.root / "synthetic-adb.exe"; adb.write_bytes(b"synthetic fixture, never executed")
        support = dict(workloads=["B01"], controls=["A0", "A2"], measurement_modes=["unobserved"], backends=["102"])
        declarations = dict(loop_support=support, loop_limits=comparison.collector.LIMITS,
                            loop_measurement_kinds=comparison.collector.KINDS)
        verification, prepared = {}, {}
        for name in comparison.collector.guards.VERIFY_REFS:
            ref = self.write("synthetic-inputs/" + name + ".json", {"scope": "synthetic local verification declaration only"})
            verification[name] = ref["path"]; verification[name + "_sha256"] = ref["sha256"]
        for name, hash_key in comparison.collector.guards.PREPARED_REFS:
            ref = self.write("synthetic-inputs/" + name + ".json", declarations if name == "feature_manifest" else {"scope": "synthetic"})
            prepared[name] = ref["path"]; prepared[hash_key] = ref["sha256"]
        rows = [dict(apk=item["path"], bytes=item["bytes"], sha256=item["sha256"], package=item["package"],
                     certificate_sha256=item["certificate_sha256"], variant="deviceTest", signature_verified=True,
                     scheme_v2_verified=True, runtime_isolation_gate="passed") for item in self.freeze["artifacts"].values()]
        audit = self.write("audit.json", dict(status="signed_artifacts_locally_verified", artifacts=rows,
                          verification=verification, prepared_inputs=prepared, **declarations))
        self.freeze.update(workloads=["B01"], frozen_utc="2000-01-01T00:00:00Z", artifact_audit=audit,
                           workload_sha256=prepared["workload_sha256"], feature_manifest_sha256=prepared["feature_manifest_sha256"])
        _, pins, _ = comparison.collector.audit_inputs(self.root, self.root / audit["path"], audit["sha256"], "102", "B01", "A0", "unobserved")
        pins.extend(comparison.common.file_pin(self.root, "tools/performance/" + name) for name in comparison.collector.TOOLS)
        self.freeze["fixed_input_manifest_sha256"] = comparison.collector.fixed_input_manifest_sha256(
            dict(pins=pins, adb=comparison.common.file_pin(self.root, adb, external=True)))
        self.sources(); profile = self.profile(); profile["freeze"]["timestamp"] = "1999-12-31T23:00:00Z"
        self.freeze["profile_pin"] = self.write(comparison.PROFILE_PATH, profile)
        freeze_ref = self.write("synthetic-freeze.json", self.freeze)
        registered = []
        for position, order in enumerate(("AB", "BA"), 1):
            parent = self.root / ("synthetic-pair-%d" % position); parent.mkdir()
            pair_id, clock = str(uuid.uuid4()), str(uuid.uuid4()); plans = {}
            for side, control in (("A", "A0"), ("B", "A2")):
                saved = comparison.collector.prepare(self.root, self.root / audit["path"], audit["sha256"], adb, "102",
                           self.freeze["device"]["device_id_sha256"], "B01", control, "unobserved", 3, 2, 5, side, order,
                           "fixture", "1", 102, parent / side, pair_id, clock)
                plans[side] = comparison.common.file_pin(self.root, saved["plan"], saved["plan_sha256"])
            registered.append(dict(position=position, workload="B01", order=order, plans=plans))
        inputs_ref = self.write("synthetic-comparison-inputs.json", dict(schema_version=1, record_type="loop_control_comparison_inputs",
                               randomization_policy=self.freeze["randomization_policy"], pairs=registered))
        prepared_plan = comparison.prepare(self.root, self.root / freeze_ref["path"], freeze_ref["sha256"],
                            self.root / inputs_ref["path"], inputs_ref["sha256"], self.root / "synthetic-comparison")
        self.assertEqual("comparison_plan_prepared_after_freeze", prepared_plan["result"])
        comparison_plan = json.loads(Path(prepared_plan["plan"]).read_bytes())
        clock_id = str(uuid.uuid4())
        claim_ref = self.write("synthetic-claim.json", dict(schema_version=1, record_type="loop_control_comparison_claim",
                        comparison_id=comparison_plan["comparison_id"], comparison_plan_sha256=prepared_plan["plan_sha256"],
                        freeze_sha256=freeze_ref["sha256"], profile_sha256=self.freeze["profile_pin"]["sha256"],
                        created_utc=comparison_plan["created_utc"], host_clock_id=clock_id,
                        freeze_checked_monotonic_ns=1, profile_checked_monotonic_ns=2, claim_monotonic_ns=3, no_retry=True))
        evidence_rows, ordinal, originals = [], 0, []
        for row in registered:
            side_refs = {}
            for side in row["order"]:
                control = "A0" if side == "A" else "A2"
                plan_raw = (self.root / row["plans"][side]["path"]).read_bytes(); plan = json.loads(plan_raw)
                bundle = pair_fixtures.fixture(row["order"], "102", control, "B01", "unobserved")[0 if side == "A" else 1]
                bundle["plan_bytes"] = plan_raw; capture = bundle["capture"]
                capture.update({key: plan[key] for key in comparison.sides.BOUND})
                launch = 100 + ordinal * 200; terminal = launch + 50; returned = terminal + 10
                process = dict(pid=2000 + ordinal, uid=10123, start_ticks=10 + ordinal * 10, cmdline=comparison.collector.HOST)
                capture.update(plan_sha256=row["plans"][side]["sha256"], fixed_input_manifest_sha256=self.freeze["fixed_input_manifest_sha256"],
                               process=process, controller_tid=3000 + ordinal, device=copy.deepcopy(self.freeze["device"]),
                               launch_monotonic_ns=launch, terminal_monotonic_ns=terminal)
                bundle["result"].update({key: capture[key] for key in ("plan_sha256", "workload", "control", "backend",
                                         "launch_monotonic_ns", "terminal_monotonic_ns", "performance_gate", "device_authentication")})
                bundle["result"]["owned"] = process; bundle["owned_stop"]["owned"] = process
                def observations(events):
                    for event in events:
                        event.update({key: plan[key] for key in ("run_id", "pair_id", "session_id", "side", "workload", "control")})
                        event["pid"] = process["pid"]
                        if event["event"] == "start": event.update(warmup=3, samples=2, iterations=5)
                        elif event["event"] == "configured": event.update(plan["expected_framework"])
                        elif event["event"] == "sample":
                            start = 1000 + event["index"] * 100
                            event.update(iterations=5, thread_cpu_start_ns=start, thread_cpu_end_ns=start + (30 if control == "A0" else 45),
                                         checksum="%016x" % (42 * 5))
                pair_fixtures.rebuild(bundle, observations)
                self.assertEqual("reported_only", bundle["result"]["result"])
                self.assertEqual("not_verified", bundle["session"]["preflight"]["status"])
                originals.append(bundle["raw"])
                prefix = (self.root / row["plans"][side]["path"]).parent.relative_to(self.root).as_posix() + "/execution/"
                refs = {}
                for key in comparison.BUNDLE_NAMES:
                    name = "raw-threadtime.log" if key == "raw" else "owned-stop.json" if key == "owned_stop" else "session-manifest.json" if key == "session_manifest" else key + ".json"
                    refs[key] = self.write(prefix + name, bundle[key])
                refs["receipt"] = self.write(prefix + "synthetic-receipt.json", dict(schema_version=1, record_type="loop_control_comparison_side_receipt",
                         comparison_plan_sha256=prepared_plan["plan_sha256"], claim_sha256=claim_ref["sha256"], freeze_sha256=freeze_ref["sha256"],
                         profile_sha256=self.freeze["profile_pin"]["sha256"], side_plan_sha256=row["plans"][side]["sha256"],
                         **{key: plan[key] for key in ("run_id", "session_id", "side", "control", "host_clock_token")}, host_clock_id=clock_id,
                         freeze_checked_monotonic_ns=launch - 20, profile_checked_monotonic_ns=launch - 15,
                         gate_monotonic_ns=launch - 10, collector_return_monotonic_ns=returned))
                side_refs[side] = refs; ordinal += 1
            evidence_rows.append(dict(position=row["position"], sides=side_refs))
        evidence_ref = self.write("synthetic-evidence.json", dict(schema_version=1, record_type="loop_control_comparison_evidence",
                                 comparison_plan_sha256=prepared_plan["plan_sha256"], claim=claim_ref, pairs=evidence_rows))
        report = comparison.verify(self.root, Path(prepared_plan["plan"]), prepared_plan["plan_sha256"],
                                   self.root / evidence_ref["path"], evidence_ref["sha256"])
        self.assertEqual("comparison_descriptive_only", report["result"])
        self.assertEqual(dict(numerator="1", denominator=2), report["group_median_A2_over_A0_minus_one"]["B01"])
        for item in report["pairs"]:
            self.assertEqual(dict(numerator="1", denominator=2), item["A2_over_A0_minus_one"])
            self.assertEqual(dict(numerator="3", denominator=1), item["A2_minus_A0_ns_per_top_level_call"])
            self.assertEqual(dict(A0="reported_only", A2="reported_only"), item["source_side_results"])
        for name in ("confidence_interval", "threshold_evaluation", "benefit_conclusion", "performance_gate"):
            self.assertEqual("not_evaluated", report[name])
        self.assertFalse(report["ab_authorized"]); self.assertFalse(report["release_qualified"])
        self.assertTrue(all(value is None for value in report["formal_metrics"].values()))
        self.assertFalse(report["profile_check"]["ab_authorized"])
        self.assertEqual(self.freeze["thresholds"], report["frozen_loop_thresholds"])
        self.assertEqual(4, len(originals))


if __name__ == "__main__":
    unittest.main()
