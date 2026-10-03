# SC-641 Verification Evidence

Felix task `task_1790966531845_nb35cf2q`; project `C:\Dev\IdeaProjects\ChromaScape`.
See `docs\SC-641-DELIVERY-PLAN.md`, `docs\SC-641-CATALOG-PROFILES.md`, and linked Implementation Evidence.

## Acceptance results

1. **PASS, contract/DOM:** golden Micro6 payloads validate; DISPLAY aliases collapse; canonical duplication and alias collisions fail; searchable aliases/filter controls show one canonical task.
2. **PASS, schema fixture/DOM:** selecting a task lazily fetches the full bundle; actual Fishing field contract names, defaults, ranges and dependent locations/stopping fields render. The fixture uses bounded sample options; a live executor's complete options were not sampled.
3. **PASS, unit/controller/DOM:** missing/invalid schema disables saves; invalid/default/type/range/options/dependency/unknown fields fail with affected field keys; unsafe numeric precision and coercion are rejected. Operator authorization is required before mutation.
4. **PASS, PostgreSQL/DOM:** immutable snapshots retain catalog/task/schema/settings/requirements/source/facts and catalog/schema/settings/requirements hashes; concurrent editors allocate versions 2 and 3; prior versions survive edits and cannot update/delete. Editing unchanged schemas retains saved settings; changed schemas require review of current defaults.
5. **PASS, service/PostgreSQL/DOM:** persisted endpoint/mode-specific cache survives store recreation; transient outage is STALE; identity/authorization/contract failures cannot consume that fallback even when the existing fetcher wraps a valid old payload in STALE; original capture times are retained; stale saves are rejected.
6. **PASS:** 44 focused backend/PostgreSQL/Micro6 regression tests, 21 current-schema regression tests and 235 Node tests pass. Real DOM tests exercise async response races and loading/unavailable controls, not only source-text matching.
7. **PASS, supplied provenance and conditional link contract:** exact supplied entry/module/revision/components and redacted/unknown status are retained without reading a current branch or inventing read sites. Code URLs require the actual Micro6 `srcsnap_` identity derived from manifest hash, exact recording/tick evidence, AVAILABLE source and installed SC-812 module. The module is absent from this base, so the live link remains disabled. Actual authorized navigation is **UNRUN**, not claimed as passed.
8. **PASS, service/DOM/storage:** absent method facts remain UNKNOWN, expired facts remain UNKNOWN_STALE, unversioned/uncited facts are rejected; supplied facts retain citation/freshness/uncertainty/ranges/units. No ranking, run start or client/account selection is implemented.

## Tests

- `aiPlayerCatalogTest`: 44 / 44 (25 catalog/profile plus 19 Micro6 fetcher regressions), no skipped tests; `aiPlayerSchemaTest`: 21 / 21, no skipped tests. Native disposable PostgreSQL 16 was mandatory and available.
- `npm test`: 235 / 235, no skipped tests (13 new real DOM cases).
- Java 17 `compileJava`, scoped `spotlessCheck`, scoped `checkstyleMain` with zero violations, `git diff --check`: PASS.
- Normal V32 migration and updated current V4 schema/fingerprint were tested separately; the reviewed final current fingerprint is `sha256:bdff330e5287ae46f766ccc312d7bb36128a8b1b31e4d12e88be345db85ef63a`.
- UNRUN: live authenticated Micro6 transport, live authorized source navigation, Windows execution and visual browser verification. Local browser preview access was denied; no screenshots or browser checks are claimed. No Micro6 compilation/runtime launch or gameplay was performed.

## Risks

- SC-665 PR #42 remains the stack base; merge ordering must preserve that dependency.
- Existing disposable schemas require the explicit reviewed reset for `sc641-current-v1`; no automatic mixed-version upgrade is permitted.
- Live source links need the separate SC-812 integration plus exact SC-811 evidence. Unknown source/facts remain unknown until supplied.
- Append-only snapshots have no automatic retention policy. Read routes retain the application's existing public-read policy; profile mutation requires operator auth.
- DOM verification passed; browser visual verification was denied and remains unrun. Live/Windows adoption still needs its environment-specific checks.

## Rollback

Revert scoped SC-641 UI/API code while preserving additive snapshots for inspection/export. Do not delete published profile versions or rewrite normal Flyway history. For the explicitly disposable development database only, restore code plus its matching pinned identity using the existing confirmed reset workflow after stopping its application users. Never reset a shared/non-disposable target.
