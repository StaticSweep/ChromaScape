# SC-810 Phase 4 — integration lineage reconciliation (2026-09-11)

## Finding: the packaging branch had diverged from the actual delivery lineage

`micro6-sc810-package` (branch `sc810-package-20260910`, used for every rebuild/re-sign cycle
so far this session) and `micro6-sc810-integration` (branch `sc810-integration-20260911`,
built on `sc-572-remediation` — confirmed via `git merge-base --is-ancestor sc-572-remediation
sc810-integration-20260911`) are **sibling branches that diverged at a common ancestor
(`90f793e83b`)**, not one built on the other:

```
git merge-base --is-ancestor sc810-integration-20260911 sc810-package-20260910  -> false
git merge-base --is-ancestor sc810-package-20260910 sc810-integration-20260911  -> false
```

Each side independently carries the same logical SC-810 feature commits (bundle-generation
task, drop `--build-id` assertion, real-artifact bundle-loading proof, `Micro6-Build-Id`
stamping, `Sc810RealBundleVerificationTest` repoint, packaged-JAR catalog-discovery fix) as
separate, differently-hashed commits — evidence of independent cherry-picks/reapplications
rather than a merge. But the trees are **not equivalent**: diffing the last logically-matching
commit on each side (`a2b58bcd33` vs. `c6dfbaaf15`) shows the integration branch has additional
content the packaging branch lacks — a 306-line change to `UniversalTaskDiscoveryService.java`
and a new 250-line test file, `UniversalTaskDiscoveryServiceJarDiscoveryTest.java`, neither of
which exist on `sc810-package-20260910`.

**Consequence**: every artifact/bundle rebuilt so far this session
(`closure-plan-phase3-5-6-7-8-rebuild-20260911.md`, digest
`sha256:12ef96ad055097a469f80a052b36eab7375e6a0f96f293fb9db01bb398d97f3c`) was built from the
wrong lineage — the packaging branch, not the `sc-572-remediation`-based integration branch
this plan's Phase 4 requires. That artifact/bundle is superseded, not just by a hygiene
cleanup, but by missing source content.

## Repository-hygiene gap on the correct lineage

The integration worktree was clean (no uncommitted changes) but had never received the
repository-hygiene cleanup — it still had the same class of accidentally-tracked generated
files. Diffed its full polluted-file scan against the already-removed list from
`sc810-package-20260910`: **identical 410-file set, zero files unique to either side**. Mirrored
the same removal and `.gitignore` rules here (commit `95e7bd20da`), rather than re-deriving the
classification from scratch, since the file set was verified byte-identical by path.

## Final integration identity for all subsequent phases

| Field | Value |
|---|---|
| Branch | `sc810-integration-20260911` (durable, not detached) |
| Commit | `95e7bd20da618f0c4b4919b0009ca26f1f53e5a6` |
| Tree | `278a6a51f089d2eab589ee164ba83c4571570dfe` |
| Worktree | `C:\Dev\IdeaProjects\micro6-sc810-integration` |
| Status | Clean (`git status --porcelain` empty) |

All remaining phases (final artifact rebuild, source bundle, packaged-runtime gate, ChromaScape
dispatch reconfirmation) must be built from this worktree/commit, not
`micro6-sc810-package`, which is now superseded for identity purposes.

## Not done in this phase

- No merge was performed between the two branches. The additional integration-branch content
  (`UniversalTaskDiscoveryService` changes) was not reviewed line-by-line for conflicts with
  anything packaging-branch-specific, because there was nothing packaging-branch-specific to
  reconcile *into* it — only the hygiene commits needed mirroring, and they applied cleanly
  with no overlap.
- `sc810-package-20260910` was left as-is (not deleted, not rebased) — it may still be
  referenced by earlier evidence files under its own now-superseded identity; those files are
  not retroactively edited, per the evidence-index convention of correcting via follow-up
  rather than rewriting history.
