# SC-274 item 2 — authoritative source/runtime assessment (18 October 2026)

Read-only assessment. No bundle was generated, no key was created, no trust root was
changed, no runtime was launched, no client was modified. The existing source-disabled
client on 8098 (pid 242640) was left untouched.

## Requirement

Item 2 needs a runtime that serves a signed `task-source-manifest/v1` bundle matching the
exact client artifact, accepted by the **configured** trust root, with authenticated
source and catalog endpoints. Per the acceptance text, generating a replacement trust
root or using source-disabled output cannot satisfy the gate.

## What exists

- Correction (2026-10-19): the bundle task is **`:client:prepareMicro6SourceBundle`**
  (`runelite-client/build.gradle.kts:1181`). The earlier name
  `:client:prepareAuthoritativeSourceBundle` does not exist and is retained only as dated
  history.
- The producer path exists in Micro6: `:client:generateAllTaskSourceBundles` (requires
  `MICRO6_SOURCE_BUNDLE_ROOT`), `:client:prepareMicro6SourceBundle`,
  `:client:runBuilderGoalAuthoritative`, and `TaskSourceManifestGenerator`
  (`C:\Dev\IdeaProjects\micro6\runelite-client\...`). Signing inputs come from
  `MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64` / `MICRO6_SOURCE_SIGNING_KEY_ID`.
- Micro6 has a **local development key pair** in `C:\Dev\IdeaProjects\micro6\.env.local`:
  key ID `micro6-sc810-local-ed25519`, public key `…xjufKMl1…`, signing private key present.
- ChromaScape's configured trust root (`ChromaScape\.env.local`):
  `CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64 = MCowBQYDK2VwAyEAoLTKQQMU6E3r1pw/…`,
  `CHROMASCAPE_SOURCE_PUBLIC_KEY_ID = sc810-dev-ed25519-611ab9dd95a518af`.

## The gap (precise, reproducible)

The two keys are different. ChromaScape's configured trust root is
`sc810-dev-ed25519-611ab9dd95a518af`; Micro6's available signing key is
`micro6-sc810-local-ed25519`. A bundle signed by the available Micro6 key is therefore
**not** accepted by the configured trust root, and the identity/fingerprint cannot agree.

The private key matching the configured trust root was searched for and **not found** in
any reachable location:

- `C:\Dev\IdeaProjects\ChromaScape` — only the public key (tests/docs), no private key.
- `C:\Dev\IdeaProjects\micro6` — one Ed25519 private key (the local dev key above) plus a
  fake fixture in `scripts/tests/launch-buildergoal-client.tests.ps1`.
- `C:\Dev\IdeaProjects\verification` — none.
- `C:\Users\Beheerder\.codex` config — none.

ChromaScape's own SC-810 evidence repeatedly records this key as a **development** key
("locally-generated development key never adopted"), so it is not a production trust
anchor either.

## Verdict

Item 2 is **blocked with a precise reason**: no bundle accepted by the configured trust
root can be produced from the material on this host, and minting a replacement trust root
is disallowed by the acceptance text. Bounded discovery of releases, Actions artifacts,
launch environment and local config (earlier increments) found no authoritative signed
bundle either.

## Options (require a decision; none applied)

1. **Hard block.** Treat the missing private key for `sc810-dev-ed25519-…` as the blocker
   and record item 2 as blocked. No change.
2. **Owned runtime on Micro6's existing key.** Generate a bundle with Micro6's existing
   local dev key (`micro6-sc810-local-ed25519`) and align ChromaScape's configured trust
   root to that public key. This may constitute "generating a replacement trust root" (and
   the key is documented as development-only), so it needs explicit user authorization.
3. **Locate the authoritative deployment.** If a trusted deployment exists elsewhere (e.g.
   the host at `192.168.1.21`, whose `:8101` responds 401 and whose `:8098` was
   unreachable), point the flow at it. No such deployment has been confirmed reachable.
