# SC-2150 — Implementation Evidence: Fedora completion run 20261021T152511Z

## Changed files
Feature set (see `verification-runs/20261021T152511Z-fedora-completion/git-checkpoints.json`, ref `refs/codex/sc-2150/20261021T152511Z-verified`, 150 files, partial tree; HEAD 5a7c30f8 and index untouched):
- Video package: `src/main/java/com/chromascape/web/debug/tickdebugger/video/**`, `src/test/java/.../tickdebugger/video/**`
- Migrations `V42`–`V46` (`*_sc2150_*.sql`)
- UI: `mouse-video.js`, `mouse-video.html`, `mouse-research.css` (review-history keyboard focus added this run); worker `mouse-video/probe.py`
- Tests: `src/test/js/mouse-video.test.js`, `src/test/python/test_mouse_video_probe.py`
- Verification harness (gitignored, in checkpoint): `scripts/sc2150/{start_verification,qualify_http,qualify_access_lifecycle,qualify_restart,qualify_competing}.py`, `qualify_browser.cjs`, `qualify_stale_preview.cjs`, `restore_checkpoint.py`
- Docs: `docs/builderflow/sc-2150/*`, `fedora-verification-operator-guide.md`, `docs/mouse-video-import.md`, `.env.example`

## Tests
Taken from run logs in `verification-runs/20261021T152511Z-fedora-completion/logs` and `summary.json` (executed in the Codex session; not re-run in this closing step):
- Video package `com.chromascape.web.debug.tickdebugger.video.*` with real PostgreSQL: 266 tests, 0 failures, 0 errors, 0 skips
- 19 retained review probes passed; 336 JavaScript tests passed; Python worker tests passed
- HTTP qualification: CFR/VFR/burst-VFR, 90° rotation, re-encoded crop and H.264 bitstream crop with exact pixels
- Browser (Playwright + axe): conflict recovery, draft/history, point and region corrections, immutable exports, keyboard navigation, 390 px layout, axe 0 violations; lost-worker cancellation; stale-preview ordering
- Access: 24 protected reads (anonymous 401, owner-B 404); capacity: full owner 429 before body, identical retry accepted, changed retry 409
- Restart/lifecycle: queued preview recovered, active decoder crash → NEEDS_RECONCILIATION with higher generation, expiry fence, two enabled JVMs each claim 6 previews once, disabled mode refuses scheduling
- Spotless and both Checkstyle tasks: BUILD SUCCESSFUL (4657 warnings)
- Full repository suite: 2854 tests, 72 failures, 15 skipped; no new failing cases versus the pre-work record — **repository is not green**
- Teardown: all three owned JVMs stopped, one FUSE mount unmounted, disposable DB `sc2150_fedora_20261021_152511` dropped, media root removed; 8090 service untouched
- Rollback rehearsal: `restore_checkpoint.py` dry-run + `--apply` on a scratch copy; baseline-path hashes matched (0 mismatches). Not applied to the working checkout.

## Remaining work
- FA12/FI10/FT10: approved licensed cursor templates and annotated held-out corpus; cursor accuracy gates are unqualified (synthetic fixtures establish mechanics only)
- FA13/FI11/FT11: approved yt-dlp pins, public clip and constrained egress; YouTube stays disabled
- Windows process containment/recovery qualification on a Windows host
- 72 pre-existing repository failures need disposition
- Felix spec_state is `draft` (earlier planning-status said spec_ready); resolve honestly before any transition. No in_review/done claim.
