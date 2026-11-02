import copy
import contextlib
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from build_aa_pilot_session import build_session, generate_session, main
from test_verify_aa_pilot import observed_session, raw_bytes, raw_events, session
from verify_aa_pilot import IncompleteRecord, InvalidRecord, verify_raw_log


def manifest(record):
    return {"schema_version": 1, "data_origin": "device_measurement", "backend": record["backend"],
            "process_start_token": record["process"]["start_token"],
            "pair_order": record["config"]["pair_order"],
            "artifacts": copy.deepcopy(record["artifacts"]), "device": copy.deepcopy(record["device"])}


def fresh_output():
    # Retain test outputs in the ignored build directory; do not delete user or workspace files.
    base = Path(__file__).resolve().parents[2] / "build" / "pilot-session-tests"
    base.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="case-", dir=str(base))) / "session.json"


class PilotSessionBuilderTest(unittest.TestCase):
    def test_builder_preserves_raw_hash_with_unrelated_invalid_utf8(self):
        source = observed_session("A", "B02", "A2")
        raw = (b"11-01 10:20:30.000 9999 9999 I Noise: unrelated \xff\n"
               + raw_bytes(source, raw_events(source)))
        record = build_session(raw, source["run_id"], manifest(source))
        self.assertEqual(hashlib.sha256(raw).hexdigest(), record["capture"]["raw_log_sha256"])
        self.assertEqual(source["samples"], record["samples"])
        verify_raw_log(record, raw)

    def test_builder_rejects_invalid_target_log_lines(self):
        source = observed_session("A")
        raw = raw_bytes(source, raw_events(source))
        for suffix in (b"11-01 10:20:30.000 100 101 I MeguvizHookBench: \xff\n",
                       b"11-01 10:20:30.000 100 101 I MeguvizHookBenc\xff: {}\n",
                       b"11-01 10:20:30.000 100 101 I MeguvizHookBench malformed\n"):
            with self.subTest(suffix=suffix):
                with self.assertRaises(InvalidRecord):
                    build_session(raw + suffix, source["run_id"], manifest(source))

    def test_observed_log_preserves_warmup_and_sample_counts(self):
        for workload in ("B01", "B02", "B03", "B04", "B05", "B06"):
            with self.subTest(workload=workload):
                source = observed_session("A", workload, "A2")
                raw = raw_bytes(source, raw_events(source))
                record = build_session(raw, source["run_id"], manifest(source))
                self.assertEqual(source["warmup_observation"], record["warmup_observation"])
                self.assertEqual(source["samples"], record["samples"])
                self.assertEqual("not_verified", record["preflight"]["status"])
                self.assertTrue(all(value is None for value in record["formal_metrics"].values()))
                verify_raw_log(record, raw)

    def test_observed_log_missing_or_wrong_counts_never_writes(self):
        source = observed_session("A")
        for change in ("missing_warmup", "warmup_count", "sample_count"):
            with self.subTest(change=change):
                events = raw_events(source)
                if change == "missing_warmup":
                    events.pop(4)
                elif change == "warmup_count":
                    events[4]["original_calls"] -= 1
                else:
                    events[5]["hook_before_calls"] -= 1
                raw = raw_bytes(source, events)
                output = fresh_output()
                with self.assertRaises((IncompleteRecord, InvalidRecord)):
                    generate_session(raw, source["run_id"], manifest(source), output)
                self.assertFalse(output.exists())

    def test_b03_observed_bad_counts_never_write_a_session(self):
        source = observed_session("A", "B03", "A2")
        for event_index in (4, 5):
            for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
                with self.subTest(event_index=event_index, field=key):
                    events = raw_events(source)
                    events[event_index][key] += 1
                    raw = raw_bytes(source, events)
                    output = fresh_output()
                    with self.assertRaises(InvalidRecord):
                        generate_session(raw, source["run_id"], manifest(source), output)
                    self.assertFalse(output.exists())

    def test_b04_builder_checks_distinct_hook_and_post_close_results(self):
        for observed in (False, True):
            for control in ("A0", "A2"):
                with self.subTest(observed=observed, control=control):
                    source = (observed_session if observed else session)("A", "B04", control)
                    raw = raw_bytes(source, raw_events(source))
                    record = build_session(raw, source["run_id"], manifest(source))
                    self.assertEqual(source["samples"], record["samples"])
                    self.assertEqual("not_verified", record["preflight"]["status"])
                    verify_raw_log(record, raw)
                    for index, value in ((1, 7), (2, 9)):
                        events = raw_events(source)
                        events[index]["result"] = value
                        output = fresh_output()
                        with self.assertRaises(InvalidRecord):
                            generate_session(raw_bytes(source, events), source["run_id"], manifest(source), output)
                        self.assertFalse(output.exists())
                    events = raw_events(source)
                    sample_index = 5 if observed else 4
                    events[sample_index]["checksum"] = "%016x" % (7 * source["config"]["iterations"])
                    output = fresh_output()
                    with self.assertRaises(InvalidRecord):
                        generate_session(raw_bytes(source, events), source["run_id"], manifest(source), output)
                    self.assertFalse(output.exists())

    def test_b05_builder_rejects_missing_or_wrong_around_counts(self):
        for control in ("A0", "A2"):
            source = observed_session("A", "B05", control)
            for phase in (4, 5):
                for key in ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls"):
                    for change in ("missing", "wrong"):
                        with self.subTest(control=control, phase=phase, field=key, change=change):
                            events = raw_events(source)
                            if change == "missing":
                                del events[phase][key]
                            else:
                                events[phase][key] += 1
                            raw = raw_bytes(source, events)
                            output = fresh_output()
                            with self.assertRaises((IncompleteRecord, InvalidRecord)):
                                generate_session(raw, source["run_id"], manifest(source), output)
                            self.assertFalse(output.exists())

    def test_b06_builder_requires_four_layer_counts_and_preflight(self):
        for observed in (False, True):
            for control in ("A0", "A2"):
                source = (observed_session if observed else session)("A", "B06", control)
                record = build_session(raw_bytes(source, raw_events(source)), source["run_id"], manifest(source))
                self.assertEqual(source["samples"], record["samples"])
                self.assertEqual("not_verified", record["preflight"]["status"])
                for index in (1, 2):
                    events = raw_events(source)
                    events[index]["hook_count"] = 1
                    output = fresh_output()
                    with self.assertRaises(InvalidRecord):
                        generate_session(raw_bytes(source, events), source["run_id"], manifest(source), output)
                    self.assertFalse(output.exists())
                if observed:
                    for index in (4, 5):
                        events = raw_events(source)
                        del events[index]["hook_after_calls"]
                        output = fresh_output()
                        with self.assertRaises((IncompleteRecord, InvalidRecord)):
                            generate_session(raw_bytes(source, events), source["run_id"], manifest(source), output)
                        self.assertFalse(output.exists())

    def test_valid_log_builds_record_without_claiming_preflight_or_metrics(self):
        source = session("A", "B02", "A2")
        original = raw_bytes(source, raw_events(source))
        record = build_session(original, source["run_id"], manifest(source))
        self.assertEqual(source["pair_id"], record["pair_id"])
        self.assertEqual(source["samples"], record["samples"])
        self.assertEqual(source["capture"]["raw_log_sha256"], record["capture"]["raw_log_sha256"])
        self.assertEqual("not_verified", record["preflight"]["status"])
        self.assertTrue(all(value is None for value in record["formal_metrics"].values()))
        verify_raw_log(record, original)

    def test_missing_identity_or_mismatched_framework_never_writes(self):
        source = session("A")
        raw = raw_bytes(source, raw_events(source))
        for change in ("missing", "framework"):
            with self.subTest(change=change):
                facts = manifest(source)
                if change == "missing":
                    del facts["device"]["abi"]
                else:
                    facts["device"]["framework_name"] = "different-framework"
                output = fresh_output()
                with self.assertRaises((IncompleteRecord, InvalidRecord)):
                    generate_session(raw, source["run_id"], facts, output)
                self.assertFalse(output.exists())

    def test_bad_sample_or_truncated_log_never_writes(self):
        source = session("A")
        for change in ("checksum", "truncated", "failed"):
            with self.subTest(change=change):
                events = raw_events(source)
                if change == "checksum":
                    events[4]["checksum"] = "0000000000000000"
                elif change == "truncated":
                    events.pop()
                else:
                    events[1]["status"] = "fail"
                raw = raw_bytes(source, events)
                output = fresh_output()
                with self.assertRaises((IncompleteRecord, InvalidRecord)):
                    generate_session(raw, source["run_id"], manifest(source), output)
                self.assertFalse(output.exists())

    def test_output_exists_is_never_overwritten(self):
        source = session("A")
        raw = raw_bytes(source, raw_events(source))
        output = fresh_output()
        output.write_text("existing result", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            generate_session(raw, source["run_id"], manifest(source), output)
        self.assertEqual("existing result", output.read_text(encoding="utf-8"))

    def test_saved_record_is_exactly_validated_session(self):
        source = session("A")
        raw = raw_bytes(source, raw_events(source))
        output = fresh_output()
        record = generate_session(raw, source["run_id"], manifest(source), output)
        self.assertEqual(record, json.loads(output.read_text(encoding="utf-8")))
        verify_raw_log(record, raw)

    def test_cli_reads_original_log_without_modification(self):
        source = session("A")
        raw = raw_bytes(source, raw_events(source))
        output = fresh_output()
        log_path = output.parent / "raw.log"
        manifest_path = output.parent / "manifest.json"
        log_path.write_bytes(raw)
        manifest_path.write_text(json.dumps(manifest(source)), encoding="utf-8")
        argv = ["build_aa_pilot_session.py", str(log_path), "--run-id", source["run_id"],
                "--manifest", str(manifest_path), "--output", str(output)]
        with patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, main())
        self.assertEqual(raw, log_path.read_bytes())
        self.assertEqual("not_verified", json.loads(stdout.getvalue())["preflight"])
        self.assertEqual("not_evaluated", json.loads(stdout.getvalue())["performance_gate"])

    def test_write_failure_reports_invalid_without_changing_existing_inputs(self):
        source = session("A")
        raw = raw_bytes(source, raw_events(source))
        output = fresh_output()
        log_path = output.parent / "raw.log"
        manifest_path = output.parent / "manifest.json"
        existing_path = output.parent / "existing.json"
        manifest_text = json.dumps(manifest(source))
        log_path.write_bytes(raw)
        manifest_path.write_text(manifest_text, encoding="utf-8")
        existing_path.write_text("existing evidence", encoding="utf-8")
        argv = ["build_aa_pilot_session.py", str(log_path), "--run-id", source["run_id"],
                "--manifest", str(manifest_path), "--output", str(output)]

        def interrupted_dump(record, destination, **kwargs):
            destination.write('{"partial":')
            raise OSError("simulated output write failure")

        with patch.object(sys, "argv", argv), \
                patch("build_aa_pilot_session.json.dump", side_effect=interrupted_dump), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(1, main())
        self.assertEqual("invalid", json.loads(stdout.getvalue())["result"])
        self.assertEqual(raw, log_path.read_bytes())
        self.assertEqual(manifest_text, manifest_path.read_text(encoding="utf-8"))
        self.assertEqual("existing evidence", existing_path.read_text(encoding="utf-8"))
        self.assertEqual('{"partial":', output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
