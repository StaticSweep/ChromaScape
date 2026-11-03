# Verification evidence — SC-274 provenance identity (3 November 2026)

This covers only the code change recorded in `implementation-evidence-20261103.md`. It is not a
story-level verification: the acceptance rows that need a genuine capture are still open, and the
Docker-backed lane could not run on this host today.

## Acceptance results

| Item | Result | Evidence |
|---|---|---|
| Reused-session guard accepts a capture echo that differs only by JSON number representation | Pass | `DebugSessionProvenanceReuseTest#anEchoOfTheSameCaptureWithFloatingPointNumbersIsAccepted` — first start stores `privacy.limit` as an integer, the echo sends `5.0`, the start succeeds and returns the stored context |
| A single differing value is still refused, and the stored context is untouched | Pass | `#anEchoWithADifferentExecutorIsRefused` — `IMMUTABLE_PROVENANCE`, stored `executorId` still `exec_1`, `startRecording` called once |
| A different task key on a recording-bearing session is still refused | Pass | `#aDifferentTaskKeyOnAReusedRecordingIsRefusedAtTheSettingsGuard` — `IMMUTABLE_PROVENANCE`, no second recording |
| Canonical identity, not raw tree equality | Pass | `BehaviorRunContextIdentityTest#numericRepresentationAloneKeepsOneIdentity` (digest equal, raw tree unequal, no differing paths) |
| Refusals triggered by a changed graph revision, recording id or session id | Pass | `#aDifferentValueIsStillRefused`, `#aDifferentRecordingOrSessionIsStillRefused` |
| A stored context whose digest no longer verifies is refused | Pass | `#aStoredContextWhoseDigestNoLongerVerifiesIsRefused`, `#aContextWithoutAnyDigestIsRefused` |
| Refusal diagnostics name fields, never values, and stay bounded | Pass | `#theDiagnosticNamesFieldsWithoutTheirValues` — `[executorId]`, no `privacy`, ≤16 entries |
| SESSION_START diagnostic no longer logs the payload | Pass by inspection of the committed diff — `fieldCount` replaces the serializer | `TickStreamWebSocketHandler` diff |
| Live refusal explained | **Not proven.** Needs P2.3a on a lease | — |

## Tests

- Focused lane: **31 tests, 0 failures** (see the implementation evidence for the exact command).
- Full `replayTest --offline`: **871 tests, 26 failed, 42 skipped.** All 26 are accounted for: 16 are
  the recorded environmental baseline; 10 are `ContainerLaunchException` for `postgres:16-alpine`
  because Docker Desktop is not running. Zero failures attributable to the change.
- `checkstyleMain` / `checkstyleTest`: build succeeds; pre-existing warning volume unchanged in kind.

Unrun: the PostgreSQL integration lane (T4) and the row-5/compile lanes that need Docker, the four
SC-274 containers, or a lease.

## Risks

- **The live cause is still unnamed.** The canonical-identity fix removes one class of false refusal
  (representation-only difference) but it does not prove that class is what refused on 30 October and
  2 November. Mitigation: the WARN at both sites now names `differingFields`; the next start is
  diagnostic evidence rather than a guess.
- **Runtime state today:** PID 43424 still listens on 8090 with a mixed build. Docker Desktop is
  stopped, so the four SC-274 containers are down and the plan's rule 11 applies before any capture.
  The only ESTABLISHED connections to 8090 are a Firefox tab on
  `/debugger?recordingId=recording_af17d0dc` (PID 151920) — no second RuneLite client.
- **`spotlessCheck` is not green on this branch at HEAD** (it reformats 1384 unrelated files). The
  touched files were formatted; the mass reformat was reverted deliberately to keep this diff
  reviewable.
- The two failed empty recordings (`recording_b794abe4`, `recording_1d6706c2`) stay untouched and are
  never evidence.

## Rollback

- Code: revert the three source files and delete the two new test classes; nothing was pushed.
- No database row, recording, curation or capability flag was written by this change.
- The reuse guard's behaviour is restored exactly by `git revert` of the commit; the previous
  condition is `!expected.equals(row.getRunContext())`.
