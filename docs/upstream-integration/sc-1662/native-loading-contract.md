# SC-1662 — Native loading contract

## Separation of loading from attachment

The upstream `RemoteInput(int pid)` constructor loads the library **and immediately injects/pairs**.
It must not be used for verification. SC-1662 therefore separates responsibilities:

| Class | Responsibility | Must never |
|---|---|---|
| `NativeArtifactResolver` | Select and validate the artifact | Enumerate processes, select a target, inject, pair, send input |
| `RemoteInputLibraryLoader` | Open the canonical path, verify required exports, bind the interface | Construct a target-bound session |
| `RemoteInputInterface` | Audited JNA mapping of the C ABI | — (declaration only) |
| `RemoteInputManifest` | Read the provenance fields of the adjacent manifest | Trust the manifest as a signature |
| `RemoteInputSmokeVerifier` | Resolve, load, symbol-check and report | Start application services, inject, pair, send input |

`RemoteInputSmokeVerifier` is a `main` entry point run as a bounded subprocess by both verification
tasks. It declares no reference to Spring or `com.chromascape.web` types and references no `EIOS_*`
symbol — a unit test asserts this against the compiled class file, so the verification path cannot
start the application or reach a native input call.

## Resolution contract

Precedence, highest first:

1. system property `chromascape.remoteinput.library`;
2. environment variable `CHROMASCAPE_REMOTEINPUT_LIBRARY`;
3. `<applicationRoot>/native/linux-x86_64/libRemoteInput.so`.

Rules enforced by `NativeArtifactResolver`:

- An explicit override must be an absolute path to a readable regular file.
- An **invalid explicit override fails immediately** (`MISSING_LIBRARY` / `INVALID_ARTIFACT`); it
  never falls back to the environment or the managed path.
- The selected path is canonicalised via `toRealPath()` before loading.
- The host must be Linux x86_64 and the file must be a 64-bit little-endian x86-64 ELF `ET_DYN`
  shared object.
- The working directory, `PATH`, downloads and system library directories are never searched, and no
  artifact is ever fetched at runtime.

### Loader path contract

`RemoteInputLibraryLoader.load(Path)` requires an **absolute, normalised** path — normally the value
returned by `NativeArtifactResolver.resolve`. It previously called `toAbsolutePath()`, which
silently resolved a relative path against the process working directory and could therefore load a
different library depending on where the JVM started. That is now rejected with `INVALID_ARTIFACT`,
and three tests assert that a relative or non-normalised path never reaches the native access
boundary, including the case where a plausible same-named file exists in the working directory.

## Verification modes — distinct and separately implemented

Both modes run `RemoteInputSmokeVerifier` as a bounded subprocess (`timeout = 5 minutes`; native load
constructors can execute, block or crash even when no EIOS operation is called).

### The manifest contract is verified before the library is loaded

Each run resolves the artifact, measures its size and SHA-256, locates the adjacent
`remoteinput-manifest.json`, and **fails if it is absent**. The manifest is then read with strict
validation — supported schema version, 64-character hex artifact digest, positive artifact size,
40-character hex source revision, non-empty patch identity — and its recorded digest and size must
equal the measured ones. Only after that does the verifier load the library.

Two consequences matter. Loading is not side-effect free (a load-time constructor self-instruments
the loading process), so a pair that fails this contract is rejected before any `dlopen`. And a pair
that cannot be verified is never reported as a success: the previous ordering loaded first, treated a
missing manifest as `null` and still printed `revision=unknown` with `SMOKE_OK`.

### Development — `verifyRemoteInputLinux`

- Classpath: the development runtime classpath (`sourceSets["main"].runtimeClasspath`).
- Application root: `build/dist`, resolving to `build/dist/native/linux-x86_64/libRemoteInput.so`.
- Launched with `java -cp <dev classpath> …RemoteInputSmokeVerifier`.

### Packaged — withdrawn

There is **no** packaged verification task. It loaded the native artifact out of the assembled
distribution, and the assembled distribution no longer contains one, so it could only ever report
`MISSING_LIBRARY`. `assembleLinuxDistribution` ships no `libRemoteInput.so`, no `third-party/RemoteInput`
tree and no RemoteInput licence file: the vendored upstream sources carry no licence at all, so no
distributable terms exist for them, and a library compiled from them would be a derivative work
regardless.

The entry point below is documented rather than deleted, because it is what a distribution shipping a
licensed library again would have to use, and `VerifyRemoteInputTask` still implements `packaged`
mode:

```
java -Dloader.main=com.chromascape.utils.core.input.remoteinput.RemoteInputSmokeVerifier \
     -cp <dist>/application.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher \
     --mode packaged --app-root <dist>
```

The Spring Boot JAR keeps application classes in `BOOT-INF/classes` and dependencies in
`BOOT-INF/lib`, so a plain `java -cp application.jar <MainClass>` cannot reach them. The
distribution's `Main-Class` is `org.springframework.boot.loader.launch.JarLauncher`, which offers no
way to select a different main class; `PropertiesLauncher` does, through `-Dloader.main`.
`PropertiesLauncher` is present in the assembled jar (verified by inspecting it). Setting a property
on the normal launcher would not load the library, because the application never calls the loader.

`verifyRemoteInputLinux` runs the verifier three times and requires the same canonical path and
SHA-256 each time:

1. with the application root as the working directory;
2. from an unrelated temporary directory;
3. from a copy placed under a path containing spaces.

Run 2 evidence for the packaged routing mechanism is in
`evidence/r2-staging-and-packaged-routing.log`: `PropertiesLauncher` executed the verifier (exit 1
with `MISSING_LIBRARY` for an absent root) with no application startup output. That mechanism is no
longer wired to a task, for the reason given above.

### Reported on success

The verifier prints one machine-readable line, only after the manifest contract was verified, the
artifact loaded and every required symbol resolved:

```
SMOKE_REPORT {"status":"SMOKE_OK","mode":"packaged","canonical":"/abs/.../libRemoteInput.so",
              "sha256":"...","sizeBytes":8256,"revision":"...","patch":"none","symbols":23}
```

The record is a single JSON object rather than a whitespace-delimited line, because a delimiter-based
parse silently truncated any canonical path containing a space. The Gradle verification tasks parse it
and assert, for **all three** runs, the mode, the digest and the canonical path against the expected
real path. The unrelated-cwd and spaced-path runs are only evidence if their own reports are
examined, not merely their exit codes.

`SMOKE_OK` is never printed merely because a process started. `--expect-sha256 <hex>` optionally pins
the expected digest.

## Distribution layout

```
build/dist/linux-x86_64/
├── application.jar
├── launcher
└── licenses/
    └── ChromaScape-LICENSE
```

The launcher derives its root from its own location (`dirname "$0"`), never the caller's working
directory, and names the artifact explicitly.

RemoteInput is **not** in this distribution. No `libRemoteInput.so`, no `third-party/RemoteInput`
tree and no RemoteInput licence file is shipped, because the vendored upstream sources carry no
licence at all: `third-party/RemoteInput/README.md` makes no licensing statement, `setup.py` names no
licence, the upstream repository root has no `LICENSE` or `COPYING`, and GitHub's licence detector
reports none for it. With no terms to distribute under, the sources cannot be redistributed and a
library compiled from them would be a derivative work of them regardless, so shipping the binary
without the sources would not help.

Two further consequences of that decision:

- `assembleLinuxDistribution` no longer depends on `stageRemoteInputLinux` or on
  `verifyRemoteInputDistributionLicence`. It deletes any `native/` directory left in the distribution
  root by an earlier run, so a stale payload cannot survive into a distribution that is supposed to
  be free of one.
- The launcher no longer passes `-Dchromascape.remoteinput.library=…`. That property is the highest
  precedence *override* in `NativeArtifactResolver`; pointing it at a file the distribution does not
  contain would be worse than omitting it. The `<applicationRoot>/native/linux-x86_64/libRemoteInput.so`
  tier is resolved from the application root and is untouched, so a deployment that places its own
  licensed library there is still picked up.

The licence gate itself (`verifyRemoteInputDistributionLicence`) is retained and still fails closed
on the unresolved marker in `third-party/RemoteInput/PROVENANCE.md`, but it is no longer wired into
the assembly: it guards a payload the distribution no longer carries. Because it is no longer a
prerequisite, it must be run explicitly if its state is to be evidenced.

## Staging contract

`stageRemoteInputLinux` refuses to publish unless the library and manifest form a valid pair:

1. both files exist and are non-empty;
2. the manifest parses and validates against the library (size, SHA-256, ELF identity, platform,
   symbol inventory, repeat-build consistency);
3. the expected pair identity is computed as `sha256(artifact-sha256 ‖ 0x00 ‖ manifest-sha256)`;
4. if `.versions/<pair-identity>/` already exists it is re-validated against its own manifest **and**
   its recomputed pair identity must equal the expected identity — a modified but internally valid
   pair under another pair's directory name is refused rather than reused;
5. otherwise the pair is copied into a temporary directory inside `build/dist/native`, set to `0644`
   and **re-validated where it actually landed**, including its recomputed pair identity;
6. the temporary directory is renamed to `build/dist/native/.versions/<pair-identity>/` — itself a
   single atomic operation, so a payload directory either exists complete or does not exist;
7. only then is the publish point switched: `.linux-x86_64.<random>` is created as a relative
   symlink to `.versions/<pair-identity>` and renamed over `build/dist/native/linux-x86_64`.

Step 7 is the only visible publish step, and a symlink rename is one atomic filesystem operation, so
an interrupted publish cannot expose a new library beside an old manifest (or the reverse): the
publish point always names a complete, re-validated pair. The previously published payload is not
touched before the swap succeeds, and the current and previous payloads are retained as fallbacks.
If the filesystem cannot rename atomically, publishing fails rather than degrading to a copy that
could expose a partial pair.

Publishing the two files separately — the previous design, in both the Gradle task and the shell
script — could not provide this: an interruption between the two moves left the new library paired
with the old manifest. A versioned payload plus a single symlink swap is what makes the pair switch
indivisible.

`verifyRemoteInputLinux` and `assembleLinuxDistribution` therefore resolve *through* the symlink;
the distribution copy dereferences it so the shipped tree contains real files.

The shell script implements the same layout with `mv -T` (a `rename(2)`) for both the payload and the
symlink swap.

## Diagnostics

`RemoteInputDiagnostic`: `MISSING_LIBRARY`, `UNSUPPORTED_PLATFORM_OR_ARCH`, `INVALID_ARTIFACT`,
`LOAD_FAILED`, `MISSING_SYMBOL`. Failures carry the selected path, the expected platform, and the
original native cause. They never contain secrets, account identity or process command lines.

Failure is terminal: no fallback attachment, no privilege changes, no injection. Nothing loads the
library eagerly — the classes perform no work in static initializers, and merely referencing
`RemoteInputLibraryLoader` performs no native access (covered by a unit test).

## ABI audit — findings

Checked against `RemoteInput/EIOS.hxx` and `Plugin/NativePlugin.hxx`:

- `extern "C"` + `[[gnu::visibility("default")]]` on Linux ⇒ unmangled `EIOS_*` exports.
- `std::int32_t` → Java `int`; `std::int32_t*` → `IntByReference`.
- `std::size_t` → Java `long`.
- C++ `bool` → Java `byte`. JNA's default Java `boolean` mapping is a 32-bit integer, while the
  pinned Linux C++ ABI uses a one-byte `bool`; the binding exposes explicit conversion helpers and
  a reflection test locks every affected method to the one-byte shape.
- **Defect corrected**: the upstream Java binding declared `boolean EIOS_IsMouseHeld(...)`, and the
  stale Windows `RemoteInput.def` lists `EIOS_IsMouseHeld@8`, but **no such symbol exists in the
  pinned native sources**. The real export is `EIOS_IsMouseButtonHeld` (`EIOS.cxx:212`,
  `EIOS.hxx:62`). The audited interface declares `EIOS_IsMouseButtonHeld`; a test asserts
  `EIOS_IsMouseHeld` is not in the required list.
- `EIOS_Inject_PID` exists but is declared in `Plugin/NativePlugin.hxx`, not `EIOS.hxx`.

### Unverified ABI assumptions (still open)

Symbol **existence** is verified; ABI **correctness** is not. Finding a symbol proves nothing about
its parameter or return mapping. The following remain unverified until a real artifact is exercised:

- `std::size_t` → `long` and pointer/`IntByReference` out-parameters are derived from header
  inspection, not from a built ELF.
- The one-byte C++ `bool` shape is verified structurally, but invocation against a real artifact is
  still required to confirm the complete calling boundary.

These are recorded rather than silently assumed.

## Load-time constructors (mandatory pre-smoke inspection)

Inspected before designing the smoke test:

- `Plugin/Plugin.cxx:134` — `[[gnu::constructor]] void __load()` (Linux branch). At load time it
  spawns a **detached thread** which calls `GetNativeReflector()` and, if successful, constructs
  `ControlCenter(getpid(), false, …)` and calls `StartHook()` for **the process doing the loading**.
- `Thirdparty/linux_detours/src/detours.cpp:3315` — `library_entry_point()` is a
  `__attribute__((constructor))` that calls `DetourBarrierProcessAttach()` and
  `DetourCriticalInitialize()`; the matching destructor runs `DetourCriticalFinalize()`.

Consequences, and why verification is a bounded subprocess:

1. `dlopen` of this library is **not side-effect free**. It self-instruments the loading process
   (`getpid()` is the loader, not a remote target) and installs detours.
2. It can block or crash, so verification must not run in-process; both verification tasks impose a
   subprocess timeout.
3. This is expected behaviour for an injected target and is SC-1664's lifecycle concern; SC-1662
   only records it as a load-time property and never calls an `EIOS_*` function.

## Commands deliberately not implemented

No process discovery, target selection, injection, pairing, mouse/keyboard operations, capture, or
recorder integration. Those belong to SC-1663 through SC-1667.
