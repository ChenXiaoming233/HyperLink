#!/usr/bin/env bash
set -euo pipefail

MARKER='forward_attempt'
RUN_ID=''
SINCE=''
EXPECTED_FORWARD_ID=''

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        'usage: verify-forward-attempt.sh --run-id ID --since EPOCH_SECONDS --forward-id ID'
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --run-id)
            [ "$#" -ge 2 ] || fail '--run-id requires a value'
            RUN_ID="$2"
            shift 2
            ;;
        --since)
            [ "$#" -ge 2 ] || fail '--since requires a value'
            SINCE="$2"
            shift 2
            ;;
        --forward-id)
            [ "$#" -ge 2 ] || fail '--forward-id requires a value'
            EXPECTED_FORWARD_ID="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "unknown argument: $1"
            ;;
    esac
done

[ -n "$RUN_ID" ] || fail '--run-id is required and must not be empty'
[ -n "$SINCE" ] || fail '--since is required and must not be empty'
[ -n "$EXPECTED_FORWARD_ID" ] || fail '--forward-id is required and must not be empty'
case "$RUN_ID$EXPECTED_FORWARD_ID" in
    *[[:space:]]*) fail 'run and forward IDs must not contain whitespace' ;;
esac
case "$SINCE" in
    *[!0-9]*) fail '--since must be Unix epoch seconds' ;;
esac

input="$(cat)"
if ! result="$(
    printf '%s\n' "$input" \
        | awk -v marker="$MARKER" \
            -v run_id="$RUN_ID" \
            -v since="$SINCE" \
            -v expected_forward_id="$EXPECTED_FORWARD_ID" '
            {
                token_total = split($0, tokens, /[[:space:]]+/)
                for (token_index = 1; token_index <= token_total; token_index++) {
                    if (tokens[token_index] != marker) {
                        continue
                    }

                    run_count = 0
                    timestamp_count = 0
                    forward_count = 0
                    attempt_count = 0
                    matches_expected_run = 0
                    run = ""
                    timestamp = ""
                    forward = ""
                    attempt = ""
                    for (field_index = token_index + 1; field_index <= token_total; field_index++) {
                        if (tokens[field_index] == marker) {
                            break
                        }
                        if (tokens[field_index] ~ /^runId=/) {
                            run_count++
                            run_value = tokens[field_index]
                            sub(/^runId=/, "", run_value)
                            if (run_value == run_id) {
                                matches_expected_run = 1
                            }
                            if (run_count == 1) {
                                run = run_value
                            }
                        } else if (tokens[field_index] ~ /^timestampEpoch=/) {
                            timestamp_count++
                            if (timestamp_count == 1) {
                                timestamp = tokens[field_index]
                                sub(/^timestampEpoch=/, "", timestamp)
                            }
                        } else if (tokens[field_index] ~ /^forwardId=/) {
                            forward_count++
                            if (forward_count == 1) {
                                forward = tokens[field_index]
                                sub(/^forwardId=/, "", forward)
                            }
                        } else if (tokens[field_index] ~ /^attempt=/) {
                            attempt_count++
                            if (attempt_count == 1) {
                                attempt = tokens[field_index]
                                sub(/^attempt=/, "", attempt)
                            }
                        }
                    }

                    if (run_count != 1 || run == "") {
                        bad = 1
                        continue
                    }

                    if (!matches_expected_run) {
                        continue
                    }

                    selected++
                    if (run_count != 1 || timestamp_count != 1 || forward_count != 1 || attempt_count != 1) {
                        bad = 1
                    }
                    if (run != run_id || timestamp !~ /^[0-9]+$/ || timestamp + 0 < since + 0 || forward == "" || attempt !~ /^[0-9]+$/ || attempt + 0 != 1) {
                        bad = 1
                    }
                    if (selected == 1) {
                        selected_run = run
                        selected_forward = forward
                    } else if (forward != selected_forward) {
                        bad = 1
                    }
                }
            }
            END {
                if (selected != 1 || bad || selected_run == "" || selected_forward == "" || selected_forward != expected_forward_id) {
                    exit 42
                }
                print selected_run "\t" selected_forward
            }
        '
)"; then
    fail 'expected exactly one current-run forward_attempt token with unique fields, attempt=1, and the expected forwardId'
fi

selected_run_id="${result%%$'\t'*}"
forward_id="${result#*$'\t'}"
run_id_hash="$(printf '%s' "$selected_run_id" | sha256sum | cut -d ' ' -f 1)"
forward_id_hash="$(printf '%s' "$forward_id" | sha256sum | cut -d ' ' -f 1)"
printf 'PASS forwardAttempts=1 attempt=1 runIdSha256Prefix=%s forwardIdSha256Prefix=%s\n' \
    "${run_id_hash:0:12}" "${forward_id_hash:0:12}"
