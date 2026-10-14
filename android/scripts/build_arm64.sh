#!/usr/bin/env bash
# Native ARM64 build of the port for Android (the app's Debian trixie arm64 userland),
# cross-compiled with Clang: the runtime and the GPU library run natively, the game's x86-64 code
# in FEXCore (libgow3cpu.so, cpu/). Run android/scripts/fetch_sysroot.py first.
# Output: out/arm64/{gow3-probe, gpu/libgow3gpu.so, fex/libgow3cpu.so, kgsl-chip-id}.
# Steps: all (default), fex, gpu, probe.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
step=${1:-all}
out=out/arm64
sysroot=${GOW3_ARM64_SYSROOT:-$PWD/out/android/arm64-sysroot}
toolchain=$PWD/android/scripts/aarch64-toolchain.cmake
package=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' android/app/AndroidManifest.xml)
# Android has no /lib: programs name the app's rootfs loader by absolute path.
interpreter=/data/data/$package/files/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
[[ -f $sysroot/usr/lib/aarch64-linux-gnu/libc.so.6 ]] ||
    { echo "No sysroot: $sysroot (python3 android/scripts/fetch_sysroot.py)" >&2; exit 1; }
mkdir -p "$out"
if [[ ! -f third_party/FEX/External/vixl/CMakeLists.txt || ! -f gpu/third_party/imgui/imgui.h ]]; then
    git submodule update --init --recursive
fi

build_fex() {
    if [[ ! -f $out/fex/build.ninja ]]; then
        cmake -S third_party/FEX -B "$out/fex" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
            -DCMAKE_BUILD_TYPE=Release -DCMAKE_PROJECT_FEX_INCLUDE="$PWD/cpu/fex_project.cmake" \
            -DBUILD_TESTING=OFF -DBUILD_THUNKS=OFF -DBUILD_FEXCONFIG=OFF -DBUILD_STEAM_SUPPORT=OFF \
            -DBUILD_FEX_LINUX_TESTS=OFF -DENABLE_ZYDIS=OFF -DTUNE_ARCH=armv8.2-a -DTUNE_CPU=generic \
            > "$out/fex-cmake.log" 2>&1 || { tail -30 "$out/fex-cmake.log" >&2; exit 1; }
    fi
    ninja -C "$out/fex" gow3cpu > "$out/fex-build.log" 2>&1 ||
        { grep -v '^\[' "$out/fex-build.log" | tail -40 >&2; echo "FEX build failed: $out/fex-build.log" >&2; exit 1; }
    echo "Guest CPU: $out/fex/libgow3cpu.so"
}

# Debian trixie's Vulkan headers (1.4.309) predate what the GPU library uses (e.g. the KosmicKrisp
# driver id, VK_VALVE_shader_mixed_float_dot_product): a pinned Khronos release, header-only, takes their place for this build.
vulkan_headers_version=v1.4.344
vulkan_headers=$PWD/out/android/vulkan-headers-$vulkan_headers_version
fetch_vulkan_headers() {
    [[ -f $vulkan_headers/include/vulkan/vulkan.hpp ]] && return
    rm -rf "$vulkan_headers.part"
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$vulkan_headers_version" https://github.com/KhronosGroup/Vulkan-Headers.git \
        "$vulkan_headers.part"
    mv "$vulkan_headers.part" "$vulkan_headers"
}

build_gpu() {
    fetch_vulkan_headers
    # This port's changes to FSR-Vulkan (gpu/patches/fsr-vulkan), applied once, as build.sh does.
    for patch in gpu/patches/fsr-vulkan/*.patch; do
        if ! git -C gpu/third_party/fsr-vulkan apply --reverse --check "$PWD/$patch" 2>/dev/null; then
            git -C gpu/third_party/fsr-vulkan apply "$PWD/$patch"
        fi
    done
    if [[ ! -f $out/gpu/build.ninja ]]; then
        cmake -S gpu -B "$out/gpu" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
            -DGOW3_ARM64_SYSROOT="$sysroot" -DCMAKE_BUILD_TYPE=RelWithDebInfo -DGOW3_PGO=off \
            -DGOW3_LTO="${GOW3_LTO:-OFF}" -DCMAKE_CXX_FLAGS="-isystem $vulkan_headers/include" \
            -DCMAKE_C_FLAGS="-isystem $vulkan_headers/include" > "$out/gpu-cmake.log" 2>&1 ||
            { tail -30 "$out/gpu-cmake.log" >&2; exit 1; }
    fi
    ninja -C "$out/gpu" gow3gpu > "$out/gpu-build.log" 2>&1 ||
        { grep -v '^\[' "$out/gpu-build.log" | tail -40 >&2; echo "GPU build failed: $out/gpu-build.log" >&2; exit 1; }
    echo "GPU library: $out/gpu/libgow3gpu.so"
}

build_probe() {
    local cc=(clang --target=aarch64-linux-gnu --sysroot="$sysroot" -fuse-ld=lld)
    export PKG_CONFIG_SYSROOT_DIR=$sysroot
    export PKG_CONFIG_LIBDIR=$sysroot/usr/lib/aarch64-linux-gnu/pkgconfig:$sysroot/usr/share/pkgconfig
    local includes libraries
    fetch_vulkan_headers
    read -r -a includes <<< "-isystem $vulkan_headers/include $(pkg-config --cflags vulkan sdl3 libavcodec libavutil libswscale)"
    read -r -a libraries <<< "$(pkg-config --libs vulkan sdl3 libavcodec libavutil libswscale)"
    mkdir -p "$out/atrac9"
    for source in third_party/LibAtrac9/C/src/*.c; do
        "${cc[@]}" -std=c99 -O2 -g -w -c "$source" -o "$out/atrac9/$(basename "${source%.c}").o"
    done
    rm -f "$out/libatrac9.a" && llvm-ar rcs "$out/libatrac9.a" "$out"/atrac9/*.o
    # lib/ beside bin/ holds libgow3gpu, libgow3cpu and the libraries the app's rootfs lacks.
    local links=(-L"$out/gpu" -lgow3gpu -L"$out/fex" -lgow3cpu -Wl,-rpath,'$ORIGIN/../lib'
                 -Wl,--dynamic-linker="$interpreter" -rdynamic)
    # isa_rewrite/isa_emulate/cpu_check are the PC's handling of older x86 CPUs: linked, never used
    # here (FEXCore provides the game's instruction sets).
    "${cc[@]}" -std=c11 -O2 -g -Wall -Wextra -Werror -pthread -no-pie "${includes[@]}" -I. -Isrc \
        src/probe.c src/runtime*.c src/cpu_check.c src/vulkan_smoke.c src/isa_emulate.c src/isa_rewrite.c \
        "$out/libatrac9.a" -lm "${links[@]}" "${libraries[@]}" -o "$out/gow3-probe"
    # run-game.sh asks it for the GPU's chip id to pick the Turnip variant (Adreno 8xx: gen8).
    "${cc[@]}" -std=c11 -O2 -Wall -Wextra -Werror android/tools/kgsl_chip_id.c \
        -Wl,--dynamic-linker="$interpreter" -o "$out/kgsl-chip-id"
    echo "Built $out/gow3-probe (aarch64)"
}

case $step in
    fex) build_fex ;;
    gpu) build_gpu ;;
    probe) build_probe ;;
    all) build_fex; build_gpu; build_probe ;;
    *) echo 'usage: build_arm64.sh [all|fex|gpu|probe]' >&2; exit 2 ;;
esac
