# Media Viewer for Android

A fast Android app for browsing local media folders and watching them as a slideshow. It is built for folders with tens of thousands of GIFs, videos and images, and it:

- shows as much content as possible: an edge-to-edge grid of thumbnails with almost no chrome;
- is driven mainly by touch, with full keyboard, mouse and TV-remote support;
- never blocks: folders stream in, thumbnails load only for what is on screen, and errors never interrupt.

One APK runs on phones, tablets, foldables, Chromebooks, Samsung DeX and Android TV (Android 8.0+, API 26).

The original task description is kept in [docs/SPEC.md](docs/SPEC.md).

## Contents

1. [Features](#1-features)
2. [Using the app](#2-using-the-app)
3. [How it works](#3-how-it-works)
4. [Building and checking](#4-building-and-checking)
5. [Performance and the reference folder](#5-performance-and-the-reference-folder)
6. [Limitations and open questions](#6-limitations-and-open-questions)

---

## 1. Features

✅ done · ◐ partly · ❌ not done

### Browsing

| Feature                                                                                                                                               | Status                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Storage roots: internal storage, SD cards, USB drives, favourite folders, recent folders                                                              | ✅                           |
| USB drives and SD cards appear in and disappear from *Storage* on the home screen as they are mounted and removed                                       | ✅ (untested on a device)    |
| Cameras and phones over USB (MTP / PTP): *Devices* on the home screen, browsing with the device's thumbnails and folder collages, slideshow, delete with *Undo*, copy and move both ways, new folders, live updates, auto-open for cameras (§3.14) | ◐ untested on a device; tested against a fake device |
| "All files access" on Android 11+, classic storage permissions on 8–10, hint for devices without the settings screen                                  | ✅                           |
| Falls back to the app's App info page when the "All files access" screen is missing, or after "Don't ask again" on 8–10                               | ✅                           |
| Streaming folder listing: tiles appear while a 30k-file folder is still being read, already in sorted order                                           | ✅                           |
| Order: back tile `..`, folders, media, other files; natural sort (`img2` before `img10`)                                                              | ✅                           |
| Sort by name / date / size / type, ascending or descending, folders first on/off; default: date, newest first; folders without images by name (`a1`, `a2`, `a10`) | ✅                           |
| Sort menu in the top bar (`S`): name or date modified, ascending or descending; kept per folder between runs                                          | ✅                           |
| Live updates: files added, deleted or renamed by other apps appear or disappear without a rescan                                                      | ✅                           |
| Breadcrumb path bar that hides on scroll                                                                                                              | ✅                           |
| Thumbnail size: automatic from the window width until changed; pinch, `Ctrl` + wheel, `+` / `-`, and a size slider in the top bar; kept between runs  | ✅                           |
| Fast-scroll thumb with a letter / date / size bubble                                                                                                  | ✅                           |
| Names too long for a tile scroll sideways in a continuous loop (marquee), never truncated                                                             | ✅                           |
| Folder tiles: collage of up to 4 previews, chosen cheap-first; more cycle on their own, or by hover or `Shift` + arrows                               | ✅                           |
| Image tiles with smart crop (crop if ≤ 10 % is lost, else fit)                                                                                        | ✅                           |
| GIF and video tiles: 5 frames (10–90 %), scrub by hover or swipe, auto-preview on rest or focus, duration / `GIF` badge                               | ✅                           |
| Large-GIF guard: first frame only until the tile is hovered or swiped                                                                                 | ✅                           |
| Animated WebP and APNG strips in tiles (own container readers, frames composited like GIFs); AVIF: first frame                                        | ✅ WebP, APNG (§3.4), AVIF ❌ (§6)|
| Video tiles start with MediaStore's own thumbnail (Android 10+) while the strip decodes                                                               | ✅ (§3.4)                    |
| Icons for other files (text, code, document, PDF, book, audio, archive, APK, unknown), partial downloads and broken files                                   | ✅                           |
| Text previews: first lines of text, code, subtitles and extensionless text files; UTF-8/16 and Cyrillic code pages                                    | ✅                           |
| Previews of other files: PDF page 1, album art, EPUB / FB2 / CBZ covers, Office thumbnails, DOCX text, APK icons, SVG; CSV as a table                 | ✅ (§3.15)                   |
| Folders without photos or videos: a collage of those previews instead of the item count                                                               | ✅ (§3.15)                   |
| Junk names: double extensions (`a.mp4.gif`), junk extensions (`a.webm'`, `a.php?id=1`), magic-byte sniffing                                           | ✅                           |
| Hidden files (`.`-prefixed, `.nomedia` folders) hidden by default                                                                                     | ✅                           |
| Selection mode: long press / two-finger tap / double-click / right-click / `Space`; drag to select a range, with edge auto-scroll                     | ✅                           |
| Selection actions: select all, invert, share, **move / copy to…**, delete with undo                                                                   | ✅                           |
| Back to the last viewed item (scrolled and focused) after the slideshow                                                                               | ✅                           |
| Open other files with an external app (`ACTION_VIEW`, so "Always" works); **Open with…** in the context menu                                          | ✅                           |
| **New text file** and **New folder** (`N` / `Shift` + `N`): `+` in the top bar, or the ⋮ menu on phones                                              | ✅                           |
| Long videos (> 3 min, configurable) open in an external player when one is installed; ↗ on the tile                                                   | ✅                           |
| ▶ Play in the folder top bar (`P`): listen to audio, mixed and lecture folders; slideshow from the first item elsewhere                               | ✅                           |
| Background listening: lock screen, notification, Bluetooth and headset controls; resumes after the app is killed                                      | ✅                           |
| Books: position saved per file and folder, smart rewind, `.m3u` / `.m3u8` play order, natural name order otherwise                                    | ✅                           |
| Lecture folders: long videos played as audio only (the picture is never decoded)                                                                      | ✅                           |
| Player at the bottom of the root and folder screens: seek bar, book progress, speed, sleep timer, collapsible; the playing file's tile is marked and takes the focus                          | ✅                           |
| Player screen: cover, file list with ✓ / ◐, seek bar with chapter ticks, keyboard and TV remote; two panes on wide windows                            | ✅ (§2 Listening)            |
| Speed 0.5–3× in 0.05 steps (kept per book); sleep timer: any minutes, end of file or chapter, 10 s fade-out, shake for 5 more min                     | ✅                           |
| Shuffle per folder (music mode: own skip length, repeat, no resume rewind, no book bar); a tap on an audio file plays its folder from it              | ✅                           |
| ▶ on folder tiles of books and audio folders; *Continue listening* on the home screen; *Start over?* for finished books                               | ✅                           |
| Skip lengths for books and music (5–60 s), also on the lock screen and headsets; skip silence                                                         | ✅                           |
| Lectures: ⋮ *Listen as audio* / *Not a lecture folder*; one position per video shared with the slideshow and the external player                      | ✅                           |
| Subtitle tracks inside MKV / MP4 videos; CC switch per folder; a video frame as the cover                                                             | ✅                           |
| Player ⋮: *File info*, *Share*; time bubble while seeking; a renamed or moved book keeps its history                                                  | ✅                           |
| Chapters (M4B / MP4, MP3 `CHAP`, Matroska, `.cue`); lyrics and subtitles (`.lrc`, `.srt`, `.vtt`, embedded `USLT` / `©lyr`)                           | ✅                           |
| Desktop clicks (auto in DeX and on Chromebooks): click focuses, double-click opens, right-click opens a context menu, `Ctrl` / `Shift` + click select | ✅                           |
| Drag files out to other apps; `Ctrl` + C copies files to the clipboard; Delete deletes                                                                | ✅                           |
| Drop files or folders from other apps into the open folder, or onto a folder tile; `Ctrl` + V pastes copied files                                     | ✅ (§2 Desktops)             |

### Text editor

| Feature                                                                                                                             | Status |
| ----------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Tap a text or code file to edit it (extensionless and unknown files too, if they turn out to be text); **Open with…** for other apps | ✅     |
| Syntax colours chosen from the file name: about 40 languages and formats in 11 families, light and dark                             | ✅     |
| Keeps the file's charset (UTF-8/16, Windows-1251, KOI8-R…), BOM and line endings; *Save as UTF-8* when needed                       | ✅     |
| Safe save (temporary file, then rename); notices files changed by other apps                                                        | ✅     |
| Undo / redo, unsaved-changes question, unsaved text kept if the app is killed                                                       | ✅     |
| Text size (slider, pinch, `Ctrl` + wheel, `Ctrl` + `+` / `-`) and line wrap, kept between runs                                      | ✅     |
| Code in JetBrains Mono (bundled, no ligatures; the system font as an option); also in code and log previews on tiles                | ✅     |
| Files of 1–10 MB open read-only; larger ones open with other apps                                                                   | ✅     |
| Find (`Ctrl` + F) with match case and all matches marked, replace (`Ctrl` + H); rename from the editor (tap the name, `F2`)                               | ✅     |
| Colour themes: Default (follows the app), One Dark, Solarized, GitHub (light / dark), Monokai, Dracula, High contrast — every colour ≥ 4.5 : 1 | ✅     |
| Current line, matching brackets and tags; optional line numbers and tab / trailing-space marks                                       | ✅     |

### Slideshow

| Feature                                                                                                    | Status |
| ---------------------------------------------------------------------------------------------------------- | ------ |
| Full-screen, immersive, black; screen stays on                                                             | ✅     |
| Tap zones: left ⅓ previous, right ⅓ next, center ⅓ menu; one-time hint the first time                      | ✅     |
| Two-finger swipes (left = previous, invertible), pinch / `Ctrl` + wheel zoom, pan when zoomed             | ✅     |
| Keyboard, mouse back button and TV remote navigation                                                       | ✅     |
| Interval R from 1 to 40 s (default 14); loop / play once / play to the end rules for animations and videos | ✅     |
| Thin timer bar at the bottom for every item; stops while paused or in the menu                           | ✅     |
| Previous goes on where an item was left, video and GIF included (last 15 items)                           | ✅     |
| Random order without repeats, stable across restarts and folder changes                                    | ✅     |
| Menu: file name (wrapped, cut after 256 characters), interval, random order with cycle progress, pause, mute, file info, share, delete with undo, back | ✅     |
| A folder with one media item: it stays on screen without the R limit, and a video loops                  | ✅     |
| Pause indicator; position (`12 / 29,102`) shown for a moment after each manual step                        | ✅     |
| Next image preloaded; fade / slide / no transition                                                         | ✅     |
| Unreadable or slow items skipped, with one grouped message                                                 | ✅     |

### Samsung DeX and desktop windows

| Feature                                                                                                           | Status                                          |
| ----------------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| Opens full screen in DeX; the first window fills the screen on Chromebooks and in desktop windowing               | ✅                                              |
| Switching the phone into or out of DeX keeps the app running, without a restart                                   | ✅                                              |
| Free window resizing: columns follow the width, and thumbnails are decoded again, sharper, after the window grows | ✅                                              |
| Top bar clear of a desktop window's title bar                                                                     | ✅                                              |
| A right-click never acts as Back                                                                                  | ◐ heuristic, not yet checked on a device (§3.9) |
| Video keeps playing while another window has focus                                                                | ✅                                              |
| Tooltips on icon buttons, hand cursor on tiles, breadcrumbs and buttons                                           | ✅                                              |
| Mouse wheel steps through the slideshow                                                                           | ✅                                              |
| One-time "Maximise the window" hint when the slideshow runs in a window                                           | ✅                                              |

### Robustness and persistence

| Feature                                                                                         | Status                                                    |
| ----------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| Wait indicator with Stop (button, Back, Esc) after ~300 ms; progress bar for move / copy        | ✅                                                        |
| Watchdog timeouts on every decode; a hung USB drive never blocks the UI                         | ✅                                                        |
| Errors never shown as dialogs: grouped snackbars and an error log (last 200)                    | ✅                                                        |
| Removed SD card / USB drive: message, then the nearest existing folder                          | ✅                                                        |
| All settings saved at once; read before the first frame                                         | ✅                                                        |
| Recent folders (20 shown by default, 1–50) with scroll position; on launch, the first one that is still available      | ✅                                                        |
| Slideshow resumes after a restart, kill or reboot: same item, same random order, video position | ✅                                                        |
| Storage Access Framework fallback (for a Play Store build)                                      | ❌ (see §6)                                               |
| FFmpeg extension bundled: audio codecs the device lacks (AC-3, E-AC-3, DTS, TrueHD, …)          | ✅                                                        |
| Baseline Profile                                                                                | ◐ broad hand-written rules; not generated on a device yet |

---

## 2. Using the app

### Root screen

Lists connected **Devices** (cameras and phones over USB, only while one is plugged in; §3.14), **Recent** folders (large tiles with previews; long press removes one; how many is a setting), **Favourites** (star a folder in its top bar; long press removes) and **Storage** volumes with free space. There is no back tile. Back exits after "Press again to exit".

On launch the app opens the first recent folder that is still available, at its saved scroll position. If the app was stopped during a slideshow, it goes straight back into it, with a 3 s *"Resumed slideshow — tap to go to folder"* message. This is configurable (§2.5).

### Folder view

A full-screen grid with a thin top bar: home, clickable breadcrumbs (the storage root is an icon, so long volume names never push the path off screen), thumbnail size, sort, ▶, `+` (new text file or folder), favourite star and settings. The bar hides when scrolling down. On narrow screens (< 600 dp) the size slider opens from a grid button, and sort, `+`, the star and settings share one ⋮ menu, so the breadcrumbs keep their room.

**Order.** Back tile, folders, media, other files; names always compare in natural order (`a1`, `a2`, `a10`), also as the tie-break for equal dates. A folder **without images or GIFs** (audio, documents, lectures, folders of folders) is sorted by **name, A to Z**; every other folder uses the sort from settings (default: date, newest first). A folder starts in name order while it is listed and switches once, as soon as the first image turns up, so a folder without images never reorders. The **sort menu** (⇅ in the top bar, ⋮ → *Sort…* on phones, or `S`) sets the folder's own order: **Name** or **Date modified**, **ascending or descending** (*A to Z* / *Z to A*, *Oldest first* / *Newest first*). It is kept for that folder between runs (up to 2,000 folders, oldest dropped first); *Default order* forgets it. *Folders first* stays a global setting. The slideshow and the fast-scroll bubble follow the folder's order.

The **thumbnail size** is automatic until you change it: about 3 columns on a phone, 4–5 on small tablets, and one column per ~200 dp on wide windows (DeX, Chromebooks, large tablets), snapped to the nearest size step. It follows the window, so it also adapts when the phone switches into or out of DeX. Pinch, `Ctrl` + wheel, `+` / `-` or the slider fix a size; the "Automatic thumbnail size" setting turns it back on.

| Action                                                         | Touch                                         | Mouse (gallery clicks)             | Keyboard                                                             | TV remote      |
| -------------------------------------------------------------- | --------------------------------------------- | ---------------------------------- | -------------------------------------------------------------------- | -------------- |
| Open (folder → enter, media → slideshow, text → editor, other → external app) | Tap                          | Click                              | Enter                                                                | OK             |
| Play a long video in the slideshow, not externally             | Context menu → Play in slideshow              | Right-click → Play in slideshow    | `Shift` + Enter                                                      | —              |
| Enter selection mode with this item                            | Long press or two-finger tap                  | Double-click or right-click        | `Space`                                                              | Long-press OK  |
| Toggle selection (in selection mode)                           | Tap, double tap, long press or two-finger tap | Click, double-click or right-click | `Space` / Enter                                                      | OK             |
| Scroll                                                         | Drag                                          | Wheel                              | Arrows; PgUp/PgDn and Home/End move the focus a screen / to the ends | D-pad          |
| Thumbnail size                                                 | Pinch, size slider                            | `Ctrl` + wheel, size slider        | `+` / `-`                                                            | Size slider    |
| Sort this folder                                               | ⇅ (⋮ → Sort… on phones)                       | ⇅                                  | `S`                                                                  | ⇅              |
| Scrub a video / GIF preview (folders cycle on their own)       | Horizontal swipe in the tile                  | Hover                              | `Shift` + ←/→                                                        | Long-press ←/→ |
| Go up a folder                                                 | Back tile, system Back                        | Back tile, mouse back button       | Backspace / `Alt` + ←                                                | Back           |
| Leave selection mode                                           | System Back, ✕                                | ✕                                  | Esc                                                                  | Back           |
| Settings                                                       | Gear                                          | Gear                               | `,`                                                                  | Gear           |
| Delete the selection (or the focused tile)                     | Selection bar                                 | Selection bar                      | Delete                                                               | Selection bar  |
| Copy files to the clipboard                                    | —                                             | —                                  | `Ctrl` + C                                                           | —              |

Focused tiles are scaled up with an outline, for keyboard and TV use. With gallery clicks (the default outside DeX and Chromebooks), a mouse click waits for the double-click timeout (~300 ms) before opening, because a double-click enters selection mode; right-click selects at once. Touch taps open at once, and a two-finger tap (both fingers down and up quickly, without moving) selects the tile under the first finger. It never opens anything, even when the fingers land on two different tiles.

**Tiles**

- **Folder:** up to 4 previews like photos on a table, picked without decoding anything: mid-size stills (50 KB–3 MB) first, then GIFs (the first frame is cheap whatever the file size), then videos. At most 2 levels deep, 20 candidates. Further groups of 4 cycle on their own every ~3 s while the grid is idle (staggered, so the tiles don't all change at once); hover or `Shift` + arrows pick a group by hand. A sideways swipe on a folder tile does nothing, so it never competes with the system Back gesture at the screen edge. A folder without media shows its item count.
- **Image:** shown with **smart crop**. If cropping to the tile would cut off at most 10 % of the image, it fills the tile; otherwise the whole image is fit, centered. For a 4:3 tile: a 1.4:1 image is cropped (5 % lost), a 3:2 photo is fit (11 %), a square or 16:9 image is fit (25 %). The mode (smart / crop / fit) and the threshold are settings.
- **GIF / video:** 5 frames at 10, 30, 50, 70 and 90 % (no black first frames), shown one at a time with the same smart crop. The hover position or a swipe picks a frame; resting the pointer or focusing the tile cycles them (~400 ms per frame). The badge shows `GIF` or the duration. GIFs above the large-GIF threshold (default 40 MB) show only their first frame until hovered or swiped; then a small ring shows while the strip is made. While the folder is still being listed, every GIF and video tile shows only its first frame (no strip, scrubbing or preview); the strips are made once the listing is complete. In selection mode, selected GIF and video tiles play the whole animation or video in the tile, looped and muted, whatever R is; at most 4 play at once (hardware video decoders are few), the others keep their frames until one is deselected or scrolls away.
- **Other files:** an icon for the type, with name and size. Partial downloads (`.crdownload`, `.part`) get a download icon and are never decoded. Broken files get a broken-image icon, and files whose decode timed out get an hourglass. Both can still be selected, deleted and shared.

Names of media tiles show on focus, hover and in selection mode, or always with the "Always show file names" setting. A media tile that has no thumbnail (broken-image or hourglass icon) always shows its name, so the file can still be told apart. Names are never truncated: a file or folder name wider than its tile scrolls sideways in a continuous loop, like a news ticker (1 s still, scroll, 1.5 s pause, again), so the whole name can be read; names that fit stay still.

**Selection mode** shows a bar with the count and **Select all**, **Invert**, **Share**, **Move / Copy to…** and **Delete**. Dragging after a long press selects a range, and the grid scrolls when the finger nears the top or bottom. Selection ends when nothing is selected, on Back, or when you change folder.

- **Delete** asks nothing: an *Undo* snackbar stays for 5 s, and only after that is the file really removed (§3.6).
- **Move / Copy to…** opens a folder picker (current folder, volumes, connected devices, favourites, recent). Moves within a volume are instant renames. Copies show progress and can be stopped; a half-copied file is removed. Existing files are never overwritten: the copy gets a `name (1).ext` name.
- **On a device folder** (camera or phone over USB, §3.14) the same bar has no **Share**; delete, move and copy work in both directions, and the top bar's `+` makes a new folder there (no text files).

### Text editor

A tap on a text or code file opens it in the editor. Files without an extension, or with an unknown one, open there too if their content is text; binary files and files over 10 MB open with other apps, as before. **Open with…** in the context menu always offers other apps. With *Open text files in the app* off, a tap opens other apps and **Edit** is in the context menu.

**New text file** (`+` in the top bar, ⋮ on phones, or `N`) asks for a name. The name is used exactly as typed, extension included; nothing is added. The line under the field says what the name gives (*Kotlin — syntax colours*, *No extension — plain text*), or why it can't be used (empty, `/`, too long, already exists). The file is created at once, empty, so a read-only card is reported before any typing; closing the editor without saving removes it again. **New folder** (`Shift` + `N`) uses the same dialog and opens the new folder.

```
┌────────────────────────────────────────────────┐
│ ←  shopping.md •            ↶  A ━━●━━ A  💾 ⋮ │
│    Markdown · UTF-8                            │
├────────────────────────────────────────────────┤
│ # Saturday                                     │
│ - milk                                         │
└────────────────────────────────────────────────┘
```

The bar shows the name (`•` when changed), the language, the encoding and `CRLF` if the file uses it. On phones, redo and the text size move into ⋮. The file opens without the keyboard; a tap in the text places the cursor.

| Action                  | Touch                       | Keyboard                                      |
| ----------------------- | --------------------------- | --------------------------------------------- |
| Save                    | 💾                          | `Ctrl` + S                                    |
| Undo / redo             | ↶, ⋮ → Redo (on phones)     | `Ctrl` + Z / `Ctrl` + Y or `Ctrl` + Shift + Z |
| Text size               | Pinch, slider (⋮ on phones) | `Ctrl` + `+` / `-`, `Ctrl` + wheel            |
| Wrap lines on / off     | ⋮                           | —                                             |
| Save in UTF-8           | ⋮ (for legacy charsets)     | —                                             |
| Find                    | 🔍                          | `Ctrl` + F; next / previous: `Enter` / `Shift` + `Enter`, `F3` / `Shift` + `F3`; close: Esc |
| Replace                 | ⌄ in the find bar           | `Ctrl` + H; `Enter` in the replace field replaces and moves on |
| Rename                  | Tap the name, ⋮ → Rename…   | `F2`                                          |
| Theme, line numbers, whitespace marks | ⋮ → Theme…, Line numbers, Show tabs and trailing spaces | — |
| Back to the folder      | ←, Back                     | Esc                                           |

- Leaving with unsaved changes asks *Save / Discard / Cancel*. If another app changed the file meanwhile, saving asks *Overwrite / Reload / Cancel*. If a character doesn't exist in the file's charset (an emoji in a Windows-1251 file), saving offers UTF-8.
- If the app is stopped with unsaved changes, they are kept; the next time the file is opened they come back, with *Discard* in a snackbar.
- Back returns to the folder, scrolled to the file.
- **Find** opens a bar under the top bar: the text to find (a selected word is taken as the query), `3 / 17`, *Aa* (match case), ↑ / ↓ and ✕. Every match is marked, the current one stronger; it is selected and scrolled into view. Plain text, case-insensitive by default, up to 10,000 matches. Read-only files scroll to the line.
- **Replace** (⌄ in the find bar or `Ctrl` + H, not in read-only files): *Replace* changes the current match and moves to the next; *All* changes every match at once. Each is one undo step; a match is replaced only if the text there still matches.
- **Rename** keeps the unsaved text, undo history and kept draft; the new extension changes the colours. An existing name is refused, never overwritten.
- **Themes** (⋮ → *Theme…* or Settings → Editor): a list with a three-line sample in each theme. *Default* is the editor's original look and follows the app's day / night; any other theme stays as chosen. The theme sets the background, text, cursor, selection, current line, find marks and line numbers; the editor bar keeps the app's colours. Text previews on tiles are not themed.
- **Current line** is tinted. With the cursor next to `(`, `)`, `[`, `]`, `{` or `}` both brackets are marked; in HTML and XML, with the cursor in a tag name, the opening and closing tags too. Brackets in strings and comments don't count; the partner must be within 20,000 characters.
- **Line numbers** (⋮ or Settings, off by default) show in a gutter, also in read-only files; a wrapped line has one number. **Tabs and trailing spaces** (off by default) are drawn as `→` and `·`.

### Listening: books, albums and lectures

The **▶ / 🎧 button** in the folder's top bar (or `P`) plays the folder:

- **Audio folder** (audio files, plus covers, subtitles and playlists), **mixed folder** (audio and video files: one queue) and **lecture folder** (two or more videos, every one longer than the long-video setting, plus `yt-dlp`-style thumbnails, subtitles and `.info.json`): 🎧 **Listen** starts the book, or continues where it stopped.
- Any other folder with media: ▶ starts the slideshow from the first item.
- Tapping a single video still watches it. The player never shows video: lectures are listened to.

Playback continues in the background with the screen off. The **player** at the bottom of the root and folder screens shows, top to bottom: the book bar (one segment per file, as wide as the file is long, played ones filled; a tap starts that file), the title (without the extension) over the folder, `3 / 24` and where we are in the whole book (`2:13:05 / 9:41:00 · 7:28:10 left`, the time left at the playback speed, `≈` until every file's duration has been read in the background), a seek bar with the elapsed and remaining time (dragging previews the target and seeks on release), then the speed (0.75–2×, kept per book), ⏮ previous file, −30 s, play / pause (a ring while the file loads), +30 s, ⏭ next file and the sleep timer (15 / 30 / 60 min or at the end of this file). ✕ stops while paused. A tap on the title, or a swipe up on the full bar, opens the **player screen** (below). ⌄ or a swipe down collapses it to a one-row bar (title, position, −30 s, play / pause, +30 s); ⌃ or a swipe up expands it again; the choice is kept. ⏮ goes to the previous file within the first 3 s of one, later to the start of the current file; ⏭ is disabled on the last file. When the book's folder is open, the playing file's tile carries a *playing* mark, and it takes the focus (scrolled into view) when playback starts from the folder and on every change of file. The lock screen, the notification, Bluetooth and headset buttons control it too, and a headset Play after the app was killed continues the last book.

**Player screen.** Opened from the bar (above), by holding the folder's 🎧 button (or `Shift` + P), and by tapping an audio file — which plays its folder from that file (at its saved place; *Open with…* in the tile menu still offers other apps). Back or `Esc` returns where it was; the folder name at the top (or ⋮ → *Open the folder*) opens the folder playing. Top to bottom:

- the cover — the file's own picture, else the image named like the file (`yt-dlp` thumbnails), `cover` / `folder` / `front`, `AlbumArt…`, the only image of the folder, else the folder's initial on a colour from its name; the same picture blurred behind the screen. A tap plays or pauses, a double tap on the left / right half skips back / forward, a sideways swipe changes the file;
- the file name (cut in the middle, so the number and the extension stay), the title tag below it when it differs, `Track 7 / 42 · 3 h 12 min left`, and the chapter playing (`Chapter 3 / 12 · Title`; a tap lists the chapters);
- synced lyrics or subtitles: the line playing highlighted between the one before and the one after; a tap shows them all, and there a tap on a line plays from it;
- the seek bar, with a tick at every chapter start, and the book bar;
- ⏮ −30 s ▶ +30 s ⏭ — with chapters, ⏮ / ⏭ move by chapter inside the file (⏮ within 3 s of a chapter start goes to the one before) and past the first / last chapter to the other file;
- speed (presets, −/+ 0.05, a slider over 0.5–3×; kept per book), the sleep timer, 🔀 shuffle, chapters and lyrics;
- below, a list with tabs — *Files* (✓ heard, ◐ partly heard with a small bar, the one playing marked and kept in view; a tap plays a file at its saved place, a long press marks it heard or not), *Chapters*, *Lyrics* / *Subtitles*. Drag its handle up (or tap it) for the whole list. Wide windows (landscape tablets, DeX) show the controls and the list side by side.

⋮ also has *File info* (size, date, duration, format, codec, bitrate, sample rate, channels and the tags), *Share*, *Start over* (forgets the book's positions and plays from the first file), *Mark as finished* and *Stop*. While the seek bar is dragged, the target time shows above it. **CC** hides or shows the synced lines under the title for this folder; all lines stay in the list's *Lyrics* / *Subtitles* tab.

Keys: `Space` / `K` play / pause, `J` / `L` skip back / forward, `Shift` + `←` / `→` or `P` / `N` previous / next (chapter or file), `[` / `]` slower / faster, `Home` the start of the file, `End` the end of the chapter or file, `Esc` back; media keys work too. With a keyboard or a TV remote the cover takes the focus: `←` / `→` skip, holding one changes the file, `Enter` plays or pauses; `↑` / `↓` move through the buttons.

**Sleep timer:** 5–90 min or any number of minutes, the end of this file, or the end of this chapter. The sound fades out over the last 10 s; shaking the phone then (or tapping the timer) adds 5 minutes.

**Shuffle (music mode)**, kept per folder: the files play in a stable random order without repeats (the slideshow's random order, with the file playing first), and the queue stops after the last one unless **repeat** (🔁, music mode only: off / all / this file, kept per folder) says otherwise. Skips use the music length (10 s by default), there is no rewind on resume, a file picked from the list starts from its beginning, and the book bar is hidden. Turning shuffle off goes back to the playlist / name order from the file playing.

**Chapters** come from the file — M4B / MP4 chapter tracks and Nero `chpl` lists, MP3 `CHAP` frames, Matroska / WebM chapters — or from a `.cue` sheet beside one large `.flac` / `.mp3` / `.ape` (a sheet written for `.wav` matches the file of the same name). **Lyrics and subtitles:** a `.lrc` (synced; several times on one line, `[offset:]`), `.srt` or `.vtt` beside the file — the same name, or a language variant like `Talk.en.vtt`, the device language first, then English — else the lyrics inside the file (MP3 `SYLT` and `USLT`, MP4 `©lyr`, FLAC / Ogg / Opus `LYRICS`; text that looks like LRC is synced). FLAC / Ogg / Opus files can also carry chapters (`CHAPTER001=00:00:00.000`, `CHAPTER001NAME=…`). YouTube's rolling automatic captions are de-duplicated.

**Lectures.** A folder of videos without audio is a lecture folder when every video is longer than the long-video setting; ⋮ in the folder's top bar (on wide screens its own ⋮ button) overrides that: *Listen as audio (lectures)*, *Not a lecture folder*, *Decide automatically*, kept per folder. A video listened to halfway starts there in the slideshow and in the external player; leaving it in the slideshow saves where it stopped back to the book (external players don't report their position: that direction is one-way). Without a picture inside the file or an image named like it (`yt-dlp` thumbnails), the cover is a frame from 10 % into the video. Subtitles: a `.srt` / `.vtt` beside the video, else the video's own text subtitle track (Matroska SRT / ASS / WebVTT, MP4 `tx3g`; the device language first, then the default track).

**Moved books.** A book whose folder was renamed or moved keeps its history: when a folder without history is played, its files are compared (name and size) with the books whose folder is gone, and one clear match takes that history over. Books on an unplugged SD card are not taken.

Order: the folder's `.m3u` / `.m3u8` playlist if there is one (the one named like the folder, otherwise the first), then files it doesn't list; without a playlist, natural file-name order (`2` before `10`), whatever the folder view's sort.

**Folder tiles** of books show where they stand: a pill over the collage with 🎧 (started), an equalizer (playing now) or ✓ (finished), and the longest label that fits the tile — `12/30` on a small tile, `12/30 · 4:05` on a medium one, `File 12 of 30 · 4:05 / 25:10` on a wide one (`Finished · 30 files` when done) — and a progress line along the bottom of the collage. Books last played before this version show only the position until they are played again.

Resume: the current file and position are saved; resuming after a pause goes back 5 s (pause over 1 min) or 20 s (over 1 h), except in music mode. Play on a finished book asks *Start over?* first.

**Skips** are 30 s in books and 10 s in music mode; Settings → *Listening* sets both (5–60 s), and the notification, the lock screen, Bluetooth and headsets skip the same. **Skip silence** (Settings, or the switch in the speed dialog) leaves out silent stretches in every book.

**Starting a book without opening it:** folder tiles of books already listened to, and of folders with audio files directly inside, have a round ▶ at the bottom right (⏸ while that book plays); holding it opens the player screen too. The home screen's ***Continue listening*** row shows the last 5 books — cover, name, the tile's status pill and progress line, and the same ▶; a tap on a card opens its folder at the current file. Phones whose battery savers are known to stop background playback (Samsung, Xiaomi, Huawei, Oppo, Vivo, OnePlus…) show a one-time hint with a way to the battery optimisation list.

### Slideshow

Opens on the tapped file and plays every media file in the folder.

The screen has three **tap zones** of equal width:

```
┌───────────┬───────────┬───────────┐
│  left ⅓  │ center ⅓ │  right ⅓ │
│ previous  │   menu    │   next    │
└───────────┴───────────┴───────────┘
```

| Action                                   | Touch                                         | Mouse / keyboard                                              | TV remote  |
| ---------------------------------------- | --------------------------------------------- | ------------------------------------------------------------- | ---------- |
| Previous item (nothing at the first one) | Tap the left ⅓, or two-finger swipe left      | Click the left ⅓, wheel up, ←, mouse back button              | ←          |
| Next item (wraps after the last one)     | Tap the right ⅓, or two-finger swipe right    | Click the right ⅓, wheel down, →, `Space`                     | →          |
| Open the menu                            | Tap, double tap or long press in the center ⅓ | Click, double-click or long click in the center ⅓, `M`, Enter | OK / Menu  |
| Close the menu                           | Tap outside it, Back                          | Esc / `M`                                                     | Back       |
| Zoom / pan an image                      | Pinch, then drag                              | `Ctrl` + wheel                                                | —          |
| Pause / resume                           | Menu                                          | `P`                                                           | Play/Pause |
| Back to the folder view                  | Back                                          | Esc / Backspace                                               | Back       |

- Side-zone taps act at once. Double tap and long press only mean "menu", and only in the center zone. The second tap of a double tap is ignored, so it doesn't close the menu it just opened.
- The zones are always left = back, right = forward. "Invert swipe direction" (swipe left = next, as in most galleries) changes only swipes.
- Swipes use two fingers: a one-finger swipe from the screen edge is the system Back gesture, and the app would step as well. Two fingers that spread or pinch zoom instead; whichever happens first decides.
- The first time the slideshow opens, a short overlay explains the zones.

**Timing.** Each item is shown for **R** seconds (1–40, default 14). Animations and videos with duration *d*:

| *d*              | Behaviour                                  | Example (R = 14 s)      |
| ---------------- | ------------------------------------------ | ----------------------- |
| *d* < 2R/3        | loops until R has passed | 7 s GIF → loops, 14 s   |
| 2R/3 ≤ *d* ≤ 3R/2 | plays once               | 20 s GIF → 20 s         |
| *d* > 3R/2        | plays to the end         | 152 s video → 152 s     |

Nothing is on screen for less than ⅔ R. A thin bar at the bottom shows how much of the item's time has passed; it stops while the slideshow is paused or the menu is open. **Previous** goes on where the item was left (timer, and the video or GIF position, for the last 15 items); an item left with less than 2 s to go starts over. GIFs play with the app's own decoder, which can start from a position and stop on a frame; GIFs above the large-GIF size, and files that aren't GIFs inside, use the platform decoder and start from their first frame.

A GIF's duration comes from a block scan, not from decoding. Delays of 0–1 cs count as 100 ms, as in browsers. Video sound is muted by default.

Changing R applies at once, also to the item on screen. The next image is preloaded. Transitions: fade (default), slide or none. An item that can't be read is skipped. An item reached automatically that isn't ready within min(R, 3 s) is also skipped. When you navigate yourself, a wait indicator with Stop appears instead. If nothing in the folder can be shown, the app returns to the folder view with a message.

**Menu** (bottom sheet; while it is open the item timer stops, so the slideshow doesn't go on to the next item, but a video or GIF keeps playing; a video that plays once and ends meanwhile goes on to the next item when the menu closes; the menu closes on a tap anywhere outside it, also in the center of the image, after ~5 s without input and after any button action except file info): the file name, cycle progress (e.g. `1,284 / 29,102`), the interval slider, **Random order**, play / pause, mute, file info (path, resolution, size, date, duration, frames), share, delete (with undo) and back to the folder.

**Random order** shows every file once per cycle, in a pseudo-random order, then starts a new cycle that never begins with the item just shown. Going back walks through the items as they were shown. Files added during a cycle are put somewhere in the not-yet-shown part; deleted ones are skipped. Turning random order on starts a cycle from the current item; turning it off continues in name order from it.

### Desktops: Samsung DeX and Chromebooks

In DeX the app opens full screen; on Chromebooks and in Android desktop windowing its first window fills the screen. Windows can be resized freely: the grid reflows, and thumbnails that became too small are decoded again once the window stops changing size.

**Desktop clicks.** In DeX and on Chromebooks, mouse clicks follow desktop rules ("Mouse clicks" setting: auto / gallery / desktop). Touch, keyboard and TV input don't change.

| Action                                                              | Desktop clicks                                                                                                      |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Focus a tile                                                        | Click                                                                                                               |
| Open                                                                | Double-click, or Enter on the focused tile                                                                          |
| Toggle selection                                                    | `Ctrl` + click; a plain click toggles while in selection mode                                                       |
| Select a range                                                      | `Shift` + click (from the last clicked tile)                                                                        |
| Context menu: Open, Select, Share, Copy, Move to…, Copy to…, Delete | Right-click; actions apply to the whole selection if the tile is part of it                                         |
| Hand files to another app (My Files, mail, chat)                    | Press on a tile and drag it out of the window; drags the selection if the tile is selected. Folders are not dragged |
| Bring files in from another app                                     | Drag them from My Files, a browser or mail onto the folder grid (it gets an outline): they are copied into the open folder, as with *Copy to…*; folders too when the other app allows it |
| Scrub a preview                                                     | Hover (as with gallery clicks)                                                                                      |

In the slideshow, the wheel steps through items (at most one step per 250 ms, so a trackpad fling doesn't skip a dozen), and a video keeps playing while another window has focus. If the slideshow runs in a window, a one-time hint says to maximise it: an app can't maximise its own window.

Icon buttons show a tooltip on hover (also on long press), and tiles, breadcrumbs and buttons show a hand cursor.

Other apps are opened only from the folder view: never from the slideshow, and never in selection mode. A long video reached inside a running slideshow plays there.

### Settings

Slideshow: interval, transition, invert swipe direction, random order, video muted by default, restore on launch (off / folder only / folder and slideshow).
Folder view: automatic thumbnail size or a fixed size, image tile mode (smart / crop / fit) and smart-crop threshold (0–30 %), large-GIF threshold, long videos open externally (off / 1–30 min), text files without encoding (auto by device language / a fixed code page), default sort (for folders with images and without their own sort), descending, folders first, show hidden files, mouse clicks (auto / gallery / desktop), always show file names.
Editor: open text files in the app, text size (10–28 sp), wrap lines, JetBrains Mono for code, colour theme, line numbers, tabs and trailing spaces.
Home screen: number of recent folders shown (1–50).
Appearance: theme (system / light / dark / AMOLED black).
Maintenance: clear thumbnail cache, error log, favourite folders.

---

## 3. How it works

### 3.1 Stack

| Area                              | Choice                                                                                                                                                  |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Language / UI                     | Kotlin 2.1, Jetpack Compose, Material 3, Material Icons (Rounded)                                                                                       |
| Concurrency                       | Coroutines and Flow                                                                                                                                     |
| Thumbnails                        | Own pipeline: `ImageDecoder` (API 28+) / `BitmapFactory`, `MediaMetadataRetriever`, own GIF codec                                                       |
| Slideshow images and animation    | GIFs: the app's own decoder (`GifCodec.FrameReader`, `SlideGif`). Other images and large GIFs: Coil 3 (`AnimatedImageDecoder` on API 28+, `GifDecoder` below) |
| Video                             | Media3 ExoPlayer, with the FFmpeg extension (`org.jellyfin.media3:media3-ffmpeg-decoder`, prebuilt for all 4 ABIs, ~1.5 MB each) as a fallback renderer |
| Settings, recent folders, session | Jetpack DataStore (Preferences, JSON for the lists)                                                                                                     |
| GIF / video metadata cache        | SQLite (`SQLiteOpenHelper`), key = path + size + mtime, pruned after 90 days                                                                            |
| Dependencies                      | A small manual container in `MediaViewerApp` (no Hilt needed at this size)                                                                              |
| SDK                               | minSdk 26, target / compile 36; one APK, `leanback` optional                                                                                            |

### 3.2 Code map

```
app/src/main/java/com/mediaviewer/
  MainActivity.kt, MediaViewerApp.kt   splash, navigation root, snackbars, Coil setup
  domain/        FileEntry, NaturalOrder, Sorting, SmartCrop, SlideTimer, SlideOrder, Book + Playlist,
                 Highlight (syntax colours), EditHistory (undo), Lyrics + Companions (lyrics, subtitles,
                 cover names), Chapters + CueSheet, PlayerRules (shuffle order, speed steps, sleep fade)   (pure Kotlin)
  data/fs/       FileTypes, FolderScanner, FolderListing, Volumes, Trash, FileOps,
                 TextFile (editor load / save / create / rename), Drafts (unsaved editor text),
                 DropImport (files dropped from other apps)
  data/mtp/      MtpDevices (USB devices, permission, storages), MtpConnection (calls, watchdog, listing,
                 delete, upload, events; pure Kotlin over MtpDriver), AndroidMtpDriver (android.mtp.MtpDevice),
                 MtpMirror (local copies, 2 GB budget), MtpTransfer (copy / move with a device), MtpPaths
  data/thumbs/   Thumbnails (scheduler, LRU, watchdogs), GifCodec (scanner + decoder),
                 WebpAnim (animated WebP container: scan, frames, key frames), ApngAnim (APNG chunks, frames, compositing order),
                 ZipIndex + DocCovers (covers inside EPUB / CBZ / ODF / OOXML / FB2),
                 TextDecode (text sniffing, charsets; pure Kotlin), TextPreview (text tiles)
  data/meta/     MetaStore (SQLite metadata cache)
  data/history/  HistoryStore (history.db: listening positions), BookFolder (queue + resume point)
  data/tags/     MediaTags (chapters and lyrics inside MP3 / MP4 / Matroska; pure Kotlin),
                 TrackExtras (chapters, lyrics, title and cover of the file playing)
  playback/      PlaybackService (MediaSessionService), BookPlayer (MediaController for the UI)
  data/settings/ SettingsStore (DataStore)
  data/          ErrorReporter, Blocking (abandonable calls)
  ui/            AppViewModel (navigation, busy state, delete / move), PermissionScreen,
                 Desktop (DeX / Chromebook detection), MouseBack (mouse back button, right-click guard)
  ui/root/       RootScreen
  ui/browser/    FolderScreen, Tiles, TileInput (gestures), TilePlayback (selected tiles play), BrowserState (selection), FolderPicker,
                 NewItemDialog (new text file / folder), TileListen (book status on folder tiles),
                 FileDrop (drop target)
  ui/editor/     EditorScreen, EditorState (text, undo, saving, find, rename), EditorFind (find bar, rename dialog)
  ui/slideshow/  SlideshowScreen, SlideshowController, SlideGestures, SlideshowMenu
  ui/settings/   SettingsScreen, ErrorLogScreen
  ui/playback/   MiniPlayer, PlayerScreen
  ui/common/     Common (share, copy), ExternalApps (open with other apps, external player lookup)
tools/check-media-folder.sh             runs the app code over a real folder
tools/pure-tests.sh                     the pure-Kotlin tests without Gradle or the SDK
tools/typecheck/                        whole-app type check without the SDK (run.sh, stubs, resolve.py)
build-apk.sh                            build / install script
```

### 3.3 Never block, never repaint everything

1. **Streaming listing.** `FolderScanner` reads a `DirectoryStream` entry by entry on `Dispatchers.IO`. It emits batches every 16 ms or 50 entries. `FolderListing` inserts each batch into its sorted position off the main thread.
2. **Stable keys.** Grid items are keyed by path, so an insertion composes only the new tiles.
3. **Per-tile state:** `Placeholder → Loading → Ready | Failed`. A finished thumbnail updates one tile.
4. **Visible tiles only.** Work starts ~150 ms after scrolling (or dragging the fast-scroll thumb) stops, and is cancelled when a tile leaves the screen. Flinging through 29k items starts almost no decodes.
5. **Bounded parallelism:** decoding uses `Dispatchers.IO.limitedParallelism(max(2, cores / 2))`.
6. **Bounded cache:** an LRU of 100 thumbnails (a 5-frame strip or a folder collage counts as one). It grows to the number of visible tiles plus prefetch rows on very dense grids, so on-screen tiles are never evicted.
7. **Cheap first:** folder previews prefer files that decode fastest.
8. **Live updates** through a `FileObserver` on the open folder.

### 3.4 Thumbnails and the GIF codec

- **Stills** are decoded straight to tile size. `ImageDecoder` handles HEIC/AVIF/WebP and EXIF rotation, and shows truncated files partially.
- **Videos:** one block on one thread opens a `MediaMetadataRetriever`, reads duration and size, takes 5 keyframe-accurate scaled frames, and releases it. The first frame is handed to the tile early. Because the retriever is used by a single thread, an abandoned (timed-out) decode never races with `release()`. The actual decoding runs in Android's media server process, so a corrupt file can't crash the app.
- **APNG** (`ApngAnim`, todo §10.10): the platform shows only an APNG's first frame, so the chunks are read here. Every PNG tile reads its first 4 KB once: an `acTL` before the image data marks an APNG (a still PNG costs nothing more and nothing is cached for it). A scan then finds each `fcTL` with its `IDAT` / `fdAT` chunks without decoding (cached in SQLite like GIFs); each needed frame becomes a standalone PNG (`IHDR` with the frame's size, `PLTE` / `tRNS` / `gAMA` / `cHRM` / `sRGB` / `iCCP` / `sBIT` copied, `fdAT` → `IDAT` without its sequence number, new CRCs) for the platform decoder. `ApngAnim.render` decides the drawing order from the nearest key frame — blend *source* or *over*, dispose *none*, *background* or *previous* (a saved copy of the canvas) — and `Thumbnails` draws it on one full-size canvas. Badge `APNG`.
- **MediaStore first frame** (Android 10+, todo §10.10): before a video's strip decodes, its id is looked up in MediaStore (one query per folder for the videos directly in it, cached for 32 folders) and `ContentResolver.loadThumbnail` gives the first frame — usually already made by the gallery — within 1.5 s or not at all. While the folder is still being listed and only first frames are wanted, that frame is all the tile gets: no video decoder runs. Files MediaStore doesn't know (`.nomedia` folders, storage it doesn't index) keep the decoder's frame.
- **Animated WebP** (`WebpAnim`, todo §10.2): `AnimatedImageDrawable` can't seek, so the RIFF container is read directly. A chunk scan reads the canvas size and each `ANMF` frame's rectangle, duration, blend and dispose flags and data offset without decoding (cached in SQLite like GIFs; a still WebP is cached as 1 frame and keeps the image path). The first frame comes from the still decoder at once. For the strip, each needed frame is rewrapped as a standalone WebP (`VP8X` + `ALPH` + `VP8 `, or `VP8L`) for `BitmapFactory` and drawn on one full-size canvas (`SRC_OVER` or `SRC`, then clear on dispose). Decoding starts at the nearest key frame (libwebp's rule), so long animations skip most frames. Checked against libwebp through Pillow: identical composited frames for lossless, lossy and lossy + alpha files. WebP tiles take the GIF/video strip path (scrub, auto-preview, live play when selected, `WEBP` badge).
- **GIFs** use a pure-Kotlin codec (`GifCodec`). Its output matches Pillow exactly on the reference folder, in frame count, duration and composited pixels.
  - *Block scan:* walks the block structure, skipping LZW data by length. It reads frame count, total duration and size without decoding, in ~3 ms for a 16 MB GIF. Results are cached in SQLite.
  - *Strip pass:* one sequential decode from the start that keeps only the 5 target frames, scaled to tile size, and stops after the last one. These GIFs are diff-encoded (almost every frame is a transparent patch over the previous ones), so frames can't be decoded on their own. One canvas and one "restore previous" backup are the only full-size buffers.
  - The first frame alone takes a few ms, whatever the file size.
- **Resolution follows the tile.** The cache remembers the tile width each thumbnail was decoded for. When a tile gets more than 25 % wider (window maximised, app moved to a monitor, larger thumbnail size), the old thumbnail stays on screen and a sharper one is decoded once the size has been steady for 300 ms, so dragging a window edge doesn't start a decode per frame. Shrinking never decodes again.
- **Watchdogs:** decode 5 s, plus 250 ms per MB for a GIF strip; metadata or block scan 3 s; large-GIF pass 60 s. The clock starts when a decode gets a thread, not while it waits in the queue. A broken file gets an icon and is not retried automatically during the session. A file that timed out gets the hourglass icon and is tried again whenever thumbnail work has been idle for 1.5 s, while its tile is on screen, with the watchdog doubled each time (up to ×8); only the first timeout is reported. Opening a file in the slideshow retries it.

### 3.5 Slow operations and errors

- A user-started operation that takes over ~300 ms shows a non-modal wait indicator. It has a label (*"Reading folder…"*, *"Loading 196 MB GIF…"*, *"Copying 3 / 12: …"*), a progress bar when progress is known, the elapsed time after 3 s, and **Stop**. Back and Esc also stop it. Stop returns the UI to its previous state at once; a call that is stuck (e.g. on a hung USB drive) is abandoned, not awaited (`abandonable`). A stopped folder listing keeps what it loaded and shows *"Listing stopped — tap to continue"*.
- Read and decode errors are never dialogs. The item gets an icon (or is skipped in the slideshow). Errors from one folder within 2 s become one snackbar (*"12 files couldn't be read"*), whose *Details* action opens the **error log**: the last 200 errors with path, reason and time, also kept in a small file. Everything is logged with the `MediaViewer` tag.
- Uncaught exceptions never end in the system "app stopped" dialog. `CrashHandler` writes the report (version, device, thread, stack trace) to `files/crash_pending.txt`, logs it, and opens the crash screen in its own `:crash` process, so it works even when the app can't start. The screen shows the exception and offers **Reset settings and clear cache**, **Clear cache only**, **Restart**, **Copy details** and **Close**. If Android doesn't let it open (the app was in the background), the next launch shows it instead. The last report stays in `files/last_crash.txt`.
- A removed SD card or USB drive shows *"Storage removed"*, and the view moves to the nearest existing parent folder, or the root. A running slideshow stops the same way, but its session is kept, so it resumes when the drive comes back.

### 3.6 Delete, trash and undo

A deleted file is renamed into a hidden `.mediaviewer-trash/` folder next to it, on the same volume, so even a 196 MB GIF moves instantly. The Undo snackbar renames it back. When the snackbar closes, the file is removed for good. Pending trash entries are recorded in a small file, so a killed app purges them on the next start. `MediaStore.createTrashRequest` is not used, because it always shows a system confirmation dialog.

### 3.7 Random order that survives restarts

Each file gets a pseudo-random key, `hash(seed, file name)`. The random order is the media list sorted by that key, with the item random order was turned on from pinned first. The session stores only the seed, the pinned file and the current file. After a restart, the same order is rebuilt exactly, even if files were added or deleted meanwhile: every remaining file keeps its place. A new cycle uses a new seed. If its first item is the one just shown, the next one is pinned first instead.

### 3.8 Persistence

| Data                                                                                                                                                                    | Where                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| Settings                                                                                                                                                                | DataStore, written on every change; read before the first frame (the splash screen waits) |
| Recent folders (up to 50): path, time, scroll anchor (key of the first visible item + offset), last viewed item                                                               | DataStore (JSON)                                                                          |
| Folder sorts (up to 2,000): path, name or date, descending                                                                                                              | DataStore (JSON)                                                                          |
| Slideshow session: folder, current item, random on/off, seed, pinned item, list size + hash, video position; saved on every item change and every second during a video | DataStore (JSON)                                                                          |
| GIF / video metadata                                                                                                                                                    | SQLite, ~100 B per file                                                                   |
| Listening history: current file, position and speed per book; position and finished flag per file (§3.12)                                                               | SQLite `history.db`, user data: never pruned, migrated                                    |
| Thumbnails                                                                                                                                                              | memory only (LRU)                                                                         |

### 3.9 Samsung DeX and desktop windows

| Concern                                                       | How it is handled                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Switching into / out of DeX changes density, size and UI mode | `configChanges` covers them, so the activity is not recreated. Samsung's `com.samsung.android.keepalive.density` and `multidisplay.keep_process_alive` meta-data keep older DeX versions from killing the app on the switch                                                                                                                      |
| Launch size                                                   | `com.samsung.android.dex.launchwidth` / `launchheight` = 0: DeX opens the app full screen. Elsewhere, `<layout android:defaultWidth="100%" android:defaultHeight="100%">` asks for a window that fills the screen; minimum window 400 × 320 dp                                                                                                   |
| Taskbar over the full-screen slideshow                        | `com.samsung.android.dex.transient_bar_delay` = 2000 ms: the DeX taskbar appears only after the pointer rests at the screen edge                                                                                                                                                                                                                 |
| Title bar of a desktop window                                 | It is reported as a caption-bar inset, not a status bar. The folder view pads its top bar with `statusBars ∪ captionBar` (`topInset()`); the other screens use `safeDrawing`, which includes it                                                                                                                                                  |
| Right-click as Back                                           | DeX can turn a right-click that no view consumed into `KEYCODE_BACK` from the mouse. `MainActivity` records each right-button press (`dispatchTouchEvent`, `ACTION_BUTTON_PRESS`) and drops a mouse Back that follows one within 500 ms. The real mouse back button is unaffected. This is a heuristic that still needs checking on a DeX device |
| Window focus                                                  | Since Android 10 all visible windows stay resumed; on Android 9 a visible window is paused when another one gets focus. The slideshow saves its session on `ON_PAUSE` but pauses the video only on `ON_STOP`                                                                                                                                     |
| Desktop detection                                             | `Configuration.semDesktopModeEnabled == SEM_DESKTOP_MODE_ENABLED` (read by reflection) for DeX, `FEATURE_PC` for Chromebooks; read again on every configuration change                                                                                                                                                                           |
| Windowed slideshow                                            | API 30+: current window bounds ≠ maximum window bounds; below: `isInMultiWindowMode`                                                                                                                                                                                                                                                             |
| Drag out and clipboard                                        | `View.startDragAndDrop` with `DRAG_FLAG_GLOBAL` and `DRAG_FLAG_GLOBAL_URI_READ` and `FileProvider` URIs; `Ctrl` + C puts the same URIs on the clipboard                                                                                                                                                                                          |
| Drop in                                                       | `Modifier.dragAndDropTarget` on the folder grid and on each folder tile (a drop there goes into that folder, the tile outlined while a drag is over it); `Ctrl` + V reads `content://` / `file://` URIs from the clipboard and copies them the same way; `requestDragAndDropPermissions` for the content URIs, released after the copy; `DropImport` copies streams (name from `OpenableColumns`, " (1)" on clashes, modification time kept); our own FileProvider URIs map back to paths and go through `FileOps`                                       |

### 3.10 Text previews

Tiles of text files show their first lines instead of an icon.

- **Which files.** By extension, `FileTypes` sorts them into `TEXT` (`txt md log csv json xml yaml srt vtt lrc nfo cue m3u diff html …`) and `CODE` (`c cpp java kt py js tsx go rs sh sql css …`, plus `Makefile`, `Dockerfile`, `.gitignore`). Well-known names without an extension (`README`, `LICENSE`, `CHANGELOG`) are text. Files with no or an unknown extension get a text check too. `doc docx odt rtf` are `DOCUMENT` and `pdf` is `PDF`, with their own icons (previews for them are planned).
- **Listing stays cheap.** Listing still reads only 200 bytes per extensionless file (the magic sniff). The text check reads 8 KB, and only when a tile is drawn, inside the thumbnail scheduler.
- **`.ts`** is MPEG-TS video or TypeScript. Listing checks for the TS sync byte (`0x47` every 188 or 192 bytes); without it the file is code.
- **Decoding** (`TextDecode`, pure Kotlin, unit-tested):
  1. BOM → UTF-8 / UTF-16 LE / BE.
  2. A NUL byte, or more than 2 % control characters → binary: the icon stays, and the file isn't checked again during the session.
  3. Strict UTF-8; a character cut off at the end of the 8 KB is fine.
  4. Otherwise legacy 8-bit text, in the first charset found: one the file declares (`<?xml encoding>`, `<meta charset>`, Python / Emacs `coding:`, Vim `fileencoding=`), then a letter-frequency check that tells Windows-1251, KOI8-R and CP866 apart, then the setting *Text files without encoding* (by default Windows-1251 for Cyrillic-script device languages, Windows-1252 otherwise). `android.icu.text.CharsetDetector` is not in the public SDK, so no general detector is used.
- **Rendering** (`TextPreview`): `StaticLayout` / `Canvas` into an `ALPHA_8` bitmap the size of the tile, on the decode dispatcher under the 5 s watchdog. The tile tints it with the theme's text colour on a paper-like background, so one cached bitmap serves light and dark themes at a quarter of the memory. The extension is shown as a badge.
  - Font: monospace for code, logs, CSV, JSON and subtitles (JetBrains Mono, bundled; the system monospace with the setting off); proportional (soft-wrapped) for `txt` / `md`, with bold Markdown headings.
  - Size: 10 sp, growing with the system font size up to 13 sp; 12 sp on TV. Line height 1.2×, 6 dp padding.
  - Whole lines only. The tile size sets the number of lines, never the font size; below 3 lines the icon is shown. Leading blank lines are dropped, tabs expand to 4 columns, control characters are removed.

### 3.11 Opening files with other apps

- **Audio files** play in the app (§3.12); *Open with…* in the context menu still offers other apps.
- **Listening position:** a long video that has a listening position (§3.12, lectures) is opened at that position.
- **Other files** open with a plain `ACTION_VIEW` (not a chooser), so the system's *Always* choice works. The MIME type comes from `FileTypes` (PDF, Office, EPUB, audio, archives, APK…), then `MimeTypeMap`. If no app handles that type, a chooser for `*/*` opens; if that fails too, a snackbar. **Open with…** in the context menu always shows the chooser.
- **Long videos.** A tap on a video longer than the setting (default 3 min; 0 = off) opens the external player when one is installed; otherwise the slideshow. The duration comes from `MetaStore`; on the first tap in a new folder it is read with `MediaMetadataRetriever` under the watchdog, on IO. Tiles of such videos show ↗ bottom-left. `Shift` + Enter and the context menu's *Play in slideshow* keep them in the app; a long video reached inside a running slideshow plays there.
- **Player lookup:** `queryIntentActivities(ACTION_VIEW, video/*)` on IO, again on every resume (manifest `PACKAGE_ADDED` receivers don't fire on Android 8+). Android 11+ needs the `<queries>` entry in the manifest.
- **Resume position:** VLC reads the extra `"position"` as a `Long` (ms), MX Player as an `Int` — the same key with different types. The app resolves the default handler first and sets the type per package; the chooser uses `EXTRA_REPLACEMENT_EXTRAS` for MX Player. `"title"` is passed as well.
- **Where:** other apps are opened only from the folder view — never from the slideshow, and never in selection mode.

### 3.12 Listening (books, albums, lectures)

- **Service:** `PlaybackService`, a Media3 `MediaSessionService` (foreground type `mediaPlayback`), owns one ExoPlayer with the FFmpeg audio fallback. The video track is disabled with `TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`, so videos are decoded as audio only. Audio focus (`USAGE_MEDIA`, speech), `setHandleAudioBecomingNoisy` (pause on unplugged headphones) and `WAKE_MODE_LOCAL`. The system media notification provides lock-screen, Bluetooth, headset, watch and car controls.
- **UI:** `BookPlayer` holds one `MediaController` for the Play button, the mini player and the player screen; they poll the position twice a second only while playing. Controllers send media items without their URI; the session rebuilds it from the media ID, which is the file path.
- **Folder kind** (`Book.classify`, pure Kotlin): computed on IO once the listing is complete. Lecture folders need every video's duration: from `MetaStore`, or `MediaMetadataRetriever` under the thumbnail watchdog; the first short video ends the check. Folders with more than 300 videos are not checked.
- **Queue** (`BookFolder.queue`, on IO): playable files (audio + video) of the folder, in playlist order (`Playlist.parse`: comments, relative and absolute paths, `..`, Windows paths matched by file name, URLs skipped; `.m3u` decoded like text previews, `.m3u8` as UTF-8), then unlisted files in natural order.
- **History** (`HistoryStore`, `history.db`, version 3): `audio_folder` (current file, position, speed, finished, last played; since v2 also the file number, queue length and file duration, for the folder tiles — read for all subfolders of a folder view in one prefix-range query; `shuffle`, `seed` and, since v3, `first` and `repeat` for music mode, `lecture` (the override) and `cc`) and `audio_file` (position, finished). Separate from `MetaStore`, which is a disposable cache; it is never pruned and migrates instead of dropping tables. Saved every 5 s while playing, and at once on pause, file change, seek, task removal and service stop; writes run on IO in the app scope. A file is finished within 2 % or 30 s of its end.
- **Smart rewind** (`Book.rewindMs`): 0 s after a pause under 1 min, 5 s under 1 h, 20 s after longer — on resume within the service and when a book is started again.
- **Resumption:** `MediaSession.Callback.onPlaybackResumption` rebuilds the last book's queue on IO; `MediaButtonReceiver` in the manifest lets a headset Play start it.
- **Idle:** paused for 30 min, or the book ended → the service stops and the notification goes. Swiping the app away while paused stops it too.
- **Slideshow:** with sound on, its video player takes audio focus, so a playing book pauses.
- **Player screen** (`PlayerScreen`): an overlay (`Overlay.PLAYER`) over the root or folder screen, so Back and the folder view under it stay as they were. It takes the key focus when it opens (the cover in keyboard mode), so keys never reach the grid below.
- **Music mode** (`ShuffleOrder`, pure Kotlin): the queue sorted by `SlideOrder.key(seed, name)` with the file playing pinned first; `shuffle` + `seed` + `first` in `history.db` rebuild the same order after a restart, and files added meanwhile fall into place. Switching reorders the controller's items in place with `moveMediaItem` (no reload, no gap; over 400 files one `setMediaItems` instead). The service skips the smart rewind while `PlaybackService.musicMode` is set (the UI and the service share a process). Repeat (`Player.repeatMode`, music only) is kept in `audio_folder.repeat`.
- **Skips** (`Skip.ms`): ExoPlayer's seek increments are fixed when it is built, so the session is given a `ForwardingPlayer` whose `seekBack` / `seekForward` and increments read the settings and the mode on every call; the app's buttons call `seekBack` / `seekForward` through the controller, so every surface skips the same. **Skip silence**: the service applies `ExoPlayer.setSkipSilenceEnabled` from the settings flow.
- **Tile ▶ and *Continue listening*:** the collage scan counts audio files directly inside a folder (`Thumb.Folder.audioCount`, no extra read); books with history show ▶ too. Lecture folders without history get none (their kind needs every video's duration). *Continue listening* reads `recentFolders`, keeps books whose current file still exists, and loads small covers (`TrackExtrasLoader.cover`, ≈ 150 px) on IO; it is read again when a book starts, stops or changes file. *Start over?* is asked by `AppViewModel.playBook` for every entry point except the headset's resumption, which still starts a finished book over.
- **Chapters, lyrics, title and cover** (`TrackExtrasLoader`, on IO, each step under an 8 s watchdog; the last 4 files cached): `MediaTags` reads chapters and lyrics without a decoder — MP4 boxes (`moov/trak/tref/chap` → the text track's `stts` / `stsc` / `stsz` / `stco` / `co64` → samples; else `moov/udta/chpl`; `moov/udta/meta/ilst/©lyr`), ID3v2.3 / 2.4 frames (`CHAP` with its `TIT2`, `SYLT` — returned as LRC, so it highlights like an `.lrc` — and `USLT`; unsynchronisation, extended headers, iTunes' plain v2.4 sizes), FLAC and Ogg Vorbis / Opus comments (`LYRICS`, `UNSYNCEDLYRICS`, `CHAPTERxxx` / `CHAPTERxxxNAME`; the Ogg comment packet is put together from the first stream's page segments), EBML (`Segment` → `Chapters`, directly or through the `SeekHead` past the clusters; the default visible edition, hidden and disabled atoms skipped). Every length is checked against the file size and an 8 MB cap before anything is allocated. Cue sheets and `.lrc` / `.srt` / `.vtt` are decoded like text previews. The title and the embedded picture come from `MediaMetadataRetriever`; covers are decoded at ≤ 1024 px.
- **Lectures** (`audio_folder.lecture`): the override is read before `Book.classify`; *Listen as audio* skips the duration check. **Shared position:** `SlideshowController` loads the folder's `audio_file` rows when it opens; a video with one starts there, and leaving it (next / previous, Back, the menu, closing) updates the row — only rows that exist, so the slideshow never adds files to the history. `BrowserState.openVideo` passes the row's position to `ExternalApps.open`.
- **Covers for videos:** `TrackExtrasLoader` tries the embedded picture, the image named like the file, then `getScaledFrameAtTime` at 10 % (closest key frame), then the folder's cover image. The notification still has no artwork (it would need the session's metadata to change while playing).
- **Embedded subtitles** (`MediaTags.subtitles`): Matroska — `Tracks` gives the text track (type 17; `S_TEXT/UTF8`, `ASS`, `SSA`, `WEBVTT`), then every `Cluster`'s `SimpleBlock` / `BlockGroup` of that track (other tracks' blocks are skipped unread, through a 64 KB read window in `FileSource`); ASS dialogue text is the 9th field, override tags removed. MP4 — a `tx3g` sample entry under a `sbtl` / `subt` / `text` handler that isn't the chapter track; samples as for chapters. Used after companion files and embedded lyrics.
- **Moved books** (`BookMatch`, pure Kotlin; `HistoryStore.orphans` / `moveBook`): rows with a current file whose folder is gone, on storage that is present; the recorded files (the ones played) must be in the new folder — the current one, and 80 % of the rest — and with only one recorded file the folder name must be the same. One match moves `audio_folder` and `audio_file` paths in one transaction; two or more move nothing.
- **Sleep timer** (`BookPlayer`): a watcher checks the time left once a second, four times a second in the last 10 s, where it sets the controller's volume (`SleepTimer.volume`, a squared ramp); at zero it pauses and puts the volume back. *End of file / chapter* counts the time left at the playback speed, and also pauses on the automatic move to the next file. The accelerometer is listened to only while the sound fades.

### 3.13 Text editor

- **Screen:** `Screen.Editor` in `AppViewModel`, over the folder's listing (which keeps its live updates). `EditorState` holds the text, undo history, colours and the save logic; the view model keeps it, so unsaved text survives the activity being recreated. Back returns to the folder with the file as the focus target.
- **Opening** (`TextFile.load`, on IO under a 20 s watchdog, with the wait indicator): over 10 MB → other apps; the first 8 KB are checked for binary data before the whole file is read; then `TextDecode.decodeFull` (the preview rules of §3.10, over the whole file) gives the text, the charset and the BOM. CRLF is detected and turned into `\n` while editing. Over 1 MB, or not writable → read-only, shown line by line in a `LazyColumn`.
- **Text field:** Compose 1.8's state-based `BasicTextField` can't style text yet, so the editor uses the `TextFieldValue` one with a `VisualTransformation` that adds the colour spans (offsets map 1:1). Its own undo works only from hardware keys, so `EditHistory` (pure Kotlin) keeps edits as diffs (common prefix and suffix) and groups typing or deleting within 1 s; the toolbar and `Ctrl` + Z use it.
- **Themes** (`EditorTheme`, pure Kotlin): a table of palettes, each with a colour for the 12 roles plus background, text, cursor, selection, current line, find marks, bracket mark and gutter. Classic palettes were kept where they reach WCAG AA (4.5 : 1 against the background) and nudged in lightness, hue kept, where they didn't (Solarized's accents, the Monokai, Dracula and One Dark comments); *Default* lost a step on two light colours (comment `757575` → `717171`, warning `B26A00` → `A86400`). `EditorTest` checks every role and the text of every palette. *Default* draws on the app's own background and text colours (dynamic on Android 12+); setting `editorTheme`, `""` = Default.
- **Drawing:** the text field sits in an outer vertical scroll (and a horizontal one without wrap), as tall as its text, so the current line, line numbers and whitespace marks are drawn from the field's `TextLayoutResult` in the same coordinates (`drawBehind`, rows on screen only). Since the field no longer scrolls itself, the editor keeps the cursor in view after each selection or text change and when the keyboard resizes the window — this also brings find's selection into view without focus. **Brackets** (`Brackets.match`, pure Kotlin): the bracket after the cursor, then the one before; a scan of ±20,000 characters that jumps over `STRING` / `COMMENT` spans; in markup, the tag name under the cursor matched by name with nesting, self-closing tags skipped. The pair is two background spans in the `VisualTransformation`. Over the colour limit there are no spans, so brackets in strings count there.
- **Colours** (`Highlight`, pure Kotlin): the language comes from the file name. The C-like, script and config languages share one table-driven lexer (keywords, comments, strings, numbers, annotations, `$variables`, `#` preprocessor lines); markup, JSON, CSS, YAML, INI/TOML, Markdown, subtitles, logs (including logcat), diff and CSV have small lexers of their own. Lexing runs on the Default dispatcher 150 ms after typing stops; in between, each edit moves the existing spans (`Spans.shifted`), so colours stay on their words. Texts over 200 K characters are not coloured.
- **Saving** (`TextFile.save`, on IO under the watchdog): size and modification time are compared with those at opening (changed → ask). The text is encoded with the file's charset, BOM and line endings, strictly (an unmappable character → ask for UTF-8), written to `.name.tmp` in the same folder, `fsync`ed, and renamed over the file; if the rename fails, the file is written in place. The tile preview is dropped from the cache (`Thumbnails.forget`). `FolderListing` treats a file renamed over an existing one like a finished write, so the entry's size and date are refreshed.
- **Drafts** (`Drafts`): on `ON_STOP`, unsaved text goes to `files/drafts/` as one JSON file per path; it is removed on save or discard, and offered again when the file is opened.
- **New files** (`TextFile.create`): `createNewFile`, which never overwrites. Names are trimmed (spaces, trailing dots) and checked for `/`, NUL and 255 UTF-8 bytes.

### 3.14 MTP devices (cameras and phones over USB)

Spec and open work: [todo §9](todo.md#9-mtp-devices-cameras-and-phones-over-usb). Untested on a device; the connection, mirror and transfers are tested against an in-memory fake device (`FakeMtpDriver`: storages, folders, thumbnails, events, slow, hanging and failing calls).

- **Finding devices** (`data/mtp/MtpDevices`, one per app): `UsbManager` devices with a still-image interface (PTP) or a vendor interface named `MTP`; a receiver follows plugging, unplugging and the answer to the system permission prompt. A device with permission is opened at once (`MtpDevice.open`); its storages become `Volume`s with `device = true` and `mtp://<device id>/<storage id>` paths (`MtpPaths`). The home screen shows them under **Devices**; a device that isn't ready shows why, and a tap asks again.
- **Calls** (`MtpConnection`, pure Kotlin over `MtpDriver`; `AndroidMtpDriver` wraps `android.mtp.MtpDevice`): every call to a device runs on that device's own thread, under a watchdog (8 s; listing and deletes 30 s; thumbnail 10 s; chunk 20 s; a whole-file transfer 20 s + 1 s per MB); after a timeout the device counts as *not responding* and later calls fail at once. A caller that leaves (Stop, a tile scrolled away) is not a timeout. Calls are short (file details 16 at a time, listing batches of at most 50, 1 MB chunks), so tiles get their thumbnails while a download runs.
- **Folders:** `FolderListing` takes the device scanner instead of `FolderScanner`, and device events instead of a folder watcher. Entries have `mtp://` paths; types come from the extension or the MTP object format. `Thumbnails.load` uses the device's own thumbnail (`getThumbnail`) for media; an image the device has no thumbnail for (some RAW + JPEG cameras, players), if the platform decodes it and it is at most 8 MB, is downloaded into the mirror and decoded. **Folder tiles** get a collage of the device thumbnails of the first media files (up to 8 candidates among the first 64 objects; no thumbnails, no collage). `BrowserState.remote` turns off share, copy to clipboard, new text files, favourites and editing.
- **Live updates:** each connection reads device events (`readEvent`) on a thread of its own. *Object added* and *object info changed* read the object's details and add or replace it in the open folder (only folders listed in this session are known by handle); *object removed* takes it out (and its mirror copy); storage events re-read the device's storages. A device that sends no events (**verify** on PTP cameras) just has no live updates.
- **Delete** (`AppViewModel.deleteOnDevice`): devices have no trash, so a deleted file only leaves the listing while the 5 s *Undo* snackbar shows; `deleteObject` runs when it closes. A folder the device won't delete in one go is emptied first (**verify** which cameras need it). A failed delete puts the file back with *"Couldn't delete …"*. Deleting in a device slideshow deletes the device file and its mirror copy.
- **Copy and move with a device** (`MtpTransfer`): the folder picker lists device storages and folders. This device → device: `sendObjectInfo` + `sendObject` (one call per file; Stop takes effect when it ends, and a file whose copy was stopped is deleted on the device); folders with `ASSOCIATION` objects. Device → this device: downloads as before. Device → device: through a temporary copy in `cache/mtp-transfer`. Clashes get `(1)` names, compared without case as device storages do. A move deletes each original after its copy. **New folder** in a device folder (`+`, `Shift` + N).
- **Auto-open:** an intent filter for `USB_DEVICE_ATTACHED` with a still-image (class 6, PTP) device filter: Android offers *Open Media Viewer?* when a camera is plugged in, which also grants access. The device's storage opens once it is ready (several storages: the home screen). Phones in MTP mode use a vendor interface the filter can't name, so they are found as before. `MainActivity` is `singleTop`, so a camera plugged in while the app is open reaches it through `onNewIntent` (**verify**).
- **Slideshow:** `AppViewModel.openDeviceSlideshow` downloads the file into `cache/mtp/…` (a `.part` file renamed when complete), parks the device folder's listing, and opens the slideshow over a normal listing of the mirror folder. A background job downloads the folder's other media in order, up to 2 GB in the mirror; the mirror's folder watcher adds each one to the slideshow. Closing it brings back the parked listing, focused on the device path of the last item. The mirror is deleted at start and when a device is unplugged; slideshow sessions in it are never resumed.
- **Open with** downloads first and passes the local copy.
- **Unplugging:** the devices flow drops the device; a folder or slideshow from it closes with *"Device disconnected"*.

---

### 3.15 Previews of documents, books, audio and apps

Tiles of other files show a picture when the file has one (todo §1.0), with a badge; the icon stays otherwise. Everything runs in the thumbnail scheduler under the 5 s watchdog, decoded at the tile size and cached like other thumbnails.

| Files | Picture | Badge |
| --- | --- | --- |
| PDF | page 1 (`PdfRenderer`) on white, filling the tile's width from the top | `12 p.` |
| Audio | the picture inside the file, else the folder's cover image (`cover` / `folder` / `front`…, listed once per folder) | duration |
| EPUB | the OPF's `cover-image` item, the item `<meta name="cover">` points at, an image named *cover*, else the first image | `EPUB` |
| FB2, FB2.ZIP | the `coverpage` image (base64 inside the book) | `FB2` |
| CBZ | the first image in natural order | `CBZ` |
| ODT / ODS / ODP | `Thumbnails/thumbnail.png` | the format |
| DOCX / XLSX / PPTX | `docProps/thumbnail.*` when the app saved one; a DOCX without one shows its paragraphs as a text preview | the format |
| APK | the app's icon (`getPackageArchiveInfo`, with the archive as its source) | the app's name |
| SVG, SVGZ | the drawing scaled into the tile (AndroidSVG 1.4; no external resources are fetched; files over 8 MB keep the icon) | `SVG` |
| CSV / TSV | the first lines as an aligned table (`CsvPreview`: tab, `;`, `,` or `|` sniffed, quotes kept, cells cut at 18 characters) | `CSV` |

- **Reading ZIPs** (`ZipIndex`, pure Kotlin): the central directory from the end record, then one entry, stored or deflated (`Inflater`). Every size is checked against the file and a cap (24 MB per entry, 16 MB of directory) before anything is allocated; ZIP64 and encrypted entries are left out. `DocCovers` finds the cover entry and the DOCX text with small tag and attribute matching, no XML parser. FB2 is read as Latin-1: its tags and base64 are ASCII whatever the book's encoding.
- **Kinds:** `svg svgz` are drawings (`VECTOR`); `epub fb2 cbz cbr mobi azw3 djvu` (and `.fb2.zip`) are books, with a book icon; `xls xlsx ods ppt pptx odp odg` are documents. CBR, MOBI, AZW3 and DjVu keep the icon.
- **Folders without media:** when the collage scan finds no images or videos, it takes these instead — books, PDFs, documents, the first audio file (an album's files share one picture), then text and code, which go into the collage as dark ink on a light page. The item count shows when there is nothing, or nothing could be drawn.

## 4. Building and checking

### Build

Requirements: JDK 17+ and an Android SDK with platform 35 or 36 (on the dev machine `/usr/lib/android-sdk`, set in `local.properties`).

```bash
./build-apk.sh                      # debug APK → release/media-viewer-debug.apk
./build-apk.sh --variant release    # minified release APK (signed with the debug key for now)
./build-apk.sh --install            # build and install with adb
./build-apk.sh --help               # all options (--clean, --lint, --ci, …)
```

The script checks Java, the SDK and the Gradle wrapper first, and prints fix commands if something is missing.

### Checking without the Android SDK

`tools/pure-tests.sh` runs the pure-Kotlin tests and `tools/typecheck/run.sh` type-checks the whole app, both without Gradle or the SDK (docs/RESEARCH_2026-10.md §0). They download `kotlinc` and their jars on the first run, outside the repo. The pure tests include the MTP connection, mirror and transfers against `FakeMtpDriver` (`MtpConnectionTest`, `MtpMirrorTest`, `MtpTransferTest`: listing batches, the watchdog, the 2 GB budget, delete, uploads, events), with one-line stubs of `Log` and `SystemClock`.

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

The tests cover smart crop (the examples above), slideshow timing (the table above), natural sort and folder order, the random order (no repeats, restore after restart, adds and deletes), file-type detection and the GIF codec on synthetic files. `EditorTest` covers the syntax colouring of every family, span shifting, undo and the WCAG contrast of every theme; `BracketsTest` covers nesting, strings and comments, unmatched brackets, the scan bound and markup tags; `TextFileTest` covers charset, BOM and CRLF round trips, *Save as UTF-8*, changed-on-disk detection, names and drafts. `PlayerTest` covers lyrics (LRC, SRT, VTT), companion files and covers, chapters and cue sheets, the shuffle order, speed steps and the sleep fade; `MediaTagsTest` reads chapters and lyrics from MP3, M4B and Matroska files built in memory, including broken ones.

### Checking a real media folder

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

### Reading the app log

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

---

## 5. Performance and the reference folder

Targets:
- the first tiles appear in < 100 ms (names and icons first, thumbnails after);
- scrolling stays at the display refresh rate while a 30k-file folder streams in;
- no main-thread disk I/O (`StrictMode` logs it in debug builds);
- bitmaps are always decoded at tile size;
- a resumed slideshow shows its first image < 1 s after a cold start.

The app is tested against a real media folder on the dev machine. This is its profile, measured on 2026-10-24 by `tools/check-media-folder.sh`:

<!-- REFERENCE-PROFILE -->
*(Being measured: the full check is running. The numbers below are from the original spec and will be replaced.)*

| Property   | Value                                                                              |
| ---------- | ---------------------------------------------------------------------------------- |
| Layout     | one flat folder, ~29,150 files, no subfolders except a hidden `.ipynb_checkpoints` |
| Total size | 338 GB                                                                             |
| GIF        | 27,793 files (95 %), median 10.9 MB, p90 25.5 MB, max 196 MB                       |
| MP4        | 571 files, H.264, 720p up to 2048×2048, 2–152 s                                    |
| JPEG / PNG | 525 / 213 files                                                                    |
| Junk       | `.crdownload` files, 0-byte GIFs, odd extensions, html/pdf/csv/json/sh             |
<!-- /REFERENCE-PROFILE -->

GIF decode speed on the dev machine (desktop JVM, files from page cache): block scan ~3 ms, first frame ~4 ms, 5-frame strip ~470 ms for a typical 16 MB, 240-frame GIF. The strip time is inherent to diff-encoded GIFs; tiles show the first frame at once and start animating when their strip is ready.

---

## 6. Limitations and open questions

**Not implemented**
- **Animated AVIF strips.** Android offers no way to seek inside these animations (`AnimatedImageDrawable` only plays), so tiles show the first frame; animated WebP and APNG have their own readers now (§3.4), AVIF would need the AV1 decoder through `MediaCodec` (docs/RESEARCH_2026-10.md §2). The slideshow animates both, for R seconds, looped.
- **APNG** is shown as a still everywhere: the platform decoders only read its first frame.
- **FFmpeg video decoding.** The bundled FFmpeg extension decodes *audio*; its video renderer is still experimental in Media3, so video codecs depend on the device's decoders. Rare AVI video codecs (MJPEG, old DivX on some devices) may not play, and their thumbnails show the broken-file icon. Everything in the reference folder is H.264 + AAC.
- **MTP devices (§3.14)** are untested on a device (tested against a fake one): `sendObject` and folder creation on cameras, events from PTP cameras, folder deletes, auto-open while the app is open. Streaming without the mirror and remembered devices are open (todo §9.8). Android's Files app may hold a device so that this app can't open it (**verify**).
- **Storage Access Framework fallback**: not built. Options (Play declaration as a file manager, a media-only flavor, a full SAF layer) and a recommendation are in docs/RESEARCH_2026-10.md §7. Not planned while no Play Store release is planned (owner, 2026-10-29).
- **Generated Baseline Profile.** The bundled profile is a broad hand-written rule for the app's own code; a profile recorded with Macrobenchmark on a device would be more precise.
- **Text previews and opening with other apps (§3.10, §3.11)** are untested on a device: the external players' `"position"` extras, the Cyrillic charset check on real files, and the text rendering need a check.
- **The text editor (§3.13)** is untested on a device: typing speed in a 1 MB file and colouring speed near the 200 K limit on a low-end phone, the on-screen keyboard over the text, pinch next to text selection, `Tab` and `Esc` with DeX keyboards, and the safe save (rename over the file) on SD cards and USB drives (FAT / exFAT through FUSE).
- **The player screen, music mode, chapters, lyrics and the sleep fade (§3.12)** are untested on a device: the layout at 360 dp and in DeX, the blurred cover (Android 12+), the list handle, D-pad focus order on a TV, the controller's volume during the fade (**verify** the session allows `COMMAND_SET_VOLUME`), shake detection while the screen is off (the accelerometer may be batched or stopped by some phones), gapless moves when shuffle reorders a playing queue, and chapters from real M4B files made by different tools. Also the folder tile's ▶ inside the tile's own gestures, the notification's skip buttons with the `ForwardingPlayer` (Media3 may draw fixed ±10 s icons), skip silence with speech, and the battery hint's settings screen on each maker. Lectures: the slideshow starting at the listening position, VLC / MX Player starting there, frame covers from 4K / HEVC files, subtitle tracks of long MKV files (the cluster walk reads every block header: **verify** its time on a slow SD card under the 8 s watchdog), the ⋮ choice in the folder bar at 360 dp.
- **Listening (§3.12)** is untested on a device: background playback with the screen off, Samsung / Xiaomi battery savers, video files played as audio (battery use), headset Play after the app was killed, and the system media controls offering the last book after a reboot (`onPlaybackResumption` without a `MediaLibraryService` may not be enough for that).
- **Previews of documents, books, audio and apps (§3.15)** are untested on a device: `PdfRenderer` with large or damaged PDFs (a native crash would take the app down: if tombstones show up, decode in a separate process, todo §5), APK icons of split APKs (`.apks` / `.xapk` are ZIPs of APKs: they keep the icon), album art in large folders while scrolling, and the text-page collages.
- **APNG strips, SVG tiles and MediaStore first frames (§3.4, §3.15)** are untested on a device: APNGs from different encoders (apngasm, ffmpeg, browsers' exports), `loadThumbnail` timing on a cold MediaStore (1.5 s limit), SVGs with text and filters (AndroidSVG draws a subset of SVG 1.1), and R8 with AndroidSVG in the release build.
- **Lint:** 18 older errors remain (Media3 `@UnstableApi` opt-ins in the slideshow and tile playback, `dispatchKeyEvent` in `MainActivity`); the release build doesn't run lint.
- **Legacy charsets other than Cyrillic** (Greek, Central European…) are not detected: they use the *Text files without encoding* setting. juniversalchardet (~200 KB) would add detection if needed.
- **On-device tests.** No emulator or device was available, so the Android-only paths (still and video thumbnails, playback, the UI) are untested on a device. This includes everything in §3.9: the DeX launch size, the right-click guard and the caption-bar inset need a check on a DeX device.
- **Dropping into the app** is untested on a device: which apps grant read access to their dropped URIs, and whether a dragged folder arrives as a tree URI that can be listed (otherwise folders are refused with a message). Drops onto a folder tile and `Ctrl` + V are new and untested too: whether the grid's own target lets the tile's take the drop (Compose gives it to the innermost target under the pointer **verify**), and whether pasted `content://` URIs of other apps can be read (the clipboard grants read access to the app that reads it **verify**). Moves (with `Shift`) are open.

**Decisions taken** (formerly open questions)
- Mouse, gallery clicks (phones, tablets, TV): a click waits for a possible second click (~300 ms) and then opens; double-click and right-click select. Desktop clicks (auto in DeX and on Chromebooks): click focuses, double-click opens, right-click opens a context menu. Touch: tap opens at once; long press and two-finger tap select.
- Thumbnail size: automatic from the window width until the user changes it. Settings saved before this existed have no "automatic" flag, so they start automatic once.
- FFmpeg is in the base APK, so users never have to install anything (+~6 MB for 4 ABIs; release APK ~8.3 MB).
- No Play Store build for now, so no SAF fallback: the app uses "All files access".
- SVG tiles use AndroidSVG (`com.caverock:androidsvg-aar` 1.4, Apache 2.0, ~220 KB of classes); SVGs still open with other apps.
- Text previews: 10 sp at least, growing with the system font size up to 13 sp; whole lines only; code, logs and tables in monospace (§3.10).
- Code font: **JetBrains Mono** is bundled (SIL Open Font License 1.1, licence in `assets/licenses/`), in its no-ligature edition (*JetBrains Mono NL*: code reads character by character), regular, bold and italic, subset to Latin, Cyrillic, Greek, punctuation, arrows and box drawing, without hinting: ~250 KB, ~130 KB compressed in the APK. Chosen over Fira Code, Source Code Pro and Cascadia Code for its clear `0/O`, `1/l/I`, tall x-height at small sizes and full Cyrillic; Google's downloadable fonts were not used because they need Play services. *Settings → Editor → JetBrains Mono for code* switches back to the system font. The crash screen keeps the system font (it must work whatever failed).
- Legacy charsets: a charset the file declares, then the Cyrillic check, then the *Text files without encoding* setting (§3.10).
- Long videos (default > 3 min) open in an external player when one is installed, otherwise in the slideshow (§3.11).
- Other apps are opened only from the folder view: never from the slideshow, never in selection mode (§3.11).
- Every audio folder plays in book mode; folders with audio and videos play both as one queue (§2, Listening).
- Shuffle is kept per folder and turns it into music mode (its own skip length, no resume rewind, no book bar); repeat exists only there. A tap on an audio file plays its folder in the app, starting at that file (§2, Listening).
- Play order: the folder's `.m3u` / `.m3u8` playlist, otherwise natural file-name order (§3.12).
- Lecture folders: only long videos; a tap on a video still watches it; listening starts from the Play button and never shows the picture (§3.12).
- Text editor: a tap on a text file opens the editor (a setting switches back to other apps). New text file and New folder are in the top bar on wide screens; on phones they share a ⋮ menu with the favourite star and settings. Limits: editable up to 1 MB, read-only up to 10 MB, colours up to 200 K characters (§3.13).
