import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest
import uuid
import zipfile

import make_bundle as bundle


VERSION = "1.0.0"


def jar(name, content=b"data"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(name, content)
        archive.writestr("META-INF/LICENSE", "test license")
        archive.writestr("META-INF/NOTICE", "test notice")
    return output.getvalue()


def fixture(root):
    for artifact, (extension, deps) in bundle.MODULES.items():
        directory = root.joinpath(*bundle.GROUP.split("."), artifact, VERSION)
        directory.mkdir(parents=True)
        stem = artifact + "-" + VERSION
        dependencies = "".join("<dependency><groupId>%s</groupId><artifactId>%s</artifactId>"
                               "<version>%s</version><scope>compile</scope></dependency>"
                               % (bundle.GROUP, dep, VERSION) for dep in deps)
        if artifact == "hook-ref-bridge":
            dependencies += ("<dependency><groupId>%s</groupId><artifactId>%s</artifactId>"
                             "<version>%s</version><scope>compile</scope></dependency>"
                             % (bundle.REF_GROUP, bundle.REF_NAME, bundle.REF_VERSION))
        pom = ("<project xmlns='http://maven.apache.org/POM/4.0.0'><modelVersion>4.0.0</modelVersion>"
               "<groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><packaging>%s</packaging>"
               "<name>name</name><description>description</description><url>https://example.org</url>"
               "<licenses><license><name>Apache</name><url>https://example.org/license</url></license></licenses>"
               "<developers><developer><id>example</id></developer></developers>"
               "<scm><url>https://example.org</url><connection>scm:git:example</connection>"
               "<developerConnection>scm:git:example</developerConnection></scm><dependencies>%s</dependencies>"
               "</project>" % (bundle.GROUP, artifact, VERSION, extension, dependencies)).encode()
        (directory / (stem + ".pom")).write_bytes(pom)
        (directory / (stem + "." + extension)).write_bytes(
            jar("classes.jar", jar("Example.class")) if extension == "aar" else jar("Example.class"))
        (directory / (stem + "-sources.jar")).write_bytes(jar("Example.kt" if artifact == "hook-dsl" else "Example.java"))
        (directory / (stem + "-javadoc.jar")).write_bytes(jar("index.html"))
    return root


class BundleTest(unittest.TestCase):
    def setUp(self):
        # Each run keeps its own synthetic fixture; never delete pre-existing or generated evidence.
        self.root = Path(__file__).resolve().parents[2] / "build" / "central-bundle-tests" / uuid.uuid4().hex
        self.root.mkdir(parents=True)
        self.staging = fixture(self.root / "staging")
        self.output = self.root / "bundle-unsigned-preview.zip"

    def path(self, artifact, suffix):
        return self.staging.joinpath(*bundle.GROUP.split("."), artifact, VERSION, artifact + "-" + VERSION + suffix)

    def test_preview_layout_checksums_and_no_overwrite(self):
        self.assertEqual(84, bundle.build(self.staging, self.output, VERSION, "unsigned-preview"))
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(84, len(archive.namelist()))
            for name in archive.namelist():
                if not name.endswith((".md5", ".sha1")):
                    data = archive.read(name)
                    for alg in ("md5", "sha1"):
                        self.assertEqual(hashlib.new(alg, data).hexdigest(), archive.read(name + "." + alg).decode().strip())
                    self.assertFalse(name.endswith(".asc"))
        before = self.output.read_bytes()
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertEqual(before, self.output.read_bytes())

    def test_missing_metadata_wrong_dependencies_and_corruption_fail_closed(self):
        pom = self.path("hook-core", ".pom")
        original = pom.read_bytes()
        pom.write_bytes(original.replace(b"hook-api", b"missing!"))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())
        pom.write_bytes(original.replace(b"<name>name</name>", b""))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        pom.write_bytes(original)
        self.path("hook-api", ".jar").write_bytes(b"corrupt")
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())

    def test_bridge_pom_requires_exact_public_ref_dependency(self):
        path = self.path("hook-ref-bridge", ".pom")
        original = path.read_bytes()
        bundle.verify_pom(original, "hook-ref-bridge", VERSION, ("hook-core",))
        external = ("<dependency><groupId>%s</groupId><artifactId>%s</artifactId>"
                    "<version>%s</version><scope>compile</scope></dependency>"
                    % (bundle.REF_GROUP, bundle.REF_NAME, bundle.REF_VERSION)).encode()
        for modified in (original.replace(external, b""),
                         original.replace(external, external.replace(b"1.0.0", b"2.0.0")),
                         original.replace(external, external.replace(b"compile", b"runtime")),
                         original.replace(external, external + external)):
            with self.assertRaises(bundle.BundleError):
                bundle.verify_pom(modified, "hook-ref-bridge", VERSION, ("hook-core",))

    def test_bridge_gradle_metadata_requires_ref_in_both_library_variants(self):
        project = {"group": bundle.GROUP, "module": "hook-core", "version": {"requires": VERSION}}
        ref = {"group": bundle.REF_GROUP, "module": bundle.REF_NAME,
               "version": {"requires": bundle.REF_VERSION}}
        module = {"component": {"group": bundle.GROUP, "module": "hook-ref-bridge", "version": VERSION},
                  "variants": [{"attributes": {"org.gradle.category": "library", "org.gradle.usage": usage},
                                "dependencies": [project, ref]} for usage in ("java-api", "java-runtime")]}
        bundle.verify_module(json.dumps(module).encode(), "hook-ref-bridge", VERSION, {})
        for i in range(2):
            broken = json.loads(json.dumps(module))
            broken["variants"][i]["dependencies"].pop()
            with self.assertRaises(bundle.BundleError):
                bundle.verify_module(json.dumps(broken).encode(), "hook-ref-bridge", VERSION, {})
        broken = json.loads(json.dumps(module))
        broken["variants"][0]["dependencies"][1]["version"]["requires"] = "2.0.0"
        with self.assertRaises(bundle.BundleError):
            bundle.verify_module(json.dumps(broken).encode(), "hook-ref-bridge", VERSION, {})

    def test_gradle_module_artifact_integrity_and_no_snapshot(self):
        path = self.path("hook-api", ".jar")
        data = path.read_bytes()
        module = {"component": {"group": bundle.GROUP, "module": "hook-api", "version": VERSION},
                  "variants": [{"attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-api"},
                                "files": [{"name": path.name, "url": path.name, "size": len(data),
                                           "sha256": hashlib.sha256(data).hexdigest()}]},
                               {"attributes": {"org.gradle.category": "library", "org.gradle.usage": "java-runtime"}},
                               {"attributes": {"org.gradle.category": "documentation", "org.gradle.usage": "java-runtime"}}]}
        self.path("hook-api", ".module").write_text(json.dumps(module))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, "1.0-SNAPSHOT", "unsigned-preview")
        module["variants"][0]["files"][0]["sha256"] = "0" * 64
        self.path("hook-api", ".module").write_text(json.dumps(module))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())

    def test_preview_requires_clear_filename_and_module_rejects_framework_dependency(self):
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.root / "bundle.zip", VERSION, "unsigned-preview")
        module = {"component": {"group": bundle.GROUP, "module": "hook-api", "version": VERSION},
                  "variants": [{"dependencies": [{"group": "io.github.libxposed", "module": "api"}]}]}
        self.path("hook-api", ".module").write_text(json.dumps(module))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")

    def test_strict_mode_requires_public_keyring_and_signatures(self):
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "signed")
        self.assertFalse(self.output.exists())

    def test_msys_windows_path_conversion(self):
        self.assertEqual("/c/fixture path/keyring.gpg",
                         bundle.msys_windows_path(r"C:\fixture path\keyring.gpg"))
        self.assertEqual("//server/share/space here/file.asc",
                         bundle.msys_windows_path(r"\\server\share\space here\file.asc"))
        with self.assertRaises(bundle.BundleError):
            bundle.msys_windows_path(r"relative\file.asc")

    @unittest.skipUnless(os.name == "nt", "Windows executables only")
    def test_only_msys_gpg_executables_receive_converted_paths(self):
        native = self.root / "native" / "gpg.exe"
        native.parent.mkdir()
        native.write_bytes(b"MZ native Gpg4win executable")
        msys = self.root / "msys" / "gpgv.exe"
        msys.parent.mkdir()
        msys.write_bytes(b"MZ import MSYS-2.0.DLL")
        value = self.root / "file with spaces.asc"
        self.assertFalse(bundle.is_msys_gpg(native))
        self.assertTrue(bundle.is_msys_gpg(msys))
        self.assertEqual(str(value), bundle.tool_path(native, value))
        self.assertEqual(bundle.msys_windows_path(str(value)), bundle.tool_path(msys, value))

    def test_rejects_framework_classes_and_missing_license(self):
        target = self.path("backend-libxposed-102", ".aar")
        target.write_bytes(jar("classes.jar", jar("io/github/libxposed/api/Leaked.class")))
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        target.write_bytes(jar("classes.jar", jar("Example.class")))
        main = self.path("hook-api", ".jar")
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("Example.class", b"example")
        main.write_bytes(output.getvalue())
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())

    def test_rejects_kotlin_runtime_in_backend_pom(self):
        pom = self.path("backend-libxposed-102", ".pom")
        raw = pom.read_bytes().replace(b"</dependencies>", b"<dependency><groupId>org.jetbrains.kotlin</groupId>"
                b"<artifactId>kotlin-stdlib</artifactId><version>2.4.21</version></dependency></dependencies>")
        pom.write_bytes(raw)
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())

    def test_sources_and_javadoc_must_include_license_and_notice(self):
        for suffix, member in (("-sources.jar", "Example.java"), ("-javadoc.jar", "index.html")):
            with self.subTest(suffix=suffix):
                path = self.path("hook-api", suffix)
                original = path.read_bytes()
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w") as archive:
                    archive.writestr(member, b"example")
                    archive.writestr("META-INF/NOTICE", "test notice")
                path.write_bytes(output.getvalue())
                with self.assertRaisesRegex(bundle.BundleError, "lacks LICENSE"):
                    bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
                self.assertFalse(self.output.exists())
                path.write_bytes(original)
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w") as archive:
                    archive.writestr(member, b"example")
                    archive.writestr("META-INF/LICENSE", "test license")
                path.write_bytes(output.getvalue())
                with self.assertRaisesRegex(bundle.BundleError, "lacks NOTICE"):
                    bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
                self.assertFalse(self.output.exists())
                path.write_bytes(original)

    def test_dsl_sources_require_kotlin(self):
        self.path("hook-dsl", "-sources.jar").write_bytes(jar("Example.java"))
        with self.assertRaisesRegex(bundle.BundleError, "lacks Kotlin sources"):
            bundle.build(self.staging, self.output, VERSION, "unsigned-preview")
        self.assertFalse(self.output.exists())

    @unittest.skipUnless(shutil.which("gpg") and shutil.which("gpgv"), "gpg and gpgv required")
    def test_signed_bundle_verifies_independent_ephemeral_signatures(self):
        # Git/MSYS agent has a short Unix socket path limit. Keep this fresh test-only home
        # inside build, use a short unique name, and retain it for audit rather than deleting it.
        for _ in range(10):
            home = Path(__file__).resolve().parents[2] / "build" / "g" / uuid.uuid4().hex[:4]
            try:
                home.mkdir(parents=True, mode=0o700)
                break
            except FileExistsError:
                continue
        else:
            self.fail("could not allocate a unique test GPG home")
        (home / "TEST-KEY-DO-NOT-PUBLISH.txt").write_text("Synthetic testing key only. Never publish this directory.\n")
        gpg = shutil.which("gpg")
        common = [gpg, "--homedir", bundle.tool_path(gpg, home), "--batch", "--yes", "--pinentry-mode", "loopback", "--passphrase", ""]
        subprocess.run(common + ["--quick-generate-key", "Bundle Test <bundle-test@example.invalid>", "ed25519", "sign", "1d"],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        listing = subprocess.run(common + ["--with-colons", "--list-keys"], check=True, stdout=subprocess.PIPE).stdout.decode()
        fingerprint = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))
        keyring = self.root / "public.gpg"
        with keyring.open("wb") as stream:
            subprocess.run(common + ["--export", fingerprint], check=True, stdout=stream)
        for path in list(self.staging.rglob("*")):
            if path.is_file():
                subprocess.run(common + ["--armor", "--detach-sign", "--local-user", fingerprint, bundle.tool_path(gpg, path)],
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(112, bundle.build(self.staging, self.root / "signed-bundle.zip", VERSION, "signed", keyring, fingerprint))
        other = self.root / "bad.zip"
        with self.assertRaises(bundle.BundleError):
            bundle.build(self.staging, other, VERSION, "signed", keyring, "0" * 40)
        self.assertFalse(other.exists())


if __name__ == "__main__":
    unittest.main()
