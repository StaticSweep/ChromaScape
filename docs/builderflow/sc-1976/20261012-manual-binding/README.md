# SC-1976 manual recording binding repair

Source: https://app.shortcut.com/leeuwbot/story/1976. Existing draft PRs: ChromaScape #59 and Micro6 #162.
Canonical Felix task: `task_1791760667867_s74wdsgf`, `in_progress/spec_ready`, `change:multi-repo`.
Authoritative plan: `note_1791760722731_aa69f615`; architecture: `note_1791760722755_73a4bd43`; API contract: `note_1791760722763_a35f9749`.

## Problem and scope

Recording `recording_4af7abd3` has an 85-second playable MP4 but zero saved ticks. The dashboard created a server session without binding Micro6. The temporary launcher also activated SC-968 routine capture, which repeatedly failed its independent settings identity requirement and suppressed Debug Recorder automatic session start.

Repair the debugger launch and dashboard recording lifecycle. Preserve normal SC-968 behavior, negotiated packet/ACK contracts, durable terminal batches, immutable finalized tick/time bounds, nullable unknown NPC HP/phase, diagnostic-only raw inputs, existing recordings, and unrelated processes/checkouts.

Exclude game authentication or gameplay automation, invented identity hashes, rewriting failed recordings, broad SC-752 UI, training/model promotion, and unrelated services.

## Linked rules

- `C:\Dev\IdeaProjects\AGENTS.md`: canonical identities, one Shortcut/Felix task, durable gated evidence and truthful synchronization. Never run `wsl --shutdown`.
- `C:\Dev\IdeaProjects\ChromaScape\AGENTS.md` and Windows restart runbook: restart only the owned verification instance.
- `C:\Dev\IdeaProjects\micro6\AGENTS.md`: scoped builds/tests/restarts authorized by the fix request; user performs login; verified revision 241 packaged client.
- SC-718/751: raw inputs are diagnostic; semantic actions alone can be labels; reuse tick/video calibration.

## Acceptance criteria

1. Given debugger-only launch, when the user logs in, then streaming uses the explicit loopback verification origin and SC-968 routine capture remains disabled.
2. Given zero or multiple idle recorder sockets, when Start Recording is requested, then it fails before creating a video/session.
3. Given one logged-in recorder, when Start Recording is requested, then normal SESSION_START binds the exact dashboard session and a new committed tick exists before HTTP success.
4. Given a disconnected, unsupported or busy recorder, when readiness fails, then the newly created recording is compensated; a reused session is preserved and success is not reported.
5. Given Stop Recording, when the terminal batch and normal SESSION_STOP settle, then ownership is released before finalization; timeout reports incomplete stop and safely closes the recording.
6. Given a finalized session, when inspected again after 60 seconds, then final tick/time bounds stay fixed and no evidence extends the interval.

## Implementation checklist

- Process-scoped `MICRO6_DEBUG_CAPTURE_ORIGIN` overrides debugger endpoints and disables automatic session start without enabling SC-968.
- `launch-sc1976-debug-capture.ps1` validates a loopback HTTP verification origin, disables routine capture, forces debugger streaming in process, and invokes the existing packaged revision-241 launcher.
- Add targeted `{type: "RECORDING_CONTROL", action: "START" | "STOP", sessionId, scriptName}` over the existing recorder socket.
- Dispatch Micro6 commands on the client thread. START refuses busy/logged-out/stale clients and supplies the preferred authoritative session through existing negotiation; STOP requires the current exact session.
- Server preflight selects exactly one idle socket, excluding start reservations. Commands cannot address another socket's binding.
- Coordinator waits up to 15 seconds outside DB/socket locks for exact binding plus a new committed snapshot. Stop waits for normal ownership release.
- Start rollback uses the existing operation token and preserves reused sessions; cleanup errors cannot mask the original readiness failure.
- Run meaningful endpoint, transport, lifecycle, readiness, rollback, stop and existing combat/storage regressions.
- Push only scoped story files to existing PR branches. Restart only verification server18090 and the owned Micro6 client; verify their build identities.
- Prove an actual short idle recording, required fields and post-stop stability before requesting another combat recording.

## Test checklist and evidence

ChromaScape: 310 selected combat/debugger/playback tests passed, zero failures/errors/skips, including real PostgreSQL migration/reload/locking checks. Four Docker-only suites were excluded. Scoped Spotless and Checkstyle main/test passed with repository warnings (4,270 main warning-severity findings).

Micro6: the final native JDK21 `:client:test --tests 'net.runelite.client.plugins.debugrecorder.*'` run passed all 289 tests, zero failures/errors/skips, with the canonical contracts composite and SC-967 vectors. Initial new-test attempts failed because of the fixture's Gson version, incomplete recorder initialization/socket teardown, and a primitive Mockito matcher; these were corrected before the successful final run. The earlier 1,673-test full client run predates this repair.

Packaged client preparation and launch succeeded from Micro6 commit `1f0d35d20a8888423f64ec272f516bdd85b44f09`, artifact `sha256:ca06f5da02a513097fcf70b3a97f65dc15baab679b62517c944bec178dd997e2`, source mode disabled. Startup logs confirm `ws://127.0.0.1:18090/ws/micro6/tick-stream`, and authenticated health confirms an open transport at the login screen. No settings-hash failure appeared in the inspected new startup interval. This establishes launch/routing, not saved tick acceptance.

ChromaScape code commit `59d20d696d13100777e365a5cc32209b487d1df7` runs on loopback18090 with owned schema `sc1976_live_20261012`; V68 was explicitly reapplied successfully, and the debugger returned HTTP200. This disposable schema uses Hibernate update with Flyway disabled and an unrelated replay worker disabled; it does not certify the production migration chain. Original8090 remained untouched.

Both PR branches were fast-forward pushed successfully. Current hosted ChromaScape run38298476626 and Micro6 run38298475891 did not start their jobs: GitHub reports an Actions budget preventing further use. The previously reported ChromaScape contracts-token prerequisite was not reverified.

After manual login, the first idle recording `recording_521df11d` / `debug_7e775dffbcc84d208bcb63ccdaf60fa2` started successfully and stored 23 real snapshots (ticks67–89, negotiated schema2). Every row served inputs, tiles, hazards, phase, specialAttack and prayersActive. The MP4 downloaded and fully decoded (769 frames, terminal decode time28.53s); finalized count and tick/time bounds remained unchanged after more than60seconds.

Stop returned HTTP504: an immediate manual terminal batch reused tick89 with a different batch identity/payload after that tick had already committed. The server correctly rejected it in the existing tick-winner receipt branch with BATCH_CHECKSUM_CONFLICT; Micro6 retained terminal-incomplete inventory. This first recording is diagnostic evidence, not an accepted smoke check or combat fixture.

Refined repair: active manual stop waits for the next actual GameTick, using distinct MANUAL_STOP_PENDING to preserve manual ownership while waiting. Repeated stop and new task/dashboard requests cannot force a same-tick terminal snapshot or rotate the session before that flush. The final batch still uses the normal durable ACK/session-stop flow. No fabricated tick increment or receipt/checksum bypass. New regression checks no same-tick collection, one next-real-tick batch and deferred stop until ACK.

The refined native JDK21 recorder suite passed 290 tests, zero failures/errors/skips (3m10s), with the canonical contracts composite and SC-967 vectors. Micro6 commit `a68cd31915dab86775369faeffcb3871968474e2` was fast-forward pushed to existing draft PR #162. Capture-contract verification and packaged preparation/launch passed. Authenticated runtime health confirms this commit, artifact `sha256:678fb0a00cefa59a8961091ec8ec52d26aab04ba9c86a9ddb1099880a9057e20`, source mode disabled, exact18090 socket and empty incomplete inventory. Only the verified prior client PID244684 was stopped before replacing its package; replacement PID59256 uses the story worktree package. Launcher log: `C:\Dev\IdeaProjects\.worktrees\sc1976-micro6\sc1976-nexttick-client.log`.

After manual login, recording `recording_3e171e40` / `debug_53eea8030e8142749746d478d4fafcdc` returned successful Start and Stop responses, persisted28 rows (ticks51-78, negotiated schema2), and finalized with no failure code. All28 served rows contain the required six field groups. Transport health remains ACTIVE with no pending/incomplete batches, no drops, no failure and no fail-closed state. Video downloaded159,039bytes and fully decoded (464frames, terminal decode time16.90s). Count and finalized tick/time bounds stayed unchanged more than60seconds after stop. The inspected10second frame shows the RuneScape welcome screen, so this capture verifies transport/storage/stop rather than gameplay video.

After the user entered the game world, repeat recording `recording_ef3542bb` / `debug_169b06c8743446d4bd230b366eaca7fb` also returned successful Start and Stop responses. It finalized33 persisted/served rows, ticks328-360, schema2, start `2026-10-12T22:07:11.784`, end `2026-10-12T22:07:31.225415`. All33 rows retain inputs/tiles/hazards/phase plus player specialAttack/prayersActive. No session failure, dropped batch, pending/incomplete batch, transport failure or fail-closed state appeared in authenticated post-stop health; transport remained ACTIVE/connected. This proves recorder release and successful successive recording without another restart. The152,039byte MP4 fully decoded (536frames, terminal decode time19.46s), and its10second frame confirms the actual idle game world. Count and finalized tick/time bounds stayed unchanged more than60seconds after stop. No combat was automated or accepted; raw gameplay artifacts remain private.

Latest Micro6 hosted run38301713966 did not start its build job: the check annotation again reports an Actions budget preventing further use. No hosted success is claimed.

Required checks: endpoint precedence/SC-968 isolation/invalid origins; validated targeted commands; client-thread handoff; preferred binding and matching stop; no/ambiguous socket preflight; new persisted-tick readiness; new-session rollback versus reused-session preservation; existing packet, combat, PostgreSQL and finalized-boundary checks; packaged runtime identity; idle MP4, field reload and 60-second stability.

## Operator sequence

1. Use the new committed paired builds and the Micro6 debugger launcher against `http://127.0.0.1:18090`.
2. Log in manually. Do not start a combat recording until the idle smoke check passes.
3. Open `http://127.0.0.1:18090/debugger`. Start Recording now waits for actual saved ticks; a failure means capture is not ready.
4. After idle verification, record approximately 1–2 minutes of manual combat including attack, movement, eating and a prayer toggle.
5. Stop through the dashboard, wait for finalization, and provide its recording URL/ID for storage, labels, video and coverage verification.

## Risks, rollback and remaining work

Older Micro6 clients cannot handle the new command and will time out safely. Multiple clients require an explicit existing owner or one idle recorder. The temporary environment affects only the launched process and leaves persisted settings untouched. The original8090 service remains a separate runtime.

Restore the prior source versions and remove the debugger-origin environment flag to roll back manual binding; retain additive DB columns and existing recordings. This restores the prior zero-tick risk, so avoid using dashboard recording as acceptance evidence afterward.

This idle repair does not complete SC-1976 acceptance. Genuine finalized combat capture, supported semantic labels, calibrated video correspondence, a sanitized accepted golden fixture, SC-752 coverage validation and hosted CI prerequisites remain. Previously observed Actions budget and ChromaScape read-only contracts-secret failures remain unverified until hosted checks run.
