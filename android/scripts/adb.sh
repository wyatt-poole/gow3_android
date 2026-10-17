#!/usr/bin/env bash
# Install, start and debug the app on a phone connected over ADB (USB, or Wireless debugging).
#   adb.sh install [file.apk]   install (keeps data) and start; without a file: the newest local
#                               build (out/android/release), else the newest APK built on GitHub
#                               (needs gh, signed in: gh auth login)
#   adb.sh start | stop         start or stop the app
#   adb.sh logs [dir]           copy the game log, the install log and the app's logcat to dir
#                               (default out/android/device-logs)
#   adb.sh shell                a shell in the app's data folder (run-as)
# ADB_SERIAL (or ANDROID_SERIAL) picks the device when several are connected.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
package=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' android/app/AndroidManifest.xml)
activity=$package/ro.cobrabm.fexdroid.MainActivity
export ANDROID_SERIAL=${ADB_SERIAL:-${ANDROID_SERIAL:-}}
[[ -n $ANDROID_SERIAL ]] || unset ANDROID_SERIAL
command -v adb >/dev/null || { echo 'adb not found: install Android platform-tools' >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo 'No device: connect it and allow USB debugging (adb devices)' >&2; exit 1; }

newest_apk() {
    local local_apk
    local_apk=$(ls -t out/android/release/GoW3-*.apk 2>/dev/null | head -n 1 || true)
    if [[ -n $local_apk ]]; then echo "$local_apk"; return; fi
    command -v gh >/dev/null || { echo 'No local APK and no gh to download one; pass the APK file' >&2; exit 1; }
    local run dir=out/android/downloaded
    run=$(gh run list --workflow android-build.yml --branch main --status success --limit 1 --json databaseId --jq '.[0].databaseId')
    [[ -n $run ]] || { echo 'No successful build on GitHub yet' >&2; exit 1; }
    rm -rf "$dir" && mkdir -p "$dir"
    echo "Downloading the APK of GitHub run $run" >&2
    gh run download "$run" --name gow3-android-apk --dir "$dir" >&2
    ls -t "$dir"/GoW3-*.apk | head -n 1
}

case ${1:-} in
    install)
        apk=${2:-$(newest_apk)}
        echo "Installing $apk"
        # -r keeps the app's data (runtime, saves). A different signing key needs an uninstall
        # first, which deletes the saves: back them up from the app's menu before.
        if ! adb install -r "$apk"; then
            echo 'Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE (another signing key),'
            echo 'back up your saves in the app, then: adb uninstall '"$package"' and install again.'
            exit 1
        fi
        adb shell am start -n "$activity" >/dev/null
        echo "Started $package. The first start unpacks the runtime (a few minutes)."
        ;;
    start) adb shell am start -n "$activity" ;;
    stop) adb shell am force-stop "$package" ;;
    logs)
        dir=${2:-out/android/device-logs}
        mkdir -p "$dir"
        adb exec-out run-as "$package" cat files/gow3-data/logs/android-game.log > "$dir/android-game.log" 2>/dev/null || true
        adb exec-out run-as "$package" cat files/gow3-data/install.log > "$dir/install.log" 2>/dev/null || true
        adb logcat -d -v time GoW3:V GoW3-X11:V AndroidRuntime:E DEBUG:V '*:S' > "$dir/logcat.txt"
        adb shell getprop ro.product.model > "$dir/device.txt"
        ls -l "$dir"
        ;;
    shell) adb shell run-as "$package" sh -c "cd files && exec sh" ;;
    *) sed -n '2,10p' "$0"; exit 2 ;;
esac
