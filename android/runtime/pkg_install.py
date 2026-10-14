#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Installs God of War III Remastered from its PS4 PKG files on Android (the app's "Install from PKG").

  pkg_install.py --pkg P --dest DIR [--replace] [--tool T]

Unpacks a game PKG (the base game, category 'gd', or its update, 'gp') with ps4-pkg-tool into
DIR/<TITLE_ID> (DIR may be that folder itself); an update is merged over the installed game. Prints
`progress <bytesDone> <bytesTotal> <label>` lines (total 0: unknown), everything else goes to stderr,
and the last stdout line is one JSON object for the app (GameInstaller.java):
  {"ok", "gameDir", "titleId", "category", "appVer", "ready", "next", "problem"}
Always exits 0. Adapted from Bloodborne-Android's tools/android/mods_android.py (install-game).
"""
import argparse
import collections
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import struct
import subprocess
import sys

HERE = Path(__file__).resolve().parent
MIB = 1 << 20
GIB = 1 << 30
GAME_MARGIN = 256 * MIB
PKG_MAGIC = b'\x7fCNT'
ENCRYPTED = ("This PKG could not be unpacked. Only fake (debug-signed) PKGs, such as those made from your "
             "own console's dump, can be installed; retail PSN PKGs are encrypted")
# God of War III Remastered releases (scripts/patches.py GAMES is the source; this is the fallback).
SERIALS = {'CUSA01623', 'CUSA01715', 'CUSA01720'}
# The version the community patches are made for; older ones run without some of them.
PATCHED_VERSION = '01.02'
# Moved last when an update is merged: a cut-short merge leaves the old executable and its metadata.
LAST_NAMES = {'eboot.bin', 'sce_sys'}


def known_serials():
    """The serials scripts/patches.py knows (upstream adds regions there), else SERIALS."""
    try:
        sys.path.insert(0, str(HERE / 'scripts'))
        import patches
        found = set()
        for game in patches.GAMES:
            found |= set(game[0])
        return found or SERIALS
    except Exception:  # noqa: BLE001 (an older or changed patches.py: the fallback list)
        return SERIALS
    finally:
        sys.path.pop(0)


class Failure(Exception):
    """A reason for the player, shown as is."""


def gigabytes(size):
    return f'{size / GIB:.1f} GB'


def progress(done, total, label):
    print(f'progress {done} {total} {label}', flush=True)


def parse_sfo(data):
    """param.sfo -> {key: str or int}; ValueError when it is not one."""
    try:
        if data[:4] != b'\0PSF':
            raise ValueError('not a param.sfo')
        _, key_start, data_start, count = struct.unpack_from('<4xIIII', data, 0)
        values = {}
        for index in range(count):
            key_offset, fmt, length, _max, data_offset = struct.unpack_from('<HHIII', data, 20 + 16 * index)
            end = data.index(b'\0', key_start + key_offset)
            key = data[key_start + key_offset:end].decode('ascii')
            raw = data[data_start + data_offset:data_start + data_offset + length]
            values[key] = (struct.unpack('<I', raw[:4])[0] if fmt == 0x0404
                           else raw.split(b'\0')[0].decode('utf-8', 'replace'))
        return values
    except (struct.error, ValueError, UnicodeDecodeError) as error:
        raise ValueError(f'param.sfo: {error}')


def read_pkg(path):
    """The PKG's header facts (big-endian): {'contentId', 'size', 'sfo'}; 'sfo' is its unencrypted
    param.sfo parsed (None when it cannot be read). Failure for a file that is not a complete PKG."""
    try:
        file_size = os.path.getsize(path)
        with open(path, 'rb') as file:
            head = file.read(0x440)
            if head[:4] != PKG_MAGIC:
                raise Failure('This is not a PS4 PKG file')
            if len(head) < 0x440:
                raise Failure('This PKG file is incomplete')
            info = {'contentId': head[0x40:0x64].split(b'\0')[0].decode('ascii', 'replace'),
                    'size': struct.unpack_from('>Q', head, 0x430)[0], 'sfo': None}
            if info['size'] > file_size:
                raise Failure('This PKG file is incomplete (the download or copy was cut short)')
            count, table = struct.unpack_from('>I', head, 0x10)[0], struct.unpack_from('>I', head, 0x18)[0]
            try:
                file.seek(table)
                for _ in range(min(count, 4096)):
                    entry = file.read(32)
                    if len(entry) < 32:
                        break
                    identifier, offset, size = struct.unpack_from('>I', entry, 0)[0], *struct.unpack_from('>II', entry, 16)
                    if identifier == 0x1000 and 0 < size <= MIB:  # param.sfo
                        file.seek(offset)
                        info['sfo'] = parse_sfo(file.read(size))
                        break
            except (ValueError, OSError, struct.error):
                info['sfo'] = None
    except OSError as error:
        raise Failure(f"Can't read the PKG: {error.strerror or error}")
    return info


def free_space(path):
    return shutil.disk_usage(path).free


def remove_staging(parent):
    """Deletes the .staging-* folders an install that was killed left in `parent`."""
    for entry in Path(parent).iterdir():
        if entry.name.startswith('.staging-'):
            if entry.is_symlink() or not entry.is_dir():
                entry.unlink()
            else:
                shutil.rmtree(entry, ignore_errors=True)


def new_staging(parent):
    while True:
        staging = Path(parent) / f'.staging-{secrets.token_hex(4)}'
        try:
            staging.mkdir()
            return staging
        except FileExistsError:
            continue


def run_tool(tool, pkg, staging, space_dir):
    """Runs ps4-pkg-tool PKG STAGING, relaying its `Progress-bytes: done total` lines as `progress`."""
    tool = Path(tool) if tool else None
    if tool is None or not tool.is_file() or not os.access(tool, os.X_OK):
        raise Failure('The PKG tool is missing from this installation (ps4-pkg-tool)')
    tail = collections.deque(maxlen=12)
    checked = False
    try:
        process = subprocess.Popen([str(tool), str(pkg), str(staging)], stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    except OSError as error:
        raise Failure(f"Can't run the PKG tool: {error.strerror or error}")
    try:
        for line in process.stdout:
            line = line.rstrip('\n')
            match = re.fullmatch(r'Progress-bytes: (\d+) (\d+)', line)
            if not match:
                if line.strip():
                    tail.append(line)
                    print(line, file=sys.stderr, flush=True)
                continue
            done, total = int(match[1]), int(match[2])
            if not checked and total:
                checked = True
                free = free_space(space_dir)
                if free < total + GAME_MARGIN:
                    process.kill()
                    raise Failure(f'Not enough free space: {gigabytes(total + GAME_MARGIN)} needed, '
                                  f'{gigabytes(free)} available')
            progress(done, total, 'Extracting')
        code = process.wait()
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()
    if code != 0:
        text = '\n'.join(tail)
        if free_space(space_dir) < 16 * MIB:
            raise Failure('Not enough free space to unpack the PKG')
        if 'file size is different' in text or 'bigger than pkg size' in text:
            raise Failure('This PKG file is damaged or incomplete')
        raise Failure(ENCRYPTED)


def child(folder, name):
    """folder/name, matching an existing entry case-insensitively (PS4 paths ignore case)."""
    folder = Path(folder)
    direct = folder / name
    if direct.exists() or not folder.is_dir():
        return direct
    for entry in folder.iterdir():
        if entry.name.casefold() == name.casefold():
            return entry
    return direct


def merge_tree(source, target):
    """Moves the files of `source` over the folder `target` (same filesystem), replacing; names
    match case-insensitively; eboot.bin and sce_sys go last."""
    target = Path(target)
    if target.is_symlink() or (target.exists() and not target.is_dir()):
        target.unlink()
    target.mkdir(exist_ok=True)
    for entry in sorted(Path(source).iterdir(), key=lambda e: (e.name.casefold() in LAST_NAMES, e.name)):
        destination = child(target, entry.name)
        if entry.is_dir() and not entry.is_symlink():
            merge_tree(entry, destination)
        else:
            if destination.is_dir() and not destination.is_symlink():
                shutil.rmtree(destination)
            os.replace(entry, destination)


def extracted_root(staging):
    folders = [e for e in Path(staging).iterdir() if e.is_dir() and not e.is_symlink()]
    return folders[0] if len(folders) == 1 else None


def sfo_of(root):
    try:
        return parse_sfo((Path(root) / 'sce_sys' / 'param.sfo').read_bytes())
    except (OSError, ValueError):
        return None


def install(args, state, result):
    serials = known_serials()
    info = read_pkg(args.pkg)
    if info['sfo'] is None:
        raise Failure("This PKG is damaged: its param.sfo can't be read")

    def identify(sfo):
        title = sfo.get('TITLE_ID') or info['contentId'][7:16]
        category = sfo.get('CATEGORY')
        result.update(titleId=title, category=category if category in ('gd', 'gp') else None,
                      appVer=sfo.get('APP_VER'))
        if title not in serials:
            raise Failure(f"This PKG is for {title}, which isn't God of War III Remastered "
                          f"({', '.join(sorted(serials))})")
        if category not in ('gd', 'gp'):
            raise Failure(f"This PKG is {category or 'unknown'} content; only the game (gd) and its "
                          'update (gp) can be installed')
        return title, category

    title, category = identify(info['sfo'])
    dest = Path(args.dest)
    if (dest / 'eboot.bin').is_file() or dest.name in serials:
        if dest.name in serials and dest.name != title:
            raise Failure(f'This PKG is for {title}, but the folder {dest.name} is for another release')
        parent, game = dest.parent, dest
    else:
        parent, game = dest, dest / title
    installed = (game / 'eboot.bin').is_file()
    if installed:
        have = (sfo_of(game) or {}).get('TITLE_ID')
        if have and have != title:
            raise Failure(f'The game in {game.name} is {have}, but this PKG is for {title}')
    if category == 'gd' and installed and not args.replace:
        raise Failure('A game is already installed there')
    if category == 'gp' and not installed:
        raise Failure(f'There is no game to update in {game}: install the base game PKG for {title} first')
    try:
        parent.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise Failure(f"Can't create the folder: {error.strerror or error}")
    remove_staging(parent)
    need = info['size'] + GAME_MARGIN
    if free_space(parent) < need:
        raise Failure(f'Not enough free space: {gigabytes(need)} needed, {gigabytes(free_space(parent))} available')
    staging = state['staging'] = new_staging(parent)
    run_tool(args.tool, args.pkg, staging, parent)
    progress(0, 0, 'Installing')
    root = extracted_root(staging)
    sfo = sfo_of(root) if root else None
    if root is None or sfo is None:
        raise Failure(ENCRYPTED)
    if identify(sfo) != (title, category):
        raise Failure('The PKG changed while it was unpacked')
    # A base game unpacks to a complete folder; anything less (an encrypted PFS image unpacks to its
    # sce_sys entries only) must not replace the game folder.
    if category == 'gd' and not (root / 'eboot.bin').is_file():
        raise Failure(ENCRYPTED)
    if category == 'gd':
        aside = None
        if game.exists() or game.is_symlink():
            aside = state['aside'] = parent / f'.staging-old-{secrets.token_hex(4)}'
            os.rename(game, aside)
        try:
            os.rename(root, game)
        except OSError:
            if aside is not None:
                os.rename(aside, game)
            raise
    else:
        merge_tree(root, game)
    result['gameDir'] = str(game)
    # The game runs from any version with eboot.bin, sce_module and sce_sys; the community patches
    # (frame rate, resolution) are made for 01.02.
    if not (game / 'eboot.bin').is_file() or not (game / 'sce_sys' / 'param.sfo').is_file():
        raise Failure(f'The installed game in {game} is incomplete')
    result['ready'] = True
    version = (sfo_of(game) or {}).get('APP_VER', '')
    if version and version < PATCHED_VERSION:
        result['next'] = f'Install the {PATCHED_VERSION} update PKG for {title} for every patch (frame rate, resolution)'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--pkg', required=True, type=Path)
    parser.add_argument('--dest', required=True, type=Path)
    parser.add_argument('--replace', action='store_true')
    parser.add_argument('--tool', default=str(HERE / 'bin' / 'ps4-pkg-tool'))
    args = parser.parse_args()

    def die_cleanly(signum, _frame):
        raise SystemExit(128 + signum)  # the finally block below removes the staging folder
    signal.signal(signal.SIGTERM, die_cleanly)
    signal.signal(signal.SIGINT, die_cleanly)
    result = {'ok': False, 'gameDir': None, 'titleId': None, 'category': None, 'appVer': None,
              'ready': False, 'next': None, 'problem': None}
    state = {}
    try:
        install(args, state, result)
        result['ok'] = True
    except Failure as failure:
        result['problem'] = str(failure)
    except Exception as error:  # noqa: BLE001 (always one JSON answer for the app)
        result['problem'] = f'Install failed: {type(error).__name__}: {error}'
    finally:
        for key in ('staging', 'aside'):
            if state.get(key):
                shutil.rmtree(state[key], ignore_errors=True)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
