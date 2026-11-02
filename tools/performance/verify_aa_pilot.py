#!/usr/bin/env python3
"""Validate two reported B01/B02/B03/B04/B05/B06 A/A pilot sessions, not a performance gate."""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path


class InvalidRecord(ValueError):
    """A record or pair contradicts the pilot protocol."""


class IncompleteRecord(ValueError):
    """A run did not complete or did not retain all samples."""


HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX16 = re.compile(r"^[0-9a-f]{16}$")
TAG = "MeguvizHookBench"
TAGGED_LINE = re.compile(r"(?:^|\s)" + TAG + r"\s*(?:\(\s*\d+\s*\))?\s*:")
THREADTIME_TAG_START = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+" + TAG + r"(?=$|[\s:(])"
)
THREADTIME_TAG_FIELD = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"\S+\s+\S+\s+\S+\s+(?P<tag>[^\s:]+)"
)
INVALID_UTF8 = re.compile(r"[\udc80-\udcff]")
THREADTIME = re.compile(
    r"^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+[VDIWEF]\s+MeguvizHookBench\s*:\s*(?P<json>\{.*\})\s*$"
)
WORKLOAD_RESULT = {"B01": 42, "B02": 7, "B03": 42, "B04": 9, "B05": 42, "B06": 4}
WORKLOAD_POST_CLOSE_RESULT = {"B01": 42, "B02": 7, "B03": 42, "B04": 7, "B05": 42, "B06": 4}
WORKLOAD_HAS_AFTER = {"B01": True, "B02": False, "B03": True, "B04": False, "B05": True, "B06": True}
WORKLOAD_CALLS = {"B01": 1, "B02": 1, "B03": 1, "B04": 1, "B05": 1, "B06": 4}
LEGACY_KIND = "thread_cpu_envelope_ns"
OBSERVED_KIND = "thread_cpu_envelope_observed_ns"
OBSERVATION_FIELDS = ("original_calls", "hook_before_calls", "hook_after_calls", "off_thread_calls")
FORMAL_METRICS = (
    "callback_cpu_ns", "task_p95_ns", "task_p99_ns", "installation_ms", "cold_start_ms",
    "additional_rss_bytes", "additional_apk_bytes", "additional_fd_count",
    "additional_thread_count", "retired_resident_bytes",
)


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidRecord("duplicate JSON field: " + key)
        result[key] = value
    return result


def is_tagged_line(line):
    return bool(TAGGED_LINE.search(line) or THREADTIME_TAG_START.search(line))


def is_corrupted_target_tag(line):
    """Fail closed when undecodable tag bytes could be the target tag."""
    match = THREADTIME_TAG_FIELD.match(line)
    if not match or not INVALID_UTF8.search(match.group("tag")):
        return False
    positions = {0}
    for char in match.group("tag"):
        if "\udc80" <= char <= "\udcff":
            positions |= {index + 1 for index in positions if index < len(TAG)}
        else:
            positions = {index + 1 for index in positions
                         if index < len(TAG) and TAG[index] == char}
        if not positions:
            return False
    return len(TAG) in positions


def iter_bench_events(raw):
    """Yield validated tagged lines without changing the original log bytes.

    Non-target lines may contain arbitrary logcat bytes; malformed or undecodable
    recognizable target lines always fail rather than disappear from a run.
    """
    for line_number, line in enumerate(raw.decode("utf-8", errors="surrogateescape").splitlines(), 1):
        if INVALID_UTF8.search(line):
            if is_tagged_line(INVALID_UTF8.sub("", line)) or is_corrupted_target_tag(line):
                raise InvalidRecord("invalid UTF-8 on bench log line %d" % line_number)
            continue
        if not is_tagged_line(line):
            continue
        match = THREADTIME.fullmatch(line)
        if not match:
            raise InvalidRecord("bench log line %d is not threadtime JSON" % line_number)
        try:
            event = json.loads(match.group("json"), object_pairs_hook=unique_fields)
        except (ValueError, TypeError) as exc:
            raise InvalidRecord("invalid bench JSON on line %d" % line_number) from exc
        if not isinstance(event, dict):
            raise InvalidRecord("bench event is not an object")
        yield line_number, int(match.group("pid")), int(match.group("tid")), event


def fields(value, expected, where):
    if not isinstance(value, dict):
        raise InvalidRecord(where + " must be an object")
    missing = set(expected) - set(value)
    extra = set(value) - set(expected)
    if missing:
        raise IncompleteRecord(where + " missing fields: " + ", ".join(sorted(missing)))
    if extra:
        raise InvalidRecord(where + " has unknown fields: " + ", ".join(sorted(extra)))


def number(value, where, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise InvalidRecord(where + " must be an integer in the documented range")


def name(value, where):
    if not isinstance(value, str) or not value.strip() or value != value.strip() or value.lower() == "null":
        raise InvalidRecord(where + " must be a nonempty, trimmed identifier")
    if len(value) > 256:
        raise InvalidRecord(where + " is too long")


def digest(value, where):
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        raise InvalidRecord(where + " must be lowercase SHA-256 hex")


def identifier(value, where):
    try:
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise InvalidRecord(where + " must be a canonical lowercase UUID") from None


def validate_observation(value, workload, iterations, where, include_iterations=False):
    if workload not in WORKLOAD_HAS_AFTER:
        raise InvalidRecord("unsupported pilot workload")
    expected = (("iterations",) if include_iterations else ()) + OBSERVATION_FIELDS
    fields(value, expected, where)
    if include_iterations:
        number(value["iterations"], where + ".iterations", 0, 1000000)
        if value["iterations"] != iterations:
            raise InvalidRecord(where + " iterations contradict the configuration")
    expected_calls = iterations * WORKLOAD_CALLS[workload]
    counts = {"original_calls": expected_calls, "hook_before_calls": expected_calls,
              "hook_after_calls": expected_calls if WORKLOAD_HAS_AFTER[workload] else 0,
              "off_thread_calls": 0}
    for key, expected_count in counts.items():
        number(value[key], where + "." + key, 0, 10000000)
        if value[key] != expected_count:
            raise InvalidRecord(where + " contradicts " + key)


def validate_session(record):
    config_hint = record.get("config") if isinstance(record, dict) else None
    observed = isinstance(config_hint, dict) and config_hint.get("measurement_kind") == OBSERVED_KIND
    fields(record, ("schema_version", "record_type", "data_origin", "pair_id", "side",
                    "run_id", "session_id", "process", "thread_id", "workload", "control",
                    "backend", "config", "artifacts", "device", "capture", "preflight",
                    "execution_status", "cleanup_reported", "samples", "formal_metrics")
           + (("warmup_observation",) if observed else ()), "session")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise InvalidRecord("unsupported schema_version")
    if record["record_type"] != "aa_pilot_session" or record["data_origin"] != "device_measurement":
        raise InvalidRecord("record_type or declared data_origin is invalid")
    for key in ("pair_id", "run_id", "session_id"):
        identifier(record[key], key)
    if record["side"] not in ("A", "B"):
        raise InvalidRecord("side must be A or B")
    if not isinstance(record["workload"], str) or record["workload"] not in WORKLOAD_RESULT or record["control"] not in ("A0", "A2"):
        raise InvalidRecord("pilot only covers B01/B02/B03/B04/B05/B06 with A0/A2")
    if record["backend"] not in ("101", "102"):
        raise InvalidRecord("backend must identify the selected adapter generation")

    process = record["process"]
    fields(process, ("pid", "start_token"), "process")
    number(process["pid"], "process.pid", 1, 2**31 - 1)
    name(process["start_token"], "process.start_token")
    number(record["thread_id"], "thread_id", 1, 2**31 - 1)

    config = record["config"]
    fields(config, ("warmup", "samples", "iterations", "measurement_kind", "pair_order"), "config")
    number(config["warmup"], "config.warmup", 0, 1000000)
    number(config["samples"], "config.samples", 1, 200)
    number(config["iterations"], "config.iterations", 1, 1000000)
    if (config["warmup"] + config["samples"] * config["iterations"]) * WORKLOAD_CALLS[record["workload"]] > 10000000:
        raise InvalidRecord("pilot exceeds host total iteration limit")
    if config["measurement_kind"] not in (LEGACY_KIND, OBSERVED_KIND):
        raise InvalidRecord("unsupported measurement_kind")
    if config["pair_order"] not in ("AB", "BA"):
        raise InvalidRecord("pair_order must be AB or BA")

    artifacts = record["artifacts"]
    fields(artifacts, ("host_apk_sha256", "module_apk_sha256", "workload_sha256",
                       "feature_manifest_sha256"), "artifacts")
    for key, value in artifacts.items():
        digest(value, "artifacts." + key)

    device = record["device"]
    fields(device, ("device_id_sha256", "build_fingerprint_sha256", "android_api", "abi",
                    "framework_name", "framework_version", "reported_api"), "device")
    digest(device["device_id_sha256"], "device.device_id_sha256")
    digest(device["build_fingerprint_sha256"], "device.build_fingerprint_sha256")
    number(device["android_api"], "device.android_api", 1, 10000)
    for key in ("abi", "framework_name", "framework_version"):
        name(device[key], "device." + key)
    number(device["reported_api"], "device.reported_api", 1, 10000)

    capture = record["capture"]
    fields(capture, ("raw_log_sha256", "format", "tag", "header_pid", "header_tid"), "capture")
    digest(capture["raw_log_sha256"], "capture.raw_log_sha256")
    if capture["format"] != "threadtime" or capture["tag"] != "MeguvizHookBench":
        raise InvalidRecord("capture must describe the documented threadtime bench log")
    number(capture["header_pid"], "capture.header_pid", 1, 2**31 - 1)
    number(capture["header_tid"], "capture.header_tid", 1, 2**31 - 1)
    if capture["header_pid"] != process["pid"] or capture["header_tid"] != record["thread_id"]:
        raise InvalidRecord("reported log header PID/TID does not match process/thread")

    if record["execution_status"] not in ("completed", "failed", "interrupted"):
        raise InvalidRecord("invalid execution_status")
    if type(record["cleanup_reported"]) is not bool:
        raise InvalidRecord("cleanup_reported must be boolean")
    if record["execution_status"] != "completed" or not record["cleanup_reported"]:
        raise IncompleteRecord("execution or cleanup did not report completion")

    preflight = record["preflight"]
    fields(preflight, ("status", "instrumented", "observed_return", "original_calls",
                       "hook_observed", "evidence_sha256"), "preflight")
    if preflight["status"] == "failed":
        raise IncompleteRecord("preflight reported failure")
    if preflight["status"] == "not_verified":
        if any(preflight[key] is not None for key in ("instrumented", "observed_return", "original_calls",
                                                       "hook_observed", "evidence_sha256")):
            raise InvalidRecord("not_verified preflight cannot contain claimed observations")
    elif preflight["status"] == "reported_pass":
        if type(preflight["instrumented"]) is not bool or preflight["hook_observed"] is not True:
            raise InvalidRecord("reported preflight must declare instrumentation and hook observation")
        if type(preflight["observed_return"]) is not int or preflight["observed_return"] != WORKLOAD_RESULT[record["workload"]]:
            raise InvalidRecord("reported preflight return contradicts workload")
        if (type(preflight["original_calls"]) is not int
                or preflight["original_calls"] != WORKLOAD_CALLS[record["workload"]]):
            raise InvalidRecord("reported preflight original calls contradict workload")
        digest(preflight["evidence_sha256"], "preflight.evidence_sha256")
    else:
        raise InvalidRecord("unknown preflight status")

    metrics = record["formal_metrics"]
    fields(metrics, FORMAL_METRICS, "formal_metrics")
    if any(value is not None for value in metrics.values()):
        raise InvalidRecord("pilot envelope data cannot populate formal performance metrics")

    if observed:
        validate_observation(record["warmup_observation"], record["workload"], config["warmup"],
                             "warmup_observation", include_iterations=True)

    samples = record["samples"]
    if not isinstance(samples, list):
        raise InvalidRecord("samples must be a list")
    if len(samples) != config["samples"]:
        raise IncompleteRecord("sample count differs from declared sample count")
    expected_checksum = "%016x" % ((WORKLOAD_RESULT[record["workload"]] * config["iterations"]) % (2**64))
    previous_end = None
    for index, sample in enumerate(samples):
        where = "samples[%d]" % index
        fields(sample, ("index", "iterations", "thread_cpu_start_ns", "thread_cpu_end_ns", "checksum")
               + (OBSERVATION_FIELDS if observed else ()), where)
        number(sample["index"], where + ".index", 0, 199)
        if sample["index"] != index or type(sample["iterations"]) is not int or sample["iterations"] != config["iterations"]:
            raise InvalidRecord(where + " index or iterations contradict the configuration")
        start, end = sample["thread_cpu_start_ns"], sample["thread_cpu_end_ns"]
        number(start, where + ".thread_cpu_start_ns", 0, 2**63 - 1)
        number(end, where + ".thread_cpu_end_ns", 0, 2**63 - 1)
        if end < start or (previous_end is not None and start < previous_end):
            raise InvalidRecord(where + " CPU clock moved backwards")
        checksum = sample["checksum"]
        if not isinstance(checksum, str) or not HEX16.fullmatch(checksum) or checksum != expected_checksum:
            raise InvalidRecord(where + " checksum contradicts the fixed workload")
        if observed:
            validate_observation({key: sample[key] for key in OBSERVATION_FIELDS},
                                 record["workload"], config["iterations"], where)
        previous_end = end
    return record


def verify_pair(left, right):
    left, right = validate_session(left), validate_session(right)
    if {left["side"], right["side"]} != {"A", "B"}:
        raise InvalidRecord("pair must contain one A and one B side")
    for key in ("pair_id", "workload", "control", "backend", "config", "artifacts", "device"):
        if left[key] != right[key]:
            raise InvalidRecord("pair mismatch: " + key)
    identifiers = [left["pair_id"], left["run_id"], right["run_id"], left["session_id"], right["session_id"]]
    if len(set(identifiers)) != len(identifiers):
        raise InvalidRecord("pair, run and session IDs must be independent")
    if left["process"] == right["process"]:
        raise InvalidRecord("paired sessions must report different processes")
    verified = all(item["preflight"]["status"] == "reported_pass" for item in (left, right))
    return {
        "result": "reported_only",
        "scope": ("reported B01/B02/B03/B04/B05/B06 A/A pilot pair structure only; no performance gate evaluated"
                  if left["workload"] == "B06" else
                  "reported B01/B02/B03/B04/B05 A/A pilot pair structure only; no performance gate evaluated"
                  if left["workload"] == "B05" else
                  "reported B01/B02/B03/B04 A/A pilot pair structure only; no performance gate evaluated"
                  if left["workload"] == "B04" else
                  "reported B01/B02/B03 A/A pilot pair structure only; no performance gate evaluated"
                  if left["workload"] == "B03" else
                  "reported B01/B02 A/A pilot pair structure only; no performance gate evaluated"),
        "provenance": "raw logs not yet checked; device, process-start, framework, artifacts and preflight are collector-reported, not independently authenticated",
        "preflight": "producer_reported_pass" if verified else "not_verified",
        "pair_id": left["pair_id"],
        "workload": left["workload"],
        "control": left["control"],
        "backend": left["backend"],
        "sample_count_per_side": left["config"]["samples"],
        "formal_metrics": {key: None for key in FORMAL_METRICS},
        "performance_gate": "not_evaluated",
    }


def same_value(event, key, expected, where):
    if key not in event:
        raise IncompleteRecord(where + " missing " + key)
    actual = event[key]
    if type(actual) is not type(expected) or actual != expected:
        raise InvalidRecord(where + " contradicts " + key)


def verify_raw_log(record, raw):
    """Match a collected threadtime log to one already validated session record."""
    if hashlib.sha256(raw).hexdigest() != record["capture"]["raw_log_sha256"]:
        raise InvalidRecord("raw log SHA-256 does not match reported capture")
    events = []
    for line_number, line_pid, line_tid, event in iter_bench_events(raw):
        if line_pid == record["process"]["pid"] and line_tid == record["thread_id"]:
            # A malformed run ID from the selected worker must not make a failure disappear.
            identifier(event.get("run_id"), "bench event.run_id")
        if event.get("run_id") != record["run_id"]:
            continue
        if line_pid != record["process"]["pid"] or line_tid != record["thread_id"]:
            raise InvalidRecord("selected bench event has an unexpected threadtime PID/TID")
        for field, expected in (("schema", 1), ("run_id", record["run_id"]), ("pair_id", record["pair_id"]),
                                ("session_id", record["session_id"]), ("pid", record["process"]["pid"]),
                                ("side", record["side"]), ("workload", record["workload"]),
                                ("control", record["control"])):
            same_value(event, field, expected, "bench event")
        if event.get("status") == "fail":
            raise IncompleteRecord("selected bench run reported failure at " + str(event.get("event")))
        events.append(event)

    if not events:
        raise IncompleteRecord("raw log has no events for selected run")
    start = events[0]
    same_value(start, "event", "start", "first event")
    same_value(start, "status", "start", "start")
    for field in ("warmup", "samples", "iterations", "measurement_kind"):
        same_value(start, field, record["config"][field], "start")

    if len(events) < 4:
        raise IncompleteRecord("raw log is missing preflight, post-close check or configuration")
    preflight = events[1]
    same_value(preflight, "event", "preflight", "module preflight")
    same_value(preflight, "status", "pass", "module preflight")
    for field, expected in (("hook_count", WORKLOAD_CALLS[record["workload"]]), ("result", WORKLOAD_RESULT[record["workload"]]),
                            ("original_count", "unverified"), ("hook_scope", "separate_preflight"),
                            ("measurement_hook_verified", False), ("preflight_restoration", "RESTORED")):
        same_value(preflight, field, expected, "module preflight")
    post_close = events[2]
    if post_close.get("event") == "configured":
        raise IncompleteRecord("raw log is missing preflight post-close check")
    same_value(post_close, "event", "preflight_post_close", "preflight post-close check")
    same_value(post_close, "status", "pass", "preflight post-close check")
    for field, expected in (("result", WORKLOAD_POST_CLOSE_RESULT[record["workload"]]),
                            ("hook_count", WORKLOAD_CALLS[record["workload"]]), ("entry_restoration", "RESTORED")):
        same_value(post_close, field, expected, "preflight post-close check")
    configured = events[3]
    same_value(configured, "event", "configured", "module configuration")
    same_value(configured, "status", "pass", "module configuration")
    same_value(configured, "backend", record["backend"], "module configuration")
    for field in ("framework_name", "framework_version", "reported_api"):
        same_value(configured, field, record["device"][field], "module configuration")
    cursor = 4
    observed = record["config"]["measurement_kind"] == OBSERVED_KIND
    if observed:
        if cursor >= len(events) or events[cursor].get("event") == "sample":
            raise IncompleteRecord("raw log is missing warmup observation")
        warmup = events[cursor]
        same_value(warmup, "event", "warmup_observation", "warmup observation")
        same_value(warmup, "status", "pass", "warmup observation")
        for field, expected in record["warmup_observation"].items():
            same_value(warmup, field, expected, "warmup observation")
        cursor += 1
    for sample in record["samples"]:
        if cursor >= len(events):
            raise IncompleteRecord("raw log is missing sample events")
        event = events[cursor]
        same_value(event, "event", "sample", "sample event")
        same_value(event, "status", "pass", "sample event")
        for field in ("index", "iterations", "thread_cpu_start_ns", "thread_cpu_end_ns", "checksum"):
            same_value(event, field, sample[field], "sample event")
        if observed:
            for field in OBSERVATION_FIELDS:
                same_value(event, field, sample[field], "sample observation")
        cursor += 1
    if cursor >= len(events):
        raise IncompleteRecord("raw log is missing cleanup event")
    cleanup = events[cursor]
    same_value(cleanup, "event", "cleanup", "module cleanup")
    same_value(cleanup, "status", "pass", "module cleanup")
    same_value(cleanup, "entry_restoration", "RESTORED", "module cleanup")
    same_value(cleanup, "physical_release", "NOT_CLAIMED", "module cleanup")
    same_value(cleanup, "evidence_source", "framework_unhook_contract" if record["control"] == "A0" else "sdk_close_report", "module cleanup")
    cursor += 1
    if cursor >= len(events):
        raise IncompleteRecord("raw log is missing end event")
    end = events[cursor]
    same_value(end, "event", "end", "end event")
    same_value(end, "status", "pass", "end event")
    same_value(end, "cleanup_reported", True, "end event")
    same_value(end, "detail", "completed", "end event")
    if cursor + 1 != len(events):
        raise InvalidRecord("extra or duplicated selected-run events after end")


def verify_pilot(left, right, left_log, right_log):
    report = verify_pair(left, right)
    verify_raw_log(left, left_log)
    verify_raw_log(right, right_log)
    if left["config"]["measurement_kind"] == OBSERVED_KIND:
        report["result"] = "observed_pilot_data_complete"
        report["scope"] = ("instrumented B01/B02/B03/B04/B05/B06 A/A event and record consistency only; no performance gate evaluated"
                           if left["workload"] == "B06" else
                           "instrumented B01/B02/B03/B04/B05 A/A event and record consistency only; no performance gate evaluated"
                           if left["workload"] == "B05" else
                           "instrumented B01/B02/B03/B04 A/A event and record consistency only; no performance gate evaluated"
                           if left["workload"] == "B04" else
                           "instrumented B01/B02/B03 A/A event and record consistency only; no performance gate evaluated"
                           if left["workload"] == "B03" else
                           "instrumented B01/B02 A/A event and record consistency only; no performance gate evaluated")
        report["instrumentation"] = "observed counts affect thread CPU envelope; not callback CPU"
    else:
        report["result"] = "pilot_data_complete" if report["preflight"] == "producer_reported_pass" else "reported_only"
        report["scope"] = ("reported B01/B02/B03/B04/B05/B06 A/A pilot data integrity only; no performance gate evaluated"
                           if left["workload"] == "B06" else
                           "reported B01/B02/B03/B04/B05 A/A pilot data integrity only; no performance gate evaluated"
                           if left["workload"] == "B05" else
                           "reported B01/B02/B03/B04 A/A pilot data integrity only; no performance gate evaluated"
                           if left["workload"] == "B04" else
                           "reported B01/B02/B03 A/A pilot data integrity only; no performance gate evaluated"
                           if left["workload"] == "B03" else
                           "reported B01/B02 A/A pilot data integrity only; no performance gate evaluated")
    report["provenance"] = ("log bytes and selected event fields checked; device, process-start, framework, "
                            "artifacts and preflight are collector-reported, not independently authenticated")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session_one", type=Path)
    parser.add_argument("session_two", type=Path)
    parser.add_argument("--log-one", type=Path, required=True, help="raw threadtime log for session_one")
    parser.add_argument("--log-two", type=Path, required=True, help="raw threadtime log for session_two")
    args = parser.parse_args()
    try:
        records = []
        for path in (args.session_one, args.session_two):
            with path.open(encoding="utf-8") as source:
                records.append(json.load(source, object_pairs_hook=unique_fields))
        result = verify_pilot(records[0], records[1], args.log_one.read_bytes(), args.log_two.read_bytes())
    except IncompleteRecord as exc:
        result = {"result": "incomplete", "reason": str(exc), "performance_gate": "not_evaluated"}
    except (InvalidRecord, OSError, UnicodeError, json.JSONDecodeError) as exc:
        result = {"result": "invalid", "reason": str(exc), "performance_gate": "not_evaluated"}
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return {"pilot_data_complete": 0, "observed_pilot_data_complete": 0,
            "reported_only": 2, "incomplete": 2, "invalid": 1}[result["result"]]


if __name__ == "__main__":
    sys.exit(main())
