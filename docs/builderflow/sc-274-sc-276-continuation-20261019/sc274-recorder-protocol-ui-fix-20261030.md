# Recorder protocol and UI follow-up — 30 October 2026

The reported observer 403 is separate from the actual recording start failure.
SC-274 remains open. This increment repairs recorder compatibility and debugger controls;
it does not create genuine run context, approved source trust or acceptance evidence.

## Diagnosis

Actual Chrome inspection showed POST /api/debug/replay/start-recording returning 503
RECORDER_TICK_TIMEOUT, while the UI retained Stop Recording with tick zero after rollback.
The previously launched 1.13.2 client repeatedly logged TICK_VERSION_CHANGED. Its compiled
TickBatchMessage defaults to schema 3 and its transport lacks server-selected version
negotiation; the server selected schema 2 for the contextless stream. The initial client
selection was wrong and was corrected rather than relaxing server validation.

The available 1.13.3 artifact at revision 429e32978b implements selectedTickBatchVersion,
selectedBehaviorTickVersion, setSchemaVersion and manual RECORDING_CONTROL. Its SHA-256
is 96c912c9cdecfa49ccd7125c52b68394190dbd11d5ebe74042d065f3c06a71e0.

The first replacement launch supplied MICRO6_DEBUG_CAPTURE_ORIGIN=127.0.0.1:8090. This
incorrectly invokes that artifact's isolated test-mode guard, which correctly refuses 8090.
That attempt was retained. The final launch uses normal saved-profile capture configuration,
with neither test-origin override nor source-authority claim. The profile's existing URL is
ws://192.168.1.6:8090/ws/micro6/tick-stream and autoStartSession=true. No guard was changed.

## Changed files

- debugger.js: Record targets the existing window-filtered live session ID. Replay,
  absent-producer and pending-window-rebind states refuse before POST. Duplicate clicks
  and recording polls cannot race a pending start; prior polls are aborted/fenced.
  Failed starts reset the button, indicator and duration timer.
- debugger.js: optional desktop observer polling stops on 401/403, displays a local-access
  message and disables only that Watch control. ObserveController remains unchanged.
- debugger.html: cache-bust debugger.js to v28. The existing cached live template may
  still render v27 until template cache refresh; hard reload fetches the corrected file.
- debugger-recording-controls.test.js: eight behavior regressions for selected-session
  targeting, refusal, double-click/poll races, rollback UI, observer access loss and
  accepted live-tick binding after refresh.
- debugger-workspace.test.js: assert bundle v28 and correct a pre-existing stale replay-core
  expectation (v2) to the already-served v3. No runtime dependency was downgraded.

## Implementation and runtime actions

Only the identified requested client was replaced, after authoritative video status reported
recording=false and process command lines matched the known artifact/profile. Earlier PIDs
226132 and 128456 are stopped. Final client PID 94796 uses the pinned 1.13.3 artifact, saved
profile, sourceMode=disabled and normal capture URL. No credentials were passed in launch
arguments, no game authentication dialog was automated, and unrelated clients were untouched.

Reviewed JS/template were copied to this worktree's build/resources/main with prior copies
retained under the evidence root. The HTTP-served debugger.js bytes match the reviewed
source SHA-256 80afd5ca6ff466c18b2b697bc9554f3cd163537a88ec9b6e53354cffc38a0f77.
ChromaScape's JVM, database, security policy, consumer configuration and schema were not restarted
or modified in this increment. Browser refresh is required to replace its already-loaded script.

## Tests

- Eight new behavior regressions pass, including safe live-session binding after page refresh.
- First full Node attempt: 275 passed, two test files could not load jsdom. Dependencies
  were installed from the existing package/lock into an external test-only directory,
  with install scripts disabled; source dependency files were not changed.
- Final complete Node suite using that dependency directory: **295 passed**, zero
  failures/cancellations/skips. Node 22.19.0, jsdom from the existing locked dependency tree.
- Source/template parsing, bundle contract and git diff --check pass.
- Binary compatibility inspection proves negotiation/manual-control code exists in the
  final artifact. Final startup reached the login screen without the test-origin failure.
- After user login, real recording recording_cda3d983 persisted ticks 1 through 424.
  The UI was observed at Feed: Active (Tick 418) with Stop Recording. The separate
  header cursor still displayed Tick 0; feed progress and SQL counts are the evidence.
- Final read-only SQL confirms FINALIZED, end time present, duration 804271 ms,
  video path present, no failure code. No agent start/stop or forged tick was issued.
- Video metrics counted 22813 frames with 6583 dropped frames; source binding was null.
  These are observed raw-capture results, not a gap-free or source-qualified capture.

## Remaining work

The user completed login and a real capture finalized. Review its content and quality,
then supply a genuine context-bound recording and published curation for original SC-274
acceptance. Do not retrofit or invent run context for this contextless capture. The separate
header tick cursor and captured-video quality remain review limitations.

The basic Record UI still does not supply a BehaviorRunContext/profile. Original SC-274
genuine provenance/privacy/travel requirements and original Felix task recovery remain
open. Runtime compatibility repair does not silently close those gates.

## Risks

Recording now intentionally requires a known selected live session; mere browser WebSocket
connectivity is insufficient. Source serving stays disabled. The actual producer must be
in-game before the server can verify a persisted tick. Current server state must be checked
after login; the static feed-status endpoint alone reports session metadata, not committed ticks.

## Rollback

Revert the scoped source/test/template follow-up and restore the two backed-up runtime
resources if necessary. Preserve failed attempts, logs and all capture evidence. The older
client is known incompatible; do not silently revert a logged-in client or source trust.

Evidence: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\record-ui-followup.
