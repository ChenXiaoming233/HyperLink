#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB_BIN="${ADB_BIN:-adb}"
APP_PACKAGE='io.github.chenxiaoming233.hyperlink'
TEST_PACKAGE="$APP_PACKAGE.test"
TEST_RUNNER="$TEST_PACKAGE/io.github.chenxiaoming233.hyperlink.RuntimeProbeInstrumentation"
VOICE_ASSIST_PACKAGE='com.miui.voiceassist'
MILINK_PACKAGE='com.milink.service'
MILINK_ACTIVITY='com.miui.circulate.world.ui.connectivitysettings.ConnectivityActivity'
PROVIDER_AUTHORITY='com.miui.voiceassist.contentprovider.LinkContentProvider'
PROBE_METHOD='hyperlink.notificationFlow.probe.v1'
RULE_FILE='/data/user/0/com.miui.voiceassist/files/notification-subscriptions.json'
PERMISSION_FILE='/data/user/0/com.miui.voiceassist/files/notification-forward-permissions.json'

adb_cmd() {
    "$ADB_BIN" "$@"
}

fail() {
    printf 'BLOCKED: %s\n' "$*" >&2
    exit 1
}

shell() {
    adb_cmd shell "$@"
}

package_uid() {
    shell cmd package list packages -U \
        | tr -d '\r' \
        | awk -v package_name="$1" '
            $1 == "package:" package_name { uid = $2 }
            END {
                sub("uid:", "", uid)
                if (uid != "") {
                    print uid
                }
            }
        '
}

logcat_since_start() {
    adb_cmd logcat -d -v brief -T "$LOG_START"
}

contains_text() {
    grep -Fq -- "$2" <<< "$1"
}

file_hash() {
    shell sha256sum "$1" | tr -d '\r' | awk '{ print $1 }'
}

probe_provider() {
    adb_cmd shell am instrument -w -r \
        -e protocolVersion 1 \
        "$TEST_RUNNER"
}

wait_for_listener() {
    local deadline=$((SECONDS + 20))
    while [ "$SECONDS" -lt "$deadline" ]; do
        local listener_log
        listener_log="$(logcat_since_start || true)"
        if contains_text "$listener_log" \
            'HyperLinkProbe target=voiceassist event=listener_ready'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

[ "${HYPERLINK_ACCEPTANCE_DEVICE_OK:-}" = '1' ] \
    || fail 'set HYPERLINK_ACCEPTANCE_DEVICE_OK=1 after confirming the authorized device'
[ -n "${ANDROID_SERIAL:-}" ] || fail 'ANDROID_SERIAL is required'

DEVICE_LIST="$(adb_cmd devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
[ "$(printf '%s\n' "$DEVICE_LIST" | sed '/^$/d' | wc -l)" -eq 1 ] \
    || fail 'exactly one online adb device is required'
[ "$DEVICE_LIST" = "$ANDROID_SERIAL" ] \
    || fail 'ANDROID_SERIAL does not match the only online device'
[ "$(shell id -u)" = '0' ] || fail 'adbd must provide a root shell'
[ "$(shell getenforce)" = 'Enforcing' ] || fail 'SELinux must be Enforcing'

shell pm path "$APP_PACKAGE" >/dev/null 2>&1 \
    || fail "$APP_PACKAGE is not installed"
shell pm path "$TEST_PACKAGE" >/dev/null 2>&1 \
    || fail "$TEST_PACKAGE is not installed"
shell pm path "$VOICE_ASSIST_PACKAGE" >/dev/null 2>&1 \
    || fail "$VOICE_ASSIST_PACKAGE is not installed"
shell pm path "$MILINK_PACKAGE" >/dev/null 2>&1 \
    || fail "$MILINK_PACKAGE is not installed"

LOG_START="$(adb_cmd shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
printf 'PF.selinux=Enforcing\n'
printf 'PF.app.uid=%s\n' "$(package_uid "$APP_PACKAGE")"
printf 'PF.voiceassist.uid=%s\n' "$(package_uid "$VOICE_ASSIST_PACKAGE")"
printf 'PF.milink.uid=%s\n' "$(package_uid "$MILINK_PACKAGE")"

RULE_HASH_BEFORE="$(file_hash "$RULE_FILE")"
PERMISSION_HASH_BEFORE="$(file_hash "$PERMISSION_FILE")"
[ "${#RULE_HASH_BEFORE}" -eq 64 ] || fail 'rule file hash is unavailable'
[ "${#PERMISSION_HASH_BEFORE}" -eq 64 ] || fail 'permission file hash is unavailable'
printf 'PF.ruleHash=%s\n' "$RULE_HASH_BEFORE"
printf 'PF.permissionHash=%s\n' "$PERMISSION_HASH_BEFORE"

shell am force-stop "$MILINK_PACKAGE"
shell am start -W \
    -a miui.intent.action.MIRROR_SETTING \
    -n "$MILINK_PACKAGE/$MILINK_ACTIVITY" >/dev/null
sleep 2

MILINK_LOG="$(
    logcat_since_start \
        | grep 'HyperLinkProbe target=milink' \
        | tail -20 \
        || true
)"
printf '%s\n' "$MILINK_LOG"
contains_text "$MILINK_LOG" 'event=hook_installed'
contains_text "$MILINK_LOG" 'event=entry_injected'
ACTIVITY_DUMP="$(shell dumpsys activity activities)"
contains_text "$ACTIVITY_DUMP" "$MILINK_ACTIVITY"
printf 'MILINK_ENTRY=PASS\n'

VOICEASSIST_PID_BEFORE="$(shell pidof "$VOICE_ASSIST_PACKAGE" || true)"
shell am force-stop "$VOICE_ASSIST_PACKAGE"
[ -z "$(shell pidof "$VOICE_ASSIST_PACKAGE" || true)" ] \
    || fail 'VoiceAssist process did not stop'

COLD_RESULT="$(probe_provider || true)"
printf 'PROVIDER.cold=%s\n' "$COLD_RESULT"
if contains_text "$COLD_RESULT" 'success=false'; then
    contains_text "$COLD_RESULT" 'errorCode=SERVICE_UNAVAILABLE'
    printf 'PROVIDER_COLD_MODE=FAIL_CLOSED\n'
else
    contains_text "$COLD_RESULT" 'success=true'
    contains_text "$COLD_RESULT" 'storeReady=true'
    printf 'PROVIDER_COLD_MODE=LISTENER_ALREADY_READY\n'
fi

shell monkey -p "$VOICE_ASSIST_PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
wait_for_listener || fail 'VoiceAssist notification listener did not become ready'

VOICEASSIST_PID_AFTER="$(shell pidof "$VOICE_ASSIST_PACKAGE" || true)"
[ -n "$VOICEASSIST_PID_AFTER" ] || fail 'VoiceAssist process did not restart'
[ "$VOICEASSIST_PID_AFTER" != "$VOICEASSIST_PID_BEFORE" ] \
    || fail 'VoiceAssist PID did not change after restart'

READY_RESULT="$(probe_provider || true)"
printf 'PROVIDER.ready=%s\n' "$READY_RESULT"
contains_text "$READY_RESULT" 'success=true'
contains_text "$READY_RESULT" 'storeReady=true'
contains_text "$READY_RESULT" 'ruleStoreReadable=true'
contains_text "$READY_RESULT" 'deviceInfoReadable=true'
contains_text "$READY_RESULT" 'notificationListenerAccess=true'

RULE_HASH_AFTER="$(file_hash "$RULE_FILE")"
PERMISSION_HASH_AFTER="$(file_hash "$PERMISSION_FILE")"
[ "$RULE_HASH_AFTER" = "$RULE_HASH_BEFORE" ] || fail 'rule file changed during probe'
[ "$PERMISSION_HASH_AFTER" = "$PERMISSION_HASH_BEFORE" ] \
    || fail 'permission file changed during probe'
printf 'POST.ruleHash=%s\n' "$RULE_HASH_AFTER"
printf 'POST.permissionHash=%s\n' "$PERMISSION_HASH_AFTER"
printf 'PROVIDER_COLD_START=PASS\n'
printf 'PROVIDER_AUTHENTICATED_RPC=PASS\n'
printf 'PROCESS_RESTART=PASS\n'
printf 'STATE_UNCHANGED=PASS\n'
printf 'HOOK_TARGET_CONFIRMATION=PASS\n'
