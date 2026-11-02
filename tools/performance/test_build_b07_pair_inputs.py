import copy
import hashlib
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import build_b07_pair_inputs as builder
from test_verify_b07_pair import fixture, replace_events
from verify_b07_pair import verify_pair


def capture_from(manifest):
    capture = {key: copy.deepcopy(manifest[key]) for key in builder.CAPTURE_FIELDS}
    capture["record_type"] = "b07_install_capture_side"
    return capture


def inputs(directory, order="AB", backend="102"):
    manifests, raws = fixture(order, backend)
    capture_paths, log_paths = {}, {}
    for index, key in enumerate(("one", "two")):
        capture_paths[key] = directory / (key + ".json")
        capture_paths[key].write_bytes(builder.json_bytes(capture_from(manifests[index])))
        log_paths[key] = directory / (key + ".log")
        log_paths[key].write_bytes(raws[index])
    return capture_paths, log_paths, manifests, raws


def invoke(captures, logs, output):
    stream = io.StringIO()
    args = ["build_b07_pair_inputs.py", str(captures["one"]), str(captures["two"]),
            "--log-one", str(logs["one"]), "--log-two", str(logs["two"]), "--output", str(output)]
    with patch("sys.argv", args), redirect_stdout(stream):
        code = builder.main()
    return code, json.loads(stream.getvalue())


class B07PairInputBuilderTest(unittest.TestCase):
    def test_complete_bundle_for_both_backends_orders_and_input_positions(self):
        for backend in ("101", "102"):
            for order in ("AB", "BA"):
                for reverse in (False, True):
                    with self.subTest(backend=backend, order=order, reverse=reverse), tempfile.TemporaryDirectory() as temp:
                        directory = Path(temp)
                        captures, logs, manifests, _ = inputs(directory, order, backend)
                        if reverse:
                            captures = dict(one=captures["two"], two=captures["one"])
                            logs = dict(one=logs["two"], two=logs["one"])
                            manifests.reverse()
                        before = {p: p.read_bytes() for p in (*captures.values(), *logs.values())}
                        output = directory / "new-bundle"
                        code, report = invoke(captures, logs, output)
                        self.assertEqual(0, code)
                        self.assertEqual("install_pair_consistent", report["result"])
                        self.assertTrue(report["output"]["bundle_complete"])
                        completion = json.loads((output / "result.json").read_bytes())
                        self.assertTrue(completion["bundle_complete"])
                        self.assertTrue(completion["manifests_written"])
                        self.assertEqual((output / "result.pending.json").read_bytes(), (output / "result.json").read_bytes())
                        for key, expected in zip(("one", "two"), manifests):
                            self.assertEqual(expected, json.loads((output / ("manifest-" + key + ".json")).read_bytes()))
                        replay = verify_pair(json.loads((output / "manifest-one.json").read_bytes()),
                                             json.loads((output / "manifest-two.json").read_bytes()),
                                             (output / "raw-one.log").read_bytes(),
                                             (output / "raw-two.log").read_bytes())
                        self.assertEqual("install_pair_consistent", replay["result"])
                        for row in completion["files"]:
                            data = (output / row["path"]).read_bytes()
                            self.assertEqual(len(data), row["bytes"])
                            self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])
                        self.assertEqual("not_evaluated", report["performance_gate"])
                        self.assertEqual("not_verified", report["device_authentication"])
                        self.assertTrue(all(p.read_bytes() == raw for p, raw in before.items()))

    def test_zero_on_either_side_is_written_unresolved_without_false_ratio(self):
        for index in (0, 1):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                captures, logs, manifests, raws = inputs(directory)
                replace_events(manifests, raws, index,
                               lambda events: events[1].update(cpu_end_ns=events[1]["cpu_start_ns"]))
                logs[("one", "two")[index]].write_bytes(raws[index])
                code, report = invoke(captures, logs, directory / "bundle")
                self.assertEqual(2, code)
                self.assertEqual("unresolved", report["result"])
                self.assertIsNone(report["descriptive_comparison"]["cpu"]["B_over_A"])
                self.assertTrue((directory / "bundle/manifest-one.json").is_file())
                self.assertTrue(json.loads((directory / "bundle/result.json").read_bytes())["manifests_written"])

    def test_identity_mismatch_is_not_inferred_from_log_and_emits_evidence_only(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, _, _ = inputs(directory)
            capture = json.loads(captures["one"].read_bytes())
            capture["pid"] += 50
            captures["one"].write_bytes(builder.json_bytes(capture))
            code, report = invoke(captures, logs, directory / "bundle")
            self.assertEqual(1, code)
            self.assertEqual("invalid", report["result"])
            self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])
            self.assertFalse((directory / "bundle/manifest-one.json").exists())
            self.assertFalse(json.loads((directory / "bundle/result.json").read_bytes())["manifests_written"])
            self.assertEqual(logs["one"].read_bytes(), (directory / "bundle/raw-one.log").read_bytes())

    def test_metadata_missing_and_invalid_peer_keep_invalid_precedence(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, manifests, raws = inputs(directory)
            capture = json.loads(captures["one"].read_bytes())
            del capture["host_uid"]
            captures["one"].write_bytes(builder.json_bytes(capture))
            replace_events(manifests, raws, 1, lambda events: events[-2].update(original_count=1))
            logs["two"].write_bytes(raws[1])
            code, report = invoke(captures, logs, directory / "bundle")
            self.assertEqual(1, code)
            self.assertEqual("invalid", report["result"])
            peer = report["inputs"]["two"]["single_side_report"]
            self.assertEqual("invalid", peer["result"])
            self.assertTrue(peer["install_observations"])
            self.assertEqual(hashlib.sha256(logs["one"].read_bytes()).hexdigest(), report["inputs"]["one"]["raw_log_sha256"])

    def test_reported_failure_and_missing_input_remain_incomplete(self):
        for missing in (False, True):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                captures, logs, manifests, raws = inputs(directory)
                if missing:
                    captures["one"] = directory / "absent.json"
                else:
                    replace_events(manifests, raws, 0, lambda events: events[2].update(status="fail"))
                    logs["one"].write_bytes(raws[0])
                code, report = invoke(captures, logs, directory / "bundle")
                self.assertEqual(2, code)
                self.assertEqual("incomplete", report["result"])
                self.assertFalse((directory / "bundle/manifest-two.json").exists())
                self.assertTrue((directory / "bundle/result.json").is_file())
                self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])

    def test_malformed_capture_values_keep_original_bytes_hash_and_peer(self):
        for raw in (b"null", b"[]", b'{"schema_version":1,"schema_version":1}', b'{"x":NaN}', b"\xff"):
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                captures, logs, _, _ = inputs(directory)
                captures["one"].write_bytes(raw)
                code, report = invoke(captures, logs, directory / "bundle")
                self.assertEqual(1, code)
                self.assertEqual("invalid", report["result"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), report["capture_input_sha256"]["one.capture"])
                self.assertEqual(raw, (directory / "bundle/capture-one.json").read_bytes())
                self.assertTrue(report["inputs"]["two"]["single_side_report"]["install_observations"])

    def test_capture_cannot_override_fixed_protocol_fields(self):
        manifests, raws = fixture()
        capture = capture_from(manifests[0])
        capture["workload"] = "B01"
        candidates, report = builder.build_inputs({"one": capture, "two": capture_from(manifests[1])},
                                                  dict(one=raws[0], two=raws[1]))
        self.assertEqual("invalid", report["result"])
        self.assertNotIn("one", candidates)
        self.assertEqual("B07", candidates["two"]["workload"])

    def test_illegal_capture_is_not_hidden_by_its_missing_log(self):
        manifests, raws = fixture()
        for illegal in (False, True):
            capture = capture_from(manifests[0])
            if illegal:
                capture["host_uid"] = -1
            candidates, report = builder.build_inputs({"one": capture, "two": capture_from(manifests[1])},
                                                      {"one": None, "two": raws[1]})
            self.assertEqual("invalid" if illegal else "incomplete", report["result"])
            self.assertNotIn("one", candidates)
            self.assertEqual("install_record_consistent", report["inputs"]["two"]["single_side_report"]["result"])

    def test_existing_directory_or_file_is_never_changed(self):
        for is_directory in (False, True):
            with self.subTest(is_directory=is_directory), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                captures, logs, _, _ = inputs(directory)
                output = directory / "existing"
                if is_directory:
                    output.mkdir(); (output / "keep").write_bytes(b"prior evidence")
                else:
                    output.write_bytes(b"prior evidence")
                code, report = invoke(captures, logs, output)
                self.assertEqual(1, code)
                self.assertFalse(report["output"]["bundle_complete"])
                self.assertEqual(b"prior evidence", (output / "keep" if is_directory else output).read_bytes())
                if is_directory:
                    self.assertEqual(["keep"], [p.name for p in output.iterdir()])

    def test_missing_input_cannot_be_created_as_output_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, _, _ = inputs(directory)
            missing = directory / "missing.json"
            captures["one"] = missing
            code, report = invoke(captures, logs, missing)
            self.assertEqual(1, code)
            self.assertFalse(missing.exists())
            self.assertIn("differ", report["reason"])

    def test_write_and_readback_failure_do_not_publish_completion(self):
        for failure_kind in ("write", "readback"):
            with self.subTest(kind=failure_kind), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                captures, logs, _, _ = inputs(directory)
                output = directory / "bundle"
                if failure_kind == "write":
                    original = builder._write_checked
                    def fail(path, data):
                        if path.name == "verification.json":
                            raise OSError("injected output failure")
                        return original(path, data)
                    mocked = patch.object(builder, "_write_checked", side_effect=fail)
                else:
                    original = builder.read_input
                    def corrupt(path, limit):
                        result = original(path, limit)
                        return b"corrupt" if path.name == "result.pending.json" else result
                    mocked = patch.object(builder, "read_input", side_effect=corrupt)
                with mocked:
                    code, report = invoke(captures, logs, output)
                self.assertEqual(1, code)
                self.assertFalse(report["output"]["bundle_complete"])
                self.assertFalse((output / "result.json").exists())
                self.assertTrue((output / "raw-one.log").is_file())
                self.assertTrue(report["inputs"]["two"]["single_side_report"]["install_observations"])

    def test_unsupported_hard_link_has_no_publish_fallback(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, _, _ = inputs(directory)
            output = directory / "bundle"
            with patch.object(builder.os, "link", side_effect=OSError("hard links unsupported")):
                code, report = invoke(captures, logs, output)
            self.assertEqual(1, code)
            self.assertFalse((output / "result.json").exists())
            self.assertTrue((output / "result.pending.json").is_file())
            self.assertFalse(report["output"]["bundle_complete"])

    def test_close_failure_retains_partial_without_completion_marker(self):
        class CloseFailure:
            def __init__(self, stream): self.stream = stream
            def __enter__(self): self.stream.__enter__(); return self
            def __exit__(self, *args):
                self.stream.__exit__(*args)
                raise OSError("injected close failure")
            def __getattr__(self, name): return getattr(self.stream, name)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, _, _ = inputs(directory)
            before = {p: p.read_bytes() for p in (*captures.values(), *logs.values())}
            output = directory / "bundle"
            original = Path.open
            def opened(path, *args, **kwargs):
                result = original(path, *args, **kwargs)
                return CloseFailure(result) if path == output / "capture-one.json" and args == ("xb",) else result
            with patch.object(Path, "open", opened):
                code, report = invoke(captures, logs, output)
            self.assertEqual(1, code)
            self.assertFalse(report["output"]["bundle_complete"])
            self.assertFalse((output / "result.json").exists())
            self.assertTrue((output / "capture-one.json").exists())
            self.assertTrue(all(p.read_bytes() == raw for p, raw in before.items()))

    def test_completion_publication_is_exclusive(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            captures, logs, _, _ = inputs(directory)
            output = directory / "bundle"
            original = builder._write_checked
            def race(path, data):
                result = original(path, data)
                if path.name == "result.pending.json":
                    (output / "result.json").write_bytes(b"other writer")
                return result
            with patch.object(builder, "_write_checked", side_effect=race):
                code, report = invoke(captures, logs, output)
            self.assertEqual(1, code)
            self.assertEqual(b"other writer", (output / "result.json").read_bytes())
            self.assertFalse(report["output"]["bundle_complete"])

    def test_short_write_is_detected_and_partial_file_retained(self):
        class ShortWriter:
            def __init__(self, source): self.source = source
            def __enter__(self): self.source.__enter__(); return self
            def __exit__(self, *args): return self.source.__exit__(*args)
            def write(self, data): return self.source.write(data[:2])
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "partial"
            original = Path.open
            def opened(path, *args, **kwargs):
                result = original(path, *args, **kwargs)
                return ShortWriter(result) if path == output and args == ("xb",) else result
            with patch.object(Path, "open", opened), self.assertRaises(OSError):
                builder._write_checked(output, b"complete")
            self.assertEqual(b"co", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
