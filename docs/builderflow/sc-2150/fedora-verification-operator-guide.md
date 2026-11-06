# SC-2150 Fedora verification and rollback

These instructions target `C:\Dev\IdeaProjects\ChromaScape` on the native Fedora checkout. Run commands from its repository root. The verification scripts refuse port 8090 and use disposable resources. They do not qualify cursor accuracy, YouTube acquisition, Windows, or authorize a release.

## Prerequisites

Use JDK 17, Docker reachable **from the Gradle JVM**, PostgreSQL 16, Node with the repository dependencies installed, Python with Pillow, ffmpeg/ffprobe, Playwright Chromium, and the repository's SC-967 contract fixture. Restricted jobs additionally require a working systemd user manager with delegated cgroup v2 controllers, bwrap, `/usr/sbin/mke2fs`, `/usr/bin/fuse2fs`, `/usr/bin/fusermount3`, `/usr/bin/setpriv`, and `/dev/fuse` access. Readiness fails closed if the required isolation or bounded filesystem cannot be demonstrated. Installing those prerequisites is a host operation; the application does not install them.

Use process-local `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` if this Docker host rejects docker-java's older default API. Do not persist the workaround in the repository or user home. Do not expose or copy `.env.local` credentials into evidence.

## Tests and immutable runtime

1. Record HEAD, dirty files and SHA-256 hashes before editing. Preserve unrelated changes. Set up an isolated Gradle build directory/cache and record the exact init script used. Run the entire `com.chromascape.web.debug.tickdebugger.video.*` package with real PostgreSQL Testcontainers; **zero skips** is required for its database gates. Also run `npm test`, the Python worker/probe tests, unscoped `spotlessCheck`, and both Checkstyle tasks. A successful filtered video selection does not imply the full repository suite passed.
2. Write `sourceSets.main.runtimeClasspath.asPath` to a local classpath file using a Gradle task that depends on `classes`. Finish compilation before starting a server. `start_verification.py` copies classpath directories into a per-instance runtime snapshot and refuses to overwrite a live recorded process.
3. Create a disposable database with a `sc2150_` name on the local verification PostgreSQL instance. The helper currently expects `chromascape-postgres`, role `chromascape`, loopback port 5433, and the host's existing local database authentication. Adapt the helper deliberately if your host differs; never substitute a production database.

```bash
python3 scripts/sc2150/start_verification.py \
  --classpath /tmp/sc2150-runtime-classpath.txt \
  --java "$JAVA_HOME/bin/java" --database sc2150_example_verify \
  --output /tmp/sc2150-example/server-a
python3 scripts/sc2150/qualify_http.py \
  --base-url http://127.0.0.1:18190 --evidence /tmp/sc2150-example/evidence
SC2150_BASE_URL=http://127.0.0.1:18190 \
SC2150_EVIDENCE=/tmp/sc2150-example/evidence \
  node scripts/sc2150/qualify_browser.cjs
```

The helper uses a 128 MiB aggregate job-workspace budget for verification: half is the fixed-size ext2/FUSE job filesystem and half is the private `/tmp` ceiling. It keeps YouTube disabled and external screen streaming/bridge integration disabled. The normal default budget is documented in `.env.example`. A finite HTTP connection/read timeout is required: the multipart parser's ten-minute streaming deadline cannot interrupt a socket read inside the servlet container. Reservations expire after one hour according to PostgreSQL time.

## Ownership, cancellation and capacity

Start a second instance on 18191 with `--owner sc2150-fedora-b --disable-workers`, and an anonymous instance on 18192 with `--owner sc2150-anon --profile verification --disable-workers`. All instances must use the same disposable database and media root (sibling output directories). The browser run creates review/export data used by the following checks.

```bash
python3 scripts/sc2150/qualify_access_lifecycle.py prepare \
  --database sc2150_example_verify --evidence /tmp/sc2150-example/evidence
# Run the browser script again to exercise the two explicitly seeded lost-worker cancellations.
python3 scripts/sc2150/qualify_access_lifecycle.py capacity \
  --database sc2150_example_verify --evidence /tmp/sc2150-example/evidence
```

`prepare` seeds two unexpired claims and one expired claim, recording their exact IDs. These are synthetic lost-worker fixtures, not evidence of an actual crash. `capacity` removes those synthetic rows in its cleanup and checks that a full owner receives 429 without transmitting body bytes, an identical retry succeeds, and a changed retry receives 409. Every concurrent upload attempt receives its own disk reservation; reusing an accepted request releases that attempt without charging the source again.

## Actual crash/restart

Keep the second and anonymous instances disabled so they can reconcile expired claims without stealing queued work. The following script deliberately stops/kills **only** the verified recorded `sc2150-fedora-a` JVM, then restarts its immutable runtime. It requires a prior browser export and local `/proc` access.

```bash
python3 scripts/sc2150/qualify_restart.py \
  --database sc2150_example_verify --evidence /tmp/sc2150-example/evidence \
  --server-output /tmp/sc2150-example/server-a \
  --classpath /tmp/sc2150-runtime-classpath.txt --java "$JAVA_HOME/bin/java"
```

A queued preview must become READY after restart. A killed active decoder's lease must become NEEDS_RECONCILIATION with a higher generation, its process scope must empty, and partial analysis artifacts must not finalize. Unexpired claims must remain owned. A reconciliation state is not automatically successful cleanup or permission to replay an execution. Retain raw diagnostics privately; public messages are bounded and sanitized.

## Sweep, quotas and release checks

Run the video package's real filesystem, cgroup, JVM-death, publication/GC barrier, quota/concurrent owner-budget, bounded-read, timestamp and geometry tests. For multiple instances, all workers and sweepers sharing the root must run the new publication fence protocol. Upgrade them together; an older process bypasses that protocol. Do not assume network filesystem locking behaves like the qualified local filesystem.

The HTTP harness generates CFR, uneven VFR, burst VFR, verified 90-degree rotation, re-encoded crop and H.264 bitstream crop clips. It compares every indexed PTS and exact preview pixels against independent ffmpeg decoding, checks source dimensions/geometry refusals and Range statuses. Synthetic clips establish mechanics, not detector accuracy. The browser harness checks conflicts, draft/history, point/region corrections, exports, keyboard and narrow layouts and retains an axe report; incomplete/manual accessibility checks remain explicit.

After testing, stop only PIDs whose current `/proc/<pid>/cmdline` matches the recorded verification JVM. Check for remaining owned scopes and FUSE mounts, unmount only verification mounts, retain reports, drop only the disposable database, and remove its temporary media root. Confirm the ordinary 8090 service was never restarted or reconfigured.

## Guarded local rollback

The run evidence identifies local `refs/codex/sc-2150/...` partial-tree checkpoints. They preserve this feature's bytes without altering the branch or index; **they are not runnable release commits**. Stop affected workers and keep the feature disabled before reverting a real deployment. Take database/media backups and use a reviewed deployable release for production rollback. This increment does not alter migrations and does not require a destructive schema reversal.

```bash
python3 scripts/sc2150/restore_checkpoint.py \
  --baseline-ref refs/codex/sc-2150/20261021T152511Z-layout-baseline \
  --final-ref refs/codex/sc-2150/20261021T152511Z-verified \
  --destination "$PWD"
# Review the dry-run. Add --apply only for an intentional restore after stopping affected workers.
```

The restore preflights **every changed file** against the recorded final blob, refuses any intervening edit before writing anything, restores baseline files, and removes only files added by the checkpoint delta. It preserves unrelated files. It has been rehearsed on a disposable copy, not applied to the working checkout. Sampling now uses `pts-window-stride/v1`; older queued visual identities may fail IDENTITY_CHANGED and must be deliberately rescheduled rather than silently relabelled.

## Gates still requiring external decisions

Provide approved licensed templates and an annotated held-out split with acceptance thresholds before qualifying cursor/visual accuracy. Provide approved downloader version/SHA, a public clip and network policy before implementing/qualifying constrained YouTube acquisition; the default restricted launcher has no network. Run real Windows process containment/recovery tests on Windows before claiming that platform. Resolve or separately disposition the repository-wide failures and complete review/release approval before closing SC-2150.
