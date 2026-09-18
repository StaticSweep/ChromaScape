# SC-1665 implementation evidence

## Implemented surface

- `RemoteInputSession` implements `TargetFrameSource` and performs bounded capture on the same
  synchronized native lane as input and close.
- `TargetFrameRuntime` publishes exactly one Controller-owned source and uses compare-and-set
  uninstall semantics.
- `RemoteInputFrameDecoder` validates dimensions and converts top-down BGRA to detached BGR.
- `FrameFreshnessTracker` reports content-derived state without retaining image buffers.
- `FrameImageAdapter` provides detached BufferedImage/Mat conversion and is used by
  `TemplateMatching`.
- `ScreenManager` remains the public compatibility facade. Linux consumes rendered frames;
  Windows GDI implementation moved to `WindowsGdiScreenCapture`.
- Stable capture diagnostics distinguish invalid dimensions, unavailable buffers and resize races.

## Deterministic verification

- Combined upstream-integration and screen-consumer suite: 174 tests, 0 failures, 0 errors, one
  expected platform skip.
- Known-pixel decoder and BufferedImage/Mat round-trip: PASS.
- Null pointer, invalid dimensions, malformed length and resize-race refusal: PASS.
- Freshness transitions, runtime source ownership, coordinate/crop behavior and Linux Win32
  isolation: PASS.
- Candidate Google Java Format dry-run, changed-file Checkstyle scan and `git diff --check`: PASS.

## Full-suite comparison

- Ordinary `test`: 2,417 tests, 111 failures, 14 skips. SC-1664's candidate baseline was 2,401 / 110
  / 14; all 16 added tests pass. The additional real-process cleanup timing failure reproduces on
  exact base `6b17b722` and is already documented as nondeterministic.
- `replayTest`: 280 tests, 13 failures, matching the established environmental failure identities.
- No failure names an SC-1665 class or behavior.

## Honest limitations

- The pinned RemoteInput ABI exposes no renderer read lock. The backing mapping is lifetime-stable,
  and dimensions are fenced around the copy, but pixels are a point-in-time observation rather
  than a render-transaction snapshot.
- Covered/minimized capture and live `PAUSED` classification are NOT RUN because no qualified live
  client was attached for this implementation pass.
- Windows runtime verification is NOT RUN on this Linux host. Windows GDI behavior is preserved by
  extraction and compile/focused tests, not claimed as a live Windows pass.
- [PR #9](https://github.com/ikiddoi/ChromaScape/pull/9) is open. Review and merge are NOT RUN; the
  Shortcut story remains In Progress and review-required.
