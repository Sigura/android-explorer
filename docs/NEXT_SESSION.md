# Prompt for the next session

Copy everything below the line into a new session on `Sigura/android-explorer`.

---

Continue the Media Viewer Android app (Kotlin, Compose). Read `README.md` (§2.1, §3.6 *Delete, trash and undo*, §3.14 *MTP devices*), `todo.md` §9 and `docs/RESEARCH_2026-10.md` §0 first, then the code: `data/mtp/MtpConnection.kt`, `data/mtp/MtpDevices.kt`, `data/mtp/MtpPaths.kt`, `data/fs/FolderListing.kt`, `data/fs/FileOps.kt`, `data/fs/Trash.kt`, `ui/browser/BrowserState.kt`, `ui/AppViewModel.kt` (the MTP parts and `copyWithSummary`). Start with a short plan in `todo.md`, commit it, then build. Avoid reading binary files (images, APKs, jars, databases). Work on a new branch from `feat/folder-tiles` while [PR #1](https://github.com/Sigura/android-explorer/pull/1) is open (it holds the 2026-10-29 work, which `master` does not have yet), from `master` once it is merged — or on the branch named by the session — and open one PR at the end.

Listening, previews and the thumbnail work of 2026-10-29 are done (todo §3, §4, §1, §10). Scope now — MTP devices, the rest of todo §9.8:

1. **A fake `MtpConnection` and tests first** (§9.8 item 9): an in-memory device (storages, folders, objects, thumbnails, slow and failing calls) behind the same interface, and tests of the listing batches, the watchdog (a call that hangs marks the device *not responding*) and the mirror's 2 GB budget. Everything below is tested against it.
2. **Delete on the device** (item 1) with the same 5 s *Undo* as local files: the file is held back until the snackbar closes, then `deleteObject`; a failed delete is reported like any other.
3. **Copy to the device and new folders** (item 2): *Copy to…* / *Move to…* with a device folder as the destination (`sendObject` with an `MtpObjectInfo`), *New folder* on the device; progress, Stop and `(1)` names as for local copies.
4. **Live updates** (item 3): `readEvent` on its own thread (object added / removed / storage changed) feeds the open device folder like `FolderListing`'s live updates.
5. **Folder collages on device folders** (item 5) from the device thumbnails of the first few files.
6. If time is left: **auto-open** (item 6: an intent filter for `USB_DEVICE_ATTACHED`, class 6 / PTP) and **thumbnails for files the device has none for** (item 8: download small images ≤ 8 MB into the mirror and decode them).

Also planned, with its own prompt: the editor colour themes (`docs/NEXT_SESSION_EDITOR.md`). Not this time: the SAF fallback (§10.7) waits for the owner's decision — ask, don't build.

Checking: no network, no device and no Android SDK may be available — keep logic in pure Kotlin where possible and unit-test it. `tools/pure-tests.sh` runs the pure-Kotlin tests and `tools/typecheck/run.sh` type-checks the whole app without the SDK (docs/RESEARCH_2026-10.md §0); add new pure tests to `tools/pure-tests.sh`, and a stub with the real signature to `tools/typecheck/stubs` for any new Google-only API. Mark device-only assumptions **verify**. Move finished items to README §1/§2/§3 and out of `todo.md`.
