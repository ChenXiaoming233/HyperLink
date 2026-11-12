#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MATRIX_FILE="$ROOT_DIR/docs/acceptance/matrix.md"
EVIDENCE_FILE="$ROOT_DIR/docs/acceptance/evidence-template.md"
OPERATIONS_FILE="$ROOT_DIR/docs/acceptance/operations.md"
README_FILE="$ROOT_DIR/docs/acceptance/README.md"
PREFLIGHT_FILE="$ROOT_DIR/scripts/acceptance/preflight-device.sh"
FORWARD_VERIFIER_FILE="$ROOT_DIR/scripts/acceptance/verify-forward-attempt.sh"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "missing required file: ${1#"$ROOT_DIR"/}"
}

require_text() {
    local file="$1"
    local text="$2"
    grep -Fq -- "$text" "$file" || fail "missing text '$text' in ${file#"$ROOT_DIR"/}"
}

require_file "$MATRIX_FILE"
require_file "$EVIDENCE_FILE"
require_file "$OPERATIONS_FILE"
require_file "$README_FILE"
require_file "$PREFLIGHT_FILE"
require_file "$FORWARD_VERIFIER_FILE"

for header in \
    '| ID | #10 条目 | 验收目标 | 前置条件 | 操作 | 预期结果 | 证据类型 | 安全恢复步骤 |'
do
    require_text "$MATRIX_FILE" "$header"
done

row_count="$(grep -Ec '^\| ACC-[0-9]{2} ' "$MATRIX_FILE")"
[ "$row_count" -eq 14 ] || fail "matrix must contain exactly 14 functional rows, found $row_count"

for number in $(seq -w 1 14); do
    count="$(grep -Ec "^\\| ACC-$number " "$MATRIX_FILE")"
    [ "$count" -eq 1 ] || fail "matrix row ACC-$number must appear exactly once, found $count"
done

if grep -Eq '^\| ACC-15 ' "$MATRIX_FILE"; then
    fail "evidence record requirement must be cross-cutting, not ACC-15 functional scenario"
fi

awk -F'|' '
    /^\| ACC-[0-9][0-9] / {
        if (NF != 10) {
            printf "FAIL: %s must contain exactly 8 non-empty table fields\n", $2 > "/dev/stderr"
            bad = 1
        }
        for (field = 3; field <= 9; field++) {
            if ($field ~ /^[[:space:]]*$/) {
                printf "FAIL: %s contains an empty required field\n", $2 > "/dev/stderr"
                bad = 1
            }
        }
    }
    END { exit bad }
' "$MATRIX_FILE" || fail "matrix field validation failed"

for text in \
    '#10 第 15 条' \
    '证据记录要求' \
    '有且仅有一次转发尝试' \
    'forwardId' \
    'attempt=1'
do
    require_text "$MATRIX_FILE" "$text"
done

for text in \
    '设备型号' \
    '系统版本' \
    'VoiceAssist 版本' \
    'MiLink 版本' \
    'APK SHA-256' \
    '证据时间' \
    '日志归档' \
    'Issue 链接' \
    '执行人' \
    '结果'
do
    require_text "$EVIDENCE_FILE" "$text"
done

for pattern in \
    '^- 设备型号：`<[^>]+>`' \
    '^- 系统版本：`<[^>]+>`' \
    '^- VoiceAssist 版本：`<[^>]+>`' \
    '^- MiLink 版本：`<[^>]+>`' \
    '^- APK SHA-256：`<[^>]+>`' \
    '^- 证据时间：`<[^>]+>`' \
    '^- 日志归档：`<[^>]+>`' \
    '^- 执行人：`<[^>]+>`' \
    '^- 结果：`<[^>]+>`'
do
    grep -Eq -- "$pattern" "$EVIDENCE_FILE" \
        || fail "evidence template must keep placeholder value for pattern: $pattern"
done

for text in \
    '只读预检' \
    '受控通知测试' \
    '日志过滤' \
    'LKG' \
    '受控 API' \
    '禁止 shell 直接覆盖生产 JSON' \
    '前置哈希' \
    '恢复后哈希' \
    '失败退出' \
    '中止' \
    '回滚'
do
    require_text "$OPERATIONS_FILE" "$text"
done

for text in \
    '不执行任何实机验收' \
    'docs/acceptance/matrix.md' \
    'scripts/acceptance/preflight-device.sh' \
    'Issue #10' \
    'Issue #11'
do
    require_text "$README_FILE" "$text"
done

[ "$(sed -n '1p' "$PREFLIGHT_FILE")" = '#!/usr/bin/env bash' ] \
    || fail "preflight must be a bash script"
require_text "$PREFLIGHT_FILE" 'set -euo pipefail'
require_text "$PREFLIGHT_FILE" 'HYPERLINK_ACCEPTANCE_DEVICE_OK'
require_text "$PREFLIGHT_FILE" 'ANDROID_SERIAL'
require_text "$PREFLIGHT_FILE" 'ro.product.model'
require_text "$PREFLIGHT_FILE" 'ro.build.version.release'
require_text "$PREFLIGHT_FILE" 'ro.build.version.sdk'
require_text "$PREFLIGHT_FILE" 'com.miui.voiceassist'
require_text "$PREFLIGHT_FILE" 'com.milink.service'
require_text "$PREFLIGHT_FILE" 'getenforce'
require_text "$PREFLIGHT_FILE" 'notification-subscriptions.json'
require_text "$PREFLIGHT_FILE" 'notification-forward-permissions.json'
require_text "$PREFLIGHT_FILE" 'sha256sum'

for script in "$PREFLIGHT_FILE" "$FORWARD_VERIFIER_FILE"; do
    for forbidden in \
        'adb pull' \
        'adb push' \
        'adb install' \
        'adb uninstall' \
        'adb reboot' \
        'adb root' \
        'adb remount' \
        'adb shell cat' \
        'adb shell cp' \
        'adb shell mv' \
        'adb shell rm' \
        'adb shell tee' \
        'adb shell dd' \
        'adb shell setprop' \
        'adb shell am force-stop' \
        'adb shell cmd notification' \
        'force-stop' \
        'pkill' \
        'kill ' \
        'rm -' \
        'unlink' \
        'truncate'
    do
        if grep -Fq -- "$forbidden" "$script"; then
            fail "${script#"$ROOT_DIR"/} contains forbidden command: $forbidden"
        fi
    done

    if grep -Eq '(^|[^&])>{1,2}[^&]' "$script"; then
        fail "${script#"$ROOT_DIR"/} must not redirect output to files"
    fi

    if grep -Eq 'notification-subscriptions\.json.*(>[^&]|tee|sed[[:space:]]+-i|perl[[:space:]]+-pi)' "$script"; then
        fail "${script#"$ROOT_DIR"/} must never write production rule JSON"
    fi
done

awk '
    /^[[:space:]]*adb[[:space:]]/ {
        if ($0 ~ /^[[:space:]]*adb[[:space:]]+get-state([[:space:]]|$)/) {
            next
        }
        if ($0 ~ /^[[:space:]]*adb[[:space:]]+shell[[:space:]]+(id[[:space:]]+-u|getprop|getenforce|pidof|dumpsys[[:space:]]+package|pm[[:space:]]+path|test[[:space:]]+-f|stat|sha256sum|command[[:space:]]+-v[[:space:]]+sha256sum)([[:space:]]|$)/) {
            next
        }
        printf "FAIL: unapproved adb command in preflight: %s\n", $0 > "/dev/stderr"
        bad = 1
    }
    END { exit bad }
' "$PREFLIGHT_FILE" || fail "preflight adb allowlist validation failed"

require_text "$FORWARD_VERIFIER_FILE" 'HyperLinkAcceptance event=forward_attempt'
require_text "$FORWARD_VERIFIER_FILE" 'forwardId'
require_text "$FORWARD_VERIFIER_FILE" 'attempt='
if grep -Fq 'adb ' "$FORWARD_VERIFIER_FILE"; then
    fail "forward-attempt verifier must consume stdin and not issue device commands"
fi

forward_pass="$(printf '%s\n' \
    'I/HyperLinkAcceptance: HyperLinkAcceptance event=forward_attempt forwardId=self-check-forward-a attempt=1' \
    | bash "$FORWARD_VERIFIER_FILE")" \
    || fail "forward-attempt verifier rejected the valid synthetic marker"
case "$forward_pass" in
    *'forwardAttempts=1 attempt=1 forwardIdSha256Prefix='*) ;;
    *) fail "forward-attempt verifier returned an unexpected successful result" ;;
esac

if printf '%s\n%s\n' \
    'I/HyperLinkAcceptance: HyperLinkAcceptance event=forward_attempt forwardId=self-check-forward-a attempt=1' \
    'I/HyperLinkAcceptance: HyperLinkAcceptance event=forward_attempt forwardId=self-check-forward-a attempt=1' \
    | bash "$FORWARD_VERIFIER_FILE" >/dev/null 2>&1
then
    fail "forward-attempt verifier accepted duplicate markers"
fi

if printf '%s\n' \
    'I/HyperLinkAcceptance: HyperLinkAcceptance event=forward_attempt forwardId=self-check-forward-a attempt=2' \
    | bash "$FORWARD_VERIFIER_FILE" >/dev/null 2>&1
then
    fail "forward-attempt verifier accepted attempt=2"
fi

printf 'PASS: acceptance kit structure and safety checks\n'
