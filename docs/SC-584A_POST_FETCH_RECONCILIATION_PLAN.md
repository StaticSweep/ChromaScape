# SC-584A Post-Fetch Catalog Reconciliation

Status: implementation plan and specification  
Owner: ChromaScape integration layer  
Affected repositories: ChromaScape (implementation and tests); Micro6 (native-Windows
verification only)

## 1. Purpose

Close the remaining race, lifecycle, and attestation gaps in ChromaScape's authenticated Micro6
catalog path. The browser continues to select an opaque `endpoint_ref`; only ChromaScape contacts
the authenticated runtime registry, and only the registry contacts the executor.

The plan makes a catalog response acceptable only when it can be reconciled with the runtime that
the registry confirms *after* the fetch. It preserves the existing fail-closed behavior: a failed
or contradictory fresh response never overwrites a verified cache entry.

This document is a plan only. It does not itself authorize a production deployment, a commit, or a
Shortcut status change.

## 2. Decisions and compatibility

### 2.1 Java baseline

New ChromaScape reconciliation code and tests may use Java 17. No Java 11 syntax restriction is
required for these gap fixes.

The existing `micro6/runelite-client` `java11ContractCompile` task is not changed by this plan:
it protects a separate shared-contract consumer. If its consumer compatibility policy is later
changed to Java 17, make that an explicit Micro6 build-policy change: rename the task, set
`options.release` to 17, update its test proof, and document why Java 11 is no longer required.
Do not silently weaken that external compatibility policy as part of a ChromaScape race fix.

### 2.2 Bounded acquisition

An acquisition has at most two executor fetches:

1. The initial fetch.
2. One re-acquisition after confirmed, non-core catalog-identity drift or a `202` crossing into a
   completed lifecycle.

No condition may recurse or issue a third fetch. Concurrent callers sharing the same confirmed
core identity and mode must coalesce onto that one acquisition, including its retry.

### 2.3 Cache policy

The cache key remains:

```text
machine_uuid | executor_instance_id | session_id | mode
```

Only an accepted response is written, and it is written under `runtimeAfter`'s core identity.
Last-known-good is eligible only when it has the same core identity and still matches current
catalog metadata under the existing cache-matching rules.

## 3. Terms

| Term | Meaning |
|---|---|
| `runtimeBefore` | Registry runtime resolved immediately before an executor catalog request. |
| `runtimeAfter` | Registry runtime resolved after a `200`, `202`, or `503` response is received. |
| Core identity | `machine_uuid`, `executor_instance_id`, and `session_id`. |
| Catalog identity | `catalog_version`, `catalog_hash`, `schema_bundle_hash`, `catalog_state`, and `schema_bundle_state`. |
| Lifecycle response | A forwarded executor `200`, `202`, or `503` response carrying registry identity headers. |
| Fresh catalog | Validated payload that is safe to cache and expose as `READY` or `DEGRADED`. |
| Last-known-good | Earlier validated cache entry exposed only as `STALE`; it is never refreshed from an invalid response. |

## 4. Non-negotiable invariants

1. A catalog is never bound to `runtimeBefore` after a lifecycle response; all response and payload
   decisions use `runtimeAfter`.
2. Every lifecycle response causes a post-fetch resolve, even if its headers are missing,
   malformed, expired, or contradictory.
3. A changed core identity is a process/ownership replacement, not a retryable catalog change.
4. An ONLINE runtime without a valid `sha256:` catalog hash is a permanent contract violation on
   both the initial and post-fetch resolve.
5. A `202` means generation only when the registry's post-fetch runtime still attests
   `GENERATING` for both catalog and schema bundle.
6. A payload bundle is a security-relevant assertion whether its value is valid or malformed. It
   cannot be ignored merely because it does not parse as a SHA-256 value.
7. A failed fresh response never replaces cache data. It may serve matching last-known-good only
   where the error semantics explicitly permit it.
8. Error status must be deterministic and preserve the most useful root cause.

## 5. Error taxonomy and precedence

Apply the following order to every lifecycle response. Earlier rows take precedence over later
ones, because they identify a more fundamental failure.

| Order | Condition | Response | Cache behavior |
|---:|---|---|---|
| 1 | Post-fetch registry resolve fails or core identity changed | `RUNTIME_CHANGED` | No fresh data; no old-core cache. |
| 2 | `runtimeAfter` is non-selectable | `STALE` only if its own matching LKG exists; otherwise `RUNTIME_NOT_SELECTABLE` | Never cache fresh. |
| 3 | `runtimeAfter` is ONLINE but lacks valid required attestation | `CONTRACT_INVALID` | No stale fallback. |
| 4 | Required response headers, lifecycle matrix, or `observed_at` are invalid | `IDENTITY_UNVERIFIED` | No fresh data. |
| 5 | Header core identity differs from `runtimeAfter` | `IDENTITY_UNVERIFIED` | No fresh data. |
| 6 | Stable header/runtime catalog identity disagreement | `IDENTITY_UNVERIFIED` | No fresh data. |
| 7 | Payload/header or payload/runtime assertion disagreement | `IDENTITY_CONFLICT` | No fresh data. |
| 8 | Payload contract/canonical-hash failure | `CONTRACT_INVALID` | Existing LKG behavior remains limited to non-identity failures. |
| 9 | Executor `503`, registry error, timeout, malformed body, or other transient fetch failure | Existing stale-or-unavailable behavior | Matching LKG only. |

`RUNTIME_CHANGED` is reserved for core replacement and an acquisition that demonstrably crossed
an identity/lifecycle boundary but did not settle during the one allowed retry. A stable bad
attestation remains `IDENTITY_UNVERIFIED`, not `RUNTIME_CHANGED`.

## 6. Required reconciliation algorithm

The target implementation is centered on `Micro6ExecutorCatalogService.doFetchAttempt`.

```text
resolve runtimeBefore
validate runtimeBefore is selectable and contract-valid
acquire/in-flight coalesce by runtimeBefore core identity + mode

for attempt in [1, 2]:
    fetch catalog through registry

    if status is 200, 202, or 503:
        resolve runtimeAfter                         // always, before header rejection
        reject changed core identity as RUNTIME_CHANGED
        if runtimeAfter is non-selectable:
            serve only runtimeAfter-matching LKG
        reject invalid ONLINE runtimeAfter as CONTRACT_INVALID
    else:
        runtimeAfter = runtimeBefore

    validate required identity headers and observed_at
    validate header core identity against runtimeAfter

    if status == 202:
        reconcile generation lifecycle (section 7)
    if status == 503 or another transient failure:
        use stale-or-unavailable only after the above checks
    if status != 200:
        return the status-specific result

    validate body shape and catalog contract
    reconcile header catalog identity against runtimeAfter
    if retryable catalog drift and attempt == 1:
        continue with runtimeAfter
    if unsettled drift on attempt == 2:
        return RUNTIME_CHANGED
    if stable mismatch:
        return IDENTITY_UNVERIFIED

    bind payload identity/lifecycle/hash assertions to headers and runtimeAfter
    classify READY/DEGRADED/UNAVAILABLE
    cache only READY or DEGRADED under runtimeAfter core identity
    return a deep copy
```

The implementation must remain iterative or use a fixed two-attempt helper. It must not create a
new independent in-flight future during the retry.

## 7. Lifecycle-specific requirements

### 7.1 HTTP 200

For a completed catalog response:

- Require complete response identity, valid lifecycle headers, and valid `observed_at`.
- Compare header `catalog_version`, `catalog_hash`, catalog state, schema-bundle state, and the
  applicable schema-bundle hash to `runtimeAfter`.
- A runtime still in `GENERATING` cannot accept a completed catalog.
- When `runtimeBefore` and `runtimeAfter` differ in catalog identity, a stale first response gets
  one re-acquisition. A response that is still stale on attempt two is `RUNTIME_CHANGED`.
- When the runtime is stable and the header differs, return `IDENTITY_UNVERIFIED`.

### 7.2 HTTP 202

Return `GENERATING` only if all of the following hold:

1. `runtimeAfter.catalog_state == GENERATING`.
2. `runtimeAfter.schema_bundle_state == GENERATING`.
3. Header `catalog_state == GENERATING`.
4. Header `schema_bundle_state == GENERATING`.
5. Header core identity matches `runtimeAfter`.

If `runtimeAfter` is `READY` or `DEGRADED`, retry exactly once even when catalog hashes and
versions are unchanged. If the second response is still a stale `202`, return `RUNTIME_CHANGED`.
If the runtime is neither generating nor completed, return `IDENTITY_UNVERIFIED`; a `202` is not
an acceptable substitute for an unavailable or impossible runtime lifecycle.

### 7.3 HTTP 503

Resolve and validate `runtimeAfter` first. A matching last-known-good response may be returned as
`STALE` only when the runtime is selectable and contract-valid. A missing/invalid post-fetch
catalog hash is `CONTRACT_INVALID`, not `STALE` or `EXECUTOR_UNAVAILABLE`.

## 8. Payload/header/runtime binding

### 8.1 Core and catalog identity

For every accepted `200`:

- Payload executor instance ID, when present, equals the header and `runtimeAfter`.
- Payload catalog version equals the header and `runtimeAfter`.
- Payload catalog hash equals the header and `runtimeAfter` after canonical recomputation.
- Payload catalog state equals the header catalog state; header catalog state equals
  `runtimeAfter.catalog_state`.
- Header schema-bundle state equals `runtimeAfter.schema_bundle_state`.

The payload/header disagreement is `IDENTITY_CONFLICT`. A header/runtime disagreement is
`IDENTITY_UNVERIFIED`, with the bounded retry rule only when catalog metadata demonstrably changed
during the fetch.

### 8.2 Schema bundle

If the payload has a non-empty `schema_bundle_hash` field:

1. The value must be a valid lowercase `sha256:` hash.
2. Header schema-bundle state must be `AVAILABLE`.
3. `runtimeAfter.schema_bundle_state` must be `AVAILABLE`.
4. Header schema-bundle hash must be valid and equal the payload hash.
5. Runtime schema-bundle hash must be valid and equal the payload hash.

Any failure is `IDENTITY_CONFLICT`, unless the header/runtime disagreement was detected first and
is therefore `IDENTITY_UNVERIFIED` under the precedence table.

This rule applies to lightweight payloads too. Unknown root fields must not become a bypass for a
bundle assertion that the lightweight contract otherwise ignores.

## 9. File-level implementation work

| File | Change |
|---|---|
| `Micro6ExecutorCatalogService.java` | Reorder lifecycle re-resolution before header rejection; perform post-fetch runtime contract validation before status/body fallbacks; tighten `202`; reconcile lifecycle states; make bundle binding total. |
| `Micro6CatalogReconciliationTest.java` | Add the regression cases in section 10 and align fixtures so a payload/header conflict is not accidentally also a header/runtime conflict. |
| `Micro6TestServers.java` | Only if needed, add deterministic response-status sequencing or header overrides. Prefer existing `beforeCatalogHook` and `beforeResolveHook`. |
| `Micro6ResponseIdentityValidationTest.java` | Retain skew/TTL coverage; add cases only if lifecycle-header parsing or state-matrix rules change. |
| `micro6/runelite-client/build.gradle.kts` | No change required for this ChromaScape plan. The present Java 11 compatibility gate is a separately scoped policy. |

Use small private helpers with names that express the contract, for example
`runtimeConfirmsGeneration`, `postFetchRuntimeContractError`, and
`hasMatchingAvailableSchemaBundle`. Avoid duplicating string comparisons throughout the fetch
method.

## 10. Regression test matrix

| ID | Scenario | Expected result |
|---|---|---|
| R1 | Missing 200/202/503 identity headers | `IDENTITY_UNVERIFIED` and two registry resolves. |
| R2 | Machine change mid-fetch | `RUNTIME_CHANGED`, no retry. |
| R3 | Executor change mid-fetch | `RUNTIME_CHANGED`, no retry. |
| R4 | Session change mid-fetch | `RUNTIME_CHANGED`, no retry. |
| R5 | Catalog hash changes mid-fetch; retry response settles | `READY`, exactly two fetches, confirmed cache only. |
| R6 | Catalog hash changes and retry never settles | `RUNTIME_CHANGED`, exactly two fetches. |
| R7 | Runtime remains `GENERATING`; valid 202 headers | `GENERATING`, no cache replacement. |
| R8 | Stable `READY` runtime receives stale 202 then 200 | One re-fetch, then `READY`. |
| R9 | Stable `READY` runtime receives two stale 202 responses | `RUNTIME_CHANGED`, exactly two fetches. |
| R10 | Runtime becomes ready during first 202 | One re-fetch even if version/hash did not change. |
| R11 | Runtime loses valid catalog hash after request; 200 body malformed | `CONTRACT_INVALID`; no stale fallback. |
| R12 | Same post-fetch hash loss on 202 | `CONTRACT_INVALID`. |
| R13 | Same post-fetch hash loss on 503 with prior cache | `CONTRACT_INVALID`; no stale fallback. |
| R14 | Header catalog/schema state differs from stable runtime | `IDENTITY_UNVERIFIED`. |
| R15 | Payload catalog state differs from matching header/runtime | `IDENTITY_CONFLICT`. |
| R16 | Payload has valid bundle but runtime is not AVAILABLE or has no bundle hash | `IDENTITY_CONFLICT`. |
| R17 | Lightweight payload carries malformed bundle hash | `IDENTITY_CONFLICT`. |
| R18 | Concurrent callers enter a bounded retry | One in-flight owner; two executor calls total; all callers get the same accepted result. |
| R19 | Future `observed_at` at +29s, +30s, +30s+1ns | Valid, valid, invalid. |
| R20 | TTL at boundary and one nanosecond beyond | Valid, invalid. |

Each rejection test must make a second request where appropriate and assert that invalid data was
not cached. Each successful retry test must assert the cache is keyed by the confirmed
post-fetch identity.

## 11. Implementation sequence

1. Add the failing regression tests for R1 and R8-R17 without changing production behavior.
2. Refactor `doFetchAttempt` to establish the lifecycle response ordering in section 6.
3. Implement the 202 helper and second-attempt outcome.
4. Extend catalog identity comparison to lifecycle state and strict available-bundle equality.
5. Run formatter and focused tests after each logical change.
6. Run concurrency regression R18 after all sequential tests pass.
7. Review reason-code precedence against this document and existing API consumers.
8. Update evidence with exact command output, platform, JDK, and test counts.

Do not combine these changes with unrelated launcher/backend files. Do not amend or reset existing
user work. Commit only after the verification gates below are satisfied and the user requests it.

## 12. Verification and evidence

### 12.1 ChromaScape

Run the focused Micro6 suite first, then the repository's broader gates:

```bash
./gradlew --no-daemon test --tests '*Micro6*'
./gradlew --no-daemon compileJava compileTestJava test
npm test
./gradlew --no-daemon diffCheck
```

If the full Java gate remains red because of pre-existing failures, record each failure and its
ownership. A focused green result is sufficient for code-level confidence but is not release
completion.

### 12.2 Micro6

Run only from native Windows, never WSL:

```cmd
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat --no-daemon --no-watch-fs :runelite-client:check
gradlew.bat --no-daemon --no-watch-fs :client:test
```

If the Java 11 contract task remains policy, verify it as a dependency of `check`. Java 17 is the
approved language level for the ChromaScape gap implementation; it does not authorize ignoring a
separately retained Micro6 Java 11 consumer gate.

### 12.3 Evidence requirements

Store output and a short result summary under `docs/evidence/` in ChromaScape. The record must
include Git revision, dirty/staged state, operating system, JDK version, command, test count, and
pass/fail result. Do not rely on `/tmp` or a terminal transcript alone.

## 13. Rollout and observability

1. Deploy first to a non-production registry/executor pair with controllable lifecycle transitions.
2. Exercise R2-R18 against the real proxy headers where feasible.
3. Monitor reason-code rates for `RUNTIME_CHANGED`, `IDENTITY_UNVERIFIED`,
   `IDENTITY_CONFLICT`, and `CONTRACT_INVALID`.
4. Treat a rise in `IDENTITY_UNVERIFIED` as an attestation/proxy issue; do not suppress it with a
   cache fallback.
5. Treat a rise in `RUNTIME_CHANGED` as heartbeat/process replacement churn and investigate the
   launcher, not the browser.
6. Roll back the deployment if unexpected cache misses or contract failures affect healthy,
   confirmed runtimes. Rollback is safe because the changes do not change persistent catalog
   storage; cached entries remain identity-scoped.

## 14. Definition of done

### Code complete

- R1-R20 are implemented and pass.
- No lifecycle response skips the post-fetch resolve.
- All accepted payloads are bound to `runtimeAfter` and strict bundle attestation.
- The two-attempt bound and concurrent coalescing are proven by tests.
- Formatter, focused tests, and static checks pass.

### Story/release complete

- The full ChromaScape Java, JavaScript, and diff gates are green or every pre-existing failure
  has an explicit accepted waiver outside SC-584.
- The native-Windows Micro6 verification is green.
- Real Fishing publication succeeds with a matching canonical account hash; no account-name
  bypass is introduced.
- Operational evidence is checked into ChromaScape.
- The required review is complete and the user explicitly authorizes commit/push and any Shortcut
  status change.

The existing documented account-hash mismatch and full ChromaScape Java failures prevent the
story from being marked Done until the story/release-complete criteria are met.
