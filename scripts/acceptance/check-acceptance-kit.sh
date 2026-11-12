#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MATRIX_FILE="$ROOT_DIR/docs/acceptance/matrix.md"
EVIDENCE_FILE="$ROOT_DIR/docs/acceptance/evidence-template.md"
OPERATIONS_FILE="$ROOT_DIR/docs/acceptance/operations.md"
README_FILE="$ROOT_DIR/docs/acceptance/README.md"
PREFLIGHT_FILE="$ROOT_DIR/scripts/acceptance/preflight-device.sh"
FORWARD_VERIFIER_FILE="$ROOT_DIR/scripts/acceptance/verify-forward-attempt.sh"
BEHAVIOR_TEST_FILE="$ROOT_DIR/scripts/acceptance/test-acceptance-kit.sh"
FAKE_ADB_FILE="$ROOT_DIR/scripts/acceptance/test-fixtures/fake-adb"

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
require_file "$BEHAVIOR_TEST_FILE"
require_file "$FAKE_ADB_FILE"

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
    'attempt=1' \
    '暂停→恢复' \
    '必须保存成功' \
    '从未授权' \
    '撤销' \
    'am force-stop com.miui.voiceassist' \
    '旧 PID' \
    '新 PID' \
    '当前 run'
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
    '^- 签名验证状态：`<[^>]+>`' \
    '^- 证据时间：`<[^>]+>`' \
    '^- 日志归档：`<[^>]+>`' \
    '^- runId SHA-256 前缀：`<[^>]+>`' \
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
    '回滚' \
    'SIGNATURE_VERIFICATION=VERIFIED' \
    'SIGNATURE_VERIFICATION=UNVERIFIED' \
    '--run-id' \
    '--since' \
    '--forward-id' \
    '按标记出现次数' \
    '人工审批' \
    '必须同时' \
    '不得先按 `--forward-id` 过滤' \
    'forward_attempted' \
    '一旦 `runId == expected`' \
    'timestamp 非数字或早于 `--since`' \
    'expected-run 的旧 timestamp'
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
require_text "$PREFLIGHT_FILE" 'ADB_BIN="${ADB_BIN:-adb}"'
require_text "$PREFLIGHT_FILE" 'adb_cmd devices'
require_text "$PREFLIGHT_FILE" 'exactly one online adb device is required'
require_text "$PREFLIGHT_FILE" 'ANDROID_SERIAL does not match the online device'
require_text "$PREFLIGHT_FILE" 'print_package_pid'
require_text "$PREFLIGHT_FILE" 'process is unavailable'
require_text "$PREFLIGHT_FILE" 'certificate_hex_from_metadata'
require_text "$PREFLIGHT_FILE" 'verify_package_signature'
require_text "$PREFLIGHT_FILE" 'SIGNATURE_VERIFICATION=UNVERIFIED'
require_text "$PREFLIGHT_FILE" 'SIGNATURE_VERIFICATION=VERIFIED'
require_text "$PREFLIGHT_FILE" 'read_field_from_dump'
require_text "$PREFLIGHT_FILE" 'read_signature_metadata'

for script in "$PREFLIGHT_FILE" "$FORWARD_VERIFIER_FILE" "$FAKE_ADB_FILE"; do
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

    if grep -Eq '(^|[^&])>{1,2}([^&[:space:]=]|[[:space:]]+[^0-9&[:space:]=])' "$script"; then
        fail "${script#"$ROOT_DIR"/} must not redirect output to files"
    fi

    if grep -Eq 'notification-subscriptions\.json.*(>[^&]|tee|sed[[:space:]]+-i|perl[[:space:]]+-pi)' "$script"; then
        fail "${script#"$ROOT_DIR"/} must never write production rule JSON"
    fi
done

if grep -Eq '^[[:space:]]*adb[[:space:]]' "$PREFLIGHT_FILE"; then
    fail "preflight must invoke adb only through the injectable adb_cmd wrapper"
fi

awk '
    /^[[:space:]]*adb_cmd[[:space:]]/ {
        if ($0 ~ /^[[:space:]]*adb_cmd[[:space:]]+(devices|get-state)([[:space:]]|$)/) {
            next
        }
        if ($0 ~ /^[[:space:]]*adb_cmd[[:space:]]+shell[[:space:]]+(id[[:space:]]+-u|getprop([[:space:]]|$)|getenforce|pidof|dumpsys[[:space:]]+package|pm[[:space:]]+path|test[[:space:]]+-f|stat|sha256sum|command[[:space:]]+-v[[:space:]]+sha256sum)([[:space:]]|$)/) {
            next
        }
        printf "FAIL: unapproved adb command in preflight: %s\n", $0 > "/dev/stderr"
        bad = 1
    }
    END { exit bad }
' "$PREFLIGHT_FILE" || fail "preflight adb allowlist validation failed"

require_text "$FORWARD_VERIFIER_FILE" "MARKER='forward_attempt'"
require_text "$FORWARD_VERIFIER_FILE" 'forwardId'
require_text "$FORWARD_VERIFIER_FILE" 'attempt='
require_text "$FORWARD_VERIFIER_FILE" '--run-id'
require_text "$FORWARD_VERIFIER_FILE" '--since'
require_text "$FORWARD_VERIFIER_FILE" '--forward-id'
require_text "$FORWARD_VERIFIER_FILE" 'timestampEpoch'
require_text "$FORWARD_VERIFIER_FILE" 'tokens[token_index] != marker'
require_text "$FORWARD_VERIFIER_FILE" 'selected_forward != expected_forward_id'
require_text "$FORWARD_VERIFIER_FILE" 'matches_expected_run'
require_text "$FORWARD_VERIFIER_FILE" 'if (!matches_expected_run)'
require_text "$FORWARD_VERIFIER_FILE" 'timestamp + 0 < since + 0'
require_text "$FORWARD_VERIFIER_FILE" '--run-id is required'
require_text "$FORWARD_VERIFIER_FILE" '--since is required'
require_text "$FORWARD_VERIFIER_FILE" '--forward-id is required'
if grep -Fq 'adb ' "$FORWARD_VERIFIER_FILE"; then
    fail "forward-attempt verifier must consume stdin and not issue device commands"
fi

for text in \
    'another forwardId in the current run' \
    'old run with the same forwardId' \
    'different run with missing fields' \
    'marker without runId' \
    'expected-run marker with missing timestamp' \
    'expected-run marker with missing forwardId' \
    'expected-run marker with missing attempt' \
    'expected-run marker with non-numeric timestamp' \
    'expected-run marker before the time window' \
    'counted lines instead of marker occurrences' \
    'duplicate forwardId tokens' \
    'multiple attempt tokens' \
    'attempt=1 plus attempt=2' \
    'attempted token' \
    'missing --run-id' \
    'missing --since' \
    'missing --forward-id' \
    'empty --run-id' \
    'empty --since' \
    'empty --forward-id' \
    'pid-missing-voiceassist' \
    'pid-missing-milink' \
    'signature-unverified' \
    'multiple-devices' \
    'serial-mismatch' \
    'offline-device' \
    'large-dumpsys'
do
    require_text "$BEHAVIOR_TEST_FILE" "$text"
done

require_text "$BEHAVIOR_TEST_FILE" 'expect_verifier_failure'
require_text "$BEHAVIOR_TEST_FILE" 'if run_verifier "$input" "$@"'

require_text "$FAKE_ADB_FILE" 'certificateHex=01020304'
require_text "$FAKE_ADB_FILE" 'multiple-devices'
require_text "$FAKE_ADB_FILE" 'offline-device'
require_text "$FAKE_ADB_FILE" 'large-dumpsys'

bash "$BEHAVIOR_TEST_FILE"

printf 'PASS: acceptance kit structure and safety checks\n'
