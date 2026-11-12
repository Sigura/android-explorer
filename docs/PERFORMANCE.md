[← README](../README.md)

# 5. Performance and the reference folder

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
