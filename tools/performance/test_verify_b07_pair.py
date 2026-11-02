import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from test_verify_b07_install import events, log
from verify_b07_pair import main, verify_pair


PAIR = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
CLOCK = "9a6bd7a5-c99d-4e27-8594-c0486158f92b"
BOOT = "721f4de7-706b-42b3-bb9e-91f23a8915a9"
IDS = {"A": ("6abefc46-11fe-4f53-8aa6-fba235cb679c", "132ef2bf-a65d-4ca5-885a-e118b487f003"),
       "B": ("9755607f-1368-44a6-bb40-797613a73dee", "ade89657-0bea-4f5f-a56c-b89034b437a3")}


def fixture(order="AB", backend="102"):
    manifests, raws = [], []
    for index, side in enumerate(("A", "B")):
        rank = order.index(side)
        pid, tid = 1234 + index, 5678 + index
        run, session = IDS[side]
        selected = events(backend)
        for event in selected:
            event.update(run_id=run, pair_id=PAIR, session_id=session, pid=pid, side=side)
        selected[1].update(wall_start_ns=100 + rank * 1000, wall_end_ns=150 + rank * 1000)
        raw = log(selected, pid, tid)
        record = {"schema_version": 1, "record_type": "b07_install_probe_side", "pair_id": PAIR,
                  "run_id": run, "session_id": session, "side": side, "pair_order": order,
                  "pid": pid, "tid": tid, "host_uid": 10123, "process_start_ticks": 10 + rank * 10,
                  "backend": backend, "control": "A2", "workload": "B07", "warmup": 0,
                  "samples": 1, "iterations": 1, "measurement_kind": "install_wall_and_thread_cpu_ns",
                  "artifacts": {"host_apk_sha256": "1" * 64, "module_apk_sha256": "2" * 64,
                                "workload_sha256": "3" * 64, "feature_manifest_sha256": "4" * 64},
                  "device": {"device_id_sha256": "5" * 64, "build_fingerprint_sha256": "6" * 64,
                             "boot_id": BOOT, "android_api": 33, "abi": "arm64-v8a", "android_user": 0,
                             "framework_name": "test", "framework_version": "1", "reported_api": int(backend)},
                  "host_clock_token": CLOCK, "launch_monotonic_ns": 100 + rank * 200,
                  "terminal_monotonic_ns": 200 + rank * 200,
                  "raw_log_sha256": hashlib.sha256(raw).hexdigest()}
        manifests.append(record)
        raws.append(raw)
    return manifests, raws


def replace_events(manifests, raws, index, change):
    # Rebuild the selected bytes, retaining independently declared metadata.
    record = manifests[index]
    selected = events(record["backend"])
    for event in selected:
        event.update(run_id=record["run_id"], pair_id=record["pair_id"], session_id=record["session_id"],
                     pid=record["pid"], side=record["side"])
    rank = record["pair_order"].index(record["side"])
    selected[1].update(wall_start_ns=100 + rank * 1000, wall_end_ns=150 + rank * 1000)
    change(selected)
    raws[index] = log(selected, record["pid"], record["tid"])
    record["raw_log_sha256"] = hashlib.sha256(raws[index]).hexdigest()


class B07PairTest(unittest.TestCase):
    def test_both_backends_orders_and_input_positions(self):
        for backend in ("101", "102"):
            for order in ("AB", "BA"):
                records, raws = fixture(order, backend)
                before = copy.deepcopy(records), list(raws)
                for swapped in (False, True):
                    i, j = (1, 0) if swapped else (0, 1)
                    report = verify_pair(records[i], records[j], raws[i], raws[j])
                    self.assertEqual("install_pair_consistent", report["result"])
                    self.assertEqual(order, report["pair_order"])
                    self.assertEqual({"numerator": "1", "denominator": "1"}, report["descriptive_comparison"]["wall"]["B_over_A"])
                    self.assertEqual(0, report["descriptive_comparison"]["cpu"]["B_minus_A_ns"])
                    self.assertEqual("not_evaluated", report["performance_gate"])
                    self.assertEqual("not_verified", report["device_authentication"])
                    for index, key in enumerate(("one", "two")):
                        expected = (raws[i], raws[j])[index]
                        self.assertEqual(hashlib.sha256(expected).hexdigest(), report["inputs"][key]["raw_log_sha256"])
                self.assertEqual(before, (records, raws))

    def test_metadata_mismatches_are_invalid_with_both_reports(self):
        for path, value in ((('artifacts', 'host_apk_sha256'), "a" * 64),
                            (('artifacts', 'module_apk_sha256'), "a" * 64),
                            (('artifacts', 'workload_sha256'), "a" * 64),
                            (('artifacts', 'feature_manifest_sha256'), "a" * 64),
                            (('device', 'boot_id'), CLOCK), (('device', 'android_user'), 1),
                            (('host_uid',), 110123), (('device', 'android_api'), 34),
                            (('device', 'device_id_sha256'), "a" * 64),
                            (('device', 'build_fingerprint_sha256'), "a" * 64),
                            (('host_clock_token',), BOOT), (('pair_order',), 'BA')):
            records, raws = fixture()
            target = records[1]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path):
                report = verify_pair(*records, *raws)
                self.assertEqual("invalid", report["result"])
                self.assertIn("single_side_report", report["inputs"]["one"])
                self.assertIn("single_side_report", report["inputs"]["two"])

    def test_framework_declarations_must_match_selected_log(self):
        for key, value in (("framework_name", "other"), ("framework_version", "2"), ("reported_api", 103)):
            records, raws = fixture()
            for record in records:
                record["device"][key] = value
            report = verify_pair(*records, *raws)
            self.assertEqual("invalid", report["result"])
            self.assertEqual(100, report["sides"]["A"]["install_observations"][0]["endpoints_ns"]["wall_start_ns"])

    def test_ids_side_and_process_independence(self):
        for mode in ("run", "session", "cross_id", "pair", "side", "same_process"):
            records, raws = fixture()
            if mode == "run": records[1]["run_id"] = records[0]["run_id"]
            elif mode == "session": records[1]["session_id"] = records[0]["session_id"]
            elif mode == "cross_id": records[1]["session_id"] = records[0]["run_id"]
            elif mode == "pair": records[1]["pair_id"] = BOOT
            elif mode == "side": records[1]["side"] = "A"
            else:
                records[1]["pid"] = records[0]["pid"]
                records[1]["process_start_ticks"] = records[0]["process_start_ticks"]
            with self.subTest(mode=mode):
                self.assertEqual("invalid", verify_pair(*records, *raws)["result"])

    def test_pid_reuse_with_later_start_is_permitted(self):
        records, raws = fixture()
        records[1]["pid"] = records[0]["pid"]
        replace_events(records, raws, 1, lambda selected: None)
        self.assertEqual("install_pair_consistent", verify_pair(*records, *raws)["result"])

    def test_three_order_checks_and_equal_boundaries(self):
        for mode in ("ticks", "host", "device", "equal_host", "equal_device"):
            records, raws = fixture()
            if mode == "ticks": records[1]["process_start_ticks"] = records[0]["process_start_ticks"]
            elif mode in ("host", "equal_host"):
                records[1]["launch_monotonic_ns"] = 199 if mode == "host" else 200
            else:
                start = 149 if mode == "device" else 150
                replace_events(records, raws, 1, lambda selected: selected[1].update(wall_start_ns=start, wall_end_ns=1200))
            with self.subTest(mode=mode):
                self.assertEqual("invalid", verify_pair(*records, *raws)["result"])

    def test_cpu_absolute_values_are_not_cross_process_order(self):
        records, raws = fixture()
        replace_events(records, raws, 0, lambda selected: selected[1].update(cpu_start_ns=9000, cpu_end_ns=9020))
        self.assertEqual("install_pair_consistent", verify_pair(*records, *raws)["result"])

    def test_zero_retained_without_division_or_gate(self):
        for index in (0, 1):
            records, raws = fixture()
            replace_events(records, raws, index, lambda selected: selected[1].update(cpu_end_ns=10))
            report = verify_pair(*records, *raws)
            self.assertEqual("unresolved", report["result"])
            self.assertEqual(0, report["sides"]["AB"[index]]["cpu"]["elapsed_ns"])
            self.assertIsNone(report["descriptive_comparison"]["cpu"]["B_over_A"])

    def test_invalid_precedes_incomplete_and_both_endpoint_sets_survive(self):
        records, raws = fixture()
        replace_events(records, raws, 0, lambda selected: selected[1].update(cpu_start_ns=-1))
        replace_events(records, raws, 1, lambda selected: selected.pop())
        report = verify_pair(*records, *raws)
        self.assertEqual("invalid", report["result"])
        self.assertEqual("incomplete", report["sides"]["B"]["result"])
        self.assertEqual(-1, report["sides"]["A"]["install_observations"][0]["endpoints_ns"]["cpu_start_ns"])
        self.assertEqual(1100, report["sides"]["B"]["install_observations"][0]["endpoints_ns"]["wall_start_ns"])
        self.assertNotIn("descriptive_comparison", report)

    def test_incomplete_precedes_zero_and_reported_failure_never_passes(self):
        records, raws = fixture()
        replace_events(records, raws, 0, lambda selected: selected[1].update(cpu_end_ns=10))
        replace_events(records, raws, 1, lambda selected: selected[4].update(status="fail"))
        self.assertEqual("incomplete", verify_pair(*records, *raws)["result"])

    def test_raw_digest_mismatch_preserves_other_side(self):
        records, raws = fixture()
        raws[0] += b"different bytes\n"
        report = verify_pair(*records, *raws)
        self.assertEqual("invalid", report["result"])
        self.assertEqual("install_record_consistent", report["sides"]["B"]["result"])
        self.assertEqual(hashlib.sha256(raws[0]).hexdigest(), report["inputs"]["one"]["raw_log_sha256"])

    def test_deep_raw_json_is_invalid_and_peer_remains_available(self):
        records, raws = fixture()
        deep = (b'11-01 10:20:30.123 1234 5678 I MeguvizHookBench: {"run_id":"'
                + IDS["A"][0].encode() + b'","deep":' + b'[' * 2000 + b'0' + b']' * 2000 + b'}\n')
        raws[0] = deep
        records[0]["raw_log_sha256"] = hashlib.sha256(deep).hexdigest()
        report = verify_pair(*records, *raws)
        self.assertEqual("invalid", report["result"])
        self.assertEqual(hashlib.sha256(deep).hexdigest(), report["inputs"]["one"]["raw_log_sha256"])
        self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])
        code, result, _ = self.run_cli(records, raws)
        self.assertEqual(1, code)
        self.assertEqual("install_record_consistent", result["sides"]["B"]["result"])

    def test_missing_or_illegal_manifest_preserves_valid_peer(self):
        for mode in ("missing", "illegal", "both", "wrong_type", "missing_record", "raw_missing"):
            records, raws = fixture()
            expected = "invalid"
            if mode == "missing": del records[0]["pid"]; expected = "incomplete"
            elif mode == "illegal": records[0]["pid"] = True
            elif mode == "both": del records[0]["pid"]; records[0]["unknown"] = 1
            elif mode == "wrong_type": records[0] = []
            elif mode == "missing_record": records[0] = None; expected = "incomplete"
            else: raws[0] = None; expected = "incomplete"
            with self.subTest(mode=mode):
                report = verify_pair(*records, *raws)
                self.assertEqual(expected, report["result"])
                self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])
                if raws[0] is not None:
                    self.assertEqual(hashlib.sha256(raws[0]).hexdigest(), report["inputs"]["one"]["raw_log_sha256"])

    def test_manifest_types_nested_fields_and_unknown_workloads(self):
        for key, value in (("warmup", False), ("samples", 2), ("iterations", 0), ("host_uid", -1),
                           ("process_start_ticks", 0), ("measurement_kind", "other"), ("workload", "B08"),
                           ("control", "A0"), ("raw_log_sha256", "x" * 64)):
            records, raws = fixture()
            records[0][key] = value
            self.assertEqual("invalid", verify_pair(*records, *raws)["result"])
        records, raws = fixture()
        records[0]["device"]["android_user"] = True
        self.assertEqual("invalid", verify_pair(*records, *raws)["result"])

    def run_cli(self, records, raws, manifest_override=None, omit_log=None):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            paths = [base / name for name in ("one.json", "two.json", "one.log", "two.log")]
            data = [json.dumps(record).encode() for record in records] + raws
            if manifest_override is not None:
                data[0] = manifest_override
            for index, (path, content) in enumerate(zip(paths, data)):
                if index != omit_log:
                    path.write_bytes(content)
            before = {path: path.read_bytes() for path in paths if path.exists()}
            argv = ["verify_b07_pair.py", str(paths[0]), str(paths[1]), "--log-one", str(paths[2]), "--log-two", str(paths[3])]
            with patch("sys.argv", argv), redirect_stdout(io.StringIO()) as captured:
                code = main()
            self.assertEqual(before, {path: path.read_bytes() for path in before})
            return code, json.loads(captured.getvalue()), data

    def test_cli_actual_files_success_zero_failure_and_immutable_inputs(self):
        records, raws = fixture()
        code, report, data = self.run_cli(records, raws)
        self.assertEqual(0, code)
        self.assertEqual("install_pair_consistent", report["result"])
        self.assertEqual(hashlib.sha256(data[0]).hexdigest(), report["manifest_sha256"]["one"])
        replace_events(records, raws, 0, lambda selected: selected[1].update(cpu_end_ns=10))
        self.assertEqual(2, self.run_cli(records, raws)[0])
        replace_events(records, raws, 1, lambda selected: selected[5].update(original_count=1))
        code, report, _ = self.run_cli(records, raws)
        self.assertEqual(1, code)
        self.assertIn("install_observations", report["sides"]["B"])

    def test_cli_bad_json_or_missing_file_retains_available_hashes_and_peer(self):
        records, raws = fixture()
        for content in (b'{"schema_version":1,"schema_version":1}', b'{"bad":NaN}', b'null', b'{',
                        b'{"deep":' + b'[' * 2000 + b'0' + b']' * 2000 + b'}'):
            code, report, _ = self.run_cli(records, raws, manifest_override=content)
            self.assertEqual(1, code)
            self.assertEqual(hashlib.sha256(content).hexdigest(), report["manifest_sha256"]["one"])
            self.assertEqual(hashlib.sha256(raws[0]).hexdigest(), report["inputs"]["one"]["raw_log_sha256"])
            self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])
        code, report, _ = self.run_cli(records, raws, omit_log=2)
        self.assertEqual(2, code)
        self.assertEqual("incomplete", report["result"])
        self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])


if __name__ == "__main__":
    unittest.main()
