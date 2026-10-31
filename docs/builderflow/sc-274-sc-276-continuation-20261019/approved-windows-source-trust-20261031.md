# SC-274 — owner-approved Windows development signer adoption

31 October 2026. The owner explicitly answered **yes** to adopting Micro6's existing
development signing key for this Windows capture, acknowledging that this changes the
original configured-signer requirement. This approval is limited to Windows development
capture. It is not production deployment, simulator fidelity or story-completion approval.

## Implemented plan

1. Recheck the existing bundle, its Ed25519 signature, manifest/snapshot identities, all 34
   file hashes/sizes and exact packaged client artifact.
2. Preserve the original trust values for rollback. Apply the approved public key only
   through the owned Windows runtime's private configuration and launch helper.
3. Start a separate source-serving Windows runtime with its own home/state, an explicit
   child environment allowlist, bounded heap and no private signing material.
4. Deploy the approved trust and separate source-purpose tokens to the owned ChromaScape
   instance, keeping replay consumption and automatic recording disabled.
5. Run the real source-client/verifier acceptance and negative matrix, read all live source
   files, and retain the missing allocation/profile/context gates.

## Changed files

No product Java or shared configuration changed in this increment. The external owned
launch helper `full-candidate\start-owned-candidate.ps1` now loads the approved public key,
key ID/environment, source transport URL and purpose tokens from the ACL-checked private
store. `ChromaScape\.env.local` remains unchanged.

New external helpers under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\runtime-registration-fix`:

- `adopt-approved-source-key.py` — records approval and prepares only public verification
  material and scoped serving tokens; preserves the original public trust for rollback.
- `serve-approved-source.ps1` — copy of the previously tested isolated serving kit, adding
  executor-read authentication and using a 256 MiB initial / 2 GiB maximum heap.
- `run-approved-source-verification.py` — runs ChromaScape's real source client and verifier
  against the serving runtime and checks deployed configuration without exposing secrets.
- `verify-source-files-live.py` — validates the served signature and all 34 source files.

The source-only runtime's own Debug Recorder plugin and automatic session start were
disabled after its first startup attempted a tick-only debug session. That source-only
session is not a gameplay recording or acceptance input. No historical evidence was
deleted. The user's real game client was not restarted or reconfigured.

## Acceptance results

- Approved signer: `micro6-sc810-local-ed25519`; public DER SHA-256
  `ca478f21759339f141e680e1160fbaaee7f7af01767863464aa9cc1a40f6e551`.
- Existing bundle: `srcbundle_fdd4e78fcc5739645ef0727a`, environment
  `native-windows-local`, snapshot `srcsnap_0585920d2af05903264627ba`.
- Exact artifact: `sha256:96c912c9cdecfa49ccd7125c52b68394190dbd11d5ebe74042d065f3c06a71e0`,
  build `429e32978b1027ca913660ef38f69b92d35bb3c0`.
- ChromaScape Windows PID 31528, port 8090, owned database `sc274_capture`; strict schema
  validation, no new migrations and no replay consumption.
- Source-serving Windows PID 14576, loopback 8180, isolated state/home under this evidence
  directory. It serves signed source at the login screen and receives no signing private key.
- The real logged-in user client PID 94796 remains independent. Capture was explicitly
  reselected from the API's current window inventory by PID/title, avoiding the extra source
  client's window. The final viewport probe delivered 15 complete JPEG frames.
- Original recording `recording_770ef399` and its diagnostic database remain preserved;
  no run context was retrofitted, no recording started and no game input was sent.

## Tests

- Real compiled ChromaScape Micro6SourceManifestClient / TaskSourceManifestVerifier /
  SourceManifestSignatureVerifier live harness: **15/15 pass**. Covers approved trust,
  exact artifact/snapshot, purpose token separation, wrong key/key ID/environment/tokens,
  manifest/file tampering, and refusal after a prior successful read. The old configured
  trust root remains a negative control and correctly rejects the development signature.
- Live signature validation plus **34/34 file hash/size checks** pass.
- Deployed server key and purpose tokens match the protected approved configuration;
  source process environment contains no signing private key.
- Final viewport: 15 frames. No Java suite was re-run because no product code changed.

## Remaining work

The Windows **development source-trust gate is resolved under this explicit owner choice**.
The whole story is not complete. The authenticated registry still has no supported allocated
runtime; no saved canonical behavior profile or actual run context has been created.
SC-800 is Planned/incomplete and owns the production allocation/start handoff. No allocation
ID, machine session, heartbeat or catalog reference was invented to bypass it, and no static
development route was represented as production allocation evidence.

Next engineering work is the supported allocation/registration/profile/context handoff.
Only after its identities and binding verify should the owner spend time on the Fishing
capture. Finalization, published curation, exact compiler/import/privacy/refusal checks and
the original historical/travel acceptance remain to be reconciled. The SC-1498 provider-origin
first-input requirement also remains; a HUMAN demonstration cannot be relabeled PROVIDER.

## Risks

- This is an owner-approved development signer; no production trust or fidelity claim follows.
- Canonical shared `.env.local` keeps the old trust values. Use the owned Windows capture
  launch helper for this configuration; an unrelated canonical restart will not inherit it.
- The separate source runtime is not a Launcher allocation and has no logged-in gameplay
  account. Its source/capability verification cannot substitute for the real capture's identity.
- Privacy/role approval, allocation readiness and capture eligibility are not inferred from
  successful cryptographic verification. No acceptance row or story state was closed.
- A credential scan matched the client's built-in development API-token default in its
  isolated Mule-coordination profile. That value is compiled into MuleCoordinationConfig,
  not inherited through the serving environment. The runtime profile ACL was restricted;
  it is runtime configuration, not a shareable evidence artifact. The scan result is retained.

## Rollback

After confirming no recording is active, stop only the service identified by owned launch
metadata and remove the approved-source overrides from its launch helper, restoring the
preserved original public trust configuration. Disable the source runtime only after
checking its PID, start identity and isolated-home command line. Retain both homes, stores,
databases, all media and evidence. Never restore a retired secret value. No shared schema or
user game-client rollback is required.
