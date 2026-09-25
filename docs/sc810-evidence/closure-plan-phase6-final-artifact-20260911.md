# SC-810 Phase 6 — final authoritative artifact (2026-09-11)

## Scope

`micro6-sc810-integration` (the corrected `sc-572-remediation` delivery lineage — see
`closure-plan-phase4-integration-lineage-20260911.md`). Logs redirected outside the Git
worktree (session scratchpad), per this phase's explicit instruction and the lesson from the
earlier bundle-generation failure (`closure-plan-phase3-5-6-7-8-rebuild-20260911.md`) where
this session's own log redirection had dirtied a different worktree.

## Real finding: the build dirtied a tracked file mid-identity-derivation

First build attempt (from commit `95e7bd20da`) succeeded, but `git status` afterward showed:
```
 M runelite-client/.micro6/gameplay-v2-reservations/gameplay-v2-reservations.snapshot.jsonl
?? runelite-client/.micro6/gameplay-v2-reservations/gameplay-v2-reservations.lock
```
Traced the cause: `FileGameplayV2ReservationStore` (constructor at
`FileGameplayV2ReservationStore.java:141-143`) maintains an event-sourced ledger — journal +
periodic snapshot + lock file. The snapshot's timestamp (`17:24:38Z`) predates this build
(`17:31:28Z` per the sidecar), confirming the mutation actually came from Phase 5's test suite
(`FileGameplayV2ReservationStoreTest`) writing to the real default store path rather than an
isolated temp directory — a leftover dirty file this build then inherited, not something the
packaging build itself wrote.

Only the `.snapshot.jsonl` half was ever tracked (both a root-level copy and a
`runelite-client/`-level copy); the journal and lock files were already correctly untracked.
This is exactly Phase 6's own stop condition ("If the build writes any output into tracked
source locations, stop and fix the build hygiene before signing"). Fixed:

1. Reverted the dirty modification (`git checkout --`), removed the stray `.lock` file.
2. `git rm --cached` both tracked snapshot copies (kept on disk, just untracked — this is real
   runtime state a running executor may still want locally).
3. Added `**/.micro6/gameplay-v2-reservations/` to `.gitignore` — anchored with `**/` so it
   matches at any depth (a first attempt without the `**/` prefix only matched the repo-root
   copy, per git's gitignore anchoring rules: a pattern containing a non-trailing `/` is
   anchored to the `.gitignore` file's own directory unless prefixed `**/`).
4. Committed (`6e271ce40c`), then rebuilt from this new commit.

## Final artifact identity (from commit `6e271ce40c`, after the fix)

| Field | Value |
|---|---|
| commit_id / build_id | `6e271ce40cbccbde224fffe73a86a3f4df61a40c` |
| tree_id | `c69147e678076735f278b079e52910a7446e441b` |
| artifact_path | `C:\Dev\IdeaProjects\micro6-sc810-integration\runelite-client\build\libs\client-1.12.39-SNAPSHOT-shaded.jar` |
| artifact_digest | `sha256:8db7a4747f6f6fe3ef9a9d959af44570d7f975ef0c80339507687208f2ef09ee` |
| `META-INF/MANIFEST.MF` `Micro6-Build-Id` | `6e271ce40cbccbde224fffe73a86a3f4df61a40c` |

All required equalities verified independently, not inferred from build success:
- `Micro6-Build-Id` (extracted from the built JAR's manifest via `unzip -p`) equals the final
  commit exactly.
- Sidecar `build_id` equals the final commit exactly.
- Sidecar `artifact_path` names the exact JAR that was hashed.
- Sidecar `artifact_digest` equals a direct `sha256sum` of the JAR, computed independently.
- `git status --porcelain=v1 -uall` was empty both before and after this (second) build —
  worktree genuinely stayed clean through identity derivation this time.

Build command (native Windows, `--no-daemon`, logs to the session scratchpad, not the repo):
```
gradlew.bat :client:shadowJar :client:micro6ArtifactIdentity `
  -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --no-daemon --console=plain
```
`BUILD SUCCESSFUL in 1m 30s`.

## Net effect

This supersedes every earlier artifact digest recorded this session
(`sha256:bfd29589...` from the original Phase 8, `sha256:12ef96ad...` from the first
packaging-branch rebuild). `sha256:8db7a4747f...ef09ee` / commit `6e271ce40c` is the current
final artifact for all subsequent phases.
