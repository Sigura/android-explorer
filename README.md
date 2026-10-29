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
| Cameras and phones over USB (MTP / PTP): *Devices* on the home screen, browsing with the device's thumbnails, slideshow, *Copy to…* (§3.14; read-only) | ◐ untested on a device       |
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
| Animated WebP / AVIF strips in tiles                                                                                                                  | ❌ first frame only (see §6) |
| Icons for other files (text, code, document, PDF, audio, archive, APK, unknown), partial downloads and broken files                                   | ✅                           |
| Text previews: first lines of text, code, subtitles and extensionless text files; UTF-8/16 and Cyrillic code pages                                    | ✅                           |
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
| Full player screen, speed, sleep timer, shuffle, chapters, lyrics                                                                                     | ❌ planned (todo.md §3–4)    |
| Desktop clicks (auto in DeX and on Chromebooks): click focuses, double-click opens, right-click opens a context menu, `Ctrl` / `Shift` + click select | ✅                           |
| Drag files out to other apps; `Ctrl` + C copies files to the clipboard; Delete deletes                                                                | ✅                           |
| Drop files or folders into the app                                                                                                                    | ❌                           |

### Text editor

| Feature                                                                                                                             | Status |
| ----------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Tap a text or code file to edit it (extensionless and unknown files too, if they turn out to be text); **Open with…** for other apps | ✅     |
| Syntax colours chosen from the file name: about 40 languages and formats in 11 families, light and dark                             | ✅     |
| Keeps the file's charset (UTF-8/16, Windows-1251, KOI8-R…), BOM and line endings; *Save as UTF-8* when needed                       | ✅     |
| Safe save (temporary file, then rename); notices files changed by other apps                                                        | ✅     |
| Undo / redo, unsaved-changes question, unsaved text kept if the app is killed                                                       | ✅     |
| Text size (slider, pinch, `Ctrl` + wheel, `Ctrl` + `+` / `-`) and line wrap, kept between runs                                      | ✅     |
| Files of 1–10 MB open read-only; larger ones open with other apps                                                                   | ✅     |
| Find, rename from the editor                                                                                                        | ❌     |

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
- **Move / Copy to…** opens a folder picker (current folder, volumes, favourites, recent). Moves within a volume are instant renames. Copies show progress and can be stopped; a half-copied file is removed. Existing files are never overwritten: the copy gets a `name (1).ext` name.

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
| Back to the folder      | ←, Back                     | Esc                                           |

- Leaving with unsaved changes asks *Save / Discard / Cancel*. If another app changed the file meanwhile, saving asks *Overwrite / Reload / Cancel*. If a character doesn't exist in the file's charset (an emoji in a Windows-1251 file), saving offers UTF-8.
- If the app is stopped with unsaved changes, they are kept; the next time the file is opened they come back, with *Discard* in a snackbar.
- Back returns to the folder, scrolled to the file.

### Listening: books, albums and lectures

The **▶ / 🎧 button** in the folder's top bar (or `P`) plays the folder:

- **Audio folder** (audio files, plus covers, subtitles and playlists), **mixed folder** (audio and video files: one queue) and **lecture folder** (two or more videos, every one longer than the long-video setting, plus `yt-dlp`-style thumbnails, subtitles and `.info.json`): 🎧 **Listen** starts the book, or continues where it stopped.
- Any other folder with media: ▶ starts the slideshow from the first item.
- Tapping a single video still watches it. The player never shows video: lectures are listened to.

Playback continues in the background with the screen off. The **player** at the bottom of the root and folder screens shows, top to bottom: the book bar (one segment per file, as wide as the file is long, played ones filled; a tap starts that file), the title (without the extension) over the folder, `3 / 24` and where we are in the whole book (`2:13:05 / 9:41:00 · 7:28:10 left`, the time left at the playback speed, `≈` until every file's duration has been read in the background), a seek bar with the elapsed and remaining time (dragging previews the target and seeks on release), then the speed (0.75–2×, kept per book), ⏮ previous file, −30 s, play / pause (a ring while the file loads), +30 s, ⏭ next file and the sleep timer (15 / 30 / 60 min or at the end of this file). ✕ stops while paused. A tap on the title opens the folder playing. ⌄ or a swipe down collapses it to a one-row bar (title, position, −30 s, play / pause, +30 s); ⌃ or a swipe up expands it again; the choice is kept. ⏮ goes to the previous file within the first 3 s of one, later to the start of the current file; ⏭ is disabled on the last file. When the book's folder is open, the playing file's tile carries a *playing* mark, and it takes the focus (scrolled into view) when playback starts from the folder and on every change of file. The lock screen, the notification, Bluetooth and headset buttons control it too, and a headset Play after the app was killed continues the last book.

Order: the folder's `.m3u` / `.m3u8` playlist if there is one (the one named like the folder, otherwise the first), then files it doesn't list; without a playlist, natural file-name order (`2` before `10`), whatever the folder view's sort.

Resume: the current file and position are saved; resuming after a pause goes back 5 s (pause over 1 min) or 20 s (over 1 h). A finished book starts over.

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
| Scrub a preview                                                     | Hover (as with gallery clicks)                                                                                      |

In the slideshow, the wheel steps through items (at most one step per 250 ms, so a trackpad fling doesn't skip a dozen), and a video keeps playing while another window has focus. If the slideshow runs in a window, a one-time hint says to maximise it: an app can't maximise its own window.

Icon buttons show a tooltip on hover (also on long press), and tiles, breadcrumbs and buttons show a hand cursor.

Other apps are opened only from the folder view: never from the slideshow, and never in selection mode. A long video reached inside a running slideshow plays there.

### Settings

Slideshow: interval, transition, invert swipe direction, random order, video muted by default, restore on launch (off / folder only / folder and slideshow).
Folder view: automatic thumbnail size or a fixed size, image tile mode (smart / crop / fit) and smart-crop threshold (0–30 %), large-GIF threshold, long videos open externally (off / 1–30 min), text files without encoding (auto by device language / a fixed code page), default sort (for folders with images and without their own sort), descending, folders first, show hidden files, mouse clicks (auto / gallery / desktop), always show file names.
Editor: open text files in the app, text size (10–28 sp), wrap lines.
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
                 Highlight (syntax colours), EditHistory (undo)   (pure Kotlin)
  data/fs/       FileTypes, FolderScanner, FolderListing, Volumes, Trash, FileOps,
                 TextFile (editor load / save / create), Drafts (unsaved editor text)
  data/thumbs/   Thumbnails (scheduler, LRU, watchdogs), GifCodec (scanner + decoder),
                 TextDecode (text sniffing, charsets; pure Kotlin), TextPreview (text tiles)
  data/meta/     MetaStore (SQLite metadata cache)
  data/history/  HistoryStore (history.db: listening positions), BookFolder (queue + resume point)
  playback/      PlaybackService (MediaSessionService), BookPlayer (MediaController for the UI)
  data/settings/ SettingsStore (DataStore)
  data/          ErrorReporter, Blocking (abandonable calls)
  ui/            AppViewModel (navigation, busy state, delete / move), PermissionScreen,
                 Desktop (DeX / Chromebook detection), MouseBack (mouse back button, right-click guard)
  ui/root/       RootScreen
  ui/browser/    FolderScreen, Tiles, TileInput (gestures), TilePlayback (selected tiles play), BrowserState (selection), FolderPicker,
                 NewItemDialog (new text file / folder)
  ui/editor/     EditorScreen, EditorState (text, undo, saving)
  ui/slideshow/  SlideshowScreen, SlideshowController, SlideGestures, SlideshowMenu
  ui/settings/   SettingsScreen, ErrorLogScreen
  ui/playback/   MiniPlayer
  ui/common/     Common (share, copy), ExternalApps (open with other apps, external player lookup)
tools/check-media-folder.sh             runs the app code over a real folder
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
  - Font: monospace for code, logs, CSV, JSON and subtitles; proportional (soft-wrapped) for `txt` / `md`, with bold Markdown headings.
  - Size: 10 sp, growing with the system font size up to 13 sp; 12 sp on TV. Line height 1.2×, 6 dp padding.
  - Whole lines only. The tile size sets the number of lines, never the font size; below 3 lines the icon is shown. Leading blank lines are dropped, tabs expand to 4 columns, control characters are removed.

### 3.11 Opening files with other apps

- **Other files** open with a plain `ACTION_VIEW` (not a chooser), so the system's *Always* choice works. The MIME type comes from `FileTypes` (PDF, Office, EPUB, audio, archives, APK…), then `MimeTypeMap`. If no app handles that type, a chooser for `*/*` opens; if that fails too, a snackbar. **Open with…** in the context menu always shows the chooser.
- **Long videos.** A tap on a video longer than the setting (default 3 min; 0 = off) opens the external player when one is installed; otherwise the slideshow. The duration comes from `MetaStore`; on the first tap in a new folder it is read with `MediaMetadataRetriever` under the watchdog, on IO. Tiles of such videos show ↗ bottom-left. `Shift` + Enter and the context menu's *Play in slideshow* keep them in the app; a long video reached inside a running slideshow plays there.
- **Player lookup:** `queryIntentActivities(ACTION_VIEW, video/*)` on IO, again on every resume (manifest `PACKAGE_ADDED` receivers don't fire on Android 8+). Android 11+ needs the `<queries>` entry in the manifest.
- **Resume position:** VLC reads the extra `"position"` as a `Long` (ms), MX Player as an `Int` — the same key with different types. The app resolves the default handler first and sets the type per package; the chooser uses `EXTRA_REPLACEMENT_EXTRAS` for MX Player. `"title"` is passed as well.
- **Where:** other apps are opened only from the folder view — never from the slideshow, and never in selection mode.

### 3.12 Listening (books, albums, lectures)

- **Service:** `PlaybackService`, a Media3 `MediaSessionService` (foreground type `mediaPlayback`), owns one ExoPlayer with the FFmpeg audio fallback. The video track is disabled with `TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`, so videos are decoded as audio only. Audio focus (`USAGE_MEDIA`, speech), `setHandleAudioBecomingNoisy` (pause on unplugged headphones) and `WAKE_MODE_LOCAL`. The system media notification provides lock-screen, Bluetooth, headset, watch and car controls.
- **UI:** `BookPlayer` holds one `MediaController` for the Play button and the mini player; the mini player polls the position twice a second only while playing. Controllers send media items without their URI; the session rebuilds it from the media ID, which is the file path.
- **Folder kind** (`Book.classify`, pure Kotlin): computed on IO once the listing is complete. Lecture folders need every video's duration: from `MetaStore`, or `MediaMetadataRetriever` under the thumbnail watchdog; the first short video ends the check. Folders with more than 300 videos are not checked.
- **Queue** (`BookFolder.queue`, on IO): playable files (audio + video) of the folder, in playlist order (`Playlist.parse`: comments, relative and absolute paths, `..`, Windows paths matched by file name, URLs skipped; `.m3u` decoded like text previews, `.m3u8` as UTF-8), then unlisted files in natural order.
- **History** (`HistoryStore`, `history.db`): `audio_folder` (current file, position, speed, finished, last played) and `audio_file` (position, finished). Separate from `MetaStore`, which is a disposable cache; it is never pruned and migrates instead of dropping tables. Saved every 5 s while playing, and at once on pause, file change, seek, task removal and service stop; writes run on IO in the app scope. A file is finished within 2 % or 30 s of its end.
- **Smart rewind** (`Book.rewindMs`): 0 s after a pause under 1 min, 5 s under 1 h, 20 s after longer — on resume within the service and when a book is started again.
- **Resumption:** `MediaSession.Callback.onPlaybackResumption` rebuilds the last book's queue on IO; `MediaButtonReceiver` in the manifest lets a headset Play start it.
- **Idle:** paused for 30 min, or the book ended → the service stops and the notification goes. Swiping the app away while paused stops it too.
- **Slideshow:** with sound on, its video player takes audio focus, so a playing book pauses.

### 3.13 Text editor

- **Screen:** `Screen.Editor` in `AppViewModel`, over the folder's listing (which keeps its live updates). `EditorState` holds the text, undo history, colours and the save logic; the view model keeps it, so unsaved text survives the activity being recreated. Back returns to the folder with the file as the focus target.
- **Opening** (`TextFile.load`, on IO under a 20 s watchdog, with the wait indicator): over 10 MB → other apps; the first 8 KB are checked for binary data before the whole file is read; then `TextDecode.decodeFull` (the preview rules of §3.10, over the whole file) gives the text, the charset and the BOM. CRLF is detected and turned into `\n` while editing. Over 1 MB, or not writable → read-only, shown line by line in a `LazyColumn`.
- **Text field:** Compose 1.8's state-based `BasicTextField` can't style text yet, so the editor uses the `TextFieldValue` one with a `VisualTransformation` that adds the colour spans (offsets map 1:1). Its own undo works only from hardware keys, so `EditHistory` (pure Kotlin) keeps edits as diffs (common prefix and suffix) and groups typing or deleting within 1 s; the toolbar and `Ctrl` + Z use it.
- **Colours** (`Highlight`, pure Kotlin): the language comes from the file name. The C-like, script and config languages share one table-driven lexer (keywords, comments, strings, numbers, annotations, `$variables`, `#` preprocessor lines); markup, JSON, CSS, YAML, INI/TOML, Markdown, subtitles, logs (including logcat), diff and CSV have small lexers of their own. Lexing runs on the Default dispatcher 150 ms after typing stops; in between, each edit moves the existing spans (`Spans.shifted`), so colours stay on their words. Texts over 200 K characters are not coloured.
- **Saving** (`TextFile.save`, on IO under the watchdog): size and modification time are compared with those at opening (changed → ask). The text is encoded with the file's charset, BOM and line endings, strictly (an unmappable character → ask for UTF-8), written to `.name.tmp` in the same folder, `fsync`ed, and renamed over the file; if the rename fails, the file is written in place. The tile preview is dropped from the cache (`Thumbnails.forget`). `FolderListing` treats a file renamed over an existing one like a finished write, so the entry's size and date are refreshed.
- **Drafts** (`Drafts`): on `ON_STOP`, unsaved text goes to `files/drafts/` as one JSON file per path; it is removed on save or discard, and offered again when the file is opened.
- **New files** (`TextFile.create`): `createNewFile`, which never overwrites. Names are trimmed (spaces, trailing dots) and checked for `/`, NUL and 255 UTF-8 bytes.

### 3.14 MTP devices (cameras and phones over USB)

Spec and open work: [todo §9](todo.md#9-mtp-devices-cameras-and-phones-over-usb). Read-only for now; untested on a device.

- **Finding devices** (`data/mtp/MtpDevices`, one per app): `UsbManager` devices with a still-image interface (PTP) or a vendor interface named `MTP`; a receiver follows plugging, unplugging and the answer to the system permission prompt. A device with permission is opened at once (`MtpDevice.open`); its storages become `Volume`s with `device = true` and `mtp://<device id>/<storage id>` paths (`MtpPaths`). The home screen shows them under **Devices**; a device that isn't ready shows why, and a tap asks again.
- **Calls** (`MtpConnection`): every call to a device runs on that device's own thread, under a watchdog; after a timeout the device counts as *not responding* and later calls fail at once. Calls are short (file details 16 at a time, 1 MB chunks), so tiles get their thumbnails while a download runs.
- **Folders:** `FolderListing` takes the device scanner instead of `FolderScanner` and doesn't watch the folder. Entries have `mtp://` paths; types come from the extension or the MTP object format. `Thumbnails.load` uses the device's own thumbnail (`getThumbnail`) for media; folders and other files keep their icons. `BrowserState.remote` turns off delete, move, share, copy to clipboard, new files, favourites and editing.
- **Slideshow:** `AppViewModel.openDeviceSlideshow` downloads the file into `cache/mtp/…` (a `.part` file renamed when complete), parks the device folder's listing, and opens the slideshow over a normal listing of the mirror folder. A background job downloads the folder's other media in order, up to 2 GB in the mirror; the mirror's folder watcher adds each one to the slideshow. Closing it brings back the parked listing, focused on the device path of the last item. The mirror is deleted at start and when a device is unplugged; slideshow sessions in it are never resumed.
- **Copy to…** (`MtpDevices.importTo`): files and folders (recursively) into a local folder, with `FileOps.freeName` for clashes. **Open with** downloads first and passes the local copy.
- **Unplugging:** the devices flow drops the device; a folder or slideshow from it closes with *"Device disconnected"*.

---

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

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

The tests cover smart crop (the examples above), slideshow timing (the table above), natural sort and folder order, the random order (no repeats, restore after restart, adds and deletes), file-type detection and the GIF codec on synthetic files. `EditorTest` covers the syntax colouring of every family, span shifting and undo; `TextFileTest` covers charset, BOM and CRLF round trips, *Save as UTF-8*, changed-on-disk detection, names and drafts.

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
- **Animated WebP / AVIF strips.** Android offers no way to seek inside these animations (`AnimatedImageDrawable` only plays), so tiles show the first frame. The slideshow animates them, for R seconds, looped.
- **APNG** is shown as a still everywhere: the platform decoders only read its first frame.
- **FFmpeg video decoding.** The bundled FFmpeg extension decodes *audio*; its video renderer is still experimental in Media3, so video codecs depend on the device's decoders. Rare AVI video codecs (MJPEG, old DivX on some devices) may not play, and their thumbnails show the broken-file icon. Everything in the reference folder is H.264 + AAC.
- **MTP devices (§3.14)** are read-only and untested on a device: delete, copy to the device, live updates, streaming without the mirror and folder collages are open (todo §9.8). Android's Files app may hold a device so that this app can't open it (**verify**).
- **Storage Access Framework fallback**: skipped on purpose. It needs a second file layer and a separate Play Store build flavor, and it isn't needed for direct installs.
- **Generated Baseline Profile.** The bundled profile is a broad hand-written rule for the app's own code; a profile recorded with Macrobenchmark on a device would be more precise.
- **Text previews and opening with other apps (§3.10, §3.11)** are untested on a device: the external players' `"position"` extras, the Cyrillic charset check on real files, and the text rendering need a check.
- **The text editor (§3.13)** is untested on a device: typing speed in a 1 MB file and colouring speed near the 200 K limit on a low-end phone, the on-screen keyboard over the text, pinch next to text selection, `Tab` and `Esc` with DeX keyboards, and the safe save (rename over the file) on SD cards and USB drives (FAT / exFAT through FUSE).
- **Listening (§3.12)** is untested on a device: background playback with the screen off, Samsung / Xiaomi battery savers, video files played as audio (battery use), headset Play after the app was killed, and the system media controls offering the last book after a reboot (`onPlaybackResumption` without a `MediaLibraryService` may not be enough for that).
- **Lint:** 18 older errors remain (Media3 `@UnstableApi` opt-ins in the slideshow and tile playback, `dispatchKeyEvent` in `MainActivity`); the release build doesn't run lint.
- **Legacy charsets other than Cyrillic** (Greek, Central European…) are not detected: they use the *Text files without encoding* setting. juniversalchardet (~200 KB) would add detection if needed.
- **On-device tests.** No emulator or device was available, so the Android-only paths (still and video thumbnails, playback, the UI) are untested on a device. This includes everything in §3.9: the DeX launch size, the right-click guard and the caption-bar inset need a check on a DeX device.
- **Dropping files into the app** (from My Files in DeX, for example). Dragging out works; a drop target would need to copy or move the dropped content URIs into the open folder.

**Decisions taken** (formerly open questions)
- Mouse, gallery clicks (phones, tablets, TV): a click waits for a possible second click (~300 ms) and then opens; double-click and right-click select. Desktop clicks (auto in DeX and on Chromebooks): click focuses, double-click opens, right-click opens a context menu. Touch: tap opens at once; long press and two-finger tap select.
- Thumbnail size: automatic from the window width until the user changes it. Settings saved before this existed have no "automatic" flag, so they start automatic once.
- FFmpeg is in the base APK, so users never have to install anything (+~6 MB for 4 ABIs; release APK ~8.3 MB).
- No Play Store build for now, so no SAF fallback: the app uses "All files access".
- Text previews: 10 sp at least, growing with the system font size up to 13 sp; whole lines only; code, logs and tables in monospace (§3.10).
- Legacy charsets: a charset the file declares, then the Cyrillic check, then the *Text files without encoding* setting (§3.10).
- Long videos (default > 3 min) open in an external player when one is installed, otherwise in the slideshow (§3.11).
- Other apps are opened only from the folder view: never from the slideshow, never in selection mode (§3.11).
- Every audio folder plays in book mode; folders with audio and videos play both as one queue (§2, Listening).
- Play order: the folder's `.m3u` / `.m3u8` playlist, otherwise natural file-name order (§3.12).
- Lecture folders: only long videos; a tap on a video still watches it; listening starts from the Play button and never shows the picture (§3.12).
- Text editor: a tap on a text file opens the editor (a setting switches back to other apps). New text file and New folder are in the top bar on wide screens; on phones they share a ⋮ menu with the favourite star and settings. Limits: editable up to 1 MB, read-only up to 10 MB, colours up to 200 K characters (§3.13).
