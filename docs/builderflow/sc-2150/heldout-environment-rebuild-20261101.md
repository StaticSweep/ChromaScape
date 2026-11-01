# SC-2150 — rebuilding the held-out review environment, 2026-11-01

The owner's blind-labelling environment was found down and empty during the 8090 deployment:
port 18191 was not listening and its database, `sc2150_library` (recorded in
`build/sc2150-library/server/launch.json`), did not exist in the cluster. Only two rehearsal
databases were dropped by the deploy work (`chromascape_rehearsal`,
`chromascape_fresh_rehearsal`); the postgres container log had rotated to 49 lines, so the cause of
the loss cannot be established from this host. **Review sessions stored there are unrecoverable.**
No labels had been sealed at that point, so the loss is queue state, not truth.

Everything needed to rebuild survived on disk: the section manifests and downloads
(`build/sc2150-yt/{manifest.json,raw,manifest-heldout2.json,raw-heldout2}`) and the content-addressed
media store (`build/sc2150-library/media/bytes`, 824 MB).

## What was rebuilt

| Item | Result |
|---|---|
| `sc2150_library` | recreated; `main` migrated it to **V86** (81 migrations) |
| 18191 instance | started from a `main` build with the `local-development` profile, `CHROMASCAPE_MOUSE_VIDEO_ENABLED=true`, the unchanged corpus policy at `build/sc2150-library/policy.json`, and the same media directory |
| Sections imported | **33/33 through the real upload route** — 9 new held-out sections and 24 earlier ones, `0 failed` |
| Blind enforcement | `GET …/observations` → **403** "Independent labels for this source group must be sealed before prediction access"; `GET …/review-policy` → 200 with `enrolled: true`, `split: heldout` |
| Owner guide | links regenerated from the new import receipts (all 33 rows resolve; spot checks 200) |

The first rebuild attempts failed for two configuration reasons worth recording:

1. The 30 s default lease expires under host load (other builds were running), so 2–5 sections per
   batch ended `NEEDS_RECONCILIATION`/`WORKER_TIMEOUT`. With
   `CHROMASCAPE_MOUSE_VIDEO_LEASE_EXPIRY_SECONDS=600` every section imported first time.
2. Mixing snapshot classpath layers (`runtime/0:1:2`) produces
   `NoSuchMethodError: MouseVideoProperties.getCorpusManifest()`; a review instance must run one
   self-consistent build.

## Two regressions on `main` that made the environment unusable

Both were introduced when the SC-2150 feature moved to `main`, and both are now fixed:

| Symptom | Cause | Fix |
|---|---|---|
| `GET /mouse/video` → 404 on a clean build | the page mapping only existed as an uncommitted edit while its template was committed | #94 |
| every `/api/debug/mouse-videos/*` call → 401 | `LocalReplayPrincipalConfig` had dropped the mouse-video pattern, so the local-development host never supplied a principal | #99 |

With both in place the deployed 8090 instance also serves the workspace
(`CHROMASCAPE_MOUSE_VIDEO_ENABLED=true` there), and the review backend can be driven from a
`main` build.

## Reproducing the instance

```bash
SPRING_PROFILES_ACTIVE=local-development \
SERVER_ADDRESS=127.0.0.1 SERVER_PORT=18191 \
SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5433/sc2150_library?sslmode=disable' \
CHROMASCAPE_MOUSE_VIDEO_ENABLED=true \
CHROMASCAPE_MOUSE_VIDEO_YOUTUBE_ENABLED=false \
CHROMASCAPE_MOUSE_VIDEO_DIRECTORY=<repo>/build/sc2150-library/media \
CHROMASCAPE_MOUSE_VIDEO_RESTRICTED_LAUNCHER=true \
CHROMASCAPE_MOUSE_VIDEO_CORPUS_MANIFEST=<repo>/build/sc2150-library/policy.json \
CHROMASCAPE_MOUSE_VIDEO_LEASE_EXPIRY_SECONDS=600 \
java -Xmx768m -Duser.name=sc2150-fedora-a -cp <main runtime classpath> \
  com.chromascape.web.ChromaScapeApplication --analysis.mcp.auto-start=false
```
