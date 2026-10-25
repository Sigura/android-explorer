#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# build-apk.sh — APK build script for the Media Viewer Android app
#
# Usage:
#   ./build-apk.sh [OPTIONS]
#
# Options:
#   --variant <type>    Build variant: debug (default) or release
#   --clean             Run ./gradlew clean before building
#   --install           Install APK via adb after successful build
#   --lint              Run Android lint checks before building
#   --no-color          Disable colored output (for CI)
#   --ci                CI mode: --no-color + strict + non-interactive
#   --help, -h          Show this help message
#
# Prerequisite auto-detection & repair:
#   • Java 17+  — probes PATH, JAVA_HOME, SDKMAN, /usr/lib/jvm, asdf, jabba
#   • Android SDK — probes ANDROID_HOME, ANDROID_SDK_ROOT, local.properties,
#                    $HOME/Android/Sdk, /opt/android-sdk, /usr/lib/android-sdk
#   • ANDROID_HOME — auto-exported for the session if found via probing;
#                     warns if set but pointing to a non-existent directory
#   • Gradle wrapper — permissions auto-fixed if gradlew is not executable
#
#   When prerequisites are missing, the script prints platform-specific fix
#   commands (apt/brew/sdkmanager) and exits with a clear error message.
#
# Examples:
#   ./build-apk.sh                                  # debug build
#   ./build-apk.sh --variant release                # release build
#   ./build-apk.sh --variant release --clean --install
#   ./build-apk.sh --ci --variant debug             # CI pipeline
#   ./build-apk.sh --install                        # quick dev iteration
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Defaults ────────────────────────────────────────────────────────────────
VARIANT="debug"
# Android package id — must stay in sync with app/build.gradle.kts.
APP_PKG="com.mediaviewer"
APK_NAME="media-viewer"
DO_CLEAN=false
DO_INSTALL=false
DO_LINT=false
USE_COLOR=true
CI_MODE=false
SHOW_HELP=false

# ── Parse arguments ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --variant)
            VARIANT="$2"
            shift 2
            ;;
        --clean)
            DO_CLEAN=true
            shift
            ;;
        --install)
            DO_INSTALL=true
            shift
            ;;
        --lint)
            DO_LINT=true
            shift
            ;;
        --no-color)
            USE_COLOR=false
            shift
            ;;
        --ci)
            CI_MODE=true
            USE_COLOR=false
            shift
            ;;
        --help|-h)
            SHOW_HELP=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Run '$0 --help' for usage."
            exit 1
            ;;
    esac
done

# ── Help ─────────────────────────────────────────────────────────────────────
if $SHOW_HELP; then
    head -36 "$0" | sed -n '/^# =/,/^# ==/p' | sed 's/^# \?//' | head -n -1
    exit 0
fi

# ── Color helpers ───────────────────────────────────────────────────────────
if $USE_COLOR; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; BLUE=''; BOLD=''; NC=''
fi

# ── OS detection (for platform-specific fix suggestions) ────────────────────
detect_os() {
    case "$(uname -s)" in
        Linux)
            if [[ -f /etc/os-release ]]; then
                OS_ID="$(grep -E '^ID=' /etc/os-release | cut -d= -f2 | tr -d '"' || echo "linux")"
            else
                OS_ID="linux"
            fi
            ;;
        Darwin)  OS_ID="macos" ;;
        *)       OS_ID="unknown" ;;
    esac
}
detect_os

# ── Logging helpers ─────────────────────────────────────────────────────────
info()    { echo -e "${GREEN}[info]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[warn]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }
header()  { echo -e "\n${BLUE}${BOLD}═══ $* ═══${NC}\n"; }
success() { echo -e "\n${GREEN}${BOLD}✓ $*${NC}\n"; }
# Print a fix suggestion (indented, dim color)
fix()     { echo -e "  ${BLUE}💡 Fix:${NC} $*"; }

# ── Per-check helper: failure → append to MISSING + FIXES arrays ────────────
# Usage: fail_check "description" "fix instruction"
fail_check() {
    MISSING+=("$1")
    FIXES+=("$2")
}

# ── Validate variant ────────────────────────────────────────────────────────
VARIANT_LOWER="$(echo "$VARIANT" | tr '[:upper:]' '[:lower:]')"
if [[ "$VARIANT_LOWER" != "debug" && "$VARIANT_LOWER" != "release" ]]; then
    error "Invalid variant: '$VARIANT'. Must be 'debug' or 'release'."
    exit 1
fi
VARIANT="$VARIANT_LOWER"

# ── Banner ──────────────────────────────────────────────────────────────────
header "Media Viewer Android APK Builder"
echo "  Variant:       ${VARIANT}"
echo "  Clean:         ${DO_CLEAN}"
echo "  Lint:          ${DO_LINT}"
echo "  Install:       ${DO_INSTALL}"
echo "  CI mode:       ${CI_MODE}"
echo ""

# =============================================================================
# PREREQUISITE CHECKS
# =============================================================================
header "Checking prerequisites"

MISSING=()
FIXES=()

# ── Java ────────────────────────────────────────────────────────────────────
# ── Helper: extract required Java version from build files ───────────────────
extract_required_java_version() {
    local req_java=""
    # Check app/build.gradle.kts for JavaVersion.VERSION_X or jvmTarget
    if [[ -f app/build.gradle.kts ]]; then
        req_java=$(grep -E 'JavaVersion\.VERSION_([0-9]+)' app/build.gradle.kts 2>/dev/null | grep -oE 'VERSION_[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
    fi
    # Fallback: check kotlinOptions.jvmTarget
    if [[ -z "$req_java" && -f app/build.gradle.kts ]]; then
        local jvm_target=$(grep -E 'jvmTarget\s*=' app/build.gradle.kts 2>/dev/null | grep -oE '"[0-9]+"' | tr -d '"' | head -1 || true)
        req_java="$jvm_target"
    fi
    echo "${req_java:-17}"
}

# ── Helper: probe common JDK install locations ──────────────────────────────
probe_jdk_locations() {
    local dirs=()
    # Standard JDK home locations
    dirs+=("$HOME/.sdkman/candidates/java/current")
    # Linux system JDKs
    dirs+=("/usr/lib/jvm/default-java")
    dirs+=("/usr/lib/jvm/java-17-openjdk-amd64")
    dirs+=("/usr/lib/jvm/java-21-openjdk-amd64")
    dirs+=("/usr/lib/jvm/java-17-openjdk")
    dirs+=("/usr/lib/jvm/java-21-openjdk")
    dirs+=("/usr/java/latest")
    # macOS
    dirs+=("/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    dirs+=("/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    dirs+=("/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home")
    dirs+=("/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home")
    # asdf version manager
    if command -v asdf &>/dev/null; then
        local asdf_java=$(asdf where java 2>/dev/null || echo "")
        [[ -n "$asdf_java" ]] && dirs+=("$asdf_java")
    fi
    # jabba version manager
    if command -v jabba &>/dev/null; then
        local jabba_java=$(jabba which --home 2>/dev/null || echo "")
        [[ -n "$jabba_java" ]] && dirs+=("$jabba_java")
    fi
    # Print the first match that has a working java binary
    for d in "${dirs[@]}"; do
        if [[ -f "$d/bin/java" ]] || [[ -f "$d/bin/java.exe" ]]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

# ── Helper: parse Java major version robustly ────────────────────────────────
parse_java_major() {
    local java_bin="$1"
    local version_line=$("$java_bin" -version 2>&1 | head -1)
    # Handles: OpenJDK, Oracle, GraalVM, Adoptium, Zulu, Corretto, etc.
    # Patterns: "1.8.0_392", "11.0.21", "17.0.9", "21.0.1"
    local major
    major=$(echo "$version_line" | sed -n 's/.*version "\([0-9]*\).*/\1/p')
    if [[ -z "$major" ]]; then
        major=$(echo "$version_line" | sed -n 's/.*version "1\.\([0-9]*\).*/\1/p')
    fi
    if [[ -z "$major" ]]; then
        major=$(echo "$version_line" | sed -n 's/.*version "\([0-9][0-9.]*\).*/\1/p' | cut -d'.' -f1)
    fi
    echo "${major:-0}"
}

# ── Helper: check if a java path is a JDK (has javac) ───────────────────────
is_jdk() {
    local java_home="$1"
    [[ -f "$java_home/bin/javac" || -f "$java_home/bin/javac.exe" ]]
}

REQUIRED_JAVA=$(extract_required_java_version)
info "Required: Java ${REQUIRED_JAVA}+"

JAVA_FOUND=false
JAVA_BIN=""
JAVA_HOME_RESOLVED=""
JAVA_JDK=false

# Step 1: Try java on current PATH
if command -v java &>/dev/null; then
    JAVA_BIN="$(command -v java)"
    JAVA_FOUND=true
fi

# Step 2: If not on PATH, try JAVA_HOME
if ! $JAVA_FOUND && [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -f "${JAVA_HOME}/bin/java" || -f "${JAVA_HOME}/bin/java.exe" ]]; then
        export PATH="${JAVA_HOME}/bin:$PATH"
        JAVA_BIN="${JAVA_HOME}/bin/java"
        JAVA_FOUND=true
        info "Added JAVA_HOME/bin to PATH"
    fi
fi

# Step 3: Probe known JDK locations
if ! $JAVA_FOUND; then
    PROBED_JDK=$(probe_jdk_locations || echo "")
    if [[ -n "$PROBED_JDK" ]]; then
        export PATH="${PROBED_JDK}/bin:$PATH"
        JAVA_BIN="${PROBED_JDK}/bin/java"
        JAVA_HOME_RESOLVED="$PROBED_JDK"
        JAVA_FOUND=true
        info "Found JDK via probing: ${PROBED_JDK}"
    fi
fi

# Step 4: Validate found Java
if $JAVA_FOUND; then
    JAVA_VERSION_LINE=$("$JAVA_BIN" -version 2>&1 | head -1)
    JAVA_MAJOR=$(parse_java_major "$JAVA_BIN")
    JAVA_VENDOR=$("$JAVA_BIN" -version 2>&1 | head -3 | tail -1)
    info "Java: ${JAVA_VERSION_LINE}"

    # Resolve JAVA_HOME
    if [[ -z "$JAVA_HOME_RESOLVED" ]]; then
        # Try to derive JAVA_HOME from the binary path
        JAVA_HOME_RESOLVED=$(dirname "$(dirname "$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")")")
    fi

    # Check if it's a JDK (not just JRE)
    if is_jdk "$JAVA_HOME_RESOLVED"; then
        JAVA_JDK=true
        info "  Type: JDK ✓"
    else
        warn "  Type: JRE only (no javac) — Gradle needs a full JDK"
    fi

    # Check JAVA_HOME consistency
    if [[ -n "${JAVA_HOME:-}" && "$JAVA_HOME" != "$JAVA_HOME_RESOLVED" ]]; then
        java_home_major=""
        if [[ -f "${JAVA_HOME}/bin/java" ]]; then
            java_home_major=$(parse_java_major "${JAVA_HOME}/bin/java")
        fi
        if [[ -n "$java_home_major" && "$java_home_major" != "$JAVA_MAJOR" ]]; then
            warn "  JAVA_HOME points to Java ${java_home_major}, but java on PATH is Java ${JAVA_MAJOR}"
            warn "  JAVA_HOME: ${JAVA_HOME}"
            warn "  PATH java: ${JAVA_HOME_RESOLVED}"
            warn "  This mismatch may cause Gradle build issues."
            # Auto-fix: prefer JAVA_HOME if it's a valid JDK with sufficient version
            if is_jdk "$JAVA_HOME" && [[ "$java_home_major" -ge "$REQUIRED_JAVA" ]]; then
                export PATH="${JAVA_HOME}/bin:$PATH"
                JAVA_BIN="${JAVA_HOME}/bin/java"
                JAVA_HOME_RESOLVED="$JAVA_HOME"
                JAVA_MAJOR="$java_home_major"
                JAVA_JDK=true
                info "  Switched to JAVA_HOME JDK (Java ${java_home_major})"
            fi
        fi
    fi

    # Version check
    if [[ "${JAVA_MAJOR:-0}" -lt "$REQUIRED_JAVA" ]]; then
        error "Java ${REQUIRED_JAVA}+ is required. Found Java ${JAVA_MAJOR:-unknown}"
        case "$OS_ID" in
            ubuntu|debian) FIX_CMD="sudo apt install openjdk-${REQUIRED_JAVA}-jdk" ;;
            macos)         FIX_CMD="brew install openjdk@${REQUIRED_JAVA}" ;;
            *)             FIX_CMD="Install JDK ${REQUIRED_JAVA}+ from https://adoptium.net" ;;
        esac
        fail_check "Java ${REQUIRED_JAVA}+" \
            "Found Java ${JAVA_MAJOR:-?}. Install Java ${REQUIRED_JAVA}+ and set JAVA_HOME.
           ${FIX_CMD}
           Then: export JAVA_HOME=/path/to/jdk-${REQUIRED_JAVA}"
    fi

    # Warn if JDK but still acceptable
    if ! $JAVA_JDK && [[ "${JAVA_MAJOR:-0}" -ge "$REQUIRED_JAVA" ]]; then
        warn "Java version is sufficient, but a full JDK (not just JRE) is recommended."
        warn "Gradle may still work via its bundled toolchain resolver, but having javac avoids surprises."
        case "$OS_ID" in
            ubuntu|debian) warn "  Install JDK: sudo apt install openjdk-${REQUIRED_JAVA}-jdk" ;;
            macos)         warn "  Install JDK: brew install openjdk@${REQUIRED_JAVA}" ;;
        esac
    fi
else
    # Nothing found anywhere — give a comprehensive diagnostic
    error "Java ${REQUIRED_JAVA}+ not found."
    echo ""
    warn "Checked locations:"
    warn "  • PATH:                  $(echo "$PATH" | tr ':' '\n' | sed 's/^/      /')"
    warn "  • JAVA_HOME:             ${JAVA_HOME:-<not set>}"
    warn "  • /usr/lib/jvm/*"
    warn "  • \$HOME/.sdkman/candidates/java/current"
    warn "  • /usr/local/opt/openjdk@*/ (macOS)"
    warn "  • /Library/Java/JavaVirtualMachines/ (macOS)"
    echo ""
    case "$OS_ID" in
        ubuntu|debian) FIX_CMD="sudo apt install openjdk-${REQUIRED_JAVA}-jdk" ;;
        macos)         FIX_CMD="brew install openjdk@${REQUIRED_JAVA}" ;;
        *)             FIX_CMD="Install JDK ${REQUIRED_JAVA}+ from https://adoptium.net" ;;
    esac
    fail_check "Java ${REQUIRED_JAVA}+" \
        "Java ${REQUIRED_JAVA}+ is not installed or not on PATH.
           ${FIX_CMD}
           Then: export JAVA_HOME=/path/to/jdk-${REQUIRED_JAVA}
         Quick install via SDKMAN (recommended for dev):
           curl -s \"https://get.sdkman.io\" | bash && sdk install java ${REQUIRED_JAVA}.0.0-tem"
fi

# ── Android SDK ─────────────────────────────────────────────────────────────
# ── Helper: probe common SDK install locations ──────────────────────────────
probe_sdk_locations() {
    local dirs=()
    # Environment variables (prefer ANDROID_HOME, fall back to ANDROID_SDK_ROOT)
    [[ -n "${ANDROID_HOME:-}" ]]       && dirs+=("$ANDROID_HOME")
    [[ -n "${ANDROID_SDK_ROOT:-}" ]]   && dirs+=("$ANDROID_SDK_ROOT")
    # Standard user-land locations
    dirs+=("$HOME/Android/Sdk")
    dirs+=("$HOME/android-sdk")
    dirs+=("$HOME/Android")
    # Linux system locations
    dirs+=("/opt/android-sdk")
    dirs+=("/usr/lib/android-sdk")
    dirs+=("/usr/local/lib/android/sdk")         # GitHub Actions / CI
    dirs+=("/snap/android-studio/current/android-studio")
    # macOS
    dirs+=("$HOME/Library/Android/sdk")
    # Android Studio bundled SDK
    dirs+=("$HOME/android-studio/Sdk")
    # Print the first match that looks like a real SDK (has platform-tools or platforms)
    for d in "${dirs[@]}"; do
        if [[ -d "$d" ]] && { [[ -d "$d/platform-tools" ]] || [[ -d "$d/platforms" ]]; }; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

# ── Helper: extract required SDK versions from project files ─────────────────
# Returns "compileSdk buildToolsVersion" from app/build.gradle.kts
extract_required_sdk_versions() {
    local compile_sdk=""
    local build_tools=""
    if [[ -f app/build.gradle.kts ]]; then
        compile_sdk=$(grep -E '^\s*compileSdk\s*=' app/build.gradle.kts 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)
    fi
    # AGP version → approximate build-tools (AGP 8.5.x → 35.x, 8.4.x → 34.x, etc.)
    if [[ -f gradle/libs.versions.toml ]]; then
        local agp_ver=$(grep -E '^\s*agp\s*=' gradle/libs.versions.toml 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1 || true)
        if [[ -n "$agp_ver" ]]; then
            local agp_minor=$(echo "$agp_ver" | cut -d. -f2)
            # Rough mapping: AGP 8.x → build-tools major = 30 + x (works for 8.0→30 through 8.7→37)
            local bt_major=$((30 + agp_minor))
            build_tools="${bt_major}.0.0"
        fi
    fi
    echo "${compile_sdk:-35} ${build_tools:-35.0.0}"
}

# ── Helper: check SDK component presence ────────────────────────────────────
check_sdk_component() {
    local sdk_dir="$1" component_dir="$2" label="$3"
    if [[ -d "${sdk_dir}/${component_dir}" ]]; then
        local count=$(ls -1 "${sdk_dir}/${component_dir}" 2>/dev/null | grep -v '^\.' | wc -l || true)
        if [[ "$count" -gt 0 ]]; then
            info "  ${label}: ${count} installed"
            return 0
        fi
    fi
    warn "  ${label}: missing or empty"
    return 1
}

# ── Helper: check accepted licenses ─────────────────────────────────────────
check_sdk_licenses() {
    local sdk_dir="$1"
    local license_file="${sdk_dir}/licenses/android-sdk-license"
    if [[ -f "$license_file" ]]; then
        # A valid license file should have a non-empty hash (not just whitespace)
        if grep -q '[0-9a-f]\{16\}' "$license_file" 2>/dev/null; then
            return 0
        fi
    fi
    # Also check the newer cmdline-tools license location
    if [[ -f "${sdk_dir}/licenses/android-sdk-preview-license" ]]; then
        return 0
    fi
    return 1
}

# ── Main SDK detection & validation ─────────────────────────────────────────
SDK_DIR=""
SDK_DIR_SOURCE=""  # track where we found the path for better diagnostics
REQUIRED_VERSIONS=$(extract_required_sdk_versions)
REQUIRED_PLATFORM=$(echo "$REQUIRED_VERSIONS" | cut -d' ' -f1)
REQUIRED_BUILD_TOOLS=$(echo "$REQUIRED_VERSIONS" | cut -d' ' -f2)
info "Required: platform ${REQUIRED_PLATFORM}, build-tools ${REQUIRED_BUILD_TOOLS}"

# Step 1: Read sdk.dir from local.properties (if it exists)
LOCAL_PROPS_SDK=""
if [[ -f local.properties ]]; then
    LOCAL_PROPS_SDK=$(grep -E '^sdk\.dir=' local.properties | head -1 | sed 's/sdk\.dir=//' || true)
fi

# Step 2: Probe all known locations for a working SDK
PROBED_SDK=$(probe_sdk_locations || echo "")

# Step 3: Resolve — prefer local.properties if valid, else ANDROID_HOME, else probed
if [[ -n "$LOCAL_PROPS_SDK" && -d "$LOCAL_PROPS_SDK" ]]; then
    SDK_DIR="$LOCAL_PROPS_SDK"
    SDK_DIR_SOURCE="local.properties"
elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    SDK_DIR="$ANDROID_HOME"
    SDK_DIR_SOURCE="ANDROID_HOME"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    SDK_DIR="$ANDROID_SDK_ROOT"
    SDK_DIR_SOURCE="ANDROID_SDK_ROOT"
elif [[ -n "$PROBED_SDK" ]]; then
    SDK_DIR="$PROBED_SDK"
    SDK_DIR_SOURCE="auto-detected"
fi

# Step 4: Handle stale ANDROID_HOME — env var set but directory doesn't exist
if [[ -n "${ANDROID_HOME:-}" && ! -d "$ANDROID_HOME" ]]; then
    warn "ANDROID_HOME is set but the directory does not exist: ${ANDROID_HOME}"
    if [[ -n "$SDK_DIR" ]]; then
        info "A working SDK was found via ${SDK_DIR_SOURCE}: ${SDK_DIR}"
        # Auto-export the correct ANDROID_HOME so Gradle uses it
        export ANDROID_HOME="$SDK_DIR"
        info "ANDROID_HOME updated → ${SDK_DIR} (for this session)"
        fix "To make this permanent, add to your shell profile (~/.bashrc, ~/.zshrc):"
        fix "  echo 'export ANDROID_HOME=\"${SDK_DIR}\"' >> ~/.bashrc"
    else
        fix "Correct ANDROID_HOME to point to your Android SDK, e.g.:"
        fix "  export ANDROID_HOME=\"\$HOME/Android/Sdk\""
        fix "  # Verify it exists: ls \"\$ANDROID_HOME/platform-tools\""
    fi
    echo ""
fi

# Step 5: Handle ANDROID_HOME not set but SDK found via probing
if [[ -z "${ANDROID_HOME:-}" && -n "$SDK_DIR" ]]; then
    warn "ANDROID_HOME is not set. Gradle may still find the SDK, but it's"
    warn "  recommended to set it explicitly for consistency."
    # Auto-export so Gradle uses the right SDK for this build
    export ANDROID_HOME="$SDK_DIR"
    info "ANDROID_HOME set → ${SDK_DIR} (for this session)"
    fix "Add permanently to your shell profile:"
    fix "  echo 'export ANDROID_HOME=\"${SDK_DIR}\"' >> ~/.bashrc"
    echo ""
fi

# Step 6: Handle stale local.properties — SDK moved but local.properties still points to old path
if [[ -n "$LOCAL_PROPS_SDK" && ! -d "$LOCAL_PROPS_SDK" && -n "$PROBED_SDK" ]]; then
    warn "local.properties points to a non-existent SDK: ${LOCAL_PROPS_SDK}"
    info "Found working SDK at: ${PROBED_SDK}"
    # Auto-repair: update local.properties to the probed location
    if [[ -f local.properties ]]; then
        sed -i "s|^sdk\.dir=.*|sdk.dir=${PROBED_SDK}|" local.properties
    else
        echo "sdk.dir=${PROBED_SDK}" > local.properties
    fi
    info "Updated local.properties → sdk.dir=${PROBED_SDK}"
    SDK_DIR="$PROBED_SDK"
    SDK_DIR_SOURCE="auto-detected (repaired stale local.properties)"
fi

# Step 7: Handle stale local.properties with no alternative found
if [[ -n "$LOCAL_PROPS_SDK" && ! -d "$LOCAL_PROPS_SDK" && -z "$SDK_DIR" ]]; then
    error "Android SDK path in local.properties does not exist: ${LOCAL_PROPS_SDK}"
    case "$OS_ID" in
        ubuntu|debian) FIX_CMD="sudo apt install android-sdk" ;;
        macos)         FIX_CMD="brew install --cask android-commandlinetools" ;;
        *)             FIX_CMD="Install Android Studio from https://developer.android.com/studio" ;;
    esac
    fail_check "Android SDK" \
        "The SDK directory '${LOCAL_PROPS_SDK}' does not exist.
           Update local.properties with the correct path, or:
           ${FIX_CMD}
           Then: export ANDROID_HOME=/path/to/Android/Sdk"
fi

# Step 8: Validate SDK and check component completeness
if [[ -n "$SDK_DIR" && -d "$SDK_DIR" ]]; then
    header "Android SDK found (${SDK_DIR_SOURCE})"
    info "Path: ${SDK_DIR}"

    # Validate essential SDK subdirectories
    SDK_OK=true

    # platform-tools (adb, etc.)
    if [[ -d "${SDK_DIR}/platform-tools" ]]; then
        if [[ -f "${SDK_DIR}/platform-tools/adb" ]]; then
            info "  platform-tools: ✓ (adb available)"
        else
            warn "  platform-tools: directory exists but adb binary missing — '--install' will not work"
            SDK_OK=false
        fi
    else
        warn "  platform-tools: missing — run: sdkmanager \"platform-tools\""
        SDK_OK=false
    fi

    # platforms (required for compilation)
    check_sdk_component "$SDK_DIR" "platforms" "platforms" || SDK_OK=false

    # Check specifically for the required platform version
    if [[ -d "${SDK_DIR}/platforms/android-${REQUIRED_PLATFORM}" ]]; then
        info "  platform android-${REQUIRED_PLATFORM}: ✓"
    else
        warn "  platform android-${REQUIRED_PLATFORM}: missing (required for compileSdk=${REQUIRED_PLATFORM})"
        SDK_OK=false
    fi

    # build-tools (Gradle picks the best available — exact version not required)
    check_sdk_component "$SDK_DIR" "build-tools" "build-tools" || SDK_OK=false

    # List installed versions for visibility (Gradle auto-selects the newest)
    if [[ -d "${SDK_DIR}/build-tools" ]]; then
        bt_versions=$(ls -1 "${SDK_DIR}/build-tools" 2>/dev/null | grep -v '^\.' | tr '\n' ' ' || true)
        info "  build-tools versions: ${bt_versions}"
    fi

    # cmdline-tools (for sdkmanager — needed for auto-install fixes)
    if command -v sdkmanager &>/dev/null; then
        info "  sdkmanager: ✓ (on PATH)"
    elif [[ -d "${SDK_DIR}/cmdline-tools/latest/bin" ]]; then
        info "  sdkmanager: ✓ (${SDK_DIR}/cmdline-tools/latest/bin)"
    elif [[ -n "$(find "${SDK_DIR}/cmdline-tools" -name sdkmanager -type f 2>/dev/null | head -1)" ]]; then
        SM_PATH=$(find "${SDK_DIR}/cmdline-tools" -name sdkmanager -type f 2>/dev/null | head -1 || true)
        info "  sdkmanager: ✓ (${SM_PATH})"
    else
        warn "  sdkmanager: not found — cannot auto-install missing SDK components"
    fi

    # License check
    if check_sdk_licenses "$SDK_DIR"; then
        info "  licenses: ✓ accepted"
    else
        warn "  licenses: not accepted — Gradle may fail"
        warn "    Accept licenses with: yes | ${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager --licenses"
        SDK_OK=false
    fi

    # If components are missing but sdkmanager is available, offer proactive fix
    if ! $SDK_OK; then
        echo ""
        warn "Some SDK components are missing. To install them automatically:"
        fix "${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager --install \\
              \"platforms;android-${REQUIRED_PLATFORM}\" \\
              \"build-tools;${REQUIRED_BUILD_TOOLS}\" \\
              \"platform-tools\" \\
              && yes | ${SDK_DIR}/cmdline-tools/latest/bin/sdkmanager --licenses"
        echo ""
        # In non-CI mode, ask if the user wants to proceed anyway
        # (Gradle can auto-download some components, but missing licenses will block it)
        if ! $CI_MODE && [[ ! -f "${SDK_DIR}/licenses/android-sdk-license" ]]; then
            warn "Without accepted licenses, Gradle will fail. Continuing anyway..."
        fi
    else
        info "SDK components: all present ✓"
    fi

elif [[ -z "${MISSING[*]:-}" || ! "${MISSING[*]}" =~ "Android SDK" ]]; then
    # No SDK found anywhere
    error "Android SDK not found in any standard location."
    if [[ -n "$PROBED_SDK" ]]; then
        info "  (probed: ${PROBED_SDK} — but it doesn't appear to be a valid SDK)"
    fi
    if [[ -n "${ANDROID_HOME:-}" && ! -d "$ANDROID_HOME" ]]; then
        warn "  ANDROID_HOME is set to '${ANDROID_HOME}' but that directory does not exist."
        warn "  Correct ANDROID_HOME or unset it: unset ANDROID_HOME"
    fi
    echo ""
    warn "Checked the following locations:"
    warn "  • ANDROID_HOME:         ${ANDROID_HOME:-<not set>}"
    warn "  • ANDROID_SDK_ROOT:     ${ANDROID_SDK_ROOT:-<not set>}"
    warn "  • \$HOME/Android/Sdk:    $HOME/Android/Sdk"
    warn "  • /opt/android-sdk"
    warn "  • /usr/lib/android-sdk"
    warn "  • \$HOME/Library/Android/sdk (macOS)"
    echo ""
    case "$OS_ID" in
        ubuntu|debian) FIX_CMD="sudo apt install android-sdk" ;;
        macos)         FIX_CMD="brew install --cask android-commandlinetools" ;;
        *)             FIX_CMD="Download Android Studio from https://developer.android.com/studio" ;;
    esac
    fail_check "Android SDK" \
        "Set ANDROID_HOME or create local.properties with sdk.dir=<path>.
           ${FIX_CMD}
         If you already have the SDK installed, find it and set ANDROID_HOME:
           find \$HOME /opt /usr -name 'platform-tools' -type d 2>/dev/null | sed 's|/platform-tools||'
           export ANDROID_HOME=/path/to/Android/Sdk
         Or create local.properties manually:
           echo 'sdk.dir=/path/to/Android/Sdk' > local.properties"
fi

# ── Gradle wrapper ──────────────────────────────────────────────────────────
if [[ -f ./gradlew ]]; then
    if [[ ! -x ./gradlew ]]; then
        warn "gradlew exists but is not executable — fixing permissions"
        chmod +x ./gradlew
    fi
    info "Gradle wrapper: present"
else
    error "gradlew not found."
    fail_check "Gradle Wrapper" \
        "Generate the Gradle wrapper:
                      gradle wrapper --gradle-version 8.12
         Or if 'gradle' itself is not installed:
           ${OS_ID:+${OS_ID} — }use SDKMAN:  sdk install gradle 8.12"
fi

# ── Fail if anything is missing ─────────────────────────────────────────────
if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo ""
    error "${#MISSING[@]} prerequisite(s) missing:"
    for i in "${!MISSING[@]}"; do
        echo ""
        echo -e "  ${RED}✗${NC} ${MISSING[$i]}"
        fix "${FIXES[$i]}"
    done
    echo ""
    exit 1
fi

success "All prerequisites satisfied"

# =============================================================================
# OPTIONAL: LINT
# =============================================================================
if $DO_LINT; then
    header "Running Android lint"
    LINT_REPORT="app/build/reports/lint-results-${VARIANT}.html"
    ./gradlew lint"${VARIANT^}" --no-daemon --warning-mode=all || {
        error "Lint found issues."
        fix "Review the HTML report:
           ${LINT_REPORT}
         To auto-fix some issues:
           ./gradlew lintFix${VARIANT^}"
        exit 1
    }
    info "Lint passed"
fi

# =============================================================================
# OPTIONAL: CLEAN
# =============================================================================
if $DO_CLEAN; then
    header "Cleaning previous build artifacts"
    rm -rf ./app/.cxx
    rm -rf .gradle
    ./gradlew clean --no-daemon
fi

# =============================================================================
# BUILD
# =============================================================================
header "Building ${VARIANT^} APK"
BUILD_START=$SECONDS

# Capitalize first letter for Gradle task name (assembleDebug / assembleRelease)
GRADLE_TASK="assemble${VARIANT^}"

# ── Git hash for versionName stamping ────────────────────────────────────────
GIT_HASH=$(git -C "$SCRIPT_DIR" rev-parse --short=7 HEAD 2>/dev/null || echo "dev")
info "Git hash: ${GIT_HASH}"

./gradlew "$GRADLE_TASK" \
    -PgitHash="$GIT_HASH" \
    --no-daemon \
    --warning-mode=all \
    --build-cache \
    || {
        error "Gradle build failed."
        fix "Common causes and solutions:
           • Out of memory → increase heap in gradle.properties:
               org.gradle.jvmargs=-Xmx4096m
           • SDK/build-tools missing → install via SDK Manager:
               ${SDK_DIR:-$ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\"
           • Dependency resolution failed → check network/proxy:
               ./gradlew $GRADLE_TASK --info            # verbose logs
           • Corrupted Gradle cache → clean and retry:
               rm -rf ~/.gradle/caches/ && ./gradlew clean $GRADLE_TASK"
        exit 1
    }

BUILD_DURATION=$((SECONDS - BUILD_START))

# =============================================================================
# VERIFY & COLLECT ARTIFACT
# =============================================================================
APK_DIR="app/build/outputs/apk/${VARIANT}"
APK_SRC="${APK_DIR}/app-${VARIANT}.apk"
APK_DST="./${APK_NAME}-${VARIANT}.apk"

if [[ ! -f "$APK_SRC" ]]; then
    error "APK not found at ${APK_SRC}"
    error "Contents of ${APK_DIR}:"
    ls -laR "$APK_DIR" 2>/dev/null || echo "  (directory does not exist)"
    exit 1
fi

# ── Move the finished APK into ./release ────────────────────────────────────
RELEASE_DIR="./release"
mkdir -p "$RELEASE_DIR"
mv "$APK_SRC" "${RELEASE_DIR}/${APK_NAME}-${VARIANT}.apk"
APK_DST="${RELEASE_DIR}/${APK_NAME}-${VARIANT}.apk"
info "APK ready: ${APK_DST}"

# ── Build summary ───────────────────────────────────────────────────────────
APK_SIZE=$(du -h "$APK_DST" | cut -f1)
APK_SHA256=$(sha256sum "$APK_DST" 2>/dev/null | cut -d' ' -f1)
if [[ -z "$APK_SHA256" ]]; then
    APK_SHA256=$(shasum -a 256 "$APK_DST" 2>/dev/null | cut -d' ' -f1 || echo "unavailable")
fi

header "Build Summary"
echo "  APK:          ${APK_DST}"
echo "  Size:         ${APK_SIZE}"
echo "  SHA256:       ${APK_SHA256:0:16}..."
echo "  Duration:     ${BUILD_DURATION}s"
echo "  Variant:      ${VARIANT}"
echo ""

success "APK built successfully: ${APK_DST}"

# =============================================================================
# OPTIONAL: INSTALL
# =============================================================================
if $DO_INSTALL; then
    header "Installing APK via ADB"

    if ! command -v adb &>/dev/null; then
        if [[ -n "$SDK_DIR" && -f "${SDK_DIR}/platform-tools/adb" ]]; then
            ADB="${SDK_DIR}/platform-tools/adb"
        else
            error "adb not found."
            fix "Install Android platform-tools:
               • Ubuntu/Debian:  sudo apt install adb
               • macOS:          brew install android-platform-tools
               • Or via SDK Manager:
                 ${SDK_DIR:-$ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \"platform-tools\""
            exit 1
        fi
    else
        ADB="adb"
    fi

    # Check if device is connected
    DEVICES=$("$ADB" devices | tail -n +2 | grep -v '^$' | wc -l)
    if [[ "$DEVICES" -eq 0 ]]; then
        error "No Android device/emulator connected."
        fix "Connect a device via USB or start an emulator:
           • Check USB debugging is enabled on the device
           • Verify connection:  $ADB devices -l
           • Start emulator:     emulator -avd <name>
           • Kill & restart ADB: $ADB kill-server && $ADB start-server"
        exit 1
    fi

    DEVICE_NAME=$("$ADB" devices | tail -n +2 | grep -v '^$' | head -1 | awk '{print $1}')

    info "Installing on ${DEVICE_NAME}..."
    "$ADB" install -r "$APK_DST" || {
        error "ADB install failed."
        fix "Common solutions:
           • App already installed with a different signature → uninstall first:
               $ADB uninstall ${APP_PKG}
           • Insufficient storage on device → free up space
           • Permission denied → check USB debugging authorization on device
           • For more details:  $ADB logcat -d | tail -50"
        exit 1
    }
    success "APK installed on ${DEVICE_NAME}"
fi

echo ""
