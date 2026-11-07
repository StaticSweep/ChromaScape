# Step 2 — development-signed authoritative Micro6 runtime, launch + verification (2026-10-19)

Second half of step 2. The isolated development runtime is launched in authoritative
source mode and its source endpoints are verified. Felix skipped. No whole-story gate is
claimed; the original trust-root gate remains blocked.

## What was launched

- Snapshot: `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` @
  `429e32978b1027ca913660ef38f69b92d35bb3c0` (clean), bundle root
  `verification\sc274-sc276-continuation-20261019\micro6-dev-bundle-root`.
- Task: `:client:runBuilderGoalAuthoritative` (main `net.runelite.client.RuneLite`,
  `MICRO6_SOURCE_MODE=authoritative`, `-Dmicro6.source.mode=authoritative`).
- Isolation: `MICRO6_EXECUTOR_BIND_ADDR=127.0.0.1:8180` (default is `127.0.0.1:8098`).
- Launcher: `verification\sc274-sc276-continuation-20261019\launch-dev-authoritative.ps1`;
  log `micro6-dev-authoritative.log`.
- Result: `:client:shadowJar` rebuilt, `:client:generateAllTaskSourceBundles` re-promoted
  `srcbundle_fdd4e78fcc5739645ef0727a`, client started, executor bound
  `127.0.0.1:8180` (listener pid 185188). The existing source-disabled client on
  **8098 (pid 242640) is unchanged**.

## Authenticated source verification (127.0.0.1:8180)

Auth contract (`Micro6BuilderExecutorServer.authorizeSourceRequest`): `Authorization:
Bearer <token>` (metadata token for `manifest-read`, content token for `file-read`/
`symbol-read`), plus `X-Source-Actor`, `X-Source-Purpose`, `X-Correlation-ID`, and
`X-Source-Environment` equal to the configured environment.

| Check | Result |
|---|---|
| `GET /api/v1/source-manifests/FISHING_TRAINING` (metadata token, `manifest-read`) | **200** — schema `task-source-manifest/v1`, env `native-windows-local`, `signature.key_id=micro6-sc810-local-ed25519`, `repository.commit_id=429e32978b…`, `clean=true`, 34 files, hash `sha256:0585920d…` |
| `GET /api/v1/source-snapshots/srcsnap_0585920d2af05903264627ba/files/file_1ef26bb845fb8d06ac4afcf328f1d44d` (content token, `file-read`) | **200**, 7779 bytes, `ETag=sha256:b5b692e6…` (matches the manifest `content_hash`) |
| `GET …/source-snapshots/srcsnap_…/symbols?file_id=…` (content token, `symbol-read`) | **200** `source-symbols/v1` |
| wrong token | **401** |
| missing audit context | **422** `SOURCE_AUDIT_CONTEXT_INVALID` |
| wrong `X-Source-Environment` | **403** |
| content token on `manifest-read` | **401** |
| metadata token on `file-read` | **401** |

Artifact identity agreement: the runtime's sidecar artifact digest
`sha256:96c912c9…` equals the promoted bundle's `artifact_digest`, and the served
manifest's `repository.commit_id` equals `build_id`. So artifact identity and signed
source identity agree for this runtime.

Earlier 400 `SOURCE_MANIFEST_INVALID` results were a transcription error on my part (a
22-hex snapshot id); the correct 24-hex id `srcsnap_0585920d2af05903264627ba` returns 200.
Recorded so the mistake is not mistaken for a runtime defect.

## Trust-root status (unchanged)

Everything above uses Micro6's development key `micro6-sc810-local-ed25519`. ChromaScape's
configured trust root is `sc810-dev-ed25519-611ab9dd95a518af` (different key), so this
runtime is **not** accepted by the canonical configuration. This is the authorized,
clearly-labeled development experiment; the original trust-root gate stays blocked.

## Remaining (steps 2 tail / 3)

- Isolated ChromaScape experiment trust config + routing (no shared-state writes), then
  prove the actual integration path consumes this runtime.
- Registry allocation/heartbeat: `ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED` (step 3).
- Game login is the user's; nothing about credentials/gameplay was automated.

## Artifacts

- `launch-dev-authoritative.ps1`, `micro6-dev-authoritative.log`, `micro6-dev-client-args.txt`
- `step2-dev-runtime-verification.json`
- bundle root `micro6-dev-bundle-root\` (unchanged from the build step)
