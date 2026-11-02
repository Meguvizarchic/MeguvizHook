#!/usr/bin/env bash
# Check one Gradle runtime dependency report from stdin.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo 'Usage: check_runtime_dependencies.sh MODULE CONFIGURATION' >&2
  exit 2
fi
module=$1
configuration=$2
expected_backend=''
other_backend=''
kotlin='no'

case "$module" in
  sample-module-101|device-test-module-101|device-benchmark-module-101|device-cpubody-module-101)
    expected_backend='backend-libxposed-101'; other_backend='backend-libxposed-102' ;;
  sample-module-102|device-test-module-102|device-benchmark-module-102|device-cpubody-module-102)
    expected_backend='backend-libxposed-102'; other_backend='backend-libxposed-101' ;;
  sample-module-kotlin-102)
    expected_backend='backend-libxposed-102'; other_backend='backend-libxposed-101'; kotlin='yes' ;;
  device-test-host|device-benchmark-host|device-clock-diagnostic|device-cpubody-host) ;;
  *) echo "Unknown module: ${module}" >&2; exit 2 ;;
esac

case "$configuration" in
  releaseRuntimeClasspath) ;;
  deviceTestRuntimeClasspath)
    case "$module" in
      device-test-*|device-benchmark-*|device-clock-diagnostic|device-cpubody-*) ;;
      *) echo "No deviceTest runtime expected for ${module}" >&2; exit 2 ;;
    esac ;;
  *) echo "Unexpected configuration: ${configuration}" >&2; exit 2 ;;
esac

deps=$(cat)
# A dependency graph for a different module (or a concatenation of reports) cannot
# establish the requested module's isolation, even when it contains the same backend.
project_headers=()
while IFS= read -r line; do
  line=${line%$'\r'}
  case "$line" in
    "Project '"*) project_headers+=("$line") ;;
  esac
done <<<"$deps"
if [[ ${#project_headers[@]} -ne 1 || "${project_headers[0]}" != "Project ':${module}'" ]]; then
  echo "Expected exactly one matching project header for ${module}" >&2
  exit 1
fi
if ! grep -Fq "${configuration} -" <<<"$deps"; then
  echo "Missing ${configuration} report for ${module}" >&2
  exit 1
fi
# Gradle can exit successfully while marking individual dependency nodes FAILED.
# A partial tree cannot establish that the full runtime dependency graph is isolated.
if grep -Eq '^[[:space:]|]*(\+---|\\---)[[:space:]]+.*[[:space:]]FAILED([[:space:]]|$)' <<<"$deps"; then
  echo "Unresolved dependency in ${module} ${configuration}" >&2
  exit 1
fi
if grep -Eq 'io\.github\.libxposed:|hook-testing' <<<"$deps"; then
  echo "Framework/testing dependency leaked into ${module} ${configuration}" >&2
  exit 1
fi
if [[ -n "$expected_backend" ]]; then
  if ! grep -Fq "project ':${expected_backend}'" <<<"$deps"; then
    echo "Expected ${expected_backend} missing from ${module} ${configuration}" >&2
    exit 1
  fi
  if grep -Fq "$other_backend" <<<"$deps"; then
    echo "Other backend generation leaked into ${module} ${configuration}" >&2
    exit 1
  fi
elif grep -Fq 'backend-libxposed-' <<<"$deps"; then
  echo "Backend leaked into ${module} ${configuration}" >&2
  exit 1
fi
if [[ "$kotlin" == 'yes' ]]; then
  if ! grep -Fq 'hook-dsl' <<<"$deps" || ! grep -Fq 'kotlin-stdlib' <<<"$deps"; then
    echo "Kotlin/DSL missing from ${module} ${configuration}" >&2
    exit 1
  fi
elif grep -Fq 'hook-dsl' <<<"$deps" || grep -Fq 'kotlin-stdlib' <<<"$deps"; then
  echo "Kotlin/DSL leaked into ${module} ${configuration}" >&2
  exit 1
fi
