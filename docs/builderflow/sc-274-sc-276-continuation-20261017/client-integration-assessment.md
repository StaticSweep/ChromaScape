# Client integration assessment, 17 October 2026

The existing SC-1991 path is the implementation to reuse for the actual client.
The current compiler candidate does not yet contain its complete presentation,
workspace and paced-control integration. A reachable debugger cannot be treated
as the finished candidate Watch experience. No integration merge or running
application restart was performed by this assessment.

## Inspected identities

- Compiler candidate: `C:\Dev\IdeaProjects\.worktrees\sc274-completion`,
  HEAD `c791c6d47102bf5971497ab5ece5f48883b79b82`, with existing dirty changes.
- Compiler SC-1991 reference: locally available
  `origin/codex/sc-1991-completion-20261011`, `6ab04515`.
  Merge base with the compiler candidate:
  `9968880cc402f755d3e4c2f98afa9a861aea24ce`.
- Native candidate: `C:\Dev\IdeaProjects\.worktrees\sc276-completion`,
  HEAD `1903922a1132e4a9f30dd6c630510b7eb523fc75`, with existing dirty changes.
  `git merge-base --is-ancestor` confirms that prior client implementation commit
  `e4594b0249537d3dc55c5e99b67600be04db619e` is an ancestor. This proves ancestry,
  not unchanged behavior under the current dirty source.

## Reuse and remaining integration

The native candidate retains `replay-client-projection.ts`,
`client/replay/ReplayClientPresentation.ts`, `replayAttachment.ts`,
session-scoped reset/action behavior, presentation bootstrap and frame support.
Its kernel and worker entry point have later changes. There is no
`client/build/replay-client-manifest.json` in the candidate, so a compatible
verified real-client package is not ready to serve from this checkout.

The compiler candidate retains durable session, execution, repository and worker
classes. The SC-1991 reference adds these complementary paths:

- `XrspsRealClientConfig`: verified real-client/cache assets and exact session pins.
- `XrspsClientPresentationController` and `XrspsClientIsolationFilter`: owner-scoped
  read-only presentation and the separate client transport boundary.
- `XrspsPlaybackController` and `XrspsSessionWorkspaceController`: paced commands,
  workspace and browser entry points.
- `XrspsAiTaskCoordinator`: bounded configured-provider execution and durable receipts.
- Extensions to `XrspsDurableSessionService`, `XrspsSessionDocument`,
  `XrspsSessionWorker`, `ReplayProperties` and local principal configuration.
- `xrsps-sessions.html`, client build scripts, browser verification harnesses,
  focused tests and the mandatory native acceptance task/workflow.

Integrate those changes against current session fencing, ownership, capability,
source scheduling, snapshot and outcome contracts. Review the compiler/curation
differences separately; importing the entire reference tree would overwrite newer
work. Retain the existing gateway and real OsrsClient rather than adding another
viewer. Build a paired clean client/worker package only after integration and
regress the domain hashes with viewer attachment, camera and reconnect variants.

The reference README records prior Linux authored/client/provider checks and an
explicit Windows skip. Its tested packages have earlier identities. Neither those
passes nor source ancestry qualify the current Windows candidate, genuine source
capture, final pin or current hosted CI.

## Computer-use recovery, 18 October 2026

The earlier `helper_sandbox_lock_failed` diagnostic is historical and no longer
blocks this session. Native `@oai/sky` initialization and `sky.list_apps()` both
succeeded on 18 October, returning application windows. No ACL, UAC or security
configuration changes were needed. This establishes helper availability only;
it does not establish client rendering, execution parity or human Watch sign-off.

The SC-1991 presentation, paced playback and workspace integration is now being
reconciled with the candidate's newer source scheduling and eligibility checks.
Current compilation, focused checks and final package qualification must pass
before the integration is described as delivered. The worker's explicit
incomplete closure/isolation/oracle qualification guard remains enforced.

## Remaining source and client qualification

2026-10-18 update: the per-file compiler integration is implemented and 169
focused Java tests pass across the integration and existing session regression
runs, with zero failures/errors/skips. Scoped formatting, full Checkstyle and
the native quests TypeScript check pass. See
`../sc-274-sc-276-continuation-20261018/implementation-evidence.md` and
`verification-evidence.md` for the precise scope and remaining gates.
The current candidate serves `/debugger` and `/xrsps-sessions` on 8090. Its live
provenance routes now return 200 for all three audited recordings, each with
`RUN_CONTEXT_REQUIRED`, eligible=false. This supersedes the earlier stale-build
404 observations retained below. Authenticated runtime discovery returns zero
micro6 runtimes, and the signed source-manifest request returns 503.

Computer helper inventory succeeds, but actual Firefox window selection is now
rejected with `Computer Use was not approved to use Firefox`. The current browser
connector exposes no targets. No page was inspected; client attachment and
human Watch are unverified. Native certification/control guards remain enforced.

Later supported-browser check on 18 October: the browser inventory again
returned no surfaces, and opening the live workspace through `iab` returned
`Browser is not available: iab`. The sandbox lock is resolved; supported browser
availability and the qualified package remain current gates. Subsequent build,
pin, worker and archive checks are documented in
`../sc-274-sc-276-continuation-20261018/client-package-continuation.md` and
`../sc-274-sc-276-continuation-20261018/worker-startup-followup.md`.

The read-only recording audit in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\existing-recordings-selection.md`
did not select an eligible genuine finalized immutable source and published
curation. Original `recording_b83c37e5` remains blocked. No full-catalog absence
claim is made. The eventual human browser Watch check must follow the integrated
candidate's automated checks and is not ready for sign-off now.

The candidate controller's exact read route was also checked separately:
`GET /api/recordings/v1/sessions/{recordingId}/provenance` returned 404 for the
three READY candidates from the earlier audit. Private raw responses and the
minimal structured result are retained at
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\current-provenance-route-probe`.
These running-application responses neither qualify its build nor prove that
source context is absent from storage. No provenance assignment was attempted.
