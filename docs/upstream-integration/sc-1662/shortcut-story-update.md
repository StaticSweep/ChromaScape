# SC-1662 — Shortcut story update

Paste the section below into SC-1662. Replace the PR placeholder after the PR exists and update the
review result after review completes.

---

**Status: IMPLEMENTATION COMPLETE — REVIEW REQUIRED.**

Implemented the Linux RemoteInput native build and load proof of concept on
`codex/sc-1662-linux-remoteinput`. The story implementation is complete on the accepted PoC scope
and is ready for code review.

Scope delivered:

- Imported and cryptographically inventoried the pinned 278-file native source tree from
  `StaticSweep/ChromaScape@5ea3b995f5d13e19c592a6399973fe7dadb5e656`; current verification is
  278/278 with no changed, added, removed or unexpected paths.
- Added a fail-closed Fedora x86-64 build with toolchain/preflight checks, manifest generation,
  ELF/dependency/export validation and repeat-build comparison.
- Built in a rootless Fedora 44 Podman toolchain container. The repeat build was byte-identical;
  `libRemoteInput.so` is 836,336 bytes with SHA-256
  `114bf72d2dc6d5b7000d24026188665cf56de7a069be947661a6c3786f3d0787`.
- Added manifest-before-load resolution, explicit diagnostics, required-symbol verification and a
  bounded development smoke verifier. The real artifact produced `SMOKE_OK` from the application
  root, an unrelated working directory and a copied path containing spaces, resolving all 23
  required exports without target selection, injection, pairing or input.
- Added indivisible content-addressed publication of the library/manifest pair. Both Kotlin and
  shell staging implementations cover pair identity, corrupt/replaced payload refusal, copied-pair
  refusal, exact predecessor retention and safe refusal of the legacy real-directory layout.
- Preserved the Windows KInput implementation and Gradle wiring.
- RemoteInput is deliberately excluded from packaged distributions for this PoC. Assembly removes
  stale native/RemoteInput payloads and produces only `application.jar`, `launcher` and
  `licenses/ChromaScape-LICENSE`. Local development can still stage and load the native pair.
- The upstream tree has no top-level licence. This is accepted for the private PoC; provenance stays
  `unresolved`, the optional distribution licence gate stays fail-closed, and no licence text was
  invented.

Verification:

- SC-1662 focused Java tests: 47/47 passed.
- Metadata/provenance helper tests: 47/47 passed.
- Licence matrix: 9/9 states passed.
- Kotlin and shell staging regression harnesses: passed.
- Distribution-exclusion regression: 12/12 passed; the launcher was syntax/executable checked and
  never launched.
- Ordinary suite: 2,302 tests, 109 failures, 13 skipped; no new failure identity versus the SC-1661
  environmental baseline (one fewer failure identity).
- Replay suite: 280 tests, 12 failures; the same 12 Docker-environmental failure identities as the
  SC-1661 baseline.
- `checkstyleMain`: passed. `spotlessCheck`: the same 16 pre-existing offender files, with no
  SC-1662 file involved.
- All retained run-6 JUnit XML digests verify. Full commands, exits, identities and SHA-256 values
  are under `docs/upstream-integration/sc-1662/evidence/`.

Review and follow-up status:

- Windows verification: **NOT RUN; explicitly waived as a blocking criterion for this PoC.** The
  retained `docs/upstream-integration/sc-1662/windows-verification-runbook.md` can be executed later.
  It must remain NOT RUN, rather than PASS, until that execution occurs.
- Review: **REQUIRED**
- PR: `<insert PR URL>`
- Merge: **PENDING REVIEW**

The real input-call ABI, process discovery/selection, injection, pairing and lifecycle remain in
SC-1663+ scope. This story proves reproducible native production, deterministic staging, manifest-
before-load validation and non-attaching development load/export verification.

---

Mark the Shortcut implementation complete and ready for review. After review and merge, replace the
PR placeholder with the merged link and record the review outcome. Windows remains a non-blocking
follow-up unless it is subsequently executed.
