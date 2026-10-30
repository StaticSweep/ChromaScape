# SC-274 capture handoff — 30 October 2026

## Subsequent service recovery

The database published-port failure behind the provenance API timeouts was recovered by
restarting the same PostgreSQL container, preserving its volume. The checker now returns
the expected RUN_CONTEXT_REQUIRED refusal for the legacy b83c input. Earlier timeout
statements below are historical. See [record-button recovery](sc274-record-button-recovery-20261030.md).
Capture context/profile/source prerequisites are still required.

The owner confirmed that the genuine recording still has to be made. A new recording is
separate evidence; it does not repair the historical b83c capture or close SC-274 by itself.

## Current readiness

- The requested TrumpSniper profile was launched in the existing packaged Micro6 client.
  Process 226132 owns the visible window and loopback executor 8098. Login-screen startup
  was observed; login and gameplay are the user's responsibility.
- This launch explicitly uses source mode disabled. It is not a trusted authoritative
  source-runtime deployment or a Launcher allocation, and no identity was invented for it.
- The current shared store has zero saved behavior profiles and zero context-bound
  recordings. Bootstrap availability reports disabled, no operator key and no approvals.
- Source/run-profile setup is therefore still required before an acceptance capture.
  A visible client and a video do not establish those prerequisites.

## Before starting capture

1. Resolve the real supported allocation/client identity and the exact published Fishing
   task/catalog/profile version. Save validated settings through the existing catalog flow.
   Freeze the graph revision if selected. Use actual identities returned by that flow.
2. Retain the source/runtime pins and any required approved signed bundle; this source-disabled
   client does not establish source trust. Preserve the original trust configuration.
3. Bind privacy policy/version/redacted fields and the intended dataset role. Keep credentials
   and account secrets out of task arguments, exports, evidence and tracker comments.
4. For the original SC-1498 provider-origin input, obtain the existing exact human approval
   for provider/account/task/budgets/target/one-use run scope. A general request to finish
   SC-274 is not that approval. A manual HUMAN demonstration must retain its actual origin.

## Start and finish the bounded session

1. Use the context-aware debug-session start flow, which calls
   `POST /api/debug/sessions/start` with recording enabled, actual task/settings and
   a non-null runContext from the real run/profile flow. The additive
   `POST /api/recordings/start` route also delegates to that atomic flow when runContext
   is present. Do not populate the request with made-up run/allocation/executor IDs.
2. Check the successful response contains both recordingId and sessionId and the bound
   runContext. Its recording/session binding and contextHash must be present. Do this
   before spending time on the Fishing capture. A tick-only or contextless start is
   not a usable SC-274 capture.
3. Keep the approved Fishing sequence short and bounded. Capture the initial player,
   inventory, XP, nearby Fishing NPC, exact semantic actions, receipts and tick/sequence
   quality. The current Fishing proof preflight expects exactly five positive catch
   inventory additions and increased Fishing XP. Stay inside the approved task/budgets.
4. End the same debug session through its normal session lifecycle
   (`POST /api/debug/sessions/stop` with the actual sessionId and last observed endTick),
   allowing capture-quality and recording finalization to finish. Stopping only the
   video is insufficient for the context eligibility check.
5. Review and publish one owned curation for the actual tick range and chosen dataset role.
   Keep unresolved world-state/action gaps explicit. A later observed target must not
   be inserted into the initial scene merely to make compilation pass.

## Read-only handoff checks

The local checker performs GETs only, prints safe check metadata and never starts,
assigns, publishes, changes or certifies a recording:

```powershell
python C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\socket-continuation\check-recording-input.py RECORDING_ID --curation-id CURATION_UUID
```

It reads the production provenance/eligibility endpoint, owned curations and selected
compiler preflight. Exit 2 means not ready; exit 3 means a transport/shape check failed.
Exit 0 is only INPUT_CHECKS_PASS_REQUIRES_REVIEW, never story acceptance. The checker
currently has no positive genuine recording to verify. Capture provenance reads on 8090
have timed out, despite static/bootstrap routes responding; a timeout must be investigated
before relying on the service for capture. No shared service was restarted here.

Return the actual recording ID, debug session ID and selected published curation UUID,
or the approved exported bundle path. Source/privacy review, compiler/native execution,
original travel acceptance and validated tracker completion remain separate checks.
