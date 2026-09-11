# SC-810 Phase 8 — packaged-runtime gate (2026-09-11)

## Correction folded in: bundle environment

The Phase 7 bundle (`srcbundle_0a49cff0d280eef221c929b9`) was generated without
`MICRO6_SOURCE_ENVIRONMENT` set, so `runelite-client/build.gradle.kts:532-533`'s
`--environment` arg defaulted to `"production"` — inconsistent with the `local-development`
intent Phase 7's own evidence documented. Regenerated with `MICRO6_SOURCE_ENVIRONMENT=local-development`
explicitly set: new bundle `srcbundle_073725b58580eeb7d80e27cd`, same artifact digest/build_id
(the environment label doesn't touch source content), `environment: "local-development"`
confirmed in `bundle.json`. This is now the current bundle; `srcbundle_0a49cff0d280eef221c929b9`
is superseded.

## Harness

No prior session's disposable verifier (`Sc810RunningArtifactAcceptance`, referenced in
`closure-plan-phase3-5-6-7-8-integration-and-final-artifact.md`) survived on disk — it was
genuinely disposable, not committed anywhere. Rewrote it from scratch in the session scratchpad
(never committed to either repo), using the exact same technique inferred from
`Micro6ArtifactAttestationTest.java`'s `new Micro6BuilderExecutorServer(mock(AccountBuilderPlugin.class))`
pattern:

- Compiled and run with the **real packaged shaded JAR directly on the classpath** (not a
  recompiled equivalent), so `Micro6BuilderExecutorServer`'s own `runningCodeSourceJar()`
  check (via `CodeSource.getLocation()`) proves the process is genuinely running the packaged
  bytes — required because `packagedArtifactIdentity(true)` enforces this whenever
  `sourceBundleRoot` is configured.
- Classpath: the shaded JAR + `mockito-core-5.23.0`, `byte-buddy-1.17.7`,
  `byte-buddy-agent-1.17.7`, `objenesis-3.3` (located directly in the Gradle module cache;
  Mockito 5.x's inline mock maker handles `AccountBuilderPlugin` without extra JVM flags).
- `AccountBuilderPlugin` is `Mockito.mock(...)` — every method returns Mockito defaults
  (null/false/0), which `Micro6BuilderExecutorServer` tolerates cleanly for identity/catalog
  purposes (confirmed empirically; this is the same mocking this codebase's own test suite uses
  for the same class).

## Real gap found and resolved: `MICRO6_LOCAL_EXECUTOR_ENABLED`

First `start()` call returned `false` with `getLastStartError(): null` (no error recorded —
`isEnabled()`'s early-return path at
`Micro6BuilderExecutorServer.java:538-545` doesn't set `lastStartError`). Traced to
`isEnabled()` (`:7942-7948`): since the mocked plugin's `getConfig()` returns `null`, it falls
through to `DotEnvLoader.getEnv("MICRO6_LOCAL_EXECUTOR_ENABLED")`, which needs `"true"`. Set as a
real process environment variable (`DotEnvLoader.getEnv` falls back to `System.getenv` when no
`.env` file value exists — confirmed by reading `DotEnvLoader.java:210-221`). Not a defect —
correct fail-closed-by-default behavior for a config gate the mocked plugin can't satisfy through
its normal path.

## Positive case: PASSED

Environment (bind `127.0.0.1:18098`, isolated from the real Micro6 dev port `8098`):
```
MICRO6_SOURCE_METADATA_TOKEN / MICRO6_SOURCE_CONTENT_TOKEN: local-only tokens (see phase10b note
  on why these values don't carry provenance weight)
MICRO6_SOURCE_ENVIRONMENT=local-development
MICRO6_SOURCE_BUNDLE_ROOT=C:\Dev\SC810\package-20260910\source-bundle-root
MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64 / _KEY_ID: real Ed25519 key, via load-sc810-signing-env.ps1
MICRO6_ARTIFACT_IDENTITY_PATH=...\runelite-client\build\identity\micro6-artifact.properties
MICRO6_LOCAL_EXECUTOR_ENABLED=true
```

Result:
```
sourceInitializationError: null
start() returned: true
isRunning(): true
getLastStartError(): null
GET /health -> 200
```
`/health` body (key fields):
```json
{
  "build_id": "6e271ce40cbccbde224fffe73a86a3f4df61a40c",
  "artifact_digest": "sha256:8db7a4747f6f6fe3ef9a9d959af44570d7f975ef0c80339507687208f2ef09ee",
  "source_snapshot_id": "srcsnap_031001ca49f7e57319b944cc",
  "catalog_state": "DEGRADED"
}
```
All three identity fields match the Phase 6 artifact and Phase 8's regenerated bundle exactly.
`GET /api/v1/catalog` returned 200; `FISHING_TRAINING` appears 11 times in the catalog body
(task entry plus alias rows) — Fishing Training is discoverable.

`catalog_state: DEGRADED` — **the same pre-existing, out-of-scope schema/validation catalog
defect documented throughout this engagement** (`schema_failed_count`/`validation_error_count`,
first flagged in the original Phase 8 evidence). Per this phase's own instruction: recorded as
an externally-owned acceptance blocker, not investigated further, not treated as a running-artifact-gate
failure. **SC-810 is not marked Done while Fishing cannot actually be dispatched as a result.**

## Negative cases: 2 of the full matrix tested, both PASSED (fail-closed)

Given session time constraints, ran two representative, safety-critical cases rather than the
full exhaustive list (missing/wrong sidecar, artifact, build, commit/tree, environment, key —
this phase's full list). The remaining cases are **not tested this session** and should be
treated as an open item, not assumed passing.

1. **Mismatched trust environment** (`MICRO6_SOURCE_ENVIRONMENT=production` against the
   `local-development` bundle): `sourceInitializationError: "promoted bundle environment
   differs from MICRO6_SOURCE_ENVIRONMENT"`, `start() returned: false`. Fails closed correctly.
2. **Untrusted/malformed public key** (garbage base64 substituted for the real Ed25519 key):
   `sourceInitializationError: "the promoted task-source bundle could not be loaded"`,
   `start() returned: false`. Fails closed correctly.

## What Phase 8 does not prove (same caveat as every prior packaged-runtime check this engagement)

Mocked `AccountBuilderPlugin`, no live RuneLite client, no LeeuwBot, no ChromaScape connection
from this harness. The full managed-capture runtime path remains unverified pending a genuine
Fishing recording (Phase 11, gated on account-migration authorization).
