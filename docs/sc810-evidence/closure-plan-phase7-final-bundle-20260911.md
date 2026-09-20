# SC-810 Phase 7 — final signed source bundle (2026-09-11)

## Scope

Generated from `micro6-sc810-integration` at the final artifact commit
`6e271ce40cbccbde224fffe73a86a3f4df61a40c` (`closure-plan-phase6-final-artifact-20260911.md`).
Bundle root outside Git: `C:\Dev\SC810\package-20260910\source-bundle-root`. Signing/verification
env loaded via `C:\Dev\SC810\secrets\source-signing-v1\load-sc810-signing-env.ps1` in the same
PowerShell invocation as the build (private key never printed, logged, or recorded).

## Environment choice

Per this phase's own instruction ("prefer `local-development` for isolated acceptance with the
development key; use `production` only if the key is formally approved as a production trust
root"): the signing key (`sc810-dev-ed25519-611ab9dd95a518af`) is explicitly a development key,
never adopted as a production trust root (per `closure-plan-phase3-5-6-7-8-integration-and-final-artifact.md`).
Every live ChromaScape check this session (including the dispatch-readiness confirmation,
`closure-plan-phase10b-...`) has correspondingly used `CHROMASCAPE_SOURCE_ENVIRONMENT=local-development`
for the *runtime trust* setting, and Phase 9 (below) will do the same. The bundle's own
`environment` field, separately, is a fixed `"production"` string baked in by the generator
itself (`TaskSourceBundleGenerator`) independent of any env var this session controls — matches
every prior bundle generated this engagement, not a new choice.

## Generation

```
gradlew.bat :client:generateAllTaskSourceBundles `
  -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --no-daemon --console=plain
```
`BUILD SUCCESSFUL in 6m 43s`.
```
bundle_id=srcbundle_0a49cff0d280eef221c929b9
FISHING_TRAINING=sha256:03820e6cbd3fc56ca8a1ad4ec77ac1036e40cfd821d8a60d67c36bd11b313c95
```

`bundle.json`:
```json
{"artifact_digest":"sha256:8db7a4747f6f6fe3ef9a9d959af44570d7f975ef0c80339507687208f2ef09ee","build_id":"6e271ce40cbccbde224fffe73a86a3f4df61a40c","bundle_id":"srcbundle_0a49cff0d280eef221c929b9","environment":"production","manifests":[{"path":"manifests/FISHING_TRAINING.json","source_manifest_hash":"sha256:03820e6cbd3fc56ca8a1ad4ec77ac1036e40cfd821d8a60d67c36bd11b313c95","source_snapshot_id":"srcsnap_03820e6cbd3fc56ca8a1ad4e","task_key":"FISHING_TRAINING"}],"schema":"task-source-bundle/v1"}
```
`artifact_digest` and `build_id` match the Phase 6 final artifact exactly. Promoted with a
`COMPLETE` marker; `current.json` confirmed pointing at `srcbundle_0a49cff0d280eef221c929b9`
directly (not inferred from build success).

`git status --porcelain=v1 -uall` was empty both before and after — worktree stayed clean
through bundle generation (the Phase 6 runtime-state fix holds under this task too).

## Verify-only re-validation of the promoted bundle

No dedicated verify-only CLI mode exists in `TaskSourceBundleGenerator` (checked directly —
no `--verify`/mode argument). Per the original Phase 8 evidence's documented behavior,
`promoteBundle` re-validates the *entire existing directory from disk* whenever the target
bundle already exists, so re-running the same task is the verify-only re-check. Reran:

```
BUILD SUCCESSFUL in 2m 15s
bundle_id=srcbundle_0a49cff0d280eef221c929b9
FISHING_TRAINING=sha256:03820e6cbd3fc56ca8a1ad4ec77ac1036e40cfd821d8a60d67c36bd11b313c95
```

**Identical `bundle_id` and manifest hash on rerun** — deterministic regeneration, and the
second run re-validated (signature, digest, snapshot) the already-promoted bundle from disk
without error. Worktree confirmed clean again afterward.

## Net effect

This is the current final signed bundle for SC-810: `srcbundle_0a49cff0d280eef221c929b9`,
bound to artifact digest `sha256:8db7a4747f...ef09ee` / commit `6e271ce40c`. Supersedes
`srcbundle_1f0d63ec65164d6d5c7daa62` (the packaging-branch bundle from earlier this session,
now superseded by the Phase 4 integration-lineage correction).
