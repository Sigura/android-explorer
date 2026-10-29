#!/usr/bin/env bash
# Whole-app Kotlin type check without the Android SDK (docs/RESEARCH_2026-10.md §0).
#   android.jar  = Robolectric's android-all (API 36) from Maven Central
#   Compose      = JetBrains Compose Multiplatform 1.8 desktop jars (+ coil3, guava, json, AndroidSVG)
#   Google-only  = hand-written stubs in ./stubs (media3, activity, lifecycle, datastore, core…),
#                  plus ./stubs/platform: Android-only Compose APIs that desktop Compose lacks.
# The stubs were written from the libraries' documented APIs: a call that doesn't exist in the
# real library can slip through. Downloads (kotlinc, jars) go to $TYPECHECK_WORK.
# Usage: tools/typecheck/run.sh [--all]   (errors only; --all: full output with warnings)
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$T/../.." && pwd)"
export TYPECHECK_WORK="${TYPECHECK_WORK:-${TMPDIR:-/tmp}/android-explorer-typecheck}"
W="$TYPECHECK_WORK"
SRC="${SRC:-$REPO/app/src/main/java}"
mkdir -p "$W"

KOTLINC="${KOTLINC:-$W/kotlinc}"
if [ ! -x "$KOTLINC/bin/kotlinc" ]; then
  echo "typecheck: fetching kotlinc 2.1.20" >&2
  curl -sSfL --retry 4 --retry-delay 3 -o "$W/kotlinc.zip" https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip &&
    unzip -q -o "$W/kotlinc.zip" -d "$W" || exit 1
fi
if ! ls "$W"/jars/android-all-*.jar >/dev/null 2>&1 || ! ls "$W"/jars/androidsvg*.jar >/dev/null 2>&1; then
  echo "typecheck: fetching jars from Maven Central" >&2
  python3 -I "$T/resolve.py" >&2 || exit 1
fi

CP="$(ls "$W"/jars/*.jar | tr '\n' ':')$KOTLINC/lib/kotlin-stdlib.jar"
OUT="$W/out"
rm -rf "$OUT" && mkdir -p "$OUT"
LOG="$W/last.log"

JAVA_OPTS="-Xmx6g -Xss8m" "$KOTLINC/bin/kotlinc" \
  -Xplugin="$KOTLINC/lib/compose-compiler-plugin.jar" \
  -no-reflect -jvm-target 17 -Xjdk-release=17 -language-version 2.1 \
  -opt-in=kotlin.RequiresOptIn \
  -Xskip-prerelease-check -Xsuppress-version-warnings \
  -cp "$CP" -d "$OUT" \
  -Xjava-source-roots="$T/stubs/java" \
  "$SRC" "$T/stubs" > "$LOG" 2>&1
RC=$?

sed -e "s#$SRC/com/mediaviewer/##" -e "s#$T/##" "$LOG" > "$LOG.rel"
if [[ "${1:-}" == "--all" ]]; then
  cat "$LOG.rel"
else
  grep -E '^[^ ]+\.kt:[0-9]+:[0-9]+: error:' "$LOG.rel" || true
  grep -E '^(error|exception):' "$LOG.rel" || true
fi
ERRORS=$(grep -cE ': error:|^error:' "$LOG.rel" || true)
WARNINGS=$(grep -cE ': warning:' "$LOG.rel" || true)
echo "---- $ERRORS errors, $WARNINGS warnings (full log: $LOG.rel); kotlinc exit $RC"
exit $RC
