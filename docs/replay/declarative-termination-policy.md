# Declarative termination policy (compiler 2.4.0)

Compiler 2.4.0 emits this data contract. The native kernel implements initial,
accepted-action and per-tick evaluation with run-state v2 and reconstruction
descriptor v4; the synthetic kernel still rejects nonempty policies. Java causal
admission remains blocked until the current compiler to native policy handoff is
verified. No new method capability or certification is advertised.

The compiler emits `termination` with schema `xrsps-termination-policy/v1` and
three ordered clause arrays: `failure`, `success`, `stop`. A clause contains an
ID (unique across all categories) and a predicate. Policies come only from the
selected published curation's `contentSnapshot.termination`; otherwise the
compiler emits empty arrays. Output and every provenance pointer bind to that
frozen source, or to an explicit compiler default. Final expectations, later
observations and budget exhaustion never supply gameplay success conditions.
Owner-scoped curation create/update requests accept optional `termination` data.
Draft updates preserve omitted policy, labels and unrelated content; an explicit
valid policy with empty arrays clears conditions. Validation precedes mutations,
including publication of stored drafts. Published and foreign-owned curations
remain immutable/invisible. Service and standalone MVC tests cover these paths;
repository doubles and supplied servlet principals do not qualify database
durability, network authentication, browser authoring or production deployment.

Supported predicate forms are `all`/`any` with `predicates`, `not` with
`predicate`, `exists` with a JSON `pointer`, `some`/`every` with an array pointer
and element-relative predicate, and `compare` with `comparison`, `left`, `right`.
Comparisons are `eq`, `ne`, `gt`, `gte`, `lt`, `lte`. Operands are a scalar
`literal`/`value`, a `path`/`pointer`, or `count`/`sum` array reducers with optional
element-relative `where`; `sum` also requires `valuePointer`.

Strict types apply. Ordered comparisons accept numbers only. Missing paths,
accessors, inherited fields, sparse elements and wrong types yield UNKNOWN;
`not(UNKNOWN)` is UNKNOWN. `exists` confirms only known presence (including
explicit null), not absence. False dominates `all`, true dominates `any`.
Empty `every` is true and empty `some` is false; use an explicit count guard
when a nonempty collection is required. Array slot order is not item identity.

The evaluator checks failure first, then success, then stop; source order breaks
ties. Any UNKNOWN failure prevents success. Explicit stop remains eligible.
It observes only the supplied detached state and cannot load code or routes.
RFC6901 escapes are supported; prototype/constructor segments are forbidden.
At most 32 clauses/category, 512 predicate/operand nodes, depth 12, 16 boolean
children and 256 UTF-16 units/string or pointer are accepted. Evaluation permits
65,536 work units, array length 4,096 and numeric magnitude 1e12. Work, collection
or aggregate overflow produces INCONCLUSIVE, never success or gameplay failure.

Shared Java/TypeScript validation vectors are pinned in each repository's test
resources. The native path evaluates after drained domain effects and before any
next tick. Temporal history predicates and full Java-to-native policy handoff
remain required. Current-pin native verification is recorded separately; the
presence of this contract document is not proof of completed story acceptance.
