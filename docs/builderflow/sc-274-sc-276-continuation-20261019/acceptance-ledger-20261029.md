# SC-274 / SC-276 acceptance ledger — 29 October 2026

Working ledger. **No row is closed.** Each row names its Given/When/Then, the evidence that
exists, and the exact remaining blocker. A helper test passing is not acceptance.

Fresh evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\identity-rerun-20261029T014052Z\`

Status vocabulary: `PARTIAL` (some supporting evidence, criterion unmet), `BLOCKED` (external
input absent), `NOT_RUN` (not attempted).

| Row | Given / When / Then | Existing evidence | Status | Remaining blocker |
|---|---|---|---|---|
| 274-1 deterministic compiler bytes | Given pinned genuine inputs, when compiled twice in fresh processes, then bytes and checksum match | `replay-package.test.ts` (same-host byte identity); independent-directory qualification is NOT_RUN | PARTIAL | Final genuine inputs and independent-directory rebuild; source is uncommitted |
| 274-2 provenance and curation | Given a real source/artifact/catalog/profile/run, when imported, then bindings and curation are preserved | authored provenance tests only | BLOCKED | Approved allocation/trust and genuine source bundle |
| 274-3 source-linked refusal | Given invalid or incomplete input, when imported, then a stable source-linked error is returned | refusal cases in replay/package tests | PARTIAL | Final supported service path and stable error catalog |
| 274-4 native Fishing | Given genuine input, when replayed, then catch, XP and inventory match independently | guarded-worker smoke (authored), `native-bank-travel-fishing` | PARTIAL | Independent capture and qualified runtime |
| 274-5 incomplete/legacy refusal | Given incomplete/legacy input, when replayed, then zero mutation and zero writes | forged-checkpoint refusal (dev smoke) | PARTIAL | Genuine positive path plus post-restart zero-write proof |
| 274-6 privacy and dataset role | Given authorized input, when stored, then role, immutability and privacy hold | none | BLOCKED | Authorized input and real schema |
| 274-7 inert bounded import/export | Given a package, when round-tripped, then bounds hold and malformed/private input is refused | none | NOT_RUN | Final package/API round trip |
| 274-travel | Given bank/travel/Fishing input, when replayed, then exact state, actions and receipts match | `native-bank-travel-fishing` (authored) | PARTIAL | Genuine evidence for all three methods |
| 274-historical | Given the historical recording, when reconciled, then the NOT_READY verdict and 23-vs-3 accounting are defensible | historical NOT_READY retained | PARTIAL | Immutable source IDs/ticks and event accounting reconciliation |
| 276-boundary | Given the production xRSPS, when driven behind the adapter, then a qualified adapter executes it | module/read guards, closure tests | PARTIAL | Linux sandbox and session qualification |
| 276-methods | Given the original method scope, when advertised, then all 18 rows have independent parity | 8 advertised; 10 contracted but `UNADVERTISED`; spell scope mechanism added (`spell-scope.ts`) | PARTIAL | Independent references; advertisement only after parity |
| 276-closure | Given the reviewed package, when installed, then loader/read boundaries and identity are reproducible | `replay-package.test.ts`, `runtime-closure-manifest.test.ts` | PARTIAL | Two independent clean directories; SC-2201 handoff written |
| 276-state | Given a durable run, when the process restarts, then continuation and ownership recover | durable-session tests (repository lane) | PARTIAL | Real service + client restart path |
| 276-safety | Given the production worker, when sandboxed, then OS isolation and measured budgets hold | prlimit/bwrap code; `confined-worker.test.ts` is Linux-only | PARTIAL | SC-2201 measurements; aggregate limits unproven |
| 276-live | Given an allocated opt-in session, when run, then authenticated lifecycle and current grants work | mechanism tests only | BLOCKED | SC-800 allocation, genuine capture, clock/renewal owners |
| 276-client | Given a real client/provider pair, when observed, then noninterference and pairing hold | none | BLOCKED | Actual client/provider, credentials, budget |
| 276-certification | Given all component evidence on frozen inputs, when evaluated, then qualification is QUALIFIED and human-signed | Corrected runner bundle: 2 PASSED, 7 NOT_RUN, verdict INCOMPLETE; structured evidence/status consistency checked | PARTIAL | Seven components, final input freeze, reviewed gate identity transition, Watch sign-off, final matrix |

## New evidence added this pass

- Differential authoritative test now executes and passes (identity regenerated); the stdio
  test remains skipped solely because the qualification gate is false.
- Plain-HTTP LAN trace verification works and rejects tampering (real Chromium, insecure
  origin).
- Qualification runner verifies evidence bytes; tampered/forged/missing evidence is rejected.
- Spell advertisement can no longer be generic; the ten methods remain unadvertised.
- Migration inventory complete (70 files; V36 and V53–V57 absent).
- Clock probe: Windows time service not started; coarse offset a few seconds; no owner named.

## 30 October 2026 — source committed, identity certifiable

The 16 previously uncommitted qualification/spell changes are committed as `791f7a3d` on
`main` (local; not pushed). The build identity regenerated from that clean commit is
`certifiable: true`; the two identity blockers ("unstaged tracked changes", "untracked
`client/replay/vendor`") no longer appear. Re-run on the committed tree: 59/59 tests passed,
zero skips; server and focused test/script typechecks exit 0.

The qualification runner still returns **2 PASSED, 7 NOT_RUN → INCOMPLETE**, and
`AUTHORITATIVE_RUNTIME_QUALIFICATION.complete` is still false. No row below changes status:
certifiable identity is not qualification.

Kernel contract suites were also re-run on the same commit after rebuilding
`packages/replay-kernel/dist` (the old `dist` carried the stale `c64ac9a7` identity):
**unit 355/355, integration 24/24, zero failures or skips**. Every one of the nine narrowed
SC-276 contract criteria in `narrowed-acceptance-draft-20261029.md` now has a named passing
test on `791f7a3d`; the mapping is in
`qualification-fix-and-final-commit-20261030.md`. That is evidence for a narrowed Done, not
for the original rows: 276-boundary still needs the Linux sandbox, 276-methods still needs
independent references, 276-state still needs the real service/client restart path, and
276-certification still needs the gate and human sign-off.

Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\final-commit-791f7a3d\`;
summary in `qualification-fix-and-final-commit-20261030.md`.

## Rules

- Close a row only with positive or expected-negative evidence from the delivered inputs.
- Never relabel a skip as a pass, or an authored fixture as independent evidence.
- Keep the gate false until every required component passes and the change is reviewed.
