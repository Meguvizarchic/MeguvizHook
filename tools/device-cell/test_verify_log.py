import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from verify_log import CHECKS, verify

RUN = "6abefc46-11fe-4f53-8aa6-fba235cb679c"
PID = 1234
DETAILS = {
    "101": {
        "baseline": "value=5,calls=1,expected=5,expected_calls=1,old_callbacks=0,new_callbacks=0,expected_old_callbacks=0,expected_new_callbacks=0",
        "install": "status=INSTALLED",
        "installed_call": "value=10,calls=0,expected=10,expected_calls=0,old_callbacks=1,new_callbacks=0,expected_old_callbacks=1,expected_new_callbacks=0",
        "replace_unsupported": "status=UNSUPPORTED_CAPABILITY",
        "after_rejected_replace": "value=10,calls=0,expected=10,expected_calls=0,old_callbacks=2,new_callbacks=0,expected_old_callbacks=2,expected_new_callbacks=0",
        "close": "entry=RESTORED",
        "restored_call": "value=5,calls=1,expected=5,expected_calls=1,old_callbacks=2,new_callbacks=0,expected_old_callbacks=2,expected_new_callbacks=0",
    },
    "102": {
        "baseline": "value=5,calls=1,expected=5,expected_calls=1,old_callbacks=0,new_callbacks=0,expected_old_callbacks=0,expected_new_callbacks=0",
        "install": "status=INSTALLED",
        "installed_call": "value=10,calls=0,expected=10,expected_calls=0,old_callbacks=1,new_callbacks=0,expected_old_callbacks=1,expected_new_callbacks=0",
        "replace": "status=INSTALLED",
        "replaced_call": "value=20,calls=0,expected=20,expected_calls=0,old_callbacks=1,new_callbacks=1,expected_old_callbacks=1,expected_new_callbacks=1",
        "old_close": "entry=NOT_RESTORED",
        "after_old_close": "value=20,calls=0,expected=20,expected_calls=0,old_callbacks=1,new_callbacks=2,expected_old_callbacks=1,expected_new_callbacks=2",
        "close": "entry=RESTORED",
        "restored_call": "value=5,calls=1,expected=5,expected_calls=1,old_callbacks=1,new_callbacks=2,expected_old_callbacks=1,expected_new_callbacks=2",
    },
}


def events(backend):
    common = {"schema": 1, "run_id": RUN, "pid": PID, "backend": backend, "detail": ""}
    start = dict(common, event="start", status="start", name="run",
                 framework_name="test framework", framework_version="test version", reported_api=102)
    checks = [dict(common, event="check", status="pass", name=name, detail=DETAILS[backend][name])
              for name in CHECKS[backend]]
    return [start, *checks, dict(common, event="end", status="pass", name="run")]


def replacement_events_101():
    items = events("102")
    for item in items:
        item["backend"] = "101"
    items[0]["probe_version"] = 2
    items[0]["reported_api"] = 101
    return items


def lines(items, prefix_pid=PID):
    return ["11-01 10:20:30.123 %5d  5678 I MeguvizHookCell: " % prefix_pid + json.dumps(item) + "\n"
            for item in items]


def verify_cli(raw):
    # Keep generated fixtures in ignored build/, preserving existing files.
    root = Path(__file__).resolve().parents[2] / "build" / "device-cell-log-tests"
    root.mkdir(parents=True, exist_ok=True)
    log = Path(tempfile.mkdtemp(prefix="case-", dir=str(root))) / "logcat.bin"
    log.write_bytes(raw)
    return subprocess.run([sys.executable, str(Path(__file__).with_name("verify_log.py")),
                           str(log), "--backend", "101", "--run-id", RUN, "--pid", str(PID)],
                          capture_output=True, text=True, encoding="utf-8", check=False)


class DeviceCellLogTest(unittest.TestCase):
    def test_cli_ignores_invalid_utf8_in_unrelated_lines(self):
        raw = (b"11-01 10:20:30.000 9999  5678 I OtherTag: unrelated \xff byte\n"
               b"11-01 10:20:30.001 9999  5678 I Weath\xffer: unrelated tag\n"
               + "".join(lines(events("101"))).encode("utf-8"))
        result = verify_cli(raw)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("pass", json.loads(result.stdout)["status"])

    def test_cli_rejects_invalid_utf8_on_tagged_lines(self):
        raw = "".join(lines(events("101"))).encode("utf-8")
        for corrupted in (raw.replace(b"test framework", b"test \xffframework", 1),
                          raw.replace(b"MeguvizHookCell:", b"MeguvizHook\xffCell:", 1),
                          raw + b"11-01 10:20:30.000 1234  5678 I MeguvizHook\xffell: {}\n",
                          raw + b"11-01 10:20:30.000 1234  5678 I MeguvizHookCell: \xff\n",
                          raw + b"11-01 10:20:30.000 1234  5678 I MeguvizHookCell \xff\n"):
            with self.subTest(corrupted=corrupted[:90]):
                result = verify_cli(corrupted)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn("invalid UTF-8 on tagged line", json.loads(result.stdout)["reason"])

    def test_complete_sequences_pass_for_both_backends(self):
        for backend in CHECKS:
            with self.subTest(backend=backend):
                self.assertEqual("pass", verify(lines(events(backend)), backend, RUN, PID)[0])

    def test_101_replacement_and_legacy_protocols_are_distinguished(self):
        status, _, metadata = verify(lines(replacement_events_101()), "101", RUN, PID)
        self.assertEqual("pass", status)
        self.assertEqual(2, metadata["probe_version"])
        status, _, metadata = verify(lines(events("101")), "101", RUN, PID)
        self.assertEqual("pass", status)
        self.assertEqual(1, metadata["probe_version"])

        # A historical rejection record cannot pass as a replacement run.
        old_as_new = events("101")
        old_as_new[0]["probe_version"] = 2
        self.assertEqual("fail", verify(lines(old_as_new), "101", RUN, PID)[0])
        new_as_old = replacement_events_101()
        del new_as_old[0]["probe_version"]
        self.assertEqual("fail", verify(lines(new_as_old), "101", RUN, PID)[0])

    def test_unknown_or_wrongly_typed_probe_version_fails(self):
        for value in (True, None, "2", 0, 3):
            with self.subTest(version=value):
                items = replacement_events_101()
                items[0]["probe_version"] = value
                self.assertEqual("fail", verify(lines(items), "101", RUN, PID)[0])
        items = events("102")
        items[0]["probe_version"] = 2
        self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])

    def test_missing_terminal_event_is_incomplete(self):
        self.assertEqual("incomplete", verify(lines(events("102")[:-1]), "102", RUN, PID)[0])

    def test_failure_is_not_mistaken_for_an_incomplete_run(self):
        items = events("101")[:3]
        items[-1]["status"] = "fail"
        self.assertEqual("fail", verify(lines(items), "101", RUN, PID)[0])

    def test_duplicate_or_out_of_order_checks_fail(self):
        items = events("102")
        items.insert(2, items[1])
        self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])

    def test_wrong_pid_and_malformed_tagged_line_fail(self):
        self.assertEqual("fail", verify(lines(events("101")), "101", RUN, PID + 1)[0])
        self.assertEqual("fail", verify(["11-01 10:20:30.123 1234 5678 I MeguvizHookCell: {broken}\n"],
                                        "101", RUN, PID)[0])
        self.assertEqual("fail", verify(lines(events("101"))
                                        + ["11-01 10:20:30.123 1234 5678 I MeguvizHookCell malformed\n"],
                                        "101", RUN, PID)[0])

    def test_logcat_prefix_pid_must_match_selected_pid(self):
        self.assertEqual("fail", verify(lines(events("102"), prefix_pid=9999), "102", RUN, PID)[0])

    def test_threadtime_is_required(self):
        raw = ["I MeguvizHookCell: " + json.dumps(item) + "\n" for item in events("101")]
        self.assertEqual("fail", verify(raw, "101", RUN, PID)[0])

    def test_contradictory_or_missing_observations_do_not_pass(self):
        for detail in ("value=5,calls=1,expected=10,expected_calls=0", ""):
            with self.subTest(detail=detail):
                items = events("102")
                items[3]["detail"] = detail
                self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])
        items = events("102")
        items[3]["detail"] = items[3]["detail"].replace("old_callbacks=1", "old_callbacks=0", 1)
        self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])

    def test_contradictory_status_detail_does_not_pass(self):
        items = events("101")
        items[4]["detail"] = "status=INSTALLED"
        self.assertEqual("fail", verify(lines(items), "101", RUN, PID)[0])

    def test_null_or_blank_framework_metadata_does_not_pass(self):
        for name, value in (("framework_name", "null"), ("framework_version", "  ")):
            with self.subTest(name=name, value=value):
                items = events("102")
                items[0][name] = value
                self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])

    def test_other_run_cannot_complete_selected_run(self):
        items = events("102")
        items[-1]["run_id"] = "4aa4d542-7ca6-4f00-9bed-b50e4199c5de"
        self.assertEqual("incomplete", verify(lines(items), "102", RUN, PID)[0])

    def test_selected_pid_cannot_hide_missing_or_invalid_run_id(self):
        for malformed in (None, "", "not-a-uuid", 7, "6ABEFC46-11FE-4F53-8AA6-FBA235CB679C"):
            with self.subTest(run_id=malformed):
                items = events("102")
                hidden = {"event": "check", "status": "fail"}
                if malformed is not None:
                    hidden["run_id"] = malformed
                items.insert(2, hidden)
                self.assertEqual("fail", verify(lines(items), "102", RUN, PID)[0])

    def test_unrelated_canonical_run_can_be_skipped(self):
        items = events("102")
        items.insert(2, {"run_id": "4aa4d542-7ca6-4f00-9bed-b50e4199c5de",
                         "event": "check", "status": "fail"})
        self.assertEqual("pass", verify(lines(items), "102", RUN, PID)[0])


if __name__ == "__main__":
    unittest.main()
