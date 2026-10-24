# SC-274 / SC-276 execution progress: stages A–F, 2026-10-19 (afternoon)

Continuation of delivery-plan-after-step2-review.md. Felix remains skipped by the user's
instruction for this continuation; nothing here is a Felix write, workflow transition or Shortcut
synchronization. The original configured trust-root gate remains **blocked**. Development-key
success below is not original-trust acceptance, genuine capture or certification.

Evidence directory: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019`
(abbreviated `<V>` below). Runtime kit: `<V>\runtime-kit`.

## Starting state found (not caused by this run)

- Neither the 8180 dev runtime (PID 185188) nor the preserved source-disabled client on 8098
  (PID 242640) was running at 12:58Z. No reboot occurred (processes from 2026-10-04 were still alive).
  The default-home client log was last written at 14:55 local. This run did not see who closed it,
  and **did not restart it**: it was a logged-in session, and login belongs to the user.
- An earlier, unrecorded session had already created the runtime kit (prepare/serve/stop split,
  isolated `user.home`, external state directory), migrated the reservation files out of the dev
  worktree into `micro6-dev-runtime-state` (copy preserved under `pre-migration-state`), and
  started an `sc810SourceTests` run that stopped during `compileTestJava`
  (`<V>\stage-d-sc810SourceTests.log`, UTF-16, incomplete, retained).
- The dev worktree `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` is Git-clean at
  `429e32978b1027ca913660ef38f69b92d35bb3c0`.

## A. Record corrections

- `step2-dev-runtime-verification.json` is preserved byte-for-byte. Its incorrect `generatedAtUtc`
  (02:35Z, earlier than PID 185188's creation at 02:59:17Z), the narrow meaning of its
  "isolation" field and the process state at correction time are recorded in
  `<V>\step2-dev-runtime-verification.correction-20261019.json`.
- Task naming by revision: at 429e32978b the producer is `:client:generateAllTaskSourceBundles`
  and the launcher task is `:client:runBuilderGoalAuthoritative`. `prepareMicro6SourceBundle`
  exists only on newer canonical source. `prepareAuthoritativeSourceBundle` never existed. The
  root-level `<V>\prepare-dev-source-bundle.ps1` (default `prepareMicro6SourceBundle`) is historical.
  The kit version `<V>\runtime-kit\prepare-dev-source-bundle.ps1` defaults to the correct task and
  fails clearly when the task is absent from the checkout.
- Correct snapshot ID: `srcsnap_0585920d2af05903264627ba` (24 hex). The 22-hex value in
  step2-dev-source-bundle.md is a transcription error.

## B. Client home/session and writable state isolation — verified on the live process

Launched with `runtime-kit\serve-dev-authoritative.ps1` at 13:13:21Z: java PID 94808 (cmd PID
78060), recorded in `micro6-dev-runtime-state\runtime.pid`.

| Check | Result |
|---|---|
| 8180 listener | `127.0.0.1` only, PID 94808 |
| `user.home` | `<V>\micro6-dev-runtime-home`, so RuneLite's `RUNELITE_DIR` and default session file (RuneLite.java:123/128) resolve there |
| Working directory / reservations / ledger / behavior outbox | `<V>\micro6-dev-runtime-state` (via `MICRO6_GAMEPLAY_V2_RESERVATIONS`, `MICRO6_GAMEPLAY_AUTHORITY_LEDGER_V2`, `-Dmicro6.behavior.outboxDir`; defaults resolve under `user.dir` = state dir) |
| Default `%USERPROFILE%\.runelite\logs\client.log` | last write 14:55:10 local, before launch, so not written by the experiment |
| Isolated home client.log | written from 15:13 local onward |
| Dev worktree after launch | `git status --porcelain` empty |
| 8090 candidate | PID 195400 unchanged |

`DotEnvLoader` searches `user.dir`, the JAR directory and `user.home` for `.env`. None exists, and
the serve script now refuses to launch if one does.

## C. Signing separated from serving

Finding fixed in this run: the kit's serve script removed only the signing key, and the child
still inherited the whole parent environment, including unrelated tokens (`SHORTCUT_API_TOKEN`,
`CODEX_LB_API_KEY`, `ZAI_API_KEY`, `OPENCODE_CONSOLE_TOKEN`). The serve script now launches through
`ProcessStartInfo` with a **cleared environment** populated only from an explicit allowlist:
minimal OS/Java variables (PATH limited to the JDK bin and System32, TEMP/TMP set to the state dir)
plus `MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64`, `..._KEY_ID`, metadata/content tokens, the
request limit and the path/mode settings. `-DumpChildEnvOnly` writes the exact block
(`micro6-dev-runtime-state\child-env-names.txt`, 28 names, `forbiddenPresent=` empty). The
private signing key is used only by `prepare-dev-source-bundle.ps1`. The manifest generator reads
it only from injected values, system properties or `System.getenv`
(TaskSourceManifestGenerator.java:310–312), none of which the serving child has.

Retained serve preflights (fail closed): JAR digest = sidecar, bundle `COMPLETE`, bundle
artifact/build/environment = JAR/sidecar, manifest self-hash and snapshot derivation, clean
repository flag, `AVAILABLE` status, port free. Readiness: authenticated manifest read returning
the expected snapshot (bounded timeout). Stop: `stop-dev-authoritative.ps1` stops only a java
listener whose command line names the dev worktree's shaded JAR and authoritative mode.

Fail-closed launch cases (`<V>\stage-c-neg\results.txt`; private bundle copies, since deleted;
8180 owned by PID 94808 throughout): wrong bundle artifact digest, missing `COMPLETE`, missing
`current.json`, wrong environment, unknown task and occupied port with a genuine bundle were all
refused before launch (6/6).

Not yet done: a repeated prepare→serve cycle (regeneration) from the now-clean worktree. That
requires stopping the live runtime and a ~12 min rebuild.

## D. Selected-revision focused tests

Command: `runtime-kit\run-stage-d-focused-tests.cmd` (`:client:sc810SourceTests` and `:client:test`
filtered to `Micro6BuilderExecutorSourceModeTest`, `BuilderGoalSettingsContractTest`,
`Micro6BuilderExecutorServerTest`). Log: `<V>\stage-d-focused-tests.log`.

Result: **BUILD SUCCESSFUL in 29m 1s** (run 13:03–13:32Z, against the clean 429e32978b worktree,
with no signing material loaded; the worktree was still clean afterwards).

| Suite | Tests | Skipped | Failures/errors |
|---|---:|---:|---:|
| sc810SourceTests: FishingTaskSourceProvider 8, JavaCompilerTreeSourceIndexer 19, SourceManifestCatalogBundle 23, SourceManifestCatalog 3, SourceManifestEndpointSecurity 4, TaskSourceBundleGenerator 18, TaskSourceGitLifecycle 18, TaskSourceManifestGenerator 22, TaskSourceManifestSchemaEquality 2, TaskSourcePathSecurity 9, TaskSourceProcessorPath 6 | 132 | 1 | 0 |
| test: Micro6BuilderExecutorServerTest 102, Micro6BuilderExecutorSourceModeTest 2, BuilderGoalSettingsContractTest 2 | 106 | 0 | 0 |

The skip is `TaskSourcePathSecurityTest.inaccessiblePosixAncestorFailsClosedWhenTheEnvironmentCanEnforceIt`,
a POSIX-permission case that does not apply on Windows. These results are for 429e32978b only and
are not inherited by canonical Micro6 HEAD, which does not compile (missing `drofirecape.core`).
Compatibility with the current ChromaScape consumer is shown by stage E, after the transport fix.

Note: this shell sets `NoDefaultCurrentDirectoryInExePath=1`, so `cmd` does not resolve a bare
`gradlew.bat` from the current directory. The scripts call it by absolute path.

## E. ChromaScape source integration under scoped development trust

### Defect found and fixed: ChromaScape cannot read from any real Micro6 source runtime

`Micro6SourceManifestClient` sent the single `micro6.source.transport.auth-token` for every read.
Micro6 (429e32978b and canonical HEAD, `authorizeSourceRequest`) checks `manifest-read` against the
metadata token and every other purpose against the content token, and returns 401 on mismatch.
With distinct tokens, `resolve()` therefore always fails on either the manifest or the entry-file
read. ChromaScape's tests did not catch this because their stub servers never checked the bearer
token. The `chromascape.source.access.*` tokens guard ChromaScape's own inbound endpoints
(`SourceAccessPolicy`), not outbound reads.

Fix (compiler candidate `C:\Dev\IdeaProjects\.worktrees\sc274-completion`, uncommitted):
- `Micro6SourceManifestClient`: new `micro6.source.transport.metadata-token` / `content-token`
  sent by purpose. Each falls back to `auth-token`, so existing configurations behave as before.
- `application.yml`: `MICRO6_SOURCE_TRANSPORT_METADATA_TOKEN`, `MICRO6_SOURCE_TRANSPORT_CONTENT_TOKEN`.
- `Micro6SourceManifestClientTest`: purpose-scoped stub mirroring Micro6. Purpose tokens resolve,
  and a shared token valid only for manifests is rejected with `SOURCE_ACCESS_DENIED`.

The live 8090 candidate (PID 195400) runs from the candidate's `build\classes`, so it was **not
recompiled**. Builds and tests ran in a mirror worktree,
`C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp` (detached at c791c6d4, with the
candidate's exact uncommitted state applied: identical tracked-diff hash, 89 untracked files).

Focused tests in the mirror (`runtime-kit\run-stage-e-chromascape-tests.cmd`,
`<V>\stage-e-chromascape-tests.log`): BUILD SUCCESSFUL.
Micro6SourceManifestClientTest 10/10, TaskSourceManifestVerifierTest 11/11,
SourceConfigurationValidatorTest 4/4. 0 failures, 0 skipped.

### Live harness against the isolated runtime

`runtime-kit\StageESourceTrustHarness.java` via `run-stage-e-harness.ps1` drives ChromaScape's real
`Micro6SourceManifestClient`, `TaskSourceManifestVerifier` and `SourceManifestSignatureVerifier`
(mirror build output plus the 8090 dependency classpath) against `http://127.0.0.1:8180`. Development
trust exists only in the harness process environment. Canonical `ChromaScape\.env.local` and
service 8090 are unchanged. No Spring context, database, queue or shared state is touched, so no
owned database was needed for this layer. Results: `<V>\stage-e-harness-results.log`, **15/15 PASS**.

| Case | Outcome |
|---|---|
| P1 dev trust, purpose tokens, fully pinned identity | accepted, signatureVerified, snapshot srcsnap_0585920d2af05903264627ba, artifact sha256:96c912c9…, commit/build 429e32978b, entry FishingExecutionTask.java lines 21–482, 34 pinned files |
| N1 wrong public key, same key ID / N2 wrong key ID | SOURCE_SIGNATURE_INVALID |
| N3 wrong pinned artifact / N4 wrong pinned snapshot | SOURCE_BUILD_MISMATCH |
| N5 wrong environment / N6 wrong tokens / N7 legacy single token | SOURCE_ACCESS_DENIED |
| N8 entry file altered by local proxy | SOURCE_CONTENT_HASH_MISMATCH |
| N9 signed manifest altered by local proxy | SOURCE_MANIFEST_INVALID |
| C1–C3 same client: accept → tampered reject → accept | no cached success masks the negative |
| O1 original configured trust root (`sc810-dev-ed25519-611ab9dd95a518af`) | SOURCE_SIGNATURE_INVALID: development bundle remains rejected |
| O2 original trust root + original environment `local-development` | SOURCE_ACCESS_DENIED |

Tampering was done only on response copies in a harness-local proxy. The served bundle was not
modified.

Not yet covered: a full isolated ChromaScape *service* (Spring context with its own database,
consumers disabled) exercising persistence paths such as source-run orchestration and the debugger
source endpoints. The live 8090 candidate still lacks the transport fix until it is rebuilt and
restarted.

## F. Registry grant authority: diagnosis only, no change made

- Registry = Docker container `leeuwbot-mule` (`0.0.0.0:8084`), compose project `leeuwbotapi_lan`,
  image `leeuwbotapi-canvas-account-edit-mule:20261018`, label `revision=5809869-account-edit`.
  Deployment: `C:\Users\Beheerder\.codex\deployments\leeuwbotapi-pr31-20261016\canvas-account-edit-20261018\compose.json`.
- Source at `5809869` (`C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api`):
  `leeuwbotapi/mule-service/handlers/micro6_endpoint_grants.go` `trustedGrant` returns
  `ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED` when `MULE_MICRO6_ENDPOINT_GRANTS_FILE` is empty or
  `MULE_MICRO6_ENDPOINT_GRANT_KEY` is not 32–256 characters. The container environment has
  neither (and no `MULE_MICRO6_ENDPOINT_FENCES_DIR`).
- Grant model: an operator-run issuer `mule-service/cmd/endpoint-grants` HMAC-signs grants with
  the deployment secret. Each grant binds endpoint_ref, allocation, fencing generation, machine,
  session, executor, account hash, artifact sha256, origin and environment, and lives at most 120 s.
  Non-DEV environments require https and non-loopback origins. A durable fence directory rejects
  replays. Operations doc: `mule-service/docs/sc802/grant-operations.json`, `topology.env.example`.
- The only registry record (machine 809b43ec…, `client_type=leeuwbot`) is a stale client heartbeat
  with no executor identity or catalog. It is not the 8180 runtime.
- Enabling grants needs a redeploy of the shared registry with a new secret, a grants file and a
  fence directory, plus a continuously running issuer. Container-to-host routing to 8180 also
  needs a design, because the runtime binds `127.0.0.1` only. **Held for user decision**: this
  changes shared infrastructure that the 8090 candidate depends on.

## Rollback

- Runtime: `runtime-kit\stop-dev-authoritative.ps1` (owned-process check). State and home dirs are
  experiment-owned and retained.
- ChromaScape fix: revert the three candidate files listed above. Remove the mirror with
  `git worktree remove C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp` after its
  evidence is no longer needed.
- No database, registry, canonical trust or 8090 configuration was changed.
