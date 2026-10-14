#!/bin/sh
# Starts God of War III inside the Android app (MainActivity.startGame), with the app's ARM64 Linux
# rootfs. The game is prepared and started by the PC port's own run.py (patches, linking, mods,
# update folder, save backup), unchanged: this script only sets up the Android side (GPU driver,
# display, input, audio) and the paths. Adapted from Bloodborne-Android's tools/android/run-thor.sh.
#   run-game.sh game | vulkan
set -eu
files=${FXD_FILES:-/data/data/io.github.gow3.android/files}
host="$files/rootfs"
base="$files/gow3"            # the port: run.py, scripts/, patches/, bin/, lib/ (replaced by updates)
data="$files/gow3-data"       # the player's: gow3.ini, android.ini, user/ (saves), out/ (prepared image)
export FXD_FILES="$files" FXD_ROOT="$host"
export PATH="$host/usr/bin:$host/bin:/system/bin"
export HOME="$files/home/gow3"
export TMPDIR="$host/tmp" XDG_RUNTIME_DIR="$host/tmp"
# shadPS4 creates ~/.local/share/shadPS4 at start, one level only.
mkdir -p "$data/logs" "$data/user" "$HOME/.local/share"
# The libraries the rootfs lacks (FFmpeg, SDL3, ...), bundled with the port, before the rootfs's.
export LD_LIBRARY_PATH="$base/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
android_ini="$data/android.ini"
setting() { sed -n "s/^$1=//p" "$android_ini" 2>/dev/null | tail -n 1; }
# First start: the phone defaults (720p, the game's own 60 FPS engine rate, a 30 FPS limit).
[ -f "$data/gow3.ini" ] || cp "$base/gow3.default.ini" "$data/gow3.ini"

# The GPU driver. One the player installed from a zip (the app's GPU driver menu, DriverManager.java)
# turns the automatic choice off: drivers/<gpu_driver>/icd.json names its library there. Without one,
# the bundled Turnip by GPU: Mesa main for Adreno 6xx/7xx, the turnip/gen8 build on Adreno 8xx
# (chip id 0x44...). GOW3_TURNIP=main|gen8 overrides that choice.
gpu_driver=$(setting gpu_driver)
case $gpu_driver in .*|*/*) gpu_driver= ;; esac
if [ -n "$gpu_driver" ] && [ -f "$files/drivers/$gpu_driver/icd.json" ]; then
    icd="$files/drivers/$gpu_driver/icd.json"
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$files/drivers/$gpu_driver"
    echo "GPU driver: $gpu_driver (installed from a zip, automatic choice off)"
else
    [ -n "$gpu_driver" ] && echo "GPU driver $gpu_driver is not installed: automatic choice"
    turnip=${GOW3_TURNIP:-}
    if [ -z "$turnip" ]; then
        case $("$base/bin/kgsl-chip-id" 2>/dev/null || true) in
            0x44*) turnip=gen8 ;;
            *) turnip=main ;;
        esac
    fi
    icd="$host/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
    [ "$turnip" = gen8 ] && [ -f "$host/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json" ] &&
        icd="$host/usr/share/vulkan/icd.d/freedreno_gen8_icd.aarch64.json"
    echo "Turnip: ${icd##*/}"
fi
export VK_DRIVER_FILES="$icd" VK_ICD_FILENAMES="$icd"

# Input: the app writes the pad state (gamepads and the on-screen controller) to a file the runtime
# reads (src/runtime_pad.c GOW3_PAD_FILE); keys go to the X11 window.
export GOW3_ANDROID_INPUT=1 GOW3_PAD_FILE="$data/android-pad.state" GOW3_PAD_QUIET=1
# Sound: SDL's disk driver mixes into the FIFO the app plays (AudioBridge.java). Only when the app
# made it: a FIFO nobody reads would stall the game's audio. Its reads pace the output.
if [ -p "$data/audio.fifo" ]; then
    export SDL_AUDIO_DRIVER=disk SDL_AUDIO_DISK_OUTPUT_FILE="$data/audio.fifo"
    export SDL_AUDIO_DISK_TIMESCALE=0 SDL_AUDIO_DEVICE_SAMPLE_FRAMES=512
fi
# Display: the game's window on Xvfb :0 (1280x720), its frames straight to the app's surface through
# the display bridge (Turnip's patched X11 WSI, FEXDROID_PRESENT).
export SDL_VIDEODRIVER=x11 DISPLAY=:0 GOW3_FULLSCREEN=1
export MESA_VK_WSI_DEBUG=sw MESA_VK_WSI_PRESENT_MODE=relaxed
export FEXDROID_PRESENT="$host/tmp/fxpresent.sock"
export SDL_JOYSTICK_HIDAPI=0

# FEXCore (the game's x86-64 code): the fast profile (no x86 memory-ordering emulation, reduced x87
# precision), as Bloodborne-Android ships it.
export FEX_APP_CONFIG_LOCATION="$HOME/.fex-emu/" FEX_APP_DATA_LOCATION="$HOME/.fex-emu/"
export FEX_APP_CACHE_LOCATION="$HOME/.cache/fex-emu/"
mkdir -p "$HOME/.fex-emu" "$HOME/.cache/fex-emu"
cp "$base/fex-config.json" "$HOME/.fex-emu/Config.json"

# The port (run.py): its data and settings outside the bundle, so app updates keep them.
export GOW3_PREBUILT=1 GOW3_DATA_DIR="$data" GOW3_CONFIG="$data/gow3.ini" GOW3_USER_DIR="$data/user"
export GOW3_PROBE="$base/bin/gow3-probe"
export GOW3_MODS_DIR=${GOW3_MODS_DIR:-/storage/emulated/0/GoW3/mods}
export GOW3_MODS_CONFIG="$data/mods.json" GOW3_PATCHES_CONFIG="$data/patches.json"
# The in-game "Apply and restart" runs run.py again through the rootfs shell (Android has no /bin/sh).
export GOW3_SHELL="$host/bin/sh"
export PYTHONHOME="$host/usr" PYTHONIOENCODING=utf-8 PYTHONUNBUFFERED=1
python="$host/usr/bin/python3.13"

# Local overrides for this device (experiments, diagnostics): sourced last, not part of the bundle.
[ -f "$data/local.sh" ] && . "$data/local.sh"
cd "$base"
case ${1:-game} in
    vulkan)
        exec "$base/bin/gow3-probe" --vulkan-only
        ;;
    game)
        [ -n "${GOW3_GAME_DIR:-}" ] && [ -f "$GOW3_GAME_DIR/eboot.bin" ] ||
            { echo "Game missing: ${GOW3_GAME_DIR:-(not chosen)}"; exit 2; }
        exec "$python" "$base/run.py"
        ;;
    *) echo 'usage: run-game.sh game|vulkan' >&2; exit 2 ;;
esac
