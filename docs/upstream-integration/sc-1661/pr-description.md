# SC-1661 — Reconciled baseline and verified handoff

The Fedora routing and replay-fixture attributes were missing from the consolidation baseline. This change carries the attributes and adapts routing while preserving consolidation behavior, then supplies the reconciliation ledger, compatibility decisions and reproducible validation evidence.

## Stacked review structure

Base branch: `codex/sc-1661-consolidation-base`, pinned at `583a05b91687d156b1baa52dbda2b219604c7b39`.
Head: `codex/sc-1661-chromascape-upstream-reconciliation`.
The base has its own consolidation PR to `integration/chromascape-consolidated-20260901`; no existing target branch is advanced outside that review.

Prerequisite: https://github.com/ikiddoi/ChromaScape/pull/3. This story: https://github.com/ikiddoi/ChromaScape/pull/4.

Historical measurement at exact head `0387c4d` against `471262b`: 49 commits / 510 changed files, comprising 41 inherited consolidation commits and 8 SC-1661 commits. Against `583a05b`, the SC-1661 PR excludes those inherited commits. Later evidence/documentation commits change the counts; the live GitHub diff is authoritative.

## Revisions and changes

- Code baseline: `966e0e3e47db71fe5d57629205efcb23cd11e026`.
- Attributes carry: `45f943f`, source `d5b2d82`.
- Fedora routing adaptation: `966e0e3`, sources `97284f1` / `ced7487`. Uses the actual ClientState field, preserving newer baseline APIs.
- Upstream integration source: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`.
- Documentation/review tip advances separately; production source is unchanged after the code baseline.
- Story: https://app.shortcut.com/leeuwbot/story/1661

## Validation

Compilation passes. The six launcher suites were rerun with recompilation: **175 tests, 0 failures/errors/skips**, supported by retained sanitized JUnit XML in `evidence/launcher-junit/`. Final-code checkstyle exits 0 with 3291 warnings (11 added MissingJavadoc findings, disclosed). The complete 16-file Spotless offending sets match the candidate baseline.

Ordinary suite: candidate 2250 / reconciled 2255 total tests, both 110 failed and 13 skipped. Replay: 280 total, 12 failed. Paired failure identity sets are retained. No additional failures were observed; blocked database tests cannot establish absence of regressions. Runtime Fedora qualification remains downstream work.

All source inventory, adjudication, upstream ledger, compatibility map and checksums are under `docs/upstream-integration/sc-1661/`.

## Deferred work and delivery

- SC-1696: forward migration for independent V33 JSON size limits.
- SC-1697: diagnose 18 inherited assertion/behavior failures (16 gameplay/provider, 2 other).
- Felix synchronization is blocked by canonical project resolution, not missing transport: `C:\Dev\IdeaProjects\ChromaScape` is rejected as Project not found on this server. No Felix transitions are claimed.

## Rollback and scope

The attributes and routing carries can be reverted individually; preserve the original consolidation branch. No original dirty worktree was changed. This PR does not implement Linux RemoteInput, discovery or rendered-buffer capture. Merge follows review of both stacked PRs.
