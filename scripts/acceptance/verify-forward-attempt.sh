#!/usr/bin/env bash
set -euo pipefail

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

input="$(cat)"
if ! forward_id="$(
    printf '%s\n' "$input" \
        | awk '
            /HyperLinkAcceptance event=forward_attempt/ {
                count++
                if (!match($0, /(^|[[:space:]])forwardId=[^[:space:]]+/)) {
                    bad = 1
                } else {
                    token = substr($0, RSTART, RLENGTH)
                    sub(/^.*forwardId=/, "", token)
                    if (id == "") {
                        id = token
                    } else if (id != token) {
                        bad = 1
                    }
                }
                if (!match($0, /(^|[[:space:]])attempt=[0-9]+/)) {
                    bad = 1
                } else {
                    attempt = substr($0, RSTART, RLENGTH)
                    sub(/^.*attempt=/, "", attempt)
                    if (attempt != 1) {
                        bad = 1
                    }
                }
            }
            END {
                if (count != 1 || bad || id == "") {
                    exit 42
                }
                print id
            }
        '
)"; then
    fail 'expected exactly one complete forward_attempt marker with a single forwardId and attempt=1'
fi

if [ -n "${EXPECTED_FORWARD_ID:-}" ] && [ "$forward_id" != "$EXPECTED_FORWARD_ID" ]; then
    fail 'forwardId does not match EXPECTED_FORWARD_ID'
fi

forward_id_hash="$(printf '%s' "$forward_id" | sha256sum | cut -d ' ' -f 1)"
printf 'PASS forwardAttempts=1 attempt=1 forwardIdSha256Prefix=%s\n' "${forward_id_hash:0:12}"
