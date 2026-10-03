# SC-1813 Verification Evidence — 2026-09-26

## Acceptance results

- ChromaScape PR #38 merged as b78ecc75b5a4a830cd487419d270ac8c3e4e9d8d; Micro6 PR #156 merged as eb85bb7b0d0fe24fea53a5c1a7086474518aee7c. Both implementation heads were verified ancestors of origin/main.
- User authorized paired restart and live verification. ChromaScape was stopped through the original owning Fedora wrapper and started through the verified checkout's wrapper. Micro6 launched through the canonical v3 Linux adapter and packaged Gradle task.
- Executor /health reported source_mode=disabled, LOGGED_IN, requested_world=380, current_world=380, world_matched=true. This verifies ordinary v3 launch and world selection in the actual runtime.
- A 15-second /ws/viewport probe received 30 distinct in-game JPEGs (960x813, approximately 104 KB each). Decoded pixels were nonuniform; the image was visually inspected as in-game, not login/synthetic. Tick count advanced 49 -> 75 for the same session debug_c17212ca00084855849cad7fe977693d. Server source=client, ACTIVE, with zero attached/desktop drops. No input session was opened by this verification.
- Before subscription, real incoming frames reported IDLE_NO_SUBSCRIBERS. Subscription produced ACTIVE and forwarded frames.
- Feed pause/resume probe: 6 active frames, 0 paused frames, 8 resumed frames. Paused forwarding delta=0, state=DISABLED; after resume state=ACTIVE. Feed left enabled.
- Controlled client exit: received/forwarded counters stopped at 238/60; state became WAITING_FOR_SESSION. The server finalized that session after its transport reconnect grace: FINALIZED, endTick=187, failureCode=null. This proves disconnect/exit cleanup; a live SESSION_STOP acknowledgement is not claimed (explicit stop ordering/ACK behavior is covered by the automated lifecycle tests).
- The client was relaunched after the exit probe. Final restored runtime state is recorded in the completion follow-up below.
- Browser automation is unavailable on this host (no enabled browsers/apps). HTTP /debugger returned 200; JavaScript tests passed. Direct browser visual acceptance must be distinguished from the verified live WebSocket/pixel path.

Sanitized machine-readable results: live-proof-20260926.json. Pixel data and account credentials are not committed.

## Tests

180 server Java passed / 11 platform-dependent skips; 77 client Java passed; 246 JavaScript passed / 3 skips; 7 Python launcher tests passed. Spotless and Checkstyle passed as configured. Both launcher task graphs, v3 contract query, actual compile-only package preparation and validate-only task discovery passed. Paired fixture comparison and Micro6 repository hygiene passed. See implementation-evidence-20260926.md for exact suites and commands.

Hosted GitHub checks on both implementation heads did not run: check annotations explicitly report an Actions budget preventing further use. There is no hosted CI pass.

## Risks

The Fedora runtime database initially had an older V46 source-binding migration, whereas merged main uses V46 for packet-FK cascade and V52 for source binding. Original V46 source-binding SQL and current V52 SQL were compared byte-for-byte and are identical. The original database chromascape_fedora03 was preserved; an isolated clone chromascape_fedora03_streaming_v3 was created on the existing local PostgreSQL instance.

Only in the clone, the matching successful source-binding history row was moved from version 46 / V46__sc810_builder_goal_source_binding_state.sql to version 52 / V52__sc810_builder_goal_source_binding_state.sql. Its checksum -1786917258 and original application metadata were preserved. Exactly one row changed. Flyway validation remained enabled. The actual current V46 packet-FK migration then applied normally with checksum -1825633474. Both 46 and 52 now report success. No migration files were rewritten and no blanket Flyway repair/validation bypass was used.

The new ignored .env.fedora.local points to the clone and paired client checkout; original DB/config/dirty trees remain intact. Native library paths and local credentials were preserved without publishing values. Windows execution and RPM recertification were not performed. The actual observed game client was idle in-game; this verification did not dispatch a new automated game task.

## Rollback

Stop the new ChromaScape instance through its own scripts/fedora/chromascape-fedora.sh wrapper, stop only the verified client process, and restart the original owning checkout with its unchanged configuration/database if necessary. Alternatively revert the paired merge commits and redeploy. Keep the original database and the clone until the operator chooses retention; no database deletion was performed.

Runtime log locations (canonical project identities; native checkout suffixes select the isolated worktrees on this host): ChromaScape .chromascape/fedora/chromascape-bootrun.log; Micro6 .micro6/verified-relaunch4.log. Both are ignored operational artifacts, not committed credential-bearing logs.

## Completion follow-up

The final canonical relaunch reached the client UI after a rebuild. One preceding relaunch reported NoClassDefFoundError for joptsimple.ValueConverter; the packaged JAR contained that class, its direct --help smoke passed, and the subsequent canonical rebuild/launch succeeded. This transient failure was not reproduced or silently claimed fixed by a source change. Runtime health and final probe results are appended below.

Felix is in_review; Shortcut sc-1813 review synchronization succeeded with comment 1816. Verification note: note_1790392663436_074e9faa. All feature acceptance checks have live/automated evidence; the separate browser-visual test remains pending rather than being fabricated.

Restored executor PID 3180520 is healthy in disabled source mode and awaits manual login. The client startup log confirms --world 380; the final pre-login health snapshot does not yet report a current world. The earlier in-game proof independently verified requested/current world 380. The live browser confirmation has been requested from the user.
