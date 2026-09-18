# SC-1668 cross-platform qualification and integration delivery

Status: implementation record. A host result is PASS only when retained evidence proves it; an
unavailable host or client remains NOT RUN and blocks the strict three-host completion gate.

Base: integration merge `ab8c4ccf43e09ce5d52b5e75a712e672984d1561`.

Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`.

## Objective

Close the final two code adaptations assigned by the SC-1661 ledger, provide one fail-closed evidence
format for two Fedora hosts and one Windows host, reconcile every upstream-only commit, and prepare
the final integration review without turning unavailable hardware into an inferred pass.

## Invariants

1. Every host qualifies the same full application revision.
2. The two Fedora records use distinct host identifiers.
3. Every required runtime case carries PASS, FAIL, or NOT_RUN plus a concrete evidence reference.
4. Strict completion accepts PASS only; FAIL and NOT_RUN both keep the matrix incomplete.
5. Fedora native evidence names the exact RemoteInput artifact SHA-256 and pinned source revision.
6. No script changes ptrace, seccomp, SELinux, namespaces, firewall, sandbox, or machine policy.
7. Selection, input, capture, and recording stay bound to one host/process/session identity. A
   restart or selection change must release that ownership before another attachment.
8. Rollback is a Git revert plus the existing owned lifecycle stop procedure; no database migration
   or distributed protocol change is introduced by this story.

## Implementation

### Remaining upstream adaptations

- AWT mode is selected before Spring starts. An explicit `java.awt.headless` value wins. Linux with
  `DISPLAY` or `WAYLAND_DISPLAY` is non-headless; Linux without a display is deterministically
  headless. HTTP page requests no longer mutate the JVM-global AWT mode.
- `BaseScript.onFirstCycle()` runs once after controller/hotkey initialization and immediately before
  the first normal cycle. A failed hook does not run the cycle or mark initialization complete.
- The retained host-native RuneLite profile path is `~/.runelite/profiles2` on Fedora and Windows.
  The incompatible Flatpak/Bolt path from upstream is intentionally not adopted.

### Evidence contract

`scripts/qualification/sc1668_evidence.py` provides:

- `init`: atomically creates a host record with every check set to NOT_RUN;
- `version`: records exact OS, architecture, Java, client, and native-artifact identity;
- `record`: records one observed result and its retained evidence reference;
- `verify`: checks schema, roles, revisions, distinct Fedora identities, and check coverage;
- `verify --require-complete`: additionally requires every result to be PASS.

Writes use a same-directory temporary file, `fsync`, and atomic replacement. Existing records are
not overwritten by `init` without explicit `--force`.

## Required matrix

Each Fedora host proves build, discovery, target selection, attachment, bounded input, capture,
recording, debugger, multi-client isolation, remote launch, resize/minimize, target restart,
repeated attachment, and release. Windows proves build, discovery, selection, input, capture,
recording, debugger, replay, remote launch, and release.

The operator procedure and exact recording commands are in `qualification-runbook.md`. The final
commit decisions are in `final-upstream-reconciliation.md`; rollback is in `rollback.md`.

## Verification layers

1. Focused Java tests for AWT startup and first-cycle ordering.
2. Eight Python evidence-contract regressions, also exposed as Gradle task `sc1668EvidenceTests`.
3. Existing native metadata and Fedora preflight fixture regressions.
4. Focused SC-1662–SC-1667 Java suites, launcher/recording/debugger suites, ordinary `test`, and
   `replayTest`, with inherited failures compared by identity.
5. Real-host records for Fedora primary, Fedora secondary, and Windows.

Layers 1–4 do not substitute for layer 5.
