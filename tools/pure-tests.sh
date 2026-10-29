#!/usr/bin/env bash
# The pure-Kotlin unit tests without Gradle or the Android SDK (docs/RESEARCH_2026-10.md §0):
# domain/*, the tag and preview readers, APNG / WebP containers, file types, and the MTP
# connection, mirror and transfers against a fake device (data/mtp, with stubs of Log / SystemClock).
# Downloads (kotlinc, JUnit, coroutines) go to $TYPECHECK_WORK, shared with tools/typecheck/run.sh.
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
for j in junit/junit/4.13.2/junit-4.13.2 org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3 \
  org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.1/kotlinx-coroutines-core-jvm-1.10.1; do
  f="$W/$(basename $j).jar"
  [ -s "$f" ] && unzip -tq "$f" >/dev/null 2>&1 && continue
  # tools/typecheck/run.sh may have fetched it already.
  [ -s "$W/jars/$(basename $j).jar" ] && cp "$W/jars/$(basename $j).jar" "$f" && continue
  curl -sSfL --retry 4 --retry-delay 3 -o "$f" "https://repo1.maven.org/maven2/$j.jar" ||
    curl -sSfL --retry 4 --retry-delay 3 -o "$f" "https://repo.maven.apache.org/maven2/$j.jar"
done
CO="$W/kotlinx-coroutines-core-jvm-1.10.1.jar"
M="$REPO/app/src/main/java/com/mediaviewer"
T="$REPO/app/src/test/java/com/mediaviewer"
STUB="$W/stub/androidx/compose/runtime"
mkdir -p "$STUB"
printf 'package androidx.compose.runtime\nannotation class Immutable\nannotation class Stable\n' > "$STUB/Immutable.kt"
# The two Android APIs the MTP and listing code use: logging and the uptime clock.
printf '%s\n' 'package android.util' 'object Log {' \
  '    @JvmStatic fun w(tag: String, msg: String, t: Throwable? = null): Int = 0' \
  '    @JvmStatic fun d(tag: String, msg: String, t: Throwable? = null): Int = 0' \
  '    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null): Int = 0' '}' > "$STUB/Log.kt"
printf '%s\n' 'package android.os' 'object SystemClock {' \
  '    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000' '}' > "$STUB/SystemClock.kt"
OUT="$W/pure-out"
rm -rf "$OUT"
"$KOTLINC/bin/kotlinc" -nowarn -cp "$W/junit-4.13.2.jar:$CO" -d "$OUT" "$STUB/Immutable.kt" "$STUB/Log.kt" "$STUB/SystemClock.kt" \
  "$M"/domain/*.kt "$M/domain/model/FileEntry.kt" "$M/data/fs/FileTypes.kt" "$M/data/tags/MediaTags.kt" \
  "$M/data/thumbs/DocCovers.kt" "$M/data/thumbs/WebpAnim.kt" "$M/data/thumbs/ApngAnim.kt" \
  "$M/data/fs/FolderScanner.kt" "$M/data/fs/FileOps.kt" \
  "$M/data/mtp/MtpPaths.kt" "$M/data/mtp/MtpDriver.kt" "$M/data/mtp/MtpConnection.kt" "$M/data/mtp/MtpMirror.kt" "$M/data/mtp/MtpTransfer.kt" \
  "$T"/domain/*.kt "$T/data/MediaTagsTest.kt" "$T/data/PreviewTest.kt" "$T/data/ApngAnimTest.kt" "$T/data/WebpAnimTest.kt" "$T/data/FileTypesTest.kt" \
  "$T/data/MtpPathsTest.kt" "$T"/data/mtp/*.kt
CLASSES=$(cd "$OUT" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g')
java -cp "$OUT:$W/junit-4.13.2.jar:$W/hamcrest-core-1.3.jar:$CO:$KOTLINC/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $CLASSES
