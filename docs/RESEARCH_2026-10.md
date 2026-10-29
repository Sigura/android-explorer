# Research notes, 2026-10-29

Findings behind todo §10. Items marked **verify** are not checked on a device yet: no device and no Android SDK were available in the build container.

## 0. Checking work without the Android SDK

The SDK download host (`dl.google.com`) is blocked in the cloud build container, so `./gradlew` can't build or run tests there. Google Maven and Maven Central are reachable.

- **Pure-Kotlin tests** (`domain/*`, `GifCodec`, `WebpAnim`, `TextFile`, `TextSearch`, `ListenLabel`) compile and run with a standalone compiler and JUnit:

  ```bash
  curl -sSLO https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip && unzip -q kotlin-compiler-2.1.20.zip
  curl -sSLO https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
  curl -sSLO https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
  kotlinc/bin/kotlinc -cp junit-4.13.2.jar -d out app/src/main/java/com/mediaviewer/domain/TextSearch.kt app/src/test/java/com/mediaviewer/domain/TextSearchTest.kt
  java -cp out:junit-4.13.2.jar:hamcrest-core-1.3.jar:kotlinc/lib/kotlin-stdlib.jar org.junit.runner.JUnitCore com.mediaviewer.domain.TextSearchTest
  ```

  `TextFileTest` also needs `org.json:json` (for `Drafts`).
- **WebP check against libwebp:** Pillow writes animated WebP (`save_all=True`, lossless, lossy, lossy + alpha, `kmax` for key frames). A small `main` that calls `WebpAnim.scan` / `standalone` dumps every frame; a Python script composites them with the frame's blend and dispose flags and compares each canvas with Pillow's own decoded frame. All three kinds matched exactly (max channel difference 0).

## 2. Animated WebP, AVIF and APNG strips in tiles

| Format | Platform | Seek / random frame | Our way |
| --- | --- | --- | --- |
| **Animated WebP** | `ImageDecoder` → `AnimatedImageDrawable` (API 28+); `BitmapFactory` / `ImageDecoder` as a still give frame 1 | **No** API to seek or read frame *n* | **Done** (todo §10.2): `WebpAnim` reads the RIFF container itself; each frame is a normal `VP8` / `VP8L` (+ `ALPH`) bitstream that the platform decodes once it is wrapped as a still WebP. Compositing (blend, dispose) runs on one canvas; key frames let a long animation skip most frames |
| **Animated AVIF** (`avis` brand) | `AnimatedImageDrawable` plays it from Android 12 (API 31) **verify**; stills from API 31 (`ImageDecoder`), on some devices only from 12 | No | An `avis` file is an ISO-BMFF with a `pict` track of AV1 samples, structurally an MP4. Two ways: (a) `MediaExtractor` + `MediaCodec` (`video/av01`; software decoder `c2.android.av1-dav1d` from Android 12, `libgav1` in 10–11) **verify** that `MediaExtractor` accepts the `avis` brand and the `pict` handler — if it does, the video strip code works as is; (b) Media3's `Mp4Extractor` + the `libgav1` extension (+~1 MB). Proposed: try (a) behind a sniff of the `ftyp` brand; otherwise first frame only, as today |
| **APNG** | Not animated by the platform at all (first frame only, README §6) | — | Same approach as WebP, pure Kotlin: split `fcTL` / `fdAT` chunks into standalone PNGs (`IHDR` with the frame size + the shared `PLTE` / `tRNS` + `IDAT` from `fdAT` with the sequence numbers removed + `IEND`, new CRCs), decode each with `BitmapFactory`, composite with `blend_op` / `dispose_op` (APNG also has *dispose to previous*, like GIF's "restore previous": one backup canvas). ~250 lines + tests; worth doing together with a slideshow APNG player on the same code |

The slideshow keeps the platform's `AnimatedImageDrawable` for WebP / AVIF (smooth, hardware-friendly). The strip code could also drive an APNG player in the slideshow (frames on demand, timing from `fcTL`).

## 3. Video thumbnails: what costs time, per format

Today (README §3.4): one `MediaMetadataRetriever` per file, `setDataSource` + metadata + 5 × `getScaledFrameAtTime(…, OPTION_CLOSEST_SYNC, w, h)`, nothing kept between runs except metadata (`meta.db`). The decode dispatcher runs `max(2, cores / 2)` decodes at once.

### Costs that hit every format

1. **No disk cache of strips.** Every app start, every folder visit after the memory cache dropped it, decodes all visible videos again. This is the largest cost by far, and the same for every format.
2. **One codec set-up per frame.** On Android 10+ each `getScaledFrameAtTime` call goes through the media server's `FrameDecoder`, which creates, configures and releases a codec for that one frame (tens of ms for hardware H.264, more for HEVC; plus a seek). 5 frames = 5 codec set-ups. **verify** the exact per-call cost with `adb shell dumpsys media.metrics`.
3. **Keyframe spacing.** `OPTION_CLOSEST_SYNC` decodes one frame per seek, which is right. Files with sparse keyframes (screen recordings, some phone cameras: 1 per 10 s or more) give the same frame for several fractions; that work is wasted.
4. **Hardware decoder slots.** Phones have 2–16 hardware decoder instances shared with other apps; `limitedParallelism(cores / 2)` may ask for more than are free, and the media server then falls back to software or fails. **verify** on a low-end device.

### Per format

| Container / codec | What `MediaMetadataRetriever` does | Weak spot | Proposal |
| --- | --- | --- | --- |
| **MP4, M4V, MOV, 3GP, 3G2** (H.264, HEVC) | `MPEG4Extractor`: the sample table gives exact sync samples; seeks are cheap | Codec set-up per frame (cost 2); HDR HEVC adds tone-mapping | Disk cache; on API 29+ the **first frame** from `ContentResolver.loadThumbnail` (MediaStore's own cache, usually already made by the gallery) before the strip; skip duplicate frames |
| **MP4 with an embedded cover** (`covr`, podcasts, music videos) | `getEmbeddedPicture()` returns it without decoding video | Not used today | Use it as the first frame of the strip when present (as `ThumbnailUtils.createVideoThumbnail` does on API 29+) |
| **MKV** (H.264, HEVC) | `MatroskaExtractor`: seeks use the **Cues** index | Files without Cues (OBS, cut or streamed dumps) seek by reading clusters from the start: seconds per frame on large files | Disk cache; when the first frame took > 1 s, take only 2 more frames; or read frames with Media3 (below), whose Matroska extractor does the same but in our process, cancellably |
| **WebM** (VP8, VP9, AV1) | Same extractor as MKV | VP9 / AV1 often have **software** decoders only: full-resolution software decode per frame, no scaling during decode | Disk cache; fewer frames (3) for software-decoded codecs; prefer `MediaCodecList` hardware check before choosing the frame count |
| **MPEG-TS, MTS, M2TS** (AVCHD camcorders, TV recordings) | `MPEG2TSExtractor`: no index; seeking estimates the byte position from the bitrate and scans for a keyframe | Slow and sometimes wrong positions; some devices fail on M2TS (192-byte packets) | Media3's `TsExtractor` uses binary-search seeking (`TsBinarySearchSeeker`) and handles M2TS; disk cache |
| **AVI** (MPEG-4 ASP / DivX / Xvid, MJPEG, H.264) | **No AVI extractor in AOSP's `MediaMetadataRetriever`** (some Samsung builds add one) → today's broken icon (README §6) | Not supported at all on most devices | Media3 has an `AviExtractor` (since 2.17): frames through Media3 make AVI thumbnails possible where a decoder exists (MPEG-4 ASP has a software decoder in AOSP; MJPEG doesn't) **verify** |
| **FLV, MPEG-PS (`.vob`, `.mpg`)** | Not in the supported list of the app today | — | Media3 extracts both; add them to `FileTypes` once frames go through Media3 |

### Ranked proposals

1. **Disk cache of strips** (all formats). One small WebP per tile (the 5 frames side by side, or the frames in one file) in `cacheDir/strips/`, keyed by path + size + mtime + width bucket; `LruCache`-style size cap (~100 MB) and the existing `meta.db` for the index. Read on IO before any decode. Expected: revisits and app restarts show strips at once, with no video decode at all. Same for GIF and animated WebP strips.
2. **First frame from MediaStore** (API 29+): `MediaStore.Video.Media` id for the path (one query per folder, cached), then `ContentResolver.loadThumbnail(uri, size)`. Instant for files the gallery already indexed; files in `.nomedia` folders fall back to today's path.
3. **Frame extraction in our process with one codec per file:** Media3 `ExperimentalFrameExtractor` (in `media3-inspector` from 1.8 / in `media3-transformer` 1.6–1.7, **verify** the module for 1.6.1): one ExoPlayer pipeline per file, seeks with `SeekParameters.CLOSEST_SYNC`, scaling on the GPU, cancellable, and the same extractors as playback (MKV without Cues, TS binary search, AVI, FLV). It moves decoding into the app process (a corrupt file can then crash the app: keep `MediaMetadataRetriever` as the default for MP4 and use Media3 for AVI / TS / MKV, or run it in a `:thumbs` process).
4. **Embedded cover** for MP4 / MKV attachments (`cover.jpg` attachment in MKV) as the first frame.
5. **Fewer frames when slow:** if the first frame took longer than ~800 ms (software codec, no index), the strip gets 3 frames instead of 5.
6. **Dedupe** frames that come back identical (same sync sample): ask for the next sync sample after the previous one instead (`OPTION_NEXT_SYNC`).

## 5. Dropping files into the app

- **API.** `Modifier.dragAndDropTarget` (Compose 1.8) wraps `View.OnDragListener`. A drop gives a `ClipData` of `content://` URIs; `Activity.requestDragAndDropPermissions(event)` grants read access until `release()` (or until the activity is destroyed). The drag must come with `DRAG_FLAG_GLOBAL_URI_READ` from the other app — My Files, Chrome and Gmail do. **verify** on DeX: My Files, Samsung Internet's downloads, Gallery.
- **Done** (todo §10.5): drop on the folder grid copies into the open folder (progress, Stop, " (1)" names, errors as for *Copy to…*); our own FileProvider URIs map back to paths, so a tile dropped on its own folder does nothing, and a tile dragged from a second window of this app is copied by path.
- **Folders.** A dragged folder arrives as a document URI with MIME `vnd.android.document/directory`. Listing its children needs a **tree** URI; a drag grants a single document. So folder drops work only when the source app sends a tree URI (**verify** what My Files sends). Otherwise the drop reports *"the app it came from doesn't share folders; drop the files instead"*.
- **Still open:** drop **onto a folder tile** (into that folder, with the tile highlighted); **move** for own tiles between two windows with `Shift` held; a drop target on the root screen (into the favourite / volume under the pointer); **paste** (`Ctrl` + V) of URIs from the clipboard with the same code; Android 7–8 have no `requestDragAndDropPermissions` problems but minSdk 26 means DeX on Android 8 must be checked.

## 7. Storage Access Framework fallback (Play Store build)

### Facts

- The app uses `MANAGE_EXTERNAL_STORAGE` ("All files access"). Google Play allows it only for listed uses after a **declaration form** in Play Console; **file managers** are a listed permitted use, and *"media viewer"* alone is not. This app browses every folder, opens any file with other apps, moves, copies, deletes and edits text — it is a file manager in Play's sense, so a declaration has a reasonable chance. **Try that first: no code.**
- Without "All files access" on Android 11+:
  - **`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`** (13+) or `READ_EXTERNAL_STORAGE` (11–12) let `java.io.File` read **media files** by path through FUSE, and `File.listFiles()` lists the media files (and folders) of shared storage — but **not** other files (text, PDF, archives…). So the browser, thumbnails, slideshow and book player keep working for media with the current path-based code.
  - Android 14+ may grant only *selected photos* (`READ_MEDIA_VISUAL_USER_SELECTED`): then only those files are visible. The app must say so and offer *Select more*.
  - **Writes** to files the app didn't create (delete, move, rename) need `MediaStore.createDeleteRequest` / `createWriteRequest` / `createTrashRequest` — one system dialog per batch. The app's own trash folder (README §3.6) can't be used; the system trash (`createTrashRequest`) can.
  - **Other files and SD-card / USB folders:** only through SAF — `ACTION_OPEN_DOCUMENT_TREE` on a folder, then `DocumentsContract` queries (slow: ~1–5 ms per file through the provider, no `File` API, no `mtime` sort without querying every child).

### Options

| | Work | Result |
| --- | --- | --- |
| **A. Play declaration as a file manager** | none in code; policy text + a video of the use | same app on Play, if accepted |
| **B. Media-only flavor** (`play`): `READ_MEDIA_*` + FUSE paths, `MediaStore` requests for delete / move / rename, the editor and other files hidden, SD / USB through MediaStore volumes | a `productFlavors` split (`direct` keeps `MANAGE_EXTERNAL_STORAGE`), a `Storage` capability object checked by delete / move / trash / editor / new file, a permission screen for media permissions incl. partial access | ~1 session; covers the main use (photos, videos, audiobooks) |
| **C. Full SAF layer** | a `Node` abstraction (path or document URI) through `FolderListing`, `Thumbnails` (decoders take `ParcelFileDescriptor`), `FileOps`, `Trash`, `TextFile`, history keys, favourites | 3–5 sessions; slow listings of big folders; every feature retested |

**Recommendation:** A first. If Play rejects the declaration, B (it reuses almost all code), and C only for the parts B can't reach (text files on shared storage: open them through `ACTION_OPEN_DOCUMENT` from the editor instead of browsing them). **Needs the owner's decision** before any code.
