# SC-810 Phase 8 — packaged artifact and signed Fishing bundle (2026-09-10)

> **Naming note**: "Phase 8" here refers to the original delivery-verification plan's phase
> numbering (Phase 8 packaging → Phase 9 live verification → Phase 10 closure), established
> before the later "SC-810 remaining implementation and closure plan" existed. That later plan
> has its own separate Phase 1-20 numbering; its Phase 8 ("prove packaged-runtime readiness") is
> evidenced instead in `closure-plan-phase3-5-6-7-8-integration-and-final-artifact.md`.

## Selected revisions (per explicit instruction)
- ChromaScape: `C:\Dev\IdeaProjects\ChromaScape-consolidation` @ `c1758c71e72ada19604d1627031ae267e4d5951a`
  (branch `wip/chromascape-consolidation-20260907`) — clean, confirmed via
  `git status --porcelain=v1 -uall` (empty).
- Micro6: new dedicated detached worktree `C:\Dev\IdeaProjects\micro6-sc810-package`, created via
  `git worktree add --detach "C:/Dev/IdeaProjects/micro6-sc810-package" 90f793e83bf0a37470431e561ac5070587cc6bb8`
  from the canonical `C:\Dev\IdeaProjects\micro6` repository. Never packaged from the active
  canonical checkout, which carries another operator's staged, unrelated work.

## Step 1 — clean-revision confirmation
Both worktrees confirmed clean via `git status --porcelain=v1 -uall` before any build.

## Two packaging-only commits, in the dedicated worktree only
The bundle generator's own attestation (`TaskSourceIdentity`) correctly refused to run from a
dirty tree, and separately rejected a mismatched `--build-id` assertion. Both were fixed by
adding/adjusting build infrastructure, committed only in this disposable packaging worktree
(detached HEAD; not the canonical repo, not the consolidation branch):
1. `e4132ff3ca02aa848f0e76908439e3710bb4a139` — adds the `generateAllTaskSourceBundles` JavaExec
   task (mirrors the reviewed-but-unmerged `buildscript_review.patch` definition, minus its
   unrelated SC-968 hashing change; does not remove or modify the existing
   `generateFishingSourceManifest` task).
2. `91d213fdc6b2df864adc96924f3b318a735c93a0` — drops the `--build-id` CLI assertion.
   `TaskSourceIdentity.deriveBuildId` only uses `gradle.properties`' `project.build.version` as a
   presence/well-formedness gate; the actual derived build ID it returns is the git commit SHA
   itself. `assertEqual` treats a `null` claim as "no assertion" (only non-null claims are
   checked), so omitting `--build-id` is the tool's own sanctioned way to avoid a spurious
   mismatch rather than reverse-engineering an exact string match.

Final packaging-worktree HEAD used for the promoted bundle: `91d213fdc6b2df864adc96924f3b318a735c93a0`,
tree `d0d4cf45c85a80960c7e500c1049adccf8b88a6b`.

## Step 2-3 — shadow JAR build and digest
```
.\gradlew.bat :client:shadowJar :client:micro6ArtifactIdentity `
  "-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java" --no-daemon --console=plain
```
Final artifact (built from the clean `91d213fdc6` commit):
- Path: `C:\Dev\IdeaProjects\micro6-sc810-package\runelite-client\build\libs\client-1.12.39-SNAPSHOT-shaded.jar`
- SHA-256: `bfd29589455c6680692e13373aa4b067d59a1b9504c3bf38da3a8a8b97911951`
  (independently cross-checked: the Gradle-generated `micro6-artifact.properties` sidecar,
  a direct `sha256sum` of the JAR file, and the digest embedded in the promoted bundle's
  `bundle.json`/manifest all agree.)
- Build ID (as embedded in the bundle — see below): the git commit SHA
  `91d213fdc6b2df864adc96924f3b318a735c93a0`, not a version string.

## Signing identity provisioning
Ed25519 keypair provisioned via `C:\Dev\SC810\provision-sc810-signing-key.ps1` (OpenSSL PKCS#8/
X.509 DER), stored under `C:\Dev\SC810\secrets\source-signing-v1\` with an ACL restricted to the
current user only. **Private key was never printed, logged, or recorded by this session.**
- Key ID: `sc810-dev-ed25519-611ab9dd95a518af`
- Public-key SHA-256 fingerprint: `611ab9dd95a518afc2c2a1088282e1e89939ddeac622043a7d528330032ec888`
  (the key ID's suffix is exactly this fingerprint's first 16 hex characters — self-consistent.)

Fixed one real bug in the provisioning script before it could run: under Windows PowerShell 5.1's
`Set-StrictMode`, `$existing = @(...) | Where-Object {...}` returns `$null` (not an empty array)
when zero files match, and `$null.Count` throws — the normal first-run case. Wrapped the pipeline
result in an outer `@(...)` so it's always an array. Also had to prepend Git for Windows'
`mingw64\bin` (bundled OpenSSL) to this PowerShell session's `$env:Path`, since `openssl.exe` was
not on native PowerShell's PATH.

Signing/verification env vars were loaded via
`. "C:\Dev\SC810\secrets\source-signing-v1\load-sc810-signing-env.ps1"` inside the same PowerShell
invocation that ran the bundle-generation Gradle command (env vars from dot-sourcing do not
persist across separate tool invocations).

## Step 4 — bundle generation
```
$env:MICRO6_SOURCE_BUNDLE_ROOT = 'C:\Dev\SC810\package-20260910\source-bundle-root'
.\gradlew.bat :client:generateAllTaskSourceBundles `
  "-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java" --no-daemon --console=plain
```
Output:
```
bundle_id=srcbundle_a6242e2d6dd8bdc1ed1748a9
FISHING_TRAINING=sha256:0af14d7e7094a58b899c7ae7907594a8fcc67f8c1ba29bd55e472e763b20e545
```
Promoted to `C:\Dev\SC810\package-20260910\source-bundle-root\bundles\srcbundle_a6242e2d6dd8bdc1ed1748a9\`
with a `COMPLETE` marker; `current.json` points to it.

## Step 5 — verification
All checked directly, not merely inferred from build success:

| Field | Bundle/manifest value | Independently confirmed against |
|---|---|---|
| commit_id | `91d213fdc6b2df864adc96924f3b318a735c93a0` | `git rev-parse HEAD` in the packaging worktree |
| tree_id | `d0d4cf45c85a80960c7e500c1049adccf8b88a6b` | `git rev-parse HEAD^{tree}` |
| clean | `true` | `git status --porcelain=v1 -uall` (empty) at generation time |
| build_id | `91d213fdc6b2df864adc96924f3b318a735c93a0` | equals commit_id (by design — see above) |
| artifact_digest | `sha256:bfd29589...11951` | `micro6-artifact.properties` sidecar + direct `sha256sum` of the JAR |
| environment | `production` | matches ChromaScape's default trust config convention |
| entry_point | `FishingExecutionTask` (`file_1a4614c082f9642c8c0cbb75f1fcf9a2`) | present, well-formed, indexed in `symbols` |
| files / symbols / closure / warnings | 34 / 1716 / 173 / 0 | matches this engagement's original pre-session measurement exactly (files=34, closure_edges=173, symbols=1716, warnings=0) |
| signature.algorithm | `Ed25519` | — |
| signature.key_id | `sc810-dev-ed25519-611ab9dd95a518af` | matches provisioned public-key fingerprint prefix |

Signature cryptographic verification: `TaskSourceBundleGenerator.validate()` verifies every
manifest signature using **only the public key**, in a code path entirely separate from
`TaskSourceManifestGenerator`'s signing (private key) path. Promotion only succeeds if this
passes — confirmed twice: once at generation, and a second time by rerunning the generator
against the already-promoted bundle (`promoteBundle` re-validates the *entire existing directory*
from disk when the target already exists, per its own documented behavior). Both runs produced
the identical `bundle_id` and manifest hash.

Source closure: the `closure` array contains real, substantive dependency edges (IMPORT,
SEMANTIC_REFERENCE, TASK_FACTORY, TASK_CLOSURE kinds) rooted at `FishingExecutionTask` and
`EnhancedFishingLocation`, not a stub.

## Step 6 — launch of the exact packaged artifact
Scope (agreed): a startup smoke test of the exact shaded JAR, not Phase 9's full live
ChromaScape-binding matrix (no game-server connection, no recording, no ChromaScape interaction
attempted).
```
& 'C:\Users\Beheerder\.jdks\corretto-21.0.10\bin\java.exe' -Xmx2g -Xms512m `
  -Dmicro6.artifact.identity.path='C:\Dev\IdeaProjects\micro6-sc810-package\runelite-client\build\identity\micro6-artifact.properties' `
  -jar 'C:\Dev\IdeaProjects\micro6-sc810-package\runelite-client\build\libs\client-1.12.39-SNAPSHOT-shaded.jar'
```
- Process launched (PID 58204), confirmed alive (742MB working set) after 15+ seconds.
- Startup log confirms the exact `-Dmicro6.artifact.identity.path` JVM argument was passed
  correctly (`launch-stdout.log`).
- Core framework, config manager, and most plugins (Mule, Mouse Recorder, etc.) started
  successfully. One unrelated plugin (`BarrowsPlugin`) threw a `NullPointerException` in
  `Rs2Antiban.setActivity` — a null `playStyle` because no player is logged in in this smoke
  test; unrelated to SC-810 or this packaged artifact's identity/integrity, and the exception
  was caught and logged by `PluginManager` without crashing the client.
- Process stopped cleanly via `Stop-Process -Force` after evidence capture; confirmed exited.
- Full logs preserved at `C:\Dev\SC810\package-20260910\launch-stdout.log` and
  `launch-stderr.log`.

## What was NOT done (per explicit constraint)
- Nothing pushed, restarted as a persistent service, or published anywhere.
- No interaction with ChromaScape's live binding/debugger flow (Phase 9, separately authorized).
- No Shortcut/Felix tracker updates (Phase 10, separately authorized).
- The private signing key was never printed, logged, or written into any evidence file by this
  session.

## Artifacts and locations for reference
- Signing key material (private, ACL-restricted): `C:\Dev\SC810\secrets\source-signing-v1\`
- Bundle root: `C:\Dev\SC810\package-20260910\source-bundle-root\`
- Launch logs: `C:\Dev\SC810\package-20260910\launch-stdout.log`, `launch-stderr.log`
- Packaging worktree (micro6): `C:\Dev\IdeaProjects\micro6-sc810-package` @ `91d213fdc6`
- ChromaScape source revision used: `C:\Dev\IdeaProjects\ChromaScape-consolidation` @ `c1758c7`
