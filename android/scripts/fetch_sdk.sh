#!/usr/bin/env bash
# The Android SDK pieces build_apk.sh needs (platform 35, build-tools 35.0.0), into out/android/sdk.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
sdk=$PWD/out/android/sdk
if [[ ! -x $sdk/cmdline-tools/latest/bin/sdkmanager ]]; then
    mkdir -p "$sdk/cmdline-tools" out/android
    curl -fsSL -o out/android/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    echo '2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258  out/android/cmdline-tools.zip' | sha256sum -c -
    unzip -q -o out/android/cmdline-tools.zip -d "$sdk/cmdline-tools"
    mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
fi
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" 'platforms;android-35' 'build-tools;35.0.0' >/dev/null
echo "Android SDK: $sdk"
