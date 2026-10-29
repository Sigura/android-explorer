# Prompt for the next session

Copy everything below the line into a new session on `Sigura/android-explorer`.

---

Continue the Media Viewer Android app (Kotlin, Compose, Media3). Read `README.md` (§2 *Listening*, §3.12), `todo.md` §3, §4 and §10, and `docs/RESEARCH_2026-10.md` first. Start with a short plan in `todo.md`, commit it, then build. Avoid reading binary files (images, APKs, jars, databases). Work on a new branch from `master` (or the branch named by the session) and open one PR at the end.

Scope (todo §4 and §3.1, left out of the 2026-10-29 PR on purpose):

1. **Full player screen** (todo §4.1, §4.2): opened from the mini player (tap on the cover / title area, or swipe up on the expanded bar), from a long press on the folder ▶ button, and by tapping an audio file. Cover art (embedded picture → `cover.jpg` / `folder.jpg` / the only image), title, folder, the book bar, seek bar, ⏮ −30 s ▶ +30 s ⏭, speed, sleep timer, the queue list. Full keyboard / D-pad / TV remote focus. `BookPlayer` already exposes `state` and `times`.
2. **Speed**: fine steps (0.5–3×, 0.05 step) on the player screen; kept per book (already saved in `history.db`).
3. **Sleep timer**: custom minutes, *end of chapter*, fade-out over the last 10 s, shake to extend (optional); `BookPlayer.sleepIn` / `sleepAtEndOfFile` exist.
4. **Shuffle / music mode** (todo §3.1): remembered per folder (`audio_folder.shuffle`, `seed` columns already exist); shuffle switches the folder to music mode (no resume rewind, no book bar).
5. **Chapters** (todo §4.5): MP4/M4B chapter atoms (`chpl` / QuickTime chapter track), MP3 `CHAP` ID3 frames, Matroska chapters, and `.cue` sheets; shown in the book bar and in a list; ⏮/⏭ move by chapter inside a single-file book.
6. **Lyrics / subtitles** (todo §4.4): `.lrc` next to the audio file (synced, the current line highlighted), embedded `USLT`, `.srt` / `.vtt` for lecture videos. Pure-Kotlin LRC parser with tests in `DomainTest`.

Optional, if time is left (all researched in `docs/RESEARCH_2026-10.md`): the **strip disk cache** for video / GIF / WebP tiles (§3, proposal 1 — the largest thumbnail win), the MediaStore first frame (§3, proposal 2), APNG strips (§2), drop onto a folder tile and `Ctrl` + V paste of URIs (§5). The editor colour themes and richer colouring, and the bundled JetBrains Mono font (`todo.md` §10.8, §10.9) are planned and can be built in the same session if the player work finishes early. The SAF fallback (§7) waits for the owner's decision — ask, don't build.

Rules: no network, no device and no Android SDK may be available — keep logic in pure Kotlin where possible and unit-test it (see `docs/RESEARCH_2026-10.md` §0 for how to compile and run the pure tests with a standalone `kotlinc`; for a whole-app type check without the SDK, put Robolectric's `android-all` (API 36) and the JetBrains Compose desktop jars from Maven Central on the classpath, with small stubs for media3 / activity / lifecycle / datastore — Google Maven redirects to the blocked `dl.google.com`). Mark device-only assumptions **verify**. Move finished items to README §1/§2/§3 and out of `todo.md`.
