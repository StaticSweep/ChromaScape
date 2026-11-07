# SC-274 — executor transport repair and remaining allocation/source gates

31 October 2026. The owner requested fixing the setup after logging into TrumpSniper.
This increment fixes transport defects; it does not claim the runtime is registered or
that another ordinary recording is eligible for SC-274.

## Implemented plan

1. Inspect the actual running Windows client without logging secrets or restarting it.
2. Trace authenticated health, heartbeat and owned-child environment setup.
3. Fix authenticated health probes and forward the two distinct configured transport
   credentials to owned child launches. Refuse invalid configuration before capture
   setup, process inspection or replacement.
4. Test authorized health over a real HTTP socket, cross-origin non-disclosure, separate
   credentials, invalid configuration and existing launch/readiness behavior.
5. Provision transport configuration only for the owned ChromaScape service; snapshot
   its compiled runtime outside mutable build outputs and restart only that identified JVM.
6. Verify live authentication boundaries and viewport delivery. Keep source trust and
   allocation prerequisites explicit rather than injecting IDs into a running process.

## Diagnosis

PID 94796 runs the genuine client in `MICRO6_SOURCE_MODE=disabled`. Its environment has
none of the executor auth token, ChromaScape heartbeat token, machine UUID, Launcher
session, endpoint reference, allocation ID, fencing generation, executor instance ID or
source bundle root. Read-only JVM property inspection likewise found no auth, heartbeat,
endpoint or allocation property. An open game window is therefore not an allocated runtime.

The client requires authenticated `/health` and catalog requests. ChromaScape's local
relaunch service sent `/health` without Authorization, and did not map its configured
server-side transport credentials to the child variable names expected by Micro6.

The local machine is enabled but reported offline/unknown by launcher-control. This
explains why the refreshed UI selects the runnable Fedora machine instead; that is not
proof the Windows client or ChromaScape is running in WSL. No remote launch was submitted.

Cryptographic comparison of the actual available private key's derived public key confirms:

- Configured trust key: `sc810-dev-ed25519-611ab9dd95a518af`, public DER SHA-256
  `611ab9dd95a518afc2c2a1088282e1e89939ddeac622043a7d528330032ec888`.
- Available Micro6 signer: `micro6-sc810-local-ed25519`, public DER SHA-256
  `ca478f21759339f141e680e1160fbaaee7f7af01767863464aa9cc1a40f6e551`.
- These do not match. No trust configuration, key, source bundle or acceptance gate was changed.

Current Shortcut SC-800 was fetched: it is Planned, incomplete. Its contract owns the
durable allocation/start handoff and explicitly distinguishes a development adapter from
production allocation evidence. This transport repair does not implement that dependency.

## Changed files

- `BuilderGoalTopologyGuard.java`: validates two distinct strong credentials before
  atomically adding only those credentials to an owned child environment. No allocation,
  machine or session identity is invented.
- `Micro6TargetRelaunchService.java`: checks transport prerequisites before side effects;
  authenticates health probes and refuses to send the credential to an origin other than
  the configured local executor origin. Normal local launches remain source-disabled.
- `BuilderGoalTopologyGuardTest.java`: valid distinct child credentials and atomic refusal
  for missing, weak or shared credentials.
- `Micro6TargetRelaunchServiceTest.java`: authenticated real-socket readiness, a trap server
  that receives zero requests on origin mismatch, and refusal before capture/process work.
  Existing fixtures now configure explicit test transport credentials.

External evidence and runtime helpers are under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\runtime-registration-fix`.
The owned launch helper loads its distinct transport credentials from the ACL-verified
private store. Existing shared/registry credentials and game-client configuration are unchanged.

## Acceptance results

- Transport repair is implemented and deployed on the Windows owned ChromaScape service,
  PID 43148, port 8090, database `sc274_capture`. Hibernate remains `validate`; 70 migrations
  validate and none was added or applied during this restart.
- Actual HTTP heartbeat boundary: missing token 401, wrong token 401, executor-purpose
  token on heartbeat 401, correct heartbeat token with intentionally invalid empty payload
  400. The last request fails required-field validation before registry mutation.
- Client lists before and after these negative probes are byte-identical and empty.
- Running server credentials match the protected store; no credential appears in arguments.
- Authenticated Mule discovery still returns zero executors without an error payload.
- Actual viewport probe delivered 15 complete frames after deployment.
- PID 94796 remains alive. No game input, login, client restart, fake heartbeat, fake
  allocation, shared-container restart, source-trust change or recording start was performed.

## Tests

- JDK 17, offline Gradle focused run after scoped formatting: 90 discovered, 77 executed
  and passed, zero failures/errors, 13 existing platform-specific cases skipped on Windows.
  Topology guard: 6/6. Relaunch: 71 executed and passed, 13 skipped.
- Live transport checks: 9/9. Viewport: 15 frames.
- Scoped Google Java Format and `git diff --check` pass.
- No full Java/native suite, actual child launch or production allocation test was run.

## Remaining work

The runtime registration/capture setup is still blocked. Missing settings are fixed at
process launch; they cannot legitimately be assigned to the old game session by fabricating
an allocated identity. A future proper launch needs actual Launcher/Mule allocation and
fenced endpoint identity, and an approved source bundle matching the exact client artifact.

An owner decision was requested: retain the current trust requirement and obtain a bundle
accepted by it, or explicitly adopt the existing development signer for a Windows development
capture. No answer is presumed. That choice would not itself complete allocation or capture.

Once the prerequisites exist, prepare the exact canonical Fishing task/profile and a real
run context, verify its binding before handing off fishing, then finalize/publish curation
and run the remaining compiler/privacy/refusal acceptance checks. SC-274 remains incomplete.

## Risks

- These repairs improve the ordinary local development launch path; they do not authorize
  that path as production allocation or authoritative source evidence.
- The origin restriction is intentional: credential-bearing local health probes cannot
  silently switch to a browser-selected address, another port or another hostname alias.
- Source-disabled launch, absent SC-800 allocation, original-trust mismatch and missing
  saved profile are separate gates; successful authentication does not clear them.
- Platform-dependent cleanup cases remain unrun on Windows. No long recording soak or
  new genuine context-bound recording was made in this increment.

## Rollback

Revert the four scoped source/test changes and restart only the owned ChromaScape service
after checking no recording is active. Retain the private store and evidence. Any future
credential rollback uses newly generated protected credentials rather than reviving retired
values. The previous runtime classpath is retained in `runtime-classpath-before.txt`;
both databases and all media remain intact. No game-client or shared-schema rollback is needed.
