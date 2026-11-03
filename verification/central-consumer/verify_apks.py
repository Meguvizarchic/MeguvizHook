#!/usr/bin/env python3
"""Inspect class definitions in the two built APKs; this never runs or installs them."""

import re
import struct
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
DEX_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex\Z")
MAX_DEX_BYTES = 64 * 1024 * 1024
PREFIX = "Lio/github/jichuo1/meguviz/hook/"


def uint(data, offset):
    if offset < 0 or offset > len(data) - 4:
        raise ValueError("DEX offset outside file")
    return struct.unpack_from("<I", data, offset)[0]


def definitions(data):
    if len(data) < 112 or len(data) > MAX_DEX_BYTES or not re.fullmatch(b"dex\\n[0-9]{3}\\x00", data[:8]):
        raise ValueError("invalid or oversized DEX")
    if uint(data, 32) != len(data) or uint(data, 36) != 112 or uint(data, 40) != 0x12345678:
        raise ValueError("unsupported DEX header")
    string_count, string_offset = uint(data, 56), uint(data, 60)
    type_count, type_offset = uint(data, 64), uint(data, 68)
    class_count, class_offset = uint(data, 96), uint(data, 100)
    for offset, count, width in ((string_offset, string_count, 4), (type_offset, type_count, 4),
                                 (class_offset, class_count, 32)):
        if offset > len(data) or count > (len(data) - offset) // width:
            raise ValueError("DEX table exceeds file")
    result = set()
    for index in range(class_count):
        type_index = uint(data, class_offset + index * 32)
        if type_index >= type_count:
            raise ValueError("invalid class type index")
        string_index = uint(data, type_offset + type_index * 4)
        if string_index >= string_count:
            raise ValueError("invalid descriptor index")
        offset = uint(data, string_offset + string_index * 4)
        # Skip uleb128 UTF-16 length; descriptors of interest are ASCII.
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("truncated DEX string length")
            value = data[offset]
            offset += 1
            if value < 128:
                break
        else:
            raise ValueError("invalid DEX string length")
        end = data.find(b"\0", offset)
        if end < 0:
            raise ValueError("unterminated DEX string")
        try:
            result.add(data[offset:end].decode("ascii"))
        except UnicodeDecodeError:
            # Non-ASCII class descriptors cannot equal any ASCII package prefix checked here.
            continue
    return result


def verify(api):
    apk = ROOT / ("backend" + api) / "build/outputs/apk/debug" / ("backend" + api + "-debug.apk")
    with ZipFile(apk) as archive:
        dex = [item for item in archive.infolist() if DEX_NAME.fullmatch(item.filename)]
        if not dex or any(item.file_size > MAX_DEX_BYTES for item in dex):
            raise ValueError("missing or oversized APK DEX")
        classes = set()
        for item in dex:
            classes.update(definitions(archive.read(item)))
    backend = PREFIX + "backend/libxposed" + api + "/LibXposed" + api + "Backend;"
    required = {backend, PREFIX + "core/HookEnvironment;", PREFIX + "api/HookId;"}
    missing = required - classes
    if missing:
        raise ValueError("expected candidate classes missing: " + ", ".join(sorted(missing)))
    if any(name.startswith("Lio/github/libxposed/") for name in classes):
        raise ValueError("framework classes defined in consumer APK")
    other = "102" if api == "101" else "101"
    if any(name.startswith(PREFIX + "backend/libxposed" + other + "/") for name in classes):
        raise ValueError("other backend generation defined in consumer APK")
    print("APK_" + api + "_CLASS_DEFINITIONS_OK framework_packaged=false")


def verify_refbridge():
    apk = ROOT / "refbridge/build/outputs/apk/debug/refbridge-debug.apk"
    with ZipFile(apk) as archive:
        dex = [item for item in archive.infolist() if DEX_NAME.fullmatch(item.filename)]
        if not dex or any(item.file_size > MAX_DEX_BYTES for item in dex):
            raise ValueError("missing or oversized Ref bridge APK DEX")
        classes = set()
        for item in dex:
            classes.update(definitions(archive.read(item)))
    required = {PREFIX + "ref/MeguvizRefMemberResolver;", PREFIX + "core/HookEnvironment;",
                "Lio/github/jichuo1/meguviz/ref/scope/CodeScope;"}
    missing = required - classes
    if missing:
        raise ValueError("expected Ref bridge classes missing: " + ", ".join(sorted(missing)))
    if any(name.startswith("Lio/github/libxposed/") for name in classes):
        raise ValueError("framework classes defined in Ref bridge consumer APK")
    print("APK_REF_CLASS_DEFINITIONS_OK framework_packaged=false runtime=not_tested")


if __name__ == "__main__":
    try:
        for version in ("101", "102"):
            verify(version)
        verify_refbridge()
    except (OSError, ValueError, KeyError) as failure:
        print("APK_CLASS_CHECK_FAILED: " + str(failure), file=sys.stderr)
        sys.exit(1)
