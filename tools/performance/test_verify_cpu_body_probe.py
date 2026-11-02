import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from verify_cpu_body_probe import InvalidRecord, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
OTHER_RUN = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
PID, TID = 1234, 5678


def events(workload="B01", warmup=1, samples=2):
    common = {"schema": 1, "run_id": RUN, "pid": PID, "tid": TID}
    rows = [dict(common, event="start", status="start", backend="102", control="A0",
                 workload=workload, warmup=warmup, samples=samples, clock="thread_cpu_ns",
                 measurement_kind="instrumented_callback_body_cpu_interval")]
    for ordinal in range(warmup + samples):
        base = 1000 + ordinal * 100
        rows.append(dict(common, event="call", status="pass", ordinal=ordinal,
                         before_count=1, original_count=1, after_count=1 if workload == "B01" else 0,
                         before_cpu_start_ns=base, before_cpu_end_ns=base + 10,
                         after_cpu_start_ns=base + 20 if workload == "B01" else None,
                         after_cpu_end_ns=base + 30 if workload == "B01" else None, reason=""))
    total = warmup + samples
    rows.append(dict(common, event="end", status="pass", before_calls=total,
                     original_calls=total, after_calls=total if workload == "B01" else 0,
                     reason="", cleanup_reported=True))
    return rows


def raw_log(items, prefix_pid=PID, prefix_tid=TID):
    return b"".join(("11-01 10:20:30.123 %5d %5d I MeguvizHookCpuBody: " % (prefix_pid, prefix_tid)
                     + json.dumps(item, ensure_ascii=True) + "\n").encode("utf-8") for item in items)


def run_verify(items, workload="B01", raw_prefix=b""):
    raw = raw_prefix + raw_log(items)
    return verify(raw, RUN, PID, TID, "102", "A0", workload)


class CpuBodyProbeTest(unittest.TestCase):
    def test_success_preserves_all_raw_readings_and_hash(self):
        for workload in ("B01", "B02"):
            with self.subTest(workload=workload):
                items = events(workload)
                raw = b"other tag: \xff\n" + raw_log(items)
                result = verify(raw, RUN, PID, TID, "102", "A0", workload)
                self.assertEqual("observed", result["result"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
                self.assertEqual([0, 1, 2], [x["ordinal"] for x in result["readings"]])
                self.assertEqual(2, result["before"]["sample_count"])
                self.assertEqual(10, result["before"]["smallest_positive_interval_ns"])
                self.assertEqual("not_evaluated", result["performance_gate"])
                if workload == "B01":
                    self.assertEqual(10, result["after"]["smallest_positive_interval_ns"])
                else:
                    self.assertNotIn("after", result)
                    self.assertIsNone(result["readings"][1]["after_cpu_start_ns"])

    def test_zero_deltas_are_unresolved_per_required_stage(self):
        for stage in ("before", "after"):
            with self.subTest(stage=stage):
                items = events()
                for row in items[2:-1]:  # sample ordinals, not warmup
                    row[stage + "_cpu_end_ns"] = row[stage + "_cpu_start_ns"]
                result = run_verify(items)
                self.assertEqual("unresolved", result["result"])
                self.assertEqual(2, result[stage]["zero_interval_count"])
                self.assertIsNone(result[stage]["smallest_positive_interval_ns"])

    def test_zero_warmup_and_exactly_one_sample(self):
        result = run_verify(events("B02", warmup=0, samples=1), "B02")
        self.assertEqual("observed", result["result"])
        self.assertEqual((0, 1), (result["warmup"], result["samples"]))

    def test_failing_call_is_not_incomplete_even_without_end(self):
        items = events()[:2]
        items[-1].update(status="fail", reason="clock_unavailable", before_cpu_start_ns=-1,
                         before_cpu_end_ns=None, original_count=0, after_count=0,
                         after_cpu_start_ns=None, after_cpu_end_ns=None)
        result = run_verify(items)
        self.assertEqual("diagnostic_failure", result["result"])
        self.assertFalse(result["terminal_event_present"])
        self.assertEqual(-1, result["readings"][-1]["before_cpu_start_ns"])
        items.append(dict(events()[-1], status="fail", reason="cleanup_failed", cleanup_reported=False,
                          before_calls=1, original_calls=0, after_calls=0))
        result = run_verify(items)
        self.assertEqual("diagnostic_failure", result["result"])
        self.assertTrue(result["terminal_event_present"])
        self.assertEqual("cleanup_failed", result["end"]["reason"])

    def test_end_failure_is_never_success_and_truncation_is_incomplete(self):
        items = events()
        failed_end = dict(items[-1], status="fail", reason="setup_failed", cleanup_reported=False,
                          before_calls=0, original_calls=0, after_calls=0)
        self.assertEqual("diagnostic_failure", run_verify([items[0], failed_end])["result"])
        self.assertEqual("incomplete", run_verify(items[:-2])["result"])
        with self.assertRaises(InvalidRecord):
            run_verify(items[:-2] + items[-1:])

    def test_bad_counts_times_and_final_cleanup_cannot_pass(self):
        for key, value in (("before_count", 2), ("original_count", 0), ("after_count", 0),
                           ("before_cpu_start_ns", -1), ("before_cpu_end_ns", None),
                           ("before_cpu_end_ns", 0), ("after_cpu_start_ns", 0)):
            items = events()
            items[2][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord):
                run_verify(items)
        items = events()
        items[-1]["cleanup_reported"] = False
        with self.assertRaises(InvalidRecord):
            run_verify(items)
        items = events("B02")
        items[1]["after_cpu_start_ns"] = 0
        with self.assertRaises(InvalidRecord):
            run_verify(items, "B02")

    def test_repeated_reordered_extra_and_cross_call_backward_fail(self):
        for mutate in (lambda xs: xs.insert(2, copy.deepcopy(xs[1])),
                       lambda xs: xs.__setitem__(slice(1, 3), [xs[2], xs[1]]),
                       lambda xs: xs.append(copy.deepcopy(xs[-1]))):
            items = events()
            mutate(items)
            with self.subTest(items=items), self.assertRaises(InvalidRecord):
                run_verify(items)
        items = events()
        items[2]["before_cpu_start_ns"] = 1029  # previous after ended at 1030
        with self.assertRaises(InvalidRecord):
            run_verify(items)

    def test_identity_schema_types_and_expected_configuration_are_strict(self):
        for prefix_pid, prefix_tid in ((9999, TID), (PID, 9999)):
            with self.subTest(prefix_pid=prefix_pid, prefix_tid=prefix_tid), self.assertRaises(InvalidRecord):
                verify(raw_log(events(), prefix_pid, prefix_tid), RUN, PID, TID, "102", "A0", "B01")
        for index, key, value in ((0, "schema", True), (0, "warmup", True), (1, "ordinal", True),
                                  (1, "original_count", True), (1, "before_cpu_end_ns", 1.0),
                                  (1, "before_cpu_start_ns", 2**63), (-1, "cleanup_reported", 1)):
            items = events()
            items[index][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(InvalidRecord):
                run_verify(items)
        with self.assertRaises(InvalidRecord):
            verify(raw_log(events()), RUN, PID, TID, "101", "A0", "B01")

    def test_other_run_cannot_share_selected_pid(self):
        other = copy.deepcopy(events()[1])
        other["run_id"] = OTHER_RUN
        with self.assertRaises(InvalidRecord):
            verify(raw_log(events()) + raw_log([other]), RUN, PID, TID, "102", "A0", "B01")
        other.update(pid=9876, tid=8765)
        self.assertEqual("observed", verify(raw_log(events()) + raw_log([other], 9876, 8765),
                                           RUN, PID, TID, "102", "A0", "B01")["result"])

    def test_corrupt_tag_duplicate_json_and_bad_encoding_fail(self):
        valid = raw_log(events())
        failures = (
            valid + b'11-01 10:20:30.123 1234 5678 I MeguvizHookCpuBody: {broken}\n',
            valid.replace(b'"schema": 1', b'"schema": 1, "schema": 1', 1),
            valid.replace(b'"thread_cpu_ns"', b'"thread_\xffcpu_ns"', 1),
            valid.replace(b'MeguvizHookCpuBody:', b'MeguvizHook\xffCpuBody:', 1),
            valid.replace(b'MeguvizHookCpuBody:', b'MeguvizHookCpuBod\xff:', 1),
            valid + b'11-01 10:20:30.123 1234 5678 I MeguvizHookCpuBody \xff\n',
        )
        for raw in failures:
            with self.subTest(raw=raw[-100:]), self.assertRaises(InvalidRecord):
                verify(raw, RUN, PID, TID, "102", "A0", "B01")

    def test_cli_exit_codes_and_raw_file_are_preserved(self):
        root = Path(__file__).resolve().parents[2] / "build" / "cpu-body-probe-log-tests"
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix="case-", dir=str(root)))
        script = Path(__file__).with_name("verify_cpu_body_probe.py")
        unresolved = events()
        for row in unresolved[2:-1]:
            row["before_cpu_end_ns"] = row["before_cpu_start_ns"]
        failed = events()[:2]
        failed[-1].update(status="fail", reason="missing_original", original_count=0)
        for name, content, exit_code in (("ok", raw_log(events()), 0),
                                        ("failure", raw_log(failed), 1),
                                        ("unresolved", raw_log(unresolved), 2),
                                        ("incomplete", raw_log(events()[:-1]), 2)):
            path = directory / (name + ".log")
            path.write_bytes(content)
            proc = subprocess.run([sys.executable, str(script), str(path), "--run-id", RUN,
                                   "--pid", str(PID), "--tid", str(TID), "--backend", "102",
                                   "--control", "A0", "--workload", "B01"],
                                  capture_output=True, text=True, encoding="utf-8", check=False)
            with self.subTest(name=name):
                self.assertEqual(exit_code, proc.returncode, proc.stderr)
                self.assertEqual(content, path.read_bytes())
                self.assertEqual("not_evaluated", json.loads(proc.stdout)["performance_gate"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), json.loads(proc.stdout)["raw_log_sha256"])


if __name__ == "__main__":
    unittest.main()
