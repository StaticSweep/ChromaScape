# Fedora RemoteInput attachment completion — implementation plan

> **FROZEN PLAN.** Implementation started on this revision. The plan below is the agreed scope and
> acceptance contract; changes require an explicit amendment in this document rather than silent
> drift.

## 1. Story

**Title:** Fedora: complete RemoteInput native attachment and safe session lifecycle qualification

**Goal:** Make ChromaScape attach to a real RuneLite/Micro6 client on Fedora, capture frames, perform
bounded input, and release the session without leaving input held, native work running, or an
unrecoverable attachment lease.

**Current state at freeze:**

| Capability | Status |
|---|---|
| Fedora startup | PASS |
| Loopback binding and `/debugger` | PASS |
| Process discovery and selection | PASS |
| Native artifact validation | PASS |
| Native library injection | library mapped into the target |
| RemoteInput client registration | FAIL — `INJECTION_TIMEOUT` |
| Capture / input / recording / release | NOT RUN |
| Focused Java tests | PASS (pre-change baseline) |
| Native metadata tests | PASS |
| Fedora shell regressions | PASS |

The observed failure proves that increasing the Java timeout alone does not attach: the injected
library is mapped but no `RemoteInput_<pid>` ControlCenter rendezvous ever appears. The exact native
stage that fails is the subject of Phases 5–7 and is not assumed.

## 2. Scope

### In scope

- Correct session close and poisoned-session behavior.
- Enforce one total attachment deadline across every native enumeration call.
- Make the Fedora qualification profile reproducible.
- Diagnose the injected native library initialization stages.
- Apply the smallest evidence-backed Linux native patch.
- Rebuild and stage the native artifact/manifest pair.
- Qualify attachment, capture, bounded input, release, repeat attachment, target restart, resizing,
  and multiple candidates.
- Regenerate committed evidence against the final revision.
- Push a review-required PR.

### Out of scope

- Fedora RPM or tar packaging.
- Shipping RemoteInput in the normal distribution.
- Windows native behavior changes.
- Unrelated Checkstyle or Spotless cleanup.
- Broad RemoteInput refactoring.
- Disabling SELinux, ptrace protections, seccomp, or firewall protections.
- Treating an unexecuted test as PASS.

Packaging is the next story and starts only after this one merges.

## 3. Mandatory safety invariants

1. ChromaScape continues to bind to `127.0.0.1` by default.
2. The qualification controller stays development-only, disabled by default, loopback-only, limited
   to one selected target, and bounded in coordinates, key duration, request size, and timeout.
3. A timed-out native call poisons its executor permanently.
4. A poisoned session never reports `"closed"`.
5. A poisoned target lease is not released while native work might still execute.
6. Reopening a poisoned identity requires a ChromaScape restart.
7. Every native call observes the same monotonic attachment deadline.
8. No global or system security setting is changed automatically.
9. No compiled `.so` is committed.
10. Any vendored native modification is documented in provenance.

## 4. Pathing

Repository paths are written with the canonical Windows root
`C:\Dev\IdeaProjects\ChromaScape-fedora-qualification`; Fedora system objects keep their native
POSIX paths (`/proc`, `/dev/shm`, `/sys/fs/selinux`, `/var/log/audit`). Documentation, evidence,
commit messages, and Shortcut never publish machine-specific mount paths or usernames.

## 5. Baseline freeze

| Fact | Value |
|---|---|
| Branch | `codex/fedora-live-qualification` |
| Frozen head | `31d9546632c3fd124aa1339d702bbbe48db36826` |
| Integration merge base | `1f1b6b5da792e6d611b648fc11219d893b5dcd7c` |
| Commits on branch | 9 |
| Upstream source pin | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` |
| Native library SHA-256 | `114bf72d2dc6d5b7000d24026188665cf56de7a069be947661a6c3786f3d0787` |
| Manifest SHA-256 | `e95550c7d0d9ab86b2ca302fae9276b67a84a90e15e602db83bf68a3cd28c34b` |
| Pair identity | `aa39b9759126963dd3471d4ec61e2c112a1edb7e169b2990d0dedd539df084d8` |
| Native toolchain image | `localhost/sc1662-fedora-toolchain:latest` (image ID `cabd7e24e0ca`, digest `sha256:15179d9fbd427cc73c799f4710631fdb37c2183dbedfe53c5a24cc758ea72af4`) |
| Fedora host | Fedora Linux 44, kernel `6.19.10-300.fc44.x86_64`, x86-64 |
| Desktop session | GNOME / Wayland (`DISPLAY=:0` via XWayland) |
| Target JVM | `openjdk 17.0.20.1` (Corretto) |
| Target client | Micro6 `microbot-1.8.9.3.jar`, `net.runelite.client.RuneLite`, RuneLite `1.11.8-SNAPSHOT` |

The nine pre-existing commits, the untracked evidence inventory, and the 60-second/223-poll failure
are preserved as the pre-fix baseline. Existing untracked evidence is explicitly **not** final.

## 6. Phase summary and status

| Phase | Deliverable | Status |
|---|---|---|
| 1 | This frozen plan | DONE |
| 2 | Poisoned-session close semantics + tests | DONE |
| 3 | Single total attachment deadline + tests | DONE |
| 4 | Reproducible Fedora qualification profile + shell regressions | DONE |
| 5 | Bounded read-only native diagnostic collector + regressions | DONE |
| 6 | Identify the exact native failure stage | IN PROGRESS |
| 7 | Smallest evidence-backed Linux native patch | PENDING |
| 8 | Rebuild/verify/publish the native pair | PENDING |
| 9 | Live Fedora qualification matrix | PENDING |
| 10 | Automated verification and regenerated evidence | PENDING |

### Phase 2 — poisoned release and close

- A held-input release timeout or `EIOS_ReleaseTarget` timeout poisons the lane, **retains** the
  attachment lease, and throws the original `RELEASE_TIMEOUT`.
- `close()` on an already-poisoned session throws `SESSION_POISONED`; a normal close stays
  idempotent.
- The controller returns HTTP 200 only after a successful native release, preserves a failed
  session reference, and reports `SESSION_POISONED` with `restartRequired=true` and
  `sessionOpen=false`.
- `/session/open` refuses to discard a poisoned session and never attempts a fresh open.

### Phase 3 — one total attachment deadline

- A single monotonic deadline is created at attachment start.
- The initial `EIOS_GetClients`, every `EIOS_GetClientPID`, `EIOS_Inject_PID`, the post-injection
  poll, every later poll, and poll sleep intervals all observe the remaining budget.
- No call receives a fresh full timeout beyond the deadline, and a large `maxClients` cannot expand
  the wall-clock budget.
- A small minimum call budget prevents a sub-scheduling-granularity remainder from spuriously
  poisoning the lane.

### Phase 4 — reproducible qualification profile

- `SPRING_PROFILES_ACTIVE` defaults to `local-development` only when unset; an operator value is
  never overwritten.
- `CHROMASCAPE_QUALIFICATION_ENABLED` defaults to `false`; `CHROMASCAPE_QUALIFICATION_CAPTURE_DIR`
  defaults to `.chromascape/qualification/captures`.
- Duplicate profile/qualification keys fail closed; production profiles never activate the harness.

### Phase 5 — bounded native diagnostic collector

- `scripts/native/diagnose-remoteinput-linux.sh` is read-only and bounded, validating pid ownership,
  ELF x86-64 library identity, and a narrow explicit output directory.
- It collects host/target environment (DISPLAY only), filtered `/proc/<pid>/maps`, X11 window
  inventory, `/dev/shm` RemoteInput objects, SELinux mode and bounded AVCs, library `ldd`/`readelf`,
  a redacted attachment timeline, and a machine-readable stage summary.
- It never dumps the full environment, memory, or screenshots, and redacts usernames and repository
  paths from every retained file.

### Phase 6 — identify the failing native stage

The collector's stage summary and the curated logs must show the first failing stage before any
patch is written. Candidate stages follow `Plugin.cxx` (`__load` → detached thread →
`GetNativeReflector`), `Platform_Linux.cxx` (`JVM` discovery → `libawt_xawt.so` → `awt_GetComponent`
→ `_NET_CLIENT_LIST`/window class → `Reflection::Create`), and `ControlCenter.cxx` (map + signals).
No patch is applied speculatively.

### Phase 7 — smallest evidence-backed native patch

Once the stage is proven: change only the Linux path, preserve Windows behavior, keep exact process
identity binding, make window selection deterministic, bound retries with a monotonic deadline, avoid
unsafe loader-lock logging, surface constructor-thread exceptions as diagnostic stages, add a
pure-helper regression, and record the patch in RemoteInput provenance.

### Phase 8 — native pair rebuild and atomic publish

Rebuild in the pinned Fedora toolchain container, verify ELF identity/exports/dependencies, verify
manifest/library agreement and pair identity, publish atomically through the existing staging task,
retain the previous target, and commit no `.so`.

### Phase 9 — live Fedora qualification matrix

Startup, discovery/selection, attachment, capture, bounded input, release, repeated attachment,
target restart, resize/minimize, and multiple clients — each with honest PASS/FAIL/NOT RUN evidence.

### Phase 10 — automated verification

Focused tests, ordinary suite, replay suite, Checkstyle/Spotless, all shell regression suites,
`bash -n` on every changed script, `git diff --check`, privacy/redaction scan, and tracked-native
scan. Pre-existing failures may remain only with recorded identities; this story introduces none.

## 7. Definition of done

The story is **Completed — Review Required** only when every Phase 9 acceptance criterion has honest
evidence against the final revision and the branch is pushed with a review-required PR. There is no
waiver for attachment, capture, input, or release.
