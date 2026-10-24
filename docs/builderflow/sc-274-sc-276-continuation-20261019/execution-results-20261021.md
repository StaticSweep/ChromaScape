# SC-274 / SC-276 V21 execution results

Date: 21 October 2026. This records the implementation run against
[remaining-implementation-plan-20261021.md](remaining-implementation-plan-20261021.md).
Felix and Shortcut were explicitly skipped; no tracker state changed.

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261021`.

## Completed

* **Step 1:** restored the pinned registry worktree
  `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` at
  `58098690b83a3c0914016ddbe69344657401eb47`; recorded source/tooling hashes, listeners and
  the initial Docker outage (`step1-baseline-20261021-initial.*`), then the recovered
  baseline (`step1-baseline-20261021.*`).
* **Step 2:** issuer now runs as the registry UID/GID, the post-publication chmod is gone,
  and a Linux grants volume avoids Windows bind-mount assumptions. Live grants pass
  **24/24**, including 50/50 registry-identity reads and 43/43 ONLINE observations during
  5 complete-snapshot replacements. See `step2-publication-readability-20261021.md`.
* **Step 3:** deployment passes **12/12** and the authenticated source probe passes
  **11/11** from the registry container namespace. The manifest signature, 8180 bundle
  identity and referenced file hash all match. See
  `step3-authenticated-source-route-20261021.md`.
* **Step 4:** clock preflight self-test **4/4**; live preflight fails closed against the
  measured **48.229 s** skew and derives a **56.229 s** renewal margin without extending
  the TTL. See `step4-clock-renewal-20261021.md`.
* **Step 7 inventory:** focused authored suite **319 tests, 0 failures, 2 skipped**; the
  acceptance-to-test map and remaining native/genuine gaps are in
  `step7-acceptance-test-gap-inventory.md`.
* **Step 9 inventory:** six cross-branch migration collisions (V47-V49, V58-V60) and an
  internally inconsistent 82-row shared history were recorded without editing any
  migration. See `step9-migration-inventory.md`.

## Still blocked

WP6 supported allocation, WP7 original trust/genuine capture, the absent native candidate,
the historical decoder audit, ChromaScape discovery (8181 returns 404), migration
reconciliation from a clean released history, clock remedy/tolerance, packaged
client/Watch with human sign-off, and shared-rollout authorization. Full detail:
`remaining-blockers-20261021.md`.

No acceptance row is closed by this run. The 24/24 grant result is development-lane
evidence, not a Launcher allocation or shared deployment.

## ## Clock evidence correction (22 October 2026)

The Stage 5 renewal-window fields were reversed in the preserved
`stage5-clock-safety-20261021.*` artifacts. The correct values are
`renewAtRemaining = 56.229 s` and `latestElapsed = 63.771 s`; renewal starts before 56.229
seconds remain, equivalent to 63.771 seconds elapsed. See
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261021\attempt-20261021-remaining-plan\stage5-clock-safety-20261022-correction.md`
for the preserved hashes, derivation and fail-closed checker. No measurement, host/service
state or acceptance row changed.

## Review correction (21 October 2026)

Discovery 404 was measured against 8180, not ChromaScape 8181. The corrected 8181 call returns 200 with an expired, stale/unselectable experiment executor. The missing-endpoint and different-build diagnoses above are superseded. The clock helper also mislabeled elapsed renewal time as remaining time; remaining threshold is the required margin, and elapsed deadline is TTL minus the margin. Production clock synchronization remains unresolved. Container source credentials now travel through stdin rather than child-process arguments. Full changes, tests and limits: C:/Dev/IdeaProjects/verification/sc274-sc276-continuation-20261021/review-fixes-20261021.md. Original evidence is retained.
