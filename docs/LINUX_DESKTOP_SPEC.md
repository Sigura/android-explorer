# Media Viewer for Linux — task and specification

A native desktop app for Ubuntu and other Linux desktops with the same features as the Android app ([README](../README.md), [original spec](SPEC.md)): browse local folders with tens of thousands of GIFs, videos and images, watch them as a slideshow, listen to books and lectures, and edit text files. It is **a real desktop app**, not the phone UI in a window. It follows GNOME conventions: header bar, sidebar, menus, mouse and keyboard first, multi-selection without a mode, drag and drop, system trash, and *Open with*.

Written 2026-10-25. The findings that led to these choices are in §13. Items marked **verify** still need a check during implementation.

## Contents

0. [Summary](#0-summary)
1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Technology choice](#2-technology-choice)
3. [Architecture](#3-architecture)
4. [Desktop UI](#4-desktop-ui)
5. [Feature parity with Android](#5-feature-parity-with-android)
6. [Platform services](#6-platform-services)
7. [Thumbnails, decoding and the GIF codec](#7-thumbnails-decoding-and-the-gif-codec)
8. [Slow operations, errors and crashes](#8-slow-operations-errors-and-crashes)
9. [Persistence](#9-persistence)
10. [Performance targets](#10-performance-targets)
11. [Testing](#11-testing)
12. [Build, packaging and milestones](#12-build-packaging-and-milestones)
13. [Findings from the investigation](#13-findings-from-the-investigation)
14. [Open questions](#14-open-questions)

---

## 0. Summary

| | |
| --- | --- |
| **Language** | Rust (stable, edition 2021) |
| **UI** | GTK 4 + libadwaita (gtk4-rs, libadwaita-rs) |
| **Video and audio** | GStreamer 1.24 (`playbin3`, `gtk4paintablesink` from gst-plugins-rs, linked into the app) |
| **Editor** | GtkSourceView 5 |
| **Images** | pure-Rust decoders in process (`image`, `gif`, `zune-jpeg`); C decoders (libheif, GStreamer, poppler) in killable helper processes |
| **Other previews** | PDF, e-books, comics, office, SVG, fonts, audio covers, RAW, archives, plus every installed system thumbnailer (§7.8) |
| **Files** | GIO: file monitors, volumes, trash, app launching, portals |
| **Storage** | TOML settings, JSON state, SQLite (`rusqlite`) |
| **Target** | Ubuntu 24.04 LTS and newer (GTK 4.14, libadwaita 1.5, GtkSourceView 5.12, GStreamer 1.24); X11 and Wayland |
| **Packages** | `.deb` first, Flatpak second |

---

## 1. Goals and non-goals

**Goals**

1. Every feature of the Android app (README §1) that makes sense on a desktop, with desktop interaction instead of touch interaction (§4, §5).
2. The same core principle: **never block, never repaint everything.** Folders stream in, only visible tiles decode, errors never interrupt, and every disk read, decode, DB call and child process runs off the UI thread under a watchdog.
3. Fits the desktop: system light and dark style, Files bookmarks, the system trash, *Open with*, copy and paste and drag and drop with the file manager, media keys (MPRIS), no screen blanking during a slideshow, HiDPI and fractional scaling.
4. Fast: the first tiles in < 100 ms, smooth scrolling while a 30k-file folder streams in, start-up < 300 ms to the first frame.

**Non-goals (for now)**

- Windows and macOS. The stack doesn't rule them out (GTK and GStreamer run there), but trash, volumes and launching are Linux-first.
- Sharing UI code with the Android app (§13.3 explains why).
- Network locations (SMB, MTP, Google Drive). GIO could reach them, but they break the streaming and watchdog assumptions. **Later**: mounted GVFS paths under `/run/user/$UID/gvfs` work as plain folders.
- Editing images or videos.
- TV-remote input, touch-first layouts, and the Android permission screens.

---

## 2. Technology choice

### 2.1 The stack

| Area | Choice | Why |
| --- | --- | --- |
| Language | **Rust** | Native speed for 30k-item folders and the GIF codec; threads without data races; memory-safe decoders that can run in process. The GNOME apps closest to this one are Rust + GTK 4: Loupe (image viewer), Showtime and Clapper (video players), Fractal, Amberol (music). |
| Toolkit | **GTK 4 + libadwaita** | The native toolkit of Ubuntu's desktop. Header bars, sidebars, adaptive layouts, toasts, preferences dialogs and the system style come for free. `GtkGridView` recycles widgets and is made for lists of 100k items. Native Wayland, fractional scaling, touchpad gestures. |
| Bindings | `gtk4` 0.9 (`v4_14`), `libadwaita` 0.7 (`v1_5`), `sourceview5` 0.9, `gstreamer` 0.23 | Versions that match Ubuntu 24.04's libraries. **verify** the exact crate versions at the start; newer crates are fine as long as the `v4_14` / `v1_5` feature flags are used. |
| Video, audio | **GStreamer 1.24**: `playbin3`; `gst-plugin-gtk4` (`gtk4paintablesink`) linked in as a crate | Hardware decoding (VA-API), every codec `gstreamer1.0-libav` has, and video in a `GdkPaintable` with zero copies, so overlays and transitions are plain GTK widgets. Ubuntu 24.04 has no `gstreamer1.0-gtk4` package, so the sink is built from the crate and registered at start-up. |
| Editor | **GtkSourceView 5** | Syntax colours for 150+ languages with light and dark schemes, undo and redo, search and replace, line numbers, current-line highlight. Replaces the Android app's own lexers and undo (`Highlight.kt`, `EditHistory.kt`). |
| Still images | `image` 0.25 (PNG, WebP, BMP, TIFF, ICO; JPEG through `zune-jpeg`), `kamadak-exif` for orientation and embedded thumbnails | Pure Rust: a broken file can't crash the app, so these decode in process. |
| HEIC, AVIF, JXL | `libheif-rs` (libheif 1.17 on Ubuntu 24.04), `jxl-oxide` (pure Rust) | libheif is C, so it runs in a helper process (§3.3). |
| GIF | Own codec: the `gif` crate for blocks and LZW, plus a port of `GifCodec`'s compositing, block scan and strip pass (§7.3) | Matches Pillow, like the Android codec. |
| Video frames, durations | GStreamer (`uridecodebin3` + `appsink`, accurate seeks) in a helper process | Same codecs as playback; a crash or hang kills only the helper. |
| Files | GIO (`gio::File`, `FileMonitor`, `VolumeMonitor`, `File::trash`, `AppInfo`, `gtk::FileLauncher`, `gtk::FileDialog`) | Native trash (shows in Files), mount events, the *Open with* portal dialog, and the native folder picker. |
| Archives | `zip`, `sevenz-rust2`, `tar` + `flate2` / `xz2` / `zstd` / `bzip2` / `lz4_flex`; `unrar` (extract only, helper process) | Compress and extract without depending on installed tools (§4.12) |
| Media keys | `mpris-server` (zbus) | The GNOME Shell media widget, headset and keyboard media keys. |
| Settings, state | `serde` + TOML (settings) and JSON (recent folders, session), written atomically | Readable files with no schema to install, unlike GSettings, so `cargo run` works with no install step. |
| Databases | `rusqlite` (bundled SQLite) | `meta.db` (metadata cache) and `history.db` (listening positions), the same schemas as Android. |
| Charsets | `encoding_rs` + a port of `TextDecode` | `encoding_rs` decodes Windows-125x, KOI8-R, CP866 and others. The port keeps the Cyrillic letter-frequency check that tells them apart. |
| Async | GLib main loop for the UI; a bounded thread pool for decoding; `async-channel` back to the UI; `glib::spawn_future_local` for UI-side futures | One rule: the UI thread only builds widgets and applies results. |
| Logging | `tracing` → stderr, the journal (`tracing-journald`) and the in-app error log | `journalctl --user -t media-viewer` replaces `adb logcat`. |
| Build | Cargo; Meson only if Flatpak or GNOME tooling needs it | — |

### 2.2 Alternatives considered

| Option | For | Against | Verdict |
| --- | --- | --- | --- |
| **Kotlin + Compose for Desktop** | Reuses the Kotlin code and much of the Compose UI | Material look inside GNOME; AWT, so Wayland only through XWayland and blurry fractional scaling; video only through vlcj, copying frames to the CPU (heavy with several playing tiles); no native trash, volumes, *Open with* or portals; JVM start-up ~1 s and 300+ MB of memory. Sharing the phone UI pulls the desktop app back toward a phone layout. | Rejected: it gives "the phone app in a window", which is what this task avoids |
| Qt 6 / QML (C++ or Python) | Mature, good multimedia (FFmpeg backend), fast list views | Looks foreign on Ubuntu's GNOME desktop; C++ or Python instead of a memory-safe fast language; a second ecosystem for trash, portals and theming | Good for KDE; second choice |
| Python + PyGObject (GTK 4) | Fastest to write | Too slow for the GIF codec and 30k-item sorting; the GIL limits decoding threads | Rejected |
| Tauri / Electron | Web UI | 30k tiles in a DOM, GIF frame scrubbing and video tiles are a poor fit; large memory use; not native-looking | Rejected |
| Rust + GTK 4 + libadwaita | Native look and behaviour, fast, safe, everything §6 needs is in GIO, GStreamer and GtkSourceView | No code shared with Android: the pure logic is ported (§13.4) | **Chosen** |

---

## 3. Architecture

### 3.1 Crates

```
media-viewer/                  (Cargo workspace; new repository, or a linux/ folder here)
  core/        pure Rust, no GTK: FileEntry, natural order, sorting, smart crop, slide timer,
               slide order (random cycles), Book + Playlist, TextDecode, TextFile (load/save/create),
               Drafts, file types + magic sniffing, GIF codec. Unit tests (ported from the Kotlin tests).
  data/        folder scanner + listing (streaming, sorted insert, GIO monitor), volumes, trash,
               file ops (copy/move with progress), MetaStore, HistoryStore, settings, state, error log,
               thumbnail scheduler + cache, helper-process client
  thumbnailer/ the helper process binary: video frames, HEIC/AVIF, PDF pages, system thumbnailers (§7.8);
               talks over stdin/stdout
  app/         GTK: application, windows, widgets, actions, shortcuts, MPRIS, crash screen
```

`core` builds and tests with plain `cargo test`, without GTK installed.

### 3.2 Threads

| Thread | Does | Never does |
| --- | --- | --- |
| UI (GLib main loop) | builds and binds widgets, applies results, handles input | file I/O, `stat`, decoding, SQLite, waiting on a lock held by a worker |
| Listing (one per open folder) | `read_dir` streaming, `stat`, magic sniff (200 B), batches of 50 entries or 16 ms | decoding |
| Decode pool, `max(2, cores / 2)` | stills, GIF block scans and strips, text previews | blocking forever: each job has a watchdog |
| IO pool | SQLite, settings writes, copies and moves, trash | — |
| Helper processes (§3.3) | video frames and durations, HEIC/AVIF | — |
| GStreamer streaming threads | playback (GStreamer's own) | — |

**Abandonable calls** (port of `Blocking.kt`): a call on a hung USB drive can't be cancelled in Rust, so it runs on a detached thread; when its watchdog fires, the caller gets `TimedOut` and stops waiting, and the thread is left to finish on its own. Detached threads are capped (16); above the cap, new calls on that volume fail with *"Drive not responding"* at once.

### 3.3 Helper processes

C decoders and GStreamer can crash or hang in native code, which a watchdog thread can't stop. They run in `media-viewer-thumbnailer`, a separate binary:

- a pool of `max(1, cores / 4)` long-lived processes, started on demand;
- requests and replies are length-prefixed messages over stdin/stdout (`bincode`): `VideoMeta { path }`, `VideoFrames { path, positions: [10, 30, 50, 70, 90 %], max_px }`, `Still { path, max_px }`; frames come back as raw RGBA, or through a `memfd` for large ones;
- a request over its watchdog → the process is killed (`SIGKILL`) and restarted, and the file is marked *timed out* (hourglass icon, §7.5). A crash → the file is marked *broken*;
- at most one request per process at a time, so a bad file can only take its own request down.

This is stronger than Android, where `MediaMetadataRetriever` runs in the media server but `ImageDecoder` runs in the app.

### 3.4 Data model and lists

- `FileEntry { path, name, kind, size, mtime, other_kind }`, the same as `domain/model/FileEntry.kt`, in `core`; the GTK side wraps it in a GObject (`EntryObject`) holding the per-tile state (`Placeholder → Loading → Ready | Failed | TimedOut`) and the thumbnail.
- The folder grid: `gio::ListStore<EntryObject>` → `gtk::FilterListModel` (hidden files, name filter) → `gtk::MultiSelection` → `gtk::GridView`. The listing inserts batches at their sorted position itself (natural order, as `FolderListing` does), so no `SortListModel` re-sort happens on every batch; changing the sort rebuilds the store in one splice.
- Items are keyed by path; a live update changes one item, and `GridView` rebinds only that tile.

### 3.5 Files on disk

| What | Where |
| --- | --- |
| Settings | `$XDG_CONFIG_HOME/media-viewer/settings.toml` |
| Recent folders, favourites, slideshow session, window state | `$XDG_STATE_HOME/media-viewer/state.json` |
| Listening history | `$XDG_DATA_HOME/media-viewer/history.db` |
| Drafts (unsaved editor text) | `$XDG_DATA_HOME/media-viewer/drafts/` |
| Error log (last 200), crash reports | `$XDG_STATE_HOME/media-viewer/error_log.txt`, `last_crash.txt` |
| Metadata cache | `$XDG_CACHE_HOME/media-viewer/meta.db` |
| Strip cache (optional, §7.6) | `$XDG_CACHE_HOME/media-viewer/strips/` |
| Shared thumbnails (read and write) | `$XDG_CACHE_HOME/thumbnails/{normal,large,x-large,xx-large}/` (freedesktop spec) |

---

## 4. Desktop UI

### 4.1 Main window

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ☰ │ ← → │ 🏠 › Videos › 2026 › trips          │ 🔍 │ ▶ Play │ ⊞▾ │ ⇅▾ │ ⋮ │ ─ □ ✕ │  header bar
├────────────────┬─────────────────────────────────────────────────────────────────┤
│ ★ Favourites   │ ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐      │
│  ▾ GIFs        │ │  ..  ││ ▦▦   ││ ▦▦   ││      ││      ││ GIF  ││ 2:31 │      │
│     ▾ 2026     │ │      ││ ▦▦   ││ ▦▦   ││  img ││  img ││      ││   ↗  │      │
│        • trips │ └──────┘└──────┘└──────┘└──────┘└──────┘└──────┘└──────┘      │
│  ▸ Lectures    │ ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐      │
│ 🕘 Recent       │ │      ││      ││      ││ ≡≡≡  ││ 📄   ││      ││      │   ▐  │  fast-scroll
│    memes       │ │      ││      ││      ││ ≡≡≡  ││      ││      ││      │   ▐  │  thumb
│ 🖥 This computer│ └──────┘└──────┘└──────┘└──────┘└──────┘└──────┘└──────┘      │
│  ▸ 💽 System   │                                                                 │
│ 🗑 Trash     12 │                                         3 selected · 48.2 MB   │  floating status
├────────────────┴─────────────────────────────────────────────────────────────────┤
│ 🎧 Lecture 03 — Course name    01:12:04 / 1:34:00   ⏪30  ⏯  30⏩   ✕             │  mini player
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **Window:** `AdwApplicationWindow` with `AdwOverlaySplitView` (sidebar | content) and `AdwToolbarView` (header bar on top, mini player at the bottom). The size, position-independent maximised state and sidebar width are saved. Below 600 px the sidebar collapses into an overlay (F9 or ☰ shows it).
- **Sidebar** replaces the Android root screen: **Favourites as an expandable folder tree** (shared with Files' bookmarks), **Recent**, **This computer** (home, file system, drives as tree roots) and **Trash**; details in §4.11. A drive being removed → toast *"Storage removed"*, and the view moves to the nearest existing parent (README §3.5).
- **Header bar:** back / forward (folder history, `Alt` + ← / →, mouse buttons 8 / 9), the **path bar** (breadcrumbs with folder menus that turn into a location entry with suggestions, §4.2), search (`Ctrl` + F: filter by name, as you type), **▶ Play / 🎧 Listen** (`P`, same rules as Android §2 Listening), the **view menu** ⊞ (thumbnail size slider, *Automatic size*, tile mode smart / crop / fit, *Show hidden files* `Ctrl` + H, *Always show names*), the **sort menu** ⇅ (name / date / size / type, descending, folders first), and the **main menu** ⋮ (*New tab* `Ctrl` + T, *New window* `Ctrl` + N, *New text file…* `Ctrl` + Alt + N, *New folder…* `Shift` + `Ctrl` + N, *Add to favourites* `Ctrl` + D, *Undo* / *Redo* `Ctrl` + Z / `Ctrl` + `Shift` + Z, *Paste* `Ctrl` + V, *Select by pattern…* `Ctrl` + S, *Open in Terminal*, *Preferences* `Ctrl` + `,`, *Error log*, *Keyboard shortcuts* `Ctrl` + ?, *About*).
- The header bar stays visible; it doesn't hide on scroll. On a desktop the space is there, and a bar that moves under the pointer is annoying.
- **Floating status** (like Files): item count, or the selection count and total size, bottom-right over the grid, only while there is a selection or while the folder is still listing (*"Reading… 12,480 items"*).
- **Toasts** (`AdwToastOverlay`) replace snackbars, with the same grouping (*"12 files couldn't be read"* + **Details**) and **Undo** for delete and other file operations (§4.12). A **progress ring** in the header bar appears while file operations run (§4.12).

### 4.2 Path bar and location entry

Keyboard-first navigation matters more on a desktop than anything touch-related. The path bar has two states, like Windows Explorer's address bar, with GNOME styling.

**Breadcrumbs (default state)**

```
│ 🏠 › Videos › 2026 ▾› trips ▾ │
             └──────────────┐
                            │ 2024        │
                            │ 2025        │
                            │ ✓ 2026      │
                            │ archive     │
                            └─────────────┘
```

- Each segment opens its folder on click. A **▾ chevron** after each segment opens a menu of that folder's subfolders (natural order, hidden ones only with *Show hidden files*, the current one checked), so a sibling or a child folder is two clicks away without typing. The last segment's chevron lists the current folder's subfolders.
- The menus are filled on the IO pool; a folder with thousands of subfolders shows the first 500 plus *"Type to filter…"*, which switches to the location entry with that folder typed in.
- The volume is an icon (as on Android), so long volume names never push the path off the bar. When the path doesn't fit, the leftmost segments collapse into a `…` menu.
- Right-click on a segment: *Open in new window*, *Copy path*, *Add to favourites*, *Properties*. Middle-click opens in a new window. Dropping files on a segment copies or moves them there (§4.5).
- **Back / forward history:** right-click or long press on ← / → lists the last 20 folders.

**Location entry (editing state)**

- Opened by **F4**, `Ctrl` + L, `Alt` + D, a click on the empty part of the path bar, or by typing `/` or `~` in the grid (as in Files). The full path is shown, selected, so typing replaces it.
- **Suggestions while typing** (a popover under the entry, updated ~50 ms after each key, filled on the IO pool, never blocking typing):
  1. subfolders of the folder typed so far that start with the typed name (case-insensitive, natural order), with `.`-folders only when the typed name starts with `.`;
  2. recent folders, favourites and bookmarks whose path or name contains the text;
  3. mounted volumes by name.
- **Keys in the entry:** ↓ / ↑ move through suggestions; **Tab** completes the common prefix, then the selected suggestion, and adds a `/`; Enter opens; `Alt` + Enter opens in a new window; Esc puts the breadcrumbs back unchanged; **F4** again (or `Alt` + ↓) opens the list of recent locations without typing (Explorer behaviour).
- **Input rules:** `~` expands to home; `$VAR` expands; relative paths resolve from the current folder; `..` works; a `file://` URI or a path pasted from elsewhere works; a path to a **file** opens its folder with that file focused and selected; a path that doesn't exist shows a red outline and the closest existing parent as the first suggestion, never an error dialog. Unmounted or slow paths are checked under the watchdog.
- Suggestions and the existence check run on the IO pool; a hung drive leaves the entry usable and shows a small spinner.

**Focus and function keys (whole window)**

| Key | Action |
| --- | --- |
| F4, `Ctrl` + L, `Alt` + D | Location entry |
| `Alt` + ↓ (path bar focused) | Open the focused segment's folder menu |
| F6 / `Shift` + F6 | Cycle the focus: sidebar → path bar → grid → mini player |
| F9 | Show / hide the sidebar |
| F10 | Main menu |
| F2 | Rename |
| F3, `Ctrl` + F | Filter by name |
| F5, `Ctrl` + R | Reload |
| F11 | Full screen for the window (the standard GNOME toggle; nothing opens in full screen by itself) |
| `Alt` + Home | Home folder |
| `Alt` + ↑, `Backspace` | Parent folder |
| `Alt` + ← / → | Back / forward |
| `Alt` + 1…9 | Tab 1…9 (§4.13) |
| `Ctrl` + T / `Ctrl` + W | New tab / close tab (the window when it is the last tab) |
| `Ctrl` + D | Add / remove favourite |
| `Ctrl` + ? | Keyboard shortcuts window |

With the path bar focused, ← / → move between segments and Enter opens the focused one. Every action is also in a menu, so nothing is keyboard-only.

### 4.3 Folder grid

- **Order:** `..` tile (shown only when there is a parent; it's also `Backspace` / `Alt` + ↑), folders, media, other files; natural sort; default date, newest first. The sort and *folders first* are global settings, as on Android. **Later:** per-folder sort.
- **Thumbnail size:** automatic from the window width until changed (one column per ~200 px at scale 1), steps of 16 px from 96 to 512 px. `Ctrl` + wheel, touchpad pinch, `Ctrl` + `+` / `-` / `0` (0 = automatic) and the slider in the view menu. Kept between runs.
- **Fast-scroll thumb** on the right edge, with a bubble showing the letter, date or size of the item at that position, by the current sort.
- **Tiles** behave as in README §2 "Tiles":
  - **Folder:** a collage of up to 4 previews, picked cheap-first (mid-size stills, then GIF first frames, then videos; 2 levels deep, 20 candidates); further groups cycle every ~3 s while idle, staggered; hover picks a group by pointer position. No media → item count. Folder tiles also accept drops (§4.5).
  - **Image:** smart crop (crop if ≤ 10 % is lost, else fit; mode and threshold are settings).
  - **GIF / video:** 5 frames at 10–90 %. The **pointer position** over the tile picks the frame (scrub); resting the pointer, or keyboard focus, cycles them every ~400 ms. Badge `GIF` or the duration; ↗ for long videos that open externally. Large GIFs (default > 40 MB) show the first frame until hovered, then a small ring while the strip is made. While the folder is still listing, only first frames.
  - **Other files:** a symbolic icon by type (from the icon theme, by MIME type), name and size; partial downloads and broken / timed-out files get their own icons. Text and code files show **text previews** (README §3.10: first lines, monospace for code, the tile's text colour on a paper background, extension badge). PDFs, e-books, comics, office files, fonts, audio covers and more get real previews (§7.8); the icon is only the fallback.
  - **Names:** shown on hover, focus and selection, or always (setting). Long names are shortened in the middle. Media tiles without a thumbnail always show the name. A **tooltip** after 700 ms shows the full name, size, date and, for media, the resolution and duration.
- **HiDPI:** thumbnails are decoded at `tile px × scale factor`; a tile that becomes > 25 % wider (window maximised, moved to a HiDPI monitor, size changed) is decoded again after 300 ms of steady size; the old one stays meanwhile (README §3.4).

### 4.4 Mouse, keyboard and touchpad

Desktop rules everywhere: there is no "gallery clicks" mode and no selection mode. Selection is always available, as in a file manager.

| Action | Mouse | Keyboard | Touchpad |
| --- | --- | --- | --- |
| Select one item | Click | Arrows move the focus and the selection | Tap |
| Add / remove one | `Ctrl` + click | `Ctrl` + arrows move the focus only, `Ctrl` + `Space` toggles | — |
| Select a range | `Shift` + click; drag on the empty area (rubber band) | `Shift` + arrows | — |
| Select all / none / invert | — | `Ctrl` + A / Esc / `Ctrl` + I | — |
| Open (folder → enter; media → viewer; text → editor; other → default app) | Double-click (setting: single click, as in Files) | Enter | Double tap |
| Play a long video in the slideshow instead of externally | Context menu → *Play in slideshow* | `Shift` + Enter | — |
| Quick look (large preview, GIF / video playing, sound off) | Middle-click | `Space` (again or Esc closes; ← / → step) | — |
| Context menu | Right-click | `Menu` key, `Shift` + F10 | Two-finger tap |
| Scrub a GIF / video tile | Hover | `Shift` + ← / → on the focused tile | Hover |
| Thumbnail size | `Ctrl` + wheel | `Ctrl` + `+` / `-` / `0` | Pinch |
| Up / back / forward | Back tile; buttons 8 / 9 | `Alt` + ↑ or `Backspace` / `Alt` + ← / `Alt` + → | Two-finger swipe right / left **verify** (GTK gives swipes only with `GtkGestureSwipe` on touch screens; may need horizontal-scroll handling) |
| Jump to a name | — | Type the first letters (jumps to the first match; `Ctrl` + F filters instead) | — |
| Rename | Context menu → *Rename…* | F2 | — |
| Copy / cut / paste files | Context menu, drag and drop (§4.5) | `Ctrl` + C / `Ctrl` + X / `Ctrl` + V (compatible with Files, both ways) | — |
| Paste into the selected folder | Context menu | `Ctrl` + `Shift` + V | — |
| Copy path | Context menu → *Copy path* / *Copy* ▸ … | `Ctrl` + `Shift` + C | — |
| Undo / redo the last file operation | Toast *Undo*, main menu | `Ctrl` + Z / `Ctrl` + `Shift` + Z | — |
| Compress / extract | Context menu | `Ctrl` + `Alt` + C / — | — |
| Select by pattern | Main menu | `Ctrl` + S | — |
| Duplicate | Context menu | `Ctrl` + `Shift` + D | — |
| Grid / list view | View menu | `Ctrl` + 1 / `Ctrl` + 2 | — |
| New tab / next / previous tab | Middle-click a folder | `Ctrl` + T / `Ctrl` + PgDn / `Ctrl` + PgUp | — |
| Search subfolders | Search bar toggle | `Ctrl` + `Shift` + F | — |
| Open in Terminal | Context menu | `Ctrl` + `Alt` + T | — |
| Move to trash / delete permanently | Context menu | Delete / `Shift` + Delete (asks first) | — |
| Properties | Context menu → *Properties* | `Alt` + Enter | — |
| Show in Files | Context menu | — | — |
| New window / close tab or window / quit | — | `Ctrl` + N / `Ctrl` + W / `Ctrl` + Q | — |
| Reload | — | F5 / `Ctrl` + R | — |
| Go to a location | Click the path bar | F4, `Ctrl` + L, `/`, `~` (§4.2) | — |
| First / last item; a screen up / down | — | Home / End; PgUp / PgDn | — |

- The focus ring (an outline and a slight scale-up of the focused tile) shows only after keyboard use, as GTK does by default.
- The **context menu** offers, in groups: *Open*, *Open with…* (the system app chooser through `gtk::FileLauncher` with *always ask*), *Open in new tab* / *new window* (folders), *Play in slideshow* (long videos), *Listen* (audio and lecture folders), *Edit* (text files, when *Open text files in the app* is off), *Quick look* | *Cut*, *Copy*, *Copy* ▸ (*Path*, *Name*, *Folder path*, *Path for terminal*, *URI*), *Paste into folder* (folders), *Duplicate*, *Make link* | *Move to…*, *Copy to…*, *Rename…* | *Compress…*, *Compress to "name.zip"*, *Extract here* / *Extract to…* (archives) | *Move to trash* | *Add to favourites* (folders), *Set as wallpaper* (images), *Open in Terminal*, *Show in Files*, *Properties*. Clicking the empty grid area gives *New folder…*, *New text file…*, *Paste*, *Select all*, *Open in Terminal*, *Properties* of the folder. With several items selected, it acts on all of them and hides the single-item entries.
- **Quick look** replaces Android's "selected GIF and video tiles play in the tile": it's the desktop way to watch an item without leaving the folder (as GNOME Sushi and macOS Quick Look do). It is a borderless popup over the grid, sized to the item; it shows images, plays GIFs and videos looped and muted, and shows text with its colours.

### 4.5 Clipboard, drag and drop, copy path

**Copy, cut and paste** (`Ctrl` + C / X / V, context menu, and *Edit* entries in the main menu)

- `Ctrl` + C / X put the selected files on the clipboard as `gdk::FileList`, `text/uri-list`, `x-special/gnome-copied-files` (`copy` / `cut` + URIs, which Files and other GNOME apps read) and `text/plain` (paths, one per line). Pasting into Files, a mail client or a chat works; copying in Files and pasting here works too.
- Cut items are drawn dimmed until they are pasted or the clipboard changes. Pasting cut items moves them; the clipboard is then cleared.
- `Ctrl` + V pastes into the open folder; on a selected folder tile, or with `Ctrl` + `Shift` + V, into that folder. Copies run as file operations with progress (§4.12).
- **Paste content as a new file** (as Files does since GNOME 47): when the clipboard holds an image and no files, `Ctrl` + V saves it as `Pasted image 2026-10-25 14-03-11.png`; plain text is saved as `Pasted text 2026-10-25 14-03-11.txt`. The new file is selected, and F2 renames it at once.
- **In the viewer**, `Ctrl` + C copies the **picture** (`image/png` texture plus the file), so it pastes into chats, documents and image editors; `Ctrl` + `Shift` + C copies the path. In the editor, `Ctrl` + C copies text as usual.
- The *Paste* menu entry shows what it will do (*Paste 3 files*, *Paste image as file*) and is disabled when the clipboard holds nothing usable.

**Drag and drop**

- **Drag out:** dragging a tile (or the selection, if the tile is selected) offers `gdk::FileList`, `text/uri-list` and `text/plain` to other apps: Files, browsers, mail, chat, editors, terminals (which paste the quoted path). Folders can be dragged too. The drag icon shows the thumbnail with a count badge for several items.
- **Drop in:** on the grid → into the open folder; on a folder tile, a breadcrumb segment, a sidebar or tree entry, or a tab → into that folder. The default follows GNOME: **move** within one volume, **copy** across volumes; `Shift` forces move, `Ctrl` forces copy, `Ctrl` + `Shift` makes a symbolic link. While dragging, the drop target is highlighted and the pointer shows the action.
- **Spring-loaded folders:** holding a drag over a folder tile, a tree node or a breadcrumb for 1 s opens it, so files can be dropped deep without letting go; leaving goes back. Near the top or bottom edge the grid scrolls.
- **Drag within the app:** dropping on a folder tile in the same window moves the items; on the folder they came from, nothing happens. Esc cancels a drag.
- **Drops from browsers and other apps:** a dropped image (`image/*` data) is saved as a file, a dropped URL as a `.url` link file (later: download), and dropped text as a `.txt` file, named like pasted content.
- Name conflicts on drop or paste ask once (§4.12). Progress and Stop as for any copy.

**Copy path and location**

| Copy | How | Result |
| --- | --- | --- |
| Full path | `Ctrl` + `Shift` + C, context menu *Copy path* | `/home/me/Videos/trips/a.mp4` (several items: one per line) |
| Name only | context menu → *Copy* ▸ *Name* | `a.mp4` |
| Folder path | context menu → *Copy* ▸ *Folder path*; right-click on a breadcrumb or tree node → *Copy path* | `/home/me/Videos/trips` |
| Quoted for a terminal | context menu → *Copy* ▸ *Path for terminal* | `'/home/me/My Videos/a b.mp4'` (shell-quoted) |
| URI | context menu → *Copy* ▸ *URI* | `file:///home/me/My%20Videos/a%20b.mp4` |
| Current folder | `Ctrl` + L then `Ctrl` + C, or the path bar's context menu | the open folder's path |

A toast confirms (*"Path copied"*). Paths with non-UTF-8 bytes are copied as the URI form, so nothing is lost.

### 4.6 Viewer and slideshow

Double-clicking a media file opens the **viewer** inside the main window, not in full screen. It's a page in the same window (`AdwNavigationView`: the folder is below it, Back returns), as GNOME's image viewer (Loupe) and video player do. The slideshow is the viewer with *Play* on. All Android rules for what is shown and for how long carry over (README §2 Slideshow, §3.7); only the controls change.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ ← │ cat_jump.gif                      │ ⏮ ⏯ ⏭ │ 14 s ▾ │ 🔀 │ 🔇 │ ⓘ │ ⋮ │ ─ □ ✕ │  header bar
│   │ 1,284 / 29,102 · GIF · 480×270                                               │
├────────────────────────────────────────────────────────────┬─────────────────────┤
│                                                            │ Properties          │
│   ‹                                                    ›   │ Name   cat_jump.gif │
│  (hover)            image / GIF / video              (hover)│ Folder trips        │
│                                                            │ Size   12.4 MB      │
│                                                            │ Date   2026-08-14   │
│                                                            │ 480×270 · 240 frames│
├────────────────────────────────────────────────────────────┤ Duration 9.6 s      │
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░░░░░░░░ │                     │  timer bar
└────────────────────────────────────────────────────────────┴─────────────────────┘
```

- **Header bar** (replaces the Android overlay menu and its bottom sheet): ← back to the folder; the file name as title and `position · type · resolution` as subtitle (always visible, not a toast that fades); ⏮ / ⏯ / ⏭; the **interval** as a menu button with a slider (1–40 s) and the transition (fade / slide / none); 🔀 random order (a toggle, with the cycle progress in its tooltip); 🔇 mute; ⓘ the properties pane; ⋮ with *Copy*, *Copy path*, *Show in Files*, *Move to trash* (with an Undo toast), *Rotate* (view only, later), *Full screen* (F11). No *Share* (no desktop equivalent); *Open with* stays folder-view only.
- **Properties pane** (ⓘ or `Alt` + Enter): a resizable sidebar on the right (`AdwOverlaySplitView`), the same data as Android's file info (path, size, date, resolution, duration, frames), plus EXIF for photos. It is part of the layout, so it never covers the image and the slideshow **doesn't pause** while it is open. Its visibility is remembered.
- **Previous / next:** ‹ › buttons fade in at the left and right edges when the pointer moves near them (Loupe style), plus keys and the wheel. There are no invisible click zones and no centre tap for a menu: a single click on the image does nothing, a **double-click toggles full screen**.
- **Full screen** only on request (F11, double-click, ⋮). In full screen the header bar and timer bar slide in when the pointer touches the top or bottom edge and hide after 2 s, the cursor hides after 2 s, and Esc leaves full screen first, then the viewer. The full-screen state is remembered for the next slideshow.
- **Timer bar:** a thin bar at the bottom for every item, as on Android; it stops while paused.
- The screen saver and suspend are inhibited only while the slideshow plays or a video plays (`gtk::Application::inhibit`), in a window or full screen.
- **Keys:** ← / → / PgUp / PgDn / wheel (one step per 250 ms) step; `Space` / `P` play or pause; Home / End first / last; `Ctrl` + wheel, `Ctrl` + `+` / `-` / `0`, touchpad pinch zoom (`1` = 100 %, `0` = fit); drag pans when zoomed; `M` mute; `R` random order; `I` / `Alt` + Enter properties; F11 / `F` full screen; Delete moves to trash; Esc / `Backspace` / `Alt` + ← back to the folder, focused on the last viewed item.
- **Video controls:** for a video, a seek bar and the time appear under the picture on hover (not over it), with volume; `Shift` + ← / → seek 5 s.
- **Unchanged from Android:** timing (loop / once / to the end), preloading, transitions, resume after restart, random order, skipping unreadable or slow items with one grouped toast, the *Previous* position memory (last 15 items), one-item folders, video sound muted by default, a playing book pausing when video sound is on.
- **Dropped from Android:** tap zones and their one-time hint, the overlay menu with its 5 s auto-close, the pause-while-menu-is-open rule (nothing covers the image now), two-finger swipes, the "Maximise the window" hint.

### 4.7 Text editor

- Opens in **its own window** (`AdwApplicationWindow` per file), so you can edit and browse at the same time. Opening a file that is already open raises its window. `Ctrl` + W closes, with *Save / Discard / Cancel* for unsaved changes.
- `GtkSourceView` with the language from GtkSourceView's language manager (file name and content type), the `Adwaita` / `Adwaita-dark` style schemes, line numbers (setting), current-line highlight, wrap lines (setting), monospace font from the system (`org.gnome.desktop.interface monospace-font-name`) for code, proportional for `txt` / `md` (setting).
- **Header bar:** the file name (`•` when changed), the language and encoding as a subtitle (`Markdown · UTF-8 · CRLF`), *Save* (`Ctrl` + S), and a menu with *Save as UTF-8* (legacy charsets only), *Wrap lines*, *Line numbers*, *Text size* (`Ctrl` + `+` / `-` / `0`, `Ctrl` + wheel), *Open with…*, *Show in Files*, *Rename…*.
- **Find and replace** (`Ctrl` + F / `Ctrl` + H, F3 / `Shift` + F3), go to line (`Ctrl` + L): missing on Android, free in GtkSourceView.
- **Loading and saving** follow README §3.13 exactly, with the ported `TextDecode` / `TextFile` in `core`: charset detection, BOM and CRLF kept, strict encoding with *Save as UTF-8*, safe save (temp file, `fsync`, rename), changed-on-disk check (*Overwrite / Reload / Cancel*; GIO also watches the file and shows a banner *"Changed on disk — Reload"*), limits (editable ≤ 1 MB, read-only ≤ 10 MB, larger → other apps; colours ≤ 200 K characters — **verify** these on the desktop, where they can probably be higher).
- **Drafts:** unsaved text is written to `drafts/` every 10 s and when the app quits or crashes; reopening the file offers it back (*"Unsaved changes restored"* + *Discard*).
- **New text file / New folder** dialog (an `AdwDialog`): the same name rules and live hint line as Android (README §2 Text editor).

### 4.8 Listening

- **▶ / 🎧** in the header bar and *Listen* in a folder's context menu: same folder kinds (audio, mixed, lecture), queue order (`.m3u` / `.m3u8`, then natural order), resume and smart rewind (5 s after a pause > 1 min, 20 s after > 1 h) as README §2 Listening and §3.12.
- **Player:** one GStreamer `playbin3` with the video flag off, so lectures are decoded as audio only. Speed through `playbin3` rate (0.5–3×). Volume follows the system.
- **Mini player** at the bottom of the main window: cover, file, book, position, −30 s, play / pause, +30 s, ✕ (stop while paused). Click opens the **player page** (later; todo.md §4).
- **MPRIS:** the GNOME Shell media widget, the lock screen, keyboard media keys and Bluetooth headsets control it. *Play* after the app was closed is not possible without a running process. **Decision:** closing the last window while a book plays keeps the app running in the background, playing, with the MPRIS controls (`gio::Application::hold`); setting *Keep playing after the window is closed* (default on). Quitting (`Ctrl` + Q) stops.
- History in `history.db` (the same schema as Android), saved every 5 s while playing and at once on pause, seek, file change and quit.

### 4.9 Preferences

`AdwPreferencesDialog` with the same groups as Android (README §2 Settings), minus the Android-only ones:

- **Slideshow:** interval, transition (fade / slide / none), random order, video muted by default, restore on launch (off / folder only / folder and slideshow).
- **Folder view:** automatic or fixed thumbnail size, open with single or double click, image tile mode and smart-crop threshold, large-GIF threshold, long videos open externally (off / 1–30 min), text files without an encoding (auto by system language / a fixed code page), sort, descending, folders first, hidden files, always show names.
- **Editor:** open text files in the app, text size, wrap lines, line numbers.
- **Listening:** keep playing after the window is closed.
- **Sidebar:** share favourites with Files' bookmarks, reveal the open folder in the tree, number of recent folders (0–50), show Trash.
- **Files:** default view (grid / list), remember view and sort per folder, restore tabs on start, default archive format and level, follow links when compressing to zip, ask before *Replace* (always on for permanent deletes).
- **Appearance:** style (follow system / light / dark), *Black background in dark style* (AMOLED-like, through CSS).
- **Maintenance:** clear the thumbnail and metadata caches (strip cache size limit, §7.6), error log, reset settings.

Dropped from Android: *Mouse clicks* (always desktop), *Invert swipe direction* (kept only if touchpad swipes are implemented), all permission and DeX items.

### 4.10 Accessibility and polish

- Every icon button has a tooltip and an accessible label; tiles expose name, type and size to screen readers (`gtk::Accessible` properties).
- `Ctrl` + ? opens a `GtkShortcutsWindow` with every shortcut above.
- The app follows the system font size and the *Large text* setting; tile text previews scale with it (README §3.10 sizes, as px at scale 1).
- Reduced motion (`gtk-enable-animations` off) → no tile cycling, no slideshow transitions.

### 4.11 Sidebar: favourites and folder tree

```
┌─────────────────────┐
│ ★ Favourites        │
│  ▾ GIFs             │   favourites are tree roots: expand them in place
│     ▸ 2025          │
│     ▾ 2026          │
│        • trips      │   ← the open folder, highlighted and kept in view
│  ▸ Lectures         │
│  ▸ Documents        │
│ 🕘 Recent            │
│    memes            │
│    downloads        │
│ 🖥 This computer     │
│  ▸ 🏠 Home           │
│  ▸ 💽 System  120 G  │
│  ▸ 🔌 USB 14 GB   ⏏ │
│ 🗑 Trash         12  │
└─────────────────────┘
```

- **Favourites** come first, as **tree roots**: each one has an expander and opens its subfolders in place, so a deep folder under a favourite is a few clicks or keys away. Favourites are **shared with Files' bookmarks** (`~/.config/gtk-3.0/bookmarks`, read and written, watched for changes), so the same list appears in Files, in file dialogs and here; setting *Share favourites with Files* (default on; off keeps a private list in `state.json`). Android favourites can be imported (§14).
  - Add: ★ in the header bar, `Ctrl` + D, *Add to favourites* in a folder's context menu, or **drag a folder onto the Favourites heading**.
  - Reorder by dragging; rename the label (*Rename…*, the folder keeps its name); remove (*Remove from favourites*, Delete key on the entry).
  - A favourite whose folder is missing (drive not mounted) is shown dimmed; clicking it says *"Not available — connect the drive"*.
- **Recent** (last 10 in the sidebar, setting 0–50; the rest under *More…*), with *Remove from recent* and *Clear recent*.
- **This computer** as tree roots too: Home, File system (`/`), and every mounted volume with free space and an eject button (`gio::VolumeMonitor`). Unmounted but known drives are shown dimmed and mount on click (through GIO, with the system password prompt when needed).
- **Trash** opens the trash as a folder (`trash:///`): items show their original location and deletion date; *Restore*, *Delete permanently*, *Empty trash* (asks first). The count badge follows the trash.

**Tree behaviour**

- Built on `gtk::ListView` + `gtk::TreeListModel`: children are listed only when a node is expanded, on the IO pool, folders only, in natural order, hidden folders only with *Show hidden files*. Thousands of subfolders are fine (the list is virtual).
- **Follows the grid:** opening a folder expands its ancestors under the nearest root (a favourite if one contains it, else Home or the volume) and scrolls the node into view, without stealing the keyboard focus. Setting *Reveal the open folder in the tree* (default on).
- **Live updates:** expanded nodes get a `FileMonitor`, so new, renamed and deleted folders appear at once; at most 64 monitors, collapsed nodes drop theirs.
- **Keys:** F6 moves the focus to the sidebar; ↑ / ↓ move; → expands or enters the first child; ← collapses or goes to the parent; Enter or Space opens the folder in the grid; `*` expands all children one level; `Ctrl` + ↑ / ↓ reorder favourites; type-ahead jumps to a name; Menu key opens the context menu.
- **Mouse:** a click opens the folder; a click on the expander only expands; middle-click opens in a new tab; right-click: *Open in new tab*, *Open in new window*, *Copy path*, *Add to / Remove from favourites*, *Rename…* (folder), *New folder…*, *Properties*, *Eject* (volumes).
- **Drop target:** every node accepts drops (move / copy / link rules of §4.5) and opens after a 1 s hover.
- Expanded nodes are remembered between runs; the sidebar width is saved; F9 hides it.

### 4.12 File operations and archives

**Operations queue**

- Copies, moves, deletes, archive jobs and batch renames run on the IO pool, several at once. A progress button in the header bar (a ring, like Files) opens a popover listing each job: what, from → to, bytes and items done, speed, time left, and **Pause** / **Stop**. Stopping a copy removes the half-written file; stopping an archive removes the partial archive.
- A job under 300 ms shows nothing; a finished long job shows a toast (*"Copied 12 files to trips"* + *Show*); a failed item doesn't stop the job: it is listed at the end (*"2 files couldn't be copied"* + *Details*).
- Quitting with jobs running asks *Stop jobs and quit?*.

**Name conflicts**

The Android app never overwrites and always names copies `name (1).ext`. On a desktop users expect a choice, so the first conflict in a job opens one dialog: *Replace*, *Skip*, *Keep both* (default, gives `name (1).ext`), *Merge* (folders), with the two files' size, date and thumbnails side by side, and *Apply to all conflicts*. *Replace* moves the old file to the trash, not into oblivion.

**Undo and redo for file operations**

`Ctrl` + Z / `Ctrl` + `Shift` + Z (and *Undo* in the toast) undo the last operations in this session: rename, batch rename, move, copy (moves the copies to the trash), new folder or file (if still empty), move to trash (restores), paste as file, extract (moves the extracted items to the trash). The main menu shows the next step (*Undo rename "a.gif"*). History: last 20 operations, kept in memory only; an undo that can't be done any more (the file was changed or moved by another app) says so in a toast and does nothing.

**Compress**

*Compress…* in the context menu (and `Ctrl` + `Alt` + C) for the selection:

```
┌ Compress 3 items ──────────────────────────────────┐
│ Name    [ trips                           ] .zip ▾ │
│ Format  ● zip   ○ 7z   ○ tar.gz  ○ tar.xz  ○ tar.zst│
│ Level   [ Normal ▾ ]   (store / fast / normal / best)│
│ ☐ Encrypt with password  [ ••••••• ]  (zip, 7z)     │
│ ☐ Delete originals after (moves them to the trash)  │
│                              [ Cancel ] [ Create ]  │
└────────────────────────────────────────────────────┘
```

| Format | Why | Library |
| --- | --- | --- |
| **zip** (default) | opens everywhere, Windows and macOS included | `zip` crate (Deflate; AES-256 encryption; UTF-8 names flag) |
| **7z** | best ratio, AES-256 with encrypted names | `sevenz-rust2` **verify** write support and speed; fallback: run `7z` / `7zz` if installed |
| **tar.gz** | Linux standard, any tool | `tar` + `flate2` |
| **tar.xz** | small, common for sources | `tar` + `xz2` (liblzma) |
| **tar.zst** | fast with good ratio, modern distros | `tar` + `zstd` |

- The name defaults to the single item's name, or the folder's name for several items; the format is remembered. The archive is written as `.name.zip.part` in the target folder and renamed when complete; a conflict asks as above.
- **Quick compress:** *Compress to "trips.zip"* in the context menu does it in one click with the last format and default level.
- Already-compressed media (GIF, JPEG, MP4, …) is stored without recompression inside zip / 7z (*Normal* level), which is much faster for media folders and costs almost nothing in size.
- Symbolic links are stored as links in tar and 7z, followed in zip (setting). Hidden files are included. File times and Unix permissions are kept.
- Progress, Pause and Stop through the operations queue.

**Extract**

- Double-clicking an archive opens it with the default archive app (as for any other file); the context menu adds **Extract here** and **Extract to…**. Formats: zip, 7z, tar (`.gz` `.bz2` `.xz` `.zst` `.lz4`), gz / xz / bz2 / zst single files, rar (`unrar`, extract only, in the helper process), iso (later).
- **Smart extract** (as Files does): an archive with one top-level folder or file is extracted as it is; otherwise into a new folder named after the archive. Conflicts ask as above.
- Encrypted archives ask for the password once per archive (the dialog says which archive), with *Show password*.
- **Safety:** entries with absolute paths, `..`, or links pointing outside the target are skipped and reported (zip-slip); a sparse or huge entry that would fill the disk stops with a clear message (free space checked against the declared sizes first).
- **Legacy file names:** zip archives from Windows without the UTF-8 flag often use CP866 or Windows-125x names; the names are decoded with the same detection as text files (`TextDecode`, Cyrillic check, then the *Text files without encoding* setting), so Russian names don't turn into garbage.
- *Delete archive after extracting* (option in *Extract to…*, moves it to the trash).
- **Browse inside an archive (later):** open a zip / 7z / tar as a read-only folder, with thumbnails and the viewer, without extracting.

**Batch rename** (F2 with several items)

A dialog with a live preview list (old → new name, conflicts in red):
- **Template:** text with tokens `{name}` `{ext}` `{n}` (counter with start and padding: `{n:3}` → `001`), `{date}` / `{time}` (file date, or EXIF date for photos: `{exif-date}`), `{width}x{height}`, `{duration}`, `{parent}`.
- **Find and replace:** plain or regular expression, case-sensitive option.
- **Change case:** lower, UPPER, Title, and *spaces → underscores* / back.
- The order of `{n}` follows the current sort. Applied as one undoable operation.

**Other operations**

- **Select by pattern** (`Ctrl` + S, as in Files): `*.gif`, `IMG_2026*`, with *Add to selection*.
- **Duplicate** (`Ctrl` + `Shift` + D on a file): a copy next to it as `name (copy).ext`.
- **Make link** (context menu, or `Ctrl` + `Shift` + drag): a symbolic link.
- **Properties** (`Alt` + Enter): name, type, size (folders: counted on the IO pool, live, with item count), location, dates, **permissions** (owner, group, read / write / execute; *Allow executing as a program*), media details (resolution, duration, codec, frames, EXIF camera and lens), and **checksums** on demand (SHA-256, MD5) with *Copy*. For several items: total size and count.
- **Open in Terminal** (context menu and `Ctrl` + `Alt` + T in a folder): the default terminal (`xdg-terminal-exec`, else `gnome-terminal` / `ptyxis` / `x-terminal-emulator`) opened in that folder.
- **Set as wallpaper** (images, context menu and viewer menu): through the `org.freedesktop.portal.Wallpaper` portal.
- **Rotate** in the viewer (`[` / `]`): JPEG rotation is lossless (writes the EXIF orientation, asks first because it changes the file); other formats are rotated for viewing only.

### 4.13 More desktop features

| Feature | Behaviour |
| --- | --- |
| **Tabs** | `Ctrl` + T new tab, `Ctrl` + W closes, `Ctrl` + PgUp / PgDn and `Alt` + 1…9 switch (`AdwTabView` + `AdwTabBar`, shown only with two or more tabs). Middle-click on a folder opens it in a tab. Tabs can be dragged out into a new window and accept drops. Open tabs are restored on the next start (setting). |
| **List view** | `Ctrl` + 2 (grid is `Ctrl` + 1, toggle in the view menu): a `gtk::ColumnView` with small thumbnails and columns Name, Size, Type, Modified, and optionally Dimensions, Duration, Frames; click a header to sort, drag to reorder, right-click to choose columns. Same selection, keys, drag and drop and context menu as the grid. Useful for documents and for comparing sizes. The view is remembered per folder (setting) or globally. |
| **Recursive search** | `Ctrl` + F filters the open folder by name as you type; a **Search subfolders** toggle (or `Ctrl` + `Shift` + F) searches below it instead, with filters: type (images, GIFs, videos, audio, documents, text), date (today, last week, last month, range), size (> / <). Results stream in under the watchdog, show their folder under the name, and work with the viewer (slideshow over the results), drag and drop and all operations. *Search in file contents* for text files is a later option. |
| **Folder history** | Back / forward per tab; right-click or long press on ← → shows the last 20 folders. |
| **Status in the window title** | `trips — Media Viewer`, so window switchers and docks show the folder. |
| **Remember per folder** (setting, default off) | sort, view (grid / list), thumbnail size; stored in `state.json`, not in the folders. |
| **Show folder sizes** (optional column in list view) | counted lazily on the IO pool, cached until the folder changes. |
| **Mount, unmount, eject** | From the sidebar; *Safely remove* waits for writes to finish, with a toast when the drive can be unplugged. |
| **Startup** | `media-viewer PATH` opens a folder (or a file: its folder with the file selected, or the viewer with `--view`); the last session (tabs, folders, scroll positions) is restored otherwise. |
| **Notifications** | A long job (> 30 s) that finishes while the window is not focused sends a desktop notification (*"Copy finished"*), clicking it shows the folder. |
| **Tags and stars (later)** | Colour labels and 0–5 star ratings stored in extended attributes (`user.xdg.tags`, `user.baloo.rating`), shown on tiles and usable in search and the slideshow filter. |
| **Duplicate finder (later)** | Finds identical files (size, then hash) and near-identical images (perceptual hash) in a folder tree; review side by side, trash the extras. Useful for large GIF / photo collections. |

### 4.14 Mobile patterns reviewed

Each phone pattern in the Android app was checked against desktop conventions. The **role** of each feature stays; only the form changes.

| Android pattern | Why it exists on the phone | Desktop |
| --- | --- | --- |
| Full-screen, immersive slideshow | small screens, no window manager | In-window viewer page; full screen only on request (F11, double-click) (§4.6) |
| Overlay menu as a bottom sheet; pauses the slideshow; closes after 5 s | no room for permanent controls | Header bar with buttons and menus, plus a non-modal properties pane that never covers the image (§4.6) |
| Invisible tap zones (left ⅓ / centre ⅓ / right ⅓) with a one-time hint | touch has no hover | Visible ‹ › buttons fading in near the edges, keys, wheel; nothing to learn |
| Top bar that hides on scroll | every pixel counts on a phone | Header bar always visible |
| Root screen as its own page, "Press again to exit" | phone navigation, system Back | Sidebar always at hand; `Ctrl` + Q / closing the window quits |
| Selection mode (long press, two-finger tap, ✕ to leave) | touch has no `Ctrl` / `Shift` | Always-on multi-selection: click, `Ctrl` / `Shift` + click, rubber band, `Ctrl` + A (§4.4) |
| Gallery clicks: a click waits 300 ms for a possible double-click | double-click selects on the phone | Standard: click selects at once, double-click (or Enter) opens |
| Selected GIF / video tiles play in the tile | no hover on touch | Hover scrubbing plus Quick look (`Space`) |
| ⋮ overflow and size slider behind a grid button on narrow screens | 360 dp phones | Full header bar; collapses only below 600 px window width |
| Snackbars | Material on Android | `AdwToast`: same role (grouped errors, Undo) |
| Share sheet | Android intents | *Copy*, drag out, *Open with…*; no Share entry |
| Two-finger swipes, pinch on the grid | touch | Keys and wheel; touchpad pinch for zoom only |
| Editor as a screen over the folder; text size slider in its bar | one window per app | Editor in its own window; text size in its menu and `Ctrl` + wheel |
| Permission screens, "All files access" | Android storage rules | None |
| Crash screen in a separate process | Android kills crashed apps | Crash dialog on the next start (§8) |
| One-time "Maximise the window" hint | apps can't maximise themselves on DeX | Dropped; the window opens maximised if it was last time |
| DeX / Chromebook detection, mouse-mode setting, right-click-as-Back guard | phone vs desktop on one APK | Dropped: always desktop |
| TV remote, D-pad focus | Android TV | Dropped; keyboard focus remains |

**Kept as they are:** the edge-to-edge dense grid (window chrome only around it), the wait indicator with Stop, toasts instead of error dialogs, the mini player bar (a desktop pattern too), all slideshow timing and order rules. Dialogs are still never used for errors; on the desktop they appear only for real decisions (unsaved changes, permanent delete, file changed on disk).

---

## 5. Feature parity with Android

Every feature from README §1, with its desktop form and the milestone (§12.3) that delivers it.

### Browsing

| Android feature | Desktop | M |
| --- | --- | --- |
| Storage roots, favourites, recent folders | Sidebar: Recent, Favourites, Bookmarks, Devices (§4.1) | 1 |
| All-files permission screens | Not needed | — |
| Streaming listing in sorted order | Same (§3.4) | 1 |
| `..`, folders, media, other; natural sort; sort menu | Same | 1 |
| Live updates | `gio::FileMonitor` on the open folder | 1 |
| Breadcrumbs hiding on scroll | Breadcrumbs in the header bar, always visible; `Ctrl` + L path entry | 1 |
| Thumbnail size: automatic, pinch, `Ctrl` + wheel, slider | Same, slider in the view menu | 1 |
| Fast-scroll thumb with bubble | Same | 2 |
| Folder collage, cycling, hover | Same | 2 |
| Smart crop | Same (port of `SmartCrop`) | 1 |
| GIF / video 5-frame strips, scrub, auto-preview, badges | Same, scrub by hover | 2 |
| Large-GIF guard | Same | 2 |
| Animated WebP / AVIF strips | Possible here: `image` decodes animated WebP frames; AVIF through libheif sequences **verify** | 5 |
| Icons for other files, partial, broken, timed out | Icon theme by MIME type + own state icons | 1 |
| Previews for more file types (todo.md §1, mostly not done on Android) | Freedesktop cache → system thumbnailers → built-in previewers: PDF, EPUB, FB2, CBZ/CBR, ODF / OOXML, SVG, fonts, audio covers, RAW, PSD, archives, AppImage, … (§7.8) | 5 |
| Text previews | Same (port of `TextDecode`, rendering with Pango into an A8 surface) | 2 |
| Junk names, magic sniffing | Same (port of `FileTypes`) | 1 |
| Hidden files | Same, `Ctrl` + H | 1 |
| Selection mode, drag range, auto-scroll | Desktop multi-selection: click, `Ctrl`, `Shift`, rubber band (§4.4) | 1 |
| Select all, invert, share, move / copy to, delete with undo | `Ctrl` + A, `Ctrl` + I, *Copy* instead of share, *Move to…* / *Copy to…* (own picker with recents + *Other location…* → `gtk::FileDialog`), trash with Undo | 3 |
| Back to the last viewed item after the slideshow | Same | 2 |
| Open with external app, *Open with…* | `gtk::FileLauncher` (default app / always ask) | 3 |
| New text file, new folder | Main menu, `Ctrl` + Alt + N, `Shift` + `Ctrl` + N | 3 |
| Long videos open in an external player, ↗ | Default video app (`AppInfo::default_for_type("video/*")`), resume position passed where the player supports it (`mpv --start=`, `vlc --start-time=`) **verify** | 3 |
| ▶ Play in the top bar | Same, header bar | 4 |
| Background listening, lock screen, headset | MPRIS, keep playing after the window closes | 4 |
| Books: positions, smart rewind, playlist order | Same (port of `Book`, `Playlist`, `HistoryStore` schema) | 4 |
| Lecture folders as audio only | Same (`playbin3` without the video flag) | 4 |
| Mini player | Bottom bar of the main window | 4 |
| Desktop clicks, context menu, `Ctrl` / `Shift` + click | The default and only mode | 1 |
| Drag out, `Ctrl` + C, Delete | Same, plus cut / paste with Files | 3 |
| Drop files into the app (missing on Android) | Done here (§4.5) | 3 |
| Favourites on the root screen | Expandable **folder tree** in the sidebar with favourites as roots, shared with Files' bookmarks; Trash view (§4.11) | 1 |
| — (new) | Rename (F2), Properties with permissions and checksums, Show in Files, Quick look, type-to-jump, filter, multiple windows, folder history back / forward | 3 |
| — (new) | Clipboard: paste images / text as files, copy the picture from the viewer, five *Copy path* forms (§4.5) | 3 |
| — (new) | Drag and drop: spring-loaded folders, drops on tree / breadcrumbs / tabs, links with `Ctrl` + `Shift`, drops from browsers (§4.5) | 3 |
| — (new) | Operations queue with pause, conflict dialog, undo / redo of file operations (§4.12) | 3 |
| — (new) | **Compress** to zip / 7z / tar.gz / tar.xz / tar.zst, **extract** with smart folders, passwords, zip-slip protection, legacy name decoding (§4.12) | 3 |
| — (new) | Batch rename, select by pattern, duplicate, make link, open in terminal, set as wallpaper, lossless JPEG rotation (§4.12) | 5 |
| — (new) | Tabs, list view with columns, recursive search with filters, notifications (§4.13) | 5 |

### Text editor

| Android feature | Desktop | M |
| --- | --- | --- |
| Open text / code files in the editor; *Open with…* | Own window per file | 3 |
| Syntax colours (own lexers, ~40 formats) | GtkSourceView (150+ languages) | 3 |
| Charset, BOM, line endings kept; *Save as UTF-8* | Same (port of `TextDecode`, `TextFile`) | 3 |
| Safe save; changed-on-disk detection | Same, plus a GIO monitor banner | 3 |
| Undo / redo; unsaved question; drafts | GtkSourceView undo; same question and drafts | 3 |
| Text size, wrap lines | Same, plus line numbers | 3 |
| Size limits | Same (**verify** higher limits) | 3 |
| Find, rename (missing on Android) | Find and replace, go to line, rename | 3 |

### Slideshow

| Android feature | Desktop | M |
| --- | --- | --- |
| Full screen, black, screen stays on | In-window viewer page; full screen only on request (F11, double-click); idle and suspend inhibited while playing (§4.6) | 2 |
| Tap zones; one-time hint | Header-bar buttons, ‹ › edge buttons on hover, keys and wheel; no hint needed | 2 |
| Two-finger swipes, pinch zoom, pan | Wheel and keys step; pinch and `Ctrl` + wheel zoom; drag pans | 2 |
| Keyboard, mouse back button | Same | 2 |
| Interval, loop / once / to-the-end rules | Same (port of `SlideTiming`) | 2 |
| Timer bar | Same | 2 |
| *Previous* resumes where left (last 15) | Same | 2 |
| Random order, stable across restarts | Same (port of `SlideOrder`) | 2 |
| Overlay menu (bottom sheet) | Header bar + properties pane (§4.6) | 2 |
| One-item folders | Same | 2 |
| Pause indicator, position | ⏯ state and the position in the header-bar subtitle, always visible | 2 |
| Preload; fade / slide / none | Same | 2 |
| Unreadable or slow items skipped, grouped message | Same | 2 |
| Videos | GStreamer + `gtk4paintablesink` | 2 |

### DeX and desktop windows

All of it becomes standard behaviour: resizable window with reflowing grid and sharper re-decodes, tooltips, pointer cursors on clickable items, wheel stepping in the slideshow, video that keeps playing in an unfocused window. The DeX-specific items (launch size, right-click-as-Back guard, caption-bar insets, *Maximise* hint) don't apply.

### Robustness and persistence

| Android feature | Desktop | M |
| --- | --- | --- |
| Wait indicator with Stop after ~300 ms; progress for copies | Same, inline under the header bar; Esc stops | 1 |
| Watchdogs on every decode | Same, plus killable helper processes (§3.3) | 1 |
| No error dialogs; grouped toasts; error log | Same | 1 |
| Removed drive → nearest parent | Same, from `VolumeMonitor` events | 1 |
| Settings saved at once, read before the first frame | Same (TOML read before the window is shown) | 1 |
| Recent folders with scroll position; open the first available | Same | 1 |
| Slideshow resumes after restart | Same | 2 |
| Crash screen | Panic hook + crash dialog on next start (§8.3) | 1 |
| FFmpeg audio codecs | `gstreamer1.0-libav` | 2 |

---

## 6. Platform services

| Concern | Implementation |
| --- | --- |
| Volumes and free space | `gio::VolumeMonitor` (mounts, `mount-added` / `mount-removed`, eject); free space from `filesystem::free` / `filesystem::size` attributes, read on the IO pool |
| Live updates | `gio::File::monitor_directory` (inotify) on the open folder only; events coalesced every 100 ms; a file renamed over another counts as a finished write (as `FolderListing` does) |
| Trash and Undo | `gio::File::trash` (system trash, shown in Files). Undo: find the item in `trash:///` by `trash::orig-path` and deletion date, and move it back. Volumes without a trash (`NOT_SUPPORTED`, some FAT drives) → the Android scheme: rename into `.mediaviewer-trash/` and purge when the toast closes, with pending entries recorded so a crash purges them on the next start (README §3.6). `Shift` + Delete → permanent, after one confirmation dialog (a desktop convention; the "no dialogs" rule is about errors) |
| Copy and move | Own code on the IO pool (`std::fs` + `copy_file_range`), with progress, Stop (removes the half-copied file) and `name (1).ext` naming, as on Android; moves within a volume are renames |
| Open, Open with | `gtk::FileLauncher::launch` (default app; through the OpenURI portal under Flatpak); *Open with…* sets `always_ask` |
| Long videos externally | The default app for the video's MIME type; if it is mpv or VLC, launch it directly with the start position. Only from the folder view, never from the slideshow or Quick look |
| Show in Files | D-Bus `org.freedesktop.FileManager1.ShowItems` |
| Clipboard | `gdk::Clipboard` with `gdk::FileList` + `x-special/gnome-copied-files` |
| Drag and drop | `gtk::DragSource` (`gdk::FileList`), `gtk::DropTarget` |
| Favourites | `~/.config/gtk-3.0/bookmarks` read, written atomically and watched with a `FileMonitor` (shared with Files and file dialogs) |
| Archives | Rust crates on the IO pool (stack table); RAR through `unrar` in the helper process; legacy zip names through `TextDecode` |
| Terminal | `xdg-terminal-exec`, else `gnome-terminal` / `ptyxis` / `x-terminal-emulator`, with the folder as working directory |
| Wallpaper | `org.freedesktop.portal.Wallpaper` |
| Notifications | `gio::Notification` for long jobs finished while unfocused |
| Extended attributes (later) | `xattr` crate for tags and ratings |
| Folder picker | Own popover (current, recent, favourites, devices) + `gtk::FileDialog::select_folder` for *Other location…* |
| Screen saver | `gtk::Application::inhibit` during the slideshow and video playback |
| Media keys | MPRIS (`mpris-server`), `org.mpris.MediaPlayer2.media_viewer` |
| Theme | `adw::StyleManager` (follows the system; light / dark override); the black variant through a CSS class |
| Single instance | `gio::Application` (D-Bus unique); `media-viewer PATH` opens the path in the running instance; the `.desktop` file registers `inode/directory` as an optional handler (*Open with Media Viewer* in Files) |
| Command line | `media-viewer [PATH]`, `media-viewer --slideshow PATH`, `media-viewer --check DIR` (§11.3) |

---

## 7. Thumbnails, decoding and the GIF codec

### 7.1 Scheduler

A port of `Thumbnails.kt` and README §3.3–3.4: work only for visible tiles, starting ~150 ms after scrolling stops, cancelled when a tile is unbound; the decode pool of `max(2, cores / 2)`; an LRU of decoded thumbnails as `gdk::MemoryTexture` (a strip or collage counts as one), growing to the visible tiles plus prefetch rows; cheap-first folder previews; retries of timed-out files after 1.5 s of idle with the watchdog doubled (up to ×8); broken files not retried during the session.

### 7.2 Stills

1. **Freedesktop thumbnail cache first:** `~/.cache/thumbnails/{normal,large,x-large,xx-large}/<md5(uri)>.png`, valid when `Thumb::MTime` matches. Files and other apps make these too, so big photo folders already opened in Files show at once. The app writes `large` / `x-large` thumbnails for the stills it decodes (setting, default on).
2. **EXIF embedded thumbnail** when it is at least the tile size (common in camera JPEGs).
3. Full decode: `zune-jpeg` / `image` in process; downscale with `fast_image_resize`; EXIF orientation applied; truncated files shown as far as they decode.
4. HEIC / AVIF through libheif in the helper process. JXL through `jxl-oxide` in process. **verify** decode times for 24 MP JPEGs without DCT scaling; if too slow, add `turbojpeg` (libjpeg-turbo with 1/2, 1/4, 1/8 scaling) in the helper.

### 7.3 GIF codec

Port `GifCodec.kt` (409 lines) to `core/gif`:

- **Block scan:** frame count, total duration, size, without decoding (~3 ms for 16 MB); delays of 0–1 cs count as 100 ms; results cached in `meta.db`.
- **First frame:** a few ms whatever the size.
- **Strip pass:** one sequential decode keeping the 5 target frames scaled to the tile, stopping after the last; one canvas and one "restore previous" buffer.
- **Frame reader** for the slideshow: start at a position, stop on a frame (README §2 Slideshow, `SlideGif`).
- LZW decoding may use the `gif` crate's decoder, but compositing (disposal methods, transparency, "restore previous") must match Pillow exactly. The existing Pillow comparisons (`MV_GIF_REF`, `MV_GIF_DUMP`, see README §4) become Rust tests against the same reference files.

### 7.4 Videos

In the helper process: `uridecodebin3` → `videoconvert` → `videoscale` → `appsink`; read duration and size; 5 accurate seeks (`ACCURATE | FLUSH`) at 10, 30, 50, 70, 90 %; the first frame goes to the tile at once. Durations go to `meta.db`. Hardware decoding is allowed but not required. Watchdog 5 s for metadata, 10 s for the strip **verify**.

### 7.5 Watchdogs

The same limits as Android (README §3.4): decode 5 s + 250 ms per MB for a GIF strip; metadata or block scan 3 s; large-GIF pass 60 s; the clock starts when the job gets a thread. In-process jobs that time out are abandoned (§3.2); helper jobs are killed.

### 7.6 Strip cache (new, optional)

Android keeps thumbnails in memory only. On a desktop, 5-frame strips of 28k GIFs (~470 ms each) are worth keeping: WebP files in `$XDG_CACHE_HOME/media-viewer/strips/`, keyed by `path + size + mtime + tile px`, with a size limit (default 2 GB, setting) and LRU pruning on the IO pool. **verify** the gain on the reference folder before making it default on.

### 7.7 Text previews

A port of README §3.10: `TextDecode` reads 8 KB, then Pango (`pangocairo`) lays out whole lines into an A8 `cairo::ImageSurface` the size of the tile, on the decode pool; the tile tints it with the theme's text colour on a paper background, so one cached surface serves both styles.

### 7.8 Previews for more file types (enhancement)

On Android only images, GIFs, videos and text have previews; everything else is an icon (todo.md §1 lists PDFs and others as research). A Linux desktop offers much more, so here **almost every common file gets a real preview**, and the icon is the last fallback.

**Where a preview comes from, in order:**

1. **The freedesktop thumbnail cache** (§7.2): Files, document viewers and other apps already made thumbnails for many files.
2. **Built-in previewers** (below): tuned for tile size, several frames or pages for hover scrubbing, and run under the watchdogs. Pure-Rust parsers run in process; C libraries run in the helper process (§3.3).
3. **System thumbnailers**: every `/usr/share/thumbnailers/*.thumbnailer` file (its `MimeType=` and `Exec=` line with `%i` input, `%o` output, `%s` size), run in the helper process with a timeout and, when available, inside `bwrap` with no network and a read-only view of the file, as GNOME does. Installed on the dev machine: evince (PDF, PostScript, DjVu, XPS, comics), gnome-font-viewer, librsvg, heif, webp, gdk-pixbuf. Installing more packages adds more types with no code: `gnome-epub-thumbnailer` (EPUB, MOBI), `ffmpegthumbnailer`, `f3d` (3D models), Blender, and others. Results are written to the freedesktop cache, so Files benefits too; failures go to `~/.cache/thumbnails/fail/media-viewer-<version>/` (as the spec asks) and aren't retried until the file changes.
4. **Icon** from the icon theme by MIME type.

**Built-in previewers**

| Type | How | Where it runs | Tile | Hover / Quick look |
| --- | --- | --- | --- | --- |
| **PDF** | `poppler-glib` (`libpoppler-glib-dev` 24.02 on Ubuntu 24.04; `poppler-rs` crate **verify**): render page 1 at tile size on white | helper | first page, page-count badge (`12 p`); encrypted → lock icon | hover scrubs pages 1–5 like a GIF strip; Quick look pages through the whole file (←/→) |
| **EPUB** | ZIP → `META-INF/container.xml` → OPF → cover item (`properties="cover-image"` or `<meta name="cover">`); no cover → first image of the first chapter, else a text preview of it | in process (`zip`, `quick-xml`) | cover, `EPUB` badge | Quick look: cover + title, author |
| **FB2 / FB2.ZIP** | `<coverpage><image l:href>` → `<binary>` base64 | in process | cover | title, author |
| **CBZ / CBR / CB7** | first image in natural order (CBZ in process; CBR through `unrar` and CB7 through `sevenz-rust` **verify**) | in process / helper | cover, page-count badge | hover scrubs the first 5 pages; Quick look pages through |
| **ODT / ODS / ODP** | `Thumbnails/thumbnail.png` inside the ZIP | in process | page thumbnail | — |
| **DOCX / XLSX / PPTX** | `docProps/thumbnail.jpeg` if saved; DOCX otherwise text from `word/document.xml` as a text preview | in process | thumbnail or text | — |
| **SVG** | `resvg` (pure Rust) | in process | rendered image | Quick look at full size |
| **Fonts** (`ttf otf woff woff2`) | `ab_glyph` renders "Aa Bb Cc 123" and the family name | in process | sample text | Quick look: a full specimen |
| **Audio** | embedded cover (`lofty`, pure Rust), else `cover.jpg` / `folder.jpg` / `front.jpg` / `AlbumArt*.jpg` / the only image in the folder; duration from the tags without decoding | in process | cover, duration badge | Quick look plays it |
| **Camera RAW** (`cr2 cr3 nef arw dng raf orf rw2`) | embedded JPEG preview (`rawler` or the EXIF thumbnail) | in process | image | Quick look and the viewer use the large embedded preview |
| **PSD / PSB** | the embedded thumbnail resource, else the flattened composite (`psd` crate) | in process | image | — |
| **Animated WebP, APNG, AVIF sequences** | frames from `image` (WebP, APNG) and libheif (AVIF) | in process / helper | 5-frame strip, like GIFs (missing on Android, README §6) | scrub and preview as GIFs |
| **Archives** (`zip tar tar.gz 7z rar`) | a text preview listing the first entries with sizes; if most entries are images, a collage of the first 4 like a folder tile | in process (zip, tar) / helper | listing or collage | Quick look lists everything |
| **HTML** | tags stripped, rendered as a text preview (never a web view) | in process | text | — |
| **CSV / TSV** | columns aligned in the text preview (todo.md §1a) | in process | table-like text | — |
| **AppImage, `.desktop`** | the `Icon=` from the icon theme; AppImage's `.DirIcon` (later) | in process | app icon | — |

**Folder collages** use the next-best previews when a folder has no media (todo.md §1a): covers (audio, EPUB, comics) → PDF first pages → text previews; the item count only when nothing can be previewed.

**Rules**

- The same scheduler, watchdogs (5 s per preview; 10 s for system thumbnailers), visible-tiles-only work and cancellation as for images (§7.1).
- Files over 200 MB get previews only from the cache, from embedded thumbnails or from header-only parsers (PDF page 1, ZIP central directory), never by reading the whole file.
- Settings (*Folder view*): *Previews for documents and other files* (on / off), *Use system thumbnailers* (on / off), *Write thumbnails to the shared cache* (on / off).
- **verify** on the reference folder and on a documents folder that previews don't slow down scrolling (they are cheaper than GIF strips, but system thumbnailers start a process each).

---

## 8. Slow operations, errors and crashes

1. **Wait indicator:** a user-started operation that takes over ~300 ms shows a non-modal progress row under the header bar with a label (*"Reading folder…"*, *"Loading 196 MB GIF…"*, *"Copying 3 / 12: …"*), a progress bar when known, the elapsed time after 3 s, and **Stop** (Esc too). A stopped listing keeps what it loaded and shows *"Listing stopped — Continue"*. (README §3.5)
2. **Errors** are never dialogs: the item gets an icon or is skipped; errors from one folder within 2 s become one toast with **Details** → the **error log** window (last 200: path, reason, time). Everything is logged with `tracing` to the journal.
3. **Crashes:** a panic hook writes the report (version, OS, thread, backtrace) to `last_crash.txt` and the journal. The next start shows a crash dialog with **Reset settings and clear cache**, **Clear cache only**, **Copy details** and **Continue**. Native crashes in decoders don't reach the app (§3.3). A crash on start twice in a row starts with defaults.
4. **Removed drive:** toast *"Storage removed"*, the view moves to the nearest existing parent or home; a running slideshow stops, but its session is kept, so it resumes when the drive comes back.

---

## 9. Persistence

| Data | Where | Notes |
| --- | --- | --- |
| Settings | `settings.toml` | written on every change (debounced 300 ms, atomic rename); read before the window is shown |
| Recent folders (≤ 50): path, time, scroll anchor (first visible item key + offset), last viewed item | `state.json` | as README §3.8 |
| Favourites | `state.json` | paths |
| Slideshow session: folder, current item, random on/off, seed, pinned item, list size + hash, video position | `state.json` | saved on every item change and every second during a video |
| Window size, maximised, sidebar width; editor window sizes | `state.json` | — |
| Open tabs (folder, scroll anchor), expanded tree nodes, per-folder view and sort (if enabled) | `state.json` | — |
| Favourites | `~/.config/gtk-3.0/bookmarks` (shared) or `state.json` (private list) | — |
| File-operation undo history | memory only | last 20, this session |
| GIF / video metadata | `meta.db` | key = path + size + mtime, pruned after 90 days; the Android schema |
| Listening history | `history.db` | user data: never pruned, migrated; the Android schema |
| Thumbnails | memory LRU + freedesktop cache (stills) + optional strip cache | — |
| Drafts | `drafts/<hash>.json` | one per file |

Matching schemas and key names mean a later *export / import* could move state between phone and PC (§14).

---

## 10. Performance targets

- The first tiles appear < 100 ms after opening a folder (names and icons first).
- Scrolling stays at the display refresh rate while a 30k-file folder streams in; no frame over 16 ms on the UI thread (check with the GTK inspector's frame graph and `GTK_DEBUG=…` **verify** flags).
- Cold start to the first frame < 300 ms; a resumed slideshow shows its first image < 1 s after a cold start.
- Memory: < 400 MB with a 30k-file folder open at a typical tile size.
- No disk I/O on the UI thread: a debug build wraps the main loop with a check that logs any `std::fs` call from it (a `cfg(debug_assertions)` wrapper around the file APIs used in `data`).
- Thumbnails are always decoded at tile size × scale factor.

**Reference folder:** the real media folder of README §5 (~29,150 files, 338 GB, 95 % GIF; README §5). Every milestone is checked against it.

---

## 11. Testing

1. **Unit tests in `core`**, ported case by case from the Kotlin tests: smart crop (the README examples), slideshow timing (the README table), natural sort and folder order, random order (no repeats, restore after restart, adds and deletes), file types and magic sniffing, the GIF codec on synthetic files, playlist parsing, book classification and resume rules, `TextDecode` and `TextFile` (charsets, BOM, CRLF round trips, *Save as UTF-8*, changed-on-disk, names, drafts). The Kotlin tests are the reference: same inputs, same expected outputs.
2. **GIF reference tests** against Pillow (`MV_GIF_REF`, `MV_GIF_DUMP`), as on Android.
3. **Real-folder check:** `media-viewer --check DIR [--threads 6] [--large-gif-mb 40]` runs the listing, type detection, every GIF's first frame, block scan and strip, video metadata through the helper, slideshow timing, and a sequential and a random pass with restore checks. Reports go to `target/media-check/` (`summary.txt`, `gif-failures.tsv`, `types.tsv`, `video-failures.tsv`). Unlike Android, stills and video frames *are* covered here.
4. **UI smoke tests:** a headless GTK run (`GDK_BACKEND=broadway` or `weston --headless` **verify**) that opens a fixture folder, scrolls, opens the slideshow and the editor, and checks that nothing panics and no frame takes over 50 ms.
5. CI: `cargo fmt --check`, `cargo clippy -D warnings`, `cargo test`, on Ubuntu 24.04.

---

## 12. Build, packaging and milestones

### 12.1 Development setup (Ubuntu 24.04)

```bash
# Rust (not installed on the dev machine yet)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# GTK, libadwaita, GtkSourceView, GStreamer, libheif
sudo apt install build-essential pkg-config \
  libgtk-4-dev libadwaita-1-dev libgtksourceview-5-dev \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libgstreamer-plugins-bad1.0-dev \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav gstreamer1.0-vaapi \
  libheif-dev libdav1d-dev libpoppler-glib-dev

cargo run -p app -- ~/Pictures
```

Versions on Ubuntu 24.04 (checked 2026-10-25): GTK 4.14.5, libadwaita 1.5.0, GtkSourceView 5.12.0, GStreamer 1.24.2 (libav 1.24.1), libheif 1.17.6, SQLite 3.45.1. `gstreamer1.0-gtk4` is **not** packaged, hence the linked-in `gst-plugin-gtk4` crate.

### 12.2 Packages

- **`.deb`** (`cargo-deb`): binaries, `.desktop` file (`MimeType=inode/directory;image/*;video/*;` — as an optional handler, not the default), icons (scalable + symbolic), AppStream metainfo. Depends on the libraries above and recommends `gstreamer1.0-libav`.
- **Flatpak** (later): GNOME runtime 47+ (has GTK, libadwaita, GtkSourceView, GStreamer); `org.freedesktop.Platform.ffmpeg-full` for codecs; `--filesystem=host` (it is a file browser), `--talk-name=org.freedesktop.FileManager1`, MPRIS own-name.
- AppImage: not planned.

### 12.3 Milestones

Each milestone ends with `cargo test` green, the real-folder check run, and the app used on the reference folder.

| M | Scope | Done when |
| --- | --- | --- |
| **0** | Workspace, CI, `core` ports with all unit tests passing (natural order, sorting, smart crop, slide timing, slide order, file types, `TextDecode`, `TextFile`, `Book`, `Playlist`, GIF codec + Pillow tests) | `cargo test` matches every Kotlin test case |
| **1** | Main window: sidebar with the favourites folder tree (shared with Files' bookmarks), recent, volumes, trash view, header bar, breadcrumbs, streaming grid with icons and still thumbnails (freedesktop cache, in-process decoders), sort, hidden files, size control, desktop selection, live updates, wait indicator, toasts, error log, settings and state, crash handling | Browsing the reference folder feels instant; no UI-thread I/O |
| **2** | GIF and video strips (helper process), scrub, collages, text previews, fast-scroll bubble; slideshow with all timing and order rules, video through GStreamer, the in-window viewer with header bar and properties pane, session resume | The Android slideshow rules all hold on the desktop |
| **3** | File operations: operations queue, conflict dialog, undo / redo, trash + Undo, copy / move with progress, compress and extract, all *Copy path* forms, paste images / text as files, spring-loaded drag and drop, *Move to…* / *Copy to…*, rename, new file / folder, clipboard with Files, drag out and drop in, *Open with*, long videos externally, Quick look, Properties, Show in Files; the editor window | Everyday file work without opening Files |
| **4** | Listening: queue, `playbin3` audio only, history, smart rewind, mini player, MPRIS, keep playing after the window closes | A book survives quit and restart at the right position |
| **5** | **Previews for more file types** (§7.8: system thumbnailers, PDF pages with Quick look paging, e-book and comic covers, office, SVG, fonts, audio covers, RAW, archives); tabs, list view, recursive search, batch rename, select by pattern, terminal, wallpaper, JPEG rotation; polish and packaging: shortcuts window, accessibility pass, multiple windows, animated WebP / AVIF strips, strip cache, `.deb`, AppStream, optional Flatpak | Folders of documents look as rich as folders of photos; installable `.deb` on a clean Ubuntu 24.04 |

Later (from todo.md §3–4, after parity): full player page, shuffle and music mode, chapters, lyrics, sleep timer. Also later: browse inside archives, content search, tags and stars, duplicate finder, download of dropped URLs.

---

## 13. Findings from the investigation

### 13.1 Running the Android app on Linux

| Option | Result |
| --- | --- |
| Waydroid | Runs the APK almost natively and can bind-mount host folders, but needs a Wayland session and keeps the phone UI. Good for testing the Android app, not a desktop app. |
| Android emulator | Most faithful runtime, but it can't see host folders (`adb push` only), and the user isn't in the `kvm` group yet (`sudo usermod -aG kvm $USER`). Useful for on-device tests of the Android app, which have never been run (README §6). |
| A real desktop app | This document. |

### 13.2 The Android code base (2026-10-25)

- Kotlin 2.1.20, Jetpack Compose (BOM 2025.04.01 = Compose 1.8), Material 3, Media3 1.6.1 + Jellyfin FFmpeg, Coil 3.1, DataStore 1.1.4, ~60 source files.
- **Platform-free already:** `domain/` (natural order, sorting, smart crop, slide timing, slide order, `Book`, `Highlight`, `EditHistory`), `data/fs/FileTypes`, `TextFile`, `Drafts`, `data/thumbs/GifCodec`, `TextDecode`, `data/history/BookFolder`, `data/Blocking`. They are covered by JVM unit tests, and they are the **reference behaviour** for the Rust ports (§11.1).
- **Android-bound:** about 30 files: thumbnails (`ImageDecoder`, `MediaMetadataRetriever`), playback (`PlaybackService`, ExoPlayer in tiles and the slideshow), SQLite through `SQLiteOpenHelper`, DataStore on `Context`, volumes (`StorageManager`), trash, external apps (`Intent`, `FileProvider`), permissions, DeX detection, `BackHandler`, `SystemClock`, `android.util.Log`. 13 UI files use `java.io.File` directly.

### 13.3 Why not share code with the Android app

- **Compose for Desktop** would reuse the Kotlin UI, but the UI is built for touch (gallery clicks, selection mode, bottom sheets, tap zones, hiding bars), and the goal here is a true desktop app. The desktop shell on Compose is also weaker on Linux (§2.2).
- **Kotlin Multiplatform** for the shared UI hits a wall: Kotlin doesn't officially support an Android + JVM shared source set with JDK access, and 13 UI files use `java.io.File`.
- A **shared pure-Kotlin core** library was the middle ground, but with Rust + GTK most of that core is replaced by better native pieces (GtkSourceView instead of `Highlight` and `EditHistory`; GStreamer and GIO instead of the platform code). What is left to port is small (§13.4), and the Kotlin tests make the port checkable.

### 13.4 What gets ported, what gets replaced

| Kotlin (Android) | Lines | Linux |
| --- | --- | --- |
| `NaturalOrder`, `Sorting`, `SmartCrop`, `SlideTiming`, `SlideOrder` | small | Port to `core`, with the same tests |
| `Book`, `Playlist`, `BookFolder` | ~250 | Port to `core` |
| `FileTypes` (types, junk names, magic sniffing, `.ts` check) | ~180 | Port to `core`; MIME types from `gio::content_type_guess` as a second opinion |
| `TextDecode`, `TextFile`, `Drafts` | ~400 | Port to `core` with `encoding_rs` |
| `GifCodec`, `SlideGif` | ~530 | Port (compositing must match Pillow) |
| `Blocking` (abandonable calls) | small | Port (§3.2) |
| `Highlight` (lexers), `EditHistory` (undo) | ~1,100 | **Replaced** by GtkSourceView |
| `Thumbnails`, `TextPreview` | ~600 | Rewrite (scheduler logic ported, decoders native) |
| `FolderScanner`, `FolderListing` | ~270 | Rewrite on `std::fs` + `gio::FileMonitor`, same streaming and sorted-insert design |
| `MetaStore`, `HistoryStore` | ~240 | Rewrite on `rusqlite`, same schemas |
| `SettingsStore` | ~290 | Rewrite on `serde`, same keys |
| Playback, Media3 session | ~500 | Rewrite on GStreamer + MPRIS |
| Volumes, Trash, FileOps, ExternalApps | ~360 | Rewrite on GIO |
| Compose UI | ~5,000 | Rewrite in GTK, desktop layout (§4) |

### 13.5 Library facts checked

- Ubuntu 24.04 package versions: §12.1. glycin (Loupe's sandboxed image loader) is packaged (`glycin-loaders` 1.0.1) and is an alternative to our own helper process for stills **verify** whether its API and speed suit a 30k-tile grid.
- Rust is not installed on the dev machine; `ffmpeg` exists only inside a conda env; VLC only as a snap. None of these are needed by the chosen stack except Rust.
- The dev machine: Ubuntu 24.04, 377 GB RAM, JDK 21; the reference folder is on a spinning-disk RAID, where many parallel readers become seek-bound (README §4: 6 threads by default). The listing and decode pools must keep that in mind: one listing thread per folder, and the decode pool capped for rotating disks (**verify** a per-volume limit using `/sys/block/*/queue/rotational`).

---

## 14. Open questions

1. **Repository:** a new repository (`media-viewer-linux`), or a `linux/` folder in this one so the Kotlin reference tests sit next to the ports? Recommendation: a `linux/` folder at first, split later if wanted.
2. **Name and app ID:** *Media Viewer*, `io.github.<user>.MediaViewer`? The ID is needed for D-Bus, the `.desktop` file and Flatpak.
3. **Single click vs double click** default for opening: double click (Files' default) is assumed.
4. **Keep playing after the window is closed:** default on is assumed (§4.8).
5. **State export / import** between phone and PC (same keys and schemas): wanted?
6. **Flatpak / Flathub** at all, given `--filesystem=host`?
7. **Touchpad swipes** in the folder view (back / forward) and in the slideshow (previous / next): worth the extra work given the GTK limits (§4.4)?
8. **Default archive format:** zip is assumed (opens everywhere). 7z writing depends on `sevenz-rust2`'s maturity; if it falls short, 7z is offered only when `7zz` / `7z` is installed.
9. **Favourites shared with Files' bookmarks** (default on) vs a private list: sharing is assumed, because the same list then appears in file dialogs too.
10. **Name conflicts:** the Android rule is "never overwrite, always `name (1)`". The desktop asks once per job (*Replace / Skip / Keep both*, default *Keep both*). Is a dialog acceptable here, or should the Android rule stay as the default with the dialog behind a setting?
