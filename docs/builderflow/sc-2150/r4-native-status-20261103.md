# SC-2150 R4 — native recording/trace round trip status (2026-11-03)

Status: **open**. R4 needs an owner-operated Micro6 login and a live fishing/drop window on this
host; no such window was available during this verification round, so no native capture was taken
and no native regression is claimed.

## What is prepared and verified as present

|Item|State|
|---|---|
|Runbook|[`w12-native-session-runbook-20261031.md`](w12-native-session-runbook-20261031.md) — read and followed as the procedure; unchanged|
|Isolation|Qualification server/client ports 8093/8099; the deployed 8090 instance and its database were not touched by this preparation|
|Native kit|`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/sc2015-qual-20261026` present (client-forward/client-rollback trees)|
|Canvas recorder|`scripts/sc2150/record_x11_canvas.py` present; the runbook's earlier stand-in qualification of the recorder is unchanged|
|Trace converter|`scripts/sc2150/label_from_mouse_trace.py` present|
|Import/analysis path to compare against|Assisted import, `visual/v1` analysis, observations, review revision and immutable export all exercised on 8090 the same day (see [`r3-8090-restore-20261102.md`](r3-8090-restore-20261102.md)); this is the disposable-instance path R4 would reuse|

## What is not verified

- No real account launch, no live fishing stand, no Bot Mouse canvas recording.
- Therefore no trace-derived labels, no clock/PTS alignment check (≤ 50 ms), no pixel cross-check
  against the drawn cursor, and no generated-versus-human provenance comparison from real footage.
- AC11 (“no invented native facts”) and the native half of AC12 remain **unverified** for this
  candidate. The export contract keeps `nativeTelemetry=false`, `exactTicksAvailable=false`,
  `controlAllowed=false` and `trainingEligible=false` regardless, so nothing in this round can be
  mistaken for native evidence.

## How to close it

Follow the runbook with the owner present: start the isolated qualification server/client, record the
canvas for ~10 minutes while dispatching fishing/drop through the 8099 executor, stop the run, convert
the trace, check source identity/ordinals/drops/clock alignment and the pixel cross-check, then import
into a disposable instance, run `visual/v1` and report generated versus human provenance separately.
Retain a sanitized record with hashes under `verification-runs/<UTC>-w12-native/`; no media or
credentials. Restore the kit afterwards.

Owner for the deferred gate: the story owner (Micro6 login and the approved fishing/drop window).
