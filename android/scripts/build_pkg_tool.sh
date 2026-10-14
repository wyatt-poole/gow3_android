#!/usr/bin/env bash
# ps4-pkg-tool (github.com/xXJSONDeruloXx/ps4-pkg-tools at 45baeda: shadPS4's PKG/PFS code, GPL-2.0),
# the CLI that unpacks the game's PKGs for the app's "Install from PKG" (android/runtime/pkg_install.py).
# Our patches (android/pkg-tool-patches, from Bloodborne-Android: byte progress, 64-bit offsets for
# multi-GB PKGs) are applied to a copy of the source. Output: out/arm64/pkg-tool/ps4-pkg-tool.
# Source: $GOW3_PKG_TOOL_SRC, else cloned into out/android/src/ps4-pkg-tools.
set -euo pipefail
cd -- "$(dirname -- "$0")/../.."
root=$PWD
commit=45baedaa29b8da42b3c6fe912800d2fa4119834b
src=${GOW3_PKG_TOOL_SRC:-$root/out/android/src/ps4-pkg-tools}
if [[ ! -f $src/CMakeLists.txt ]]; then
    git clone -q https://github.com/xXJSONDeruloXx/ps4-pkg-tools.git "$src"
    git -C "$src" checkout -q "$commit"
    git -C "$src" submodule update --init --recursive --depth 1
fi
[[ -f $src/externals/cryptopp/cryptlib.h ]] || git -C "$src" submodule update --init --recursive --depth 1
# The patched copy: src/ copied, everything else (the big vendored externals) linked.
copy=$root/out/pkg-tool-src
rm -rf "$copy"
mkdir -p "$copy"
for entry in "$src"/*; do
    [[ ${entry##*/} == src ]] || ln -s "$entry" "$copy/${entry##*/}"
done
cp -a "$src/src" "$copy/src"
for patch in "$root"/android/pkg-tool-patches/*.patch; do
    patch -d "$copy" -p1 -s < "$patch" || { echo "Patch failed: $patch" >&2; exit 1; }
done
sysroot=${GOW3_ARM64_SYSROOT:-$root/out/android/arm64-sysroot}
toolchain=$root/android/scripts/aarch64-toolchain.cmake
package=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$root/android/app/AndroidManifest.xml")
interpreter=/data/data/$package/files/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
[[ -f $sysroot/usr/lib/aarch64-linux-gnu/libc.so.6 ]] || { echo "No sysroot: $sysroot" >&2; exit 1; }
out=$root/out/arm64/pkg-tool build=$root/out/arm64/pkg-tool-build
mkdir -p "$build" "$out"
# The tool prints "Progress: ..." lines into a pipe: line-buffered stdout (linked in, runs before main).
cat > "$build/linebuf.c" <<'C'
#include <stdio.h>
__attribute__((constructor)) static void line_buffered_stdout(void) { setvbuf(stdout, NULL, _IOLBF, 0); }
C
clang --target=aarch64-linux-gnu --sysroot="$sysroot" -O2 -c "$build/linebuf.c" -o "$build/linebuf.o"
# Static: the vendored cryptopp, the sysroot's zlib, libstdc++ and libgcc; libc and libm stay dynamic.
cmake -S "$copy" -B "$build" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DCMAKE_BUILD_TYPE=Release \
    -DGOW3_ARM64_SYSROOT="$sysroot" -DBUILD_PKG_QT_GUI=OFF -DCRYPTOPP_BUILD_TESTING=OFF \
    -DZLIB_LIBRARY="$sysroot/usr/lib/aarch64-linux-gnu/libz.a" -DZLIB_INCLUDE_DIR="$sysroot/usr/include" \
    "-DCMAKE_EXE_LINKER_FLAGS=-fuse-ld=lld -no-pie -static-libstdc++ -static-libgcc -Wl,--dynamic-linker=$interpreter $build/linebuf.o" \
    > "$build/cmake.log" 2>&1 || { tail -30 "$build/cmake.log" >&2; exit 1; }
ninja -C "$build" ps4-pkg-tool > "$build/build.log" 2>&1 || { tail -40 "$build/build.log" >&2; exit 1; }
cp "$build/ps4-pkg-tool" "$out/ps4-pkg-tool"
llvm-strip --strip-all "$out/ps4-pkg-tool"
echo "Built $out/ps4-pkg-tool (aarch64)"
