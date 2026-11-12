#!/usr/bin/env bash
set -euo pipefail

MARKER='HyperLinkAcceptance event=forward_attempt'
RUN_ID=''
SINCE=''
EXPECTED_FORWARD_ID=''

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        'usage: verify-forward-attempt.sh [--run-id ID] [--since EPOCH_SECONDS] [--forward-id ID]' \
        'At least one scope option is required. Multiple options are combined with AND.'
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

[ -n "$RUN_ID" ] || [ -n "$SINCE" ] || [ -n "$EXPECTED_FORWARD_ID" ] \
    || fail 'provide --run-id, --since, or --forward-id to reject historical logs'
case "$RUN_ID$EXPECTED_FORWARD_ID" in
    *[[:space:]]*) fail 'run and forward IDs must not contain whitespace' ;;
esac
if [ -n "$SINCE" ]; then
    case "$SINCE" in
        *[!0-9]*) fail '--since must be Unix epoch seconds' ;;
    esac
fi

input="$(cat)"
if ! result="$(
    printf '%s\n' "$input" \
        | awk -v marker="$MARKER" \
            -v run_id="$RUN_ID" \
            -v since="$SINCE" \
            -v expected_forward_id="$EXPECTED_FORWARD_ID" '
            function token_count(text, key,    tmp, pattern) {
                pattern = "(^|[[:space:]])" key "=[^[:space:]]+"
                tmp = text
                return gsub(pattern, "&", tmp)
            }
            function token_value(text, key,    tmp, found, pattern) {
                pattern = "(^|[[:space:]])" key "=[^[:space:]]+"
                tmp = text
                if (!match(tmp, pattern)) {
                    return ""
                }
                found = substr(tmp, RSTART, RLENGTH)
                sub("^[[:space:]]*", "", found)
                sub("^" key "=", "", found)
                return found
            }
            {
                segment_count = split($0, segments, marker)
                if (segment_count <= 1) {
                    next
                }
                for (part = 2; part <= segment_count; part++) {
                    segment = segments[part]
                    run = token_value(segment, "runId")
                    timestamp = token_value(segment, "timestampEpoch")
                    forward = token_value(segment, "forwardId")
                    if (run_id != "" && run != run_id) {
                        continue
                    }
                    if (since != "" && timestamp !~ /^[0-9]+$/) {
                        continue
                    }
                    if (since != "" && timestamp + 0 < since + 0) {
                        continue
                    }
                    if (expected_forward_id != "" && forward != expected_forward_id) {
                        continue
                    }

                    selected++
                    if (token_count(segment, "runId") != 1 || token_count(segment, "timestampEpoch") != 1 || token_count(segment, "forwardId") != 1 || token_count(segment, "attempt") != 1) {
                        bad = 1
                    }
                    if (run == "" || forward == "" || timestamp !~ /^[0-9]+$/) {
                        bad = 1
                    }
                    attempt = token_value(segment, "attempt")
                    if (attempt !~ /^[0-9]+$/ || attempt + 0 != 1) {
                        bad = 1
                    }
                    if (selected == 1) {
                        selected_run = run
                        selected_forward = forward
                    } else if (run != selected_run || forward != selected_forward) {
                        bad = 1
                    }
                }
            }
            END {
                if (selected != 1 || bad || selected_run == "" || selected_forward == "") {
                    exit 42
                }
                print selected_run "\t" selected_forward
            }
        '
)"; then
    fail 'expected exactly one scoped forward_attempt occurrence with one runId, one timestamp, one forwardId, and one attempt=1'
fi

selected_run_id="${result%%$'\t'*}"
forward_id="${result#*$'\t'}"
run_id_hash="$(printf '%s' "$selected_run_id" | sha256sum | cut -d ' ' -f 1)"
forward_id_hash="$(printf '%s' "$forward_id" | sha256sum | cut -d ' ' -f 1)"
printf 'PASS forwardAttempts=1 attempt=1 runIdSha256Prefix=%s forwardIdSha256Prefix=%s\n' \
    "${run_id_hash:0:12}" "${forward_id_hash:0:12}"
