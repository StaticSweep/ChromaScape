# SC-1664 implementation evidence

Status vocabulary: PASS / FAIL / NOT RUN / BLOCKED.

## Implemented

- Neutral `TargetInputSession`, typed states/diagnostics and explicit client-relative geometry.
- Linux `RemoteInputSession` using the merged, audited SC-1662 interface, resolver and loader.
- Exact-PID enumeration, bounded injection/pair/native calls and poison-on-timeout semantics.
- Identity-scoped attachment leases acquired before all attachment side effects.
- Selection/identity/generation fencing before attachment and every operation.
- Windows KInput adapter with post-construction fencing and cleanup-safe lease ownership.
- Held-key/button cleanup, retryable deterministic cleanup and idempotent success paths.
- Atomic controller startup, idempotent shutdown and session-backed mouse/keyboard utilities.
- Environment example entries for the disabled-by-default feature and explicit artifact root.

## Current gate status

- Correct merged SC-1662/SC-1663 integration base: PASS (`cc9de8c7`).
- Java compilation and focused fake-native/session tests: PASS.
- Source isolation and runtime-free compiled reference checks: PASS.
- Changed-file Checkstyle and formatting: PASS; repository-wide warnings remain baseline debt.
- Disposable live Micro6/RuneLite attachment and input: NOT RUN — no qualified client is running.
- Real 25-cycle native lifecycle smoke: NOT RUN; the unit seam covers 25 cycles without native code.
- Windows runtime/KInput verification: NOT RUN — must be run later on Windows.
- Combined SC-1662/SC-1663/SC-1664 and launcher-focused gate: PASS (213 tests, 1 expected
  Windows-only skip).
- Ordinary comparison: PASS for regression identity. Final candidate ran 2,401 tests with 110
  failures and 14 skips; exact base ran 2,383 with the same 110 failures and 14 skips. All 18 added
  tests pass. A process-cleanup timing assertion seen in an earlier run passed both its isolated
  rerun and the final full run.
- Replay comparison: PASS for regression identity. Candidate and exact base both ran 280 tests with
  the same 13 environment/Windows-path failures.
- Shortcut update text: PASS. PR review and merge: NOT RUN.

No native binary, credential, raw process listing or machine-specific absolute path is committed as
evidence.
