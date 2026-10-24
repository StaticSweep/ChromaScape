# SC-274 / SC-276 publication verification - 24 October 2026

## Implemented scope

The user authorized publication of the entire ChromaScape SC-274/SC-276 candidate, including
the older compiler dependencies required by the follow-up tests. It contains compiler/native
contracts, source/provenance integration, session/client integration, recovered tests and
implementation/verification plans, plus the latest artifact/model/provenance and JVM-cleanup
fixes. The separately labelled SC-666 Gradle task remains uncommitted in the original worktree.

Publication target: origin branch `codex/sc274-completion-20261015`. This is a review
candidate, not a merge, deployment, production qualification or acceptance closure.

## Changed files

The candidate commit is b69a668b; whitespace cleanup is 9dfac340. There are 176 changed
candidate paths relative to c791c6d4 before this documentation-only publication record.
Key follow-up files: ImmutableArtifactStore.java, FishingDatasetBuilderService.java,
FishingActiveModelServiceTest.java, FishingDatasetBuilderServiceTest.java and
ScenarioManifestFreshJvmDeterminismTest.java with its fixture/child helpers.

Corrected the unsupported Windows durability Javadoc. The compatibility path is not
qualified for immediate power-loss persistence; details and primary documentation are in
[publication-review-20261024.md](publication-review-20261024.md).

## Tests actually run

Clean detached checkout of source commit `9dfac34051a8cb9f8d5f2b11210238cb530344af` at
`C:\Dev\IdeaProjects\.worktrees\sc274-publish-verify-20261024`:

- Gradle artifactCompletionTest and expanded affected test selection: BUILD SUCCESSFUL,
  716 unique Java tests, **699 passed, zero failed, 17 skipped**. Repeated cases across
  source sets are counted once in that union.
- Matched all **32** formerly failing cases by suite and test name: **32 now pass**.
- Artifact completion suite: **61/61 passed**.
- Fresh-JVM determinism/cleanup suite: **6/6 passed**.
- Debugger JavaScript: **14/14 passed**; changed JS/MJS syntax: **7/7 files passed**.
- Git diff whitespace validation passed after scoped historical-note cleanup.
- Candidate scan: no matches for 18 selected current sensitive values, no files over 5 MB.
  Ignored environment files and protected secret storage are not included in publication.

The 17 skips are explicitly gated native durable-session/PostgreSQL integration cases:
SC1991_WORKER_ENTRYPOINT and SC1991_TEST_JDBC_URL were not supplied. They are not passes.
This does not claim that the entire repository or final acceptance matrix ran.

Structured results: [publication-verification-20261024.json](publication-verification-20261024.json).
Raw logs/XML and candidate scan:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\publish-review-20261024T030104Z`.
The final publication record changes documentation only; tested source is unchanged.

## Remaining work and risks

1. Windows post-link power-loss durability remains unqualified; NTFS journaling is not
   evidence that the link is persisted before return. Select a supported implementation/
   platform contract before production qualification.
2. Portable source/dependency/package inputs, historical 27-path recovery boundary and
   owned compiler build/service location.
3. Remaining advertised native/compiler method parity, provenance/privacy/state gates.
4. OS sandbox, complete scenario resource/cancellation limits, throughput and closure
   deadline/load reproducibility.
5. Released migration authority and actual Flyway installation/upgrades/data preservation.
6. Sustained clock/renewal operations and operational ownership.
7. Supported allocation, approved original-trust artifact or amendment, genuine context/
   stabilization/capture and authorized login where required.
8. Paired package/Watch/noninterference, human sign-off, final frozen-input matrix and
   separately authorized delivery. Repository-wide Spotless debt remains recorded; it
   was not mass-formatted or rerun during this publication check.

No acceptance row is closed. Shared services/databases, trust roots, replay settings,
game sessions, Felix and Shortcut were not changed. Native repository changes are not
published by this ChromaScape branch push.

## Rollback

No force push or shared deployment. Supersede candidate commits with reviewed follow-ups;
retain evidence, unrelated user work, grants/fence state and high-water generations.
Remote publication is confirmed only by a successful push and matching remote commit
record in the local evidence directory; a dry-run alone is not publication proof.
