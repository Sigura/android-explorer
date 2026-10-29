# TODO — feature enhancements

Planned work after the current feature set (README §1). Each section lists the goal, the proposed UI, the implementation notes for this codebase, and open questions. Items marked **verify** are assumptions that still need a check on a device or in the library source.

Priority order: **3 → 4 → 1b**. Finished items move to the README; what is left here is open work.

**Done (2026-10-24, not yet tried on a device):** text previews (README §3.10); opening files with other apps, including long videos in an external player (README §3.11); the core of §3 — background service, `history.db`, audio / mixed / lecture folders, playlist order, the folder Play button and the mini player (README §3.12).

**Done (2026-10-25, not yet tried on a device):** the text editor with new text files and folders (§6; README §3.13).

**Done (2026-10-29, not yet tried on a device):** book status on folder tiles, animated WebP strips, dropping files into the app, find and rename in the editor (§10; README §2, §3.4, §3.9, §3.12). Research for video thumbnails, animated AVIF / APNG and the SAF fallback: docs/RESEARCH_2026-10.md.

**Still open in §3 / §4:** shuffle and music mode (§3.1), the folder **tile** ▶ button and progress ring (§3.2), the battery-saver hint (§3.3), the per-file progress in the list, renamed-folder matching, the *Start over?* question (a finished book now simply starts over) and the *Continue listening* row (§3.4), the lecture override in ⋮, the shared position with the slideshow / external player, video frames as cover art and subtitles (§3.6), and the whole player screen (§4.1, §4.2, §4.4, §4.5).

Contents

1. [Previews for more file types](#1-previews-for-more-file-types)
2. ~~Open with the system~~ — done, README §3.11
3. [Audio, audiobook and lecture playback](#3-audio-audiobook-and-lecture-playback)
4. [Player screen](#4-player-screen)
5. [Things easy to forget](#5-things-easy-to-forget)
6. ~~Text files: create and edit~~ — done, README §3.13
7. [Linux desktop version](#7-linux-desktop-version) — separate app, spec in docs/LINUX_DESKTOP_SPEC.md
8. [Sorting, mini player and long names](#8-sorting-mini-player-and-long-names)
9. [MTP devices: cameras and phones over USB](#9-mtp-devices-cameras-and-phones-over-usb) — first step done, README §3.14
10. [Listening tiles, animated WebP, video thumbnails, drop, find, SAF](#10-listening-tiles-animated-webp-video-thumbnails-drop-find-saf) — requested 2026-10-29

---

## 1. Previews for more file types

### 1a. Text previews — done (README §3.10)

Still open:

- **Folder tiles** of folders with **no** media: build the collage from the next-best previews — covers (audio, EPUB, CBZ) → PDF first pages → text previews. Only when nothing previewable is found, show the item count as today. Text previews are `ALPHA_8` bitmaps tinted by the tile, so the collage must tint them too.
- **CSV:** align the columns of the first lines. Markdown already gets bold headings.

### 1b. Other useful formats (research)

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

### 3.1 What counts as an audio folder

- **Audio files:** `mp3 m4a m4b aac flac ogg oga opus wav wma mka aiff ape wv amr mpc`, and MP4 files with an `M4A` / `M4B` / `M4P` brand (`FileTypes`, done).
- A folder is an **audio folder** when all its visible files are audio, apart from these companion files: images (covers), `lrc srt vtt txt nfo cue m3u m3u8 pdf`. Hidden files are ignored.
- A **lecture folder** has only long videos (§3.6).
- A **mixed folder** has audio files **and** videos (decided): its audio and video files play as **one queue**, in book mode by default. Any audio file in a folder makes that folder play in book mode.
- **Every audio folder starts in book mode** (decided): files play in order (§3.5), and the exact position is remembered.
  - **Shuffle** is the only switch, and it is remembered **per folder**. Turning it on makes the folder a music folder:
    - files play in random order (stable, without repeats);
    - a file opened later starts from the beginning;
    - skip is ±10 s instead of ±30 s.
  - The last file and position are still remembered in music mode, so the folder button resumes the current track.
  - Turning shuffle off returns the folder to book mode, continuing in order from the current file.
- Audio files in a folder with images: tapping one opens the player with the folder's audio files as the queue, starting at that file.

### 3.2 Folder tile play button

- Audio folders get a round **▶ play / resume** button over the collage (bottom-right corner, 40 dp touch target). Tapping it starts or continues playback without opening the folder, and the mini player (§4.3) appears.
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
- Samsung / Xiaomi battery savers may kill the service. If playback is stopped by the system, show a one-time hint linking to the battery optimisation settings.
- Stop the service, and remove the notification, after it has been paused for about 30 min, or when it is swiped away while paused.

### 3.4 History: resume where it stopped

- A **separate** SQLite database, `history.db` (`HistoryStore`), with real migrations. Not `MetaStore`: that one is a disposable cache — it drops its table on every version bump and prunes rows unused for 90 days, which would lose listening history. Tables:
  - `audio_folder`: folder path, current file name, position, speed, shuffle (on = music mode), shuffle seed and cycle state, total duration, finished flag, last played time;
  - `audio_file`: path + size + mtime, saved position, finished flag.
  - Per-file positions let a book be continued from any file, and show per-file progress in the list.
- Save every ~5 s while playing, and at once on pause, file change, seek and `onTaskRemoved`. Restore after a kill or reboot (same idea as the slideshow restore).
- **Smart rewind** on resume (common in audiobook apps): go back 0 s if paused < 1 min, 5 s if < 1 h, 15–30 s if longer. Make it a setting.
- A file counts as **finished** when played to within 1–2 % (or 30 s) of its end. Book mode moves on to the next file automatically. At the end of the last file, the book is marked finished, and the next tap asks "Start over?".
- A renamed or moved folder: try to match by its list of file names + sizes, so the history isn't lost. Optional.
- **Continue listening** row on the root screen, above Recent: the last 5 audio folders, each with its cover, progress and a ▶ button.

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
  - Per-folder override in ⋮: *Listen as audio* / *Not a lecture folder*, saved in `history.db`.
- **A tap on a video still watches it** (slideshow, or the external player for long videos). Listening starts only from the Play button.
- **Shared position:** the position saved while listening is the slideshow's and the external player's start position for that file, and the other way round.
- **Cover:** the video's tile frame is the cover art (player, lock screen, notification).
- **Subtitles:** embedded text tracks (MKV / MP4) and `.vtt` / `.srt` companions show as the scrolling text of §4.4.
- **No video in the player** (decided): the player never shows the picture, it only plays the sound. Watching stays in the folder view (tap the video).
- Videos without an audio track are skipped.

### 3.7 Play button in the folder view — top bar done (README §2, Listening); folder tiles open

There is no Play button in the folder view today: a slideshow starts by tapping a tile. Add one (decided):

- A **▶ Play** button in the top bar of the folder view (and `P` on a keyboard).
  - **Audio, mixed or lecture folder:** starts or resumes the book (`Continue 12 / 30` when there is history), and the mini player appears.
  - **Any other folder with media:** starts the slideshow from the first media item.
- Folder tiles: audio, mixed and lecture folders get the round ▶ / resume button and progress ring of §3.2.
- Not shown in selection mode.

## 4. Player screen

Opened from the mini player, from the folder ▶ button (long press), or by tapping an audio file. It follows the same principles as the rest of the app: dark, little chrome, large touch targets, with full keyboard and TV remote support.

### 4.1 Layout (phone, portrait)

```
┌──────────────────────────────────────┐
│ ←  Book / folder name            ⋮    │  top bar: back, folder name, overflow
│                                      │
│          ┌──────────────┐            │
│          │              │            │  cover (square, ≤ 40 % of the height);
│          │    COVER     │            │  blurred cover as the background
│          │              │            │
│          └──────────────┘            │
│   07 - The long chapter na…mp3       │  file name, max 2 lines, cut in the middle
│   Track 7 / 42 · 3 h 12 min left     │  position in the queue + book time left
│                                      │
│   ┄ previous subtitle line ┄         │  subtitles / lyrics (§4.4), 1–3 lines,
│   ▌Current line, highlighted         │  current line highlighted;
│   ┄ next line ┄                      │  tap → full-screen lyrics
│                                      │
│ 12:04 ━━━━━━━━━●──────────── -21:40  │  seek bar: elapsed / remaining
│ ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂ │  thin bar: whole-book progress
│                                      │
│   ⏮    ↺30    ( ▶ )    ↻30    ⏭  │  prev file, −30 s, play/pause, +30 s, next file
│   1.0×   🌙 sleep   🔀   🔁   CC     │  speed, sleep timer, shuffle, repeat*, subtitles
├──────────────────────────────────────┤
│ ✓ 05 - Intro.mp3               12:10 │  file list: duration, ✓ finished,
│ ◐ 06 - Something.mp3           34:02 │  ◐ partly heard (mini progress bar),
│ ▶ 07 - The long chapter…       33:44 │  current one highlighted and scrolled into view
│   08 - Next.mp3                28:15 │
└──────────────────────────────────────┘
🔀 shuffle is always shown; on = music mode for this folder (saved per folder)
* repeat only in music mode; skip is ±10 s in music mode
```

- The file list is a bottom sheet that can be dragged up to full height, so long lists don't push the controls off screen. It opens scrolled to the current file.
- Tapping a file plays it: from its saved position in book mode, from the start in music mode.
- **Landscape, tablet, DeX:** two panes, with cover + controls on the left and the list on the right.
- File name: show the **file name**, as requested, cut in the middle so the number and extension stay visible. Show the tag title (if any and different) as a second line in smaller text.
- Cover sources, in order: embedded art of the current file → `cover/folder/front.*` in the folder → the only image in the folder → a generated placeholder (folder initial on a colour derived from the name).

### 4.2 Controls and gestures

| Action | Touch | Keyboard | TV remote / headset |
| --- | --- | --- | --- |
| Play / pause | ▶ button, tap on the cover | `Space`, `K` | Play/Pause; headset single press |
| Skip back / forward 30 s (10 s in music mode; settable) | ↺ / ↻ buttons; double-tap the left / right half of the cover | `←` / `→`, `J` / `L` | `←` / `→`; headset triple / double press |
| Previous / next file | ⏮ / ⏭; horizontal swipe on the cover | `Shift` + `←` / `→`, `N` / `P` | Long press `←` / `→` |
| Seek | Drag the seek bar; a precise-seek bubble shows the target time | `Home` / `End` | — |
| Speed | 1.0× button → sheet with presets 0.75–3.0× and a ±0.05 stepper | `[` / `]` | — |

- **⏮ behaviour:** within the first 3 s it goes to the previous file; later it goes to the start of the current file (standard behaviour). Done in the mini player (README §2, Listening), with ⏭; the folder view marks and focuses the playing file.
- **Shuffle** (🔀, switches the folder to music mode): a stable shuffle without repeats, reusing `SlideOrder` (same rules as the slideshow random order). `SlideOrder` always wraps into a new cycle; with repeat off the queue must stop at the end instead (a flag). Its on/off state, seed and cycle progress are saved per folder.
- **Speed** is saved per book. **Skip silence** is a toggle in the speed sheet (`ExoPlayer.setSkipSilenceEnabled`).
- **Sleep timer:** 5 / 15 / 30 / 45 / 60 min, or end of the current file. The last 10 s fade out. Shaking the phone or tapping the notification while it fades adds 5 more min. 🌙 shows the remaining time.
- **Overflow menu (⋮):** no *Open with…*: other apps open only from the folder view (README §3.11). Open folder, file info (tags, bitrate, codec), share, mark book as unfinished / finished, reset history.

### 4.3 Mini player — done (README §2, Listening): full and collapsed, seek bar, book progress and bar, speed, sleep timer; open: player screen, cover

A 56 dp bar at the bottom of the folder and root screens while something plays or is paused. It shows the cover, file name, a thin progress line and ▶/⏸. Tapping it opens the player screen; swiping it down while paused stops playback. The grid gets bottom padding, so the last row isn't hidden.

### 4.4 Subtitles and lyrics for audio (research)

MP3 has no standard *embedded* subtitle track. Audio subtitles exist as companion files or tags. Support these, in this order:

| Source | Where | How | Prevalence |
| --- | --- | --- | --- |
| **`.lrc`** synced lyrics (`[mm:ss.xx] line`) | companion file with the same base name | own parser (~50 lines); also handle `[offset:]` and several timestamps on one line | common for music |
| **`.srt` / `.vtt`** | companion file with the same base name | Media3 `MediaItem.SubtitleConfiguration`; the text renderer should work without a video track (**verify**), otherwise parse it ourselves | audiobooks transcribed with Whisper and similar tools |
| **ID3 `SYLT`** (synced lyrics frame) | inside MP3 | Media3's `Id3Decoder` probably returns it as a raw `BinaryFrame` → parse it ourselves (**verify**) | rare |
| **ID3 `USLT`, Vorbis `LYRICS` / `UNSYNCEDLYRICS`, MP4 `©lyr`** | inside the file | `Metadata` from the player; if the text looks like LRC, treat it as synced | fairly common, mostly unsynced |

- Synced text: 1–3 lines under the title, with the current line highlighted. Tapping it opens full-screen lyrics that follow the playback; tapping a line seeks to it.
- Unsynced text: a "Lyrics" button opens it as scrollable text.
- **CC** button: on / off, saved per folder. It is hidden when the file has no text.

### 4.5 Chapters (audiobooks)

- `m4b` / `m4a` chapter markers (QuickTime chapter track or Nero `chpl` atom). Media3 exposes these only partially (**verify**); otherwise parse the `moov` atoms ourselves.
- A **CUE sheet** for one large `mp3` / `flac` file.
- With chapters, ⏮/⏭ step by chapter, the list shows chapters, and the seek bar shows chapter ticks.

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

- **Replace** (find is done, §10.6).
- **Read-only files** (1–10 MB) always wrap: the line list has no sideways scroll.
- **Verify on a device:** typing speed in a 1 MB file (the text field lays out the whole text on each change), colouring speed near the 200 K limit, the keyboard over the text, pinch next to text selection, `Tab` / `Esc` on DeX keyboards, the safe save on FAT / exFAT through FUSE, and whether breadcrumbs fit at 360 dp with the phone ⋮ menu.

---

## 7. Linux desktop version

A separate native desktop app (Rust + GTK 4 + libadwaita + GStreamer) with the same features and a real desktop UI. The full task, stack choice, UI spec and investigation findings are in [docs/LINUX_DESKTOP_SPEC.md](docs/LINUX_DESKTOP_SPEC.md). The earlier idea of sharing the Kotlin / Compose code with a desktop build was dropped (spec §13.3).

---

## 8. Sorting, mini player and long names

Requested 2026-10-25. The Linux app gets the same features (spec §4.3, §4.8).

- **Name order for folders without images.** A new default sort, *Automatic*: folders with images or GIFs sort by date, newest first (as now); every other folder (documents, audio, books, lectures, folders of folders) sorts by name in natural order (`a1`, `a2`, `a10`, using `NaturalOrder`). The first listing batch decides; the result is remembered with the folder, so the next visit starts in the right order.
- **Sort remembered per folder.** The sort menu (name, date modified, size, type; ascending / descending; folders first) applies to the open folder and is saved for it between runs (DataStore, keyed by path, ≤ 5,000 folders, least recently opened dropped first). *Automatic* removes the folder's own choice; *Use for all folders* sets the default. The slideshow follows the folder's sort.
- **⏮ / ⏭ in the mini player** (§4.3): icon buttons for the previous and next file around ▶/⏸, with the §4.2 rule (⏮ within the first 3 s → previous file, later → start of the file); disabled at the ends of the queue; also from the media session (lock screen, headset). The bar may need more room at 360 dp **verify**.
- **The playing file is selected:** with the book's folder open, the playing file's tile gets a *now playing* badge and is scrolled into view on every file change; it is selected only when no other selection is being made (selection mode off, or only the previously playing file selected).
- **Scrolling long names** (marquee): every file and folder name that doesn't fit its tile scrolls sideways in a continuous loop (the name, a gap, the name again, without stopping), so the whole name can be read; names that fit don't move (`Modifier.basicMarquee(iterations = Int.MAX_VALUE)`, Compose 1.8). Off when *Remove animations* is on; names are then shortened as now. **verify** scrolling smoothness with a full screen of long hash names.

---

## 9. MTP devices: cameras and phones over USB

Requested 2026-10-25. **First step done (2026-10-25, not yet tried on a device):** the home screen *Devices* section, browsing, device thumbnails, the slideshow from a local mirror, *Copy to…* and *Open with* (README §3.14). The rest of this section is open.

**Goal.** A camera, another phone, an action cam or a media player plugged into the phone or tablet (USB OTG, USB-C) shows up on the home screen. Its photos and videos can be browsed with thumbnails, watched in the slideshow and copied to this device, as easily as an SD card.

### 9.1 Findings

- Such devices don't mount as a folder: they speak **MTP** (phones, players) or **PTP** (cameras; MTP is an extension of it). Android has no path for them; apps see them only through **`android.mtp.MtpDevice`** on top of **`UsbManager`** (USB host mode, API 12+). Everything the app needs is there on API 26: storages (`getStorageIds`, `getStorageInfo`: name, free and total space), folder listing (`getObjectHandles(storage, 0, parent)` + `getObjectInfo`), the device's **own thumbnails** (`getThumbnail`: a ~160 px JPEG made by the camera or phone, so no download is needed for tiles), chunked reads (`getPartialObject`, API 24), whole-file copies (`importFile`), and, for later, `deleteObject`, `sendObject` and change events (`readEvent`, API 24).
- **Permission:** each device needs the user's OK through the system prompt (`UsbManager.requestPermission`); it lasts until the device is unplugged. No manifest permission is needed. Android 12+ requires a mutable `PendingIntent` for the answer.
- **One owner at a time.** Android's own `MtpDocumentsProvider` (behind the Files app) may already hold the device; `MtpDevice.open` then fails. **verify** on Pixel and Samsung: does the provider let go when the Files app is closed, and is there a reliable way to take over?
- **The other way, the Storage Access Framework** (the user picks the device in the system picker, files come as `content://` URIs) would also work and avoid the conflict, but the app is built on file paths, and the SAF fallback was left out on purpose (README §6). MTP directly keeps the home screen entry and the device thumbnails.
- **Phones as devices** show no storages until they are unlocked and set to *File transfer*.
- **Speed:** a USB 2 MTP link moves 10–40 MB/s, and each `getObjectInfo` is one round trip (2–10 ms), so a folder of 10,000 camera files takes 20–100 s to list. The listing must stream like local folders do, and thumbnails must not wait behind a download.
- **Slideshow, players and decoders read local files** (Coil, ExoPlayer, `MediaMetadataRetriever`, `GifCodec`). Streaming from the device into each of them would need a data source per decoder; copying into a cache first works with all of them at once.
- **Linux** is not part of this: the desktop port is on hold (todo §7).

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
- **Thumbnails:** the device's own JPEG for photos and videos; folders and other files keep their icons. No collages, no 5-frame strips on device folders.
- **File types** from the extension, else from the MTP object format (JPEG, PNG, GIF, MP4, MP3, …); no magic-byte sniffing (it would need a download).
- **Read-only for now:** no *Delete*, *Move*, *Share*, *Ctrl* + C, new files, favourites or editing. *Open*, *Select*, *Copy to…* and *Open with…* remain.
- No live updates yet (no folder watcher on the device).

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

1. **Delete on the device** (`deleteObject`) with the same 5 s *Undo* as local files: the file is held back until the snackbar closes, then deleted (no device trash exists).
2. **Copy to the device** (`sendObject`): *Copy to…* / *Move to…* with a device folder as the destination, and new folders.
3. **Live updates** from device events (`readEvent`: object added / removed), for a camera shooting while connected.
4. **Streaming playback** instead of the mirror for long videos: a Media3 `DataSource` over `getPartialObject` (seeking needs random access; **verify** read speed). Also the book player for audio folders on a device.
5. **Folder collages** from the device thumbnails of the first few files.
6. **Auto-open:** an intent filter for `USB_DEVICE_ATTACHED` with a device filter (class 6, PTP) so Android offers *"Open Media Viewer?"* when a camera is plugged in; it also grants the permission.
7. **Remember devices:** a stable key (vendor id, product id and serial number, readable after permission) so recent folders and favourites can point into a device, shown dimmed while it is unplugged.
8. **Thumbnails for files the device has none for** (some cameras for RAW, many players): download small images (≤ 8 MB) into the mirror and decode them as usual.
9. **Tests:** `MtpPaths` is covered (`MtpPathsTest`). A fake `MtpConnection` would allow tests of the listing batches, the watchdog and the mirror budget.
10. **verify** on devices: a Canon / Nikon / Sony camera in PTP mode, an Android phone in MTP mode (locked, then unlocked), a Kindle or MP3 player; 10,000-file listing speed; `getThumbnail` for videos on phones; behaviour when Android's Files app holds the device; unplugging during a download; Android TV boxes with USB host.


---

## 10. Listening tiles, animated WebP, video thumbnails, drop, find, SAF

Requested 2026-10-29. Plan first, then the work; the findings of the research items are in [docs/RESEARCH_2026-10.md](docs/RESEARCH_2026-10.md). No device and no Android SDK were available (the SDK download host is blocked in the build container), so only the pure-Kotlin parts are compiled and unit-tested; everything else is **verify** on a device.

| # | Request | Kind | Plan |
| --- | --- | --- | --- |
| 10.1 | Folder tiles of books being listened to show where we are | build | **done** (README §2 Listening, §3.12) |
| 10.2 | Our own player for animated WebP / AVIF strips in tiles | research + build (WebP) | **WebP done** (README §3.4); AVIF and APNG: research doc §2 |
| 10.3 | Faster video thumbnails, per format | research | **done**: findings and ranked proposals in the research doc §3; next step: the strip disk cache |
| 10.4 | Full player screen, speed, sleep timer, shuffle, chapters, lyrics | next session | prompt in [docs/NEXT_SESSION.md](docs/NEXT_SESSION.md) |
| 10.5 | Drop files and folders into the app (DeX, Chromebooks, split screen) | research + build (files) | **done** for the folder grid (README §2 Desktops, §3.9); open: research doc §5 |
| 10.6 | Text editor: find, rename from the editor | build | **done** (README §2 Text editor) |
| 10.7 | Storage Access Framework fallback (for a Play Store build) | research | **done**: options and a recommendation in the research doc §7; needs an owner decision |
| 10.8 | Text editor: colour themes and richer colouring | build | planned, below |
| 10.9 | Text editor and text previews: the best monospaced font available | build | **done**: JetBrains Mono NL bundled (README §6, *Code font*) |

**Verify on a device:** the tile pill at 360 dp with 2–3 columns and in DeX; the label switching while a book plays (one tile recomposes per second); animated WebP strips of large stickers (memory: one full-size canvas) and of WebPs with odd offsets; the editor scrolling to the current match while the find field has focus (Compose scrolls a text field to its selection **verify** without focus); drops from My Files, Chrome, Gmail and Gallery in DeX, and a dropped folder.

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

### 10.5 Drop into the app

- `Modifier.dragAndDropTarget` (Compose 1.8) on the folder grid. Accepts drops of `content://` and `file://` URIs; `requestDragAndDropPermissions` keeps read access during the copy.
- A drop on the grid **copies** into the open folder (never moves: the source app owns its files), with the existing progress, Stop, `freeName` (" (1)") and error summary of *Copy to…*. Our own tiles dropped back onto the same folder do nothing.
- The grid shows a highlighted outline while a drag with files is over it.
- **Folders:** a dropped directory (`vnd.android.document/directory`) is copied recursively when the drag permission allows listing its children; otherwise it is reported as skipped (**verify** with My Files in DeX).

### 10.6 Text editor: find and rename

- **Find:** `Ctrl` + F or the 🔍 button opens a bar under the top bar: the query, `3 / 17`, previous / next (`Enter`, `Shift` + `Enter`, `F3`, `Shift` + `F3`), *Match case*, ✕ (`Esc`). All matches get a background colour, the current one is selected and scrolled into view; in read-only files the list scrolls to its line. Matching is pure Kotlin (`TextSearch`, unit-tested), plain text, case-insensitive by default, capped at 10,000 matches.
- **Rename:** a tap on the file name in the editor bar (or `F2`) opens a rename dialog with the name selected up to the extension; the same name rules as new files; never overwrites. The unsaved text, undo history and draft follow the new name; the colouring follows the new extension.
- Replace is not part of this step.

### 10.8 Text editor: colour themes and richer colouring

Requested 2026-10-29, planned. Syntax colours exist today (`Highlight`, 11 lexer families, one light and one dark palette in `EditorScreen.rememberStyles`). The addition:

- **Colour themes.** A theme is a named palette for the 12 `Role`s + background, text, selection, current line and find marks (§10.6). Built in: *Default* (today's), *One Dark*, *Solarized* (light / dark), *GitHub* (light / dark), *Monokai*, *Dracula*, *High contrast*. The default follows the app's day / night; a chosen theme is kept (settings, `editorTheme`). The palettes live in one pure-Kotlin table (`EditorTheme`), tested for contrast: each role ≥ 4.5 : 1 against its background (WCAG AA), checked in `EditorTest`.
- **Where:** editor ⋮ → *Theme…* (a list with a live 3-line sample per theme), and Settings → *Text editor*. Text previews on tiles stay tinted by the tile (README §3.10), not themed.
- **Richer colouring** in the editor:
  - **current line** background;
  - **matching bracket** (`()[]{}`, and tags in markup) highlighted next to the cursor, found by a bounded scan (±20,000 characters) that skips strings and comments using the existing spans;
  - **whitespace marks** (optional: tabs as `→`, trailing spaces);
  - **line numbers** in a gutter (optional; read-only files too), in the theme's muted colour.
- **Limits unchanged:** no colours above 200 K characters (README §3.13); bracket matching and the current line still work there.
- **Verify:** typing speed with the extra spans in a 1 MB file on a low-end phone; the `TextFieldValue` editor draws the gutter and current line from `onTextLayout` (line tops), so check scrolling smoothness.

### 10.9 The best monospaced font available

Requested 2026-10-29. **Done** the same day, with two changes to the plan below: the **no-ligature** edition (*JetBrains Mono NL*) is bundled instead of turning ligatures off by font features (smaller, simpler), and the crash screen keeps the system font. Hinting is dropped (high-density screens don't use it): ~250 KB for regular, bold and italic. Still to verify on a device: text-preview line fit and the editor's look at 10–12 sp. Today code, logs and tables use the system `monospace` (Droid Sans Mono on most phones, Samsung's own on One UI; no ligatures, weak Cyrillic on some builds).

- **Bundle JetBrains Mono** (SIL Open Font License 1.1): the variable TTF subset to Latin, Latin-1, Cyrillic, Greek and box drawing / arrows is ~150–200 KB (regular + bold via the `wght` axis, or two static files ~110 KB each). Clear `0/O`, `1/l/I`, tall x-height for small sizes, full Cyrillic — the main reason over Fira Code / Source Code Pro / Cascadia Code, which are the alternatives considered (all OFL; Cascadia's Cyrillic is good too, but it is larger).
- **Ligatures off** by default (`fontFeatureSettings = "liga 0, calt 0"`) — code reads character by character in an editor; a setting can turn them on.
- **Used in:** the editor (`textStyle`), monospace text previews on tiles (`TextPreview`: `Typeface` from `res/font`, loaded once), the crash screen and the error log. Proportional text stays the system font.
- **Setting:** *Code font*: JetBrains Mono (default) / System monospace. The system choice stays for devices where the user installed their own font (Samsung font styles change `monospace` too).
- **Not chosen:** Google Fonts *downloadable fonts* (no APK size, but needs Google Play services — missing on many TV boxes and Huawei devices — and a network fetch on first use); the APK grows by ~0.2 MB instead (release APK ~8.3 MB today).
- **Steps:** add `res/font/jetbrains_mono.ttf` (subset with `pyftsubset`, keep `OFL.txt` in `app/src/main/assets/licenses/` and a line in Settings → About), `FontFamily(Font(R.font.jetbrains_mono, …))` in one place (`ui/common/Fonts.kt`), replace the three `Monospace` uses and `Typeface.MONOSPACE`. **Verify** that the text-preview line count per tile (README §3.10) still fits, since the metrics differ from Droid Sans Mono.
