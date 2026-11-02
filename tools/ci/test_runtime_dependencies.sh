#!/usr/bin/env bash
# Verify the gate with synthetic Gradle reports; no dependency resolution is performed here.
set -euo pipefail
checker="$(cd "$(dirname "$0")" && pwd)/check_runtime_dependencies.sh"

expect_pass() {
  if ! printf "Project ':%s'\n%s\n" "$1" "$3" | bash "$checker" "$1" "$2" >/dev/null 2>&1; then
    echo "Expected pass: $1 $2" >&2
    exit 1
  fi
}
expect_fail() {
  if printf "Project ':%s'\n%s\n" "$1" "$3" | bash "$checker" "$1" "$2" >/dev/null 2>&1; then
    echo "Expected rejection: $1 $2" >&2
    exit 1
  fi
}
expect_fail_raw() {
  if printf '%s\n' "$3" | bash "$checker" "$1" "$2" >/dev/null 2>&1; then
    echo "Expected rejection of raw report: $1 $2" >&2
    exit 1
  fi
}

release='releaseRuntimeClasspath - Runtime classpath of release'
device='deviceTestRuntimeClasspath - Runtime classpath of deviceTest'
backend101="project ':backend-libxposed-101'"
backend102="project ':backend-libxposed-102'"
kotlin='project :hook-dsl
org.jetbrains.kotlin:kotlin-stdlib:2.4.21'

expect_pass sample-module-101 releaseRuntimeClasspath "${release}
${backend101}"
expect_pass sample-module-kotlin-102 releaseRuntimeClasspath "${release}
${backend102}
${kotlin}"
expect_pass device-benchmark-module-101 deviceTestRuntimeClasspath "${device}
${backend101}"
expect_pass device-test-host deviceTestRuntimeClasspath "${device}
No dependencies"
expect_pass device-clock-diagnostic deviceTestRuntimeClasspath "${device}
No dependencies"
expect_pass device-cpubody-module-101 deviceTestRuntimeClasspath "${device}
${backend101}"
expect_pass device-cpubody-host deviceTestRuntimeClasspath "${device}
No dependencies"
expect_pass sample-module-101 releaseRuntimeClasspath "${release}
${backend101}
Gradle documentation: FAILED is a dependency status, not an error in this explanatory line."

expect_fail sample-module-101 releaseRuntimeClasspath "${release}
${backend101}
io.github.libxposed:api:101.0.0"
expect_fail sample-module-101 releaseRuntimeClasspath "${release}
+--- ${backend101}
|    \\--- org.example:missing:1.0 FAILED"
expect_fail device-test-module-102 deviceTestRuntimeClasspath "${device}
+--- ${backend102}
\\--- org.example:missing:2.0 -> 2.1 FAILED"
expect_fail device-test-module-102 deviceTestRuntimeClasspath "${device}
${backend102}
io.github.libxposed:api:102.0.0"
expect_fail device-test-module-101 deviceTestRuntimeClasspath "${device}
${backend101}
${backend102}"
expect_fail device-test-module-101 deviceTestRuntimeClasspath "${device}
No dependencies"
expect_fail device-test-module-101 deviceTestRuntimeClasspath "${device}
${backend101}
project ':hook-testing'"
expect_fail sample-module-101 releaseRuntimeClasspath "${release}
${backend101}
org.jetbrains.kotlin:kotlin-stdlib:2.4.21"
expect_fail sample-module-kotlin-102 releaseRuntimeClasspath "${release}
${backend102}
project :hook-dsl"
expect_fail device-benchmark-host deviceTestRuntimeClasspath "${device}
${backend102}"
expect_fail device-clock-diagnostic deviceTestRuntimeClasspath "${device}
${backend102}"
expect_fail device-cpubody-module-101 deviceTestRuntimeClasspath "${device}
${backend101}
${backend102}"
expect_fail device-test-module-101 deviceTestRuntimeClasspath "${release}
${backend101}"
expect_fail sample-module-101 deviceTestRuntimeClasspath "${device}
${backend101}"
expect_fail_raw sample-module-101 releaseRuntimeClasspath "${release}
${backend101}"
expect_fail_raw sample-module-101 releaseRuntimeClasspath "Project ':device-test-module-101'
${release}
${backend101}"
expect_fail_raw sample-module-101 releaseRuntimeClasspath "Project ':sample-module-101'
${release}
${backend101}
Project ':device-test-module-101'
${release}
${backend101}"
expect_fail_raw sample-module-101 releaseRuntimeClasspath "Project ':sample-module-101'
${release}
${backend101}
Project ':sample-module-101'
${release}
${backend101}"
echo 'runtime dependency gate: positive and negative cases passed'
