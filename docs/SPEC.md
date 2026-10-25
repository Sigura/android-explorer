# Media Viewer for Android — original specification

This is the task description and product spec the app was built from (kept for reference).
The current description of the app is in [../README.md](../README.md); where the two differ, the README describes what the app does.


---

## 0. Building and checking

### Build

Requirements: JDK 17+ and an Android SDK with platform 35 or 36 (on the dev machine: `/usr/lib/android-sdk`, set in `local.properties`).

```bash
./build-apk.sh                      # debug APK → release/media-viewer-debug.apk
./build-apk.sh --variant release    # minified release APK (signed with the debug key for now)
./build-apk.sh --install            # build and install with adb
./build-apk.sh --help               # all options (--clean, --lint, --ci, …)
```

The script checks Java, the SDK and the Gradle wrapper first, and prints fix commands if something is missing.

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

These cover smart crop (§4.2 examples), slideshow timing (§5.1 table), natural sort, the random order (§6, incl. restore after restart), file-type detection and the GIF codec.

### Checking a real media folder

```bash
tools/check-media-folder.sh /path/to/folder
tools/check-media-folder.sh /path/to/folder --threads 6 --large-gif-mb 40
```

This runs the app's own folder and slideshow code on the JVM over **every file** in the folder: streaming listing and sorted insertion, type detection and magic sniffing, the GIF block scan and 5-frame strip decode for every GIF, slideshow timing for every item, and a full random cycle with restore checks. Results go to `build/reports/media-check/` (`summary.txt`, `gif-failures.tsv`, `types.tsv`), and JaCoCo line coverage to `app/build/reports/coverage/test/debug/index.html`.

- `--threads` defaults to **6**. On a spinning-disk RAID, many parallel readers make it seek-bound: 16 threads read only ~21 MB/s, while one stream reads ~300 MB/s. On SSDs, more threads are fine.
- Not covered on the JVM (needs a device or emulator): `ImageDecoder` stills, `MediaMetadataRetriever` video frames, ExoPlayer, Coil and the Compose UI.

---

## 1. Technology choice

**Kotlin is the right choice.** It is Google's recommended language for Android, and the modern Android UI toolkit (Jetpack Compose) is Kotlin-only.

| Area | Choice | Why |
|---|---|---|
| Language | Kotlin 2.x | Official Android language, coroutines, null safety |
| UI | Jetpack Compose + Material 3 | Lazy grids with stable keys recompose only the items that changed, never the whole list |
| Concurrency | Kotlin Coroutines + Flow | Folder scans and thumbnail jobs run off the main thread, and each finished item is sent to the UI as soon as it is ready |
| Images / GIF / WebP | Coil 3 (`coil-gif`, `coil-video`) | Animated GIF/WebP/HEIF, video frame decoding, memory and disk cache |
| Video playback | AndroidX Media3 (ExoPlayer) | MP4, MKV, WebM and others; hardware decoding |
| AVI and exotic codecs | Media3 + FFmpeg extension (optional module) | ExoPlayer has no AVI extractor for every codec, so this is the fallback |
| Video frames for thumbnails | `MediaMetadataRetriever.getScaledFrameAtTime` | Fast, scaled frame extraction |
| Settings | Jetpack DataStore (Preferences) | Async and type-safe |
| DI | Hilt (or Koin) | |
| Icons | Material Symbols (Rounded) | Complete, free, and matches Material 3 |
| Min / target SDK | minSdk 26 (Android 8), targetSdk latest | Animated `ImageDecoder` on 28+, with a fallback for 26–27 |
| Form factors | Phone, tablet, foldable, Chromebook, Android TV | One APK, `leanback` feature marked as optional |

### Storage access

The app is a file browser, so it needs real folders and must be able to delete files:

- **Primary:** `MANAGE_EXTERNAL_STORAGE` ("All files access"). The app explains this once on first launch and opens the system settings screen.
- Some devices (many Android TV boxes, some OEM builds) have no "All files access" settings screen. If the intent can't be resolved, go straight to the fallback. On Android 8–10 the plain `READ/WRITE_EXTERNAL_STORAGE` runtime permissions are used instead.
- **Fallback:** if the user declines (or for a Play Store build), use the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) for folders the user picks, plus `MediaStore` for images and video.
- Removable SD cards and USB OTG drives appear as roots.

---

## 2. Core principle: never block, never repaint everything

These rules apply everywhere in the app:

1. **Folder listing is streamed.** A scanner coroutine on `Dispatchers.IO` sends entries in small batches (for example every 16 ms or every 50 entries, whichever comes first) through a `Flow`. The UI appends them to the list. Existing items are never rebuilt.
2. **Stable keys.** Every grid item uses its absolute path as its `key`. Adding, removing or updating one item recomposes only that item.
3. **Thumbnails are per item.** Each tile has its own loading state (`Placeholder → Loading → Ready | Failed`). When a thumbnail finishes, only that tile updates.
4. **Thumbnails load only for visible items.**
   - No thumbnail work starts while the grid is scrolling (`LazyGridState.isScrollInProgress`). Work starts when scrolling stops (~150 ms debounce).
   - Only visible tiles, plus one row above and one below, are requested.
   - When a tile leaves the screen, its job is cancelled.
5. **Limited parallelism.** Thumbnail decoding uses `Dispatchers.IO.limitedParallelism(N)`, where N = CPU cores / 2, minimum 2. This keeps scrolling at 60/120 fps.
6. **Bounded cache.** An in-memory LRU cache holds thumbnails (and frame strips) for at most **100 items**, the most recently used. If the visible tiles plus the prefetch rows exceed 100 (very dense grid on a large tablet or TV), the capacity grows to that number, or the cache would evict tiles that are still on screen and reload them in a loop. Coil's disk cache can be kept, but it is size-capped (for example 256 MB) and can be cleared in settings.
7. **Cheap first.** For folder previews, choose the files that are fastest to decode first (see §4.1).
8. **Live updates.** A `FileObserver` (or a `MediaStore` `ContentObserver`) on the open folder adds or removes single items without rescanning.

### 2.1 Slow operations, junk and read errors

Real storage contains junk files, broken folders, slow SD cards and USB drives that hang or get unplugged. The app must never freeze, never crash and never ask the user to confirm an error.

#### Slow operations: a wait icon and a way out

- Any **user-started** operation that takes longer than **~300 ms** shows a **wait indicator** with a **Back / Stop button**. Examples: opening a folder, loading the next slideshow item, delete, bulk move/copy, restoring a session, checking a volume.
  - The indicator is non-modal: a spinner (or a progress bar when progress is known), a short label (*"Reading folder…"*, *"Loading 196 MB GIF…"*) and the elapsed time after 3 s.
  - **Stop** works from the on-screen button, system Back, Esc or the TV Back key. The UI goes back to its previous state **immediately**, without waiting for the operation to actually finish.
- **How cancelling works:** use coroutine cancellation. Some calls ignore cancellation, for example `File.listFiles()` on a hung USB drive or `MediaMetadataRetriever` on a corrupt file. Run those through `runInterruptible` on a dedicated I/O pool, give them a watchdog timeout, and **abandon** the result if Stop was pressed. A stuck thread must never hold up the UI or the next operation.
- **Folder listing:** stopping keeps the entries already loaded. A chip at the top says *"Listing stopped — tap to continue"*.
- **Background work never shows a wait icon.** Thumbnails, frame strips and metadata scans just show the tile placeholder. Watchdog limits:
  - thumbnail or frame decode: 5 s;
  - metadata or block scan: 3 s;
  - magic-byte sniff: 1 s.

  When a limit is hit, the tile gets the `broken_image` / `hourglass_disabled` icon, and the file is not retried automatically during this session. Opening the file manually retries it.
- **Slideshow:** if the next item isn't ready within R (or 3 s, whichever is shorter), skip to the following item. Stop at the first item that can be shown. If nothing can be shown, the slideshow goes back to the folder view with a message.

#### Read errors: ignore and notify, no confirmations

- Read and decode errors are **never shown as dialogs** and **never need confirmation**. This includes I/O errors (`EIO`), permission denied, truncated or corrupt data, unsupported codecs, decoder exceptions, `OutOfMemoryError` while decoding a bitmap, and files that disappear.
- **Ignore and continue:** the item gets a "broken" or "unknown" icon in the grid, the slideshow skips it, and folder previews pick another candidate. Selection mode, delete and share still work on broken files, so the user can clean up junk.
- **Notify without interrupting:** show an auto-dismissing snackbar at the bottom (~4 s). It never steals focus and never pauses the slideshow.
  - Errors are **grouped**: within a 2 s window, errors from the same folder become one message (*"12 files couldn't be read"*), so a folder full of junk doesn't flood the screen.
  - Tapping the message (optional) opens an **error log** screen with path, reason and time. The log keeps the last 200 entries, in memory and in a small file.
- **Removed volume** (SD card or USB unplugged, folder deleted while open): show one message (*"Storage removed"*). Then go up to the nearest existing parent folder, or to the root. A running slideshow stops the same way. The session is kept, so it can resume when the drive comes back (§7.3).
- **Failed operations the user started** (delete, move): show a snackbar with the reason. The item stays where it was. No retry dialog.
- **Native decoder crashes:** some codecs crash in native code on malformed files. Video frame extraction and the GIF block scanner run in a separate `:decoder` process (bound service). If that process dies, the file is marked broken, the process restarts, and the UI process is not affected.
- **Logging:** every ignored error is logged with the `MediaViewer` tag, so problems can be diagnosed without being shown to the user.

---

## 3. Supported content

| Type | Extensions (not exhaustive) | Tile behaviour |
|---|---|---|
| Static images | jpg, jpeg, png, bmp, heic, heif, avif, webp (static), svg (optional) | Shown as is |
| Animated images | gif, webp (animated), apng, avif (animated) | Frame scrub (see §4.3) |
| Video | mp4, m4v, mkv, webm, avi, mov, 3gp, ts | Frame scrub (see §4.3) |
| Folder | — | Preview collage (see §4.1) |
| Other / unknown | — | Material Symbols icon by type: `description` (text/pdf), `audio_file`, `folder_zip` (archive), `apk_document`, `draft` (unknown) |

The type is detected by extension first (fast). A content check (magic bytes) runs only when the extension is missing or doesn't match what the file can decode.

Real folders are messy. The scanner and decoders must handle all of these, which all appear in the reference folder (§8.1):

- **Double extensions:** `name.mp4.gif` and `name.webm.gif` are GIFs. Only the **last** extension counts.
- **Junk extensions:** `name.webm'`, `name.php?id=300737`, `name.mp4,gif`. Fall back to magic-byte sniffing, which costs one 16-byte read.
- **Partial downloads:** `*.crdownload` and `*.part`. Show them as "incomplete" files with a `downloading` icon and don't try to decode them, but still allow delete.
- **Broken files:** 0-byte or truncated GIFs. Show a `broken_image` icon instead of a thumbnail, never crash or retry in a loop, and skip them in the slideshow.
- **Hidden metadata:** `.directory` (Dolphin), `.ipynb_checkpoints/`, `.nomedia`, `.thumbnails/`. Hide them by default (see settings).
- **File names** up to 255 bytes, including non-ASCII characters. Truncate names in the middle with an ellipsis so the extension stays visible.

---

## 4. Folder view (main screen)

A full-screen grid with minimal UI. The default is edge-to-edge with transparent system bars. A thin top bar shows the current path as clickable breadcrumbs, and it hides on scroll.

The top bar also has a small **thumbnail-size slider**. It steps through the same sizes as pinch, `Ctrl` + wheel and `+` / `-` (from about 72 dp to 480 dp per tile), and all of them change the same "Grid density" setting (§9). The size is saved at once and kept between runs (§7.1), so the folder view always reopens at the last chosen size.

### Item order

1. **Back tile** (`..`) is always the first item in any subfolder. It is **not** shown at the root.
2. **Folders**
3. **Media files** (images, animated images, videos)
4. **Other files** (icons)

Each group is sorted by name using natural sort, so `img2` comes before `img10`. Other sort orders are in settings (§9).

### Root

The root screen lists storage volumes: internal storage, SD card, USB drives, and favourite or pinned folders. It has no back tile. The system Back action at the root exits the app (with a "press again to exit" toast).

### 4.1 Folder tiles

Folder tiles are **large**, similar to Dolphin's folder previews (see the reference image in the original request).

- **Layout:** a folder shape with up to **4 thumbnails** of media found inside, slightly rotated and overlapping like photos on a table, with the folder name below.
- **Choosing the first previews quickly:** as soon as the folder's file list arrives (a fast directory read, no decoding), pick the files that will produce a good thumbnail fastest:
  - prefer static images between ~50 KB and ~3 MB (not tiny icons, not huge 50 MP RAWs);
  - then GIF/WebP. The cost of a GIF's **first frame does not depend on file size**: only the header and first image block (usually < 200 KB) are read, even for a 196 MB GIF;
  - then video, which needs a frame decode (~20–80 ms per frame for H.264, more for 2048×2048 streams).
- Recurse at most **2 levels deep** and stop after the first ~20 candidates, so huge trees don't stall the scan.
- **Moving through the folder's contents:**
  - **By default:** the 4 slots move to the next group of 4 images on their own, about every 3 s, but only while the grid is not scrolling. Tiles are staggered, so they don't all change at the same moment. Folders with 4 or fewer candidates stay still.
  - **Mouse:** when the pointer hovers over the tile, its horizontal position picks the group.
  - **D-pad / keyboard:** while the tile has focus, Left/Right with `Shift` (or long-press Left/Right on a TV remote) steps through the groups.
  - **Touch:** no swipe. A horizontal swipe on a folder tile does nothing, because a one-finger swipe from the screen edge is the system Back gesture and would fire both.
  - When hover or keys stop, the automatic cycle carries on.
- Empty folders, or folders with no media, show a plain large folder icon and an item count.

### 4.2 Static image tiles

The image is shown **as is**, using **smart crop**:

- Compare the image's aspect ratio with the tile's. The share of the image that cropping would cut off is `lost = 1 − min(a_img / a_tile, a_tile / a_img)`, where `a = width / height`.
- If `lost ≤ 10 %`: **crop**, so the image fills the tile, centered (`ContentScale.Crop`, `Alignment.Center`).
- If `lost > 10 %`: **fit**, so the whole image is visible, **centered**, with the empty bands in the tile background colour (`ContentScale.Fit`, `Alignment.Center`).
- Examples for a 4:3 tile:
  - a 3:2 photo loses 11 % → fit;
  - a 1:1 image loses 25 % → fit;
  - an 800×600 frame loses 0 % → crop;
  - an 836×470 GIF (16:9) loses 25 % → fit;
  - a 2048×2048 video loses 25 % → fit;
  - a 1.4:1 image loses 5 % → crop.
- The decision uses only the image size, which is read from the header (`BitmapFactory` `inJustDecodeBounds`, the GIF logical screen size, video metadata), **before** decoding. The bitmap is then decoded straight to the size it is drawn at.
- The same rule applies to the frames of animated and video tiles (§4.3) and to the images in folder previews (§4.1).
- The 10 % threshold and the mode (Smart / always Crop / always Fit) are in settings (§9).

### 4.3 Animated image and video tiles

These work like folder tiles, but show **one frame at a time in the whole tile**, while folder tiles show 4 images together. Frames use the same smart crop rule as images (§4.2).

- **5 equidistant frames** are extracted, at 10 %, 30 %, 50 %, 70 % and 90 % of the duration. This avoids black first frames.
- **Scrubbing:** mouse hover position, or a horizontal swipe inside the tile, picks one of the 5 frames.
- **Auto preview:** while the pointer rests on the tile (or the tile has D-pad focus), the 5 frames cycle repeatedly. The step adapts: about 400 ms per frame, a bit longer for long videos.
- **Selected tiles play:** in selection mode, a selected GIF or video plays whole in the tile, looped and muted, regardless of R. At most 4 tiles play at once (hardware video decoders are few); the rest keep their frames until a slot frees up. Video uses a `TextureView`, so the tile's clip, rounding and focus scale apply.
- **Badge:** a small corner badge shows the duration (`1:23`) for video, or `GIF` for animated images.
- Frames are generated **only when the tile is visible and the grid is not scrolling**. Until then, the tile shows the first frame (or a placeholder).
- The 5-frame strip counts as **one** entry in the 100-item LRU cache.

**Building the frame strip, per format:**

- **Video (MP4/MKV/WebM/AVI):** `MediaMetadataRetriever.getScaledFrameAtTime(t, OPTION_CLOSEST_SYNC, w, h)` (API 27+; on API 26 use `getFrameAtTime` and scale down). Five keyframe seeks are fast. Duration comes from the container metadata, so no decoding is needed.
- **GIF:** GIF has **no index and no seeking**. Frame count and total duration are not stored in the header. So:
  1. **Block scanner (no decoding):** walk the GIF block structure, skipping the LZW image data by its length bytes. This collects the frame count, each frame's delay (total duration) and the byte offset of each frame. It only reads the file and does no decoding work. The result (`frameCount`, `durationMs`, offsets) is cached per file (path + size + mtime) in a small Room/SQLite table. The table is separate from the 100-item bitmap cache and holds only a few bytes per file.
  2. **Single sequential decode pass:** decode once from the start and keep only the 5 target frames, downscaled straight to tile size. Never hold all frames in memory: one 836×470 frame is about 1.5 MB as ARGB, and 240 of them would be 360 MB.
  3. **Large-file guard:** for GIFs above a threshold (for example 40 MB, about 10 % of the reference folder), show only the first frame until the user actually hovers or swipes on the tile. Only then start the full pass, and show a thin progress ring while it runs.
- **Animated WebP / AVIF:** `ImageDecoder` / `AnimatedImageDrawable` (API 28+; AVIF needs API 31+). Same rule: one pass, keep 5 frames.
- **APNG:** the platform decoders show only the first frame. Treat APNG as a static image unless a dedicated decoder is added later.
- GIF frames cannot be decoded on their own (a frame can be a partial patch over earlier frames, depending on its disposal method), so the stored offsets do not remove the need for the sequential pass. They are used to read the first frame quickly and to report progress.

### 4.4 Other files

Each file shows an icon for its type (see §3) with its name and size below.

### 4.5 Gestures and input (folder view)

| Action | Touch | Mouse | Keyboard | TV remote |
|---|---|---|---|---|
| Open (folder → enter, file → slideshow) | Tap | Click | Enter | OK |
| Enter selection mode, selecting this item | Long press | Double-click or right-click | `Space` | Long-press OK |
| Toggle selection (in selection mode) | Tap, double tap or long press | Click or double-click | `Space` / Enter | OK |
| Scroll | One- or two-finger drag up/down | Mouse wheel | Arrow keys, PgUp/PgDn, Home/End | D-pad |
| Change grid density (thumbnail size) | **Pinch**, or the size slider in the top bar | `Ctrl` + wheel, or the size slider | `+` / `-` | Size slider |
| Scrub video/GIF preview | Horizontal swipe inside tile | Hover | `Shift` + ←/→ | Long-press ←/→ |
| Move through folder previews | — (cycles on its own) | Hover | `Shift` + ←/→ | Long-press ←/→ |
| Go up a folder | Back tile, system Back gesture | Back tile, mouse back button | Backspace / `Alt` + ← | Back |

All items are focusable, and focus is clearly visible (scale-up plus outline) for TV and keyboard use.

### 4.6 Selection mode

- **Enter** by long press, double-click or right-click on any item. That item becomes the first selected one.
- In selection mode, tap or click (single or double) **toggles** an item.
- A contextual top bar shows the count and actions: **Select all**, **Invert**, **Delete**, **Share**, **Move/Copy to…**, **Cancel**.
- Drag-to-select: after a long press, drag across tiles to select a range (as in Google Photos).
- Selection mode **ends automatically** when nothing is selected or when the user changes folder.

### 4.7 Returning from the slideshow

When the user returns to the folder view, the grid is **scrolled so the last-viewed element is visible and focused**, even if the slideshow moved far beyond where it started.

---

## 5. Slideshow view

Opens on the tapped file. It plays every media file in the current folder (folders and non-media files are skipped). The view is full screen and immersive: system bars hidden, black background.

### 5.1 Timing

- **R = 14 s** by default. R can be set from 1 s to 40 s in the slideshow menu (§6) and in settings. The value is saved.
- **Static images:** shown for R.
- **Animated images and videos**, with duration `d`:
  - `d < R/2`: **loop** it until R has passed.
  - `R/2 ≤ d ≤ 3R/2`: play it **once**, then go to the next item.
  - `d > 3R/2`: play it **to the end**. It is never cut. A thin progress bar at the bottom shows that this item is longer than R.
- Video sound is **muted by default**. A mute toggle is in the menu, and its state is saved.

Examples with R = 14 s (so R/2 = 7 s and 3R/2 = 21 s), using clips from the reference folder:

| Item | Duration `d` | Behaviour | Time on screen |
|---|---|---|---|
| Short GIF loop, 6 frames | 0.6 s | loops ~23× | 14 s |
| GIF, 13 frames | 1.1 s | loops | 14 s |
| GIF, 124 frames | 10.3 s | plays once | 10.3 s |
| GIF, 240 frames (very common) | 20.0 s | plays once | 20 s |
| GIF, 256 frames | 32.0 s | plays to the end, progress bar | 32 s |
| MP4, 2048×2048 @ 32 fps | 10.0 s | plays once | 10 s |
| MP4, 720×1080 | 152 s | plays to the end, progress bar | 152 s |

GIF duration comes from the block scanner (§4.3), not from decoding. Delays of 0–1 centiseconds are treated as 10 cs (100 ms), the way browsers do it, so `d` matches what the user actually sees.

### 5.2 Navigation

The screen is split into three vertical **tap zones** of equal width:

```
┌───────────┬───────────┬───────────┐
│  left ⅓  │ center ⅓ │  right ⅓ │
│ previous  │   menu    │   next    │
└───────────┴───────────┴───────────┘
```

| Action | Touch | Mouse / keyboard | TV remote |
|---|---|---|---|
| Previous item (if one exists, otherwise nothing happens) | Tap the **left ⅓**, or two-finger swipe left | Click the **left ⅓**, ← / mouse back button | ← |
| Next item (after the last one, wraps to the first) | Tap the **right ⅓**, or two-finger swipe right | Click the **right ⅓**, → / Space | → |
| Open slideshow menu | Tap, double tap or long press in the **center ⅓** | Click, double-click or long click in the **center ⅓**, or `M` | OK / Menu |
| Close slideshow menu | Tap outside, system Back | Esc / `M` | Back |
| Zoom / pan an image | Pinch / drag (when zoomed, one- and two-finger drags pan instead of changing item) | `Ctrl` + wheel | — |
| Pause / resume timer | — | `P` | Play/Pause |
| Back to folder view | System Back | Esc (when the menu is closed) / Backspace | Back |

Tap zones:

- A tap or click in a side zone acts **at once**. It does not wait for a possible double tap, because double tap and long press only mean something in the center zone.
- Double tap and long press open the menu **only in the center ⅓**. In the side zones, a double tap is simply two taps (two items forward or back), and a long press does nothing.
- When the image is zoomed in, a tap in any zone still navigates, and a drag pans.
- While the menu is open, a tap outside the menu closes it and does not navigate.
- The zones are always left = back and right = forward. "Invert swipe direction" does not change them.

Swipes use **two fingers**. A one-finger swipe from the screen edge is the system Back gesture (gesture navigation), and the app would step as well, so one finger never changes the item. Two fingers either pinch or slide: if the distance between them changes by more than 10 % first, it is a zoom; if they first move sideways together, it is a swipe.

Swipe direction follows the original request. An **"Invert swipe direction"** option is in settings for users used to the common gallery behaviour (swipe left = next).

### 5.3 Behaviour

- **The screen stays on** during the slideshow: `FLAG_KEEP_SCREEN_ON` is set on the window while the view is active, so no screen timeout and no dimming. The flag is cleared when leaving the view.
- The next item is **preloaded** (decoded or buffered) in the background so the switch is instant. Transition: a short crossfade (configurable: none / fade / slide).
- Files that fail to decode, or are not ready in time, are skipped automatically, with a grouped snackbar (§2.1).

---

## 6. Slideshow menu (overlay)

A semi-transparent bottom sheet / overlay. The slideshow timer pauses while it is open. It contains:

1. **Interval slider** for R, from 1 s to 40 s in 1 s steps, with the current value shown. It applies immediately and is saved.
2. **Random order** toggle. When on, items are shown in random order with **no repeats until every item has been shown once**. Then a new random cycle starts.
   - Implementation: when random order is turned on, pick a new `seed`. Each file gets a pseudo-random sort key `hash(seed, file name)`, and the permutation is the media list sorted by that key, with the current item pinned first. Storing only `seed`, the pinned first file and the current file is enough to rebuild exactly the same order after an app restart (§7). Unlike a Fisher–Yates shuffle with an index cursor, the keyed order stays the same when files are added or deleted while the app was closed: every remaining file keeps its place.
   - The new cycle uses a new seed. If its first item equals the last item of the previous cycle, swap it with the next one so the same file never appears twice in a row.
   - **Swipe back** in random order goes to the previously *shown* item (back through the permutation), not to the previous file by name.
   - **Files added** while a cycle runs are inserted at random positions in the not-yet-shown part. **Deleted or missing** files are skipped silently.
   - Turning random order **off** continues in name order from the current item. Turning it **on** starts a new cycle from the current item.
   - The menu shows progress for the current cycle, for example `1,284 / 29,102`.
3. **Delete file** deletes the current file **without a confirmation dialog**, then moves to the next item.
   - *Polish:* an **"Undo"** snackbar is shown for 5 s. The file is renamed into a hidden app trash folder (`.mediaviewer-trash/`) on the **same volume**, so the move is instant even for a 196 MB GIF, and is only removed permanently after the snackbar closes. This keeps the "no confirmation" speed while protecting against mistakes.
   - `MediaStore.createTrashRequest` (API 30+) is **not** used: it always shows a system confirmation dialog, which breaks the "no confirmation" rule. It is only used in the SAF/Play-Store fallback, where there is no other way to delete.
4. **Back to folder view.**

Additional items (polish):

5. Play / Pause the slideshow.
6. Mute / unmute video.
7. File info: name, resolution, size, date, duration, frame count.
8. Share.

The menu opens from the center tap zone (§5.2) and closes on tap outside it, Back, or after ~5 s without input.

---

## 7. Persistence and session restore (enhancement)

The app should feel like it was never closed.

### 7.1 Settings survive restarts

Every setting in §9 and every value changed in the slideshow menu is stored in **Jetpack DataStore** as soon as it changes: R, random order, mute, grid density, sort, theme, swipe direction, and so on. Nothing needs a "Save" button. The values are read once at startup, before the first frame is drawn, so the UI never flashes default values.

### 7.2 Recent folders

- Keep a list of the **last ~20 opened folders**, most recent first. For each one store the path, the last time it was opened, the scroll anchor (the key of the first visible item plus its offset) and the last-viewed item.
- The root screen shows a **"Recent"** row above the storage volumes, with large folder tiles (§4.1). Long-press removes an entry.
- **On launch**, open the **first recent folder that is still available**. A folder is skipped if it was deleted, if its SD card or USB drive is unplugged, or if permission was revoked. The check runs off the main thread, with a short timeout per entry so a slow USB drive can't block startup. If no recent folder is available, show the root screen.
- The folder opens **scrolled to its saved position**. The scroll is restored as soon as the anchor item streams in, without waiting for the full listing.

### 7.3 Resume the slideshow

- While the slideshow runs, save `SlideshowSession` on every item change (no more than about once per second): folder path, current file, random order on/off, `seed`, `cursor`, the list identity (item count plus a hash of the sorted names) and the playback position inside long videos.
- If the app was **in the slideshow when it last stopped**, the next launch goes **straight back into the slideshow** at the last seen item. This covers being closed, killed by the system, a reboot or a battery swap.
  - A long video continues from its saved position.
  - **Random order continues with the same permutation**, so items already shown in this cycle are not shown again.
  - If the folder changed meanwhile (files added or deleted), keep the order and apply the add/skip rules from §6.
  - If the last item no longer exists, continue with the next one in the order.
- If the slideshow was closed normally (the user went back to the folder view), the app opens in the folder view, scrolled to the last seen item (§4.7).
- **Escape hatch:** for 3 s after the slideshow is restored, a small toast says *"Resumed slideshow — tap to go to folder"*.

### 7.4 Storage format

| Data | Where | Size |
|---|---|---|
| Settings | DataStore Preferences | < 1 KB |
| Recent folders, slideshow session | DataStore (Proto or JSON) | < 10 KB |
| GIF/video metadata (frames, duration, offsets) | Room table, key = path + size + mtime, pruned of entries not opened for 90 days | ~100 B/file |
| Thumbnails | memory LRU (100) + optional size-capped disk cache | capped |

---

## 8. Performance targets

- First folder tiles appear in **< 100 ms** after opening a folder (names and icons first, thumbnails after).
- 30,000-file flat folder (see §8.1): the list keeps streaming in with no visible jank. Scrolling stays at the display refresh rate.
- No main-thread disk I/O (checked with `StrictMode` in debug builds).
- Memory: thumbnail cache ≤ 100 items, and bitmaps are always decoded at tile size, never full resolution.
- Baseline Profiles are included for fast cold start and smooth scrolling.
- With a session to restore (§7.3), the slideshow shows its first image **< 1 s** after a cold start.

### 8.1 Reference test folder

The app is tested against a real media folder on the dev machine. Its profile, measured on 2026-10-24:

| Property | Value |
|---|---|
| Layout | **one flat folder, ~29,150 files**, no subfolders except a hidden `.ipynb_checkpoints` |
| Total size | 338 GB |
| GIF | 27,793 files (95 %), median 10.9 MB, p90 25.5 MB, max 196 MB |
| GIF properties (sample) | mostly 836×470 (also 250×455 … 1200×675), 5–256 frames, 0.5–32 s, very often exactly 240 frames / 20 s |
| MP4 | 571 files, H.264, 720p up to 2048×2048, 16–60 fps, 2–152 s, median 7.3 MB, max 140 MB |
| JPEG / PNG | 525 / 213 files, median 0.5 MB / 3.2 MB |
| Junk | 24 × `.crdownload`, some 0-byte GIFs, odd extensions, html/pdf/csv/json/sh |
| Dates | mtime spread over 2020–2026 (sorting by date makes sense) |

What this folder tests:

- **Streaming listing:** ~29k entries must appear progressively. The first screen of tiles must show up before the scan finishes, and the scroll bar grows as items stream in.
- **Fast scroll:** a draggable scrollbar thumb is required. Flinging through 29k items by finger alone is not usable. While dragging, show a letter/date bubble.
- **No GIF-decoding storms:** flinging from top to bottom must start almost no decodes (§2, rule 4). The largest GIFs must not cause memory spikes (§4.3).
- **Random order at scale:** a 29k-item permutation plus a saved cursor, restored after restart (§7.3).
- **Slow storage:** if a folder like this is on an SD card or USB OTG drive, reads are slow. A first-frame-only read keeps tiles responsive.

---

## 9. Settings

A settings screen (gear icon in the top bar, or `,` on the keyboard):

- Slideshow interval R (1–40 s, default 14)
- Slideshow transition (none / fade / slide)
- Invert swipe direction
- Random order (same switch as in the slideshow menu)
- Restore last session on launch: off / folder only / folder and slideshow (default)
- Large-GIF threshold for deferred frame strips (default 40 MB)
- Video muted by default
- Grid density (also changed by pinch, `Ctrl` + wheel, `+` / `-` and the size slider in the folder view's top bar)
- Image tile mode: **Smart** (default: crop if ≤ 10 % is lost, otherwise fit centered) / always Crop / always Fit; Smart threshold 0–30 % (default 10 %)
- Sort: name / date / size / type, ascending or descending; folders first (on/off)
- Show hidden files (`.`-prefixed) and `.nomedia` folders
- Theme: system / light / dark / AMOLED black
- Clear thumbnail cache
- Favourite folders, shown on the root screen

---

## 10. Suggested project structure

```
app/
  src/main/java/.../mediaviewer/
    MainActivity.kt
    ui/
      browser/        # FolderScreen, FolderTile, MediaTile, BackTile, SelectionBar
      slideshow/      # SlideshowScreen, SlideshowMenu, player wrappers
      settings/
      theme/
      input/          # unified gesture / mouse / key / D-pad handling
    domain/
      model/          # FileEntry, MediaType, FolderPreview, FrameStrip
      usecase/        # ListFolder, BuildFolderPreview, ExtractFrames, DeleteFile
    data/
      fs/             # streaming scanner, FileObserver, volume roots, SAF fallback
      thumbs/         # thumbnail scheduler, LRU(100), visibility/scroll gating
      settings/       # DataStore: settings, recent folders, slideshow session
      meta/           # Room: GIF block-scan / video metadata cache
```

---

## 11. Milestones

1. **Skeleton:** project setup, permissions flow, root volumes, streaming folder list with icons, navigation and back tile, wait indicator with Stop, and error snackbars (§2.1).
2. **Thumbnails:** static image tiles, visibility- and scroll-aware scheduler, LRU(100).
3. **Folder previews:** 4-image collage, fast candidate selection, automatic cycling, hover/key scrubbing.
4. **Animated/video tiles:** 5-frame strips, scrubbing, auto preview, live playback when selected.
5. **Selection mode:** enter/exit rules, bulk actions.
6. **Slideshow:** timing rules, two-finger swipes, keep screen on, preloading, return-to-position.
7. **Slideshow menu and settings:** slider, random order (seed + cursor), delete with undo.
8. **Persistence:** DataStore settings, recent folders, open first available folder on launch, slideshow session restore.
9. **Input polish:** keyboard, mouse, TV remote (D-pad focus), pinch-to-resize grid, fast-scroll thumb.
10. **Performance pass:** Baseline Profiles, StrictMode, reference folder (§8.1, ~29k files / 338 GB).

---

## 12. Open questions

- In folder view, a mouse **double-click** enters selection mode (as requested). Because a single click also opens the item, a mouse click must wait for the double-click timeout (~300 ms) before it opens anything. Touch taps are not delayed. Is that delay acceptable, or should opening need a double-click on desktop (and selection use right-click only)?
- Should AVI and other FFmpeg-only formats be in the base APK, or in an optional download, given the app size cost (~+10–20 MB)?
- Is a Play Store release planned? If so, `MANAGE_EXTERNAL_STORAGE` needs a policy justification, and a SAF-only build may be needed.
