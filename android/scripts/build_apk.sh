#!/usr/bin/env bash
# Builds the APK: the Java app (android/app, no Gradle), fexdroid's two native bridges and the runtime
# archive (android/scripts/build_runtime.py). Needs a JDK and the Android SDK's platform 35 and
# build-tools 35.0.0 ($ANDROID_SDK_ROOT, else out/android/sdk; android/scripts/fetch_sdk.sh).
# Signing: $GOW3_KEYSTORE (with $GOW3_KEYSTORE_PASS and $GOW3_KEY_ALIAS) for releases, else a local
# debug key in out/android/debug.keystore. Updates install only over an APK signed with the same key.
# Output: out/android/release/GoW3-<version>.apk and its .sha256.
#   build_apk.sh [--no-runtime]   (--no-runtime: the app alone, for UI work; it cannot start the game)
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
sdk=${ANDROID_SDK_ROOT:-$PWD/out/android/sdk}
tools=$sdk/build-tools/35.0.0
android_jar=$sdk/platforms/android-35/android.jar
[[ -x $tools/d8 && -f $android_jar ]] || { echo "No Android SDK in $sdk (android/scripts/fetch_sdk.sh)" >&2; exit 1; }
release=$PWD/out/android/release
out=$PWD/out/android/apk
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' android/app/AndroidManifest.xml)
rm -rf "$out" && mkdir -p "$out/classes" "$out/dex" "$out/res" "$out/assets" "$out/lib/arm64-v8a"
cp -r android/app/res/. "$out/res/"
# The port's own icon (launcher/gow3.png), when there is one, in place of the vector fallback.
if [[ -f launcher/gow3.png ]]; then rm -f "$out/res/drawable/icon.xml"; cp launcher/gow3.png "$out/res/drawable/icon.png"; fi
cp android/app/THIRD_PARTY_LICENSES.txt "$out/assets/"
if [[ ${1:-} != --no-runtime ]]; then
    [[ -f $release/runtime.tar.gz ]] || python3 android/scripts/build_runtime.py
    cp "$release/runtime.tar.gz" "$out/assets/runtime.payload"
    cp "$release/runtime.sha256" "$out/assets/runtime.sha256"
fi
for bridge in libfxdisplay.so libfxio.so; do
    [[ -f $release/$bridge ]] || { echo "Missing $release/$bridge (python3 android/scripts/build_runtime.py)" >&2; exit 1; }
    cp "$release/$bridge" "$out/lib/arm64-v8a/"
done
mapfile -t sources < <(find android/app/src -name '*.java' | sort)
javac --release 8 -Xlint:-options -classpath "$android_jar" -d "$out/classes" "${sources[@]}"
mapfile -t classes < <(find "$out/classes" -name '*.class' | sort)
"$tools/d8" --release --min-api 28 --lib "$android_jar" --output "$out/dex" "${classes[@]}"
# -0 payload: the runtime archive is stored, not compressed again (it is read once, at install).
"$tools/aapt" package -f -M android/app/AndroidManifest.xml -S "$out/res" -A "$out/assets" \
    -I "$android_jar" -0 payload -F "$out/unsigned.apk"
python3 - "$out" <<'PY'
import sys, zipfile
from pathlib import Path
out = Path(sys.argv[1])
with zipfile.ZipFile(out / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted((out / 'dex').glob('*.dex')):
        z.write(p, p.name)
    for p in sorted((out / 'lib').rglob('*.so')):
        z.write(p, str(p.relative_to(out)))
PY
"$tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
if [[ -n ${GOW3_KEYSTORE:-} ]]; then
    keystore=$GOW3_KEYSTORE pass=${GOW3_KEYSTORE_PASS:?set GOW3_KEYSTORE_PASS} alias=${GOW3_KEY_ALIAS:-gow3}
else
    keystore=$PWD/out/android/debug.keystore pass=android alias=gow3
    [[ -f $keystore ]] || keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias gow3 -dname 'CN=God of War III Android local build' -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
fi
mkdir -p "$release"
apk=$release/GoW3-$version.apk
"$tools/apksigner" sign --ks "$keystore" --ks-pass "pass:$pass" --ks-key-alias "$alias" --out "$apk" "$out/aligned.apk"
"$tools/apksigner" verify "$apk"
(cd "$release" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
echo "Built $apk ($(du -h "$apk" | cut -f1))"
