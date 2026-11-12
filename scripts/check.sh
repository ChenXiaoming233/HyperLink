#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
bash -n scripts/check.sh scripts/acceptance/*.sh
