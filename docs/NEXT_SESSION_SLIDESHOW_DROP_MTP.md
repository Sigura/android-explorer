# Prompt for a session: slideshow Back / Next, editor wrap, drop moves, MTP third step

Copy everything below the line into a new session on `Sigura/android-explorer`.

---

Build the items below for the Media Viewer Android app (Kotlin, Compose). Read `todo.md` first (§5 *Things easy to forget* for context, §6, §9.8, §10.5, §10.10), README §2 *Slideshow*, §2 *Text editor*, §3.9, §3.13, §3.14, and `docs/RESEARCH_2026-10.md` §5. Start with a short plan added to `todo.md` as a new §11 (one subsection per item below), commit it, then build in the order given; commit after each item.

**Base.** Start from `master`. It must contain `domain/EditorTheme.kt` and `domain/Brackets.kt` (the editor themes work of 2026-10-29). If it doesn't, stop and ask the owner to merge `editor-themes.bundle` / `editor-themes.diff` first: item 2 edits the same `EditorScreen.kt`. If the session names its own branch, create it from `master`.

**Delivery.** Do **not** push and do **not** open a pull request. At the end, leave in the working directory a `git bundle` of the new commits (`origin/master..HEAD`) and one diff of the whole branch (`git diff origin/master..HEAD`), and check that the bundle fetches into a clean clone of `master`. Keep both out of git (`.git/info/exclude`).

**Checking.** No Android SDK in the build container (`dl.google.com` is blocked). `tools/pure-tests.sh` runs the pure-Kotlin tests (it compiles `domain/*.kt` and the listed `data/` files; add any new pure file and test outside `domain/` to it) and `tools/typecheck/run.sh` type-checks the whole app without the SDK (docs/RESEARCH_2026-10.md §0). Both must pass before each commit. Put the logic in pure Kotlin where it can be tested (`domain/`, or a small pure class next to the Android code, like `data/mtp/MtpConnection.kt` against `FakeMtpDriver`). Mark device-only assumptions **verify**. Move what is finished to README §1 / §2 / §3 and out of `todo.md`. Don't read binary files (fonts, images, jars).

## 1. Slideshow Back / Next: no blank switch, and Next goes on where it was left

Code: `ui/slideshow/SlideshowController.kt` (`next`, `previous`, `show`, `visits`, `preloadNext`, `advanceFrom`, `armNotReady`), `ui/slideshow/SlideshowScreen.kt`, `domain/SlideOrder.kt`.

Today only the automatic advance waits for the next item to be preloaded (`advanceFrom`, up to `MAX_PRELOAD_WAIT_MS`); a manual Next / Previous (tap zones, swipes, keys, wheel, remote) switches at once, and after ~300 ms shows the wait indicator over an empty slide. A position is restored only when going **back** (`visits`, `dir < 0`).

- **Don't change the item before it is ready.** A manual Next or Previous keeps the current item on screen (still playing) until the target is decoded and cached (`preloadImage`, GIF meta / first frame; for a video: the player prepared and the first frame rendered, **verify** how far the current `SlideshowScreen` video path allows that), then switches with the usual transition. Preload the **previous** item too (not only `peekNext`), so Back is instant.
  - While waiting: a small, non-blocking progress mark (not the full wait overlay) after ~300 ms; a second press of the same direction while waiting moves the *target* on (skips ahead), the opposite direction cancels the wait. Keep Stop (Back / Esc) working as today for very slow files.
  - An item that fails to load is skipped with the existing error path, never left as a black slide; the 3 s *not ready* rule of automatic advance (README §2.1) stays.
  - Large or slow files (network-backed FUSE, MTP mirror, SD cards): a cap on the wait (reuse `MAX_PRELOAD_WAIT_MS`) after which it switches anyway and shows the current wait indicator.
- **Next after Back goes on where the item was left.** Back then Next returns to the item that was on screen at the same video / GIF position and the same elapsed slide time — the same `visits` rule as Previous today, in both directions (the "shown to its end, or nearly → start over" rule of `MIN_LEFT_MS` stays). Also when Back was pressed several times, and within the last `MAX_VISITS` items.
- Pure, tested part: the decision of what to show and when (target index, pending target while waiting, restored elapsed time / position by direction) in a small state machine in `domain/` (e.g. `SlideNav`), with tests in a new `SlideNavTest` (Back then Next restores, several Backs, the near-end rule, double press while waiting, opposite press cancels, failure skips).
- **Verify:** the switch on a 4K HEIC and a 300 MB video from an SD card, rapid tapping, the TV remote's held arrow, DeX wheel scrolling.

## 2. Text editor (§6): word wrap by default, everywhere

- New files and every file open with lines wrapped (`editorWrap`, default `true`). Check why it may appear off for the owner: a stored `false` from an earlier version, or the read-only path. If needed, migrate once (a new key, e.g. `editor_wrap_v2`, default `true`) so existing installs start wrapped; the ⋮ *Wrap lines* toggle and Settings keep working.
- **Read-only files** (1–10 MB) follow the same setting instead of always wrapping: off = a horizontal scroll for the whole `LazyColumn` (one shared `ScrollState`, width of the longest line measured lazily or capped, **verify** speed in a 10 MB log). Removes the open item in §6.

## 3. Drag and drop: the rest of §10.5 (research doc §5)

- **Move with `Shift`:** our own tiles dragged between two windows of the app (DeX, split screen) move instead of copy when `Shift` is held at the drop (`DragAndDropEvent` key state, **verify** on DeX), with the existing move path (*Undo*, error summary). Drops from other apps always copy.
- **Drop target on the root screen:** onto a favourite or a volume tile under the pointer (copies into it), with the same highlight as folder tiles.
- **Folders from other apps:** when the drag carries a tree URI, copy recursively (`DocumentsContract`); otherwise the current message. **verify** what My Files, Files by Google and Chrome send.
- Pure part tested (the decision copy / move / refuse for source, target and modifier keys).

## 4. MTP devices, third step (todo §9.8)

Code: `data/mtp/` (`MtpDriver`, `AndroidMtpDriver`, `MtpConnection`, `MtpMirror`, `MtpTransfer`, `MtpPaths`), tests in `app/src/test/.../data/mtp/` against `FakeMtpDriver`.

1. **Streaming playback** of long videos and of audio folders (the book player) instead of the local mirror: a Media3 `DataSource` over `getPartialObject` / `getPartialObject64` with a read-ahead buffer; seeking = a new partial read. Keep the mirror for images and as the fallback when the device has no partial reads. Pure part: the buffer and request planning against `FakeMtpDriver` (partial reads, seeks, a device that answers slowly, unplugging mid-read). **verify** read speed on a phone and a camera.
2. **Remember devices:** a stable key (vendor id, product id, serial number, readable after permission) so recent folders, favourites, sort and history can point into a device; shown dimmed while it is unplugged, and opened again when it comes back (permission asked once).
3. **Copy / move on the same device** stays through a local temporary file (`CopyObject` / `MoveObject` are not in `android.mtp.MtpDevice`); make it one job with progress and Stop, and say in the README why it is slower.
4. Keep the existing **verify** list of §9.8 item 4 and add to it.

## 5. Docs

`todo.md` §1a, §3.7 and §8 were marked done on 2026-10-29; keep them so. Each finished item above moves to README §1 (feature tables), §2 (use), §3 (how it works) and out of `todo.md`; what is left stays in §11 with its **verify** list.
