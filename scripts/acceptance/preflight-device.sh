#!/usr/bin/env bash
set -euo pipefail

VOICE_ASSIST_PACKAGE='com.miui.voiceassist'
MILINK_PACKAGE='com.milink.service'
RULE_FILE='/data/user/0/com.miui.voiceassist/files/notification-subscriptions.json'
PERMISSION_FILE='/data/user/0/com.miui.voiceassist/files/notification-forward-permissions.json'

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

require_sha256() {
    local name="$1"
    local value="${2:-}"
    [ "${#value}" -eq 64 ] || fail "$name must be a 64-character SHA-256 value"
    case "$value" in
        *[!0-9A-Fa-f]*) fail "$name must contain only hexadecimal characters" ;;
    esac
}

read_package_field() {
    local package_name="$1"
    local expression="$2"
    adb shell dumpsys package "$package_name" \
        | tr -d '\r' \
        | awk -v expression="$expression" '
            $0 ~ expression {
                value = $0
                sub(".*" expression "=", "", value)
                sub("[[:space:]].*", "", value)
                print value
                exit
            }
        '
}

print_package_version() {
    local package_name="$1"
    local label="$2"
    local version_name
    local version_code
    version_name="$(read_package_field "$package_name" 'versionName')"
    version_code="$(read_package_field "$package_name" 'versionCode')"
    [ -n "$version_name" ] || fail "$package_name versionName is unavailable"
    [ -n "$version_code" ] || fail "$package_name versionCode is unavailable"
    printf '%s.versionName=%s\n' "$label" "$version_name"
    printf '%s.versionCode=%s\n' "$label" "$version_code"
}

print_package_uid() {
    local package_name="$1"
    local label="$2"
    local uid
    uid="$(read_package_field "$package_name" 'userId')"
    [ -n "$uid" ] || fail "$package_name UID is unavailable"
    printf '%s.uid=%s\n' "$label" "$uid"
}

print_signature_metadata() {
    local package_name="$1"
    local label="$2"
    local metadata
    metadata="$(
        adb shell dumpsys package "$package_name" \
            | tr -d '\r' \
            | awk '
                /signatures=|signingDetails/ {
                    print $0
                    count++
                    if (count == 4) {
                        exit
                    }
                }
            '
    )"
    [ -n "$metadata" ] || fail "$package_name signature metadata is unavailable"
    printf '%s\n' "$metadata" | sed "s/^/$label.signatureMetadata=/"
}

print_apk_hashes() {
    local package_name="$1"
    local label="$2"
    local paths
    paths="$(adb shell pm path "$package_name" | tr -d '\r')"
    [ -n "$paths" ] || fail "$package_name APK paths are unavailable"
    printf '%s\n' "$paths" | while IFS= read -r entry; do
        case "$entry" in
            package:*) ;;
            *) fail "unexpected APK path entry for $package_name" ;;
        esac
        local apk_path="${entry#package:}"
        printf '%s.apkPath=%s\n' "$label" "$apk_path"
        adb shell sha256sum "$apk_path" \
            | awk -v label="$label" '{ print label ".apkSha256=" $1 }'
    done
}

print_private_file_evidence() {
    local path="$1"
    local label="$2"
    adb shell test -f "$path" || fail "$label file is unavailable"
    printf '%s.path=%s\n' "$label" "$path"
    adb shell stat -c 'size=%s mtimeEpoch=%Y mode=%a uid=%u gid=%g' "$path" \
        | sed "s/^/$label.stat=/"
    adb shell sha256sum "$path" | awk -v label="$label" '{ print label ".sha256=" $1 }'
}

[ "${HYPERLINK_ACCEPTANCE_DEVICE_OK:-}" = '1' ] \
    || fail 'set HYPERLINK_ACCEPTANCE_DEVICE_OK=1 only when device acceptance is authorized'
[ -n "${ANDROID_SERIAL:-}" ] || fail 'ANDROID_SERIAL is required to prevent targeting the wrong device'
command -v adb
adb get-state | tr -d '\r' | grep -Fx 'device'

[ "$(adb shell id -u | tr -d '\r')" = '0' ] || fail 'root adb shell is required for read-only private file metadata'
[ "$(adb shell getenforce | tr -d '\r')" = 'Enforcing' ] || fail 'SELinux must be Enforcing'
adb shell command -v sha256sum | tr -d '\r' | grep -Eq '(^|/)sha256sum$'

printf 'device.model=%s\n' "$(adb shell getprop ro.product.model | tr -d '\r')"
printf 'device.release=%s\n' "$(adb shell getprop ro.build.version.release | tr -d '\r')"
printf 'device.sdk=%s\n' "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
printf 'device.fingerprint=%s\n' "$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
printf 'selinux.mode=%s\n' "$(adb shell getenforce | tr -d '\r')"

for package_name in "$VOICE_ASSIST_PACKAGE" "$MILINK_PACKAGE"; do
    adb shell dumpsys package "$package_name" | grep -Fq "Package [$package_name]" \
        || fail "$package_name is not installed"
done

print_package_version "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_package_version "$MILINK_PACKAGE" 'milink'
print_package_uid "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_package_uid "$MILINK_PACKAGE" 'milink'
print_signature_metadata "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_signature_metadata "$MILINK_PACKAGE" 'milink'
print_apk_hashes "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_apk_hashes "$MILINK_PACKAGE" 'milink'

printf 'voiceassist.pid=%s\n' "$(adb shell pidof "$VOICE_ASSIST_PACKAGE" | tr -d '\r')"
printf 'milink.pid=%s\n' "$(adb shell pidof "$MILINK_PACKAGE" | tr -d '\r')"

require_sha256 'VOICEASSIST_SIGNER_SHA256' "${VOICEASSIST_SIGNER_SHA256:-}"
require_sha256 'MILINK_SIGNER_SHA256' "${MILINK_SIGNER_SHA256:-}"
printf 'voiceassist.signingCertificateSha256.source=release-approval-record\n'
printf 'voiceassist.signingCertificateSha256=%s\n' "$VOICEASSIST_SIGNER_SHA256"
printf 'milink.signingCertificateSha256.source=release-approval-record\n'
printf 'milink.signingCertificateSha256=%s\n' "$MILINK_SIGNER_SHA256"

print_private_file_evidence "$RULE_FILE" 'rules'
print_private_file_evidence "$PERMISSION_FILE" 'permissions'

printf 'PASS: read-only preflight metadata and hashes collected\n'
