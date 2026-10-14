#!/usr/bin/env bash
# Runs the ARM64 build's tests on an x86-64 host under qemu-user (apt install qemu-user), after
# android/scripts/build_arm64.sh: the FEXCore crossings (tests/test_guest_cpu.c), upstream's loader
# tests (tests/test_probe.py: synthetic x86-64 guest images through gow3-probe and FEXCore) and the
# runtime unit tests that do not need an x86-64 host (pad, semaphores, content).
# tests/test_runtime.c is left out: it calls host functions as if they were guest code, which only
# an x86-64 host can do.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
command -v qemu-aarch64 >/dev/null || { echo 'Needs qemu-aarch64 (apt install qemu-user)' >&2; exit 1; }
sysroot=$PWD/out/android/arm64-sysroot
[[ -f out/arm64/gow3-probe && -f out/arm64/fex/libgow3cpu.so ]] || { echo 'Run android/scripts/build_arm64.sh first' >&2; exit 1; }
libs=$PWD/out/arm64/fex:$PWD/out/arm64/gpu:$sysroot/usr/lib/aarch64-linux-gnu:$sysroot/usr/lib/aarch64-linux-gnu/pulseaudio
run=(qemu-aarch64 -E "LD_LIBRARY_PATH=$libs" "$sysroot/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
cc=(clang --target=aarch64-linux-gnu --sysroot="$sysroot" -fuse-ld=lld -std=c11 -O2 -g -Wall -Wextra -Werror -pthread -I. -Isrc)
export PKG_CONFIG_SYSROOT_DIR=$sysroot
export PKG_CONFIG_LIBDIR=$sysroot/usr/lib/aarch64-linux-gnu/pkgconfig:$sysroot/usr/share/pkgconfig
vulkan_headers=$(ls -d out/android/vulkan-headers-* | tail -n 1)
read -r -a includes <<< "-isystem $vulkan_headers/include $(pkg-config --cflags vulkan sdl3 libavcodec libavutil libswscale)"
read -r -a libraries <<< "$(pkg-config --libs vulkan sdl3 libavcodec libavutil libswscale)"
tests=out/arm64/tests
mkdir -p "$tests"
failed=0
"${cc[@]}" tests/test_guest_cpu.c -Lout/arm64/fex -lgow3cpu -o "$tests/guest-cpu-test"
"${run[@]}" "$tests/guest-cpu-test" || failed=1
"${cc[@]}" "${includes[@]}" tests/test_pad.c "${libraries[@]}" -o "$tests/pad-test"
"${run[@]}" "$tests/pad-test" | tail -n 1 || failed=1
"${cc[@]}" "${includes[@]}" tests/test_sema.c src/runtime*.c out/arm64/libatrac9.a -lm -Lout/arm64/gpu -lgow3gpu \
    -Lout/arm64/fex -lgow3cpu -rdynamic "${libraries[@]}" -o "$tests/sema-test"
"${run[@]}" "$tests/sema-test" | tail -n 1 || failed=1
"${cc[@]}" -D_GNU_SOURCE tests/test_content.c src/runtime_content.c -o "$tests/content-test"
"${run[@]}" "$tests/content-test" | tail -n 1 || failed=1
# Upstream's loader tests run out/gow3-probe: a wrapper there for the time of the tests, unless a
# PC build owns that path.
wrapper=out/gow3-probe
if [[ -e $wrapper && ! -f out/gow3-probe.qemu ]]; then
    echo "Skipping tests/test_probe.py: $wrapper is a PC build" >&2
else
    printf '#!/bin/sh\nexec %s %s/out/arm64/gow3-probe "$@"\n' "$(printf '%q ' "${run[@]}")" "$PWD" > "$wrapper"
    chmod +x "$wrapper" && touch out/gow3-probe.qemu
    python3 -m unittest discover -s tests -p test_probe.py || failed=1
    rm -f "$wrapper" out/gow3-probe.qemu
fi
if [[ $failed != 0 ]]; then echo 'ARM64 tests failed.'; exit 1; fi
echo 'ARM64 tests passed.'
