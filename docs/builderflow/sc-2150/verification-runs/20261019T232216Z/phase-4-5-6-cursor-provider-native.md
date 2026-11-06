# SC-2150 Phases 4-6 — cursor, provider, native regression

Run ID: `20261019T232216Z`

## Phase 4 — cursor and analysis qualification

| Prerequisite | Observation | State |
|---|---|---|
| Cursor tracking capability | `GET /capabilities` → `features.cursorTrackingAvailable=false`; unknown: "cursor-tracking: unavailable until a reviewed cursor pack is configured" | MISSING |
| Approved template pack | none present in the repository or configuration (`youtubeDownloader`, cursor pack keys absent) | BLOCKED |
| Annotated held-out clips with provenance | none present (no `*held*out*`, `*annotation*`, `*template*pack*` assets outside dependency dumps) | BLOCKED |

No measurement was performed and no threshold (median ≤4 px, p95 ≤12 px, recall ≥90 %,
absent-span false detections ≤1 %) may be claimed. Imported footage remains
`trainingEligible=false`, `controlAllowed=false`.

Disposition: **MISSING + BLOCKED**. No synthetic substitutes were invented.

## Phase 5 — pinned YouTube acquisition

| Prerequisite | Observation | State |
|---|---|---|
| `yt-dlp` or approved downloader binary | `command -v yt-dlp` → not installed | BLOCKED |
| Pinned downloader path + SHA-256 | `chromascape.mouse-video.youtube-downloader` / `…sha256` unset (defaults empty) | BLOCKED |
| Operator-approved accessible public clip | none supplied | BLOCKED |
| Provider gate | `youtubeEnabled=false`; `tools.youtubeDownloaderConfigured=false` | BLOCKED |

The provider stayed disabled in the isolated instance, as the plan requires. No acquisition,
success-path provenance or failure-mode run occurred. A mock/unit pass
(`MouseVideoYouTubeTest` 2/2, `MouseVideoYouTubeAcquirerTest` 2/2) cannot close this gate.

Disposition: **BLOCKED**.

## Phase 6 — native regression

Command (verbatim, JDK 17):

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --rerun \
  --tests '*MouseResearch*Test' --tests '*MouseRecording*Test' \
  --tests '*MouseCampaign*Test' --tests '*MouseTraining*Test' \
  --tests '*MouseCaptureSummaryTest' --tests '*Stabilization*Test' \
  --tests '*LocalReplayPrincipalFilterTest' --tests '*ReplayArtifactServiceReuseTest' \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain
node --test src/test/js/mouse-research.test.js src/test/js/mouse-recording.test.js src/test/js/mouse-task-recording.test.js
```

| Gate | Observed | State |
|---|---|---|
| Java selectors | 204 tests, 0 skipped, **1 failure**, 30 suites | FAIL |
| JavaScript selectors | 26 tests, 26 pass, 0 fail | PASS |

Failing case:
`com.chromascape.web.MouseResearchPageTest.inspectorTemplateHasTargetTimelinePhysicalInputsAndBackNavigation`
— `expected: <true> but was: <false>`.

Reproducer: the assertion requires `templates/mouse-recording.html` to contain
`data-inspection-clicks`, `data-inspection-pointers`, `href="/mouse"` and
`Missing targets stay unknown`. The first three are present; the fourth is absent. The
template is **untracked** in this checkout (`?? src/main/resources/templates/mouse-recording.html`),
so the drift belongs to the working tree, not to SC-2150's `mouse-video` files. It is still
a real failing native regression for VA10 and must be reported, not waived.

All other native suites passed, including `MouseCaptureSummaryTest` (3), `MouseCampaign*`
(13), `MouseResearch*` (32 excluding the page test), `MouseRecording*` (12),
`Stabilization*` (129), `LocalReplayPrincipalFilterTest` (4) and
`ReplayArtifactServiceReuseTest` (8).

Disposition: **FAIL** (one template assertion), **PASS** for JavaScript.
