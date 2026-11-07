# Stage F1: operator-issued grant mechanics on revision 5809869

Observed 19 October 2026. Labelled operator-issued experiment. No shared registry,
credential, database or runtime was used or changed. This verifies the grant **mechanism**
only; it is not Launcher allocation, genuine capture or SC-800 automatic issuance.

## Pinned source

- Repository: `C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api`
- Branch: `codex/canvas-entrypoint-20261016`
- Revision: `58098690b83a3c0914016ddbe69344657401eb47` (`5809869`, "List idle assignments
  in the Canvas legend"), matching the deployed revision label `5809869-account-edit`.
- Clean detached worktree: `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` (Git status
  empty at checkout). The unrelated current checkout `6a687de` was not tested as deployed
  code.
- Exact source archive used for the Linux run:
  `f1-grants\pinned-5809869.tar`,
  `sha256:da805435cfce99a0e3f6001f09577e9897463dcd1d1c0e2453145084414cb9fb`
  (67,010,560 bytes), containing `leeuwbotapi/mule-service` and `leeuwbotapi/shared`.

## Native Windows support: unsupported

The fence store is durable only if `directory.Sync()` succeeds after the atomic rename.
On Windows 11 / NTFS that call returns `Access is denied`, so the pinned service cannot
create a durable fence.

Probe (Go 1.24.13, Windows amd64):

```
Dir.Sync: sync C:\Users\...\fence-probe-3746004332: Access is denied.
```

The pinned revision's own grant tests reproduce the consequence natively:

```
TestEndpointGrantIsBoundToAllocationSessionArtifactAndExpiry  FAIL  ENDPOINT_FENCE_STORE_UNAVAILABLE
TestEndpointFenceSurvivesReloadAndRejectsRollback             FAIL  ENDPOINT_FENCE_STORE_UNAVAILABLE
TestEndpointGrantRotatesReferenceAcrossAgentReconnect...      FAIL  forged observation selected route or missing grant binding
```

No workaround was applied to the product. Relaxing `directory.Sync()` would silently
remove the replay fence and was not done.

## Linux qualification against the exact source

`golang:1.24`, `GOWORK=off`, source mounted from the extracted pinned archive. The only
added file is the additive harness `handlers/f1_grants_harness_test.go`
(`sha256:6ea412cdf3e41791d9f090b091b7f11c7a18a304722275582989ef95d80d8216`); no production
file was edited.

Pinned repository tests (unmodified):

```
TestEndpointGrantIsBoundToAllocationSessionArtifactAndExpiry  PASS
TestEndpointFenceSurvivesReloadAndRejectsRollback             PASS
TestEndpointGrantRotatesReferenceAcrossAgentReconnect...      PASS
```

Additive F1 harness (all PASS):

| Case | Result |
|---|---|
| Valid operator-issued grant accepted and applied to runtime | PASS |
| Lifetime >120 s refused; exactly 120 s accepted; expired claim refused | PASS |
| Same-generation renewal before expiry accepted; fence high-water unchanged | PASS |
| Atomic whole-file replacement revokes absent allocation, keeps replacement | PASS |
| Revocation by omission → `ENDPOINT_GRANT_MISSING` | PASS |
| Missing file → `ENDPOINT_GRANT_AUTHORITY_UNAVAILABLE` | PASS |
| Missing grants file/key → `ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED` | PASS |
| Wrong machine / session / executor / account → `ENDPOINT_GRANT_REJECTED` | PASS |
| Wrong environment → `ENDPOINT_GRANT_REJECTED` | PASS |
| Link-local / metadata origin → `ENDPOINT_GRANT_ROUTE_REJECTED` | PASS |
| Tampered signature → `ENDPOINT_GRANT_REJECTED` | PASS |
| Malformed artifact at signing → refused | PASS |
| Different allocation/reference → `ENDPOINT_GRANT_MISSING` | PASS |
| Higher generation fences older reference | PASS |
| Rollback to generation 1 → `ENDPOINT_GENERATION_FENCED` | PASS |
| Fresh process re-reads fences, rejects rolled-back signed file | PASS |
| Fresh process accepts current generation after restore | PASS |
| Issuer CLI package tests (`cmd/endpoint-grants`) | PASS |

Evidence: `f1-grants\f1-grant-harness.log`,
`sha256:463c951171a14b11ffe9e06ec89ace47f60fd569b7150e223f7ab8099e0bf626`
(12,164 bytes). Combined result: `ok handlers`, `ok cmd/endpoint-grants`.

## What this does not establish

- No live owned registry was started on `127.0.0.1:8182`. Native Windows hosting is
  unsupported, and a container-to-host route to the Windows `127.0.0.1:8180` runtime is a
  separate design that was not reviewed or implemented.
- No registered runtime, allocation, machine/session/executor identity, heartbeat, catalog
  or Launcher selection was produced. Those remain stage F2.
- Automatic grant issuance and renewal remain SC-800 scope. The issuer CLI is experiment
  tooling and was not run as a continuously running service.
- Development (`DEV`) loopback claims were used. Non-DEV HTTPS/non-loopback and mTLS routes
  were not exercised.

## Risks and rollback

- The Linux harness proves the contract, not the deployed container's database/auth wiring.
- Windows remains unable to host the fence store; do not deploy this service natively to
  Windows without a reviewed fence implementation.
- Rollback: delete `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` and the extracted
  `f1-grants\src`; retain the evidence log and archive. No service is running and no shared
  state needs rollback.

Felix and Shortcut remain unchanged.
