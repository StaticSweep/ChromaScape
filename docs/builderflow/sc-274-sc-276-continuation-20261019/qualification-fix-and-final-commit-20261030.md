# Qualification fix, source commit and certifiable identity — 30 October 2026

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\final-commit-791f7a3d\`

## Changed files

Native repository `C:\Dev\IdeaProjects\xrsps-typescript`. Committed as **791f7a3d**
on `main`, local only — no push, no merge, no service restart, no database or credential
operation.

| File | Change |
|---|---|
| `client/replay/replayTraceValidation.ts` | Plain-HTTP trace path uses the vendored SHA-256 fallback |
| `client/tests/replay-observer-validation.test.ts` | Known-answer vectors and tamper rejection for the fallback |
| `client/replay/vendor/noble-hashes/*` | Vendored SHA-256 fallback with LICENSE and NOTICE |
| `packages/replay-kernel/src/contracts/capabilities.ts` | Capability contract carries the spell-scope surface |
| `server/src/replay/authoritative-replay-capabilities.ts` | Production construction refuses a generic `CAST_SPELL` claim |
| `server/src/replay/spell-scope.ts` | Bootstrap-safe exact scope metadata, no gameplay imports |
| `server/src/replay/qualification-component-outcome.ts` | Structured, identity-bound component result validation |
| `server/src/replay/qualification-runner.ts` | Structured acceptance required; status must agree with the recorded command outcome |
| `server/src/replay/qualification-plan.ts` | Oracle matrix and independent package builds are not dischargeable by unit-test exit status |
| `server/src/replay/oracle-qualification-input.ts` | Bounded capture/replay artifact parsing; inputs cannot supply approvals |
| `server/scripts/evaluate-oracle-qualification.ts` | Actual matrix evaluation instead of unit-test exit status |
| `server/scripts/run-replay-qualification.ts` | Forwards `--oracle-input`; records the artifact digest |
| `server/tests/{qualification-runner,spell-scope,oracle-qualification-input}.test.ts` | Status forgery, stale identity, exact scope, bounded input regressions |
| `docs/replay/sc2201-linux-qualification-handoff.md` | v2 evidence protocol and integrity-versus-authority limits |

Regenerated build artifact `packages/replay-kernel/src/generated/runtime-identity.ts` is
git-ignored and produced from the clean commit:

- `buildCommit` `791f7a3d4c41da4e38e726bfb070ca6e7aed0c18`
- `cacheRevision` `sha256:5300de2ec69afcf33b6b2cfc686034afe601a740718d179d5ea8f1cd5a1d2b41`
- `contentRevision` `sha256:d8da821db87dc5c6640a5c5c6e5b9a26af65b70de0041d521471de9fe617d126`
- **`certifiable: true`** — previously the candidate identity was non-certifiable because the
  tree carried unstaged tracked edits and the untracked vendored fallback.

## Tests

Run on the committed tree, Node 22.19.0, Windows x64, serial test files, using the
repository's matching Windows esbuild 0.28.1 via `ESBUILD_BINARY_PATH` (the checked-in
`node_modules` carry only the Linux binary and cannot run on this host).

- **59 passed, 0 failed, 0 skipped, 0 cancelled**, exit 0 — `final-tests.log`.
- `server/tsconfig.quests.json` typecheck exit 0 — `server-typecheck.log`.
- Focused test/script typecheck including the existing `java-random` declarations exit 0 —
  `tests-scripts-typecheck.log`, `tsconfig.review.json`.

A first attempt without `--test-concurrency=1` failed 11 of 21 file-level cases because the
files were scheduled in parallel against shared worker ports; the serial run is the valid
result. A first attempt without `ESBUILD_BINARY_PATH` failed on the platform-mismatched
esbuild binary; that is an environment fault, not a source regression.

## Acceptance results

- Qualification runner on the committed tree: **2 PASSED (`module-guard`, `read-guard`),
  7 NOT_RUN → INCOMPLETE**. `qualification-run/qualification-result.json`,
  `qualification-run.log`.
- The two identity blockers previously reported by the runner are gone; the only remaining
  reason is the genuinely incomplete qualification work.
- `AUTHORITATIVE_RUNTIME_QUALIFICATION.complete` **remains false**. No gate, capability
  fidelity, `certifiable` capability flag or `controlAllowed` value was raised.
- No story row is closed. Ledger statuses are unchanged: 274 rows PARTIAL/BLOCKED/NOT_RUN,
  276 rows PARTIAL/BLOCKED.

## Risks

- A certifiable build identity is not qualification. File digests prove integrity, not
  provenance or authority.
- The vendored SHA-256 fallback is now committed; it must be reviewed for licence and
  supply-chain acceptance like any other dependency.
- The evidence was produced on Windows against a working tree whose `node_modules` are
  Linux-built. The recorded commands and the esbuild override are part of the environment;
  reproducing them elsewhere requires the same pin.
- SC-2208 (TSPS integration) may still change the pinned code and force requalification.

## Remaining work

Unchanged by this pass: independent references for the 18 method rows and the 10 unadvertised
spell methods; Linux sandbox, aggregate resources and durability (SC-2201, needs an owner and
a non-WSL2 host); genuine allocation and source trust; real client/provider and Watch
sign-off; released migrations; clock and renewal ownership; final reviewed gate transition
and delivery.

## Rollback

Revert commit `791f7a3d`; the generated identity file is untracked and regenerates from
whatever commit is checked out. No push or shared-state change occurred, so rollback is local.
