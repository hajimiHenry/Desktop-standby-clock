#!/usr/bin/env bash

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
cd "$repository_root"

release_version=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle)
if [[ -z "$release_version" ]]; then
    echo "Unable to read versionName from app/build.gradle." >&2
    exit 1
fi

user_home_dir=${HOME:?HOME is required}
export STANDBY_CLOCK_KEYSTORE=${STANDBY_CLOCK_KEYSTORE:-"$user_home_dir/.android/standby-clock-release.jks"}
export STANDBY_CLOCK_KEY_ALIAS=${STANDBY_CLOCK_KEY_ALIAS:-standby-clock}

if [[ -z "${STANDBY_CLOCK_STORE_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
    STANDBY_CLOCK_STORE_PASSWORD=$(security find-generic-password \
        -a standby-clock \
        -s com.henry.standbyclock.release-signing \
        -w)
    export STANDBY_CLOCK_STORE_PASSWORD
fi
export STANDBY_CLOCK_KEY_PASSWORD=${STANDBY_CLOCK_KEY_PASSWORD:-${STANDBY_CLOCK_STORE_PASSWORD:-}}

if [[ ! -f "$STANDBY_CLOCK_KEYSTORE" ]]; then
    echo "Release keystore not found: $STANDBY_CLOCK_KEYSTORE" >&2
    exit 1
fi
if [[ -z "${STANDBY_CLOCK_STORE_PASSWORD:-}" || -z "$STANDBY_CLOCK_KEY_PASSWORD" ]]; then
    echo "Release signing passwords are not configured." >&2
    exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    homebrew_jdk17=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    if [[ -x "$homebrew_jdk17/bin/java" ]]; then
        export JAVA_HOME=$homebrew_jdk17
    fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "JDK 17 was not found. Set JAVA_HOME before running this script." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleRelease

signed_apk=app/build/outputs/apk/release/app-release.apk
if [[ ! -f "$signed_apk" ]]; then
    echo "Signed release APK was not produced: $signed_apk" >&2
    exit 1
fi

android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$android_sdk" && -f local.properties ]]; then
    android_sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
fi
if [[ -z "$android_sdk" || ! -d "$android_sdk/build-tools" ]]; then
    echo "Android SDK build-tools were not found." >&2
    exit 1
fi
apksigner_path=$(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)
if [[ -z "$apksigner_path" ]]; then
    echo "apksigner was not found in the configured Android SDK." >&2
    exit 1
fi

"$apksigner_path" verify --verbose --print-certs "$signed_apk"

mkdir -p dist
release_name="Desktop-Standby-Clock-v${release_version}.apk"
install -m 0644 "$signed_apk" "dist/$release_name"
(
    cd dist
    shasum -a 256 "$release_name" > "$release_name.sha256"
)

echo "Release artifacts:"
echo "  dist/$release_name"
echo "  dist/$release_name.sha256"
