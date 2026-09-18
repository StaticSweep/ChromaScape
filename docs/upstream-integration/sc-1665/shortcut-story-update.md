# SC-1665 Shortcut update

Implementation is complete for deterministic review on branch
`codex/sc-1665-rendered-buffer-capture`.

Implemented:

- active SC-1664 session owns rendered-buffer capture; no second attachment;
- bounded BGRA copy with identity/generation/lifecycle fencing, null-pointer and resize refusal;
- detached BGR images and stride-safe BufferedImage/Mat adapters;
- content-based `NEW`/`CHANGED`/`UNCHANGED`/`PAUSED` metadata;
- existing screen-space `ScreenManager` API retained with corrected clamped cropping;
- Windows GDI code isolated from the Linux facade path;
- deterministic pixel, crop, resize, missing-buffer, freshness, ownership and isolation tests.

Review-required/not yet claimed:

- covered/minimized and paused-rendering behavior on a qualified live client;
- Windows runtime regression;
- PR review and merge.

Verification completed on this host:

- focused integration/screen gate: 174 tests, 0 failures, 1 platform skip;
- ordinary suite: 2,417 / 111 failures / 14 skips, with all 16 added tests passing and the one
  count delta reproduced on the exact base;
- replay suite: 280 / same 13 established environmental failures;
- candidate formatting, changed-file Checkstyle and whitespace: PASS.

The story must remain In Progress until the final verification evidence is reconciled and the PR is
reviewed. Host-specific checks may be explicitly waived for this proof of concept, but must remain
recorded as NOT RUN rather than PASS.
