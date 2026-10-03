# SC-810 artifact rebuild and re-sign after repository hygiene cleanup (2026-09-11)

## Why

`micro6-sc810-package` commit `0325bc64fd` removed 45 accidentally-tracked generated files
(JVM crash dumps, tick-replay run logs, 18 stray root-level copies of the `microbotReleaseJar`
Gradle task's `build/libs/` output) and added narrow `.gitignore` rules. This changes the
commit/tree identity the original Phase 8 artifact was built from
(`91d213fdc6b2df864adc96924f3b318a735c93a0`), so the final artifact and signed source bundle
were rebuilt and re-signed from the new commit rather than left pointing at a superseded
identity.

## Build

Native Windows (per `micro6-sc810-package/AGENTS.md`'s compile-authorization rule — explicitly
requested this turn), same commands as the original Phase 8 evidence:

```
gradlew.bat :client:shadowJar :client:micro6ArtifactIdentity `
  -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --no-daemon --console=plain
```

`BUILD SUCCESSFUL in 3m 1s`, 5 tasks executed (`verifyCaptureContract`,
`generateFishingSourceManifest`, `processResources`, `classes`, `shadowJar`,
`micro6ArtifactIdentity`), 23 up-to-date. `shadowJarSign` reported `SKIPPED` (separate JAR-signing
task, unrelated to the SC-810 Ed25519 source-provenance signing below; consistent with the
original Phase 8 run).

## New artifact identity

| Field | Value |
|---|---|
| commit_id / build_id | `0325bc64fd2a918466c11f02bbba8c9c35f66083` |
| tree_id | `fc1e0a85fafc7cbaba3a189fdea756df4dd0c2ef` |
| artifact_path | `C:\Dev\IdeaProjects\micro6-sc810-package\runelite-client\build\libs\client-1.12.39-SNAPSHOT-shaded.jar` |
| artifact_digest | `sha256:12ef96ad055097a469f80a052b36eab7375e6a0f96f293fb9db01bb398d97f3c` |

Cross-checked: `runelite-client/build/identity/micro6-artifact.properties` sidecar and a direct
`sha256sum` of the JAR agree; both also agree with the digest embedded in the new bundle's
`bundle.json` below.

## Real gate hit during the rebuild, and the actual root cause

First bundle-generation attempt failed:
```
SourceManifestGenerationException: authoritative source generation requires a clean Git worktree
```
Root cause was **not** a leftover from the removed files — it was new stray files this session's
own `Start-Process -RedirectStandardOutput/-RedirectStandardError` had just dropped into the
`micro6-sc810-package` working directory root (`chroma_sc810_rebuild_shadowjar.{out,err,pid}`,
`chroma_sc810_rebuild_bundle.{out,err,pid}`) — the same mistake the repository-hygiene commit had
just fixed, reintroduced by this session's own build tooling. Moved all six files out of the repo
(to the session scratchpad) and reran; `git status --porcelain=v1 -uall` confirmed genuinely
clean before the retry.

## Bundle generation (retry, from the clean worktree)

```
$env:MICRO6_SOURCE_BUNDLE_ROOT = 'C:\Dev\SC810\package-20260910\source-bundle-root'
gradlew.bat :client:generateAllTaskSourceBundles `
  -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --no-daemon --console=plain
```

`BUILD SUCCESSFUL in 2m 27s`.
```
bundle_id=srcbundle_1f0d63ec65164d6d5c7daa62
FISHING_TRAINING=sha256:c1c1e808b1240a5dd23a97ec30a48d4658e9f83f12298d8b26ce704789342224
```

Promoted to `C:\Dev\SC810\package-20260910\source-bundle-root\bundles\srcbundle_1f0d63ec65164d6d5c7daa62\`
with a `COMPLETE` marker; `current.json` confirmed pointing to it (verified directly, not
inferred from build success). Promotion only succeeds if `TaskSourceBundleGenerator.validate()`
verifies every manifest signature using the public key alone (a code path separate from the
private-key signing path) — same guarantee as Phase 8.

`bundle.json` contents:
```json
{"artifact_digest":"sha256:12ef96ad055097a469f80a052b36eab7375e6a0f96f293fb9db01bb398d97f3c","build_id":"0325bc64fd2a918466c11f02bbba8c9c35f66083","bundle_id":"srcbundle_1f0d63ec65164d6d5c7daa62","environment":"production","manifests":[{"path":"manifests/FISHING_TRAINING.json","source_manifest_hash":"sha256:c1c1e808b1240a5dd23a97ec30a48d4658e9f83f12298d8b26ce704789342224","source_snapshot_id":"srcsnap_c1c1e808b1240a5dd23a97ec","task_key":"FISHING_TRAINING"}],"schema":"task-source-bundle/v1"}
```

`artifact_digest` and `build_id` match the freshly rebuilt artifact exactly.
`environment: "production"` matches the original Phase 8 characterization — this is the
generator's own baked-in claim, independent of whichever `CHROMASCAPE_SOURCE_ENVIRONMENT`
value a given ChromaScape instance later runs under.

Signing identity unchanged from Phase 8: Ed25519 key `sc810-dev-ed25519-611ab9dd95a518af`,
loaded via `C:\Dev\SC810\secrets\source-signing-v1\load-sc810-signing-env.ps1`; private key was
never printed, logged, or recorded by this session.

## Net effect

This is now the current promoted bundle and artifact for SC-810. Any dispatch-readiness check,
Fishing launch, or debugger-content comparison from this point forward should be evaluated
against build_id `0325bc64fd2a918466c11f02bbba8c9c35f66083` /
digest `sha256:12ef96ad055097a469f80a052b36eab7375e6a0f96f293fb9db01bb398d97f3c`, not the
superseded `91d213fdc6...` identity from the original Phase 8 evidence.
