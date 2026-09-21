# SC-1662 — Linux RemoteInput build and load: implementation specification

> **Frozen pre-implementation planning record (written 2026-09-14).** This is the specification as
> written *before* any SC-1662 code existed. Its substantive plan is retained for that record; only
> this banner and explicit historical qualifiers were added. Its status line, test checklist and
> closing "plan ... not implement" note describe the plan, not the current tree.
> Current implementation state and executed evidence are in
> [`implementation-evidence.md`](./implementation-evidence.md) and
> [`verification-evidence.md`](./verification-evidence.md) (run 6 is the latest retained bundle);
> nothing below should be read as a
> current status claim.

Status when written: planned only; no production implementation or SC-1662 test pass existed.
Story: https://app.shortcut.com/leeuwbot/story/1662
Canonical project: C:\Dev\IdeaProjects\ChromaScape
Classification: change:runtime. Intended workflow: shortcut_delivery.
Branch: codex/sc-1662-linux-remoteinput.
Parent: merged integration commit 3cb66d86d9bb1f7a4f29837888b1b537d0bfd8d3.
Pinned source: StaticSweep/ChromaScape 5ea3b995f5d13e19c592a6399973fe7dadb5e656.
Reconciled production baseline: 966e0e3e47db71fe5d57629205efcb23cd11e026.

## Prerequisite closure and planning record

SC-1661 was reviewed by the user and both PRs merged on 2026-09-14:
- Prerequisite #3: af005016a39ab45b14c6d7731dcf3afd25855dd7.
- Reconciliation #4: 3cb66d86d9bb1f7a4f29837888b1b537d0bfd8d3.
The fetched integration tree is identical to reviewed head 765e22a.
Shortcut SC-1661 was explicitly marked Done at the user's request.
Felix task lookup returned no tagged task; SC-1662 creation returned
"Project not found: C:\Dev\IdeaProjects\ChromaScape".
This is a project-resolution error, not a transport outage. No task, spec_ready gate,
state transition, or Felix synchronization succeeded. This committed specification
and the Shortcut description preserve the plan; they are not a substitute claim of
Felix persistence. Resolve the canonical identity, scan/reconcile existing pending
records, search the stable tag again, and persist this specification before delivery.
Do not create a duplicate POSIX identity or bypass validation.

## Outcome and exclusions

Build libRemoteInput.so from pinned, auditable sources on native Fedora x86_64,
stage it predictably, and load/resolve its required bindings from Java 17 in
development and packaged execution without reliance on the current directory.
Preserve the existing Windows KInput backend and packaging.

Out of scope: /proc target discovery and selection (SC-1663); automatic injection,
target pairing, input and cleanup lifecycle (SC-1664); pixel-buffer capture and
screen consumers (SC-1665); recorder/debugger integration (SC-1666); broad startup
diagnostics (SC-1667); end-to-end host qualification (SC-1668); SC-1696/1697 fixes.
No network video protocol, macOS/ARM qualification, JDK/Gradle upgrade, automatic
dependency installation, root execution, setcap, ptrace policy or sandbox changes.

## Evidence-backed starting points

Read before changes:
- docs/upstream-integration/sc-1661/compatibility-map.md, especially Decision 6
  and Boundaries 5/7; upstream-commit-ledger.md and baseline-validation.md.
- Pinned third-party/DEV_README.md and third-party/RemoteInput/CMakeLists.txt.
- Pinned utils/core/input/remoteinput/RemoteInput.java and RemoteInputInterface.java.
- Current build.gradle.kts, settings.gradle.kts and Windows KInput load path.

Observed upstream CMake uses C++20, Java/JNI discovery, Python bindings enabled
by default, and links Linux GL/X11/dl/pthread/rt. Its Python choices are hard-coded:
do not assume a -D option overrides the current set() statements.
The upstream RemoteInput(pid) constructor loads AND immediately injects/pairs.
Do not instantiate it for a library smoke test; separate loading from attachment.
Current copyNativeLibraries stages KInput DLLs into build/dist only on Windows.
A wholesale upstream Gradle replacement would discard fork-specific tasks.

## Architecture and proposed interfaces

Separate three concerns in the remoteinput package:
1. NativeArtifactResolver: resolves a configured artifact and validates metadata.
2. RemoteInputLibraryLoader: loads an absolute canonical path through JNA and
   verifies the required exported symbols without selecting or injecting a PID.
3. Existing/new binding interface: copied selectively and audited against EIOS
   headers. SC-1664 will own constructing a target-bound session later.

Proposed configuration contract:
- Explicit chromascape.remoteinput.library system property takes precedence over
  CHROMASCAPE_REMOTEINPUT_LIBRARY environment variable.
- Explicit paths must be absolute regular readable files; invalid explicit settings
  fail without trying another binary.
- Otherwise use an explicitly supplied application/native root. Development Gradle
  launch supplies the repository's build/dist root. Packaged launch supplies a
  distribution-root-relative native directory from its launcher location, not cwd.
- Expected Linux layout: build/dist/native/linux-x86_64/libRemoteInput.so plus
  remoteinput-manifest.json. Packaged distribution uses the same native subtree
  beside the application jar.
- Never search arbitrary cwd, PATH, downloads, system library directories, or an
  unpinned bundled Linux binary. Never fetch a native binary at runtime.
- Verify ELF class, machine and platform before JNA load. Surface missing transitive
  libraries and unresolved bindings as distinct diagnostics with the selected path,
  host architecture and cause. No loader error may initiate attachment.
- Avoid eager static initialization: normal application startup on a machine without
  the Linux artifact remains possible when this capability is not requested.
- No secrets, process command lines or account identity in diagnostic/evidence output.

Names are proposed additions, not assertions that these classes already exist.
Review native ABI widths, calling convention and bool/pointer mappings against the
pinned headers (especially long/size_t across Windows/Linux); do not blindly copy
Java mappings. Restrict this story to the binding seam needed for load verification.

## Implementation sequence and files

### 1. Import/provenance
- [ ] Inventory the pinned native tree and recursively required CMake source/include
      dependencies. Carry source, headers, build definitions, licenses and notices;
      exclude IDE state, caches, build.zip and unneeded precompiled artifacts.
- [ ] Add third-party/RemoteInput with a provenance manifest listing source repository,
      commit, imported paths/tree identity, license files and every local patch.
      Check attribution/license completeness before distribution.
- [ ] Keep third_party/KInput and Windows artifacts untouched.
      Do not conflate the upstream third-party and fork third_party directories.

### 2. Reproducible native build
- [ ] Add scripts/native/build-remoteinput-linux.sh with strict error handling, explicit
      source/output roots, bounded parallelism and native Fedora x86_64 preflight.
      Resolve paths from the script/repository location, including paths with spaces.
- [ ] Preflight C/C++ compiler with C++20, CMake >= 3.15, make or selected generator,
      JDK17/JNI headers, Python >= 3.8 development headers, GL and X11 headers/libs,
      and binutils/file/hash tools. Record installed versions and JAVA_HOME.
      Derive and verify Fedora package names on the implementation host; do not
      treat upstream's four-package example as a complete dependency manifest.
- [ ] Use an isolated build/native/remoteinput/linux-x86_64 directory. Configure
      Release and an explicit JDK; keep upstream Python behavior initially unless
      clean-build evidence justifies a documented local patch.
- [ ] Build only the needed shared target. Record exact configure/build commands.
      Fail if any required vendored dependency is absent; no floating downloads.
- [ ] Generate manifest: source commit and local patch identity, compiler/CMake/JDK/
      Python versions, options, output path/size/SHA256, ELF architecture, DT_NEEDED
      libraries, and sorted required/exported symbols.
      Reproducible procedure is required; do not promise bit-identical binaries
      across different toolchains. Record actual repeat-build hash results.

### 3. Gradle and distribution
- [ ] Add explicit buildRemoteInputLinux, stageRemoteInputLinux and
      verifyRemoteInputLinux task contracts (final names documented if changed).
      Ordinary unit tests/tasks must not require a native compiler.
- [ ] Stage only a validated successful artifact and its manifest; exclude intermediate
      objects and unreviewed binaries from git. Failed builds must not masquerade as
      a new valid artifact by reusing stale output.
- [ ] Add a documented Linux distribution/staging task including jar, sidecar native
      directory and launcher. Supply explicit application root for bootRun and launch.
- [ ] Preserve Java17, replayTest, replayCertificationTest,
      replayStressCertificationTest, cleanChromascape, cleanAll, copyNativeLibraries,
      and check dependency on spotlessCheck/checkstyleMain. Retain Windows-only
      certification's fail-closed behavior; no silent success on Linux.
- [ ] Preserve existing Windows processResources/build/jar native copy behavior.

### 4. Load seam and diagnostics
- [ ] Add resolver/loader and scoped unit tests using injected platform/filesystem/
      native-loading boundaries; avoid requiring a real .so for ordinary unit tests.
- [ ] Add subprocess native load smoke entrypoint that loads the artifact and resolves
      required symbols only. Inspect native load constructors before running to verify
      their effects; no target enumeration/injection/control call is part of this test.
- [ ] Report MISSING_LIBRARY, UNSUPPORTED_PLATFORM_OR_ARCH, INVALID_ARTIFACT,
      LOAD_FAILED and MISSING_SYMBOL with actionable distinctions.
      Preserve the original native error as cause; no fallback attachment.

## Acceptance and test specification

1. Given a clean Fedora x86_64 toolchain and pinned sources, when the documented
   build runs, then a valid shared library and complete provenance manifest exist.
2. Given launch from the project, a directory with spaces, and an unrelated cwd,
   when development or packaged verification runs, then each loads the same
   intended canonical path and SHA without contacting or attaching to a client.
3. Given both overrides, when resolving, then the system property wins; given an
   invalid explicit override, then resolution fails closed rather than falling back.
4. Given absent file, directory instead of file, unreadable file, corrupt/non-ELF,
   wrong ELF class/machine, missing dependency or missing required export, then
   the corresponding diagnostic is returned and zero attach calls occur.
5. Given no Linux artifact and capability not requested, when ordinary unit tests or
   application startup execute, then missing optional native capability alone does
   not crash them.
6. Given existing Windows packaging, when build/copy tasks run on Windows with the
   known DLL fixtures, then the existing DLL names/placement and backend survive.
7. Given the fork build, when task enumeration and targeted checks run, then all
   protected tasks/wiring survive and changed-code tests pass.

Test execution checklist (all unrun at the time of writing; see `verification-evidence.md` for the
current, executed status):
- [ ] Resolver precedence/path/platform tests, loader failure tests and no-attach tests.
- [ ] Clean Fedora configure/build, ELF/dependency/export inspection and repeat build.
- [ ] Actual development and packaged subprocess load tests from unrelated cwd.
- [ ] Injected loader error tests plus real native negative fixtures in an isolated test
      directory. Do not patch system libraries or alter host-wide library search.
- [ ] Java compile/compileTestJava, tasks, focused native tests and six launcher suites.
- [ ] Spotless/checkstyle changed files plus baseline comparison; avoid unrelated format churn.
- [ ] replayTest and ordinary test results compared by failure identity, not only totals.
- [ ] Native Windows packaging regression on a Windows host; missing host means
      unverified, never PASS. Required acceptance must be resolved before story closure.
- [ ] Review logs/manifests for private data and retain commands, UTC times, exits,
      counts, source revisions and checksums under docs/upstream-integration/sc-1662/evidence.

Existing baseline limitations: 110 ordinary and 12 replay failures, 13 ordinary skips,
and 16 Spotless files. Focused baseline is 175 passing tests. Do not treat those
historical results as execution of this new implementation or blanket waivers.

## Delivery, estimate and rollback

Plan commits: (1) provenance/native import, (2) build/stage/manifest,
(3) resolver/bindings/diagnostics, (4) smoke/regression evidence and documentation.
Open a dedicated PR against integration/chromascape-consolidated-20260901.
Do not include later Linux stories. Recheck target movement before starting coding.

Provisional effort: 2–4 focused engineering days for this story, plus host/review
availability; revise after the first clean native build. Main uncertainty is compatibility
of the pinned C++/Python/JNI tree with the Fedora toolchain, then packaged loading.
This is not an estimate for the whole Linux integration epic.

Rollback by reverting these isolated commits and removing only generated SC-1662
staging artifacts. Windows KInput remains intact. No DB migration or server API
change is planned. User review and actual acceptance evidence are required to finish.
Resolve Felix canonical project registration and persist scope, this spec, acceptance,
implementation/test checklists and rule links (AGENTS.md and compatibility map);
satisfy genuine gates without waivers. At the time of writing, the user asked to plan the
next task and not implement it in that turn (historical — see the frozen-record notice above).
