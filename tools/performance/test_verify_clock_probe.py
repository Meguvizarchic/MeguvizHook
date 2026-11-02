import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from verify_clock_probe import InvalidRecord, verify


RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
OTHER_RUN = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
PID = 1234
TID = 5678


def clock_events():
    base = {"schema": 1, "run_id": RUN, "pid": PID, "tid": TID}
    result = [dict(base, event="start", status="start", warmup=1, samples=3,
                   clock="thread_cpu_ns", wall_clock="elapsed_realtime_ns")]
    for phase, index, cb, ca, wb, wa in (
            ("warmup", 0, 100, 100, 1000, 1010),
            ("sample", 0, 101, 104, 1011, 1020),
            ("sample", 1, 105, 105, 1021, 1021),
            ("sample", 2, 110, 120, 1030, 1050)):
        result.append(dict(base, event=phase, status="pass", index=index, cpu_before_ns=cb,
                           cpu_after_ns=ca, wall_before_ns=wb, wall_after_ns=wa, reason=""))
    result.append(dict(base, event="end", status="pass", reason=""))
    return result


def raw_log(items, prefix_pid=PID, prefix_tid=TID):
    return b"".join(("11-01 10:20:30.123 %5d %5d I MeguvizHookClock: " % (prefix_pid, prefix_tid)
                     + json.dumps(item, ensure_ascii=True) + "\n").encode("utf-8") for item in items)


def failure_log(event_index, changes, reason, end=True):
    items = clock_events()[:event_index + 1]
    items[-1].update(changes, status="fail", reason=reason)
    if end:
        items.append({"schema": 1, "run_id": RUN, "pid": PID, "tid": TID,
                      "event": "end", "status": "fail", "reason": reason})
    return raw_log(items)


class ClockProbeTest(unittest.TestCase):
    def test_observed_intervals_preserve_values_and_raw_hash(self):
        raw = b"other process: \xff\n" + raw_log(clock_events())
        result = verify(raw, RUN, PID, TID)
        self.assertEqual("observed", result["result"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["raw_log_sha256"])
        self.assertEqual((2, 1, 3), (result["cpu"]["positive_interval_count"],
                                     result["cpu"]["zero_interval_count"],
                                     result["cpu"]["smallest_positive_interval_ns"]))
        self.assertEqual((2, 1, 9), (result["wall"]["positive_interval_count"],
                                     result["wall"]["zero_interval_count"],
                                     result["wall"]["smallest_positive_interval_ns"]))
        self.assertEqual((101, 104, 1011, 1020),
                         tuple(result["readings"][1][key] for key in
                               ("cpu_before_ns", "cpu_after_ns", "wall_before_ns", "wall_after_ns")))
        self.assertEqual("not_evaluated", result["performance_gate"])

    def test_zero_sample_intervals_are_unresolved_per_clock(self):
        for clock in ("cpu", "wall"):
            items = clock_events()
            for index, item in enumerate(items[2:-1]):
                value = (200 if clock == "cpu" else 2000) + index
                item[clock + "_before_ns"] = value
                item[clock + "_after_ns"] = value
            result = verify(raw_log(items), RUN, PID, TID)
            self.assertEqual("unresolved", result["result"])
            self.assertEqual("unresolved", result[clock]["increment_observation"])
            self.assertIsNone(result[clock]["smallest_positive_interval_ns"])
            self.assertEqual("observed", result["wall" if clock == "cpu" else "cpu"]["increment_observation"])

    def test_zero_warmup_with_one_sample(self):
        items = clock_events()
        items[0]["warmup"] = 0
        items[0]["samples"] = 1
        result = verify(raw_log([items[0], items[2], items[-1]]), RUN, PID, TID)
        self.assertEqual("observed", result["result"])
        self.assertEqual([("sample", 0)], [(x["phase"], x["index"]) for x in result["readings"]])

    def test_unavailable_and_backward_values_are_failures_not_incomplete(self):
        cases = (
            (2, {"cpu_before_ns": -1}, "cpu_unavailable"),
            (2, {"cpu_after_ns": -2, "wall_before_ns": -1}, "cpu_unavailable"),
            (2, {"wall_after_ns": -1}, "wall_unavailable"),
            (2, {"cpu_after_ns": 100}, "cpu_backward"),
            (3, {"cpu_before_ns": 103}, "cpu_backward"),
            (2, {"wall_after_ns": 1010}, "wall_backward"),
            (3, {"wall_before_ns": 1019}, "wall_backward"),
        )
        for index, changes, reason in cases:
            for include_end in (False, True):
                with self.subTest(reason=reason, include_end=include_end):
                    result = verify(failure_log(index, changes, reason, include_end), RUN, PID, TID)
                    self.assertEqual("clock_failure", result["result"])
                    self.assertEqual(reason, result["reason"])
                    self.assertEqual(include_end, result["terminal_event_present"])
                    for key, value in changes.items():
                        self.assertEqual(value, result["readings"][-1][key])

    def test_status_or_end_cannot_hide_invalid_reading(self):
        items = clock_events()
        items[2]["cpu_before_ns"] = -1
        with self.assertRaises(InvalidRecord):
            verify(raw_log(items), RUN, PID, TID)
        raw = failure_log(2, {"cpu_before_ns": -1}, "cpu_unavailable")
        raw = raw.replace(b'"reason": "cpu_unavailable"}', b'"reason": "wall_unavailable"}', 1)
        with self.assertRaises(InvalidRecord):
            verify(raw, RUN, PID, TID)

    def test_truncation_is_incomplete_but_false_success_is_invalid(self):
        items = clock_events()
        self.assertEqual("incomplete", verify(raw_log(items[:-2]), RUN, PID, TID)["result"])
        with self.assertRaises(InvalidRecord):
            verify(raw_log(items[:-2] + items[-1:]), RUN, PID, TID)
        self.assertEqual("incomplete", verify(b"unrelated\n", RUN, PID, TID)["result"])

    def test_repeated_reordered_or_extra_events_fail(self):
        for mutate in (lambda xs: xs.insert(2, copy.deepcopy(xs[1])),
                       lambda xs: xs.__setitem__(slice(2, 4), [xs[3], xs[2]]),
                       lambda xs: xs.append(copy.deepcopy(xs[-1]))):
            items = clock_events()
            mutate(items)
            with self.assertRaises(InvalidRecord):
                verify(raw_log(items), RUN, PID, TID)

    def test_prefix_payload_identity_and_types_are_strict(self):
        for prefix_pid, prefix_tid in ((9999, TID), (PID, 9999)):
            with self.subTest(prefix_pid=prefix_pid, prefix_tid=prefix_tid):
                with self.assertRaises(InvalidRecord):
                    verify(raw_log(clock_events(), prefix_pid, prefix_tid), RUN, PID, TID)
        for key, value in (("pid", True), ("tid", 9999), ("schema", True), ("index", True),
                           ("cpu_after_ns", 1.0), ("wall_before_ns", 2**63)):
            items = clock_events()
            items[2][key] = value
            with self.subTest(key=key):
                with self.assertRaises(InvalidRecord):
                    verify(raw_log(items), RUN, PID, TID)

    def test_unrelated_run_does_not_complete_or_pollute_selected_run(self):
        items = clock_events()
        other = copy.deepcopy(items[2])
        other.update(run_id=OTHER_RUN, pid=9876, tid=8765)
        raw = raw_log(items[:2]) + raw_log([other], 9876, 8765) + raw_log(items[2:])
        self.assertEqual("observed", verify(raw, RUN, PID, TID)["result"])
        self.assertEqual("incomplete", verify(raw_log(items[:-1]) + raw_log([other], 9876, 8765),
                                              RUN, PID, TID)["result"])

    def test_another_run_cannot_share_or_claim_selected_pid(self):
        for prefix_pid, json_pid, json_tid in ((PID, PID, TID), (PID, 9876, 8765), (9876, PID, TID)):
            other = copy.deepcopy(clock_events()[2])
            other.update(run_id=OTHER_RUN, pid=json_pid, tid=json_tid)
            raw = raw_log(clock_events()) + raw_log([other], prefix_pid, json_tid)
            with self.subTest(prefix_pid=prefix_pid, json_pid=json_pid):
                with self.assertRaises(InvalidRecord):
                    verify(raw, RUN, PID, TID)

    def test_tagged_malformed_json_duplicates_and_bad_encoding_fail(self):
        valid = raw_log(clock_events())
        failures = (
            valid + b'11-01 10:20:30.123 1234 5678 I MeguvizHookClock: {broken}\n',
            valid.replace(b'"schema": 1', b'"schema": 1, "schema": 1', 1),
            valid.replace(b'"thread_cpu_ns"', b'"thread_\xffcpu_ns"', 1),
            valid.replace(b'MeguvizHookClock:', b'MeguvizHook\xffClock:', 1),
            valid.replace(b'MeguvizHookClock:', b'MeguvizHookCloc\xff:', 1),
            valid + b'11-01 10:20:30.123 1234 5678 I MeguvizHookClock \xff\n',
        )
        for raw in failures:
            with self.subTest(raw=raw[-100:]):
                with self.assertRaises(InvalidRecord):
                    verify(raw, RUN, PID, TID)

    def test_cli_exit_codes_and_original_file_are_preserved(self):
        root = Path(__file__).resolve().parents[2] / "build" / "clock-probe-log-tests"
        root.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix="case-", dir=str(root)))
        script = Path(__file__).with_name("verify_clock_probe.py")
        unresolved = clock_events()
        for index, item in enumerate(unresolved[2:-1]):
            item["cpu_before_ns"] = item["cpu_after_ns"] = 200 + index
        for name, content, exit_code in (("ok", raw_log(clock_events()), 0),
                                        ("failure", failure_log(2, {"cpu_before_ns": -1}, "cpu_unavailable"), 1),
                                        ("unresolved", raw_log(unresolved), 2),
                                        ("incomplete", raw_log(clock_events()[:-1]), 2)):
            path = directory / (name + ".log")
            path.write_bytes(content)
            proc = subprocess.run([sys.executable, str(script), str(path), "--run-id", RUN,
                                   "--pid", str(PID), "--tid", str(TID)],
                                  capture_output=True, text=True, encoding="utf-8", check=False)
            with self.subTest(name=name):
                self.assertEqual(exit_code, proc.returncode, proc.stderr)
                self.assertEqual(content, path.read_bytes())
                self.assertEqual("not_evaluated", json.loads(proc.stdout)["performance_gate"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), json.loads(proc.stdout)["raw_log_sha256"])


if __name__ == "__main__":
    unittest.main()
