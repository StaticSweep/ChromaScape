# Logged-in Windows Fishing handoff — 31 October 2026

## Changed files

ChromaScape's Micro6CanonicalHash and Micro6CatalogV2Contract now verify the
SC-572 CanonicalCatalogModel producer alongside the legacy CatalogV2Publication
summary model. Both are recomputed from their defined fields. An arbitrary attested
hash is never accepted. The new model includes settings and uses the producer's alias
projection. Its lightweight response retains schema content, which is validated with
the full per-schema checks. Legacy lightweight requirements remain unchanged.

CatalogLiveProducerCompatibilityTest and two captured, sanitized catalog fixtures
cover the exact signed Micro6 8f5fff2d capture artifact. Runtime addresses, account name
and instance ID were removed from the fixtures; canonical hash-bearing data is intact.

The external Windows launch helpers now copy only the requested saved TrumpSniper
profile and settings into the isolated home and use the user's explicitly requested
saved legacy email/password handoff. Java expands a restricted argument file before
the main class; credentials are absent from the OS command line. Logs and handoff files
are private. No authentication dialog was automated. Free world 301 avoids the
members-world refusal observed at world 312. Automated tasks and recording stay off
until the normal manual recording API is invoked.

## Tests

Final Gradle aiPlayerCatalogTest selection: **72 tests, zero failures/errors/skips**:
CatalogServiceTest 11, live producer compatibility 5, canonical hash 11, catalog
contract 26, executor catalog service 19. Includes changed metadata/schema/aliases,
forged hash and lightweight schema-hash refusal. Touched Java files formatted using
Google Java Format. No repo-wide formatting applied.

The first attempt was preserved: it exposed the producer's schema-bearing lightweight
response and also selected CatalogPostgresTest without its required disposable JDBC
configuration. That database lane was not claimed verified; the final focused selection
does not include it.

Live before deployment: logged-in TrumpSniper identity confirmed, real fresh allocation
renewed by the Launcher client, operator-issued DEV endpoint grant active, executor
selectable, correct source artifact, recorder ready. 14/14 capture readiness checks and
two actual Linux-namespace-to-Windows bridge reads pass. The registry-proxied catalog
was then correctly refused by the old verifier; that refusal motivated this change.

Evidence: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff\
catalog-live-final-test-summary.json, catalog-live-final-junit, captured producer catalogs,
capture-readiness.json and catalog-bridge-verification.json. Initial failed attempts
remain alongside final results.

## Remaining work

Deploy to the owned Windows ChromaScape capture service only; verify authenticated
registry catalog selection; save a real Fishing profile; start a HUMAN context-bound
capture, then have the user fish. Finalize and verify real ticks, catches, XP/inventory,
source context and curation afterward. HUMAN data does not prove provider-origin or
independent oracle fidelity. No acceptance row or story is closed by setup checks.

## Risks and rollback

Compatibility covers two explicitly implemented producer projections; it does not
accept unknown hash algorithms. Source trust remains the separately authorized local
development signer. This is owned experimental capture, not production certification.
Restore the preceding immutable ChromaScape classpath snapshot to roll back; preserve
database, runtime and fence volumes. No game input is dispatched by these helpers.
