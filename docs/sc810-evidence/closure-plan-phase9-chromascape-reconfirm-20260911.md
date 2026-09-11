# SC-810 Phase 9 — reconfirm ChromaScape dispatch against the final artifact (2026-09-11)

## Scope

Read-only reconfirmation of dispatch readiness through ChromaScape, now pointed at the
corrected final Micro6 lineage (`micro6-sc810-integration`, commit `6e271ce40c`) rather than
the earlier, now-superseded `micro6-sc810-package` used in the Phase 10b check. No account
mutation, no Fishing launch, per this phase's own instruction.

## Environment

- Inspected active processes/ports before starting: port `8195` free, dedicated
  `chromascape-sc810-p9-postgres` container restarted (data preserved from Phase 1), no other
  operator's instance affected.
- `BUILDER_GOAL_MICRO6_PROJECT_DIR=C:\Dev\IdeaProjects\micro6-sc810-integration` (previously
  `micro6-sc810-package` in the Phase 10b check).
- Same `local-development` trust environment / `SPRING_PROFILES_ACTIVE=local-development` /
  `MCP_AUTO_START=false` / transport-token placeholders as Phase 10b (see that file for why
  each gate exists).

## Results

1. **`GET /debugger` → 200.** ✓
2. **`GET /api/debug/builder-goals/machines` → 200.**
   - `CZC8518C27 (Beheerder)` (`1d391d65-6c97-5b77-b676-964f157e8c73`):
     `online: true`, `runnable: true`, `builderGoalRunnable: true`, `disabledReason: null`. ✓
   - `CZC8518C27 (ikiddoi)` (`c72f5177-f22f-550a-8633-870d01f6acbb`):
     `online: false`, `runnable: false`, `builderGoalRunnable: false`,
     `disabledReason: "Launcher machine is offline"`. ✓ Correctly excluded.
3. **Beheerder UUID remains the eligible registration; stale ikiddoi remains offline.** ✓
   (Same result as Phase 10b — the launcher-side state hasn't changed.)

## What could NOT be checked through ChromaScape itself, and why

`GET /api/debug/builder-goals/catalog`:
```json
{"tasks":[],"catalogAvailable":false,"degraded":true,
 "degradedReason":"Live Micro6 catalog unavailable and no saved Builder Goal tasks were found.",
 "source":"unavailable"}
```
Fishing Training visibility and final-artifact/snapshot identity matching **through
ChromaScape's own dispatch path** require a live-connected Micro6 executor reachable at the
address ChromaScape/the launcher expects — i.e., actually launching the real Micro6 client
under the launcher's control. That is a distinct runtime action
(`micro6-sc810-integration/AGENTS.md`: "Do not launch the client... unless the user explicitly
requests that exact runtime action") not separately authorized this session, and was not
attempted here.

This is **not the same gap** as Phase 8: Phase 8 independently proved, via the standalone
packaged-runtime harness talking directly to the executor's own `/health`, that Fishing
Training is discoverable and the exact final build_id/artifact_digest/source_snapshot_id are
correctly advertised. What Phase 9 could not additionally prove is that *ChromaScape's own*
dispatch path sees the same thing when a live client is actually running and connected — that
requires the full live-launch path this phase's own scope explicitly excludes ("performs no
account mutation and no Fishing launch").

## Cleanup

Confirmed by direct process inspection that PID `88780` (`java.exe`, created `2026-09-11
20:06:26`, owner of the `LISTEN` socket on port `8195`) was this session's own process.
Stopped it; confirmed port `8195` no longer accepts connections afterward. Stopped (not
removed) `chromascape-sc810-p9-postgres`, preserving its data.

## Net effect

Dispatch-readiness through ChromaScape's machine-selection logic is reconfirmed against the
final, corrected artifact lineage. Nothing regressed from the Phase 10b check. The remaining
gap (live catalog visibility through ChromaScape specifically) is gated on the same live-launch
capability that Phase 11 (genuine Fishing recording) needs anyway, which itself is gated on the
still-unauthorized account-record migration.
