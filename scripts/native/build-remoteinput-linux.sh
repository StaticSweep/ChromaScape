#!/usr/bin/env bash
#
# SC-1662 — build the pinned RemoteInput Linux shared library on Fedora x86_64.
#
# This script:
#   * preflights every native prerequisite and reports what is missing;
#   * verifies the pinned source provenance before compiling anything;
#   * configures and builds the pinned CMake target out-of-tree;
#   * validates the resulting ELF artifact and its required exports;
#   * writes a machine-readable provenance manifest through a JSON serializer;
#   * re-validates the library/manifest pair, then publishes it under build/dist by switching a
#     single symlink onto an immutable, content-addressed copy of the pair.
#
# It deliberately does NOT:
#   * install packages, run as root, or elevate privileges;
#   * change ptrace restrictions, apply setcap, or disable sandboxing;
#   * download any source or binary dependency.
#
# Usage:
#   scripts/native/build-remoteinput-linux.sh [--jobs N] [--repeat-build]
#                                            [--patch-declaration FILE] [--no-stage]
#
# --jobs N            Parallel build jobs. Positive integer in 1..256. Defaults to min(nproc, 4).
# --repeat-build      Perform a second clean build and record both hashes.
# --patch-declaration Documented local native patch (JSON); required if the working tree differs
#                     from the pinned import. Its changedPaths/addedPaths/removedPaths must equal
#                     the measured change set exactly, and patchIdentity must equal the derived
#                     identity (the helper prints the expected value when it does not match).
#                     Without it, any drift fails the build.
# --no-stage          Build and validate without publishing to build/dist.
#
# Exit codes: 2 on usage error, non-zero on any provenance, preflight, configure, build,
# validation, manifest or staging failure.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
HELPER="$SCRIPT_DIR/remoteinput_metadata.py"

SRC_DIR="$REPO_ROOT/third-party/RemoteInput"
BUILD_DIR="$REPO_ROOT/build/native/remoteinput/linux-x86_64"
REPEAT_BUILD_DIR="$REPO_ROOT/build/native/remoteinput/linux-x86_64-repeat"
INTERMEDIATE_DIR="$REPO_ROOT/build/native/remoteinput"
PROVENANCE_FILE="$INTERMEDIATE_DIR/provenance.json"
BUILD_FACTS_FILE="$INTERMEDIATE_DIR/build-facts.txt"
# Published layout: an immutable, content-addressed payload directory plus the symlink that
# consumers resolve. See stage_artifact for why the switch has to be a symlink rename.
STAGE_PARENT="$REPO_ROOT/build/dist/native"
STAGE_VERSIONS_DIR="$STAGE_PARENT/.versions"
STAGE_DIR="$STAGE_PARENT/linux-x86_64"

PINNED_SOURCE_REPOSITORY="https://github.com/StaticSweep/ChromaScape"
PINNED_SOURCE_REVISION="5ea3b995f5d13e19c592a6399973fe7dadb5e656"
CMAKE_MIN_VERSION="3.15"
PYTHON_MIN_VERSION="3.8"
MIN_JOBS=1
MAX_JOBS=256

# Exported C symbols this story binds and therefore requires. Derived from
# RemoteInput/EIOS.hxx plus EIOS_Inject_PID (declared in Plugin/NativePlugin.hxx).
# NOTE: the native symbol is EIOS_IsMouseButtonHeld; there is no EIOS_IsMouseHeld.
# Mirrored by RemoteInputLibraryLoader.REQUIRED_SYMBOLS; keep both in step.
REQUIRED_SYMBOLS=(
  EIOS_Inject_PID
  EIOS_RequestTarget
  EIOS_ReleaseTarget
  EIOS_GetTargetDimensions
  EIOS_GetImageBuffer
  EIOS_GetDebugImageBuffer
  EIOS_HasFocus
  EIOS_GainFocus
  EIOS_LoseFocus
  EIOS_IsKeyboardInputEnabled
  EIOS_SetKeyboardInputEnabled
  EIOS_GetMousePosition
  EIOS_MoveMouse
  EIOS_HoldMouse
  EIOS_ReleaseMouse
  EIOS_ScrollMouse
  EIOS_IsMouseButtonHeld
  EIOS_SendString
  EIOS_HoldKey
  EIOS_ReleaseKey
  EIOS_IsKeyHeld
  EIOS_GetClients
  EIOS_GetClientPID
)

JOBS=""
DO_REPEAT=0
DO_STAGE=1
PATCH_DECLARATION=""

usage() {
  cat <<'USAGE'
Usage:
  scripts/native/build-remoteinput-linux.sh [--jobs N] [--repeat-build]
                                           [--patch-declaration FILE] [--no-stage]

  --jobs N              Parallel build jobs. Positive integer in 1..256.
                        Defaults to min(nproc, 4).
  --repeat-build        Perform a second clean build and record both hashes.
  --patch-declaration F Documented local native patch (JSON); required if the working tree
                        differs from the pinned import. changedPaths/addedPaths/removedPaths
                        must equal the measured change set exactly, and patchIdentity must
                        equal the derived identity (printed by the helper on mismatch).
                        Without it, any drift fails the build before it starts.
  --no-stage            Build and validate without publishing to build/dist.

Exit codes: 2 on usage error, non-zero on any provenance, preflight, configure, build,
validation, manifest or staging failure.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --jobs=*) JOBS="${1#--jobs=}"; shift ;;
    --jobs) shift; [ "$#" -gt 0 ] || { echo "--jobs requires a value" >&2; exit 2; }; JOBS="$1"; shift ;;
    --repeat-build) DO_REPEAT=1; shift ;;
    --patch-declaration=*) PATCH_DECLARATION="${1#--patch-declaration=}"; shift ;;
    --patch-declaration) shift; [ "$#" -gt 0 ] || { echo "--patch-declaration requires a value" >&2; exit 2; }; PATCH_DECLARATION="$1"; shift ;;
    --no-stage) DO_STAGE=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Parallelism is validated before anything else runs. Negative and non-numeric values are
# rejected (they are not digits), and 0 or an out-of-range value is rejected explicitly.
if [ -n "$JOBS" ]; then
  case "$JOBS" in
    *[!0-9]*) echo "--jobs must be a positive integer in $MIN_JOBS..$MAX_JOBS (got '$JOBS')" >&2; exit 2 ;;
  esac
  if [ "$JOBS" -lt "$MIN_JOBS" ] || [ "$JOBS" -gt "$MAX_JOBS" ]; then
    echo "--jobs must be in $MIN_JOBS..$MAX_JOBS (got '$JOBS')" >&2; exit 2
  fi
else
  JOBS="$( (nproc 2>/dev/null || echo 2) )"
  [ "$JOBS" -gt 4 ] && JOBS=4
  [ "$JOBS" -lt "$MIN_JOBS" ] && JOBS="$MIN_JOBS"
fi

log()  { printf '[sc-1662] %s\n' "$*"; }
die()  { printf '[sc-1662] ERROR: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

version_ge() { # version_ge <actual> <minimum>
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" = "$2" ]
}

helper() { python3 -B "$HELPER" "$@"; }

# --------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------
detect_versions=""
fact() { printf '%s\n' "$detect_versions" | sed -n "s/^$1=//p"; }
CXX_BIN=""

preflight() {
  [ -d "$SRC_DIR" ] || die "vendored sources missing: $SRC_DIR"

  [ "$(uname -s)" = "Linux" ] || die "unsupported host OS: $(uname -s) (Linux required)"
  [ "$(uname -m)" = "x86_64" ] || die "unsupported host architecture: $(uname -m) (x86_64 required)"

  have cmake || die "cmake not found. Fedora: 'cmake'"
  local cmake_version
  cmake_version="$(cmake --version | head -1 | awk '{print $3}')"
  version_ge "$cmake_version" "$CMAKE_MIN_VERSION" \
    || die "cmake $cmake_version is older than required $CMAKE_MIN_VERSION"

  have make || die "make not found (selected generator: Unix Makefiles). Fedora: 'make'"

  local cxx
  cxx="${CXX:-$(command -v g++ || command -v c++ || true)}"
  have "$cxx" || die "no C++ compiler found. Fedora: 'gcc-c++'"
  printf 'int main(){return 0;}\n' | "$cxx" -std=c++20 -x c++ - -fsyntax-only \
    || die "C++ compiler '$cxx' does not support -std=c++20"
  local cxx_version
  cxx_version="$("$cxx" --version | head -1)"
  # Pinned for the configure step and re-checked against CMakeCache.txt afterwards, so the
  # preflight is meaningful rather than an assumption about what CMake will select.
  CXX_BIN="$(command -v "$cxx")"

  have as || die "GNU assembler 'as' not found (required for kubo_injector shellcode.S). Fedora: 'binutils'"

  # JDK 17 + JNI headers. The toolchain provided by Gradle is the only supported source of
  # JAVA_HOME; the daemon's own JVM is not necessarily Java 17.
  [ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is not set; a JDK 17 with JNI headers is required"
  [ -x "$JAVA_HOME/bin/javac" ] || die "JAVA_HOME does not contain a JDK (no bin/javac): $JAVA_HOME"
  local java_version
  java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  case "$java_version" in
    *'"17'*) : ;;
    *) die "JDK 17 required, found: $java_version" ;;
  esac
  [ -f "$JAVA_HOME/include/jni.h" ] || die "JNI headers missing: $JAVA_HOME/include/jni.h (Fedora: 'java-17-openjdk-devel')"
  [ -f "$JAVA_HOME/include/linux/jni_md.h" ] || die "JNI platform headers missing under $JAVA_HOME/include/linux"

  # Python development files (pinned CMake enables PYTHON_BINDINGS).
  local python_version python_include
  python_version="$(python3 --version 2>&1 | awk '{print $2}')"
  version_ge "$python_version" "$PYTHON_MIN_VERSION" \
    || die "python3 $python_version is older than required $PYTHON_MIN_VERSION"
  python_include="$(python3 -c 'import sysconfig;print(sysconfig.get_paths()["include"])')"
  [ -f "$python_include/Python.h" ] \
    || die "Python development headers missing at $python_include (Fedora: 'python3-devel')"

  # Artifact inspection / hashing tooling. ldd is required because the GL/X11 link probe below and
  # the built artifact both have their dependencies resolved with it.
  for tool in file sha256sum readelf nm ldd; do
    have "$tool" || die "'$tool' not found. Fedora: 'file' / 'binutils' / 'coreutils'"
  done

  # OpenGL and X11 headers; the link libraries are proved separately below.
  local gl_h="" x11_h=""
  for d in /usr/include /usr/local/include; do
    [ -f "$d/GL/gl.h" ] && gl_h="$d/GL/gl.h"
    [ -f "$d/X11/Xlib.h" ] && x11_h="$d/X11/Xlib.h"
  done
  [ -n "$gl_h" ] || die "OpenGL headers missing (GL/gl.h). Fedora: 'mesa-libGL-devel'"
  [ -n "$x11_h" ] || die "X11 headers missing (X11/Xlib.h). Fedora: 'libX11-devel'"

  # Headers alone do not prove the linker can find -lGL/-lX11, which is what the CMake build
  # needs. Link a throwaway translation unit with the already-validated compiler and then resolve
  # the result with ldd, so a missing link library is reported here rather than by CMake.
  local probe="$INTERMEDIATE_DIR/.sc1662-link-probe"
  rm -f -- "$probe"
  if ! printf 'int main(){return 0;}\n' |
    "$CXX_BIN" -std=c++20 -x c++ - -o "$probe" -lGL -lX11; then
    rm -f -- "$probe"
    die "OpenGL/X11 link libraries are not linkable (-lGL -lX11). Fedora: 'mesa-libGL-devel libX11-devel'"
  fi
  local probe_unresolved
  probe_unresolved="$(ldd "$probe" 2>&1 | grep 'not found' || true)"
  rm -f -- "$probe"
  if [ -n "$probe_unresolved" ]; then
    printf '%s\n' "$probe_unresolved" | sed 's/^/[sc-1662] ERROR: unresolved dependency: /' >&2
    die "the OpenGL/X11 link probe has unresolved runtime dependencies"
  fi
  log "OpenGL/X11 headers and link libraries verified"

  detect_versions="$(cat <<EOF
cmake=${cmake_version}
cxx=${cxx_version}
cxx_compiler=${cxx}
generator=Unix Makefiles
jobs=${JOBS}
java=${java_version}
java_home=${JAVA_HOME}
python=${python_version}
python_include=${python_include}
EOF
)"
  log "preflight OK"
  printf '%s\n' "$detect_versions" | sed 's/^/[sc-1662]   /'
}

# --------------------------------------------------------------------------
# Source provenance — must pass before anything is compiled
# --------------------------------------------------------------------------
require_helper() {
  [ -f "$HELPER" ] || die "metadata helper missing: $HELPER"
  have python3 || die "python3 not found. Fedora: 'python3-devel'"
}

verify_source_provenance() {
  log "verifying pinned source provenance against import-manifest.sha256"
  mkdir -p "$INTERMEDIATE_DIR"

  local args=(
    verify-sources
    --repo-root "$REPO_ROOT"
    --source-repository "$PINNED_SOURCE_REPOSITORY"
    --source-revision "$PINNED_SOURCE_REVISION"
    --output "$PROVENANCE_FILE"
  )
  if [ -n "$PATCH_DECLARATION" ]; then
    [ -f "$PATCH_DECLARATION" ] || die "patch declaration not found: $PATCH_DECLARATION"
    args+=(--patch-declaration "$PATCH_DECLARATION")
  fi

  helper "${args[@]}" | sed 's/^/[sc-1662]   /' \
    || die "source provenance verification failed; refusing to build unverified sources"
}

# --------------------------------------------------------------------------
# Configure and build
# --------------------------------------------------------------------------
configure_and_build() { # $1 = build dir
  local bdir="$1"
  # Stale-output prevention: never build on top of a previous tree.
  rm -rf "$bdir"
  mkdir -p "$bdir"

  log "configuring (Release) -> $bdir"
  # CMAKE_CXX_COMPILER pins the compiler the preflight actually validated.
  # Python_EXECUTABLE is the variable FindPython honours; PYTHON_EXECUTABLE is kept for any
  # legacy find_package(PythonInterp) path. Which one CMake used is confirmed afterwards from
  # CMakeCache.txt, never assumed.
  cmake -S "$SRC_DIR" -B "$bdir" \
    -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=20 \
    -DCMAKE_CXX_COMPILER="$CXX_BIN" \
    -DJAVA_HOME="$JAVA_HOME" \
    -DJava_JAVA_EXECUTABLE="$JAVA_HOME/bin/java" \
    -DJava_JAVAC_EXECUTABLE="$JAVA_HOME/bin/javac" \
    -DPython_EXECUTABLE="$(command -v python3)" \
    -DPYTHON_EXECUTABLE="$(command -v python3)"

  log "building target 'RemoteInput' (parallel=$JOBS)"
  cmake --build "$bdir" --target RemoteInput --parallel "$JOBS"
}

cache_value() { # $1 = CMakeCache.txt, $2 = key
  sed -n "s/^$2:[^=]*=//p" "$1" | head -1
}

resolved_cxx=""
resolved_cxx_version=""
resolved_python=""

# The preflight is only meaningful if CMake then selected the tools it validated.
confirm_cmake_selection() { # $1 = build dir
  local cache="$1/CMakeCache.txt"
  [ -f "$cache" ] || die "CMake configuration cache not found: $cache"

  resolved_cxx="$(cache_value "$cache" CMAKE_CXX_COMPILER)"
  resolved_cxx_version="$(cache_value "$cache" CMAKE_CXX_COMPILER_VERSION)"
  resolved_python="$(cache_value "$cache" Python_EXECUTABLE)"
  [ -n "$resolved_python" ] || resolved_python="$(cache_value "$cache" PYTHON_EXECUTABLE)"

  [ -n "$resolved_cxx" ] || die "CMake did not record CMAKE_CXX_COMPILER in $cache"
  [ -n "$resolved_python" ] || die "CMake did not record a Python interpreter in $cache"

  if [ "$(readlink -f -- "$resolved_cxx")" != "$(readlink -f -- "$CXX_BIN")" ]; then
    die "CMake selected C++ compiler '$resolved_cxx' but preflight validated '$CXX_BIN'"
  fi
  if [ "$(readlink -f -- "$resolved_python")" != "$(readlink -f -- "$(command -v python3)")" ]; then
    die "CMake selected Python '$resolved_python' but preflight validated '$(command -v python3)'"
  fi

  log "CMake selected cxx=$resolved_cxx (${resolved_cxx_version:-unknown}) python=$resolved_python"
}

artifact_path() { printf '%s/libRemoteInput.so' "$1"; }

# --------------------------------------------------------------------------
# Validation
# --------------------------------------------------------------------------
validate_artifact() { # $1 = .so path
  local so="$1"
  [ -f "$so" ] || die "artifact not produced: $so"
  [ -s "$so" ] || die "artifact is empty: $so"

  if ! file -b "$so" | grep -q 'ELF 64-bit LSB shared object'; then
    die "artifact is not an ELF 64-bit shared object: $(file -b "$so")"
  fi
  if ! file -b "$so" | grep -q 'x86-64'; then
    die "artifact is not x86-64: $(file -b "$so")"
  fi

  local missing=0 sym
  local exported
  exported="$(nm -D --defined-only "$so" | awk '{print $3}')"
  for sym in "${REQUIRED_SYMBOLS[@]}"; do
    if ! printf '%s\n' "$exported" | grep -qx "$sym"; then
      printf '[sc-1662] ERROR: required symbol missing: %s\n' "$sym" >&2
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] || die "$missing required symbol(s) missing from $so"

  # Resolve the declared dependencies now rather than deferring every load-time failure to
  # verifyRemoteInputLinux: an unresolvable dependency is a build-environment defect, and it is
  # reported here with the offending library named.
  local unresolved
  unresolved="$(ldd "$so" 2>&1 | grep 'not found' || true)"
  if [ -n "$unresolved" ]; then
    printf '%s\n' "$unresolved" | sed 's/^/[sc-1662] ERROR: unresolved dependency: /' >&2
    die "artifact has unresolved shared-library dependencies"
  fi

  log "declared dependencies:"; readelf -d "$so" | grep NEEDED | sed 's/^/[sc-1662]   /' || true
  log "all declared dependencies resolve"
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

pair_identity() { # $1 = artifact, $2 = manifest
  # The published pair is identified by both files: a byte-identical rebuild still produces a
  # different manifest (generatedAtUtc at minimum), and that manifest must not be discarded in
  # favour of the one already on disk. Mirrored by StageRemoteInputLinuxTask.pairIdentity.
  printf '%s\0%s' "$(sha256_of "$1")" "$(sha256_of "$2")" | sha256sum | awk '{print $1}'
}

# --------------------------------------------------------------------------
# Manifest
# --------------------------------------------------------------------------
# Measured build facts are handed to the helper as a key=value sidecar. No measured value is
# ever interpolated into JSON by this script -- that is what produced invalid JSON when a JDK
# version string contained quotes.
collect_build_facts() { # $1 = .so path, $2 = repeat sha or ""
  local so="$1" repeat_sha="$2"
  mkdir -p "$INTERMEDIATE_DIR"

  {
    printf 'generatedAtUtc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'hostOs=%s\n' "$(uname -s | tr '[:upper:]' '[:lower:]')"
    printf 'hostArch=%s\n' "$(uname -m)"
    printf 'hostPlatform=%s-%s\n' "$(uname -s | tr '[:upper:]' '[:lower:]')" "$(uname -m)"
    printf 'cxxPath=%s\n' "${resolved_cxx:-$(fact 'cxx_compiler')}"
    printf 'cxxVersion=%s\n' "${resolved_cxx_version:-$(fact 'cxx')}"
    printf 'cxxStandard=20\n'
    printf 'cmakeVersion=%s\n' "$(fact 'cmake')"
    printf 'cmakeGenerator=%s\n' "$(fact 'generator')"
    printf 'jdkHome=%s\n' "$JAVA_HOME"
    printf 'jdkVersion=%s\n' "$(fact 'java')"
    printf 'jdkMajorVersion=17\n'
    printf 'pythonExecutable=%s\n' "${resolved_python:-$(command -v python3)}"
    printf 'pythonVersion=%s\n' "$(fact 'python')"
    printf 'pythonInclude=%s\n' "$(fact 'python_include')"
    printf 'buildType=Release\n'
    printf 'sourceDir=%s\n' "$SRC_DIR"
    printf 'buildDir=%s\n' "$(dirname -- "$so")"
    printf 'parallelism=%s\n' "$JOBS"

    local sym
    for sym in "${REQUIRED_SYMBOLS[@]}"; do printf 'requiredSymbol=%s\n' "$sym"; done

    nm -D --defined-only "$so" | awk '{print $3}' \
      | grep -E '^(EIOS_|SimbaPlugin)' | sort -u \
      | while IFS= read -r sym; do [ -n "$sym" ] && printf 'exportedSymbol=%s\n' "$sym"; done

    readelf -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | sort -u \
      | while IFS= read -r sym; do [ -n "$sym" ] && printf 'declaredDependency=%s\n' "$sym"; done

    if [ -n "$repeat_sha" ]; then
      printf 'repeatSha256=%s\n' "$repeat_sha"
      if [ "$repeat_sha" = "$(sha256_of "$so")" ]; then
        printf 'byteIdentical=true\n'
      else
        printf 'byteIdentical=false\n'
      fi
    fi
  } > "$BUILD_FACTS_FILE"
}

write_manifest() { # $1 = .so path, $2 = output path, $3 = repeat sha or ""
  local so="$1" out="$2" repeat_sha="${3:-}"
  collect_build_facts "$so" "$repeat_sha"
  helper write-manifest \
    --build-facts "$BUILD_FACTS_FILE" \
    --provenance "$PROVENANCE_FILE" \
    --artifact "$so" \
    --output "$out" \
    | sed 's/^/[sc-1662]   /' \
    || die "manifest generation or validation failed"
}

# --------------------------------------------------------------------------
# Staging
# --------------------------------------------------------------------------
# The pair is published as an immutable, content-addressed payload directory and exposed by
# switching a single symlink onto it. Only the symlink swap is the visible publish step, and a
# symlink rename is one atomic filesystem operation, so an interrupted stage cannot leave a new
# library beside an old manifest. The previously published payload is never touched before the
# swap succeeds.
stage_artifact() { # $1 = .so path, $2 = manifest path
  [ "$DO_STAGE" -eq 1 ] || { log "staging skipped (--no-stage)"; return 0; }
  local so="$1" manifest="$2"

  helper verify-manifest --manifest "$manifest" --artifact "$so" \
    | sed 's/^/[sc-1662]   /' \
    || die "refusing to stage: manifest and artifact do not form a valid pair"

  mkdir -p "$STAGE_VERSIONS_DIR"
  local identity payload tmp
  identity="$(pair_identity "$so" "$manifest")"
  payload="$STAGE_VERSIONS_DIR/$identity"

  if [ -d "$payload" ]; then
    # Already published: re-validate what is actually there instead of trusting it, and prove it is
    # the pair we were asked to publish. A library that validates against its own sibling manifest
    # is not enough on its own: a modified but internally consistent pair could otherwise be reused
    # under this pair's directory name.
    helper verify-manifest --manifest "$payload/remoteinput-manifest.json" \
      --artifact "$payload/libRemoteInput.so" >/dev/null \
      || die "refusing to stage: the existing payload $payload no longer validates"
    [ "$(pair_identity "$payload/libRemoteInput.so" "$payload/remoteinput-manifest.json")" = "$identity" ] \
      || die "refusing to stage: the existing payload $payload validates but is not the expected pair $identity"
  else
    tmp="$(mktemp -d "$STAGE_PARENT/.sc1662-stage.XXXXXX")"
    # Remove the temporary staging directory on any exit, including a later `die`.
    trap "rm -rf -- '$tmp'" EXIT

    install -m 0644 "$so" "$tmp/libRemoteInput.so"
    install -m 0644 "$manifest" "$tmp/remoteinput-manifest.json"

    # Validate what actually landed in the temporary directory, not what we intended to copy, and
    # prove the copy still carries the expected identity before publishing it.
    helper verify-manifest --manifest "$tmp/remoteinput-manifest.json" \
      --artifact "$tmp/libRemoteInput.so" >/dev/null \
      || die "refusing to stage: the copied pair failed re-validation"
    [ "$(pair_identity "$tmp/libRemoteInput.so" "$tmp/remoteinput-manifest.json")" = "$identity" ] \
      || die "refusing to stage: the copied pair did not reproduce the expected identity $identity"

    chmod 0755 "$tmp"
    # rename(2) of a directory: the payload either exists complete or does not exist at all.
    mv -T -- "$tmp" "$payload" \
      || die "refusing to stage: could not publish the payload directory atomically"
    trap - EXIT
  fi

  # The previous publish layout used a real directory. It is deliberately NOT deleted automatically:
  # an interruption between deletion and the symlink switch would remove the previous publish point
  # and leave none at all.
  if [ -e "$STAGE_DIR" ] && [ ! -L "$STAGE_DIR" ]; then
    die "refusing to publish: $STAGE_DIR is a real directory, not the versioned publish symlink. Remove it explicitly (rm -rf '$STAGE_DIR') and re-run."
  fi

  # Captured before the swap: the payload being replaced is retained by identity, not by timestamp,
  # so republishing an older payload cannot prune its own predecessor.
  local previous=""
  if [ -L "$STAGE_DIR" ]; then
    previous="$(basename "$(readlink "$STAGE_DIR")")"
  fi

  # The visible publish step: create the link under a temporary name, then rename it over the
  # switch point. A new library can never be visible with an old manifest.
  local tmp_link="$STAGE_PARENT/.linux-x86_64.$$"
  rm -f -- "$tmp_link"
  ln -s -- ".versions/$identity" "$tmp_link"
  if ! mv -T -- "$tmp_link" "$STAGE_DIR"; then
    rm -f -- "$tmp_link"
    die "refusing to stage: could not switch $STAGE_DIR atomically"
  fi

  # Keep the published pair and the payload it displaced; discard everything older, so a failed
  # stage always has a previous complete pair to fall back to.
  local candidate name
  for candidate in "$STAGE_VERSIONS_DIR"/*/; do
    [ -d "$candidate" ] || continue
    name="$(basename "$candidate")"
    [ "$name" = "$identity" ] && continue
    [ -n "$previous" ] && [ "$name" = "$previous" ] && continue
    rm -rf -- "$candidate"
  done

  log "published: $STAGE_DIR -> .versions/$identity"
}

main() {
  # Cheapest and most fundamental gate first: never even look for a compiler when the tree
  # that would be compiled does not match the pinned import.
  require_helper
  verify_source_provenance
  preflight
  configure_and_build "$BUILD_DIR"
  confirm_cmake_selection "$BUILD_DIR"
  local so; so="$(artifact_path "$BUILD_DIR")"
  validate_artifact "$so"

  local repeat_sha=""
  if [ "$DO_REPEAT" -eq 1 ]; then
    log "repeat clean build for hash comparison"
    configure_and_build "$REPEAT_BUILD_DIR"
    confirm_cmake_selection "$REPEAT_BUILD_DIR"
    local so2; so2="$(artifact_path "$REPEAT_BUILD_DIR")"
    validate_artifact "$so2"
    repeat_sha="$(sha256_of "$so2")"
    if [ "$repeat_sha" = "$(sha256_of "$so")" ]; then
      log "repeat build byte-identical"
    else
      log "repeat build differs (recorded; cross-build byte identity is not required)"
    fi
  fi

  local manifest="$BUILD_DIR/remoteinput-manifest.json"
  write_manifest "$so" "$manifest" "$repeat_sha"
  stage_artifact "$so" "$manifest"

  log "sha256=$(sha256_of "$so")"
  log "size=$(stat -c %s "$so")"
  log "done"
}

# Only run when executed, not when sourced. The staging regressions source this file to exercise
# `stage_artifact` directly against isolated paths; `main` must not fire in that case.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
