# SC-1666 Shortcut update

SC-1666 implementation is complete for review on branch
`codex/sc-1666-linux-capture-contracts`.

Review PR: https://github.com/ikiddoi/ChromaScape/pull/10

Delivered:

- host-qualified host/process/generation frame binding;
- additive `FrameEvent` freshness and identity metadata with existing APIs retained;
- cached/queued stale-frame invalidation across target changes;
- recording readiness pinned to its exact start target;
- debugger pipeline and recording source diagnostics;
- Windows capture-time selection race refusal;
- real non-empty MP4 encode/finalize round trip;
- deterministic same-PID/different-host and generation-change coverage.

Verification:

- focused native/discovery/recording/viewport/launcher suite: 292 tests, 0 failures/errors, 1
  Windows-only skip;
- ordinary suite: 2,425 tests / 111 established failures / 14 skips, with all eight then-current
  SC-1666 tests passing;
- replay suite: 280 tests / same 13 established failures;
- candidate formatting and whitespace: PASS.

Review-required/NOT RUN:

- live two-client/two-Fedora-host routing;
- live remote Builder Goal launch-to-capture readiness;
- Windows runtime regression;
- PR #10 review and merge.

Host-specific checks may be explicitly waived for this proof of concept, but remain NOT RUN rather
than PASS.
