# FEDORA-01/02 packaging and certification runbook

## 1. Build the distribution

```bash
export JAVA_HOME="$HOME/jdks/jdk-17.0.20.1+1"
./gradlew --offline --no-daemon assembleLinuxDistribution
```

Produces `build/dist/linux-x86_64/{application.jar,launcher,licenses/ChromaScape-LICENSE}`.
The assembly is RemoteInput-free and deletes any stale `native/` directory first.

## 2. Build the packages

```bash
scripts/fedora/package-distribution.sh            # tarball + .sha256 + PACKAGE-MANIFEST.json
scripts/fedora/package-distribution.sh --verify build/packages/chromascape-<v>-linux-x86_64.tar.gz
scripts/fedora/build-rpm.sh --tarball build/packages/chromascape-<v>-linux-x86_64.tar.gz
```

`build-rpm.sh` uses host `rpmbuild` when present and otherwise a rootless podman Fedora container
(`FEDORA_RPM_IMAGE`, default `registry.fedoraproject.org/fedora:44`). It fails closed when neither is
available; it never silently skips the RPM.

### Native payload (gated)

```bash
./gradlew stageRemoteInputLinux
scripts/fedora/package-distribution.sh --native-payload
```

`--native-payload` refuses while `third-party/RemoteInput/PROVENANCE.md` records
`top-level-licence-resolution: unresolved` or the licence file is absent/empty.

## 3. Install without sudo (tarball)

```bash
scripts/fedora/install-tarball.sh \
  --tarball build/packages/chromascape-<v>-linux-x86_64.tar.gz \
  --prefix "$HOME/.local/opt/chromascape" --bin-dir "$HOME/.local/bin"
```

Layout: `releases/<version>/` is immutable, `current` is a symlink, and `~/.local/bin/chromascape`
is a shim that execs `current/launcher`. Upgrade installs a new release; rollback flips `current`:

```bash
scripts/fedora/install-tarball.sh --rollback <version> --prefix "$HOME/.local/opt/chromascape"
scripts/fedora/uninstall-tarball.sh --prefix "$HOME/.local/opt/chromascape" --bin-dir "$HOME/.local/bin"
```

## 4. Run the certification

```bash
scripts/fedora/certify-packaged.sh \
  --package build/packages/chromascape-<v>-linux-x86_64.tar.gz \
  --evidence  build/packages/fedora02-tarball-evidence.json \
  --host-id   "$(hostname -s)" --revision "$(git rev-parse HEAD)"
```

What it does by default: installs into a private temporary prefix (no sudo), verifies the release
has no build tooling, checks Java/native detection, starts a **rootless disposable Postgres**
container, starts the packaged runtime, checks loopback-only binding and `/debugger`, restarts it,
discovers/selects a target if one is running, then uninstalls and proves no residue. `--skip-runtime`
performs the static subset; `--no-postgres` targets an external database; `--live` opts into live
client checks.

The packaged runtime needs the same configuration a deployment supplies. The harness sets only
throwaway certification values:

| Variable | Value used for certification |
|---|---|
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / random loopback port |
| `SPRING_DATASOURCE_URL` | disposable Postgres on a random loopback port |
| `ANALYSIS_MCP_AUTO_START` | `false` (no checkout `scripts/mcp-bridge` required) |
| `STABILIZATION_AUTH_TOKEN` / `STABILIZATION_AUTH_WRITE_TOKEN` | non-blank throwaway tokens |
| `CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64` / `_KEY_ID` / `_ENVIRONMENT` | throwaway Ed25519 public key |
| `CHROMASCAPE_SOURCE_ACCESS_METADATA_TOKEN` / `_CONTENT_TOKEN` | non-blank throwaway tokens |
| `MICRO6_SOURCE_TRANSPORT_BASE_URL` / `_AUTH_TOKEN` / `_ACTOR` | loopback URL and throwaway tokens |
| `CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED` | `false` for the base-runtime start |

## 5. Live client cases (operator)

1. Launch a host-native RuneLite/Micro6 client as the same user, outside Flatpak/bwrap.
2. Export `BUILDER_GOAL_MICRO6_PROJECT_DIR` to the Micro6 checkout so discovery can scope it.
3. Re-run with `--live` (and `FEDORA02_TARGET_PID` when more than one client is eligible) so
   `packagedNativeAttachment`, `frameCapture`, `boundedInput`, `inputRelease`, `cleanClose`,
   `targetRestart` and `multipleClients` execute against the live target.

## 6. Verify the matrix

```bash
python3 -B scripts/qualification/fedora02_evidence.py verify \
  <dir>/fedora02-tarball-evidence.json <dir>/fedora02-rpm-evidence.json
python3 -B scripts/qualification/fedora02_evidence.py verify --require-complete \
  <dir>/fedora02-tarball-evidence.json <dir>/fedora02-rpm-evidence.json
```

The first command validates an honest partial matrix. The second is the release gate: it exits 2
unless both forms agree on the exact revision and package version, carry distinct artifact digests,
record exact `os`/`arch`/`java`/`packageSha256`, and every required case is `PASS`.
