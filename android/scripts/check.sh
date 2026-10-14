#!/usr/bin/env bash
# Fast check after an upstream sync (minutes, not the full build): the runtime and loader (src/)
# compile with -Werror for ARM64 (Android) and x86-64 (the PC build), the Android app's Java
# compiles, and the Python scripts parse. Fetches the Debian sysroots and the SDK when missing.
#   check.sh [--gpu]   --gpu: also builds the ARM64 GPU library and FEXCore (android/scripts/build_arm64.sh)
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
for arch in arm64 amd64; do
    [[ -f out/android/$arch-sysroot/usr/include/stdio.h ]] || python3 android/scripts/fetch_sysroot.py --arch "$arch"
done
[[ -f gpu/third_party/imgui/imgui.h && -f third_party/LibAtrac9/C/src/atrac9.h ]] || git submodule update --init --recursive
failed=0
for pair in aarch64:arm64 x86_64:amd64; do
    triple=${pair%%:*}-linux-gnu sysroot=$PWD/out/android/${pair##*:}-sysroot
    export PKG_CONFIG_SYSROOT_DIR=$sysroot
    export PKG_CONFIG_LIBDIR=$sysroot/usr/lib/$triple/pkgconfig:$sysroot/usr/share/pkgconfig
    read -r -a includes <<< "$(pkg-config --cflags vulkan sdl3 libavcodec libavutil libswscale)"
    for source in src/probe.c src/runtime*.c src/cpu_check.c src/isa_rewrite.c src/isa_emulate.c src/vulkan_smoke.c; do
        clang --target="$triple" --sysroot="$sysroot" -std=c11 -O2 -Wall -Wextra -Werror -pthread \
            "${includes[@]}" -I. -Isrc -c "$source" -o /dev/null || { echo "FAILED ($triple): $source"; failed=1; }
    done
    echo "Runtime: $triple checked"
done
sdk=${ANDROID_SDK_ROOT:-$PWD/out/android/sdk}
[[ -f $sdk/platforms/android-35/android.jar ]] || bash android/scripts/fetch_sdk.sh
classes=$(mktemp -d)
javac --release 8 -Xlint:-options -nowarn -classpath "$sdk/platforms/android-35/android.jar" -d "$classes" \
    $(find android/app/src -name '*.java') || { echo 'FAILED: Android app (Java)'; failed=1; }
rm -rf "$classes"
echo 'Android app: Java checked'
python3 -m py_compile run.py scripts/*.py android/runtime/*.py android/scripts/*.py || failed=1
sh -n android/runtime/run-game.sh || failed=1
echo 'Scripts checked'
if [[ ${1:-} == --gpu ]]; then
    bash android/scripts/build_arm64.sh fex && bash android/scripts/build_arm64.sh gpu || failed=1
fi
if [[ $failed != 0 ]]; then echo 'Check failed.'; exit 1; fi
echo 'All checks passed.'
