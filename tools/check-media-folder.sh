#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# check-media-folder.sh — run the app's folder-view and slideshow code over
# every file of a real media folder (README §8.1) and report coverage.
#
# Usage:
#   tools/check-media-folder.sh DIR [--threads N] [--large-gif-mb N]
#
#   DIR             folder to check (required)
#   --threads N     parallel GIF decoders (default: 6)
#   --large-gif-mb  large-GIF threshold, as in settings (default: 40)
#
# What runs (on the JVM, the real app classes):
#   • streaming listing + sorted insertion (FolderScanner, FolderListing)
#   • type detection by extension and magic-byte sniffing (FileTypes)
#   • every GIF: first frame, block scan, 5-frame strip decode (GifCodec)
#   • slideshow timing for every media file at R = 1, 14, 40 s (SlideTimer)
#   • slideshow order: sequential pass, a full random cycle with no repeats,
#     restore-after-restart checks (SlideOrder)
#
# Not covered here (needs a device or emulator): ImageDecoder stills,
# MediaMetadataRetriever video frames, ExoPlayer, Coil and the Compose UI.
#
# Output:
#   build/reports/media-check/summary.txt        — the summary printed below
#   build/reports/media-check/gif-failures.tsv   — every GIF that can't be read
#   build/reports/media-check/types.tsv          — detected type of every file
#   app/build/reports/coverage/test/debug/index.html — JaCoCo line coverage
# =============================================================================

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DIR=""
# Spinning-disk RAIDs are seek-bound with many parallel readers; 4–8 is usually fastest there.
THREADS=6
LARGE_GIF_MB=40
while [[ $# -gt 0 ]]; do
    case "$1" in
        --threads) THREADS="$2"; shift 2 ;;
        --large-gif-mb) LARGE_GIF_MB="$2"; shift 2 ;;
        --help|-h) sed -n '4,33p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) DIR="$1"; shift ;;
    esac
done

if [[ -z "$DIR" ]]; then
    echo "Usage: $0 DIR [--threads N] [--large-gif-mb N]" >&2
    exit 1
fi

if [[ ! -d "$DIR" ]]; then
    echo "Not a folder: $DIR" >&2
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
    for d in "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
        [[ -d "$d/platforms" ]] && export ANDROID_HOME="$d" && break
    done
fi

REPORT="$ROOT/build/reports/media-check"
rm -rf "$REPORT"
mkdir -p "$REPORT"

echo "Checking $(find "$DIR" -maxdepth 1 -mindepth 1 | wc -l) entries in $DIR with $THREADS threads…"
START=$SECONDS
set +e
MV_CHECK_DIR="$DIR" MV_CHECK_THREADS="$THREADS" MV_CHECK_LARGE_GIF_MB="$LARGE_GIF_MB" MV_CHECK_REPORT="$REPORT" \
    ./gradlew :app:testDebugUnitTest --rerun :app:createDebugUnitTestCoverageReport \
    --no-daemon --console=plain -q > "$REPORT/gradle.log" 2>&1
RC=$?
set -e
grep -vE '^(openjdk|OpenJDK)' "$REPORT/gradle.log" | grep -vE '^\s*$' | grep -v '^  GIFs:' || true
if [[ $RC -ne 0 ]]; then
    echo ""
    echo "Some checks failed: see app/build/reports/tests/testDebugUnitTest/index.html"
    [[ -f "$REPORT/summary.txt" ]] && cat "$REPORT/summary.txt"
    exit 1
fi

echo ""
echo "════════ Summary ($((SECONDS - START)) s) ════════"
cat "$REPORT/summary.txt"

COV="app/build/reports/coverage/test/debug/report.xml"
if [[ -f "$COV" ]]; then
    echo ""
    echo "════════ Line coverage (JVM-testable code) ════════"
    python3 -I - "$COV" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = []
for pkg in root.findall('package'):
    for src in pkg.findall('sourcefile'):
        c = {x.get('type'): (int(x.get('missed')), int(x.get('covered'))) for x in src.findall('counter')}
        m, cv = c.get('LINE', (0, 0))
        if m + cv: rows.append((pkg.get('name').replace('/', '.') + '.' + src.get('name'), cv, m + cv))
for name, cv, tot in sorted(rows):
    if name.split('.')[-2] in ('fs', 'thumbs', 'domain', 'model') or '.domain.' in name:
        print(f"  {100 * cv / tot:5.1f} %  {cv:4d}/{tot:<4d} {name}")
EOF
    echo "  Full report: app/build/reports/coverage/test/debug/index.html"
fi
