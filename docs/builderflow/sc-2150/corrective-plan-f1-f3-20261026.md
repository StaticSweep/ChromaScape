# SC-2150 F1–F3 bounded corrective implementation (2026-10-26)

Scope: repair the review client/server contract, uncertain-write retries and prior suggestion exposure. Preserve existing SC-2150 WIP, legacy corrections/v1 digests/revisions and the complete CP25 plan. No commit, publication, held-out qualification, provider or native-runtime change.

Architecture/API: provenance-bearing corrections use corrections/v2, with validated UNKNOWN, BLIND_UNVERIFIED or DETECTOR_ASSISTED values. INDEPENDENT is refused until the authoritative server review-session/ledger protocol exists. Legacy corrections lacking provenance retain corrections/v1 byte/digest semantics. Revisions and exports retain normalized provenance; converter records unverified status and refuses all unverified labels, including legacy labels with missing provenance, for held-out qualification. No schema migration: existing revision JSON/contract columns suffice. Client exposure is monotonic per source+analysis and persisted locally when available; storage failure is fail-closed. It never certifies independence. Exact serialized pending requests retain URL/context/base/version/UUID before awaits, are retried unchanged, then newer edits receive a fresh identity. Explicit 409 recovery may discard a rejected request only after reloading the base.

Linked rules: shared AGENTS.md delivery contract, repository AGENTS.md; preserve existing changes and source privacy, no fabricated independent evidence. Existing architecture/ERD/security/observability notes and full-remaining-implementation-plan-20261025.md remain authoritative for larger session work.

Acceptance:
- Given a current UI-generated label, when POSTed to the actual backend, then it saves with validated provenance and a versioned contract; unknown values and independent claims fail. Legacy request digest remains unchanged.
- Given a committed write whose response is lost and a later edit, when retried, then the original serialized body/UUID/base/context is resent first and the later edit uses a new UUID after acknowledgement.
- Given prior suggestion exposure, when Blind is enabled or the editor is reopened, then subsequent labels stay assisted. Unexposed blind labels remain BLIND_UNVERIFIED and cannot qualify as independent.

Implementation checklist: F26I1 versioned normalized provenance + export/converter handling; F26I2 immutable pending request retries + acknowledgement; F26I3 monotonic conservative exposure and truthful UI; F26I4 regressions and evidence.
Test checklist: F26T1 contract/service regressions and real backend POST/export; F26T2 lost-response/later-edit Chromium regression; F26T3 exposure/toggle/reopen and unverified conversion regressions; F26T4 complete JS and focused Java/Python checks.

Remaining work: authoritative server sessions/exposure ledger and all untouched CP25 packages remain open; this increment cannot establish independent held-out truth.
