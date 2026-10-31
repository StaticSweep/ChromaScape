# SC-274 Windows assignment and Fishing catalog continuation — 31 October 2026

Development capture setup only. SC-274 acceptance remains open. No gameplay or game
authentication was automated. The original TrumpSniper process, PID 94796, was retained.

## Changed files

Backend isolated worktree `C:\Dev\IdeaProjects\.worktrees\sc274-owned-registry-20261031`,
based on deployed pin `58098690b83a3c0914016ddbe69344657401eb47`:

- `handlers/assignment_lifecycle.go`: re-evaluate cached claims against current assignment
  state; do not retain transient claim errors for 24 hours.
- `handlers/assignment_claim_cache.go`: match live, unexpired assignment ownership,
  machine, slot, client, profile, proxy and run before replaying a success.
- `handlers/assignment_claim_cache_test.go`: regression coverage for renewed/current
  state, terminal/expired/missing assignments and changed claim scope.
- Local commit `8bb340d` (`codex/sc274-owned-registry-claim-fix-20261031`). Not pushed.

Owned verification tooling under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff`:
official Launcher-client lifecycle probe, bounded assignment supervisor, explicit source
environment allowlist, protected configuration loader and owned-registry deployment helper.
No command polling or gameplay dispatch is enabled.

The isolated Micro6 checkout `sc274-micro6-capture-20261031` contains a pending explicit
catalog-task scope implementation and tests. Its build/qualification results must be
recorded before calling it delivered. The original full catalog behavior is retained when
the scope is absent. A selected task's schema failure must remain a failure.

## Tests

- Launcher lifecycle, per-account leases and authenticated executor probe: **73/73**,
  zero failures or skips. `launcher-protocol-tests.log`.
- Go `TestReusableAssignmentClaim`: pass, including eleven named state/scope cases
  plus malformed-cache and missing-assignment checks. `claim-cache-regression.log`.
- Live official Launcher client against the patched owned registry: **11/11**.
  Real Windows machine registration, server-issued session/lease, renewal, wrong-version
  refusal, second-slot same-account refusal, release, fresh reclaim and renewal. No
  commands polled. The attempt directories preserve earlier failed probes.
- Cross-compiled Linux backend binary SHA-256:
  `8fba7e78e628ca2805ca38b81ea318567df5898309af2378181aa67aa176e3f6`.
  Build used `-buildvcs=false` after Go could not obtain VCS status; the exact source pin,
  patch commit and binary hash are recorded separately rather than inferred from stamping.
- The existing source artifact and approved signer remain unchanged in the currently
  running source client. Health confirms source mode authoritative, real lease/instance,
  recorder ready on dedicated loopback 8185, and remote gameplay control disabled.
- The supervisor was resumed against the same server-issued lease and session without
  restarting the client or changing its generation. Renewal and runtime reporting continue.

## Acceptance results

The earlier blanket statement that no assignment API exists was too broad. The deployed
revision already has generic assignment claim/heartbeat/release and Launcher registration.
They can support this bounded operator-owned development handoff. This does not establish
all SC-800 production allocation guarantees.

The live test exposed stale claim replay: release followed by claim could return an old
claim-time response from a 24-hour cache. The first source launch attempt was therefore
not a valid usable allocation and was never granted a selectable route. Its evidence is
retained under `failed-cached-lease-attempt`. The patched live release/reclaim test passes.

Current owned lease is `5463ad71-08eb-415c-a442-55a484a6bf84`, generation 1; execution
`f327af0d-4e5f-4b5d-8994-8df8fbd7c946` is actually supplied to and returned by the
source client. This is a real development execution identity, not an authored registry row.

The source client is at the login screen. Its full catalog has 319 canonical rows,
8 invalid display-only keys and 12 schema failures in other tasks. Fishing's schema is
AVAILABLE, but the global catalog is DEGRADED. ChromaScape correctly refuses profile
creation; no gate has been relaxed and no profile/run context has been fabricated.

## Risks

- The currently running catalog is still degraded; do not start the acceptance recording.
- The pending bounded catalog build needs tests, a clean source commit, exact packaged
  artifact verification and a newly signed bundle using the already approved existing
  development signer. No replacement trust root is authorized or needed.
- Operator-issued grants are enabled only after the observed logged-in account matches
  TrumpSniper. Until then no grant is issued. The supervisor is bounded to two hours;
  its stop releases its lease, and any issued grant expires within 90 seconds.
- Registry clock comparison was within the existing five-second preflight threshold at
  launch (approximately 0.6 seconds). This is not time-service ownership or production
  clock qualification.
- The shared registry on 8084, shared databases, canonical trust and replay consumers were
  not changed. The registry replacement is only the owned loopback 8182 container.
- Original SC-274 Felix task resolution remains unresolved. Continuation notes were
  updated; no task workflow transition or completed acceptance row is claimed.

## Rollback

The previous owned registry container is retained, stopped, as
`sc274-registry-mule-20261020-before-claim-fix-20261031`. Its database, grants and fence
volumes were retained and reused by the replacement. Never reset or roll back fences.
Stop/release the owned supervisor before a registry rollback. Restore the prior image
against the current preserved volumes only after checking there is no active capture.

Owned ChromaScape was restarted after confirming `recording=false` and is configured for
the owned registry on 8182. An initial invocation used the helper's default 8183 port;
that process was stopped, and the owned candidate was restarted explicitly on
`0.0.0.0:8090`. No schema initialization flag was used. The original registry credentials
remain in the protected store; configuration rollback is available from the saved helper.

## Remaining work

1. Test and package the explicit Fishing-only catalog; retain full-catalog errors outside
   this scoped runtime. Sign/verify the exact new artifact with the approved dev key.
2. Launch the exact allocated artifact, verify its real catalog and account identity,
   issue/renew its development endpoint grant, and verify discovery through ChromaScape.
3. Save the actual Fishing profile and bind an immutable HUMAN recording run context.
4. User logs in and fishes only after readiness checks; finalize recording and publish
   reviewed curation. Verify compilation, determinism, privacy and import refusal gates.
5. HUMAN demonstration does not replace the original provider-origin prerequisite.
   Original-trust production approval, independent oracle evidence, Linux qualification,
   operations/release authority and any applicable Watch sign-off remain separate gates.

## Post-build verification update

This section supersedes the earlier pending-build and current-runtime statements above.

- Micro6 local commit `8f5fff2d075e78a08eb974d3b1859b70619c0e31` implements an explicit
  task-scoped catalog and context-bound dashboard recording. Default full-catalog errors
  remain visible. The scoped runtime advertises exactly FISHING_TRAINING; its selected
  schema must still validate. Source unit/protocol tests: **106/106**, no failures/skips.
- ChromaScape local code commit `6b5e2cdf` forwards the exact task/settings/run-context
  reference, targets the actual recorder connection, refuses unknown selectors without
  fallback, and retains/validates declared catalog scope in profile snapshots.
  Recorder/controller tests: **90/90**. Catalog and scope-consumer tests: **59/59**.
  No failures/errors/skips. Scope tests are included in that 59, not added twice.
- Packaged Micro6 artifact: 85,428,551 bytes,
  `sha256:052577d981077e31eadf8c12ed7621315e56c43e7492d2322f64d01ce67b6517`.
  Bundle `srcbundle_60d2a73e00d7872f5093ca8d`, snapshot
  `srcsnap_33360096093092a8e68c3c05`. Existing approved signer only; no new trust root.
- Live signature and all **34/34** source-file hashes/sizes pass. The real ChromaScape
  source-client/verifier harness passes **15/15** on the new exact identity; the original
  trust root continues rejecting it. No signing private key is in the serving process.
- The first bundle-build attempt omitted required public verification inputs. Those inputs
  were supplied from the same approved key; the failed attempt is preserved. One verifier
  attempt omitted its original-environment negative-test input; another tamper attempt
  matched a nonexistent JSON field and therefore did not alter the response. Both failed
  attempts are retained. The corrected test alters the actual pinned repository tree ID
  and explicitly refuses to run if no mutation occurred. Its rejection now passes.
- The earlier bounded lease expired and released normally. Fresh real lease
  `9409f6db-6629-485f-94a2-2a8c9c9659d4`, generation 1, owns actual execution
  `d75cf4ec-9693-422c-ae77-1fd6428927e0`, Windows source PID 96800. Observed catalog is
  **READY**, exactly one task, zero schema failures and zero validation errors. Recorder
  is ready, source mode authoritative, remote gameplay disabled. At verification it is
  still at LOGIN_SCREEN; no grant, profile, recording or completed acceptance is claimed.

Remaining executable handoff: deploy the tested ChromaScape classes, verify the
server-issued recorder connection selector over authenticated health, obtain the user's
actual logged-in account observation, issue the bounded DEV grant, save the real profile,
then start a context-bound HUMAN recording before the user fishes. No raw gameplay input
is dispatched by the setup. The original provider-origin prerequisite remains open.

## Live deployment and handoff verification

The tested ChromaScape candidate is deployed as Windows PID 189168 on `0.0.0.0:8090`,
using an immutable copy of the compiled classes/resources under the evidence directory.
The owned capture database remains `sc274_capture`, Hibernate validate, replay worker off,
automatic recording off. Original TrumpSniper PID 94796 remains alive and was not stopped.
The helper's process/stream completion was not used as deployment proof: the live PID,
HTTP protocol behavior and authenticated source observations are the evidence.

Final live setup checks: **15/15**. Local and LAN debugger return 200. Missing/wrong
executor credentials return 401. The authenticated executor reports the exact allocated
instance, authoritative source, ready recorder and remote gameplay disabled. Its actual
WebSocket selector is `5119a542-dd07-25d5-5e09-118453fe7886`. An unknown recorder selector
returns 503 RECORDER_UNAVAILABLE before capture; recording inventory is unchanged and
recording remains stopped. Discovery contains the actual executor exactly once, with its
matching catalog hash. Before login it is correctly unselectable: ENDPOINT_GRANT_MISSING.
The older authored registry fixture remains stale; it is not the selected capture runtime.

Container-to-Windows catalog routing was qualified separately. Mule proxies catalog reads
from Linux, so its own loopback cannot directly address the Windows process. The existing
Docker Desktop host gateway does reach the Windows loopback listener. An owned read-only
bridge now listens only on `127.0.0.1:8180` inside the owned Mule network namespace and
forwards GET `/health` and `/api/v1/catalog` to fixed `192.168.65.254:8180`. It publishes
no Windows ports, runs non-root/read-only with dropped capabilities and bounded CPU,
memory/PIDs. It supplies the owned executor credential only on that fixed local hop;
public registry authorization is unchanged. The actual executor and artifact are checked
on every response. Other routes/methods/queries, redirects, wrong credentials and changed
upstream identity are refused. Three Go test functions (including six route/method/query
subcases) pass. Real health and full-catalog reads from Mule's namespace both return the
exact expected instance/artifact. No shared registry restart or broad runtime bind occurred.

Bridge container: `sc274-registry-windows-catalog-20261031`, image
`sha256:18e23f7f3a9275e289ba96c262e3b40ca210f47257365dbe510b920f83fb31a0`.
The bridge must be recreated if its owned registry network namespace or expected runtime
identity changes. Stop only this labelled bridge during rollback; no volumes are mounted
into it and no fences are reset.

The evidence scan found two matches for an 11-character legacy registry value in existing
compiled/tracked class and configuration bytes. No other scanned owned-purpose credential
matched. That legacy value was not rotated. The serving launch environment is ACL-protected;
no signing private key is in the client. This is a scoped scan, not a workspace-wide
secret-free or legacy-credential audit result.

The finished capture client was brought forward with Computer Use. It is still at the
login screen. The user must log into TrumpSniper there; no authentication was automated.
After actual account verification, the bounded supervisor can issue the DEV endpoint grant.
Full authenticated registry catalog selection, saved profile, context-bound capture,
published curation and genuine compilation remain pending that handoff. A HUMAN capture
does not discharge SC-1498's provider-origin requirement. No acceptance row, Felix task or
Shortcut story was marked complete, and these local commits have not been pushed.
