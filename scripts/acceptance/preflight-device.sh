#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
VOICE_ASSIST_PACKAGE='com.miui.voiceassist'
MILINK_PACKAGE='com.milink.service'
RULE_FILE='/data/user/0/com.miui.voiceassist/files/notification-subscriptions.json'
PERMISSION_FILE='/data/user/0/com.miui.voiceassist/files/notification-forward-permissions.json'

adb_cmd() {
    "$ADB_BIN" "$@"
}

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

read_field_from_dump() {
    local package_dump="$1"
    local expression="$2"
    printf '%s\n' "$package_dump" \
        | awk -v expression="$expression" '
            value == "" && $0 ~ expression {
                value = $0
                sub(".*" expression "=", "", value)
                sub("[[:space:]].*", "", value)
            }
            END { print value }
        '
}

print_package_version_from_dump() {
    local package_dump="$1"
    local package_name="$2"
    local label="$3"
    local version_name
    local version_code
    version_name="$(read_field_from_dump "$package_dump" 'versionName')"
    version_code="$(read_field_from_dump "$package_dump" 'versionCode')"
    [ -n "$version_name" ] || fail "$package_name versionName is unavailable"
    [ -n "$version_code" ] || fail "$package_name versionCode is unavailable"
    printf '%s.versionName=%s\n' "$label" "$version_name"
    printf '%s.versionCode=%s\n' "$label" "$version_code"
}

print_package_uid_from_dump() {
    local package_dump="$1"
    local package_name="$2"
    local label="$3"
    local uid
    uid="$(read_field_from_dump "$package_dump" 'userId')"
    [ -n "$uid" ] || fail "$package_name UID is unavailable"
    printf '%s.uid=%s\n' "$label" "$uid"
}

read_signature_metadata() {
    local package_dump="$1"
    printf '%s\n' "$package_dump" \
        | awk '/signatures=|signingDetails|certificateHex=|signingCertificate=/ { print }'
}

print_signature_metadata() {
    local package_name="$1"
    local label="$2"
    local metadata="$3"
    [ -n "$metadata" ] || fail "$package_name signature metadata is unavailable"
    printf '%s\n' "$metadata" | sed "s/^/$label.signatureMetadata=/"
}

certificate_hex_from_metadata() {
    local metadata="$1"
    printf '%s\n' "$metadata" \
        | awk '
            hex == "" && match($0, /(certificateHex|signingCertificate)=[0-9A-Fa-f]+/) {
                hex = substr($0, RSTART, RLENGTH)
                sub(/^.*=/, "", hex)
            }
            END { print hex }
        '
}

verify_package_signature() {
    local package_name="$1"
    local label="$2"
    local expected_sha="$3"
    local metadata="$4"
    local certificate_hex
    local actual_sha
    local expected_lower
    local actual_lower
    local xxd_path

    certificate_hex="$(certificate_hex_from_metadata "$metadata")"
    if [ -z "$certificate_hex" ] || [ $(( ${#certificate_hex} % 2 )) -ne 0 ]; then
        printf '%s SIGNATURE_VERIFICATION=UNVERIFIED\n' "$label"
        return 1
    fi
    case "$certificate_hex" in
        *[!0-9A-Fa-f]*)
            printf '%s SIGNATURE_VERIFICATION=UNVERIFIED\n' "$label"
            return 1
            ;;
    esac
    xxd_path="$(command -v xxd || true)"
    if [ -z "$xxd_path" ]; then
        printf '%s SIGNATURE_VERIFICATION=UNVERIFIED\n' "$label"
        return 1
    fi

    actual_sha="$(
        printf '%s' "$certificate_hex" \
            | xxd -r -p \
            | sha256sum \
            | cut -d ' ' -f 1
    )"
    expected_lower="$(printf '%s' "$expected_sha" | tr '[:upper:]' '[:lower:]')"
    actual_lower="$(printf '%s' "$actual_sha" | tr '[:upper:]' '[:lower:]')"
    printf '%s.signingCertificateSha256.actual=%s\n' "$label" "$actual_lower"
    if [ "$actual_lower" != "$expected_lower" ]; then
        printf '%s SIGNATURE_VERIFICATION=MISMATCH\n' "$label"
        return 1
    fi
    printf '%s SIGNATURE_VERIFICATION=VERIFIED\n' "$label"
}

print_apk_hashes() {
    local package_name="$1"
    local label="$2"
    local paths
    paths="$(adb_cmd shell pm path "$package_name" | tr -d '\r')"
    [ -n "$paths" ] || fail "$package_name APK paths are unavailable"
    printf '%s\n' "$paths" | while IFS= read -r entry; do
        case "$entry" in
            package:*) ;;
            *) fail "unexpected APK path entry for $package_name" ;;
        esac
        local apk_path="${entry#package:}"
        printf '%s.apkPath=%s\n' "$label" "$apk_path"
        adb_cmd shell sha256sum "$apk_path" \
            | awk -v label="$label" '{ print label ".apkSha256=" $1 }'
    done
}

print_private_file_evidence() {
    local path="$1"
    local label="$2"
    adb_cmd shell test -f "$path" || fail "$label file is unavailable"
    printf '%s.path=%s\n' "$label" "$path"
    adb_cmd shell stat -c 'size=%s mtimeEpoch=%Y mode=%a uid=%u gid=%g' "$path" \
        | sed "s/^/$label.stat=/"
    adb_cmd shell sha256sum "$path" | awk -v label="$label" '{ print label ".sha256=" $1 }'
}

print_package_pid() {
    local package_name="$1"
    local label="$2"
    local pid
    if ! pid="$(adb_cmd shell pidof "$package_name" | tr -d '\r')"; then
        fail "$label process is unavailable"
    fi
    [ -n "$pid" ] || fail "$label process is unavailable"
    printf '%s.pid=%s\n' "$label" "$pid"
}

read_device_property() {
    local properties_dump="$1"
    local property_name="$2"
    printf '%s\n' "$properties_dump" \
        | awk -v property_name="$property_name" '
            value == "" && $0 ~ "^\\[" property_name "\\]: \\[" {
                value = $0
                sub("^.*\\]: \\[", "", value)
                sub("\\].*$", "", value)
            }
            END { print value }
        '
}

print_device_property_from_dump() {
    local properties_dump="$1"
    local property_name="$2"
    local label="$3"
    local value
    value="$(read_device_property "$properties_dump" "$property_name")"
    [ -n "$value" ] || fail "$property_name is empty"
    printf '%s=%s\n' "$label" "$value"
}

[ "${HYPERLINK_ACCEPTANCE_DEVICE_OK:-}" = '1' ] \
    || fail 'set HYPERLINK_ACCEPTANCE_DEVICE_OK=1 only when device acceptance is authorized'
[ -n "${ANDROID_SERIAL:-}" ] || fail 'ANDROID_SERIAL is required to prevent targeting the wrong device'
require_sha256 'VOICEASSIST_SIGNER_SHA256' "${VOICEASSIST_SIGNER_SHA256:-}"
require_sha256 'MILINK_SIGNER_SHA256' "${MILINK_SIGNER_SHA256:-}"
adb_bin_path="$(command -v "$ADB_BIN" || true)"
[ -n "$adb_bin_path" ] || fail 'adb command is unavailable'
printf 'adb.binary.resolved=true\n'

devices_output="$(adb_cmd devices | tr -d '\r')"
device_records="$(
    printf '%s\n' "$devices_output" \
        | awk 'NR > 1 && NF >= 2 { print $1 "\t" $2 }'
)"
record_count="$(printf '%s\n' "$device_records" | awk 'NF { count++ } END { print count + 0 }')"
[ "$record_count" -eq 1 ] || fail 'exactly one online adb device is required; offline or multiple devices are rejected'
device_record="$(printf '%s\n' "$device_records" | awk 'NF && record == "" { record = $0 } END { print record }')"
online_serial="${device_record%%$'\t'*}"
online_state="${device_record#*$'\t'}"
[ "$online_state" = 'device' ] || fail 'exactly one online adb device is required; offline or unauthorized devices are rejected'
[ "$online_serial" = "$ANDROID_SERIAL" ] || fail 'ANDROID_SERIAL does not match the online device'

adb_state="$(adb_cmd get-state | tr -d '\r')"
[ "$adb_state" = 'device' ] || fail 'adb selected device is not online'
printf 'device.adbOnlineCount=1\n'
printf 'device.adbSerialMatch=true\n'

root_uid="$(adb_cmd shell id -u | tr -d '\r')"
[ "$root_uid" = '0' ] || fail 'root adb shell is required for read-only private file metadata'
selinux_mode="$(adb_cmd shell getenforce | tr -d '\r')"
[ "$selinux_mode" = 'Enforcing' ] || fail 'SELinux must be Enforcing'
sha256_path="$(adb_cmd shell command -v sha256sum | tr -d '\r')"
case "$sha256_path" in
    sha256sum|*/sha256sum) ;;
    *) fail 'sha256sum is unavailable on the device' ;;
esac

properties_dump="$(adb_cmd shell getprop | tr -d '\r')"
print_device_property_from_dump "$properties_dump" ro.product.model 'device.model'
print_device_property_from_dump "$properties_dump" ro.build.version.release 'device.release'
print_device_property_from_dump "$properties_dump" ro.build.version.sdk 'device.sdk'
print_device_property_from_dump "$properties_dump" ro.build.fingerprint 'device.fingerprint'
printf 'selinux.mode=%s\n' "$selinux_mode"

voiceassist_dump="$(adb_cmd shell dumpsys package "$VOICE_ASSIST_PACKAGE" | tr -d '\r')"
milink_dump="$(adb_cmd shell dumpsys package "$MILINK_PACKAGE" | tr -d '\r')"
case "$voiceassist_dump" in
    *"Package [$VOICE_ASSIST_PACKAGE]"*) ;;
    *) fail "$VOICE_ASSIST_PACKAGE is not installed" ;;
esac
case "$milink_dump" in
    *"Package [$MILINK_PACKAGE]"*) ;;
    *) fail "$MILINK_PACKAGE is not installed" ;;
esac

print_package_version_from_dump "$voiceassist_dump" "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_package_version_from_dump "$milink_dump" "$MILINK_PACKAGE" 'milink'
print_package_uid_from_dump "$voiceassist_dump" "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_package_uid_from_dump "$milink_dump" "$MILINK_PACKAGE" 'milink'

voiceassist_metadata="$(read_signature_metadata "$voiceassist_dump")"
milink_metadata="$(read_signature_metadata "$milink_dump")"
print_signature_metadata "$VOICE_ASSIST_PACKAGE" 'voiceassist' "$voiceassist_metadata"
print_signature_metadata "$MILINK_PACKAGE" 'milink' "$milink_metadata"

printf 'voiceassist.signingCertificateSha256.source=approved-reference\n'
printf 'voiceassist.signingCertificateSha256=%s\n' "$VOICEASSIST_SIGNER_SHA256"
printf 'milink.signingCertificateSha256.source=approved-reference\n'
printf 'milink.signingCertificateSha256=%s\n' "$MILINK_SIGNER_SHA256"

signature_ok=1
verify_package_signature "$VOICE_ASSIST_PACKAGE" 'voiceassist' \
    "$VOICEASSIST_SIGNER_SHA256" "$voiceassist_metadata" || signature_ok=0
verify_package_signature "$MILINK_PACKAGE" 'milink' \
    "$MILINK_SIGNER_SHA256" "$milink_metadata" || signature_ok=0
[ "$signature_ok" -eq 1 ] || fail 'signature verification did not pass; PASS is forbidden'

print_apk_hashes "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_apk_hashes "$MILINK_PACKAGE" 'milink'
print_package_pid "$VOICE_ASSIST_PACKAGE" 'voiceassist'
print_package_pid "$MILINK_PACKAGE" 'milink'

print_private_file_evidence "$RULE_FILE" 'rules'
print_private_file_evidence "$PERMISSION_FILE" 'permissions'

printf 'PASS: read-only preflight metadata and hashes collected\n'
