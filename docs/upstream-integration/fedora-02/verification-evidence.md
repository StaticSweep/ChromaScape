# FEDORA-01/02 verification evidence

This file records what was actually executed and observed. Checks not run are named as such; no
fixture result is presented as a live result.

## Environment

| Item | Value |
|---|---|
| Host | Fedora Linux 44 (Workstation Edition), x86_64 |
| Runtime Java (packaged, verified) | `openjdk 25.0.4.1` (Fedora 44 default) |
| Build Java (Gradle toolchain) | `17.0.20.1` |
| Base revision | `644deac116d44071b4b6d02ecd15d1c91861569a` |
| Database for live start | disposable `postgres:16-alpine` container (rootless podman) |
| RPM toolchain | rootless `registry.fedoraproject.org/fedora:44` container with `rpm-build` |

## FEDORA-01 artifacts

| Artifact | SHA-256 |
|---|---|
| `chromascape-0.2.0-linux-x86_64.tar.gz` | `4b306dd9eda8bb9616ff0731adc3800e928e48c7f042dd2242e88db3e14ee375` |
| `chromascape-0.2.0-1.fc44.x86_64.rpm` | `9b560340477f295f077b34385bb90b6662dd16022fced9b6b8cbb92576afaf24` |

- `./gradlew --offline --no-daemon assembleLinuxDistribution` → BUILD SUCCESSFUL.
- Two independent tarball builds of the same distribution were **byte-identical** (fixture
  regression `deterministic rebuild`).

## FEDORA-02 tarball certification

`scripts/fedora/certify-packaged.sh --package …0.2.0… --previous-package …0.2.1… --evidence …`
→ `FEDORAPKG level=PASS code=CERTIFICATION`. 11 PASS, 9 NOT_RUN, 0 FAIL.

| Check | Result | Evidence |
|---|---|---|
| `tarballInstallNoSudo` | PASS | non-root install into a private prefix; shim written |
| `launchWithoutBuildTools` | PASS | release has only `application.jar` + `launcher`; no Gradle/Git/compiler inputs |
| `javaDetection` | PASS | Boot `Start-Class=com.chromascape.web.ChromaScapeApplication` |
| `nativeDetection` | PASS | base package has no stale `native/`; loader resolves `<appRoot>/native/linux-x86_64` |
| `loopbackBinding` | PASS | only `[::ffff:127.0.0.1]:<port>` listener |
| `debuggerEndpoint` | PASS | `/debugger` returned HTTP 200 |
| `runtimeRestart` | PASS | stop/start re-served `/debugger` |
| `upgrade` | PASS | current `0.2.1 → 0.2.0` without rebuilding |
| `rollback` | PASS | current `0.2.0 → 0.2.1` |
| `uninstall` | PASS | prefix + shim removed |
| `noResidue` | PASS | no install path, process or disposable container remains |
| `targetDiscovery`, `explicitSelection` | NOT_RUN | no eligible RuneLite/Micro6 client running |
| `packagedNativeAttachment`, `frameCapture`, `boundedInput`, `inputRelease`, `cleanClose`, `targetRestart`, `multipleClients` | NOT_RUN | live client checks are opt-in; no client present |

## FEDORA-02 RPM certification

`scripts/fedora/certify-packaged.sh --role rpm --rpm …` →
`FEDORAPKG level=PASS code=CERTIFICATION`. 6 PASS, 14 NOT_RUN, 0 FAIL.

| Check | Result | Evidence |
|---|---|---|
| `rpmDisposableInstall` | PASS | installed in a disposable Fedora 44 container |
| `launchWithoutBuildTools` | PASS | installed tree has no Gradle/Git/compiler inputs |
| `javaDetection` | PASS | `dnf` resolved a Java 17+ runtime (Java 25); `java -version` supported |
| `nativeDetection` | PASS | base RPM carries no native payload |
| `uninstall` | PASS | `dnf remove chromascape` removed the package |
| `noResidue` | PASS | no package files or rpmdb entry remain |
| runtime/client checks | NOT_RUN | the container certifies install/layout/removal only |

Evidence matrix gate:

```
python3 -B scripts/qualification/fedora02_evidence.py verify \
  build/packages/fedora02-tarball-evidence.json build/packages/fedora02-rpm-evidence.json
→ FEDORA02_EVIDENCE status=VALID forms=2

python3 -B scripts/qualification/fedora02_evidence.py verify --require-complete …
→ FEDORA02_EVIDENCE status=INVALID …  (release gate correctly refuses NOT_RUN cases)
```

## Fixture regressions

| Command | Result |
|---|---|
| `bash scripts/fedora/tests/packaging-regressions.sh` | 45 passed, 0 failed |
| `python3 -B -m unittest discover -s scripts/qualification/tests -t .` | 18 passed, 0 failed |

## Findings from certification

1. The `codex/fedora-poc-packaging` base cannot start the packaged app:
   `TargetInputSessionFactory` had two constructors and no `@Autowired`, so Spring failed with
   "No default constructor found". The qualification lineage (`644deac1`) fixes this; FEDORA-02 is
   based there.
2. The packaged app requires deployment configuration to start: `ANALYSIS_MCP_AUTO_START=false` (or a
   bundled bridge directory), non-blank stabilization tokens, and SC-810 source trust key/tokens.
3. Fedora 44 no longer provides `java-17-openjdk-headless`. The RPM now requires the portable
   capability `java-headless >= 17`, and the packaged app was verified to run on Fedora 44's Java 25.
4. The RemoteInput native pair remains `NOT_RUN` because
   `third-party/RemoteInput/PROVENANCE.md` records `top-level-licence-resolution: unresolved`.
   `package-distribution.sh --native-payload` fails closed with `LICENCE_GATE_UNRESOLVED`
   (regression-covered).

## Not executed

- Live RuneLite/Micro6 attachment, frame capture, bounded input, held-input release, target
  restart and multiple-client isolation. These need a running host-native client and the native
  pair, and are the remaining FEDORA-02 work on a client-equipped host via `--live`.
