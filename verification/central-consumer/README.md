# Independent Maven consumer verification

This standalone Gradle build resolves all MeguvizHook libraries only from the explicitly supplied
Maven repository. It contains no project dependency on the source checkout and performs no device
operation. Android consumers use separate apps for API 101, API 102 and the optional Ref bridge;
backend apps declare the framework API as `compileOnly` themselves. The JVM consumer installs and
closes hooks against the fake backend, including descriptor resolution through the real Ref bridge.

Run both metadata modes from the repository root, replacing the staging URI and version with the
exact candidate. Use an absolute `file:` URI for local staging. Android SDK 37.2 and build-tools
37.0.0 are needed, as for the main project.

```sh
./gradlew -p verification/central-consumer verifyCandidate \
  -PcentralRepo=file:///absolute/path/to/maven/repository -PcentralVersion=1.0.0 \
  -PmetadataMode=gradle --no-daemon
python verification/central-consumer/verify_apks.py
./gradlew -p verification/central-consumer verifyCandidate \
  -PcentralRepo=file:///absolute/path/to/maven/repository -PcentralVersion=1.0.0 \
  -PmetadataMode=pom --no-daemon
python verification/central-consumer/verify_apks.py
```

`gradle` mode requires Gradle module metadata rather than falling back to POM; `pom` mode ignores
Gradle metadata redirection and resolves the POM. Both verify transitive API/core dependencies,
selected candidate versions, each framework's compile-only presence and its runtime absence. The
bridge consumers also require `meguviz-ref-core:1.0.0` as a transitive dependency. The standalone APK
check inspects defined DEX classes, requires the bridge and Ref classes, and rejects bundled framework
classes or the opposite backend. Run it after each mode if preserving evidence for all APKs; the
second Gradle run can overwrite the first mode's debug APKs. Assembled APKs and fake-backend
execution do not establish actual Android, Binder or Hook behavior.
