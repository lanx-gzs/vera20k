# VeraZip — an unofficial Android build of VERA20K

This repository is a fork of [VERA20K](https://github.com/YuriPlanet/vera20k). It is **not**
VERA20K, and it is not produced, endorsed or supported by the VERA20K project. Per the upstream
[LEGAL.md](LEGAL.md) it uses its own name, icon and package id (`org.verazip`) everywhere except
in the copyright and license notices, which are kept unchanged.

Modified on 2026-11-14. Base commit: `2276a36cfda77fdb1c4943f7187d750859b50196`.

## What this fork adds

An Android launcher and file layer in front of the existing engine, so a phone install can be
managed without a desktop:

| Area | Files | What changed |
| --- | --- | --- |
| Entry point | `android/app/src/main/java/org/vera20k/LauncherActivity.kt` | Resource checker / file manager: directory browsing, search, sort by name or mtime, import a file, import a ZIP (extract + optional delete of the source), move/copy/rename/delete, first-run wizard when `*.mix` masters are missing. |
| In-game shell | `VeraActivity.kt`, `Ui.kt` | Windowed text-entry panel plus a floating key bar (Ctrl / Shift / Alt as latches, force-attack, text entry), drawn over the engine surface. |
| Viewers | `TextViewerActivity.kt`, `ImageViewerActivity.kt`, `FileKinds.kt` | Paged text viewer (48 KB pages, strict-UTF-8 probe with GB18030 fallback, page-scoped highlighting, editing up to 2 MB, streaming search) and a PNG viewer. |
| Campaigns and maps | `CampaignActivity.kt`, `Campaigns.kt`, `MapActivity.kt`, `MapPreview.kt` | Separate browse areas for campaigns and maps; map card reads `[Map]`, `[Basic]`, `[Waypoints]`, `[Infantry]`, `[Units]`, `[Structures]`, `[Aircraft]`, `[Terrain]`, `[Triggers]`, `[Tags]`, `[Houses]`, `[Lighting]` and draws a start-position schematic. Terrain tiles are **not** rendered: they live in the retail MIX archives, which this fork does not ship or decode on the app side. |
| Settings | `SettingsActivity.kt` | Toggles for size/hidden display, plus attribution for World-Altering Editor (GPLv3) whose documentation informed the map section list. |
| Engine-side hooks | `android/src/lib.rs`, `src/lib.rs`, `src/app/handler.rs`, `src/app/input/ime.rs`, `src/app/input/mod.rs`, `src/app/input/sticky_modifiers.rs`, `src/app/shell_saved_seeds.rs` | JNI registrations for text commit, folder pick results and modifier state; `sticky_modifiers` keeps Ctrl/Shift/Alt latched across frames; text commit extended to the save-name and seed-name fields and to the skirmish nickname field. |
| Packaging | `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`, `android/app/src/main/res/mipmap-*/ic_launcher*.png` | `applicationId` renamed to `org.verazip`, label `VeraZip`, seven activities declared, launcher icon added for five densities (drawn for this fork; no Electronic Arts artwork). |

## What is not here

- **No game files.** VERA20K and this fork contain none of Red Alert 2 / Yuri's Revenge's assets.
  They belong to Electronic Arts, no license here covers them, and shipping them together with
  this code would be piracy. You need your own installation.
- **No prebuilt APK.** The release artefacts are built from this tree; the corresponding source is
  this tree.
- **No upstream art.** The launcher icon is original to this fork.

## Licensing

The code is GPLv3, as upstream: see [LICENSE-GPL](LICENSE-GPL) and the additional terms in
[LEGAL.md](LEGAL.md), which this fork keeps verbatim. Where this fork's own files carry no other
notice they are released under the same GPLv3. Art under `art/` stays CC BY-NC-SA 4.0 and is not
used here.

## Building

Prerequisites: Rust 1.99 or newer, Android SDK with platform 36, NDK `28.2.13676358`, JDK 17.

Engine library for a phone (arm64):

```sh
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.2.13676358
export CARGO_BUILD_JOBS=1          # the linker needs well under 1 GB per job
cargo ndk -t arm64-v8a --platform 26 \
  -o android/app/src/main/jniLibs \
  build --profile fast -p vera20k-android --no-default-features --locked
```

APK:

```sh
cd android
./gradlew :app:assembleDebug
# android/app/build/outputs/apk/debug/app-debug.apk
```

Delete that output before rebuilding, otherwise Gradle can leave a stale, oversized APK in place.

The result is signed with the debug key, is arm64 only, and is not a store-ready build.
