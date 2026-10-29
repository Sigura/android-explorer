# TODO — feature enhancements

Planned work after the current feature set (README §1). Each section lists the goal, the proposed UI, the implementation notes for this codebase, and open questions. Items marked **verify** are assumptions that still need a check on a device or in the library source.

Priority order: **3 → 4 → 1b** — all three done on 2026-10-29 (§3.0, §3.0b, §1.0, §10.4); what is left of them is listed in each section. Finished items move to the README; what is left here is open work.

**Done (2026-10-24, not yet tried on a device):** text previews (README §3.10); opening files with other apps, including long videos in an external player (README §3.11); the core of §3 — background service, `history.db`, audio / mixed / lecture folders, playlist order, the folder Play button and the mini player (README §3.12).

**Done (2026-10-25, not yet tried on a device):** the text editor with new text files and folders (§6; README §3.13).

**Done (2026-10-29, not yet tried on a device):** book status on folder tiles, animated WebP strips, dropping files into the app, find and rename in the editor (§10; README §2, §3.4, §3.9, §3.12). Research for video thumbnails, animated AVIF / APNG and the SAF fallback: docs/RESEARCH_2026-10.md.

**Done (2026-10-29, not yet tried on a device):** the player screen, music mode and shuffle, chapters, lyrics and subtitles, fine speed steps and the sleep timer with fade-out (§4, §10.4; README §2 *Listening*, §3.12).

**Done (2026-10-29, second step):** the tile ▶, *Continue listening*, *Start over?*, skip lengths, skip silence, repeat, `SYLT` and FLAC / Ogg tags, the battery-saver hint (§3.0).

**Done (2026-10-29, not yet tried on a device):** editor colour themes, current line, matching brackets, line numbers and whitespace marks (§10.8; README §2 *Text editor*, §3.13).

**Done (2026-10-29, third step):** lectures (override, shared position, frame covers), embedded subtitles, CC, seek bubble, file info, Share, moved books (§3.0b).

**Next session** (slideshow Back / Next waits for the item and goes on where it was left, editor wrap by default, drop moves and the root screen, MTP streaming and remembered devices): prompt in [docs/NEXT_SESSION_SLIDESHOW_DROP_MTP.md](docs/NEXT_SESSION_SLIDESHOW_DROP_MTP.md).

**Still open in §3 / §4:** artwork in the notification (§4.6), the external player's position read back (§3.6), detecting that a battery saver stopped playback (§3.3), a TalkBack check (§4.6), and the optional extras of §5.

Contents

1. [Previews for more file types](#1-previews-for-more-file-types)
2. ~~Open with the system~~ — done, README §3.11
3. [Audio, audiobook and lecture playback](#3-audio-audiobook-and-lecture-playback)
4. [Player screen](#4-player-screen)
5. [Things easy to forget](#5-things-easy-to-forget)
6. ~~Text files: create and edit~~ — done, README §3.13
7. ~~Linux desktop version~~ — dropped from the plan
8. ~~Sorting, mini player and long names~~ — done, README §1
9. [MTP devices: cameras and phones over USB](#9-mtp-devices-cameras-and-phones-over-usb) — first and second steps done, README §3.14; open: §9.8
10. [Listening tiles, animated WebP, video thumbnails, drop, find, SAF](#10-listening-tiles-animated-webp-video-thumbnails-drop-find-saf) — requested 2026-10-29

---

## 1. Previews for more file types

### 1.0 Previews for documents, books and folders without media — done (2026-10-29)

Built as planned (README §3.15): PDF, audio covers, EPUB / FB2 / CBZ, ODF and OOXML thumbnails, DOCX text, APK icons, aligned CSV, collages for folders without media. Pure parts tested in `PreviewTest` and `FileTypesTest`. **Still open:** SVG (AndroidSVG, ~100 KB: owner's decision), fonts, ZIP listings, CBR (RAR), split APKs (`.apks` / `.xapk`).

### 1a. Text previews — done (README §3.10)

Nothing left: folder tiles of folders with no media and CSV columns were done with §1.0.

### 1b. Other useful formats — PDF, audio, EPUB, FB2, CBZ, ODF, OOXML, APK done (§1.0); SVG, fonts, ZIP listings open

All of these use the platform or small pure-Java code; no large native libraries. Each one runs under the existing decode watchdog.

| Format | How | Cost | Notes |
| --- | --- | --- | --- |
| **PDF** | `android.graphics.pdf.PdfRenderer` (API 21+, built in): render page 1 to a bitmap with a white background | none | Not thread-safe: one renderer per file, closed after use. Encrypted or broken PDFs throw → broken icon. A PDF tile could show the page count badge. |
| **Audio cover art** | `MediaMetadataRetriever.getEmbeddedPicture()`; fallback `cover.jpg` / `folder.jpg` / `front.jpg` / `AlbumArt*.jpg` / the only image in the folder | none | Needed for §3/§4 anyway. Tile badge: duration. |
| **EPUB** | ZIP → `META-INF/container.xml` → OPF → `<meta name="cover">` or a manifest item with `properties="cover-image"` | small (`ZipFile` + `XmlPullParser`) | No cover → first `<img>` in the first spine item, or a text preview of the first chapter. |
| **FB2 / FB2.ZIP** | `<coverpage><image l:href="#id">` → `<binary id="id">` base64 | small | Common format for Russian-language e-books. |
| **CBZ (comics)** | First image in the ZIP by natural order | small | CBR (RAR) needs `junrar` (RAR4 only); skip unless asked. |
| **APK** | `PackageManager.getPackageArchiveInfo()`; set `applicationInfo.sourceDir` / `publicSourceDir` to the path, then `loadIcon()` | none | Also shows the app label and version as the tile name. |
| **ODT / ODS / ODP** | `Thumbnails/thumbnail.png` inside the ZIP | small | Almost always present. |
| **DOCX / XLSX / PPTX** | `docProps/thumbnail.jpeg` if saved; otherwise text extracted from `word/document.xml` → text preview (README §3.10) | small | XLSX/PPTX without a thumbnail → icon. |
| **SVG** | AndroidSVG (`com.caverock:androidsvg-aar`, ~100 KB) | small dep | Optional. |
| **Fonts** (`ttf otf`) | `Typeface.createFromFile` → render "Aa Bb Cc 123" | none | Optional, cheap. |
| **ZIP / archives** | List the first entries as a text preview | none for ZIP | 7z/RAR: icon only. |
| **HTML** | Strip tags, render as text | none | Never run a WebView for thumbnails. |
| DjVu, PSD, RAW camera formats | — | heavy | Skip. Some RAW files contain an embedded JPEG; `ExifInterface.getThumbnail()` may be enough. **verify** |

Opening a document full-screen inside the app (a PDF reader, for example) is **not** planned: such files open with the system (README §3.11).

---

## 2. Open with the system — done (README §3.11)

---

## 3. Audio, audiobook and lecture playback

**Goal.** A folder of MP3s (an audiobook or an album), or of lecture videos, plays as a playlist in the background, with the screen locked. The app remembers the file and position, and continues there next time.

### 3.0 The rest of listening — done (2026-10-29, second step)

Built as planned (README §2 *Listening*, §3.12): the tile ▶ (§3.2), *Start over?* and *Continue listening* (§3.4), skip lengths, skip silence and repeat (§4.6), `SYLT` and FLAC / Ogg comments (§4.6), the battery-saver hint (§3.3). One change: a tap on a *Continue listening* card opens its folder; its ▶ plays. **Verify on a device:** the tile ▶ doesn't also open the folder (nested click inside the tile's own gestures), the notification's skip buttons with a `ForwardingPlayer` (Media3 may draw its own ±10 s icons whatever the increment), skip silence with speech, the hint's settings screen on each maker, covers in the row on slow storage.

### 3.0b Lectures and the player's last pieces — done (2026-10-29, third step)

Built as planned (README §2 *Listening*, §3.11, §3.12): the lecture override, positions shared with the slideshow and the external player, video frames as covers, subtitle tracks inside MKV / MP4, CC per folder, the seek bubble, *File info*, *Share*, moved books. Pure parts tested in `PlayerTest` (`BookMatch`) and `MediaTagsTest` (Matroska and MP4 subtitle tracks, ASS cleaning, the read window). **Still open:** the notification's artwork; reading the external player's position back (VLC and MX Player return it through `startActivityForResult`).

### 3.1 What counts as an audio folder

- **Audio files:** `mp3 m4a m4b aac flac ogg oga opus wav wma mka aiff ape wv amr mpc`, and MP4 files with an `M4A` / `M4B` / `M4P` brand (`FileTypes`, done).
- A folder is an **audio folder** when all its visible files are audio, apart from these companion files: images (covers), `lrc srt vtt txt nfo cue m3u m3u8 pdf`. Hidden files are ignored.
- A **lecture folder** has only long videos (§3.6).
- A **mixed folder** has audio files **and** videos (decided): its audio and video files play as **one queue**, in book mode by default. Any audio file in a folder makes that folder play in book mode.
- **Every audio folder starts in book mode** (decided): files play in order (§3.5), and the exact position is remembered.
- **Shuffle / music mode** per folder and a tap on an audio file — **done** (README §2 *Listening*, §3.12).

### 3.2 Folder tile play button

- **Done** (§3.0). Audio folders get a round **▶ play / resume** button over the collage (bottom-right corner, 40 dp touch target). Tapping it starts or continues playback without opening the folder, and the mini player (§4.3) appears.
- A thin **progress ring or bar** shows how much of the book was heard. A ✓ marks finished books.
- A tap elsewhere on the tile opens the folder as usual. Long press and selection work as usual.

### 3.3 Background playback and lock screen

- Use Media3 **`MediaSessionService`** (`androidx.media3:media3-session`, same version as the existing Media3) with the existing ExoPlayer and FFmpeg audio renderer.
  - The system media notification gives lock-screen, notification-shade, Bluetooth, headset-button, smartwatch and car (AVRCP) controls with no custom UI.
  - The UI talks to the service through a `MediaController`, so the player screen, mini player and folder button share one player.
- Manifest:
  - `<service android:foregroundServiceType="mediaPlayback">`
  - the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions (Android 14+)
  - `WAKE_LOCK` (needed by `WAKE_MODE_LOCAL`)
  - `POST_NOTIFICATIONS` is declared but **not** requested: media-session notifications are exempt on Android 13+. **verify**
- **Playback resumption:** implement `MediaSession.Callback.onPlaybackResumption` (Media3), so a Bluetooth / headset Play press after the app was killed resumes the last book, and the book shows in the system media controls after a reboot.
- **Audio focus with the slideshow:** the slideshow's ExoPlayer requests no audio focus today. Give it `AudioAttributes` with focus handling when its sound is on, so unmuting a video pauses the book (and the book resumes after).
- Player settings:
  - `setWakeMode(C.WAKE_MODE_LOCAL)`, so playback continues with the screen off;
  - `setAudioAttributes(..., handleAudioFocus = true)`, so playback pauses for calls and ducks for navigation prompts;
  - `setHandleAudioBecomingNoisy(true)`, so playback pauses when headphones are unplugged.
- Samsung / Xiaomi battery savers may kill the service: a one-time hint when a book starts — **done** (§3.0). Still open: detecting that the system actually stopped playback.
- Stop the service, and remove the notification, after it has been paused for about 30 min, or when it is swiped away while paused.

### 3.4 History: resume where it stopped

- A **separate** SQLite database, `history.db` (`HistoryStore`), with real migrations. Not `MetaStore`: that one is a disposable cache — it drops its table on every version bump and prunes rows unused for 90 days, which would lose listening history. Tables:
  - `audio_folder`: folder path, current file name, position, speed, shuffle (on = music mode), shuffle seed and cycle state, total duration, finished flag, last played time;
  - `audio_file`: path + size + mtime, saved position, finished flag.
  - Per-file positions let a book be continued from any file, and show per-file progress in the list.
- Save every ~5 s while playing, and at once on pause, file change, seek and `onTaskRemoved`. Restore after a kill or reboot (same idea as the slideshow restore).
- **Smart rewind** on resume (common in audiobook apps): go back 0 s if paused < 1 min, 5 s if < 1 h, 15–30 s if longer. Make it a setting.
- A file counts as **finished** when played to within 1–2 % (or 30 s) of its end. Book mode moves on to the next file automatically (*Start over?* — **done**, §3.0). At the end of the last file, the book is marked finished, and the next tap asks "Start over?".
- A renamed or moved folder keeps its history — **done** (§3.0b).
- **Continue listening** row on the root screen — **done** (§3.0).

---

### 3.5 Play order: playlists, then names — done (README §3.12)

- **Playlist** (decided): if the folder contains an `.m3u` / `.m3u8`, the queue follows it.
  - Format: one path per line, relative to the playlist (or absolute); `#` lines are comments; `#EXTINF:<seconds>,<title>` gives a title and duration. `\` separators (playlists made on Windows) are accepted.
  - Encoding: `.m3u8` is UTF-8; `.m3u` is decoded with `TextDecode` (BOM, UTF-8, then the legacy charset), as text previews are.
  - Entries that don't exist are skipped. Files in the folder that the playlist doesn't list are appended after it, in name order, so nothing is lost.
  - Several playlists: use the one named like the folder, otherwise the largest; the player's ⋮ menu offers *Order: playlist name / file names*.
  - URLs (`http://…`) are ignored: the app plays local files only.
  - A `.cue` sheet for one large file gives chapters, not the queue (§4.5).
- **No playlist** (decided): book mode sorts by **file name** in natural order (`NaturalOrder`: `2 - x` before `10 - x`), regardless of the folder view's sort setting (date, size…). Music mode shuffles that list.
- Pure Kotlin `PlaylistParser` with tests: comments, `#EXTINF`, relative and Windows paths, missing entries, unlisted files appended.

### 3.6 Lecture folders: listen to videos

**Goal.** A folder of recorded lectures or talks (videos) can be **listened to** like an audiobook: in the background, screen off, with speed, skip-silence and a remembered position.

- **Playback:** the same player and service as §3.3. The video track is switched off (`TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`), so only audio is decoded: battery use close to an MP3, and video codecs the device can't decode don't matter. The FFmpeg extension covers AC-3 / DTS audio. **verify** battery and screen-off playback on a device.
- **Lecture folder** (shows the Play button, §3.7):
  1. at least 2 videos and no audio files (with audio files it is a mixed folder, §3.1);
  2. other visible files are companions: subtitles, `txt json description nfo pdf` and slides, and images if there are no more of them than videos (`yt-dlp` writes a `.webp` / `.jpg` thumbnail, `.vtt` subtitles, `.info.json` and `.description` next to each video);
  3. **all videos are long** (decided): each one is longer than the long-video setting (README §3.11, default 3 min). Durations come from `MetaStore`; until all are known, the folder is not a lecture folder yet.
  - Per-folder override in ⋮: *Listen as audio* / *Not a lecture folder* — **done** (§3.0b).
- **A tap on a video still watches it** (slideshow, or the external player for long videos). Listening starts only from the Play button.
- **Shared position** — **done** (§3.0b), except reading the external player's position back.
- **Cover:** a frame of the video — **done** for the player screen and *Continue listening* (§3.0b); the lock screen and notification are open.
- **Subtitles:** `.vtt` / `.srt` companions and embedded text tracks (MKV / MP4) — **done** (§3.0b).
- **No video in the player** (decided): the player never shows the picture, it only plays the sound. Watching stays in the folder view (tap the video).
- Videos without an audio track are skipped.

### 3.7 Play button in the folder view — done (top bar: README §2, Listening; folder tile ▶: §3.0)

There is no Play button in the folder view today: a slideshow starts by tapping a tile. Add one (decided):

- A **▶ Play** button in the top bar of the folder view (and `P` on a keyboard).
  - **Audio, mixed or lecture folder:** starts or resumes the book (`Continue 12 / 30` when there is history), and the mini player appears.
  - **Any other folder with media:** starts the slideshow from the first media item.
- Folder tiles: audio, mixed and lecture folders get the round ▶ / resume button and progress ring of §3.2.
- Not shown in selection mode.

## 4. Player screen

**Done** 2026-10-29 (README §2 *Listening*, §3.12): the layout of the old §4.1 (cover, names, lyrics, seek bar, book bar, controls, the list as a sheet, two panes on wide windows), the controls and keys of §4.2, chapters (§4.5) and lyrics / subtitles (§4.4); the mini player (§4.3) opens it.

### 4.6 Still open

- ~~Repeat, skip silence, settable skip lengths~~ — done (§3.0).
- ~~CC, embedded subtitles, seek bubble, file info, Share~~ — done (§3.0b).
- **Artwork in the notification and on the lock screen** (the cover the player screen shows).
- **Accessibility:** check TalkBack order and the seek bar's volume-key adjustment on a device.

---

## 5. Things easy to forget

- **Gapless playback** between files (ExoPlayer handles it for a playlist set with `setMediaItems`; check MP3 encoder delay / LAME headers). **verify**
- **Durations of all files** in the list without decoding: read them lazily through `MetaStore` (cache), as GIF/video durations are today. A 1,000-file book must not block the list.
- **Files changing during playback:** a deleted current file → skip to the next one with a snackbar. A removed SD card → stop and keep the saved position. New files appear in the queue (`FolderListing` live updates).
- **Audio inside the slideshow:** optional background music while a slideshow runs. Pick an audio folder in the slideshow menu; it ducks under video sound. Low priority.
- **Native crashes:** `PdfRenderer`, `MediaMetadataRetriever` and FFmpeg can crash in native code, which the watchdog can't catch. PDFs add a new path; if tombstones show up, decode thumbnails in a separate process.
- **Volume boost** for quiet recordings (`LoudnessEnhancer`, ~+6 dB). Optional.
- **Bookmarks with a note** inside a book. Optional.
- **Android Auto / AAOS:** would need `MediaLibraryService` with a browsable tree. Out of scope for now, but choosing `MediaSessionService` keeps the door open.
- **TV / DeX:** the player screen is fully focusable, and Play/Pause media keys from a keyboard work everywhere in the app, not only on the player screen.
- **Accessibility:** content descriptions on all player buttons. The seek bar is adjustable with TalkBack volume gestures.
- **Docs:** move each feature to README §1 (feature tables), §2 (usage), §3 (how it works, code map) when it is implemented, and remove it here. Add tests for the playlist parser, the LRC parser, lecture-folder detection, book/music detection and the resume/rewind rules in `DomainTest` (pure Kotlin).

### Decisions

Decisions for finished features are in README §6, *Decisions taken*.

1. Audio folders start in book mode. Shuffle is remembered per folder and switches it to music mode (§3.1).
2. Folders with audio and videos play both as one queue, in book mode by default (§3.1).
3. Play order: an `.m3u` / `.m3u8` playlist if there is one, otherwise natural file-name order (§3.5).
4. Lecture folders: only long videos (all above the long-video setting); a tap on a video still watches it; listening starts from the Play button; the player plays the sound only, never the picture (§3.6, §3.7).
5. The folder view gets a ▶ Play button: book playback for audio, mixed and lecture folders, the slideshow for other media folders (§3.7).

---

## 6. Text files: create and edit — done (README §2 "Text editor", §3.13)

Done 2026-10-25, not yet tried on a device. Decisions are in README §6.

Still open:

- ~~Replace~~ — done 2026-10-29 (README §2 Text editor).
- **Read-only files** (1–10 MB) always wrap: the line list has no sideways scroll.
- **Verify on a device:** typing speed in a 1 MB file (the text field lays out the whole text on each change), colouring speed near the 200 K limit, the keyboard over the text, pinch next to text selection, `Tab` / `Esc` on DeX keyboards, the safe save on FAT / exFAT through FUSE, and whether breadcrumbs fit at 360 dp with the phone ⋮ menu.

---

## 8. Sorting, mini player and long names — done (README §1 *Browsing*, §2 *Listening*)

Requested 2026-10-25; built as described below. **Verify on a device:** the mini player's ⏮ / ⏭ at 360 dp, and marquee scrolling smoothness with a full screen of long hash names.

- **Name order for folders without images.** A new default sort, *Automatic*: folders with images or GIFs sort by date, newest first (as now); every other folder (documents, audio, books, lectures, folders of folders) sorts by name in natural order (`a1`, `a2`, `a10`, using `NaturalOrder`). The first listing batch decides; the result is remembered with the folder, so the next visit starts in the right order.
- **Sort remembered per folder.** The sort menu (name, date modified, size, type; ascending / descending; folders first) applies to the open folder and is saved for it between runs (DataStore, keyed by path, ≤ 5,000 folders, least recently opened dropped first). *Automatic* removes the folder's own choice; *Use for all folders* sets the default. The slideshow follows the folder's sort.
- **⏮ / ⏭ in the mini player** (§4.3): icon buttons for the previous and next file around ▶/⏸, with the §4.2 rule (⏮ within the first 3 s → previous file, later → start of the file); disabled at the ends of the queue; also from the media session (lock screen, headset). The bar may need more room at 360 dp **verify**.
- **The playing file is selected:** with the book's folder open, the playing file's tile gets a *now playing* badge and is scrolled into view on every file change; it is selected only when no other selection is being made (selection mode off, or only the previously playing file selected).
- **Scrolling long names** (marquee): every file and folder name that doesn't fit its tile scrolls sideways in a continuous loop (the name, a gap, the name again, without stopping), so the whole name can be read; names that fit don't move (`Modifier.basicMarquee(iterations = Int.MAX_VALUE)`, Compose 1.8). Off when *Remove animations* is on; names are then shortened as now. **verify** scrolling smoothness with a full screen of long hash names.

---

## 9. MTP devices: cameras and phones over USB

Requested 2026-10-25. **First step done (2026-10-25, not yet tried on a device):** the home screen *Devices* section, browsing, device thumbnails, the slideshow from a local mirror, *Copy to…* and *Open with* (README §3.14). **Second step done (2026-10-29, tested against a fake device):** delete with *Undo*, copy and move to and from devices, new folders, live updates, folder collages, auto-open for cameras, thumbnails for files without one (README §3.14). Open: §9.8.

**Goal.** A camera, another phone, an action cam or a media player plugged into the phone or tablet (USB OTG, USB-C) shows up on the home screen. Its photos and videos can be browsed with thumbnails, watched in the slideshow and copied to this device, as easily as an SD card.

### 9.1 Findings

- Such devices don't mount as a folder: they speak **MTP** (phones, players) or **PTP** (cameras; MTP is an extension of it). Android has no path for them; apps see them only through **`android.mtp.MtpDevice`** on top of **`UsbManager`** (USB host mode, API 12+). Everything the app needs is there on API 26: storages (`getStorageIds`, `getStorageInfo`: name, free and total space), folder listing (`getObjectHandles(storage, 0, parent)` + `getObjectInfo`), the device's **own thumbnails** (`getThumbnail`: a ~160 px JPEG made by the camera or phone, so no download is needed for tiles), chunked reads (`getPartialObject`, API 24), whole-file copies (`importFile`), and, for later, `deleteObject`, `sendObject` and change events (`readEvent`, API 24).
- **Permission:** each device needs the user's OK through the system prompt (`UsbManager.requestPermission`); it lasts until the device is unplugged. No manifest permission is needed. Android 12+ requires a mutable `PendingIntent` for the answer.
- **One owner at a time.** Android's own `MtpDocumentsProvider` (behind the Files app) may already hold the device; `MtpDevice.open` then fails. **verify** on Pixel and Samsung: does the provider let go when the Files app is closed, and is there a reliable way to take over?
- **The other way, the Storage Access Framework** (the user picks the device in the system picker, files come as `content://` URIs) would also work and avoid the conflict, but the app is built on file paths, and the SAF fallback was left out on purpose (README §6). MTP directly keeps the home screen entry and the device thumbnails.
- **Phones as devices** show no storages until they are unlocked and set to *File transfer*.
- **Speed:** a USB 2 MTP link moves 10–40 MB/s, and each `getObjectInfo` is one round trip (2–10 ms), so a folder of 10,000 camera files takes 20–100 s to list. The listing must stream like local folders do, and thumbnails must not wait behind a download.
- **Slideshow, players and decoders read local files** (Coil, ExoPlayer, `MediaMetadataRetriever`, `GifCodec`). Streaming from the device into each of them would need a data source per decoder; copying into a cache first works with all of them at once.

### 9.2 Home screen

- A **Devices** section, shown only while a device is connected, at the top (above *Recent*): plugging one in is usually why the home screen is open.
- One row per storage of a ready device: *"Canon EOS R6"*, or *"Pixel 8 · Internal shared storage"* when it has several, with the free-space bar of volumes. A tap opens it in the folder view.
- A device not yet ready is one row with a hint: *"Tap to allow access"* (the system prompt), *"Connecting…"*, *"Unlock the device and choose File transfer, then tap here"*, *"In use by another app — …"*. A tap asks or tries again.
- Plugging in and unplugging update the section at once (`ACTION_USB_DEVICE_ATTACHED` / `DETACHED`).

### 9.3 Connection

- Every call to one device runs on its own thread, one after another (`MtpDevice` isn't thread-safe). Calls are short: file details 16 at a time, downloads in 1 MB chunks, so thumbnails interleave with a running download.
- Every call has a watchdog (8 s; listing 30 s; thumbnail 10 s; chunk 20 s). A device that misses one is marked *not responding*: later calls fail at once, and the folder shows *"Device not responding — unplug it and plug it in again"* (README §2.1).
- **Paths:** `mtp://<USB device id>/<storage id>/<folders>/<name>`. They are valid until the device is unplugged, so they are never added to recent folders, favourites or the slideshow session.

### 9.4 Folder view

- The normal folder view: streaming listing, sort, tile sizes, selection, breadcrumbs (the storage is the root icon), fast scroller.
- **Thumbnails:** the device's own JPEG for photos and videos; folder collages from those; no 5-frame strips on device folders.
- **File types** from the extension, else from the MTP object format (JPEG, PNG, GIF, MP4, MP3, …); no magic-byte sniffing (it would need a download).
- No *Share*, *Ctrl* + C, new text files, favourites or editing (the files aren't on this phone).

### 9.5 Copy to this device

- *Copy to…* in the tile menu and the selection bar. The folder picker starts in the last local folder. Files and whole folders are copied, with the wait indicator, the file name, and Stop. Name clashes get a *" (1)"* suffix, as local copies do; a stopped copy leaves no partial file.

### 9.6 Slideshow and other apps

- Tapping a photo, GIF or video downloads it (with progress) into a **mirror** in the app's cache, `cache/mtp/<device>/<storage>/<folders>/`, and opens the slideshow over that mirror folder.
- The folder's other media follow in its order, from the tapped one round to the one before it; each joins the slideshow as soon as it is complete (the mirror folder is a normal watched listing, and downloads are renamed into place). Downloading ahead stops at **2 GB** in the mirror; opening a slideshow from another device folder frees the rest of the mirror first.
- Leaving the slideshow returns to the device folder, focused on the last item shown.
- Other files open with other apps (README §3.11) from a downloaded copy.
- The mirror is emptied at app start and when the device is unplugged.
- **Limits of this step:** the slideshow knows only the files downloaded so far (a random order shuffles those; *previous* stops at the tapped file until the download wraps round). Very large folders are better copied first.

### 9.7 Unplugging

- The device's folders close with *"Device disconnected"*, and the view goes to the home screen. A running slideshow from it closes the same way.

### 9.8 Still open

1. **Streaming playback** instead of the mirror for long videos: a Media3 `DataSource` over `getPartialObject` (seeking needs random access; **verify** read speed). Also the book player for audio folders on a device.
2. **Remember devices:** a stable key (vendor id, product id and serial number, readable after permission) so recent folders and favourites can point into a device, shown dimmed while it is unplugged.
3. **Copy on the device without the round trip:** `CopyObject` / `MoveObject` exist in MTP but not in `android.mtp.MtpDevice`; device → device copies go through a local temporary file.
4. **verify** on devices: a Canon / Nikon / Sony camera in PTP mode, an Android phone in MTP mode (locked, then unlocked), a Kindle or MP3 player; 10,000-file listing speed; `getThumbnail` for videos on phones; `sendObject` and new folders on cameras (some refuse unknown formats); events from cameras; deleting full folders; behaviour when Android's Files app holds the device; unplugging during a download or upload; auto-open while the app is already open (`singleTop`); Android TV boxes with USB host.

---

## 10. Listening tiles, animated WebP, video thumbnails, drop, find, SAF

Requested 2026-10-29. Plan first, then the work; the findings of the research items are in [docs/RESEARCH_2026-10.md](docs/RESEARCH_2026-10.md). No device and no Android SDK were available (the SDK download host is blocked in the build container), so only the pure-Kotlin parts are compiled and unit-tested; everything else is **verify** on a device.

| # | Request | Kind | Plan |
| --- | --- | --- | --- |
| 10.1 | Folder tiles of books being listened to show where we are | build | **done** (README §2 Listening, §3.12) |
| 10.2 | Our own player for animated WebP / AVIF strips in tiles | research + build (WebP) | **WebP done** (README §3.4); AVIF and APNG: research doc §2 |
| 10.3 | Faster video thumbnails, per format | research | **done**: findings and ranked proposals in the research doc §3; next step: the strip disk cache |
| 10.4 | Full player screen, speed, sleep timer, shuffle, chapters, lyrics | build | **done** (README §2 Listening, §3.12); open: §4.6 |
| 10.5 | Drop files and folders into the app (DeX, Chromebooks, split screen) | research + build (files) | **done** for the folder grid (README §2 Desktops, §3.9); open: research doc §5 |
| 10.6 | Text editor: find, rename from the editor | build | **done** (README §2 Text editor) |
| 10.7 | Storage Access Framework fallback (for a Play Store build) | research | **done**: options and a recommendation in the research doc §7. **Not planned** (owner, 2026-10-29): no Play Store release is planned; revisit before one (first step: the Play declaration as a file manager, no code) or for devices that can't grant "All files access" (some Android TV boxes) |
| 10.8 | Text editor: colour themes and richer colouring | build | **done** (README §2 Text editor, §3.13) |
| 10.10 | SVG, APNG, MediaStore first frames, drops onto tiles, paste | build | **done** (below) |
| 10.9 | Text editor and text previews: the best monospaced font available | build | **done**: JetBrains Mono NL bundled (README §6, *Code font*) |

**Verify on a device:** the tile pill at 360 dp with 2–3 columns and in DeX; the label switching while a book plays (one tile recomposes per second); animated WebP strips of large stickers (memory: one full-size canvas) and of WebPs with odd offsets; the editor scrolling to the current match while the find field has focus (Compose scrolls a text field to its selection **verify** without focus); drops from My Files, Chrome, Gmail and Gallery in DeX, and a dropped folder.

### 10.10 SVG, APNG, MediaStore first frames, drops onto tiles, paste — done (2026-10-29)

Built as planned (README §1, §2 *Desktops*, §3.4, §3.9, §3.15): SVG / SVGZ tiles (AndroidSVG 1.4), APNG strips (`ApngAnim`, tested in `ApngAnimTest`: frames rebuilt as PNGs and decoded by `ImageIO`, the compositing order recorded), MediaStore first frames, drops onto folder tiles, `Ctrl` + V. **Still open:** animated AVIF (needs a device), moves with `Shift` held while dropping, APNG in the slideshow (it shows the first frame).

### 10.1 Where we are, on the folder tile

- **Data.** `history.db` v2 adds `idx`, `total` and `duration` to `audio_folder` (an `ALTER TABLE … ADD COLUMN` migration, never a drop), written with every save from the player: the file number, the queue length and the current file's duration. Books saved by v1 show what they have (file and position) until they are played again.
- **Loading.** The folder view reads the books of its subfolders in **one query** (`HistoryStore.foldersIn(parent)`, a path-prefix range like `files()`), once per listing and again when playback stops or the book changes; the book playing right now uses the live `NowPlaying` instead.
- **Tile.** A dark pill over the top of the collage: an icon and a label, plus a thin progress line along the bottom of the collage (`(file − 1 + position / duration) / files`).
  - Icon: **equalizer** = playing now, **headphones** = started, **✓** = finished.
  - Label: the **longest of three variants that fits** the pill (measured, not guessed from a breakpoint), so a small tile is short and a wide one explains more:
    - short: `12/30` (finished: no text, the ✓ alone)
    - medium: `12/30 · 4:05`  (finished: `Finished`)
    - long: `File 12 of 30 · 4:05 / 25:10`  (finished: `Finished · 30 files`)
  - The variants are built by pure Kotlin (`ListenLabel`), unit-tested.
- Not in this step: the ▶ resume button on the tile (§3.2) — the label is the first half of it.

### 10.2 Animated WebP strips in tiles

- **Why own code.** `AnimatedImageDrawable` (and Coil on top of it) only plays forward; there is no seek, so tiles show the first frame (README §6). The WebP container is simple (RIFF → `VP8X` with the animation flag → `ANIM` → `ANMF` frames with offset, size, duration, blend and dispose flags, each holding an ordinary `VP8` / `VP8L` (+ `ALPH`) bitstream).
- **Plan.** `WebpAnim` (pure Kotlin, like `GifCodec`'s block scan): a chunk scan that reads the canvas size, frame count, total duration and each frame's chunk offsets without decoding, cached in `MetaStore` like GIFs. The strip pass rebuilds each needed frame as a standalone WebP in memory (`RIFF/WEBP/VP8X/ALPH/VP8` or `RIFF/WEBP/VP8L`), decodes it with the platform decoder at the strip scale, and composites it on one canvas with the frame's blend and dispose rules. It starts at the nearest **key frame** before each target (a frame that covers the canvas without blending, or follows a full-canvas dispose — the same rule libwebp uses), so a long animation decodes few frames.
- **Detection.** The listing keeps `.webp` as an image (no extra read per file). The still decoder reads the first 30 bytes anyway: an animated WebP (`VP8X` flag bit 1) becomes a strip with frame count and duration, like a GIF; the tile shows the strip and scrubs it.
- **AVIF (`avis`) and APNG:** researched only (research doc §2); they need the AV1 decoder / a PNG chunk splitter and are proposed for a later step.

### 10.4 Player screen, speed, sleep timer, shuffle, chapters, lyrics

**Done** 2026-10-29, as planned: README §2 *Listening* (use) and §3.12 (how it works); pure-Kotlin parts tested in `PlayerTest` and `MediaTagsTest`. Changes to the plan: chapters and lyrics are read by our own `MediaTags` (MP4, ID3, EBML) rather than from Media3's metadata, which reaches only the service, not the controller; *end of chapter* falls back to the end of the file; a held arrow on the cover first skips, then changes the file. Still open: §4.6.

**Verify on a device:** the screen at 360 dp, in landscape and in DeX; the list handle; D-pad focus order on a TV and the cover's focus ring; the blurred cover (Android 12+); `Player.setVolume` through the controller during the fade (`COMMAND_SET_VOLUME` available to the controller); shake detection with the screen off; shuffle reordering a playing queue without a gap (`moveMediaItem`); chapters of M4B files from different tools (iTunes, ffmpeg, Audiobook Builder, mp4chaps) and MP3 `CHAP` from podcasts; `.lrc` / `.vtt` in legacy charsets; covers from large embedded pictures (memory).

### 10.5 Drop into the app

- `Modifier.dragAndDropTarget` (Compose 1.8) on the folder grid. Accepts drops of `content://` and `file://` URIs; `requestDragAndDropPermissions` keeps read access during the copy.
- A drop on the grid **copies** into the open folder (never moves: the source app owns its files), with the existing progress, Stop, `freeName` (" (1)") and error summary of *Copy to…*. Our own tiles dropped back onto the same folder do nothing.
- The grid shows a highlighted outline while a drag with files is over it.
- **Folders:** a dropped directory (`vnd.android.document/directory`) is copied recursively when the drag permission allows listing its children; otherwise it is reported as skipped (**verify** with My Files in DeX).

### 10.6 Text editor: find and rename

- **Find:** `Ctrl` + F or the 🔍 button opens a bar under the top bar: the query, `3 / 17`, previous / next (`Enter`, `Shift` + `Enter`, `F3`, `Shift` + `F3`), *Match case*, ✕ (`Esc`). All matches get a background colour, the current one is selected and scrolled into view; in read-only files the list scrolls to its line. Matching is pure Kotlin (`TextSearch`, unit-tested), plain text, case-insensitive by default, capped at 10,000 matches.
- **Rename:** a tap on the file name in the editor bar (or `F2`) opens a rename dialog with the name selected up to the extension; the same name rules as new files; never overwrites. The unsaved text, undo history and draft follow the new name; the colouring follows the new extension.
- Replace is not part of this step.

### 10.8 Text editor: colour themes and richer colouring — done (2026-10-29)

Built as planned (README §1, §2 *Text editor*, §3.13): `EditorTheme` with nine themes and a contrast test, the theme dialog in the editor ⋮ and in Settings, current line, matching brackets and tags (`Brackets`, tested in `BracketsTest`), line numbers and whitespace marks behind settings. Changes to the plan: the text field now sits in an outer scroll (it is as tall as its text), so everything is drawn from its layout and the editor keeps the cursor in view itself; read-only files get line numbers and the theme, but no whitespace marks, current line or brackets (no cursor there).

**Verify on a device:** typing speed in a 1 MB file with line numbers on (a line-start pass per edit) and the bracket scan; scrolling smoothness with the gutter and marks drawn per frame; the cursor kept in view while typing at the bottom with the keyboard open, and while selecting by dragging past the edge (the field no longer scrolls itself: Compose's own edge auto-scroll may not reach the outer scroll); the find selection scrolled into view in a long file; selection handles with a chosen theme; the dialog's samples on a 360 dp phone.

### 10.9 The best monospaced font available

Requested 2026-10-29. **Done** the same day, with two changes to the plan below: the **no-ligature** edition (*JetBrains Mono NL*) is bundled instead of turning ligatures off by font features (smaller, simpler), and the crash screen keeps the system font. Hinting is dropped (high-density screens don't use it): ~250 KB for regular, bold and italic. Still to verify on a device: text-preview line fit and the editor's look at 10–12 sp. Today code, logs and tables use the system `monospace` (Droid Sans Mono on most phones, Samsung's own on One UI; no ligatures, weak Cyrillic on some builds).

- **Bundle JetBrains Mono** (SIL Open Font License 1.1): the variable TTF subset to Latin, Latin-1, Cyrillic, Greek and box drawing / arrows is ~150–200 KB (regular + bold via the `wght` axis, or two static files ~110 KB each). Clear `0/O`, `1/l/I`, tall x-height for small sizes, full Cyrillic — the main reason over Fira Code / Source Code Pro / Cascadia Code, which are the alternatives considered (all OFL; Cascadia's Cyrillic is good too, but it is larger).
- **Ligatures off** by default (`fontFeatureSettings = "liga 0, calt 0"`) — code reads character by character in an editor; a setting can turn them on.
- **Used in:** the editor (`textStyle`), monospace text previews on tiles (`TextPreview`: `Typeface` from `res/font`, loaded once), the crash screen and the error log. Proportional text stays the system font.
- **Setting:** *Code font*: JetBrains Mono (default) / System monospace. The system choice stays for devices where the user installed their own font (Samsung font styles change `monospace` too).
- **Not chosen:** Google Fonts *downloadable fonts* (no APK size, but needs Google Play services — missing on many TV boxes and Huawei devices — and a network fetch on first use); the APK grows by ~0.2 MB instead (release APK ~8.3 MB today).
- **Steps:** add `res/font/jetbrains_mono.ttf` (subset with `pyftsubset`, keep `OFL.txt` in `app/src/main/assets/licenses/` and a line in Settings → About), `FontFamily(Font(R.font.jetbrains_mono, …))` in one place (`ui/common/Fonts.kt`), replace the three `Monospace` uses and `Typeface.MONOSPACE`. **Verify** that the text-preview line count per tile (README §3.10) still fits, since the metrics differ from Droid Sans Mono.
