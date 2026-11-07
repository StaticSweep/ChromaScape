# Native checkpoint continuation

Native descriptor4/run-state-v2 restore is distinct from causal-v2 restore. It
does not authorize another manifest, source schedule, termination policy or
runtime. Public admission, provenance, capability and certification checks remain
in force. This is not a claim of full SC-274/SC-276 completion.

## Persisted authority

Before registering a native checkpoint the Java driver validates the exact
manifest, accepted requests, counters, offline descriptor, runtime, internal
checksum and observation hashes. It creates a closed
`xrsps-native-checkpoint-context/v1` document from its own accepted ledger, not
the snapshot's declarations. The context binds:

- checkpoint/run/attempt identity, branch key and parent checkpoint;
- manifest/runtime digests, internal snapshot checksum and complete artifact
  content address;
- state/Java-chain hashes, tick, exact consumed source-action count;
- accepted-request-prefix and native-run-state digests.

The existing fenced checkpoint registration persists this context, snapshot
reference and checkpoint row together. Native contexts require no CHAIN artifact;
causal-v2 still requires its own context and finalized CHAIN. The hashes are
integrity bindings against trusted persisted anchors, not cryptographic signatures
against a writer who can alter the database and all artifacts.

## Restore ordering

1. Require an owned terminal parent, identical stored manifest and stop policy,
   correct checkpoint lineage, finalized same-run SNAPSHOT reference and exact
   content-addressed bytes. Historical native rows without a native context refuse.
2. Parse bounded JSON with duplicate-key/trailing-token rejection. Contexts are
   limited to 8 KiB; snapshots use the configured stdout-line byte ceiling (10 MiB
   by default). Nesting is bounded to 128. There is no causal-v2 100,000-node cap
   imposed on a native 100,000-step prefix. The configured wire/artifact limits
   still apply; support for a step count is not an unlimited snapshot-size promise.
3. Validate independent row/context/runtime/checksum/state bindings. Structurally
   check every receipt: closed fields, continuity, exact next semantic source and
   index, due-source timing, tick/step/action bounds, ceiling precedence and final
   run state. This is not historical predicate evaluation or fabricated execution
   evidence. The native runtime must independently replay and validate gameplay.
4. Send `restore` as the first state request to the fresh native worker, without a
   preceding reset or hidden manifest side channel. Validate the returned tick,
   state and observation, then a fresh observation and the complete snapshot's
   content address. Only then install the Java ledger and drive the exact suffix.

Inherited source actions count toward the full lineage prefix. Java inherits its
chain hash, derives sample tick/sequence from the stored receipts, and excludes
parent event ordinals from child persistence. Terminal checkpoints remain terminal:
they may be read and finalized but cannot issue another step or consume a suffix.

## Verification boundary

`NativeCheckpointRestoreTest` exercises production Java registration and restore
with controlled transport and in-memory repository/artifact/terminalization stubs.
It does not establish PostgreSQL fencing, real artifact publication or durability.
Its 100,000-step test is structural Java validation, not a native performance test.

The opt-in `NativeOutcomeGuardedHandoffTest` additionally uses fresh guarded native
stdio processes. Inputs are explicitly authored and admission/persistence remain
mocked. The native runtime stays `SYNTHETIC_CONTRACT`, `certifiable=false` and
`controlAllowed=false`. Consult the separately recorded terminal test results;
the existence of these tests alone does not claim they passed.

Full published-compiler handoff, real DB finalization/recovery/export/migrations,
method/oracle/isolation/resource/privacy qualification, live-server/client Watch,
full builds/CI and PR readiness remain separate gates. Original historical
recording gaps must never be repaired with these authored fixtures.
