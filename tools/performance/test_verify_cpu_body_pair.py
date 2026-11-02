import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_verify_cpu_body_probe import events, raw_log
from verify_cpu_body_pair import InvalidRecord, verify_pair


PAIR = "c240a9c4-d22d-4e2b-9df6-c8773cbd99f6"
RUN_A = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
RUN_B = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
HOST_CLOCK = "d51af36c-d724-4be0-98ac-3fb04068a482"
BOOT = "fdc77040-7eb2-4a96-9cc3-5535619c63da"


def raw_for(side, items=None):
    rows = copy.deepcopy(events() if items is None else items)
    pid, tid, run_id = (1234, 5678, RUN_A) if side == "A" else (2345, 6789, RUN_B)
    for row in rows:
        row.update(run_id=run_id, pid=pid, tid=tid)
    return raw_log(rows, pid, tid)


def make_pair():
    raw_a, raw_b = raw_for("A"), raw_for("B")
    common = {"schema_version": 1, "record_type": "cpu_body_probe_side", "pair_id": PAIR,
              "pair_order": "AB", "backend": "102", "control": "A0", "workload": "B01",
              "warmup": 1, "samples": 2, "clock": "thread_cpu_ns",
              "measurement_kind": "instrumented_callback_body_cpu_interval",
              "artifacts": {"host_apk_sha256": "a" * 64, "module_apk_sha256": "b" * 64},
              "device": {"device_id_sha256": "c" * 64, "build_fingerprint_sha256": "d" * 64,
                         "boot_id": BOOT, "framework_name": "framework", "framework_version": "2"},
              "host_clock_token": HOST_CLOCK}
    a = dict(copy.deepcopy(common), side="A", run_id=RUN_A, pid=1234, tid=5678,
             process_start_ticks=100, launch_monotonic_ns=1000, terminal_monotonic_ns=2000,
             raw_log_sha256=hashlib.sha256(raw_a).hexdigest())
    b = dict(copy.deepcopy(common), side="B", run_id=RUN_B, pid=2345, tid=6789,
             process_start_ticks=200, launch_monotonic_ns=3000, terminal_monotonic_ns=4000,
             raw_log_sha256=hashlib.sha256(raw_b).hexdigest())
    return a, b, raw_a, raw_b


class CpuBodyPairTest(unittest.TestCase):
    def test_complete_ab_and_ba_regardless_of_input_order(self):
        a, b, ra, rb = make_pair()
        result = verify_pair(b, a, rb, ra)
        self.assertEqual("body_cpu_pair_consistent", result["result"])
        self.assertEqual("not_evaluated", result["performance_gate"])
        self.assertEqual("not_verified", result["device_authentication"])
        self.assertIn("not independently authenticated", result["provenance"])
        a.update(pair_order="BA", process_start_ticks=200, launch_monotonic_ns=3000, terminal_monotonic_ns=4000)
        b.update(pair_order="BA", process_start_ticks=100, launch_monotonic_ns=1000, terminal_monotonic_ns=2000)
        self.assertEqual("BA", verify_pair(a, b, ra, rb)["pair_order"])

    def test_mismatched_config_artifact_device_or_clock_are_rejected(self):
        for key, value in (("backend", "101"), ("control", "A2"), ("workload", "B02"),
                           ("warmup", 0), ("samples", 1), ("host_clock_token", RUN_A)):
            a, b, ra, rb = make_pair(); b[key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)
        for section, key, value in (("artifacts", "host_apk_sha256", "e" * 64),
                                    ("device", "build_fingerprint_sha256", "e" * 64),
                                    ("device", "boot_id", RUN_A),
                                    ("device", "framework_version", "3")):
            a, b, ra, rb = make_pair(); b[section][key] = value
            with self.subTest(section=section, key=key), self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)
        a, b, ra, rb = make_pair(); b["clock"] = "elapsed_realtime_ns"
        with self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)

    def test_duplicate_identity_and_recorded_order_conflicts_fail(self):
        for mutate in (lambda a, b: b.update(side="A"),
                       lambda a, b: b.update(run_id=RUN_A),
                       lambda a, b: b.update(pid=a["pid"], process_start_ticks=a["process_start_ticks"]),
                       lambda a, b: b.update(process_start_ticks=50),
                       lambda a, b: b.update(launch_monotonic_ns=1500),
                       lambda a, b: b.update(terminal_monotonic_ns=b["launch_monotonic_ns"])):
            a, b, ra, rb = make_pair(); mutate(a, b)
            with self.subTest(a=a, b=b), self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)

    def test_tampered_raw_or_start_counts_never_pass(self):
        a, b, ra, rb = make_pair()
        with self.assertRaises(InvalidRecord): verify_pair(a, b, ra + b"x", rb)
        a, b, ra, rb = make_pair(); rows = events(); rows[0]["samples"] = 1
        rb = raw_for("B", rows); b["raw_log_sha256"] = hashlib.sha256(rb).hexdigest()
        with self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)

    def test_unresolved_incomplete_and_failure_are_distinct(self):
        a, b, ra, rb = make_pair(); rows = events()
        for item in rows[2:-1]: item["before_cpu_end_ns"] = item["before_cpu_start_ns"]
        rb = raw_for("B", rows); b["raw_log_sha256"] = hashlib.sha256(rb).hexdigest()
        self.assertEqual("unresolved", verify_pair(a, b, ra, rb)["result"])
        a, b, ra, rb = make_pair(); rb = raw_for("B", events()[:-1]); b["raw_log_sha256"] = hashlib.sha256(rb).hexdigest()
        self.assertEqual("incomplete", verify_pair(a, b, ra, rb)["result"])
        a, b, ra, rb = make_pair(); rows = events()[:2]
        rows[-1].update(status="fail", reason="missing_original", original_count=0)
        rb = raw_for("B", rows); b["raw_log_sha256"] = hashlib.sha256(rb).hexdigest()
        self.assertEqual("diagnostic_failure", verify_pair(a, b, ra, rb)["result"])

    def test_strict_types_unknown_keys_and_sha_format(self):
        for key, value in (("schema_version", True), ("pid", True), ("tid", 0),
                           ("warmup", False), ("samples", 201), ("process_start_ticks", 0),
                           ("launch_monotonic_ns", True), ("raw_log_sha256", "A" * 64)):
            a, b, ra, rb = make_pair(); a[key] = value
            with self.subTest(key=key), self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)
        a, b, ra, rb = make_pair(); a["unexpected"] = 1
        with self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)
        a, b, ra, rb = make_pair(); a["artifacts"]["unexpected"] = "x"
        with self.assertRaises(InvalidRecord): verify_pair(a, b, ra, rb)

    def test_cli_rejects_duplicate_manifest_keys_without_modifying_raw(self):
        a, b, ra, rb = make_pair()
        root = Path(__file__).resolve().parents[2] / "build" / "cpu-body-pair-tests"
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix="case-", dir=str(root)))
        one, two, log_a, log_b = (directory / name for name in ("one.json", "two.json", "a.log", "b.log"))
        one.write_text(json.dumps(a).replace('"schema_version": 1', '"schema_version": 1, "schema_version": 1', 1), encoding="utf-8")
        two.write_text(json.dumps(b), encoding="utf-8"); log_a.write_bytes(ra); log_b.write_bytes(rb)
        proc = subprocess.run([sys.executable, str(Path(__file__).with_name("verify_cpu_body_pair.py")),
                               str(one), str(two), "--log-one", str(log_a), "--log-two", str(log_b)],
                              capture_output=True, text=True, encoding="utf-8", check=False)
        self.assertEqual(1, proc.returncode, proc.stderr)
        self.assertEqual("invalid", json.loads(proc.stdout)["result"])
        self.assertEqual(ra, log_a.read_bytes()); self.assertEqual(rb, log_b.read_bytes())


if __name__ == "__main__":
    unittest.main()
