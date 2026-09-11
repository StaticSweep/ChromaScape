# SC-810 evidence index

This directory accumulated evidence under two separate, non-overlapping numbering schemes used
at different points in this engagement. Files are not renamed once referenced elsewhere (git
commits, Shortcut comments), so this index exists to keep them navigable rather than renaming
in place.

## Original delivery-verification plan (Phase 8 → 9 → 10, 2026-09-10)

Established early in this engagement; already cross-referenced by these exact filenames in git
commit messages and Shortcut comments (do not rename):

- `phase-8-evidence.md` — packaged artifact and signed Fishing bundle.
- `phase-9-evidence.md` — live verification against the running debugger API.
- `phase-10-running-artifact-gate.md` — running-artifact attestation gate investigation, fix,
  and proof (finding 1 from the accuracy review).

## "SC-810 remaining implementation and closure plan" (Phase 1 → 20, 2026-09-11)

A later, separate, larger plan given afterward, with its own independent Phase 1-20 numbering.
Evidenced in files prefixed `closure-plan-phaseN-...`:

- Phase 1 (freeze/record baseline): verified inline in conversation, no separate file.
- Phase 2 (packaged-JAR catalog-discovery blocker): `closure-plan-phase2-catalog-discovery-fix.md`
- Phases 3, 5, 6, 7, 8 (integration onto `sc-572-remediation`, pre-package verification, final
  artifact, final signed bundle, packaged-runtime readiness):
  `closure-plan-phase3-5-6-7-8-integration-and-final-artifact.md`
- Phase 4 (trust environment decision): folded into the Phase 3/5-8 file above
  (`local-development`, not `production`).
- Phase 9 (account-record migration): **on hold**, not attempted, no file.
- Phase 10 (LeeuwBot connectivity re-check): `closure-plan-phase10-leeuwbot-connectivity-check.md`
- Phases 11+ (start ChromaScape acceptance, record a genuine Fishing run, atomic binding, live
  debugger content, negative matrix, restart persistence, cross-revision protection,
  documentation/Shortcut/Felix reconciliation, final completion gate): not reached — gated on
  Phase 9 (on hold) and Phase 10 not being fully satisfied (launcher API reachable, but no
  machine reports online/dispatch-ready).
