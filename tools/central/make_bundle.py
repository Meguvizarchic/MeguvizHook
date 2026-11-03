#!/usr/bin/env python3
"""Build a bounded, explicit Central Portal bundle without uploading or signing files.

The signed mode verifies pre-existing signatures with gpgv and an explicitly supplied public
keyring. The preview mode deliberately omits signatures and is not upload-ready. No file is
overwritten, no private key is opened, and no default GPG home is consulted by this script.
"""
import argparse
import hashlib
import json
import ntpath
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

GROUP = "io.github.jichuo1.meguviz.hook"
MODULES = {
    "hook-api": ("jar", ()),
    "hook-core": ("jar", ("hook-api",)),
    "hook-dsl": ("jar", ("hook-api", "hook-core")),
    "hook-testing": ("jar", ("hook-api", "hook-core")),
    "hook-ref-bridge": ("jar", ("hook-core",)),
    "backend-libxposed-101": ("aar", ("hook-api", "hook-core")),
    "backend-libxposed-102": ("aar", ("hook-api", "hook-core")),
}
REF_GROUP = "io.github.jichuo1.meguviz"
REF_NAME = "meguviz-ref-core"
REF_VERSION = "1.0.0"
MAX_FILE = 128 * 1024 * 1024
MAX_TOTAL = 768 * 1024 * 1024
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}\Z")
FINGERPRINT = re.compile(r"(?:[0-9A-F]{40}|[0-9A-F]{64})\Z")


class BundleError(Exception):
    pass


def fail(message):
    raise BundleError(message)


def is_msys_gpg(executable):
    """Recognize Git/MSYS GPG from its executable name and PE import marker, not just PATH."""
    if os.name != "nt":
        return False
    resolved = shutil.which(str(executable)) or str(executable)
    path = Path(resolved)
    if path.name.lower() not in ("gpg.exe", "gpgv.exe"):
        return False
    try:
        if path.stat().st_size > 32 * 1024 * 1024:
            return False
        data = path.read_bytes()
        return data.startswith(b"MZ") and b"msys-2.0.dll" in data.lower()
    except OSError:
        return False


def msys_windows_path(value):
    """Convert an absolute Windows path for a recognized MSYS executable, not Gpg4win."""
    raw = os.fspath(value)
    drive, tail = ntpath.splitdrive(raw)
    if len(drive) == 2 and drive[1] == ":" and tail.startswith(("\\", "/")):
        return "/" + drive[0].lower() + tail.replace("\\", "/")
    if drive.startswith("\\\\") and tail.startswith(("\\", "/")):
        return drive.replace("\\", "/") + tail.replace("\\", "/")
    raise BundleError("MSYS GPG requires an absolute Windows drive or UNC path")


def tool_path(executable, value):
    return msys_windows_path(value) if is_msys_gpg(executable) else os.fspath(value)


def regular(path):
    """Reject symlinks and Windows reparse points in every existing path component."""
    path = Path(os.path.abspath(os.fspath(path)))
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            fail("symbolic links or reparse points are not accepted: " + str(item))
    if not stat.S_ISREG(path.stat().st_mode):
        fail("expected regular file: " + str(path))
    if path.stat().st_size > MAX_FILE:
        fail("file exceeds size limit: " + str(path))
    return path


def read(path):
    path = regular(path)
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        data = stream.read(MAX_FILE + 1)
        after = os.fstat(stream.fileno())
    now = path.stat()
    key = lambda x: (x.st_dev, x.st_ino, x.st_size, x.st_mtime_ns)
    if len(data) > MAX_FILE or key(before) != key(after) or key(after) != key(now):
        fail("file changed while reading: " + str(path))
    return data


def xmltext(root, path):
    element = root.find(path)
    return "" if element is None or element.text is None else element.text.strip()


def verify_pom(data, artifact, version, required):
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        fail("POM entities are forbidden")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        raise BundleError("invalid POM: " + artifact) from exc
    ns = "{http://maven.apache.org/POM/4.0.0}"
    if root.tag != ns + "project":
        fail("invalid POM namespace: " + artifact)
    for name, expected in (("groupId", GROUP), ("artifactId", artifact), ("version", version)):
        if xmltext(root, ns + name) != expected:
            fail("wrong POM " + name + ": " + artifact)
    for path in ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                 "developers/developer/id", "scm/url", "scm/connection", "scm/developerConnection"):
        if not xmltext(root, "/".join(ns + part for part in path.split("/"))):
            fail("missing POM " + path + ": " + artifact)
    packaging = xmltext(root, ns + "packaging") or "jar"
    if packaging != MODULES[artifact][0]:
        fail("wrong POM packaging: " + artifact)
    seen = set()
    seen_ref = False
    for dep in root.findall(ns + "dependencies/" + ns + "dependency"):
        group = xmltext(dep, ns + "groupId")
        name = xmltext(dep, ns + "artifactId")
        if group == GROUP:
            if name in seen or name not in required or xmltext(dep, ns + "version") != version:
                fail("unexpected project dependency: " + artifact)
            if (xmltext(dep, ns + "scope") or "compile") != "compile" or xmltext(dep, ns + "optional") == "true":
                fail("non-API project dependency: " + artifact)
            seen.add(name)
        if group == REF_GROUP:
            if (artifact != "hook-ref-bridge" or name != REF_NAME or seen_ref
                    or xmltext(dep, ns + "version") != REF_VERSION
                    or (xmltext(dep, ns + "scope") or "compile") != "compile"
                    or xmltext(dep, ns + "optional") == "true"):
                fail("unexpected or invalid Ref dependency: " + artifact)
            seen_ref = True
        if group in ("junit", "io.github.libxposed"):
            fail("test or framework compileOnly dependency leaked: " + artifact)
        if artifact.startswith("backend-libxposed-") and group == "org.jetbrains.kotlin" and name.startswith("kotlin-stdlib"):
            fail("Kotlin runtime leaked into Java-only backend POM: " + artifact)
    if seen != set(required):
        fail("missing project dependencies: " + artifact)
    if artifact == "hook-ref-bridge" and not seen_ref:
        fail("missing Ref API dependency: " + artifact)


def verify_archive(data, role, artifact):
    import io
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(entries) > 100000 or len(names) != len(set(names)):
                fail("invalid archive entries: " + artifact)
            for entry in entries:
                name = entry.filename
                if name.startswith(("/", "\\")) or "\\" in name or ".." in name.split("/"):
                    fail("unsafe archive entry: " + artifact)
                if entry.file_size > MAX_FILE or (entry.external_attr >> 16) & 0o170000 == stat.S_IFLNK:
                    fail("unsafe archive member: " + artifact)
            if sum(entry.file_size for entry in entries) > MAX_TOTAL:
                fail("expanded archive exceeds limit: " + artifact)
            if archive.testzip() is not None:
                fail("corrupt archive: " + artifact)
            if role in ("jar", "classes", "embedded") and any(n.startswith("io/github/libxposed/") for n in names):
                fail("framework classes packaged in artifact: " + artifact)
            if role == "aar" and "classes.jar" not in names:
                fail("AAR lacks classes.jar: " + artifact)
            license_files = {n.upper() for n in names}
            if role == "aar":
                license_files.update(verify_archive(archive.read("classes.jar"), "classes", artifact))
                for name in names:
                    if name.startswith("libs/") and name.endswith(".jar"):
                        verify_archive(archive.read(name), "embedded", artifact)
            if role in ("jar", "aar", "sources", "javadoc"):
                license_names = ("META-INF/LICENSE", "META-INF/LICENSE.TXT", "LICENSE", "LICENSE.TXT",
                                 "META-INF/LICENSE-MEGUVIZHOOK-" + artifact.upper())
                notice_names = ("META-INF/NOTICE", "META-INF/NOTICE.TXT", "NOTICE", "NOTICE.TXT",
                                "META-INF/NOTICE-MEGUVIZHOOK-" + artifact.upper())
                if not any(n in license_files for n in license_names):
                    fail(role + " artifact lacks LICENSE: " + artifact)
                if not any(n in license_files for n in notice_names):
                    fail(role + " artifact lacks NOTICE: " + artifact)
            if role == "jar" and not any(n.endswith(".class") for n in names):
                fail("JAR lacks classes: " + artifact)
            if role == "classes" and not any(n.endswith(".class") for n in names):
                fail("AAR classes.jar lacks classes: " + artifact)
            if role == "sources" and not any(n.endswith((".java", ".kt")) for n in names):
                fail("sources JAR lacks sources: " + artifact)
            if role == "sources" and artifact == "hook-dsl" and not any(n.endswith(".kt") for n in names):
                fail("Kotlin DSL sources JAR lacks Kotlin sources")
            if role == "javadoc" and not any(n.endswith(".html") for n in names):
                fail("javadoc JAR lacks HTML: " + artifact)
            return {n.upper() for n in names}
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise BundleError("invalid archive: " + artifact) from exc


def verify_module(data, artifact, version, files):
    try:
        module = json.loads(data)
        component = module["component"]
        if (component["group"], component["module"], component["version"]) != (GROUP, artifact, version):
            fail("wrong Gradle module coordinates: " + artifact)
        library_usages = set()
        for variant in module["variants"]:
            seen = set()
            seen_ref = False
            for dep in variant.get("dependencies", []):
                group, name = dep["group"], dep["module"]
                if group in ("junit", "io.github.libxposed"):
                    fail("test or framework dependency leaked in module metadata: " + artifact)
                if artifact.startswith("backend-libxposed-") and group == "org.jetbrains.kotlin" and name.startswith("kotlin-stdlib"):
                    fail("Kotlin runtime leaked into Java-only backend metadata: " + artifact)
                if group == GROUP:
                    required = MODULES[artifact][1]
                    if name in seen or name not in required or dep.get("version", {}).get("requires") != version:
                        fail("unexpected module project dependency: " + artifact)
                    seen.add(name)
                if group == REF_GROUP:
                    if (artifact != "hook-ref-bridge" or name != REF_NAME or seen_ref
                            or dep.get("version", {}).get("requires") != REF_VERSION):
                        fail("unexpected or invalid Ref module dependency: " + artifact)
                    seen_ref = True
            attributes = variant.get("attributes", {})
            usage = attributes.get("org.gradle.usage")
            if attributes.get("org.gradle.category") == "library" and usage in ("java-api", "java-runtime"):
                library_usages.add(usage)
                if seen != set(MODULES[artifact][1]):
                    fail("missing Gradle module project dependencies: " + artifact)
                if artifact == "hook-ref-bridge" and not seen_ref:
                    fail("missing Gradle module Ref dependency: " + artifact)
            for file in variant.get("files", []):
                name = file["name"]
                if name not in files or file.get("url") != name:
                    fail("module metadata names an absent or unsafe artifact: " + artifact)
                payload = files[name]
                if file.get("size") != len(payload):
                    fail("module artifact size mismatch: " + artifact)
                for alg in ("sha512", "sha256", "sha1", "md5"):
                    if alg in file and hashlib.new(alg, payload).hexdigest() != file[alg]:
                        fail("module artifact checksum mismatch: " + artifact)
        if library_usages != {"java-api", "java-runtime"}:
            fail("missing Gradle library API or runtime variant: " + artifact)
    except (ValueError, KeyError, TypeError) as exc:
        raise BundleError("invalid Gradle module metadata: " + artifact) from exc


def signature(gpgv, keyring, fingerprint, sig_path, artifact_path, expected_bytes):
    # gpgv reads paths itself. Read before and after to detect ordinary modifications; callers must
    # keep staging and keyring immutable during this operation (there is no atomic path locking).
    result = subprocess.run([gpgv, "--homedir", tool_path(gpgv, keyring.parent),
                             "--keyring", tool_path(gpgv, keyring), "--status-fd", "1",
                             tool_path(gpgv, sig_path), tool_path(gpgv, artifact_path)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode != 0:
        fail("gpgv signature verification failed: " + str(artifact_path))
    valid = []
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[:2] == ["[GNUPG:]", "VALIDSIG"]:
            valid.append(fields[2].upper())
            if len(fields) >= 12:
                valid.append(fields[11].upper())  # primary fingerprint for signing subkeys
    if fingerprint not in valid:
        fail("unexpected signing fingerprint: " + str(artifact_path))
    if read(artifact_path) != expected_bytes:
        fail("artifact changed during signature verification: " + str(artifact_path))


def build(staging, output, version, mode, keyring=None, fingerprint=None, gpgv="gpgv"):
    if not VERSION.fullmatch(version) or version.upper().endswith("-SNAPSHOT") or ".." in version:
        fail("version must be a bounded non-SNAPSHOT release version")
    staging = Path(os.path.abspath(os.fspath(staging)))
    for item in (staging, *staging.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            fail("staging uses a link or reparse point")
    if not staging.is_dir():
        fail("staging must be a directory")
    output = Path(os.path.abspath(os.fspath(output)))
    if output.suffix.lower() != ".zip" or output.exists() or output.is_symlink():
        fail("output must be a new .zip file")
    if mode == "unsigned-preview" and "unsigned-preview" not in output.stem.lower():
        fail("preview filename must contain unsigned-preview")
    if not output.parent.is_dir():
        fail("output directory does not exist")
    # Check ancestors without modifying them.
    for parent in (output.parent, *output.parent.parents):
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            fail("output path uses a link or reparse point")
    if mode == "signed":
        if not keyring or not fingerprint or not FINGERPRINT.fullmatch(fingerprint.upper()):
            fail("signed mode requires an explicit keyring and full signing fingerprint")
        keyring = regular(keyring)
        fingerprint = fingerprint.upper()
    elif mode != "unsigned-preview" or keyring or fingerprint:
        fail("invalid preview arguments")
    base = Path(*GROUP.split("."))
    packed = {}
    for artifact, (extension, deps) in MODULES.items():
        directory = staging / base / artifact / version
        stem = artifact + "-" + version
        names = [stem + ".pom", stem + "." + extension, stem + "-sources.jar", stem + "-javadoc.jar"]
        optional = stem + ".module"
        if (directory / optional).exists() or (directory / optional).is_symlink():
            names.append(optional)
        files = {name: read(directory / name) for name in names}
        verify_pom(files[names[0]], artifact, version, deps)
        verify_archive(files[names[1]], extension, artifact)
        verify_archive(files[names[2]], "sources", artifact)
        verify_archive(files[names[3]], "javadoc", artifact)
        if optional in files:
            verify_module(files[optional], artifact, version, files)
        for name, data in files.items():
            relative = (base / artifact / version / name).as_posix()
            packed[relative] = data
            for alg in ("md5", "sha1"):
                packed[relative + "." + alg] = (hashlib.new(alg, data).hexdigest() + "\n").encode("ascii")
            if mode == "signed":
                sig_path = directory / (name + ".asc")
                sig_data = read(sig_path)
                if not sig_data.startswith(b"-----BEGIN PGP SIGNATURE-----"):
                    fail("signature is not ASCII-armored: " + str(sig_path))
                signature(gpgv, keyring, fingerprint, sig_path, directory / name, data)
                if read(sig_path) != sig_data:
                    fail("signature changed during verification")
                packed[relative + ".asc"] = sig_data
    if sum(map(len, packed.values())) > MAX_TOTAL:
        fail("bundle exceeds size limit")
    with output.open("xb") as stream:
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(packed):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, packed[name])
    return len(packed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staging", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--mode", choices=("unsigned-preview", "signed"), required=True)
    parser.add_argument("--keyring", type=Path, help="explicit public verification keyring for gpgv")
    parser.add_argument("--fingerprint", help="full expected signing or primary fingerprint")
    parser.add_argument("--gpgv", default="gpgv", help="gpgv executable")
    args = parser.parse_args()
    try:
        count = build(args.staging, args.output, args.version, args.mode,
                      args.keyring, args.fingerprint, args.gpgv)
    except (BundleError, OSError, ValueError) as exc:
        print("bundle failed: " + str(exc), file=sys.stderr)
        return 1
    print("bundle created: mode=%s files=%d (no upload)" % (args.mode, count))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
