[← README](../README.md)

# 4. Building and checking

## Build

Requirements: JDK 17+ and an Android SDK with platform 35 or 36 (on the dev machine `/usr/lib/android-sdk`, set in `local.properties`).

```bash
./build-apk.sh                      # debug APK → release/media-viewer-debug.apk
./build-apk.sh --variant release    # minified release APK (signed with the debug key for now)
./build-apk.sh --install            # build and install with adb
./build-apk.sh --help               # all options (--clean, --lint, --ci, …)
```

The script checks Java, the SDK and the Gradle wrapper first, and prints fix commands if something is missing.

## Checking without the Android SDK

`tools/pure-tests.sh` runs the pure-Kotlin tests and `tools/typecheck/run.sh` type-checks the whole app, both without Gradle or the SDK (docs/RESEARCH_2026-10.md §0). They download `kotlinc` and their jars on the first run, outside the repo. The pure tests include the MTP connection, mirror and transfers against `FakeMtpDriver` (`MtpConnectionTest`, `MtpMirrorTest`, `MtpTransferTest`: listing batches, the watchdog, the 2 GB budget, delete, uploads, events), with one-line stubs of `Log` and `SystemClock`.

## Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

The tests cover smart crop (the examples above), slideshow timing (the table above), natural sort and folder order, the random order (no repeats, restore after restart, adds and deletes), file-type detection and the GIF codec on synthetic files. `EditorTest` covers the syntax colouring of every family, span shifting, undo and the WCAG contrast of every theme; `BracketsTest` covers nesting, strings and comments, unmatched brackets, the scan bound and markup tags; `TextFileTest` covers charset, BOM and CRLF round trips, *Save as UTF-8*, changed-on-disk detection, names and drafts. `PlayerTest` covers lyrics (LRC, SRT, VTT), companion files and covers, chapters and cue sheets, the shuffle order, speed steps and the sleep fade; `MediaTagsTest` reads chapters and lyrics from MP3, M4B and Matroska files built in memory, including broken ones.

## Checking a real media folder

```bash
tools/check-media-folder.sh /path/to/folder
tools/check-media-folder.sh /path/to/folder --threads 6 --large-gif-mb 40
```

This runs the app's own folder and slideshow code on the JVM over **every file** in the folder:
- streaming listing and sorted insertion;
- type detection and magic sniffing;
- for every GIF, the first frame, the block scan and the 5-frame strip;
- slideshow timing for every item;
- a sequential pass and a full random cycle, with restore checks.

Results go to `build/reports/media-check/` (`summary.txt`, `gif-failures.tsv`, `types.tsv`). JaCoCo line coverage goes to `app/build/reports/coverage/test/debug/index.html`.

- `--threads` defaults to **6**. On a spinning-disk RAID, many parallel readers make reading seek-bound: 16 threads read only ~21 MB/s, while one stream reads ~300 MB/s. On SSDs, more threads are fine.
- Not covered on the JVM (needs a device or emulator): `ImageDecoder` stills, `MediaMetadataRetriever` video frames, ExoPlayer, Coil and the Compose UI.

Opt-in comparisons against Pillow are also available: `MV_GIF_REF=<json>` runs `RealGifTest` (metadata) and `MV_GIF_DUMP=<dir>` runs `GifDump` (pixels). See the test sources.

## Reading the app log

The app logs with the `MediaViewer` tag, including crashes: the crash screen (§3.5) logs them as *"Uncaught exception in …"*. Only a crash the handler couldn't take over goes to the system's `crash` buffer and the `AndroidRuntime` tag. With the device connected (`adb devices`):

```bash
# Everything from the app only, across restarts and crashes (filters by the app's uid)
adb logcat -v time --uid=$(adb shell pm list packages -U com.mediaviewer | sed -n 's/.*uid://p' | tr -d '\r')

# The same for the current process only; the app must be running, or pidof prints nothing
adb logcat -v time --pid=$(adb shell pidof com.mediaviewer)

# App messages and crashes only, from any process, survives app restarts
adb logcat -v time MediaViewer:V AndroidRuntime:E '*:S'

# The last crash, with its stack trace (also shown on the crash screen)
adb shell run-as com.mediaviewer cat files/last_crash.txt     # debug builds only
adb logcat -d -b crash                                         # crashes the handler didn't catch

# Native crashes (decoders, FFmpeg): tombstones
adb logcat -v time DEBUG:V libc:F '*:S'

# Clear old output before reproducing, then save it to a file
adb logcat -c
adb logcat -v time MediaViewer:V AndroidRuntime:E '*:S' | tee mediaviewer.log
```

To rule out stored state when a crash keeps coming back:
- The crash screen's **Reset settings and clear cache** is the first thing to try.
- The only disk cache is `meta.db` (GIF block scans and video metadata). Coil's disk cache is off and thumbnails live in memory only, so a restart already clears them.
- Settings, favourites, recent folders and the slideshow session are in DataStore (`files/datastore/settings.preferences_pb`); `error_log.txt` and `pending_trash.txt` are in the app's files. `adb shell pm clear com.mediaviewer` wipes all of it, and the storage permission with it.
- The in-app error log (snackbar *Details*) can be read without a crash: `adb shell run-as com.mediaviewer cat files/error_log.txt` (debug builds only).
