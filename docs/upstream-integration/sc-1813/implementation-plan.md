# SC-1813 paired streaming and launcher reconciliation

## Scope and exclusions

Complete the paired ChromaScape #38 / Micro6 #156 drafts against current main. Preserve the original running, dirty Fedora worktrees. Deliver bounded view-only client pixels, lifecycle and source arbitration, visible feed diagnostics, and genuine launcher contract v3 support. Exclude MP4 changes, database migrations, RPM recertification, authentication automation and unrelated features.

Felix task: `task_1790373482776_czm675c9j`, canonical project `C:\Dev\IdeaProjects\ChromaScape`; Shortcut sc-1813. Root and repository AGENTS.md govern delivery. The user request authorizes focused builds and tests. User handles any game login.

## Implemented plan

1. Integrate current main into both isolated draft branches; preserve the active runtime checkouts.
2. Freeze paired screen-frame/v1 capability fixtures: version 1, per-session stream ID, 768000 JPEG bytes, longest edge 960, maximum 5 fps, initial enabled state.
3. Micro6 copies a rendered image into owned memory, encodes on a dedicated worker, and permits only one outstanding render/encode operation. Cancel stale callbacks and fence disable, stream rotation and shutdown. Config defaults: enabled, 2 fps, 960 px, JPEG quality 70.
4. Coalesce outbound frames, discard them before durable traffic, and reject new frames during stop/recovery. Retain tick ACK/retry behavior. Missing or unsupported capabilities disable only pixels.
5. Server reserves bounded frame work outside the socket lifecycle monitor. Validate socket/session/stream, typed envelope, sequence/rate, size and JPEG metadata before allocating a decoded raster. Recheck identity, feed generation and source at actual broadcast.
6. Route unchanged JPEG bytes through the existing bounded binary viewport pipeline. Attached input capture wins over explicit desktop Watch, which wins over client pixels. Frame streaming creates no input session.
7. Expose feed state, source, age, counters and rejection reason in Observe. Distinguish valid frames without a viewer from a silent sender.
8. Implement launcher v3 in Python/Linux and PowerShell: normal task forces source mode disabled; authoritative task generates source bundles and enables the source catalog. Invalid modes fail. Preserve world forwarding and secure argument-file handling.
9. Run focused lifecycle, transport, rendering, launcher, UI and static checks. Push and merge the paired PRs after local gates pass. Track live in-game/browser acceptance separately from synthetic tests.

## Given / When / Then acceptance

- Given an active client without an attachment, when it renders, then valid client pixels can reach the existing viewport without acquiring input.
- Given stale, malformed, oversized or wrong-identity frames, when received, then they cannot mutate the viewport.
- Given stalled rendering, encoding or transport, when more ticks arrive, then capture stays bounded and tick/stop semantics remain intact.
- Given stop, disable/resume, reconnect or source replacement, when old work completes, then its pixels cannot publish into the new lifecycle.
- Given an older peer without capability, when a debug session starts, then ticks remain usable and pixels stay disabled.
- Given no viewport subscribers, when valid pixels arrive, then diagnostics report idle-no-subscribers rather than a broken sender.
- Given ordinary launch, when source bundle environment is present, then it still runs with source mode disabled; authoritative launch explicitly selects its own task and mode.
- Given the final paired runtime on Fedora, when a real game session streams then stops, then a bounded viewport probe must show advancing pixels tied to that session and clean stop. This is a separate live acceptance gate.

## Test checklist

Focused Java lifecycle and source arbitration tests; Micro6 render/transport/source-mode/world/first-tick tests; Python launcher tests; JavaScript debugger tests; Spotless and Checkstyle; launcher task graph checks for both modes; compare shared fixture bytes. Record actual results in verification evidence. Windows execution and live in-game/browser checks must not be inferred from Linux unit tests.

## Operations and rollback

Use the owning Fedora wrapper and the repository restart runbook for ChromaScape. Preserve ignored local configuration, DB identity and source credentials. Launch Micro6 through the canonical launcher; `MICRO6_SOURCE_MODE=authoritative` selects source bundles, otherwise disabled. Both peers need the paired v3 launcher change before server-managed launches.

Debug Recorder config controls Live screen frames, frame rate, longest edge and JPEG quality independently of ticks. Server Feed disables client pixels; Desktop Watch selects a view-only desktop source; attached input remains highest priority. Observe reports feed/source/age/counters/reason, and viewport diagnostics report subscriber and backpressure counts. Never log image payloads or tokens.

Rollback by reverting the paired merge commits and restarting only their owned processes with preserved configuration. Do not revert unrelated changes or delete active runtime data. Original worktrees remain available until replacement verification is complete.
