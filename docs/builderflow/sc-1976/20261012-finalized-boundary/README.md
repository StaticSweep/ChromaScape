# SC-1976 finalized recording boundary follow-up

## Plan and outcome

Existing combat PR59 at 41053748 accepted legacy tick batches for any existing debug session and unconditionally expanded endTick. The genuine recording recording_4fc844dc exposed this: video ended at 02:17:22 but stored tick2815 was timestamped02:42:18.

Implemented one database row-lock boundary shared by tick ingestion, explicit stop, disconnect finalization and start rollback. Before receipt/action/snapshot/log/overlay writes, terminal sessions require both source tick and capture timestamp to be within their immutable recording interval. Invalid or out-of-interval data fails permanently through the existing BATCH_INPUT_INVALID transport error. Delayed packets inside both bounds remain admissible. Finalized bounds never move. Capture timestamps use the same host time-zone conversion as existing ingestion/session metadata.

The lock remains held through the ingestion transaction. Explicit stop/disconnect take the same debug-session lock before canonical frame closure to keep lock ordering consistent. Existing canonical-frame explicit-stop and reconnect contracts remain unchanged.

## Changed files

Owning identity: `C:\Dev\IdeaProjects\ChromaScape`.

- DebugSessionRepository: pessimistic capture mutation query.
- DebugSessionService: transaction-required ingestion read; locked stop/disconnect/rollback and immutable terminal bounds.
- TickStreamIngestionService: boundary validation before durable evidence writes.
- TickStreamIngestionServiceTest and DebugSessionServiceTest: seven new cases for beyond-end tick, reused tick with late capture time, invalid timestamp, pre-start tick, admissible delayed packet, frozen bounds and separate new-session bounds.
- Existing combat round-trip, quest-helper and player-action tests: mocks use the new transactional read entry point.

## Tests

Java17 compile passed. Focused suite: 127 tests passed, zero failures/errors/skips, across ingestion54, session15, player-action4, combat round-trip3, quest-helper7 and WebSocket44. Includes seven new cases. Run used native SC967_VECTORS_PATH to the contracts fixture and cached JDK17. Initial default-JDK25 and Windows fixture-path attempts failed before tests; corrected environment succeeded.

`git diff --check` passed. Formatting was applied to owned files; unrelated baseline formatting and CRLF files were preserved. checkstyleMain/checkstyleTest completed successfully with zero error-severity violations; repository-wide warnings were 4,265 main / 718 test. Two brace warnings on relocated existing statements were corrected afterward. No unrelated warning cleanup was included.

## Micro6 source audit

Read the committed PR162 at ce940c2ab0, as well as the local source. It already waits for the terminal batch and SESSION_STOP acknowledgement, checks the callback's session identity and clears client binding through markSessionStopped. New-start/pending-stop paths prevent replacing an unsettled session. No additional Micro6 lifecycle defect was established, so no Micro6 code was changed. No Micro6 compilation, tests, installation or runtime actions performed.

## Remaining work and limits

- Not deployed. Matching combat collector/server builds, negotiated schema, migrations and collector settings must be verified on the intended host.
- Missing fields in the old recording are a runtime/payload/persistence/projection diagnosis, not evidence that existing collector code is absent.
- Real concurrent database stop/ingestion stress and live capture acceptance remain unrun; mocked regressions do not claim those results.
- Does not delete or repair previously contaminated stored ticks. Existing recording must not be relabeled as accepted evidence.
- A fresh finalized capture must establish required fields, eligible semantic labels, video-frame correspondence and stable bounds after stop; generate a sanitized golden fixture and verify coverage consumption.
- Raw manual clicks remain diagnostic until validated by existing semantic-action gates; no training label is manufactured.
- Hosted CI budget/token prerequisites remain unresolved from the last story report.

## Tracker and rollback

Existing Felix task task_1791760667867_s74wdsgf was reported spec_ready/in_progress by SC1976 comments. The configured connector cannot retrieve that task or its existing plan; no duplicate task or fabricated state transition was created. Follow-up plan note note_1791766579820_07107d5d was successfully stored under the canonical ChromaScape identity. Story remains In Progress; live acceptance is incomplete.

Rollback is a code revert; no migration or wire schema change. Reverting restores the observed contamination risk. Leave additive combat columns and existing recordings intact.
