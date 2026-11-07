# SC-274 capture-quality repairs — 31 October 2026

## Implemented plan

1. Preserve recording_f370c9fb and diagnose its failed finalization.
2. Repair self-unregistration so a duration-limit callback cannot interrupt its own
   encoder drain/native close/index publication.
3. Add session-local non-combat tick/clock quality and calibration requests to Micro6,
   retaining unavailable/gap/reset/delivery failures and keeping combat settlement separate.
4. Run focused native-video/transport regressions, rebuild the exact signed client,
   and recheck the saved capture's eligibility before asking for another recording.

## Changed files

ChromaScape: UnifiedFrameCaptureService tracks callback ownership and gracefully shuts
down its dispatcher when called by one of that dispatcher's own callbacks. External
shutdown retains its bounded await/interrupt behavior. No interrupted flag is cleared
to disguise cancellation. UnifiedFrameCaptureShutdownTest reproduces the real executor
case; RecordingServiceRoundTripTest exercises automatic duration stop with real FFmpeg
and a generated measured-video index. Existing source fencing is unchanged.

Micro6 branch codex/sc274-capture-quality-20261031, commit
bea75d556394209182480c7f24c06f876746629f: TickCaptureQuality, DebugRecorderPlugin,
ChromaScapeWebSocketClient and three focused test files. Context-bound Fishing captures
request four-timestamp calibration and emit generic quality on real ticks and the
terminal tick. Generic timing does not infer combat actions or complete human intent
coverage. Existing combat capture behavior remains separately scoped.

## Tests

ChromaScape recordingProvenanceTest selection: 17 tests, zero failures/errors/skips:
native recorder round trips 5, measured index 7, source fence 1, dispatcher shutdown 1,
capture source fence 3. The automatic-stop test expires the duration guard using a
test-local elapsed-time injection, without changing the host clock. Its clock-reset
quality flag remains honest; it verifies native close/index publication. Other round
trips verify ordinary index publication with no quality flags. The selected suite was
rerun on the final formatted source and passed.

Micro6 :client:test selection: 108 tests, zero failures/errors/skips: four-timestamp
clock 5, actual WebSocket transport 64, combat semantic capture 26, dashboard controls
8, generic timing quality 5. New integration cases verify a Fishing calibration request
on the wire and its real terminal GameTick before the stop ACK. The first offline
attempt lacked cached Mockito 5.23.0; a normal online build resolved the declared version.
No dependency override or source gate relaxation was applied.

Exact clean Micro6 rebuild/sign succeeded, retaining approved key
micro6-sc810-local-ed25519. New artifact is 85,430,900 bytes,
sha256:099635b166541c441eac0091a7a65ae12121b1babab51ee4e3621b85f50f080d.
No new key or trust root was generated. No signing key was passed to a serving process.
The old running source artifact/worktree was not overwritten or relaunched.

## Existing media recovery

The pre-recovery media could not decode: FFmpeg reported a missing moov atom. The old
JVM still retained its drained native recorder after the interrupted stop. Before any
mutation, copied and verified the exact 531,365,936-byte file into the restricted
original-media-before-native-finalization archive. Its SHA-256 remains
eeb5726258ffea255dff3f2141d00dcbdabd57c73846008bb4f438f29cd7b442.

Used the recorder's existing normal admission path to retire that completed native
owner, followed by a one-second labelled video-only diagnostic and normal stop.
recording_ca611f23 finalized 29 frames with measured-video-index/v1 and no quality flags.
This was not a human training capture and dispatched no gameplay. The original MP4
then decoded successfully: 764x502, measured duration 3602033333 microseconds. Its
finalized bytes are 531,911,856 bytes, SHA-256
96a189602a879bc3b73ea9b24343ee1835dbbdf2c558f5d4793ebb60b33eccac.
No timing index was fabricated for the old media; its original capture calibration
was not measured and cannot be reconstructed as verified evidence.

## Acceptance results

The genuine Fishing measurements remain seven trout, seven salmon and +840 Fishing XP.
The saved session is FINALIZED with 1,169 accepted snapshots and explicit stop closure.
Authenticated provenance still returns eligible=false/CAPTURE_INCOMPLETE; a training
read refuses with HTTP 400 and that same code. The canonical aggregate is explicit:
acceptedFrames=1169, expected=1169, observed=0, unknown=1169, dropped=0;
unsupported families SCRIPT_INTENT and TRANSITION_VERIFICATION. These counts were
read, not changed. Generic tick/video calibration is separate from this semantic
coverage gate. No curation or training publication was attempted around that refusal.

Evidence root: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\capture-quality-repair.
JUnit XML, build logs, signed bundle report, native auto-stop artifact, media probes,
native-owner-recovery.json and existing-capture-recheck.json are retained. Raw personal
capture/context data and the original byte archive stay under the restricted private
evidence directory.

## Remaining work

Deploy the verified ChromaScape repair to only the owned idle 8090 service and check
start/stop again. The new client bundle is built and tested but not yet activated in
the game process. Complete and qualify measured HUMAN intent/transition coverage
before requesting another acceptance capture. The existing adapter's declared unknown
coverage is a real remaining engineering gate, not a missing user recording or a reason
to set its counters to zero. Original-trust/production/oracle and provider-origin claims
remain outside these development timing repairs. SC-274 remains open.

## Risks and rollback

No old capture was promoted to eligible, no timestamps were backfilled, and no semantic
quality gate was weakened. Native-video smoke proves this Windows path, not power-loss
durability or independent game fidelity. Retain the protected original byte archive,
playable recovered media, old immutable ChromaScape snapshot and old signed client.
Rollback the server using the prior immutable classpath; roll back client activation
using the prior signed artifact/bundle. Revert only these scoped commits; do not reset
runtime fences or unrelated work. No tracker state or story completion is claimed.

## Owned deployment verification

Deployed ChromaScape commit 2b413a09's tested classes/resources into a new immutable
owned snapshot and restarted only the idle Windows 8090 service with the same owned
sc274_capture database. Retained the prior immutable classpath for rollback. The game
client PID 188552 was not restarted. The explicit target window still belongs to that
same process.

Post-deployment normal API start/stop passed: labelled non-training diagnostic
recording_79788aef, 29 physically indexed frames, measured-video-index/v1, zero quality
codes, and recording=false afterward. This is a live Windows video-only check; the
automatic-duration regression runs through real FFmpeg and the real dispatcher in the
focused test suite. No live calibrated game tick or authoritative fidelity is claimed.

The client correction remains a clean, signed deployment-ready bundle, not activated
in the running game process. The rebuilt bundle must replace the old attested runtime
as a whole when the HUMAN semantic coverage implementation is qualified; no hot class
replacement or repeated user capture is appropriate while that gate remains open.

Local commits only; no pushes, Felix transitions or Shortcut changes in this repair.
Original raw archive and recovered playable recording are both retained. The old
metadata's wall-clock duration must not be treated as measured media duration.
