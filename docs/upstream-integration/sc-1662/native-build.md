# SC-1662 — Native build (Linux x86_64)

## Purpose

Build `libRemoteInput.so` from the pinned vendored sources on Fedora x86_64 and record exactly what
produced it. This document covers Phase A (import), Phase B (build), Phase C (manifest/staging) and
the provenance gate that now runs before any of it.

## Sources

Vendored under `third-party/RemoteInput/` (hyphen). This is distinct from the existing fork
`third_party/KInput/` (underscore), which is untouched. See `third-party/RemoteInput/PROVENANCE.md`
for the import record, exclusions, identity check and the unresolved licensing item.

- Pinned commit: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`
- 278 imported files; verified byte-identical to the pinned commit (0 drift).

## Source provenance gate (mandatory, runs first)

`scripts/native/remoteinput_metadata.py verify-sources` reads
`third-party/RemoteInput/import-manifest.sha256` and, **before any compiler is looked for**:

1. confirms every listed path exists and is a regular file;
2. recomputes each SHA-256 and compares it with the manifest;
3. detects files present in `third-party/` that are neither listed nor one of the two documented
   local metadata files (`RemoteInput/PROVENANCE.md`, `RemoteInput/import-manifest.sha256`);
4. derives `actualSourceTreeSha256` — a deterministic digest over `path + measured digest` for the
   tree that was actually built;
5. reports `localPatchIdentity: "none"` **only** when every check passed.

Any drift fails the build before configuration. A genuine local patch must be declared in a JSON
patch declaration (`--patch-declaration FILE`, fields `patchIdentity`, `reason`, `changedPaths`,
`addedPaths`, `removedPaths`). The declaration is checked **in both directions**: `changedPaths`,
`addedPaths` and `removedPaths` must equal the measured change set exactly, so an unchanged file
declared as changed, a non-existent file declared as added, or a declared removal that still exists
are all rejected — a subset check would have let a declaration describe a patch the tree does not
contain. `patchIdentity` is not trusted either: it must equal the identity derived from the
declared paths, their measured digests and the declared reason (the helper prints the expected
`sha256:…` value when it disagrees). A declaration may not claim `none`. This is what stops a
modified native tree from producing a manifest that claims an unchanged pinned import.

## Build script

```sh
scripts/native/build-remoteinput-linux.sh [--jobs N] [--repeat-build]
                                          [--patch-declaration FILE] [--no-stage]
```

- `--jobs N` — positive integer in `1..256`. `0`, negatives, non-numeric and out-of-range values are
  rejected with exit code 2 before anything else runs. Unset defaults to `min(nproc, 4)`.
- `--repeat-build` — second clean build; both hashes and their equality are recorded.
- `--patch-declaration FILE` — documented local patch; see above.
- `--no-stage` — build and validate without publishing to `build/dist`.

Behaviour:

- Resolves the repository root from the script location, so it does not depend on the caller's
  working directory. Paths are quoted, so directories containing spaces are supported.
- Preflights, and fails closed with an actionable message, for: Linux x86_64; a C++20-capable
  compiler; CMake ≥ 3.15; `make`; the GNU assembler (needed for `kubo_injector`'s `shellcode.S`); a
  JDK 17 `JAVA_HOME` with JNI headers; Python ≥ 3.8 with `Python.h`; OpenGL and X11 headers; and
  `file`/`sha256sum`/`readelf`/`nm`/`ldd`.
- Proves the GL/X11 **link** libraries, not just the headers. A throwaway translation unit is
  compiled and linked with the already-validated compiler using `-lGL -lX11`, and the resulting
  binary is then resolved with `ldd`. A missing link library is therefore reported by the preflight
  rather than deferred to CMake.
- Resolves the built artifact's own dependencies with `ldd` in `validate_artifact` and **fails** if
  any is `not found`, rather than deferring every load-time failure to verification.
- Refuses to install packages, run as root, or change ptrace/`setcap`/sandbox state. It never
  downloads sources or binaries.
- Builds out-of-tree into `build/native/remoteinput/linux-x86_64/` with `-DCMAKE_BUILD_TYPE=Release`
  and bounded parallelism. The build directory is deleted before configuring, so a failed build
  cannot present stale output as new.

### Toolchain selection is verified, not assumed

The script passes the preflighted compiler as `-DCMAKE_CXX_COMPILER` and the preflighted interpreter
as `-DPython_EXECUTABLE` (the variable `FindPython` honours; the legacy `-DPYTHON_EXECUTABLE` is also
passed for any `find_package(PythonInterp)` path). After configuring it re-reads `CMakeCache.txt`
and **fails** if CMake selected a different C++ compiler or Python interpreter than the preflight
validated. The resolved values, not the preflight guesses, are what the manifest records.

`JAVA_HOME` comes from the Gradle Java 17 **toolchain**, not from the Gradle daemon's JVM:
`buildRemoteInputLinux` resolves the toolchain through `javaToolchains.launcherFor` and sets
`JAVA_HOME` from its installation path at execution time. A daemon running a different supported JDK
therefore cannot supply the wrong JDK.

## Python bindings are hard-coded

The pinned `CMakeLists.txt` uses `set(PYTHON_BINDINGS ON)`, `set(USE_PYBIND11 OFF)` and
`set(USE_PYTHON3 OFF)` as **normal** variables, which shadow cache entries. Passing
`-DPYTHON_BINDINGS=OFF` therefore does **not** override them. Pinned behaviour is retained; no
speculative patch was applied. Disabling Python would require a documented patch, recorded in
`PROVENANCE.md`, which has not been necessary or justified.

## Fedora packages

The upstream guide lists only four packages and is not a sufficient dependency manifest. Verified
set for Fedora x86_64 (names checked against this host's package layout):

```
make cmake gcc-c++ python3-devel mesa-libGL-devel libX11-devel binutils file
# plus a JDK 17 with JNI headers, e.g. java-17-openjdk-devel
```

## Containerized clean-Fedora build (accepted for SC-1662)

The SC-1662 build host has no compiler toolchain and no passwordless sudo, so the clean-Fedora build
runs in a rootless podman container (explicitly accepted for this story). The exact sequence, with
the run-6 identities:

```
# 1. Base image
podman pull fedora:latest                     # fedora:latest -> image 8c45708ba0fc,
                                              # digest sha256:e64b8b79...f3383
# 2. Build container: keep-id maps the host user to uid 1000 (the build script refuses root and
#    file ownership must map back to the host); repo at /ws, host JDK 17 bind-mounted at /opt/jdk17
podman run -d --name sc1662-build --userns=keep-id \
  -v "$PWD":/ws -v "$HOME/.jdks/corretto-17.0.20.1":/opt/jdk17:ro \
  --entrypoint sleep fedora:latest infinity
# 3. Toolchain packages (installed as container root; confined to the container namespace)
podman exec -u root sc1662-build \
  dnf install -y make cmake gcc-c++ python3-devel mesa-libGL-devel \
                 libX11-devel binutils file java-17-openjdk
# 4. Commit the toolchain state so the build image is reproducible
podman commit sc1662-build sc1662-fedora-toolchain
                                              # -> image cabd7e24e0ca,
                                              # digest sha256:15179d9f...72af4
# 5. Native build inside the container (repo is the same mounted tree)
podman exec -e JAVA_HOME=/opt/jdk17 sc1662-build \
  ./gradlew --offline buildRemoteInputLinux -PremoteInputRepeatBuild=true -PremoteInputJobs=4 \
             -Psc967VectorsPath=/ws/…/ed25519-vectors.json
```

Run-6 container identity: Fedora release 44 (glibc-2.43-8.fc44.x86_64), podman 5.8.1, GCC 16.2.1,
CMake 4.3.0, Python 3.14.7, JDK `openjdk 17.0.20.1` at `/opt/jdk17`; full record retained as
`evidence/r6-container-identity.txt`.

When the compile runs in the container, the host Gradle daemon cannot reproduce that environment, so
the host invokes staging with `-PremoteInputSkipBuild=true` after the container build succeeds. The
property is an explicit trust decision for the container workflow; staging still revalidates the
pair's measurable properties before publishing (see the `remoteInputSkipBuild` note in
`build.gradle.kts`).

## Artifact and manifest

On success:

```
build/native/remoteinput/linux-x86_64/libRemoteInput.so
build/native/remoteinput/linux-x86_64/remoteinput-manifest.json   # provenance, written here
build/dist/native/.versions/<pair-identity>/libRemoteInput.so      # immutable published payload
build/dist/native/.versions/<pair-identity>/remoteinput-manifest.json
build/dist/native/linux-x86_64 -> .versions/<pair-identity>        # the publish symlink
```

`<pair-identity>` is the identity of the pair, not of the library: `sha256(artifact-sha256 ‖ 0x00 ‖
manifest-sha256)`, with a single NUL byte between the two digests. Keying on the library alone would
make a byte-identical rebuild — which still produces a different manifest (`generatedAtUtc` at
minimum) — reuse the old payload and discard the new manifest.

`build/dist/native/linux-x86_64` is a **symlink**, not a directory. The validated pair is copied
into a temporary directory, re-validated where it landed (including a recomputed pair identity),
renamed into a content-addressed `.versions/<pair-identity>/` directory, and only then exposed by
renaming a freshly created symlink over the publish point. That rename is a single atomic filesystem
operation, so an interrupted stage cannot leave a new library beside an old manifest; the previously
published payload is untouched until the swap succeeds and is retained as the fallback. Older
payloads beyond the current and previous one are pruned. If the payload directory already exists it
is re-validated **and** must recompute to the expected pair identity before it is reused — a modified
but internally valid pair under another pair's directory name is refused, not trusted. Development
verification (`verifyRemoteInputLinux`) resolves through the symlink.

`assembleLinuxDistribution` no longer consumes the staged output at all: the packaged distribution
ships no native payload, because the vendored sources carry no licence to distribute them under. The
staging layout above is therefore a local development and verification surface only, and the
distribution dereference that used to justify this symlink design no longer applies.

The manifest is produced by `scripts/native/remoteinput_metadata.py write-manifest`. Measured build
facts reach the helper as a `key=value` sidecar (repeated keys for symbol lists) and are serialized
with Python's `json` module; **no measured value is ever interpolated into JSON by the shell**. That
is what previously produced `{"javaVersion": "openjdk version "17.0.20.1" …"}`. The helper:

1. hashes and sizes the artifact and reads its ELF header (class/machine/type) itself;
2. serializes to a temporary file in the destination directory;
3. re-reads and validates what was actually written;
4. publishes by `os.replace` (atomic) only if validation passed.

Manifest fields: `schemaVersion`, `generatedAtUtc`, `sourceRepository`, `sourceRevision`,
`importManifestSha256`, `localPatchIdentity`, `localPatch`, `actualSourceTreeSha256`,
`sourceInventory`, `host`, `compiler`, `cmake`, `jdk`, `python`, `buildOptions`, `artifact`
(filename/size/SHA-256/ELF identity), `declaredSharedLibraryDependencies`, `requiredSymbols`,
`exportedSymbols`, `repeatBuild`. Unexecuted measurements are `null`, never guessed.

Validation enforces: supported schema version; valid revision and SHA-256 formats; non-empty
artifact; **manifest size and hash equal the measured file**; expected platform and architecture;
required-symbol inventory present and a subset of the exported inventory; and internally consistent
repeat-build fields.

`--repeat-build` performs a second clean build and records both hashes and whether they matched.
Byte-identical output across different toolchains is not promised.

The manifest is **provenance, not a signature or a trust guarantee**.

## Gradle interface

```bash
./gradlew buildRemoteInputLinux -PremoteInputRepeatBuild=true -PremoteInputJobs=4
```

### Launch environment — run Gradle on Java 17

The Gradle Kotlin DSL in this project does **not** configure on the default Java 25.0.4.1 JVM: the
build fails during script compilation with `IllegalArgumentException: 25.0.4.1`. Every invocation
must therefore name a Java 17 `JAVA_HOME` explicitly. On the verification host:

```bash
JAVA_HOME=/home/<user>/.jdks/corretto-17.0.20.1 \
GRADLE_USER_HOME=<repo>/ChromaScape/.gradle-home \
./gradlew --offline <tasks...> \
  -Psc967VectorsPath=<contracts>/sc967/ed25519-vectors.json
```

This is the launcher environment, not the native toolchain: the native build additionally resolves
its own JDK 17 from the Gradle toolchain (see below).

### Dependency-cache bootstrap — `--offline` does not work on a clean host

`GRADLE_USER_HOME` above points at a cache that must be **populated first**. A clean host starting
directly with `--offline` fails in the dependency-resolution phase: Gradle cannot resolve the build's
plugin classpath (Spring Boot, Spotless, Checkstyle) or the compile/test classpath, and fails with
"No cached version of … available for offline mode". `--offline` proves reproducibility of the
*workflow*; it does not provision the machine.

Run two stages, in this order.

Stage 1 — **online** bootstrap (no `--offline`), which populates the dependency cache:

```bash
./gradlew compileJava compileTestJava testClasses bootJar \
  -Psc967VectorsPath="$SC967_VECTORS"
```

Stage 2 — prove the required workflow works **offline** once the cache is populated:

```bash
./gradlew --offline compileJava compileTestJava bootJar \
  -Psc967VectorsPath="$SC967_VECTORS"

./gradlew --offline assembleLinuxDistribution \
  -Psc967VectorsPath="$SC967_VECTORS"
```

Run-6 records (see `evidence/index.md`): the initial `--offline compileJava compileTestJava bootJar`
attempt on the fresh worktree-local cache failed with missing cached artifacts for the build
script's plugin classpath; the Stage-1 online command populated the cache; the Stage-2 offline
commands then passed. **A clean machine cannot operate offline before the Gradle cache is
populated; do not quote the offline commands without the bootstrap stage.**

Whether this repository shares a populated `GRADLE_USER_HOME` across worktrees affects only the
first stage; the two-stage order is required regardless.

`--repeat-build` is a script flag only, so `-PremoteInputRepeatBuild=true` is the supported Gradle
interface to it (accepted values: `true`, `1`, `yes`, case-insensitive; absent or anything else
means no repeat build). `-PremoteInputJobs` is validated against the same `1..256` range. The task
deliberately always executes (`outputs.upToDateWhen { false }`) because the resolved toolchain
identity is not a declared input and a cached artifact describing a different JDK or compiler must
never be reused.

### Linux symbol names cannot come from `RemoteInput.def`

The only canonical symbol list in the tree is the Windows module-definition file, which is
`__stdcall`-decorated (`EIOS_*@N`) and stale. On Linux the API is `extern "C"` with
`[[gnu::visibility("default")]]`, so exports are unmangled. The required-symbol list is derived from
`EIOS.hxx` plus `EIOS_Inject_PID` (`Plugin/NativePlugin.hxx`), and is mirrored by
`RemoteInputLibraryLoader.REQUIRED_SYMBOLS`.

## Historical note (not an execution record)

SC-1661 recorded 175 focused launcher tests passing and an ordinary suite of 2250–2255 tests with
110 failures / 13 skips, plus 12 replay failures and 16 Spotless offenders. Those are **comparison
data**, not SC-1662 evidence. SC-1662's own execution status is in `verification-evidence.md`.
