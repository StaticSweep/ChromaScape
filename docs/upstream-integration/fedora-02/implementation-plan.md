# FEDORA-01 / FEDORA-02 — packaging and clean-host certification

Status: implementation record. Acceptance results belong in `verification-evidence.md`.
Base revision: `644deac116d44071b4b6d02ecd15d1c91861569a`
(`codex/fedora-live-qualification`, the superset of the earlier packaging branch that also carries
the SC-1662–SC-1668 target-discovery/input/capture stack).

## Why this exists

FEDORA-01 proves a tarball and RPM can be **built**. FEDORA-02 proves they **work** on a clean Fedora
host with no development checkout, no Gradle, no compiler and no repository files. The two are one
delivery: a package that never starts is not a package.

## Scope

### FEDORA-01 — packaging

- Turn the assembled Linux distribution (`build/dist/linux-x86_64`: `application.jar`, `launcher`,
  `licenses/ChromaScape-LICENSE`) into a versioned, deterministic `chromascape-<version>-linux-x86_64.tar.gz`
  that ships its own non-root installer, uninstaller and a machine-readable `PACKAGE-MANIFEST.json`.
- Wrap the same tarball in an RPM (`packaging/fedora/chromascape.spec.in`) so the RPM is a thin
  system-installation view of one vetted payload, never a second build.
- Keep the RemoteInput native pair opt-in. `--native-payload` copies the manifest-verified pair and
  refuses to run while the two-factor licence gate in `third-party/RemoteInput/PROVENANCE.md` is
  unresolved. A base package therefore contains no unlicensed binary, and no stale `native/`
  directory can survive assembly.

### FEDORA-02 — clean-host certification

- Non-root tarball install into an isolated prefix; RPM install in a disposable clean environment.
- Start the packaged runtime with no Gradle, Git, compiler or repository files.
- Verify Java and native dependency detection.
- Verify loopback-only binding and `/debugger`.
- Discover and explicitly select RuneLite/Micro6.
- Attach with the packaged native pair, capture frames, perform bounded mouse/keyboard input,
  release all held input and close cleanly.
- Restart ChromaScape and the target; exercise multiple-client selection.
- Upgrade, roll back and uninstall, then confirm no process, package file or stale native payload
  is left behind.

## Exclusions

- No change to ptrace, capabilities, seccomp, SELinux, namespaces, firewall or system package
  state. The harness starts a **rootless** disposable Postgres container and removes it.
- No secret is committed. The source-trust public key and dummy tokens in the harness are
  throwaway certification values, not production credentials.
- The base package deliberately excludes RemoteInput. The native payload is a separate, gated
  package variant; while the licence marker is `unresolved`, FEDORA-02 records the native-attachment
  cases `NOT_RUN` with that reason instead of claiming them.
- Live RuneLite/Micro6 attachment, capture and input are opt-in (`--live`); without a running client
  they are recorded `NOT_RUN`, never guessed PASS.

## Components

| Component | Path | Purpose |
|---|---|---|
| Packaging library | `scripts/fedora/lib/packaging.sh` | layout, verify, install/rollback/uninstall primitives |
| Package builder | `scripts/fedora/package-distribution.sh` | deterministic tarball + manifest |
| RPM builder | `scripts/fedora/build-rpm.sh`, `packaging/fedora/chromascape.spec.in` | RPM from the tarball |
| Non-root installer | `scripts/fedora/install-tarball.sh` | versioned release + `current` symlink + shim |
| Uninstaller | `scripts/fedora/uninstall-tarball.sh` | removes only recorded layout, proves no residue |
| Certification harness | `scripts/fedora/certify-packaged.sh` | runs the FEDORA-02 matrix, fail-closed |
| Evidence contract | `scripts/qualification/fedora02_evidence.py` | record shape + release gate |
| Regressions | `scripts/fedora/tests/packaging-regressions.sh`, `scripts/qualification/tests/test_fedora02_evidence.py` | fixture coverage |

## Acceptance mapping

| Story criterion | Implementation proof |
|---|---|
| Install tarball without sudo | `tarballInstallNoSudo` check; installer refuses non-absolute prefixes |
| Install RPM in a disposable clean env | `rpmDisposableInstall` check (rootless container) |
| Start without Git/Gradle/compiler/repo files | `launchWithoutBuildTools`; release tree scanned for offenders |
| Java and native dependency detection | `javaDetection` (Boot manifest), `nativeDetection` (manifest-verified pair or absence) |
| Loopback-only binding and `/debugger` | `loopbackBinding` (`ss`, IPv4/IPv6 loopback only), `debuggerEndpoint` (HTTP 200) |
| Discover and select RuneLite/Micro6 | `targetDiscovery`, `explicitSelection` (SC-1667 procfs rules) |
| Attach, capture, bounded input, release, close | `packagedNativeAttachment`, `frameCapture`, `boundedInput`, `inputRelease`, `cleanClose` |
| Restart runtime and target | `runtimeRestart`, `targetRestart` |
| Multiple-client selection | `multipleClients` |
| Upgrade, rollback, uninstall | `upgrade`, `rollback`, `uninstall` |
| No residue | `noResidue` |

## Planning checklist

- [x] Scope and exclusions recorded before implementation.
- [x] Acceptance criteria mapped to named checks.
- [x] Base revision identified and justified.
- [x] Licence gate preserved rather than bypassed.
- [x] Fixture regressions defined for every shipping code path that can run without a client.
- [x] Live-client cases explicitly opt-in and honestly abstained.

## Validation checklist

- [x] `bash -n` on every Fedora script.
- [x] Packaging regressions (fixture) green.
- [x] Evidence-contract unit tests green.
- [x] Real `assembleLinuxDistribution` build succeeds.
- [x] Real tarball builds, verifies and is byte-identical across two builds.
- [x] Real packaged runtime starts, binds loopback only and serves `/debugger`.
- [ ] RPM built on a host with `rpmbuild` or a Fedora container image (toolchain absent here).
- [ ] Live RuneLite/Micro6 attach/capture/input (no client running).
