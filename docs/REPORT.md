# Media Viewer — work report

Date: 2026-10-24. Covers the work from the spec review to the current state of the app.

## 1. What was done

### Spec review (now [docs/SPEC.md](SPEC.md))

Problems found in the original task description, and how each was resolved:

| Problem                                                                                                | Resolution                                                                                                         |
| ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| §8.1 referred to a non-existent "§2.4"                                                                 | Pointed to §2, rule 4                                                                                              |
| Esc both opened the slideshow menu and left the slideshow                                              | Esc / `M` close the menu; Esc with the menu closed leaves                                                          |
| `MediaStore.createTrashRequest` for "delete without confirmation", but it always shows a system dialog | Own trash folder on the same volume (instant rename) + Undo                                                        |
| `getScaledFrameAtTime` is API 27+, minSdk was 26                                                       | Fallback to `getFrameAtTime` + scaling on API 26                                                                   |
| Animated AVIF needs API 31+; APNG isn't animated by the platform                                       | Documented; APNG shown as a still                                                                                  |
| LRU of 100 thumbnails smaller than the visible tiles on dense grids → reload loop                      | Cache grows to visible + prefetch tiles                                                                            |
| No "All files access" screen on many TV boxes                                                          | Detected; hint shown; documented                                                                                   |
| Mouse double-click = selection, but single click = open → click must wait                              | Click waits for the double-click timeout (touch doesn't); kept as an open question                                 |
| Fisher–Yates + cursor can't rebuild the order after files changed                                      | Keyed permutation `hash(seed, name)`: same state, stable under adds/deletes                                        |
| "Skip if not ready in min(R, 3 s)" vs "wait icon when loading the next item"                           | Automatic advance skips; manual navigation waits with Stop                                                         |
| Separate `:decoder` process for crash safety                                                           | Not needed: `MediaMetadataRetriever` already decodes in the media server process, and the GIF codec is pure Kotlin |
| GIF "frame offsets" suggested random access                                                            | GIFs here are diff-encoded (8,398 of 8,885 sampled frames are transparent patches); a sequential pass is required  |

Added on request during the work: slideshow **tap zones** (left ⅓ back, right ⅓ forward, center ⅓ menu), a **thumbnail-size slider** in the folder view, kept between runs; **two-finger swipes** in the slideshow and **no swipe on folder tiles** (a one-finger edge swipe also fired the system Back gesture); folder previews that **cycle on their own**; selected GIF/video tiles that **play in full**.

### The app

About 40 Kotlin files (~6,800 lines incl. tests). See README §1 for the full feature list with status and §3 for how it works. In short:

- **Folder view:** a streaming, sorted grid with live updates. Tiles are folder collages that cycle through their previews on their own, smart-cropped images, 5-frame GIF/video strips with scrubbing and auto-preview (selected ones play in full), and icons for other files. It has selection with drag-select, share, move/copy (with progress and Stop) and delete with undo; breadcrumbs, a fast-scroll thumb, and pinch / `Ctrl`+wheel / `+`/`-` / a slider for thumbnail size. Keyboard, mouse and TV-remote input are supported throughout.
- **Slideshow:** tap zones, two-finger swipes, zoom/pan, timing rules (loop / once / to the end), random order without repeats, a menu, preloading, transitions, and skipping of unreadable items. The screen stays on.
- **Robustness:** a wait indicator with Stop, watchdogs with abandonable calls, grouped error snackbars plus an error log, handling of removed storage, and a crash screen as the last fallback (details, reset settings, clear cache).
- **Persistence:** settings, recent folders with scroll position, opening the last available folder on launch, and slideshow resume (same item, same random order, video position).
- **FFmpeg:** the Media3 FFmpeg extension is bundled (prebuilt Jellyfin AAR) as a fallback for audio codecs the device lacks; release APK 2.4 → 8.3 MB.
- **Build:** `build-apk.sh` (adapted from ml2diseases-app; it also fixes a latent bug there, where `local` was used outside a function under `set -e`). Release builds are minified with R8 (8.3 MB APK incl. FFmpeg) and include a Baseline Profile.

### Verification

| Check                                                                                                          | Result                                                                      |
| -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Unit tests (smart crop, timing, natural sort, folder order, random order incl. restore, file types, GIF codec) | 25 tests, all pass                                                          |
| GIF metadata vs Pillow, 160 real files                                                                         | 160/160 identical (frames, duration, size); 3 broken files rejected cleanly |
| GIF pixels vs Pillow, first / middle / last frame of 25 real files                                             | 75/75 frames pixel-identical                                                |
| Full reference folder (`tools/check-media-folder.sh`, all ~29k files)                                          | see §4 below                                                                |
| Debug and release APK builds                                                                                   | pass                                                                        |
| On-device / emulator testing                                                                                   | **not done**: no device or system image was available                       |

Bugs found and fixed through these checks:
- The first LZW rewrite (40× "faster") skipped 2 bytes after each frame. It was caught by the pixel comparison, which only frame 0 had passed.
- A video retriever could be released by one thread while an abandoned decode still used it, a potential native crash.
- An invisible fast-scroll strip blocked taps on the rightmost tiles.
- Back from the slideshow lost the last viewed item.
- Sorted insertion and re-sorting of 30k entries ran on the main thread.
- Slideshow menu taps leaked through and closed the menu.
- The check script reported failure when `grep -v` filtered all output.

## 2. UX review: fixed in this round

| Issue                                                                 | Fix                                                                             |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| System Back in selection mode went up a folder and lost the selection | Back clears the selection first                                                 |
| On phones the size slider squeezed the breadcrumbs                    | Below 600 dp it opens from a button                                             |
| On touch screens, media file names were never visible                 | "Always show file names" setting (names also show on focus / hover / selection) |
| An unreadable folder showed an empty grid                             | "This folder can't be read" message                                             |
| Next/previous before the folder listing finished did nothing          | Waits with "Reading folder…" and Stop, then navigates                           |
| No sign that the slideshow was paused                                 | Pause icon in the corner                                                        |
| Tap zones are invisible and not discoverable                          | One-time overlay explaining them; its dismissing touch doesn't navigate         |
| Changing R in the menu only applied from the next item                | Applies at once                                                                 |
| Drag-to-select was limited to one screen                              | Auto-scrolls near the top and bottom edges                                      |
| Recent folders on the root screen were plain icons                    | Real collage previews                                                           |
| Move / Copy to… was missing                                           | Folder picker, progress bar, Stop, no overwrites                                |
| Preloading the next GIF decoded it twice                              | Preload stills only                                                             |
| Touch had only long press for selection                               | Two-finger tap selects too; it never opens a tile                               |
| The position was only visible in the menu                             | `12 / 29,102` shows for 1.5 s after each manual step                            |
| The menu's 5 s auto-close interrupted reading the file info           | No auto-close while the info is open                                            |
| The slideshow menu stayed open after Delete, covering the next slide  | Every menu button except File info closes the menu                              |
| Opening a folder list with an empty or unreadable folder (`Android/data`) crashed | A folder preview with no images shows the folder icon and item count |
| The same empty-range crash was possible for a 0-frame strip and the fast-scroll bubble of an empty folder | Guarded |
| Sharing, copying or dragging thousands of files could crash (Binder transaction limit) | Caught; "Too many files" message; a file without a content URI is left out |
| A slideshow video could blip sound before the mute setting applied    | The player starts silent                                                        |
| Any uncaught exception ended in the system "app stopped" dialog       | Crash screen in its own process: details, Copy, Reset settings and clear cache, Restart |

## 3. Suggestions and TODOs

Ordered by value; effort in brackets.

**Must do before a release**
1. **Test on real devices** [M]: a phone, a tablet, a Samsung phone in DeX (see §5) and an Android TV box, with the reference folder on internal storage and on an SD card. Check scrolling at 30k items, the first-tile time, slideshow resume, and TV D-pad focus. The JVM checks can't cover Compose, `ImageDecoder`, `MediaMetadataRetriever` or ExoPlayer.
2. **Instrumented UI tests** [M]: Compose tests for the tap zones, selection, the move picker and Back behaviour.
3. **Release signing** [S]: release builds are signed with the debug key for now.

**Performance**
4. **GIF strip cost on phones** [M]: ~470 ms per 16 MB GIF on a desktop JVM, likely 1–2 s on a phone. Options:
   - persist finished strips to a small disk cache (WebP, ~30 KB each), so revisiting a folder is instant;
   - decode at half resolution (sample every 2nd pixel when compositing; saves ~25 %);
   - give the visible tiles nearest the center priority.
5. **Generated Baseline Profile** [S]: record one with Macrobenchmark instead of the broad rule.
6. **Thumbnail disk cache** for stills and video frames [S], size-capped, cleared by "Clear thumbnail cache".

**Features**
7. **SAF / MediaStore fallback** [L]: skipped on purpose (no Play Store build planned); it would need a second file layer and a build flavor.
8. **Animated WebP strips** [M]: possible with a small WebP container parser plus libwebp decoding of each frame, or by playing the drawable once off-screen and sampling it.
9. **FFmpeg video decoding** [M]: the bundled extension (added, in the base APK) covers audio; video codecs still depend on the device. Revisit when Media3's FFmpeg video renderer becomes stable, or build FFmpeg with video decoders ourselves. Thumbnails would still use `MediaMetadataRetriever`, so a fallback frame grabber would also be needed.
10. **Slideshow filters** [S]: "GIFs only", "videos only", "longer than N s"; useful for a folder that is 95 % GIFs.
11. **Rename and new folder** [S] in selection mode and in the move picker.
12. **Search / filter by name** in the folder view [S].
13. **Slideshow over subfolders** (recursive) [M].

**UX polish**
14. A brief edge flash when swiping or tapping past the first item, since "previous" does nothing there [S].
15. Haptic feedback on long press and on entering selection mode [S].
16. **Drop files into the app** [S] in DeX and on Chromebooks: copy or move dropped content URIs into the open folder.

## 4. Full reference-folder check

<!-- FULL-CHECK -->
*(Running at the time of writing; results will be added here.)*
<!-- /FULL-CHECK -->

## 5. Samsung DeX readiness

A code review for DeX found nothing blocking: the activity was already resizable and declared the configuration changes a DeX switch causes, so it is neither restarted nor killed. The review found these gaps, now fixed (details in README §3.9):

| Issue                                                                                                        | Fix                                                                                                                                                          |
| ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Thumbnails decoded for a small window were upscaled, blurry, after maximising or moving to a monitor         | The cache remembers the decode width; tiles > 25 % wider are decoded again once the size has settled (300 ms)                                                |
| The folder view's top bar used only the status-bar inset, so it could sit under a desktop window's title bar | `statusBars ∪ captionBar`                                                                                                                                    |
| In DeX an unconsumed right-click can arrive as Back and go up a folder or step back in the slideshow         | Mouse Back within 500 ms of a right-button press is dropped (heuristic, to be checked on a device)                                                           |
| Mouse rules were gallery-style (click waits ~300 ms, double-click selects), unlike a desktop                 | "Mouse clicks" setting, auto = desktop in DeX / on Chromebooks: click focuses, double-click opens, `Ctrl` / `Shift` + click select, right-click context menu |
| The app opened in a window of DeX's choosing                                                                 | Samsung's `launchwidth` / `launchheight` = 0 → full screen; `<layout>` 100 % × 100 % elsewhere                                                               |
| On Android 9 the video paused when another window got focus                                                  | Video pauses on `ON_STOP`; the session is still saved on `ON_PAUSE`                                                                                          |
| Missing desktop shortcuts                                                                                    | Delete, `Ctrl` + C, PgUp / PgDn / Home / End move the focus, wheel steps in the slideshow                                                                    |
| No cursor or tooltip feedback                                                                                | Hand cursor on tiles, breadcrumbs and buttons; tooltips on icon buttons                                                                                      |
| Files couldn't be handed to other apps by mouse                                                              | Drag a tile (or the selection) out of the window                                                                                                             |
| A windowed slideshow isn't full screen, with no explanation                                                  | One-time "Maximise the window" hint                                                                                                                          |

Added on request in the same round:
- **Automatic thumbnail size** from the window width (about 3 columns on a phone, one per ~200 dp on wide windows) until the user picks one.
- **Storage permission fallback:** the App info page opens when the "All files access" screen is missing, and after "Don't ask again" on Android 8–10, where the permission dialog no longer appears.

Checked: debug build and all unit tests pass. **Not checked on a DeX device**: launch size, the right-click guard, the caption-bar inset, drag out and tooltips on hover.
