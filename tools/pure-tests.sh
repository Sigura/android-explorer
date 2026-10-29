#!/usr/bin/env bash
# The pure-Kotlin unit tests without Gradle or the Android SDK (docs/RESEARCH_2026-10.md §0):
# domain/*, the tag and preview readers, APNG / WebP containers, file types.
# Downloads (kotlinc, JUnit) go to $TYPECHECK_WORK, shared with tools/typecheck/run.sh.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
W="${TYPECHECK_WORK:-${TMPDIR:-/tmp}/android-explorer-typecheck}"
mkdir -p "$W"
KOTLINC="${KOTLINC:-$W/kotlinc}"
if [ ! -x "$KOTLINC/bin/kotlinc" ]; then
  curl -sSfL --retry 4 --retry-delay 3 -o "$W/kotlinc.zip" https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip
  unzip -q -o "$W/kotlinc.zip" -d "$W"
fi
# Maven Central, then its Apache mirror (repo1 sometimes answers 429 to busy build containers).
for j in junit/junit/4.13.2/junit-4.13.2 org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3; do
  f="$W/$(basename $j).jar"
  [ -s "$f" ] && unzip -tq "$f" >/dev/null 2>&1 && continue
  curl -sSfL --retry 4 --retry-delay 3 -o "$f" "https://repo1.maven.org/maven2/$j.jar" ||
    curl -sSfL --retry 4 --retry-delay 3 -o "$f" "https://repo.maven.apache.org/maven2/$j.jar"
done
M="$REPO/app/src/main/java/com/mediaviewer"
T="$REPO/app/src/test/java/com/mediaviewer"
STUB="$W/stub/androidx/compose/runtime"
mkdir -p "$STUB"
printf 'package androidx.compose.runtime\nannotation class Immutable\nannotation class Stable\n' > "$STUB/Immutable.kt"
OUT="$W/pure-out"
rm -rf "$OUT"
"$KOTLINC/bin/kotlinc" -nowarn -cp "$W/junit-4.13.2.jar" -d "$OUT" "$STUB/Immutable.kt" \
  "$M"/domain/*.kt "$M/domain/model/FileEntry.kt" "$M/data/fs/FileTypes.kt" "$M/data/tags/MediaTags.kt" \
  "$M/data/thumbs/DocCovers.kt" "$M/data/thumbs/WebpAnim.kt" "$M/data/thumbs/ApngAnim.kt" \
  "$T"/domain/*.kt "$T/data/MediaTagsTest.kt" "$T/data/PreviewTest.kt" "$T/data/ApngAnimTest.kt" "$T/data/WebpAnimTest.kt" "$T/data/FileTypesTest.kt"
CLASSES=$(cd "$OUT" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$OUT:$W/junit-4.13.2.jar:$W/hamcrest-core-1.3.jar:$KOTLINC/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $CLASSES
