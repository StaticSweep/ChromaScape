# SC-274 / SC-276 narrowed acceptance — DRAFT for owner review (29 October 2026)

Status: **draft text only.** It changes no story, tracker state or acceptance until the owner reviews, edits and
adopts it (decision D1 in `remaining-implementation-plan-20261029.md`). Adopting it is a deliberate scope change by the
owner, not a test result, and must not be described as the original acceptance being met.

Evidence cited below is what the plan and acceptance ledger already record. It was not re-run for this draft and must be
re-verified on the final commit before any row is closed.

## 1. Principle

Each story is closed on the capability it actually delivers. Anything that needs an outside input, a Linux host, a
human sign-off, or that another story owns, moves to a named follow-up and stays open there. Nothing is deleted, and the
original text is preserved in the story history.

A narrowed Done means "the adapter/compiler contract is complete and tested". It does **not** mean certified,
independently verified against the real game, or production-qualified.

## 2. Proposed text for SC-274 (scenario compiler)

> **Amendment (2026-10-29, owner-approved scope).** This story is complete when the compiler contract below is met on a
> pinned, genuine input. Items listed under "Moved to follow-up" are not waived; they are tracked there.
>
> 1. **Deterministic output.** The same immutable inputs and compiler version produce a byte-identical manifest and
>    checksum, reproduced from two independent clean directories.
> 2. **Evidence linkage.** Every manifest field and semantic action links to source evidence or audited fixture provenance
>    with OBSERVED / DERIVED / OPERATOR_SUPPLIED / DEFAULTED labels.
> 3. **Stable refusal.** Off-by-one, tick-gap, duplicate/reordered-action, missing-state and incompatible-cache cases
>    fail with stable, source-linked reasons. Ambiguous target identity is rejected.
> 4. **Genuine Fishing.** One bounded Fishing recording from a genuine source (not an authored fixture) compiles into a
>    runnable scenario with no raw-input dependency.
> 5. **Incomplete scenarios.** An incomplete scenario stays usable for playback and policy replay and cannot run causal
>    simulation (`isSimulationAllowed()` / `requireSimulationAllowed()` refuse).
> 6. **Roles and privacy.** Train/validation/held-out roles and privacy constraints are preserved on authorized input.
> 7. **Bounded import.** Scenario import is size- and schema-bounded and cannot load code or network routes, shown by a
>    package/API round trip with malformed and private-input refusals.
>
> **Moved to follow-up (still open):** bank-to-travel-to-Fishing scenario (multi-region, plane change, bank/jewelry/
> exchange widget semantics, duplicate/ambiguous-widget fixtures); the `recording_b83c37e5` regression, which **stays
> recorded as blocked** until original evidence resolves it; source-trust and allocation (SC-800); migration authority.

Evidence today (from the ledger): rows 274-1, 274-3, 274-5 are PARTIAL; 274-2 and 274-6 are BLOCKED; 274-7 is NOT_RUN.
Still needed even under the narrowed text: criterion 4 needs one genuine Fishing recording and its source context;
criterion 1 needs the independent-directory rebuild; criterion 7 needs the final round trip; criterion 6 needs authorized
input. Criterion 2 needs a genuine input to be shown beyond authored fixtures.

## 3. Proposed text for SC-276 (kernel adapter)

> **Amendment (2026-10-29, owner-approved scope).** This story is complete when the headless adapter contract below is
> met. The adapter is **not** certified: capabilities stay `certifiable=false` and `controlAllowed=false` until the
> follow-ups pass, and no capability may imply xRSPS fidelity before independent differential evidence exists.
>
> 1. The SC-805 Fishing proof is a supported automated kernel test.
> 2. A run needs no open socket, listening port, browser, database or account credential.
> 3. The same manifest, snapshot and action sequence yields identical ordered traces and hashes.
> 4. Parallel runs and cancellation show no shared player/NPC/RNG/cache mutation.
> 5. Snapshot restore round-trips player, world entities, ground/resources, queues, timers, RNG and stop state.
> 6. Invalid, stale or unsupported actions fail without mutation.
> 7. The capability manifest exposes exact adapter/upstream/cache/content/schema versions, advertises only methods that
>    have passed their contract tests, and reports `controlAllowed=false`.
> 8. An incompatible xRSPS upgrade fails contract tests and preserves the last supported pin.
> 9. All simulator acceptance tests run on xRSPS with no OpenRune dependency.
>
> **Moved to follow-up (still open):** worker OS isolation and aggregate resource/network denial (SC-2201, former
> criterion 7); independent oracle references and parity for all 18 methods, and advertisement of the 10 unadvertised
> spell methods (SC-281 / SC-1427); local LIVE_XRSPS Watch mode and real-client viewer noninterference (SC-1991);
> qualification gate and final certification (SC-282); human Watch sign-off.

Evidence today (from the plan): kernel unit 355/355 and integration 24/24, 100-run determinism 100/100 and the guarded
Fishing smoke are recorded on Windows development runs; the ledger marks 276-boundary, 276-methods, 276-closure,
276-state and 276-safety PARTIAL. Re-run on the final commit before closing.

**Weakness to decide on:** the 8 advertised methods currently have authored-fixture evidence only; no independent
reference exists for any of them (ledger row 276-methods). Under this draft they may be advertised only as
"contract-tested", never as verified against the real game. If that wording is not acceptable, criterion 7 must wait for
independent references and this narrowing no longer saves much of the SC-276 work.

## 4. Follow-up stories (open, not waived)

| Moved item | Tracker home | Status |
|---|---|---|
| Linux sandbox, aggregate resources, durability | SC-2201 | exists, To Do, no owner or host (decision D4) |
| Real client, Watch mode, viewer noninterference | SC-1991 | exists, In Progress |
| Independent references, parity, 10 spell methods | SC-281 / SC-1427 | exist; confirm scope |
| Qualification gate, deployment, rollback | SC-282 | exists; confirm scope |
| Source trust and allocation | SC-800 | referenced; confirm it exists and scope |
| Bank/travel scenarios and `recording_b83c37e5` regression | new story needed | to create |
| Migration authority (V36, V47-V50, V53-V60, V71/V72) | new story needed | to create |
| Clock, grant renewal, named operators | new story needed | to create |

New stories need an owner (decision D2). Confirm the existing ones' scope before pointing work at them; this draft has not
read SC-281, SC-282, SC-800 or SC-1427.

## 5. Effects to check before adopting

- **SC-1199** is blocked by SC-274 and SC-276 and needs a genuine native Fishing capture and an end-to-end run. A
  narrowed Done on these stories does not unblock that; its dependency note should be reviewed.
- **SC-2208 (TSPS integration)** may change the pinned xRSPS code. Decide ordering (D3) before closing SC-276 so the
  evidence matches the code that ships.
- The Felix tasks and the five pending outbox records still need the replay and the gate-respecting transitions
  (`in_review` to `done`) described in the delivery contract; Felix was not reachable this session.
- Record the amendment in each story as a dated section that states its scope-change nature, keeps the original
  criteria visible, and links the follow-ups. Do not edit away the original acceptance text.
