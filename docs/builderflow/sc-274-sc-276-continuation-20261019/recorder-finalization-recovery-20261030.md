# Recorder finalization recovery — 30 October 2026

## Diagnosis and correction to the earlier report

The user could not start a second recording. Live status reported recording=false,
metrics still identified recording_cda3d983, and the start route reported Previous
recording was not finalized. The prior FINALIZED database row and saved video path
did not establish native encoder finalization or readiness for another recording.
The earlier readiness assurance was too strong.

RecordingService stops admitting frames before waiting up to 15 seconds for its
encoder. On timeout it correctly retains the native handle, but a subsequent start
previously refused forever, even after the encoder had finished draining.

## Changed files

- RecordingService.java: on a new start, a retained native recorder is released only
  after its encoder Future is done (or no encoder was submitted). A running owner
  still causes refusal. Stop/release failures still refuse the new start. Existing
  media is retained and no calibrated finalization is asserted for it.
- RecordingServiceRoundTripTest.java: recovery followed by real MP4 encoding,
  retained previous media, release failure refusal, and existing active-owner
  timeout refusal are covered.

## Runtime recovery

The last successful live check confirmed no active recording. The identified
ChromaScape PID 79468 was stopped; the logged-in Micro6 client and database were
not stopped. Its approximately 16 GB log was retained under the worktree as
chroma_bootrun.before-recorder-recovery-20261030.log. The established restart
script is invoked with canonical configuration, unchanged existing tokens,
Hibernate validate and replay worker disabled. No host/WSL shutdown is used.

## Tests

The first Gradle attempt stalled before test execution during severe host
unresponsiveness and was interrupted. It is not a passing test result.
Final test and live-cycle results are recorded in the follow-up below.

## Remaining work

Verify the rebuilt service and a fresh recording start/stop cycle. A database
FINALIZED label alone must not be used again as proof that the video encoder is
released. Original SC-274 run/source context, curation and acceptance gates remain
open; this recovery must not fabricate those inputs.

## Risks and rollback

A retained recording may lack a calibrated index; releasing its native handle does
not promote it to qualified evidence. Active encoders must never be force-released.
Revert this scoped service/test change to restore the previous refusal behavior.
Keep the media and historical log. Restart only the owned application if needed;
do not restart the logged-in client or database as a recovery shortcut.

Evidence root: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\recorder-handle-recovery.

## Verified follow-up

- Isolated Gradle run on JDK 17: 8 tests, 0 failures/errors/skips (round trip 3,
  dimensions 4, source fencing 1). The active-owner timeout still refuses; a
  completed-owner recovery produces a nonempty calibrated MP4 and retains the
  previous file. Failed native release refuses the next start.
- Google Java Format 1.17.0 passed with Guava 33.4.8; git diff --check passed.
  The first standalone formatter attempt used an incompatible older Guava and
  failed before formatting; it was corrected. Formatting-only changes followed
  the independently tested source; behavior is unchanged.
- First service restart failed at ARTIFACT_DURABILITY_UNAVAILABLE, as Windows
  STRICT mode intentionally requires. Added ARTIFACTS_DURABILITY_MODE=development
  to the ignored canonical .env.local and the recovery wrapper. The artifacts
  subdirectory was absent before this startup; no populated root was adopted or
  changed from qualified to development. This local service is unqualified.
- Second restart succeeded on PID 47808. Both local and LAN /debugger probes
  returned 200. Replay consumption remains worker=false, Hibernate validate;
  the existing database and game client PID 94796 remain running.
- The client's reconnect circuit had opened after five minutes of service
  downtime. Its Debug Recorder plugin was toggled off/on through its existing UI,
  restoring the WebSocket without restarting the game client. The game window
  itself showed disconnected, requiring manual user login (requested).
- The restored configured auto-start session produced recording_f48e9d35. It was
  stopped to avoid recording the login flow. The debug stop returned 504
  RECORDER_STOP_TIMEOUT because the disconnected client did not acknowledge;
  the server nevertheless finalized its native video: 838 frames, no frame drops,
  queue high-water 2, zero game ticks. This is not a passing live tick cycle.
- First two-cycle driver attempt was made before the transport had reconnected:
  RECORDER_UNAVAILABLE, no diagnostic start succeeded. Its failed evidence is
  retained. No second-cycle or end-to-end success is claimed.

Remaining live verification depends on the user reconnecting to the game. Keep
SC-274 open, do not claim ready-to-record based solely on stored metadata, and
repeat two selected-producer start/stop cycles once genuine ticks are arriving.
