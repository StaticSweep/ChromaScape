# Step 2 — isolated development-signed Micro6 runtime (build + bundle), 2026-10-19

Build/sign half of step 2. The runtime launch half is not yet done. Felix skipped.

## Revision selection (important finding)

The canonical `micro6` HEAD (`a6cf32fce730a4e8ea0808f74a4697967f4233f6`,
`codex/sc-1618-micro6`) **does not compile**: `…/microbot/drofirecape/FcActions.java`
references a `net.runelite.client.plugins.microbot.drofirecape.core` package that does not
exist in git history or any checkout. That file was added by `98e4a9ae51`, an ancestor of
HEAD; the core package was never committed. The pre-existing `:client:compileJava` failure
is 42 errors, all rooted in the missing package. No source was modified to work around it.

Snapshot instead taken at **`429e32978b1027ca913660ef38f69b92d35bb3c0`**
(`codex/sc1976-finish-20261012`) — the revision that produced the existing 8098 artifact
and which predates the broken commit — in a **clean, isolated** worktree:
`C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` (detached HEAD, clean tree).
The dirty canonical micro6 checkout and the 8098 client were not touched.

Task naming: at `429e32978b` the producer is **`:client:generateAllTaskSourceBundles`**
(`build.gradle.kts:561`); `:client:prepareMicro6SourceBundle` (HEAD:1181) does not exist at
this revision. `:client:runBuilderGoalAuthoritative` exists (`:917`) and depends on
`:client:generateAllTaskSourceBundles`.

## Build inputs

- Contracts: `-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java`.
- Injected client: `C:\Dev\IdeaProjects\micro6\.micro6\runtime\injected-client-241.jar`,
  SHA-256 `981eba4888f4cf0ec8fd74f09b9ed85728d7b2bc8c1f57194883aed64dec768a` (matches the
  build's required digest).
- Signing material: `micro6\.env.local` loaded into the process environment only
  (`MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64`, `MICRO6_SOURCE_SIGNING_KEY_ID` =
  `micro6-sc810-local-ed25519`, plus tokens). Never written to source, evidence or logs.
- Isolated bundle root: `verification\sc274-sc276-continuation-20261019\micro6-dev-bundle-root`.
- Command: `gradlew.bat --no-daemon -Dorg.gradle.jvmargs=-Xmx4g <props> :client:generateAllTaskSourceBundles`
  (launcher `verification\sc274-sc276-continuation-20261019\prepare-dev-source-bundle.ps1`).

## Result — BUILD SUCCESSFUL (12m16s)

- Artifact: `…sc274-micro6-dev-runtime\runelite-client\build\libs\client-1.13.3-SNAPSHOT-shaded.jar`,
  85,417,424 bytes,
  `artifact_digest=sha256:96c912c9cdecfa49ccd7125c52b68394190dbd11d5ebe74042d065f3c06a71e0`
  (independently recomputed, matches the sidecar), `build_id=429e32978b…`.
  Note `:client:shadowJarSign` was SKIPPED; the JAR itself is not signed.
- Bundle root `current.json`: schema `task-source-current/v1` →
  `bundles/srcbundle_fdd4e78fcc5739645ef0727a`; `COMPLETE` marker present.
- `bundle.json`: schema `task-source-bundle/v1`, `environment=native-windows-local`,
  `artifact_digest` equals the artifact above, `build_id=429e32978b…`,
  manifest `FISHING_TRAINING.json` (`sha256:0585920d…`, snapshot `srcsnap_0585920d2af059032646ba`).
- Manifest: schema `task-source-manifest/v1`, `status=AVAILABLE`,
  `repository={clean:true,commit_id:429e32978b…,tree_id:0a5e2d8a…,repository_id:micro6}`,
  `entry_point` FishingExecutionTask, 34 files, 37 content objects,
  `signature={algorithm:Ed25519,key_id:micro6-sc810-local-ed25519,signed_bytes_hash:sha256:3e1130ec…}`.

## Trust-root status (unchanged)

This bundle is signed by Micro6's development key `micro6-sc810-local-ed25519`
(public `…xjufKMl1…`). ChromaScape's **configured** trust root is
`sc810-dev-ed25519-611ab9dd95a518af` (public `…oLTKQQMU6E3r1pw…`), a different key.
The dev-key bundle is therefore **not** accepted by the canonical trust configuration. The
original trust-root gate remains blocked; this is the explicitly authorized development
experiment and is labeled as such.

## Remaining in step 2 / step 3

- Determine isolated DB/artifact/config routing for the second ChromaScape experiment
  before launching (do not let a second service write shared state).
- Launch `:client:runBuilderGoalAuthoritative` on the owned port/source-mode with the
  isolated bundle root; keep game login with the user.
- Give only the isolated experiment Micro6's development public key and source route;
  prove authenticated reads, wrong-token denial and signature/artifact validation.

## Artifacts

- `verification\sc274-sc276-continuation-20261019\prepare-dev-source-bundle.ps1`
- `…\micro6-dev-bundle-build.log` (BUILD SUCCESSFUL)
- `…\step2-dev-source-bundle.json`
- bundle root: `…\micro6-dev-bundle-root\`
