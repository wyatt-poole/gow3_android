# God of War III Remastered for Android

An Android port of [gow3_pc](https://github.com/FelipePrado0/gow3_pc), the native PC port of
God of War III Remastered (PS4), built the way
[Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android) ports Bloodborne. Both PC ports
come from [deadinside28's bloodborne_pc](https://github.com/deadinside28/bloodborne_pc) and share its
git history, so this fork stays a normal fork of gow3_pc: upstream changes merge in with `git merge`.

> **Experimental, and not yet tested on a device.** The ARM64 build compiles and links, and the APK
> builds, but nobody has run the game with it yet. Expect crashes, and expect it to be slow: gow3_pc's
> patches target desktop PCs, and here the game's x86-64 code goes through a translator.
> No game files are included. You need your own dump (or PKGs) of the game.

## How it works

The game's own `eboot.bin` runs in the app's process. Its calls to PS4 system libraries go to the
port's native runtime, and its graphics are translated to Vulkan. On a PC the game's x86-64 code
runs directly on the CPU. On a phone, [FEXCore](https://github.com/FEX-Emu/FEX) translates it to
ARM64, linked into the game as a library (`libgow3cpu.so`). Nothing else is emulated.

| Part | PC (gow3_pc) | Android (this fork) |
|---|---|---|
| Game code (`eboot.bin`, x86-64) | runs on the CPU | FEXCore JIT (`cpu/`, `src/guest_cpu.h`) |
| Runtime, GPU translation (shadPS4 video core) | native x86-64 | native ARM64, same source |
| Vulkan driver | the PC's | Mesa Turnip (Adreno 6xx/7xx/8xx), in the APK |
| Window, input, sound | SDL3 | SDL3 on an Xvfb display, sent to the app's surface; touch and gamepads via a pad file; audio via a FIFO |
| Launcher | `run.py` + Tk launcher | the same `run.py`, started by the Android app |

`src/guest_cpu.h` is the one boundary between the game's code and the host. On x86-64 it compiles
to plain calls (`src/runtime_cpu.c`), so the PC build behaves as before. On ARM64 FEXCore implements
it. The Android app (`android/app`) and its Linux userland come from Bloodborne-Android. The prebuilt
rootfs (Debian, Xvfb, Python, Turnip, fexdroid's display bridge) is game-agnostic, so the build takes
it from a pinned Bloodborne-Android release and rehomes it to this app's package name.

## Install and play

1. Install the APK (`GoW3-<version>.apk` from this repository's releases, or build it below).
   It needs Android 9 or newer, an Adreno 6xx/7xx/8xx GPU (Snapdragon) and a kernel with 4 KB
   memory pages. Bloodborne-Android, which this is built on, was tested on 16 GB devices (AYN Thor,
   Snapdragon 8 Gen 2; Oppo Find N5, 8 Elite).
2. Open **God of War III** and allow storage access.
3. Choose your game:
   - **Choose folder**: your extracted dump (`CUSA01623`, `CUSA01715` or `CUSA01720`, the folder
     with `eboot.bin`, `sce_module` and `sce_sys`), or the folder that holds it.
   - **Install from PKG…**: pick the base game `.pkg`, then a folder to install into. The app
     unpacks it to `<folder>/<serial>` and starts the game. Then use **Install from PKG** in the
     menu (Storage) for the **01.02 update** PKG, which is merged into the same folder. Only fake
     (debug-signed) PKGs made from your own console's dump unpack; retail PSN PKGs are encrypted.
4. The first start takes a few minutes: the runtime unpacks and the game image is prepared, as on PC.

**The app menu**: swipe in from the left edge, or press Back.
- **In-game menu**: gow3_pc's own menu (cheats, orb and damage multipliers, enemy health bar,
  performance overlay). Tap to use it, and press Back to close it. With a controller, **R3 + L2**
  opens it, as on PC.
- **Graphics settings**: render resolution (480p / 720p / 1080p), display output (auto, the
  default, matches the screen; or 1280x720 / 1920x1080; changing it restarts the app), engine frame rate (60 or the 120 FPS patch) and FPS
  limit. The defaults are 720p rendering, automatic output, 60 and a 30 FPS limit; for a sharp 1080p
  picture set both resolutions to 1080p.
- **GPU driver**: the bundled Turnip (chosen automatically by GPU), or a Turnip zip.
- **On-screen controller**, editable layout. Physical controllers work too.
- **Back up / Restore saves**: saves are also compatible with the PC port's `user/savedata`.
- **Check for updates**: installs newer releases of this app.

Mods: loose-file mods in `/storage/emulated/0/GoW3/mods` are applied by the PC port's mod loader
(`scripts/mods.py`), as on PC.

## Build

All of it cross-compiles on an x86-64 Linux host. A clean clone has everything it needs; the
scripts download pinned, checksummed inputs into `out/`.

```bash
git clone --recursive https://github.com/wyatt-poole/gow3_android && cd gow3_android
# Host tools: clang, lld, llvm, cmake, ninja, pkg-config, glslang, a JDK (17+), python3, patch
python3 android/scripts/fetch_sysroot.py      # Debian trixie arm64 sysroot (SHA-256 checked)
bash android/scripts/build_arm64.sh           # FEXCore, GPU library, gow3-probe (aarch64)
bash android/scripts/build_pkg_tool.sh        # ps4-pkg-tool for Install from PKG
bash android/scripts/test_qemu.sh             # optional: ARM64 tests under qemu-user (FEXCore, loader)
bash android/scripts/fetch_sdk.sh             # Android SDK platform 35, build-tools 35.0.0
python3 android/scripts/build_runtime.py      # rootfs (pinned Bloodborne-Android 0.8.3) + the port
bash android/scripts/build_apk.sh             # out/android/release/GoW3-<version>.apk
```

`build_apk.sh` signs with a local debug key, or with `GOW3_KEYSTORE`/`GOW3_KEYSTORE_PASS`/
`GOW3_KEY_ALIAS`. The **Android - build** workflow does all of this in GitHub Actions: run it from
the Actions tab; every push to main builds it too, and a tag `android-<version>` also attaches it to a release. For a stable
signing key there, add the repository secrets `GOW3_KEYSTORE_B64` (base64 keystore),
`GOW3_KEYSTORE_PASS` and `GOW3_KEY_ALIAS`.

## Updating from the PC port

```bash
android/sync-upstream.sh --check   # what is new upstream, and which shared files both sides changed
android/sync-upstream.sh           # merge upstream main into this branch
android/scripts/check.sh           # runtime compiles for ARM64 and x86-64, app and scripts check
android/scripts/build_arm64.sh && android/scripts/test_qemu.sh   # deeper: build and run ARM64 tests
```

The **Android - sync upstream** workflow does the same every day. It merges gow3_pc's main into the
branch `upstream-sync`, runs the check and opens a pull request. When upstream doesn't merge cleanly,
the job fails and lists the conflicting files; resolve them locally with `android/sync-upstream.sh`.

What keeps syncs easy:
- Android-only code is in new paths only: `android/`, `cpu/`, `third_party/FEX`,
  `.github/workflows/android-*`, `README.android.md`, `gpu/shim/gow3_cpu.h`,
  `gpu/shim/gow3_guest_call.h`, `src/guest_cpu.h` and `src/runtime_cpu.c`.
- Edits to shared files are small and guarded by `GUEST_CPU_NATIVE`, `__x86_64__` or
  `__aarch64__`: `src/probe.c`, `src/runtime.c`, `src/runtime.h`, `src/runtime_kernel.c`,
  `src/runtime_thread.c`, `src/runtime_pad.c` (Android pad input), `src/cpu_check.c`,
  `gpu/shim/gow3_write_log.cpp`, `.gitmodules`, and from Bloodborne-Android's aarch64 commit
  `gpu/shadps4/common/{signal_context.cpp,types.h}`, `core/libraries/avplayer/avplayer_{impl,state}.cpp`,
  `shader_recompiler/ir/passes/{flatten_extended_userdata_pass.cpp,srt.h}` and
  `video_core/renderer_vulkan/vk_{bind_helper.h,draw_pipe.h,pipeline_serialization.cpp,rasterizer.cpp,scheduler.cpp}`.
  `android/sync-upstream.sh --check` lists which of them upstream changed too.
- The game is prepared by upstream's own `run.py` and scripts, unchanged. New patches, scripts and
  options reach Android with no extra work.
- Settings the game writes stay in `gow3.ini`, as on PC. App-only settings go in `android.ini`.

If upstream adds code that calls game functions from host code (a new guest callback), route it
through `guest_cpu_call` (C) or `GuestCall` (C++, `gpu/shim/gow3_guest_call.h`). If it adds host
functions that game code jumps to directly, route them through `guest_cpu_host_function`.
`android/scripts/check.sh` catches the x86-only constructs that don't compile on ARM64. A new guest
callback compiles anywhere, though, so look for one when reviewing a sync.

## Credits and licenses

- [gow3_pc](https://github.com/FelipePrado0/gow3_pc) by Felipe Prado and contributors (GPL-2.0).
- [Bloodborne-Android](https://github.com/ducvd89/Bloodborne-Android) by ducvd89 (GPL-2.0): the
  FEXCore integration (masies), the Android app, the runtime rootfs, Turnip builds and patches.
- [bloodborne_pc](https://github.com/deadinside28/bloodborne_pc) by deadinside28,
  [shadPS4](https://github.com/shadps4-emu/shadPS4), [FEX](https://github.com/FEX-Emu/FEX) (MIT),
  [fexdroid](https://github.com/cobrabm12/fexdroid) (MIT; display and input bridges),
  [Mesa](https://mesa3d.org) (Turnip), [ps4-pkg-tools](https://github.com/xXJSONDeruloXx/ps4-pkg-tools)
  (GPL-2.0; see `android/runtime/ps4-pkg-tool.NOTICE`).

This project is not affiliated with Sony Interactive Entertainment or Santa Monica Studio.
God of War is a trademark of Sony Interactive Entertainment.
