# SC-2150 R1 — assisted review packaging and profile provisioning

Date: 2026-11-02. Follow-through for
[R1 of the remaining plan](remaining-plan-20261102.md). Scope: make the automatic assisted
review feature reproducible from a fresh checkout and documented local settings, with the
effective analysis profile and private cursor-template pack explicit.

## Changed behavior

| Area | Change |
|---|---|
| Template pack identity | `visual-cursor-template-pack-id` is the only pack an analysis may be scheduled under. Omitting `templatePackId` selects it; naming it is equivalent; any other label is refused with `INVALID_REQUEST`. |
| Template provisioning | `visual-cursor-template-sha256` pins the template bytes. A missing or changed file fails scheduling with `SOURCE_UNAVAILABLE` / `CHECKSUM_MISMATCH` instead of silently analysing without suggestions or with other bytes. |
| Capability report | `GET /api/debug/mouse-videos/capabilities` now includes `analysisProfile`: algorithm, cursor method, toolchain pin, resolved pack, actual and pinned template digests, template state, sampling profile, hotspot, thresholds and storage bounds. |
| Provisioning tool | `scripts/sc2150/cursor_template_pack.py seed` derives a private template from an exact source frame/crop and writes a provenance manifest; `verify` checks a provisioned file against its digest. |
| Storage failure | Decoder output that exhausts the bounded job storage now reports `QUOTA_EXCEEDED` instead of `DECODER_FAILED`; an undecodable source stays a decoder failure. |
| Job progress | Analysis jobs report truthful sub-stages (`ANALYSIS_SAMPLES`, `ANALYSIS_TRACKING`) and completed tracking work as a percentage of the forward/backward passes. The job API exposes `createdAt`/`updatedAt`; the UI shows the stage label and elapsed time instead of a bare percentage. |
| Operator UI | The automatic-track action explains unavailable provisioning (`analysisProfile.templateProblem`) and does not offer a run that cannot produce suggestions. |
| Configuration | `.env.example` documents the template, pack, digest, hotspot and sampling settings. |

## Evidence

- The seed script reproduces the existing reviewed pack exactly: source frame ordinal `3`,
  crop `[1356,724,12,19]`, hotspot `[0,0]` yields template pixels identical to
  `build/sc2150-assisted/clip-arrow.png` and SHA-256
  `14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`.
- Python tests: `src/test/python/test_sc2150_cursor_template_pack.py` (mask rule, digest
  verification, manifest identity, ffmpeg frame selection).
- JS tests: `src/test/js/mouse-video.test.js` includes the stage/elapsed assertions.
- Java tests: `MouseVideoAnalysisIdentityTest` covers pack normalization, unsupported packs and
  pinned/missing template digests; `MouseVideoCatalogServiceTest` covers the capability profile
  and the mismatch state; `MouseVideoAnalysisWorkerTest` covers storage-exhaustion mapping.
- Candidate test run (clean worktree from `origin/main` + this change): 235 tests, 0 failures,
  1 skipped; JS 40/40; Python 6/6.
- Live smoke on an isolated port and disposable database: `/api/debug/mouse-videos/capabilities`
  reported `analysisProfile.templatePackId = clip-arrow/v1`, `templateState = AVAILABLE`, the
  pinned digest, 10 fps and 960 px; `/mouse/video` served the new assets (script `v=11`).

## Not claimed

- No new detector accuracy result. Assisted suggestions remain machine predictions with
  visibility/confidence and frame lineage, not independent human truth.
- No change to the frozen W10 qualification template or candidate.
- R2 resource/recovery measurements, R3 deployment, R4 native capture, R5 acceptance and R6
  YouTube remain open as described in the remaining plan.
