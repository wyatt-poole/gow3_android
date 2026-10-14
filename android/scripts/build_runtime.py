#!/usr/bin/env python3
"""Builds the game-free runtime archive the APK embeds (assets/runtime.payload).

  rootfs/  the app's ARM64 Linux userland: Debian trixie with Xvfb, Python 3.13, Mesa Turnip (two
           variants, Adreno 6xx/7xx and 8xx), fexdroid's display/shared-memory pieces and the
           Lossless Scaling layer. Taken unchanged from a pinned Bloodborne-Android release (its
           runtime is not game-specific) and rehomed to this app's package: every
           /data/data/<their package>/ path becomes /data/data/<ours>/. The two package names have the
           same length, so ELF interpreters, RPATHs and glibc's built-in paths keep their offsets.
  gow3/    this port: run.py, scripts/, patches/ (from the repository, unchanged), the ARM64 binaries
           (android/scripts/build_arm64.sh, build_pkg_tool.sh) and the libraries the rootfs lacks.

The base APK's native display/input bridges (libfxdisplay.so, libfxio.so, MIT, fexdroid) are
extracted next to the archive for build_apk.sh. Nothing from the game is ever packaged.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'out/android/release'
CACHE = ROOT / 'out/android/base'
# The base runtime: Bloodborne-Android 0.8.3 (github.com/ducvd89/Bloodborne-Android, GPL-2.0).
BASE_VERSION = '0.8.3'
BASE_URL = (f'https://github.com/ducvd89/Bloodborne-Android/releases/download/android-{BASE_VERSION}/'
            f'Bloodborne-{BASE_VERSION}.apk')
BASE_SHA256 = 'cdf98fd2b0c7db14dac2d905d046d9919c75e7c7583783b9c8660d73655ba0eb'
BASE_PACKAGE = 'com.ducvd89.bloodborne'
BRIDGES = ('lib/arm64-v8a/libfxdisplay.so', 'lib/arm64-v8a/libfxio.so')
ARM64 = ROOT / 'out/arm64'
SYSROOT = ROOT / 'out/android/arm64-sysroot'
# The port's files, as in the repository (run.py finds scripts/ and patches/ beside itself).
PORT_FILES = ('run.py', 'scripts', 'patches', 'LICENSE')
RUNTIME_FILES = ('run-game.sh', 'pkg_install.py', 'gow3.default.ini', 'fex-config.json')
# FFmpeg's libraries share internal symbols: always the same build of all of them.
FFMPEG = ('libavformat.so.61', 'libavcodec.so.61', 'libavutil.so.59', 'libswscale.so.8', 'libswresample.so.5')


def package_name():
    manifest = (ROOT / 'android/app/AndroidManifest.xml').read_text()
    return manifest.split('package="', 1)[1].split('"', 1)[0]


def fetch_base(url, digest):
    """The base APK (cached under out/android/base), verified by its SHA-256."""
    CACHE.mkdir(parents=True, exist_ok=True)
    apk = CACHE / url.rsplit('/', 1)[1]
    if not apk.is_file() or hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest() != digest:
        print(f'Downloading {url}')
        part = apk.with_suffix('.part')
        with urllib.request.urlopen(url, timeout=600) as response, part.open('wb') as out:
            shutil.copyfileobj(response, out, 1 << 20)
        if hashlib.file_digest(part.open('rb'), 'sha256').hexdigest() != digest:
            part.unlink()
            raise SystemExit(f'SHA-256 mismatch for {url}')
        part.replace(apk)
    return apk


def needed(path):
    output = subprocess.check_output(['llvm-readelf', '-d', str(path)], text=True)
    return [line.split('[', 1)[1].split(']', 1)[0] for line in output.splitlines() if '(NEEDED)' in line]


def bundle_libraries(rootfs_libs):
    """{name: path}: libgow3gpu, libgow3cpu and the libraries they and gow3-probe need that the rootfs
    lacks, from the sysroot (the same Debian release)."""
    ours = {'libgow3gpu.so': ARM64 / 'gpu/libgow3gpu.so', 'libgow3cpu.so': ARM64 / 'fex/libgow3cpu.so'}
    libdirs = [SYSROOT / 'usr/lib/aarch64-linux-gnu', SYSROOT / 'usr/lib']

    def find(name):
        if name in ours:
            return ours[name]
        for directory in libdirs:
            if (directory / name).exists():
                return (directory / name).resolve()
        raise SystemExit(f'not in the sysroot: {name}')

    ship, seen = {}, set()
    queue = [ARM64 / 'gow3-probe', *(find(n) for n in FFMPEG)]
    for name in FFMPEG:
        ship[name] = find(name)
    while queue:
        for name in needed(queue.pop()):
            if name in seen:
                continue
            seen.add(name)
            if name in rootfs_libs and name not in ours and name not in FFMPEG:
                continue
            ship[name] = find(name)
            queue.append(ship[name])
    return ship


def rehome(data, old, new):
    return data.replace(old, new) if old in data else data


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base-apk', type=Path, help='a local copy of the base APK (else downloaded)')
    args = parser.parse_args()
    for path in (ARM64 / 'gow3-probe', ARM64 / 'kgsl-chip-id', ARM64 / 'gpu/libgow3gpu.so',
                 ARM64 / 'fex/libgow3cpu.so', ARM64 / 'pkg-tool/ps4-pkg-tool'):
        if not path.is_file():
            raise SystemExit(f'Missing {path}: run android/scripts/build_arm64.sh and build_pkg_tool.sh first')
    ours = package_name()
    old_root, new_root = f'/data/data/{BASE_PACKAGE}/'.encode(), f'/data/data/{ours}/'.encode()
    if len(old_root) != len(new_root):
        raise SystemExit(f'The package name must have {len(BASE_PACKAGE)} characters, as {BASE_PACKAGE} '
                         f'(the rootfs is rehomed in place): {ours}')
    apk = args.base_apk or fetch_base(BASE_URL, BASE_SHA256)
    if hashlib.file_digest(open(apk, 'rb'), 'sha256').hexdigest() != BASE_SHA256:
        raise SystemExit(f'{apk} is not Bloodborne-Android {BASE_VERSION} (SHA-256 mismatch)')
    OUT.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        payload = z.read('assets/runtime.payload')
        expected = z.read('assets/runtime.sha256').decode().strip()
        if hashlib.sha256(payload).hexdigest() != expected:
            raise SystemExit('The base APK\'s runtime does not match its own checksum')
        for name in BRIDGES:
            (OUT / Path(name).name).write_bytes(z.read(name))
        (OUT / 'python-packages.txt').write_bytes(z.read('assets/python-packages.txt'))

    archive = OUT / 'runtime.tar.gz'
    rootfs_libs, rehomed = set(), 0
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:gz') as base, \
            tarfile.open(archive, 'w:gz', compresslevel=6, format=tarfile.PAX_FORMAT) as tar:
        for member in base:
            if not member.name.startswith('rootfs'):
                continue  # bbport/: Bloodborne's own port
            if member.name.startswith('rootfs/usr/lib/') and member.name.count('/') <= 4:
                rootfs_libs.add(member.name.rsplit('/', 1)[1])
            if member.issym() or member.islnk():
                member.linkname = rehome(member.linkname.encode(), old_root, new_root).decode()
                tar.addfile(member)
            elif member.isfile():
                data = base.extractfile(member).read()
                changed = rehome(data, old_root, new_root)
                rehomed += changed is not data
                member.size = len(changed)
                tar.addfile(member, io.BytesIO(changed))
            else:
                tar.addfile(member)

        def add(path, name, mode=None):
            info = tar.gettarinfo(str(path), name)
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            if mode is not None:
                info.mode = mode
            with open(path, 'rb') as f:
                tar.addfile(info, f)

        def add_tree(path, name):
            for child in sorted(Path(path).rglob('*')):
                if child.is_file() and '__pycache__' not in child.parts:
                    add(child, f'{name}/{child.relative_to(path)}')

        for item in PORT_FILES:
            source = ROOT / item
            if source.is_dir():
                add_tree(source, f'gow3/{item}')
            else:
                add(source, f'gow3/{item}')
        icon = ROOT / 'launcher/gow3.png'
        if icon.is_file():
            add(icon, 'gow3/launcher/gow3.png')
        for item in RUNTIME_FILES:
            add(ROOT / 'android/runtime' / item, f'gow3/{item}')
        add(ROOT / 'android/runtime/ps4-pkg-tool.NOTICE', 'gow3/licenses/ps4-pkg-tool.NOTICE')
        stage = OUT / 'stage'
        shutil.rmtree(stage, ignore_errors=True)
        (stage / 'bin').mkdir(parents=True)
        (stage / 'lib').mkdir()
        for name in ('gow3-probe', 'kgsl-chip-id'):
            shutil.copy2(ARM64 / name, stage / 'bin' / name)
        shutil.copy2(ARM64 / 'pkg-tool/ps4-pkg-tool', stage / 'bin/ps4-pkg-tool')
        libraries = bundle_libraries(rootfs_libs)
        for name, source in sorted(libraries.items()):
            shutil.copy2(source, stage / 'lib' / name)
        for path in [*(stage / 'bin').iterdir(), *(stage / 'lib').iterdir()]:
            subprocess.run(['llvm-strip', '--strip-debug', str(path)], check=True)
            add(path, f'gow3/{path.relative_to(stage)}', 0o755)
        for name in ('gow3-data', 'home/gow3'):
            info = tarfile.TarInfo(name)
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            tar.addfile(info)
    with tarfile.open(archive) as tar:
        names = tar.getnames()
    banned = ('boot-linked.bin', 'eboot.bin', 'eboot.elf', 'savedata', 'Lossless.dll', '.sprx')
    if any(any(word in name for word in banned) for name in names):
        archive.unlink()
        raise SystemExit('Refusing to package a game-derived file')
    digest = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
    (OUT / 'runtime.sha256').write_text(digest + '\n')
    (OUT / 'runtime.json').write_text(json.dumps({
        'base': {'url': BASE_URL, 'sha256': BASE_SHA256, 'package': BASE_PACKAGE},
        'package': ours, 'files_rehomed': rehomed, 'bundled_libraries': sorted(libraries),
    }, indent=2) + '\n')
    print(f'Runtime: {archive} ({archive.stat().st_size:,} bytes), sha256 {digest}; '
          f'{rehomed} rootfs files rehomed to {ours}, {len(libraries)} libraries bundled')


if __name__ == '__main__':
    main()
