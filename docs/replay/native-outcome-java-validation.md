# Java native outcome validation boundary

`NativeRunLedger` is a candidate pure Java reader for native
`xrsps-run-state/v2`. `ReplayWorker` selects it only after an explicit compatible
`nativeRunProtocol` capability and all existing admission checks. A `runState`
property does not implicitly negotiate a protocol. Existing nonempty-policy,
fidelity, certification, completeness and privacy admission gates remain intact.

## Implemented boundary

Construct one validator per manifest-source execution from an already
checksum/import-validated manifest. It copies source actions and policy, reuses
the compiler execution-limit and exact selected-source policy validators, and
derives the native effective combat step ceiling. `acceptReset` establishes a
zero-counter initial prefix. `acceptStep` validates the actual scheduler request
and accepted result against that prefix before replacing any trusted state.

Validation includes closed ledger/limit/request shapes, strict integral and
boolean types, exact recording coordinates and counters, action identity and
index, zero-tick timed receipts, no advancement past a pending source action,
manifest/effective limits, declared condition category and ID, terminal tick,
and legal reason/outcome combinations. Ceiling precedence is step, action, then
tick/recording-end. Conditions and evaluator work-limit outcomes may precede
ceilings. Inclusive end-tick source receipts remain eligible before recording
end unless a condition or earlier action/step ceiling terminates execution.

An invalid response leaves the previous prefix unchanged. A terminal ledger
refuses any next gameplay step. `requireUnchanged` checks read/snapshot evidence
without admitting a restored prefix. Returned state/evidence is detached; JSON
integral storage classes do not change read equality.

The evidence envelope `xrsps-native-outcome-evidence/v1` carries a
manifest checksum, the validated ledger and source-prefix/suffix counts and
indices. `allActionsConsumed` and `recordingEndReached` are independent facts.
Neither establishes gameplay success, conformance PASSED, an execution status,
native parity or certification. The dedicated `OUTCOME` artifact adds
`conformanceScope` (`FULL_RECORDING` only when both source actions and recording
end are consumed, otherwise `SOURCE_PREFIX`). Its content address enters the
unchanged report-v1 `artifactRefs`; report `outcome` remains expectation
conformance, never a substituted gameplay outcome. A native terminal reason
also identifies full-recording versus prefix scope. `/artifacts/outcome` uses
the same owned-run, terminal-run and finalized-content-address checks as other
artifact downloads. Migration V71 permits the new artifact kind; source code
alone does not establish that migration or durability has been verified.

The production source loop consumes reset/action/tick/budget ledgers before
another gameplay request, stops early source/tail execution, and counts only
actual accepted source actions. Native untimed manifests also advance toward
their bounded recording deadline. Snapshot registration requires descriptor
v4, lifecycle v2, offline mode, exact immutable manifest, the complete accepted
request prefix, ledger equality, internal checksum, live runtime identity and
both canonical-state/observation-hash bindings. Native fractional XP uses the
same decimal/scientific notation thresholds as TypeScript v1; Java's default
scientific form at 1e7 is not a compatible canonical representation. This fixes
the observed ring-enchantment checkpoint checksum mismatch; it is not a new
cross-pin migration or complete floating-point-domain qualification claim.
Outcome artifact registration/publication stays inside the existing fenced
finalization lifecycle, with the same reserved database terminal timestamp.

## Deliberate limits and next integration

This reader validates structural consistency and declared condition identity;
it does not independently evaluate a gameplay predicate or prove the native
evaluator's earliest-boundary/condition-priority semantics. Native tests remain
necessary for those claims. It does not validate an unanchored restored ledger
or authorize replaying a terminal world.

Before enabling Java policy execution:

1. Verify explicit native run protocol v1 (v2 state/v1 policy/v4 descriptor,
   reset/action/each-tick evaluation) in both runtimes. Mixed causal-v2/native
   lifecycle declarations are rejected until their composition is qualified.
2. Verify production-loop stopping and snapshot/OUTCOME artifact registration
   through actual guarded native execution and durable database/finalization/
   export tests, including partial failure, fencing, publication and recovery.
3. Implement anchored native branch restore. Native branch admission is
   deliberately rejected before reset rather than routed into causal-v2 restore
   or trusted from unanchored snapshot counters. A read cannot establish a prefix.
4. Consume unchanged compiler output for selected published nonempty policies
   through the actual Java scheduler and guarded native workers. Only verified
   handoff/persistence permits reconsidering the unsupported-policy gate.

Pure Java contract tests and controlled-transport production-loop tests are not
database/native certification. The previous guarded-worker reset deadline
failure remains historical failed evidence until separately resolved. Full
P1-P11 / 17-row story acceptance remains required.
