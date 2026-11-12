#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFIER="$ROOT_DIR/scripts/acceptance/verify-forward-attempt.sh"
PREFLIGHT="$ROOT_DIR/scripts/acceptance/preflight-device.sh"
FAKE_ADB="$ROOT_DIR/scripts/acceptance/test-fixtures/fake-adb"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "missing test dependency: ${1#"$ROOT_DIR"/}"
}

require_file "$FAKE_ADB"
source "$FAKE_ADB"
export -f fake_adb

expected_signer_sha256="$(
    printf '\x01\x02\x03\x04' | sha256sum | cut -d ' ' -f 1
)"

run_preflight() {
    local scenario="$1"
    shift
    FAKE_ADB_SCENARIO="$scenario" \
    ADB_BIN=fake_adb \
    HYPERLINK_ACCEPTANCE_DEVICE_OK=1 \
    ANDROID_SERIAL=fixture-serial \
    "$@" bash "$PREFLIGHT"
}

expect_preflight_failure() {
    local scenario="$1"
    local expected="$2"
    local output
    if output="$(run_preflight "$scenario" \
        env \
        VOICEASSIST_SIGNER_SHA256="$expected_signer_sha256" \
        MILINK_SIGNER_SHA256="$expected_signer_sha256" 2>&1)"
    then
        fail "preflight unexpectedly passed for scenario $scenario"
    fi
    case "$output" in
        *"$expected"*) ;;
        *) fail "preflight failure for $scenario did not contain: $expected" ;;
    esac
    case "$output" in
        *'PASS: read-only preflight metadata and hashes collected'*)
            fail "preflight printed PASS for failed scenario $scenario"
            ;;
    esac
}

run_verifier() {
    local input="$1"
    shift
    printf '%s\n' "$input" | bash "$VERIFIER" "$@"
}

expect_verifier_failure() {
    local description="$1"
    local input="$2"
    shift 2
    if run_verifier "$input" "$@" >/dev/null 2>&1; then
        fail "$description"
    fi
}

current_marker='I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000100 forwardId=forward-current attempt=1'
historical_marker='I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-old timestampEpoch=1600000000 forwardId=forward-current attempt=1'

require_file "$VERIFIER"
require_file "$PREFLIGHT"

forward_pass="$(
    run_verifier "$current_marker" \
        --run-id run-current \
        --since 1700000000 \
        --forward-id forward-current
)" || fail "forward verifier rejected the current-run positive case"
case "$forward_pass" in
    *'forwardAttempts=1 attempt=1'*) ;;
    *) fail "forward verifier returned an unexpected positive result" ;;
esac

historical_pass="$(
    run_verifier "$current_marker"$'\n'"$historical_marker" \
        --run-id run-current \
        --since 1700000000 \
        --forward-id forward-current
)" || fail "forward verifier counted an old run with the same forwardId"
case "$historical_pass" in
    *'forwardAttempts=1 attempt=1'*) ;;
    *) fail "forward verifier returned an unexpected old-run result" ;;
esac

different_run_ignored="$(
    run_verifier "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-old' \
        --run-id run-current \
        --since 1700000000 \
        --forward-id forward-current
)" || fail "forward verifier did not ignore a different run with missing fields"
case "$different_run_ignored" in
    *'forwardAttempts=1 attempt=1'*) ;;
    *) fail "forward verifier returned an unexpected different-run result" ;;
esac

complete_different_run_ignored="$(
    run_verifier "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-old timestampEpoch=1700000101 forwardId=forward-other attempt=1' \
        --run-id run-current \
        --since 1700000000 \
        --forward-id forward-current
)" || fail "forward verifier did not ignore a complete marker from another run"
case "$complete_different_run_ignored" in
    *'forwardAttempts=1 attempt=1'*) ;;
    *) fail "forward verifier returned an unexpected complete different-run result" ;;
esac

expect_verifier_failure \
    "forward verifier accepted a marker without runId" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt timestampEpoch=1700000101 forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted an empty runId marker" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId= timestampEpoch=1700000101 forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted duplicate current runId tokens" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current runId=run-current timestampEpoch=1700000101 forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier ignored an expected-run marker with missing timestamp" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current forwardId=forward-other attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier ignored an expected-run marker with missing forwardId" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000101 attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier ignored an expected-run marker with missing attempt" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000101 forwardId=forward-other' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier ignored an expected-run marker with non-numeric timestamp" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=not-a-number forwardId=forward-other attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier ignored an expected-run marker before the time window" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1600000000 forwardId=forward-other attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted another forwardId in the current run" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000101 forwardId=forward-other attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier counted lines instead of marker occurrences" \
    'I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000100 forwardId=forward-current attempt=1 HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000100 forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted duplicate forwardId tokens" \
    'I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000100 forwardId=forward-current forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted multiple attempt tokens" \
    'I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000100 forwardId=forward-current attempt=1 attempt=2' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted attempt=1 plus attempt=2" \
    "$current_marker"$'\n''I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempt runId=run-current timestampEpoch=1700000101 forwardId=forward-current attempt=2' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted an attempted token" \
    'I/HyperLinkAcceptance: HyperLinkAcceptance forward_attempted runId=run-current timestampEpoch=1700000100 forwardId=forward-current attempt=1' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted input without a marker" \
    'I/HyperLinkAcceptance: unrelated line without a marker' \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted a forwardId mismatch" \
    "$current_marker" \
    --run-id run-current \
    --since 1700000000 \
    --forward-id forward-other

expect_verifier_failure \
    "forward verifier accepted a missing --run-id" \
    "$current_marker" \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted a missing --since" \
    "$current_marker" \
    --run-id run-current \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted a missing --forward-id" \
    "$current_marker" \
    --run-id run-current \
    --since 1700000000

expect_verifier_failure \
    "forward verifier accepted an empty --run-id" \
    "$current_marker" \
    --run-id '' \
    --since 1700000000 \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted an empty --since" \
    "$current_marker" \
    --run-id run-current \
    --since '' \
    --forward-id forward-current

expect_verifier_failure \
    "forward verifier accepted an empty --forward-id" \
    "$current_marker" \
    --run-id run-current \
    --since 1700000000 \
    --forward-id ''

preflight_success="$(
    run_preflight success env \
        VOICEASSIST_SIGNER_SHA256="$expected_signer_sha256" \
        MILINK_SIGNER_SHA256="$expected_signer_sha256"
)" || fail "preflight sandbox positive case failed"
case "$preflight_success" in
    *'voiceassist.pid=123'*) ;;
    *) fail "preflight did not report VoiceAssist PID" ;;
esac
case "$preflight_success" in
    *'milink.pid=456'*) ;;
    *) fail "preflight did not report MiLink PID" ;;
esac
case "$preflight_success" in
    *'SIGNATURE_VERIFICATION=VERIFIED'*) ;;
    *) fail "preflight did not verify extracted certificate SHA-256" ;;
esac

expect_preflight_failure pid-missing-voiceassist \
    'voiceassist process is unavailable'
expect_preflight_failure pid-missing-milink \
    'milink process is unavailable'
expect_preflight_failure signature-unverified \
    'SIGNATURE_VERIFICATION=UNVERIFIED'
expect_preflight_failure multiple-devices \
    'exactly one online adb device is required'
expect_preflight_failure serial-mismatch \
    'ANDROID_SERIAL does not match the online device'
expect_preflight_failure offline-device \
    'exactly one online adb device is required'

if signature_mismatch_output="$(run_preflight signature-mismatch env \
    VOICEASSIST_SIGNER_SHA256='0000000000000000000000000000000000000000000000000000000000000000' \
    MILINK_SIGNER_SHA256="$expected_signer_sha256" 2>&1)"
then
    fail "preflight accepted a certificate SHA-256 mismatch"
fi
case "$signature_mismatch_output" in
    *'SIGNATURE_VERIFICATION=MISMATCH'*) ;;
    *) fail "preflight did not report certificate SHA-256 mismatch" ;;
esac

if missing_signer_output="$(env \
    ADB_BIN=fake_adb \
    FAKE_ADB_SCENARIO=success \
    HYPERLINK_ACCEPTANCE_DEVICE_OK=1 \
    ANDROID_SERIAL=fixture-serial \
    MILINK_SIGNER_SHA256="$expected_signer_sha256" \
    bash "$PREFLIGHT" 2>&1)"
then
    fail "preflight accepted a missing expected signer SHA-256"
fi
case "$missing_signer_output" in
    *'VOICEASSIST_SIGNER_SHA256'*) ;;
    *) fail "preflight did not fail on missing VOICEASSIST_SIGNER_SHA256" ;;
esac

large_output="$(
    run_preflight large-dumpsys env \
        VOICEASSIST_SIGNER_SHA256="$expected_signer_sha256" \
        MILINK_SIGNER_SHA256="$expected_signer_sha256"
)" || fail "preflight failed while consuming a large dumpsys stream"
case "$large_output" in
    *'SIGNATURE_VERIFICATION=VERIFIED'*) ;;
    *) fail "large dumpsys scenario did not complete signature verification" ;;
esac

printf 'PASS: acceptance kit behavioral sandbox tests\n'
