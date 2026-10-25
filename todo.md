# TODO — feature enhancements

Planned work after the current feature set (README §1). Each section lists the goal, the proposed UI, the implementation notes for this codebase, and open questions. Items marked **verify** are assumptions that still need a check on a device or in the library source.

Priority order: **3 → 4 → 1b**. Finished items move to the README; what is left here is open work.

**Done (2026-10-24, not yet tried on a device):** text previews (README §3.10); opening files with other apps, including long videos in an external player (README §3.11); the core of §3 — background service, `history.db`, audio / mixed / lecture folders, playlist order, the folder Play button and the mini player (README §3.12).

**Done (2026-10-25, not yet tried on a device):** the text editor with new text files and folders (§6; README §3.13).

**Still open in §3 / §4:** shuffle and music mode (§3.1), the folder **tile** ▶ button and progress ring (§3.2), the battery-saver hint (§3.3), the per-file progress in the list, renamed-folder matching, the *Start over?* question (a finished book now simply starts over) and the *Continue listening* row (§3.4), the lecture override in ⋮, the shared position with the slideshow / external player, video frames as cover art and subtitles (§3.6), and the whole player screen (§4.1, §4.2, §4.4, §4.5).

Contents

1. [Previews for more file types](#1-previews-for-more-file-types)
2. ~~Open with the system~~ — done, README §3.11
3. [Audio, audiobook and lecture playback](#3-audio-audiobook-and-lecture-playback)
4. [Player screen](#4-player-screen)
5. [Things easy to forget](#5-things-easy-to-forget)
6. ~~Text files: create and edit~~ — done, README §3.13
7. [Linux desktop version](#7-linux-desktop-version) — separate app, spec in docs/LINUX_DESKTOP_SPEC.md

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

- **⏮ behaviour:** within the first 3 s it goes to the previous file; later it goes to the start of the current file (standard behaviour).
- **Shuffle** (🔀, switches the folder to music mode): a stable shuffle without repeats, reusing `SlideOrder` (same rules as the slideshow random order). `SlideOrder` always wraps into a new cycle; with repeat off the queue must stop at the end instead (a flag). Its on/off state, seed and cycle progress are saved per folder.
- **Speed** is saved per book. **Skip silence** is a toggle in the speed sheet (`ExoPlayer.setSkipSilenceEnabled`).
- **Sleep timer:** 5 / 15 / 30 / 45 / 60 min, or end of the current file. The last 10 s fade out. Shaking the phone or tapping the notification while it fades adds 5 more min. 🌙 shows the remaining time.
- **Overflow menu (⋮):** no *Open with…*: other apps open only from the folder view (README §3.11). Open folder, file info (tags, bitrate, codec), share, mark book as unfinished / finished, reset history.

### 4.3 Mini player — done (README §2, Listening); open: tap opens the player screen, swipe down to stop

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

- **Find** (`Ctrl` + F, with a search bar under the top bar) and **rename** by tapping the name in the editor bar.
- **Read-only files** (1–10 MB) always wrap: the line list has no sideways scroll.
- **Verify on a device:** typing speed in a 1 MB file (the text field lays out the whole text on each change), colouring speed near the 200 K limit, the keyboard over the text, pinch next to text selection, `Tab` / `Esc` on DeX keyboards, the safe save on FAT / exFAT through FUSE, and whether breadcrumbs fit at 360 dp with the phone ⋮ menu.

---

## 7. Linux desktop version

A separate native desktop app (Rust + GTK 4 + libadwaita + GStreamer) with the same features and a real desktop UI. The full task, stack choice, UI spec and investigation findings are in [docs/LINUX_DESKTOP_SPEC.md](docs/LINUX_DESKTOP_SPEC.md). The earlier idea of sharing the Kotlin / Compose code with a desktop build was dropped (spec §13.3).
