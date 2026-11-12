[← README](../README.md)

# 1. Features

✅ done · ◐ partly · ❌ not done

### Browsing

| Feature                                                                                                                                               | Status                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- |
| Storage roots: internal storage, SD cards, USB drives, favourite folders, recent folders                                                              | ✅                           |
| USB drives and SD cards appear in and disappear from *Storage* on the home screen as they are mounted and removed                                       | ✅ (untested on a device)    |
| Cameras and phones over USB (MTP / PTP): *Devices* on the home screen, browsing with the device's thumbnails and folder collages, slideshow, delete with *Undo*, copy and move both ways, new folders, live updates, auto-open for cameras (§3.14) | ◐ untested on a device; tested against a fake device |
| Long videos and audio books play straight from a camera or phone (streamed, no copy first); the slideshow knows the whole device folder        | ◐ untested on a device; tested against a fake device |
| Devices remembered by serial number: their folders stay in *Recent* and *Favourites* (dimmed while unplugged), keep their sort and listening history; access is asked again by itself | ◐ untested on a device |
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
| Drop files or folders from other apps into the open folder, onto a folder tile, or onto a home screen folder or volume; `Ctrl` + V pastes copied files | ✅ (§2 Desktops)             |
| Own tiles dropped with `Shift` held (in one window or between two) are moved, with *Undo*                                                             | ✅ (untested on a device)    |

### Text editor

| Feature                                                                                                                             | Status |
| ----------------------------------------------------------------------------------------------------------------------------------- | ------ |
| Tap a text or code file to edit it (extensionless and unknown files too, if they turn out to be text); **Open with…** for other apps | ✅     |
| Syntax colours chosen from the file name: about 40 languages and formats in 11 families, light and dark                             | ✅     |
| Keeps the file's charset (UTF-8/16, Windows-1251, KOI8-R…), BOM and line endings; *Save as UTF-8* when needed                       | ✅     |
| Safe save (temporary file, then rename); notices files changed by other apps                                                        | ✅     |
| Undo / redo, unsaved-changes question, unsaved text kept if the app is killed                                                       | ✅     |
| Text size (slider, pinch, `Ctrl` + wheel, `Ctrl` + `+` / `-`) and line wrap (on by default, also for read-only files), kept between runs | ✅     |
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
| Previous and Next go on where an item was left, video and GIF included (last 15 items)                    | ✅     |
| Previous / Next keep the item on screen until the next one is ready (small spinner after ~300 ms); a second press skips further, the opposite one cancels | ✅ (untested on a device) |
| Random order without repeats, stable across restarts and folder changes                                    | ✅     |
| Menu: file name (wrapped, cut after 256 characters), interval, random order with cycle progress, pause, mute, file info, share, delete with undo, back | ✅     |
| A folder with one media item: it stays on screen without the R limit, and a video loops                  | ✅     |
| Pause indicator; position (`12 / 29,102`) shown for a moment after each manual step                        | ✅     |
| Next and previous items preloaded (a video in a second player); fade / slide / no transition               | ✅     |
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
