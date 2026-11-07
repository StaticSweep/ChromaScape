# SC-274 narrowed acceptance — draft for owner approval (3 November 2026)

Status: **DRAFT. Not yet posted to Shortcut.** Under rule 4, Shortcut changes need your approval.
The text that would be replaced is saved verbatim at
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\acceptance\sc274-description-before-narrowing-20261103.md`.

How to apply: **append** section A to the SC-274 description. Every existing section stays as
history. Create the follow-up story in section B and link it to SC-274 ("SC-274 relates to it";
"SC-2201 blocks it"; "SC-1498 blocks it").

---

## A. Text to append to SC-274

```markdown
## Narrowed acceptance (owner decision, 2026-11-03)

The v2 compiler requires a registered causal xRSPS capability before it produces any manifest,
and no qualified runtime exists yet (SC-2201 is To Do). The genuine-manifest criteria therefore
cannot be demonstrated in this story. By owner decision this story closes on the criteria below;
the remaining criteria move unchanged to SC-<follow-up>.

### Retained here (Done requires all of them)

| # | Criterion | Evidence |
|---|---|---|
| 3 | Off-by-one, tick gap, duplicate/reordered action, missing state and incompatible cache/collision revision fail with one stable source-linked code each, byte-identical across two fresh JVMs | closed 2026-11-02 (`wp1/refusal-a.json`, `wp1/refusal-b.json`) |
| 5 | Incomplete scenarios stay playback-usable; causal simulation refused with a stable blocker; zero writes, including across a service restart; also shown on a genuine finalized recording | boundary closed 2026-11-02; genuine half: `recording_af17d0dc` plus the new capture |
| Travel (negative) | Missing bank state, charges, widget semantics, spawn state, region/collision identity or actions fail with stable source-linked reasons; multi-region, plane-change, duplicate/reordered and ambiguous-widget fixtures; coverage reported separately from completeness | closed 2026-11-02 (`wp1/coverage.md` §1.5) |
| Historical | `recording_b83c37e5` re-audited with pinned decoders; precise blocked verdict retained | closed 2026-11-02 (`historical/b83c-verdict.md`) |
| Capture | A fresh genuine HUMAN Fishing recording with exact run context including `graphRevision`, per-tick world/region/plane and Fishing-spot `spotId`; contiguous ticks, complete media, observed catch and XP | pending |
| Output | The recording-side evidence for every Output element is present with provenance, or listed as a named blocker; no raw click, packet or WebSocket dependency | pending |

### Moved to SC-<follow-up> (unchanged wording)

Criteria 1, 2, 4, 6 and 7 on genuine input; the live-bootstrap alignment (SC-1498 provider-origin
recording); the travel amendment's positive half (bank-to-travel-to-Fishing compile); the hidden-state
(timers, varps, RNG, quest) and Mule/coordination-interval contract gaps (G1, G4).
Fixture-level evidence for 1, 2, 6 and 7 already exists and moves with them as supporting, not
closing, evidence.
```

---

## B. Follow-up story to create

**Title:** `REPLAY-2b: Genuine-input ScenarioManifest acceptance after causal runtime (SC-274 follow-up)`

**Description:**

```markdown
Carries the SC-274 acceptance that needs a qualified causal xRSPS runtime or the SC-1498 approval.
Original wording is in SC-274; nothing here is relaxed.

Blocked by SC-2201 (registered, qualified causal runtime) and SC-1498 (one-use human approval of
provider, account, task, budgets and kill switch).

## Acceptance
1. Same immutable genuine inputs and compiler version produce a byte-identical manifest and checksum in two independent clean clones (fresh JVMs, no build cache).
2. Every manifest field and action of that genuine manifest links to source evidence or an audited fixture, with explicit provenance; the live-world → simulator-world mapping is resolved or a named blocker.
4. A bounded genuine Fishing recording compiles into a scenario that runs causally with no raw input dependency.
6. Train/validation/held-out role, ownership and redaction hold for the genuine package across owner/foreign/missing principals.
7. The genuine package round-trips byte-identically through the import API; malformed, oversized, over-deep, code-carrying and network-carrying variants are refused with zero writes. Run on a clean reviewed build (not the mixed SC-274 candidate).
- Alignment: SC-1498's provider-origin recording compiles with lineage kept; simulator outputs use distinct identities.
- Travel positive: a bank-to-travel-to-Fishing genuine capture compiles deterministically with exact source mapping on an SC-276-capable world.
- Contract: hidden state (timers, varps, RNG, quest) yields `SCENARIO_INCOMPLETE` with named reasons; Mule/coordination intervals are excluded by a defined rule.

## Starting evidence
SC-274 evidence root `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\`, the genuine
HELD_OUT capture produced by SC-274, and the fixture-level tests for rows 1, 2, 6 and 7.
```

---

## C. After approval

1. Append section A to SC-274 (fill in the follow-up ID).
2. Create the section B story; add the links.
3. Record both IDs in the Felix plan note and in `acceptance-ledger-20261029.md`.
4. SC-274 then closes when the two pending rows (Capture, Output) have evidence and the code is merged.
