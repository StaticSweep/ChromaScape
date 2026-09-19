#!/usr/bin/env bash
#
# SC-1668 source-level regression for the corrected Linux window-selection decision logic.
#
# The native decision logic cannot be exercised without a live X11/JNI target, so this guards the
# corrected structure directly in the pinned source:
#   1. the enumeration callback collects every matching target-owned window and never stops early;
#   2. candidate windows are ordered deterministically (non-skip-taskbar before skip-taskbar);
#   3. the reflection probe tries every candidate until one produces a usable java.awt.Frame;
#   4. attachment diagnostics are opt-in and emitted only from the detached thread path.
#
# Exit: 0 when every assertion holds, 1 otherwise.

set -u

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
SOURCE="$REPO_ROOT/third-party/RemoteInput/RemoteInput/Platform/Platform_Linux.cxx"

FAILURES=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

assert_present() { # $1 = pattern, $2 = name
  if grep -Eq "$1" "$SOURCE"; then
    pass "$2"
  else
    fail "$2 (pattern not found: $1)"
  fi
}

assert_absent() { # $1 = pattern, $2 = name
  if grep -Eq "$1" "$SOURCE"; then
    fail "$2 (unexpected pattern: $1)"
  else
    pass "$2"
  fi
}

[ -f "$SOURCE" ] || { echo "source not found: $SOURCE" >&2; exit 1; }

# 1. The callback collects into a vector and always continues the enumeration.
assert_present 'reinterpret_cast<std::vector<Window>\*>\(other\)->push_back\(window\)' \
  'enumeration callback collects every matching window'
assert_absent '\*reinterpret_cast<Window\*>\(other\) = window' \
  'enumeration callback no longer stops at the first match'
assert_present 'return true;  // always keep enumerating' \
  'enumeration callback keeps enumerating after a match'

# 2. Deterministic ordering: non-skip-taskbar windows first.
assert_present 'std::stable_sort\(windows.begin\(\), windows.end\(\), \[&\]\(Window left, Window right\)' \
  'candidate windows are ordered with a stable sort'
assert_present '!IsSkipTaskbar\(display, left\) && IsSkipTaskbar\(display, right\)' \
  'ordering prefers non-skip-taskbar windows'

# 3. Every candidate is tried until Reflection::Create succeeds.
assert_present 'for \(Window windowFrame : GetMainWindows\(\)\)' \
  'reflection probe iterates every candidate window'
assert_present 'reflection = Reflection::Create\(frame\);' \
  'reflection is created per candidate frame'

# 4. Diagnostics are opt-in and gated.
assert_present 'REMOTEINPUT_ATTACH_DIAGNOSTICS' \
  'attachment diagnostics are opt-in via an environment variable'
assert_present 'Diagnose\("GetNativeReflector: entered"\)' \
  'detached reflection entry stage is diagnosed'

if [ "$FAILURES" -eq 0 ]; then
  echo "REMOTEINPUT_WINDOW_SELECTION_REGRESSIONS status=PASS"
  exit 0
fi
echo "REMOTEINPUT_WINDOW_SELECTION_REGRESSIONS status=FAIL failures=$FAILURES" >&2
exit 1
