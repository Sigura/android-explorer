# Prompt for a session: APNG in the slideshow, notification artwork, books on a device, external player position

Copy everything below the line into a new session on `Sigura/android-explorer`.

---

Build the items below for the Media Viewer Android app (Kotlin, Compose). Read `todo.md` first (§5 *Things easy to forget* for context, §3.6, §4.6, §9.8, §10.10, §11), README §2 *Slideshow*, §2 *Listening*, §3.4, §3.12, §3.14, §3.16, and `docs/RESEARCH_2026-10.md` §2. Start with a short plan added to `todo.md` as a new §12 (one subsection per item below), commit it, then build in the order given; commit after each item.

**Base.** Start from `master`. It must contain `domain/SlideNav.kt` and `data/mtp/MtpStream.kt` (the slideshow / drop / MTP work of 2026-10-29). If it doesn't, stop and ask the owner to merge `slideshow-drop-mtp.bundle` / `slideshow-drop-mtp.diff` first: items 1, 3 and 4 edit the same files. If the session names its own branch, create it from `master`.

**Delivery.** Do **not** push and do **not** open a pull request. At the end, leave in the working directory a `git bundle` of the new commits (`origin/master..HEAD`) and one diff of the whole branch (`git diff origin/master..HEAD`), and check that the bundle fetches into a clean clone of `master`. Keep both out of git (`.git/info/exclude`).

**Checking.** No Android SDK in the build container (`dl.google.com` is blocked). `tools/pure-tests.sh` runs the pure-Kotlin tests (it compiles `domain/*.kt` and the listed `data/` files; add any new pure file and test outside `domain/` to it) and `tools/typecheck/run.sh` type-checks the whole app without the SDK (docs/RESEARCH_2026-10.md §0; a library API the stubs in `tools/typecheck/stubs` lack needs a stub with the real signature). Both must pass before each commit. Put the logic in pure Kotlin where it can be tested (`domain/`, or a small pure class next to the Android code, like `data/mtp/MtpStream.kt` against `FakeMtpDriver`). Mark device-only assumptions **verify**. Move what is finished to README §1 / §2 / §3 and out of `todo.md`. Don't read binary files (fonts, images, jars).

## 1. APNG in the slideshow (todo §10.10)

Today an APNG shows its first frame in the slideshow (the platform doesn't animate it). `data/thumbs/ApngAnim` already splits the frames, rebuilds each as a PNG and knows the compositing order (`blend_op`, `dispose_op`, *dispose to previous*).

- Play it like `SlideGif` does GIFs: frames decoded on demand off the main thread into two bitmaps used in turn, timing from each `fcTL` (delays of 0 count as 100 ms, as in browsers), a start position (the slideshow's `visits` / `SlideNav` resume), pause on a frame, loop count from `acTL` (0 = forever).
- Duration from the chunk scan (no decode), cached in `MetaStore` like GIFs, so `SlideTimer` decides loop / play once / play to the end; the preparation of a step (`SlideshowController.prepare`) does the scan.
- Large ones (canvas over 4 MP, like `SlideGif.MAX_PIXELS`) keep the first frame.
- Pure, tested part: the timeline (frame at a position, the frames to composite from the last key frame) in `ApngAnimTest`.
- **Verify:** an APNG sticker set, a large APNG, pause and Back / Next resume.

## 2. Artwork in the notification and on the lock screen (todo §4.6)

- The cover the player screen shows (`TrackExtras.cover`: embedded picture, the image named like the file, a video frame for lectures, the folder's cover) goes into the media session's metadata (`MediaMetadata.artworkData` with `PICTURE_TYPE_FRONT_COVER`, scaled to ≤ 512 px, or `artworkUri` through our `FileProvider`), set when the file changes, so the notification, the lock screen, Bluetooth car displays and Wear show it. **Verify** which of the two the notification uses on Android 13+ and on Samsung.
- Device books (item 3) get it once their cover can be read.

## 3. Books on a device: chapters, durations, covers (todo §9.8 item 3)

Books on a camera or phone stream today (README §3.14) but have no chapters, lyrics, covers or durations of files not played yet, because those readers open local files.

- `MediaTags` already reads through a `ByteSource`: give it one over `MtpConnection` partial reads (a small `MtpStream`, or `readAt` with a little cache), under the chunk watchdog, so embedded chapters (M4B, MP3 `CHAP`) and `USLT` lyrics come from the device. `.cue` / `.lrc` / cover images next to the file: from the device listing, read through the mirror (they are small).
- Durations for the book bar (`BookPlayer.loadTimes`): from the file's own header through the same `ByteSource` (MP4 `mvhd`, MP3 Xing / VBRI or bitrate × size, FLAC `STREAMINFO`, Ogg last page), cached in `MetaStore`; pure and tested on small sample files built in the test.
- Lecture folders on a device: the same duration reader for videos (MP4 / MKV headers), so `Book.classify` can decide.
- **Verify** on a phone with an M4B and a folder of MP3s, and on an MP3 player.

## 4. The external player's position read back (todo §3.6)

- Long videos open in an external player at the saved position (`ExternalApps.open`). Read the position back when it returns: MX Player (`return_result` → `position`, `end_by`), VLC (`extra_position`, `extra_duration`) and Just Player / mpv where they document it; save it to `history.db` like the slideshow does (`saveListenPosition`), finished when within the book rule (`Book.isFinished`).
- `startActivityForResult` → an `ActivityResultLauncher` in `MainActivity`; the pure part (which extras mean what, per package) in `domain/` with tests.
- **Verify** with MX Player, VLC and Just Player installed; a player that returns nothing keeps the old position.

## 5. Docs

Each finished item above moves to README §1 (feature tables), §2 (use), §3 (how it works) and out of `todo.md`; what is left stays in §12 with its **verify** list. Keep the device checks of §11 (todo §9.8 item 4, §11.1–§11.3) open until someone runs them on a device.
