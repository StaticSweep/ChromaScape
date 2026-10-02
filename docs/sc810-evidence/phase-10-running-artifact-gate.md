# SC-810 running-artifact attestation gate — investigation, fix, and proof (2026-09-11)

> **Naming note**: this file's name continues the sequential evidence numbering established
> earlier in this engagement (`phase-8-evidence.md` → `phase-9-evidence.md` → this file), from
> before the later "SC-810 remaining implementation and closure plan" (its own separate
> Phase 1-20) existed. It does not correspond to that later plan's Phase 10. That plan's own
> phases are evidenced in `closure-plan-phase2-...`, `closure-plan-phase3-5-6-7-8-...`, and
> `closure-plan-phase10-...` in this same directory.

## What was asked

Following the 2026-09-10 accuracy review, finding 1 was: `Sc810RealBundleVerificationTest`
calls `SourceManifestCatalog.Registry.loadBundle()` directly and never starts or invokes
`Micro6BuilderExecutorServer`, so the actual "exact running JAR" comparison the server performs
(`packagedArtifactIdentity(true)` / `verifyPackagedJar(..., requireRunningArtifact=true)`) was
never exercised. This record documents investigating that gate, a real defect found in it, the
fix, and a genuine proof run against the real packaged artifact.

## Defect found (confirmed empirically, not just by reading source)

`Micro6BuilderExecutorServer.verifyPackagedJar(..., requireRunningArtifact=true)` requires a
`Micro6-Build-Id` manifest attribute on the running JAR, read via
`new JarFile(jar).getManifest().getMainAttributes().getValue("Micro6-Build-Id")`, and compares
it to the identity sidecar's declared `build_id`. `runelite-client/build.gradle.kts`'s
`shadowJar` task never wrote that attribute at all (manifest only had `Main-Class`), and the
`micro6ArtifactIdentity` task's sidecar declared `build_id=${project.version}` (a static
version string) rather than the git commit SHA that `TaskSourceIdentity.deriveBuildId()`
embeds in the promoted bundle's `build.build_id` field.

Net effect: **this gate could never pass for any build this task produced, regardless of which
commit was packaged** — `initializeSourceCatalog`'s non-test `MICRO6_SOURCE_BUNDLE_ROOT` path
would always fail with `running packaged artifact identity is unavailable`. Confirmed directly
by unzipping the already-built `client-1.12.39-SNAPSHOT-shaded.jar`'s `META-INF/MANIFEST.MF`:
only `Manifest-Version`/`Main-Class` were present.

## Fix (packaging worktree only, `sc810-package-20260910` branch)

Two commits on `C:\Dev\IdeaProjects\micro6-sc810-package`:

- `90344456` — moved the existing `fishingSourceBuildId` Gradle provider (git commit SHA via
  `git rev-parse HEAD`, already used for the Fishing source manifest) earlier in the file and
  reused it for both sides of the gate: `shadowJar`'s manifest now sets
  `Micro6-Build-Id = fishingSourceBuildId`, and `micro6ArtifactIdentity`'s sidecar now declares
  `build_id=${fishingSourceBuildId.get()}` instead of `project.version`.
- `a2b58bcd33` — repoints `Sc810RealBundleVerificationTest`'s hardcoded expected manifest
  hash/snapshot/bundle IDs at the bundle regenerated after the fix (see below); no production
  code changed in this commit.

## Rebuild and re-signing at the final commit

Rebuilding after the fix necessarily changes the JAR bytes (the commit SHA is now embedded in
the manifest), so the previously-promoted bundle/JAR from Phase 8
(digest `bfd29589...`, bundle `srcbundle_a6242e2d6dd8bdc1ed1748a9`) is superseded. Rebuilt at
the final commit `90344456866ad6e0ca0d30b517a8bf0259f87104`:

- Shaded JAR SHA-256: `bcba97b460a75606a14c05fc97fbc026da14584040e226ee0e8a8ec9364112a2`
  (cross-verified: `sha256sum`, the `micro6ArtifactIdentity` sidecar, and the JAR's own
  `Micro6-Build-Id` manifest attribute all agree).
- Regenerated, signed, promoted bundle: `srcbundle_752bec0ca477b3a7752b31ec`, manifest hash
  `sha256:644a15f83e51c392ce55b64cffb94acd04886928cbd52a599647c145f164d8d1`, snapshot
  `srcsnap_644a15f83e51c392ce55b64c`, environment `production` — `build.artifact_digest` and
  `build.build_id` in the manifest match the JAR above exactly.
- Note the one-commit lag: `a2b58bcd33` (test-constant update, no production code) was
  committed after this build to keep the worktree clean for `generateAllTaskSourceBundles`,
  which itself requires a clean tree. The artifact/bundle above are bound to `90344456`, one
  commit behind literal current HEAD. This is fine: the artifact and bundle are internally
  self-consistent and were never rebuilt after `a2b58bcd33` (no code applicable to their
  digest changed). If this branch is later integrated elsewhere, rebuild and re-sign from
  that final integrated commit, as already noted in the delivery-evidence pending record.

## Real running-artifact proof (not a JUnit test on the Gradle test classpath)

`packagedArtifactIdentity(true)`'s check (`Class.getProtectionDomain().getCodeSource()`) can
only be satisfied when `Micro6BuilderExecutorServer.class` is actually loaded from the packaged
JAR — impossible from Gradle's test classpath (a `build/classes/...` directory), which is
exactly the gap the review identified. Wrote a small standalone verifier
(`Sc810RunningArtifactAcceptance.java`, compiled and run outside Gradle, not committed to
either repo — a one-off acceptance tool) and ran it with the real shaded JAR placed first on
the JVM classpath (Mockito/ByteBuddy/Objenesis appended after it, for `mock(AccountBuilderPlugin.class)`
only — no game client, no LeeuwBot, no ChromaScape involved):

```
Micro6BuilderExecutorServer loaded from: file:/C:/Dev/IdeaProjects/micro6-sc810-package/runelite-client/build/libs/client-1.12.39-SNAPSHOT-shaded.jar
sourceInitializationError (constructor-time, real MICRO6_SOURCE_BUNDLE_ROOT path): null
start() returned: true
lastStartError: null
boundAddress: 127.0.0.1:18199
GET /health -> 200
{"...","build_id":"90344456866ad6e0ca0d30b517a8bf0259f87104","artifact_digest":"sha256:bcba97b460a75606a14c05fc97fbc026da14584040e226ee0e8a8ec9364112a2","source_snapshot_id":"srcsnap_644a15f83e51c392ce55b64c",...}
RESULT_EXIT_CODE=0
```

`build_id`/`artifact_digest`/`source_snapshot_id` in the live `/health` response all match the
values above exactly, from the real application code path (`initializeSourceCatalog` →
`packagedArtifactIdentity(true)` → `verifyPackagedJar(requireRunningArtifact=true)` →
`SourceManifestCatalog.Registry.loadBundle`), with `Micro6BuilderExecutorServer` genuinely
loaded from the real packaged, signed artifact. This is the mechanism proof that was missing:
finding 1 is closed.

Config used (env vars only, no code changes to the server): `MICRO6_SOURCE_BUNDLE_ROOT`,
`MICRO6_SOURCE_ENVIRONMENT=production`, `MICRO6_SOURCE_METADATA_TOKEN`/`_CONTENT_TOKEN` (local
verification-only values), `MICRO6_ARTIFACT_IDENTITY_PATH` (the sidecar above),
`MICRO6_LOCAL_EXECUTOR_ENABLED=true`, `MICRO6_EXECUTOR_BIND_ADDR=127.0.0.1:18199`,
`MICRO6_CERTIFICATION_MODE=false`, plus the real Ed25519 verification key/key ID from
`load-sc810-signing-env.ps1`.

## Still not proven (unchanged from the prior review — this run does not close these)

- `AccountBuilderPlugin` was mocked; there was no live RuneLite client, no LeeuwBot, no
  ChromaScape, and no game session. The full managed-capture runtime path
  (`Micro6CaptureIdentityFactory` invoked by a real running client against a real bound run)
  remains unverified and still requires the already-documented blocked infrastructure.
- The `/health` response reported `"catalog_state":"DEGRADED"` because the Builder Goal task
  *catalog* discovery (a different mechanism from the SC-810 source-manifest bundle proven
  above) throws `IllegalArgumentException: URI is not hierarchical` when scanning task
  packages from inside a JAR (`UniversalTaskDiscoveryService.getClassesInPackage` uses
  `new File(URI)`, which only works for exploded `file:` URIs, not `jar:file:...!/...`
  entries) — every skilling/quest category reported 0 discovered tasks in this run. This is a
  **new, separate, pre-existing defect**, unrelated to SC-810's source-manifest/bundle
  mechanism (which worked correctly), not fixed here, and out of SC-810's scope. Worth its own
  ticket: the Builder Goal catalog is evidently never actually exercised from a packaged JAR
  today, only from an IDE/exploded-classes launch.

## Evidence artifacts

- `C:\Dev\IdeaProjects\micro6-sc810-package` — commits `90344456`, `a2b58bcd33` on
  `sc810-package-20260910` (branch preserved, not detached).
- `C:\Dev\SC810\package-20260910\source-bundle-root\bundles\srcbundle_752bec0ca477b3a7752b31ec\`
  — the regenerated, signed, promoted bundle (old bundle `srcbundle_a6242e2d...` retained
  alongside it, not deleted).
- Verifier source and full run log captured in this session's tool transcript; the verifier
  itself was a disposable scratchpad tool, not committed to either repository.
