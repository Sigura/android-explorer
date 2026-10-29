# Prompt for a session: editor colour themes and richer colouring

Copy everything below the line into a new session on `Sigura/android-explorer`.

---

Build the editor colour themes and richer colouring planned in `todo.md` §10.8 for the Media Viewer Android app (Kotlin, Compose). Read `todo.md` §10.8 first, then README §2 *Text editor* and §3.13, and the code: `ui/editor/EditorScreen.kt` (`rememberStyles`, `rememberFoundStyles`, `SpanTransformation`, `Marks`, `textStyle`), `ui/editor/EditorState.kt`, `ui/editor/EditorFind.kt`, `domain/Highlight.kt` (`Role`, `Spans`), `data/settings/SettingsStore.kt`, `ui/settings/SettingsScreen.kt`, `ui/common/CodeFonts.kt`. Start with a short plan added to `todo.md` §10.8, commit it, then build. Avoid reading binary files (fonts, images, jars). Work on the branch the session names (or a new one from `master`), and open one PR at the end.

Scope:

1. **Themes:** a pure-Kotlin `EditorTheme` table (domain package): name, dark flag, and colours for the 12 `Role`s plus background, text, cursor, selection, current line, find match / current match, gutter text. Built in: *Default* (today's light and dark palettes from `rememberStyles`, unchanged), *One Dark*, *Solarized Light*, *Solarized Dark*, *GitHub Light*, *GitHub Dark*, *Monokai*, *Dracula*, *High contrast*. *Default* follows the app's day / night; any other theme is used as chosen. Setting `editorTheme` (string id) in `SettingsStore`; chooser in the editor ⋮ → *Theme…* (a dialog listing themes, each with a 3-line coloured code sample) and in Settings → Editor.
2. **Contrast test** in `EditorTest`: every role colour and the text colour ≥ 4.5 : 1 against the theme background (WCAG relative luminance, pure Kotlin); fix palette values that fail rather than relaxing the test.
3. **Current line** background (theme colour) in the editable editor.
4. **Matching bracket:** with the cursor next to `()[]{}` (and `<tag>` / `</tag>` in markup languages), highlight both brackets. Pure-Kotlin `Brackets.match(text, offset, spans)` in the domain package: a bounded scan (±20,000 characters) that skips brackets inside strings and comments using the existing spans; unit-tested (nesting, strings, comments, unmatched, the bound).
5. **Optional, behind settings (default off):** line numbers in a gutter (also for read-only files), and whitespace marks (tabs as `→`, trailing spaces).
6. Colours stay off above 200 K characters (`TextFile.COLOUR_LIMIT`); current line and bracket matching still work there.

Checking: no Android SDK may be available in the build container (`dl.google.com` is blocked). Follow `docs/RESEARCH_2026-10.md` §0 to compile and run the pure-Kotlin tests with a standalone `kotlinc` 2.1.20 + JUnit 4 (add a tiny stub for `androidx.compose.runtime.Immutable` / `Stable` when compiling `domain/model`). For a whole-app type check, put Robolectric's `org.robolectric:android-all` (API 36) and the JetBrains Compose desktop 1.8 jars from Maven Central on the classpath, with small hand-written stubs for media3 / activity / lifecycle / datastore / core, and run `kotlinc` with its bundled `compose-compiler-plugin.jar`. Google Maven redirects to the blocked host. Mark device-only assumptions **verify** (typing speed in a 1 MB file with the extra spans, gutter drawing from `onTextLayout`, scrolling smoothness). Move what is finished to README §1 / §2 / §3.13 and out of `todo.md`.
