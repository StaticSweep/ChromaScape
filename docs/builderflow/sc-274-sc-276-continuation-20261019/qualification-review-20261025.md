# SC-274 / SC-276 qualification review - 25 October 2026

## Verdict

**The stories are not done.** Native HEAD 3e465691 and Java HEAD f919b533 contain
substantial new implementation, but runtime qualification is still false, independent
references are missing and additional review gaps remain. No acceptance row is closed.
This review changes documentation/evidence only; no production source, commits, remote
branches, services, containers, WSL state, credentials or tracker state were changed.

## What is implemented

- Native 1daf4006 loads supported CJS/JSON/TypeScript inputs from verified bytes, refuses
  unsupported module paths and adds synchronous loader hooks and negative tests.
- Java f919b533 applies prlimit CPU/file-descriptor/file-size/core limits before bubblewrap
  and supplies a Node old-space cap. Ceilings are explicitly provisional.
- Native 816a83b4 adds skill:fishing-v1 and removes uncontracted CAST_SPELL advertisement.
- Native 3e465691 adds the oracle comparator/matrix, explicit missing-input results and
  authored-fixture refusal by declared kind.
- Java 6c90b610 addresses the previous retained-orphan retry finding by forcing reused
  object directories on new-reference and existing-reference paths.

## P1: sealed-loader claim is bypassable through builtin ESM exports

In runtime-module-guard.ts, register/registerHooks are replaced on the CommonJS module
object by assignment. They remain writable, and the existing ESM node:module namespace
still exposes the original registration function. The guard permits that builtin import.

Fresh isolated Node 22.19 probe, after installing the guard and without capturing the
registration function beforehand:

- registerWritable=true; registerHooksWritable=true;
- esmRegistrationEqualsSealed=false;
- the ESM registration function installed a short-circuit resolver/loader;
- importing a previously unpinned module executed its source and returned 42.

Required: protect registration properties and the exposed builtin ESM bindings; cover
existing namespaces/named exports and attempts to add a short-circuit hook after installation.
Review the trusted bootstrap boundary explicitly. Do not infer that the existing 14 guard
tests establish a sealed loader: this new negative case is absent from them.

This is a module-boundary failure in the isolated worker test process. It is not evidence
of a production authoritative execution: AUTHORITATIVE_RUNTIME_QUALIFICATION remains false.

## Qualification blocker: oracle independence is caller-declared, not established

sealOracleReference computes a digest supplied in the same object as the data. Matrix
entries specify IDs, not independently approved expected digests/provenance. Validation
therefore establishes internal consistency, not authority or independent capture origin.

Fresh probe: a known synthetic AUTHORED_FIXTURE returned INCOMPLETE; changing its kind to
INDEPENDENT_CAPTURE and resealing it returned QUALIFIED with no external authority/digest.

Before any verdict can discharge the runtime gate:

- Bind references to a trusted, reviewed capture inventory containing expected digests and
  provenance authority. Keep independent capture verification separate from comparison success.
- Refuse relabel-and-reseal substitution, conflicting duplicate IDs and unapproved references.
- Bind each actual replay to reference/scenario/action-input/runtime identities. Current actual
  output is indexed only by method, which cannot distinguish multiple required captures.
- Ensure the evaluated matrix covers the actual advertised surface; the coverage helper is
  separate from evaluateOracleMatrix and is not enforced by that evaluator.

The present gate stays closed, so this is not a demonstrated production-authority bypass.
However, the harness alone cannot establish the stated independent-fidelity guarantee.

## Oracle semantic coverage is still partial

projectOracleFrame compares position, XP, inventory and GAME event type names. It drops
event payloads and does not compare equipment, bank contents, quest progression, exchange
book state or combat state. Those are material to several advertised methods.

Define required projections/outcomes per method; add deliberate regressions for those fields
and scenario bindings. Keep comparisons bounded and reject invalid/ambiguous reference input.
Position/XP/inventory equality alone must not qualify every quest/bank/travel/exchange method.

The original 276-methods acceptance also promises glassblowing and enumerated Magic/auxiliary
behavior. Removing bare CAST_SPELL advertisement improves capability accuracy but does not
complete that original scope. Add the required scoped method contracts and production/headless
parity evidence, or obtain an explicit acceptance amendment. Advertised-method matrix coverage
alone cannot silently reduce the original story requirements.

## Resource limits are useful, but not full worker-tree budgets

The current command sets CPU seconds, descriptors, per-file size and core limits; Node gets
max-old-space-size. It does not establish aggregate worker-tree memory/CPU/process limits,
total temporary storage, full wall-clock scenario deadlines or measured production throughput.

Node documents old-space as a V8 memory section, not total process memory:
[Node CLI](https://nodejs.org/api/cli.html#--max-old-space-sizesize-in-mib).
Linux resource limits are per-process; inherited limits are not an aggregate tree budget:
[getrlimit/prlimit](https://www.man7.org/linux/man-pages/man2/prlimit.2.html),
[fork CPU counters](https://www.man7.org/linux/man-pages/man2/fork.2.html).
Target-platform aggregate enforcement can use a reviewed delegated mechanism such as the
kernel's [cgroup v2 memory/PID controls](https://www.kernel.org/doc/html/v6.12/admin-guide/cgroup-v2.html).

Add native/Buffer allocation, process fan-out, multiple-file growth and aggregate CPU cases
inside an already resource-bounded owned environment. Do not run uncontrolled exhaustion on
the shared host. The cancellation test's blocking readLine also needs a bounded asynchronous
read; checking a deadline outside a blocking read does not enforce that deadline.

## Fresh verification actually performed

- Current native module-guard, oracle-harness and server oracle-matrix selection: **24/24
  passed** on Windows Node 22.19 with the previously documented Windows esbuild binary.
  This does not replace the reported Linux bootstrap/resource runs.
- Two additional isolated probes reproduced the loader bypass and relabel/reseal result.
- Reviewed the resource-limit test source and launch/configuration diff. Did not rerun
  Linux resource tests, Gradle, full native qualification or the nine known-red session cases.

Evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261025\qualification-review-20261025T125202Z`:
probe sources/logs, targeted-tests.log and review-evidence hashes.
Fixtures and hook changes existed only in disposable review processes/directories.

## Remaining implementation and acceptance sequence

Full execution plan, original WP mapping, prerequisites and exit criteria:
[remaining-implementation-plan-20261025.md](remaining-implementation-plan-20261025.md).

1. Fix the builtin-ESM hook-registration escape; add its regression and rerun guard/bootstrap
   tests on the pinned target Node/Linux platform.
2. Implement trusted reference approval/digest/scenario binding and method-complete oracle
   projections. Keep missing genuine captures INCOMPLETE; never relabel authored fixtures.
3. Finish reachable-handler closure and per-read filesystem enforcement, reproducible Linux
   package/dependency/cache inputs and stable bounded closure export.
4. Qualify aggregate resource controls, wall-clock/cancellation cleanup and cold/warm/parallel
   production scenario performance, with actual OS negative tests on the target platform.
5. Complete the genuine independent oracle matrix. Review qualification criteria on final pins
   before changing the gate. Rerun the nine worker cases after relevant prerequisites change;
   four actual client/provider cases remain separately gated.
6. Resolve released migration authority/Flyway upgrades, sustained clock/renewal ownership,
   supported allocation, original-trust-approved source and genuine capture/context inputs.
7. Qualify paired Watch/observer noninterference and human sign-off; resolve scoped formatting,
   freeze all final inputs and run the full 17-row acceptance matrix and authorized delivery.
8. Power-loss qualification remains unperformed. Publish/release/merge only within the user's
   current authorization; this review performs no push. Preserve local native recovery anchors
   and the unrelated SC-666 Gradle hunk.

Local engineering in steps 1-4 can proceed while independent captures and operational/human
inputs wait. The gate is unfinished qualification work, not a reason to stop all local progress.
Keeping it false until evidence exists is correct; flipping it to clear tests would be wrong.


## Correction note (2026-10-26)

This document was written against native HEAD 3e465691 and Java HEAD f919b533. Since then the native work
(through f4e4aa5f) was merged into xrsps-typescript `main` and pushed (now 4f935c59); the Java commits
through f919b533 are published on codex/sc274-completion-20261015. Statements that nothing is published, or
that the review performs no commit or push, describe the state when it was written, not now.

Not re-verified after the merge: whether the P1 loader-registration escape (R1) and the oracle
relabel-and-reseal finding (R2) are fixed in merged `main`. runtime-module-guard.ts on `main` now refuses
`node:module` imports and seals register/registerHooks, so R1 may be partly done; confirm with the review probe
before closing it. The qualification gate, the 17 acceptance rows and the external inputs remain open. The
SC-666 Gradle hunk is unrelated and is committed separately.
