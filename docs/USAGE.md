[← README](../README.md)

# 2. Using the app

## Root screen

Lists connected **Devices** (cameras and phones over USB, only while one is plugged in; §3.14), **Recent** folders (large tiles with previews; long press removes one; how many is a setting), **Favourites** (star a folder in its top bar; long press removes) and **Storage** volumes with free space. There is no back tile. Back exits after "Press again to exit".

Folders on a camera or phone that has a serial number are remembered like any other: they appear in *Recent* and can be starred, and show the device's name. While it is unplugged they are dimmed, and a tap says to plug it in. Plugged in again while the app is open, the device asks for access by itself (Android asks once per plug), and its folders open as before.

On launch the app opens the first recent folder that is still available, at its saved scroll position. If the app was stopped during a slideshow, it goes straight back into it, with a 3 s *"Resumed slideshow — tap to go to folder"* message. This is configurable (§2.5).

## Folder view

A full-screen grid with a thin top bar: home, clickable breadcrumbs (the storage root is an icon, so long volume names never push the path off screen), thumbnail size, sort, ▶, `+` (new text file or folder), favourite star and settings. The bar hides when scrolling down. On narrow screens (< 600 dp) the size slider opens from a grid button, and sort, `+`, the star and settings share one ⋮ menu, so the breadcrumbs keep their room.

**Order.** Back tile, folders, media, other files; names always compare in natural order (`a1`, `a2`, `a10`), also as the tie-break for equal dates. A folder **without images or GIFs** (audio, documents, lectures, folders of folders) is sorted by **name, A to Z**; every other folder uses the sort from settings (default: date, newest first). A folder starts in name order while it is listed and switches once, as soon as the first image turns up, so a folder without images never reorders. The **sort menu** (⇅ in the top bar, ⋮ → *Sort…* on phones, or `S`) sets the folder's own order: **Name** or **Date modified**, **ascending or descending** (*A to Z* / *Z to A*, *Oldest first* / *Newest first*). It is kept for that folder between runs (up to 2,000 folders, oldest dropped first); *Default order* forgets it. *Folders first* stays a global setting. The slideshow and the fast-scroll bubble follow the folder's order.

**Favourite files.** *Add to favourites* in a file's right-click menu, or ☆ in the selection bar for the selected files, keeps them at the top of their folder, right after its folders (first of all with *Folders first* off), in the folder's order among themselves; a small ★ marks their tiles. The same button or menu item removes them again (when every chosen file is a favourite already). Files on a camera or phone can be favourites when the device is remembered by its serial number (§3.14). The slideshow shows favourites first too (see *Random order*).

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

Selection mode also ends once an action on the selection runs: favourites, share, move / copy or delete.

Focused tiles are scaled up with an outline, for keyboard and TV use. With gallery clicks (the default outside DeX and Chromebooks), a mouse click waits for the double-click timeout (~300 ms) before opening, because a double-click enters selection mode; right-click selects at once. Touch taps open at once, and a two-finger tap (both fingers down and up quickly, without moving) selects the tile under the first finger. It never opens anything, even when the fingers land on two different tiles.

**Tiles**

- **Folder:** up to 4 previews like photos on a table, picked without decoding anything: mid-size stills (50 KB–3 MB) first, then GIFs (the first frame is cheap whatever the file size), then videos. At most 2 levels deep, 20 candidates. Further groups of 4 cycle on their own every ~3 s while the grid is idle (staggered, so the tiles don't all change at once); hover or `Shift` + arrows pick a group by hand. A sideways swipe on a folder tile does nothing, so it never competes with the system Back gesture at the screen edge. A folder without media shows its item count.
- **Image:** shown with **smart crop**. If cropping to the tile would cut off at most 10 % of the image, it fills the tile; otherwise the whole image is fit, centered. For a 4:3 tile: a 1.4:1 image is cropped (5 % lost), a 3:2 photo is fit (11 %), a square or 16:9 image is fit (25 %). The mode (smart / crop / fit) and the threshold are settings.
- **GIF / video:** 5 frames at 10, 30, 50, 70 and 90 % (no black first frames), shown one at a time with the same smart crop. The hover position or a swipe picks a frame; resting the pointer or focusing the tile cycles them (~400 ms per frame). The badge shows `GIF` or the duration. GIFs above the large-GIF threshold (default 40 MB) show only their first frame until hovered or swiped; then a small ring shows while the strip is made. While the folder is still being listed, every GIF and video tile shows only its first frame (no strip, scrubbing or preview); the strips are made once the listing is complete. In selection mode, selected GIF and video tiles play the whole animation or video in the tile, looped and muted, whatever R is; at most 4 play at once (hardware video decoders are few), the others keep their frames until one is deselected or scrolls away.
- **Other files:** an icon for the type, with name and size. Partial downloads (`.crdownload`, `.part`) get a download icon and are never decoded. Broken files get a broken-image icon, and files whose decode timed out get an hourglass. Both can still be selected, deleted and shared.

Names of media tiles show on focus, hover and in selection mode, or always with the "Always show file names" setting. A media tile that has no thumbnail (broken-image or hourglass icon) always shows its name, so the file can still be told apart. Names are never truncated: a file or folder name wider than its tile scrolls sideways in a continuous loop, like a news ticker (1 s still, scroll, 1.5 s pause, again), so the whole name can be read; names that fit stay still.

**Selection mode** shows a bar with the count, **Select all**, ☆ / ★ favourites and ⋮ with **Invert selection**, **Share**, **Move to…**, **Copy to…** and **Delete**, so it fits the narrowest phone. Dragging after a long press selects a range, and the grid scrolls when the finger nears the top or bottom. Selection ends when nothing is selected, on Back, or when you change folder.

- **Delete** asks nothing: an *Undo* snackbar stays for 5 s, and only after that is the file really removed (§3.6).
- **Move / Copy to…** opens a folder picker (current folder, volumes, connected devices, favourites, recent). Moves within a volume are instant renames. Copies show progress and can be stopped; a half-copied file is removed. Existing files are never overwritten: the copy gets a `name (1).ext` name.
- **On a device folder** (camera or phone over USB, §3.14) the same bar has no **Share**; delete, move and copy work in both directions, and the top bar's `+` makes a new folder there (no text files).

## Text editor

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
| Wrap lines on / off     | ⋮ (also in read-only files), Settings | —                                   |
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
- **Wrap lines** is on by default for every file. Off, long lines scroll sideways; in read-only files (1–10 MB) the whole page scrolls sideways together, and lines longer than 2,000 characters are cut there.
- **Line numbers** (⋮ or Settings, off by default) show in a gutter, also in read-only files; a wrapped line has one number. **Tabs and trailing spaces** (off by default) are drawn as `→` and `·`.

## Listening: books, albums and lectures

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

## Slideshow

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

Nothing is on screen for less than ⅔ R. A thin bar at the bottom shows how much of the item's time has passed; it stops while the slideshow is paused or the menu is open. Pause stops only the item's time, as the open menu does: a video or GIF keeps playing and looping, also a video that otherwise plays once, which goes on to the next item when it next ends after Play. **Previous** and **Next** go on where the item was left (the video or GIF position, for the last 15 items), so Back and then Next returns to the same moment, and the item gets its full time again (a video that would play once loops from its start if it reaches its end before that); a video or GIF left with less than 2 s of it to go starts over, and the item's time running out doesn't count. GIFs play with the app's own decoder, which can start from a position and stop on a frame; GIFs above the large-GIF size, and files that aren't GIFs inside, use the platform decoder and start from their first frame.

A GIF's duration comes from a block scan, not from decoding. Delays of 0–1 cs count as 100 ms, as in browsers. Video sound is muted by default.

Changing R applies at once, also to the item on screen. The next and previous items are loaded ahead. Transitions: fade (default), slide or none.

**Stepping yourself** (tap zones, swipes, keys, wheel, remote): the item on screen stays, still playing, until the one you go to is loaded; then it changes with the transition. If that takes more than ~300 ms a small spinner shows in the bottom corner. Pressing the same way again while it waits moves on one more item (a held remote arrow skips ahead); the other way cancels and the item on screen stays. An item that can't be read is skipped with the usual message and the next one that way is tried. After 30 s the slide changes anyway and the wait indicator with Stop appears; Back and Esc leave the slideshow as always.

An item that can't be read is skipped. An item reached automatically that isn't ready within min(R, 3 s) is also skipped. If nothing in the folder can be shown, the app returns to the folder view with a message.

**Menu** (bottom sheet; while it is open the item timer stops, so the slideshow doesn't go on to the next item, but a video or GIF keeps playing and looping, also a video that otherwise plays once, which goes on to the next item when it next ends after the menu closes; the menu stays open until closed: by a tap anywhere outside it, also in the center of the image, Back, Esc, the star, delete, back to the folder, play / pause or share): the file name, cycle progress (e.g. `1,284 / 29,102`), a ☆ / ★ favourite toggle, the interval slider, **Random order**, then delete (with undo), back to the folder, play / pause, file info (path, resolution, size, date, duration, frames), share and mute.

**Random order** shows every file once per cycle, in a pseudo-random order, then starts a new cycle that never begins with the item just shown. Going back walks through the items as they were shown. Files added during a cycle are put somewhere in the not-yet-shown part; deleted ones are skipped. Turning random order on starts a cycle from the current item; turning it off continues in name order from it. Favourite files come first in every cycle, shuffled among themselves, then the rest (after the item a cycle was started from).

## Desktops: Samsung DeX and Chromebooks

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
| Bring files in from another app                                     | Drag them from My Files, a browser or mail onto the folder grid (it gets an outline): they are copied into the open folder, as with *Copy to…*; folders too when the other app shares them as a folder. Onto a folder tile, or onto a recent folder, favourite or volume on the home screen: into that folder |
| Move files between folders or windows of this app                   | Drag tiles onto a folder tile, another window's grid or a home screen folder: they are copied; hold `Shift` while dropping to move them (*Undo* in the message). Device folders take no drops |
| Scrub a preview                                                     | Hover (as with gallery clicks)                                                                                      |

In the slideshow, the wheel steps through items (at most one step per 250 ms, so a trackpad fling doesn't skip a dozen), and a video keeps playing while another window has focus. If the slideshow runs in a window, a one-time hint says to maximise it: an app can't maximise its own window.

Icon buttons show a tooltip on hover (also on long press), and tiles, breadcrumbs and buttons show a hand cursor.

Other apps are opened only from the folder view: never from the slideshow, and never in selection mode. A long video reached inside a running slideshow plays there.

## Settings

Slideshow: interval, transition, invert swipe direction, random order, video muted by default, restore on launch (off / folder only / folder and slideshow).
Folder view: automatic thumbnail size or a fixed size, image tile mode (smart / crop / fit) and smart-crop threshold (0–30 %), large-GIF threshold, long videos open externally (off / 1–30 min), text files without encoding (auto by device language / a fixed code page), default sort (for folders with images and without their own sort), descending, folders first, show hidden files, mouse clicks (auto / gallery / desktop), always show file names.
Editor: open text files in the app, text size (10–28 sp), wrap lines, JetBrains Mono for code, colour theme, line numbers, tabs and trailing spaces.
Home screen: number of recent folders shown (1–50).
Appearance: theme (system / light / dark / AMOLED black).
Maintenance: clear thumbnail cache, error log, favourite folders.
