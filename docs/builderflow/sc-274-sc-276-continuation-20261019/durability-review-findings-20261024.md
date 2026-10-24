# Durability slice review - 24 October 2026

Reviewed local ChromaScape HEAD f66b9b3b3506a92426c4d232f0b6dfef9ff9edab.
Product implementation was not modified, committed or pushed during this review.

## Verified delivered evidence

- Retained artifact XML totals 68 cases: 64 pass, zero failures/errors, four POSIX skips.
- Retained XrspsSessionRepositoryIntegrationTest XML: 3/3 pass.
- Native commit 79382be38272da3d36f627c9932384692dd27873 contains 105 changed files.
- Git bundle verification succeeds and records a complete history and the intended branch.
  SHA-256: E0365BC5C02D5B54C1E8955B1F20E97A097C3D85B5EC4DB86BFC926C34E61494.
- Product store source matches the existing e44d6506 verification checkout. Review probes
  used its compiled main classes, Corretto JDK 17 and the package-private force seam.

## New findings

### P1: STRICT/DEVELOPMENT admission is not serialized

ImmutableArtifactStore.initializeDurability (line 248) reads the marker and writeMarker
replaces it without using the root's JVM mutex or OS lock. Two initializers can both see
no marker, admit different modes and overwrite the marker. Their cached qualification
flags remain different, and later locked operations do not revalidate the marker.

A barrier at the injected admission force reproduced both constructors succeeding, followed
by DEVELOPMENT publication and a successful read through the qualified STRICT instance.
Initialization can also overlap locked pending-file cleanup because it uses the same pending
directory outside that lock; that additional race was not separately reproduced.

Required fix: serialize admission and marker persistence across threads/processes with the
root's locking protocol; publish the marker without a conflicting mode overwrite. Add
concurrent same-mode/opposite-mode initialization and initialization-versus-operation tests.

### P1: missing/null marker silently upgrades populated development content

marker (line 315) returns null for both absence and the valid JSON literal null.
initializeDurability then bootstraps STRICT without checking existing content history.
The fresh probe published a DEVELOPMENT artifact, removed the marker (and separately wrote
JSON null), reopened STRICT and successfully read that artifact as a qualified store.

This contradicts the blanket claim that development output can never become production
evidence. Re-probing directory support does not establish prior publication provenance or
the required persistence sequence for all existing content.

Required fix: reject malformed/null/inconsistent markers; distinguish an empty new root
from a populated unmarked root. Require an explicit reviewed migration/requalification path
for legacy data rather than silently promoting it. Retain forensic/recovery content.

### P1: recursive directory creation misses parent persistence

initializeDurability creates the root hierarchy using Files.createDirectories and forces
the root, not its newly created entry in its parent. directory (line 629) also creates
multiple missing levels but forces only the final directory's immediate parent.

For a fresh nested root, the recorded calls never forced the root's parent. After clearing
the initialization trace and publishing the first artifact, the calls were objects,
objects/<prefix>, refs and refs/<owner>; the root was not forced after creation of its
objects/refs entries. Current count/substring-based force tests do not assert this ordering.

Required fix: create/track each new directory level and force every changed parent in a
defined order, including root admission and pending setup. Specify an existing trusted
boundary and add exact path/order plus force-failure regressions. Do not infer full POSIX
qualification merely because some force calls occurred.

## Probe evidence and limits

`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\durability-review-20261024T154458Z`:
DurabilityReviewProbe.java, argument files, compilation/runtime logs and probe-results.json.

Results: deletedPromoted=true; json-nullPromoted=true;
newRootParentForced=false; rootForcedAfterObjectsAndRefsCreation=false;
concurrentModesAdmittedAndDevelopmentReadByStrict=true.

The directory-force callback is deliberately injected/no-op. These are fresh admission-policy
and call-order reproductions, not actual POSIX fsync or power-loss tests. The first probe
attempt had a review-harness barrier bug and timed out when a later main-thread force
re-entered it. Its logs are preserved; the corrected probe limits the barrier to admission.
No real artifact root or shared service/database was used or changed.

## Remaining sequence

1. Fix the three reproduced findings and add focused regression coverage.
2. Run the four positive STRICT cases on an owned Linux filesystem and review full parent
   persistence/fault behavior. Windows refusal plus injected tests are supporting evidence only.
3. Resolve the native authoritative qualification gate without bypassing hard-coded false
   eligibility. Ten worker-gated cases remain blocked; four need client/provider inputs.
4. Finish advertised-method contracts (including Fishing/CAST_SPELL), reproducible Linux
   packaging and actual OS/resource/cancellation/performance qualification.
5. Obtain migration authority, durable clock/renewal ownership, supported allocation,
   original-trust/genuine inputs, paired Watch/human sign-off and authorized final delivery.

Native recovery ownership and database evidence are valid progress. The 27-path compiler
history boundary and repository-wide formatting debt remain recorded. All original acceptance
rows stay open. No WSL start/shutdown, remote push or Felix/Shortcut operation occurred.

## Resolution - 24 October 2026 (post-review implementation)

All three findings are fixed in ImmutableArtifactStore with regression tests:

- Admission and marker persistence run under the root JVM mutex and OS lock; the marker is
  published by hard link (never overwritten); every locked operation revalidates the marker.
- Absent marker on a populated root, JSON null, malformed and inconsistent markers are refused.
  Legacy roots can only be adopted through adoptLegacyRootAsDevelopment(reviewReference) as an
  unqualified DEVELOPMENT root; in-place promotion to STRICT is impossible.
- Directories are created level by level; the parent of every new entry is forced in order,
  including the root's parent. The nearest existing ancestor is the trusted boundary and is
  probed before STRICT creates anything.
- Additional defect found by the first Linux run: a failed directory force left the manifest
  link discoverable and a retry returned it without forcing. Manifest publication now retracts
  its link on force failure and the existing-reference path re-forces the directory.
- A pre-existing test expected a snapshot to stay active after its dependency was quarantined;
  corrected.

Evidence: Windows Gradle run - ImmutableArtifactStoreTest 31 (0 fail, 4 POSIX skips),
ArtifactRegistryControllerTest 4, XrspsSessionRepositoryIntegrationTest 3/3 against a
disposable postgres:16-alpine (removed afterwards). Linux run in eclipse-temurin:17-jdk on a
container overlay (ext4-backed) filesystem - 33 executed, 0 failed, 2 Windows-only skipped, so
the four POSIX positives and the two-JVM admission race now run and pass. Force callbacks in
the call-order tests are recording stubs; the Linux positives use the real primitive, but no
power-loss test was performed. spotlessJavaCheck still fails on 1,363 pre-existing files.
