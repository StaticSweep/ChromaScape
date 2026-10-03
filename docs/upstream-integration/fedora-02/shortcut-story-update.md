# FEDORA-02 shortcut story update

## Title

FEDORA-02: Certify the packaged ChromaScape distribution on a clean Fedora host

## Description

FEDORA-01 proves the tarball and RPM can be built. FEDORA-02 proves they work independently of the
development checkout.

## Delivered

- **Packaging (FEDORA-01).** `scripts/fedora/package-distribution.sh` builds a deterministic
  `chromascape-<version>-linux-x86_64.tar.gz` with a shipped non-root installer, uninstaller and
  `PACKAGE-MANIFEST.json`. `scripts/fedora/build-rpm.sh` + `packaging/fedora/chromascape.spec.in`
  wrap the same tarball as an RPM, using host `rpmbuild` or a rootless podman Fedora container.
  The RemoteInput pair is opt-in and refused while the licence gate is unresolved.
- **Certification (FEDORA-02).** `scripts/fedora/certify-packaged.sh` runs the clean-host matrix and
  records fail-closed evidence through `scripts/qualification/fedora02_evidence.py`.
- **Regressions.** `scripts/fedora/tests/packaging-regressions.sh` and
  `scripts/qualification/tests/test_fedora02_evidence.py`, wired as the Gradle
  `fedoraPackagingRegressions` and `fedora02EvidenceTests` tasks.

## Verified on this host

- Real `assembleLinuxDistribution` build + real tarball, byte-identical across two builds.
- Tarball installed without sudo, started with no Gradle/Git/compiler/repository files, bound
  loopback only, served `/debugger`, restarted, upgraded `0.2.1 → 0.2.0`, rolled back, uninstalled
  with no residue.
- RPM installed and removed in a disposable `registry.fedoraproject.org/fedora:44` container.

## Findings recorded

- The earlier `codex/fedora-poc-packaging` base cannot start the packaged app
  (`TargetInputSessionFactory` lacked `@Autowired`); the qualification lineage fixes it. FEDORA-02 is
  based on `644deac1`.
- The packaged app requires deployment configuration to start: `ANALYSIS_MCP_AUTO_START=false`
  (or a bundled bridge), non-blank stabilization tokens, and SC-810 source trust keys/tokens.
- Fedora 44 no longer provides `java-17-openjdk-headless`; the RPM now requires
  `java-headless >= 17`, and the packaged app was verified to run on Fedora 44's Java 25.

## Not certified (honest gaps)

- Live RuneLite/Micro6 attach, capture, bounded input, release, target restart and multiple-client
  selection require a running client; they are opt-in (`--live`) and recorded `NOT_RUN` otherwise.
- The RemoteInput native pair is `NOT_RUN` while the licence marker is `unresolved`.
