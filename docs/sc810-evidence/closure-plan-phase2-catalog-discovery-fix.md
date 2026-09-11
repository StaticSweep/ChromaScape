# SC-810 Phase 2 (catalog-discovery fix) — result and an honest correction (2026-09-11)

## What was fixed and proven

`UniversalTaskDiscoveryService.getClassesInPackage` (and `getSkillCategories`) called
`new File(resource.toURI())` unconditionally, throwing `IllegalArgumentException: URI is not
hierarchical` for a `jar:file:...!/` resource URL — meaning a packaged, shaded JAR launch could
never discover tasks by package scanning at all. Fixed on `sc810-package-20260910`, commit
`bb789cc52a41d1e91ae410ee991357d2a7aea1aa`:

- Branches explicitly on `resource.getProtocol()`: `file` keeps the exploded-directory scan
  unchanged; `jar` recursively enumerates `.class` entries at or beneath the package path via a
  dedicated `JarFile` handle (never the classloader's shared/cached one, so closing it can't
  break a concurrent class load elsewhere); any other protocol is explicitly rejected rather
  than falling through to `new File(URI)`.
- `getClassesInPackage` gained a package-visible `ClassLoader`-accepting overload purely for
  test injection; `Class.forName` itself is unaffected (it always resolves through
  `UniversalTaskDiscoveryService`'s own defining classloader).
- New test suite `UniversalTaskDiscoveryServiceJarDiscoveryTest` (7 tests, all passing): exploded
  discovery against the real mining package; jar discovery of a temp jar built from the same real
  compiled classes finding an identical class set/ordering; exact package-prefix-boundary
  protection; inner-class symmetry with exploded scanning; `JarFile`-handle closure (proven via
  post-scan file deletion, which fails on Windows if a handle leaked); a jar path containing
  spaces; and an unsupported resource protocol failing closed to an empty list.

Rebuilt the shaded JAR at this commit (SHA-256 `f855176aeaafc4e5e87d760223ba2c083b20b77848bc9b0f035d53d77a25fbee`,
`Micro6-Build-Id` matches), regenerated/signed the bundle (`srcbundle_1e39de6c66af33be8ab47074`),
and relaunched the same real-packaged-JAR verifier used for the running-artifact-gate proof.
**Confirmed empirically: zero occurrences of "URI is not hierarchical" in the new run's log**,
versus many per skilling/quest category before the fix. The fix works.

## Correction: this does not reach `catalog_state: READY`

The Phase 2 acceptance bar in the plan was `catalog_state: READY` and Fishing Training
discoverable. After the fix, `/api/v1/catalog` still reports:

```
"catalog_state":"DEGRADED","catalog_valid":false, ... "count":319, "task_failed_count":0,
"schema_failed_count":14, "validation_error_count":8,
"validation_errors":["task_invalid_key:DISPLAY::UGVZDCBDB250CM9S", ...8 total]
```

`task_failed_count: 0` and `count: 319` (a large, populated catalog) show the fixed
`UniversalTaskDiscoveryService` scanning is not the current bottleneck — this codebase's catalog
is built primarily from an extensive hardcoded task-mapping table
(`[UniversalTaskDiscovery] Added 61 hardcoded fallback tasks`, observed in the run log), with
dynamic package scanning as a supplement. `catalog_state: DEGRADED` here is driven by two
**separate, previously-undiscovered issues, unrelated to JAR-vs-exploded resource resolution**:

1. `schema_failed_count: 14` — 14 tasks fail settings-schema generation (`safeBuildArgsSchemaForTask`).
2. `validation_error_count: 8` — 8 `task_invalid_key:DISPLAY::<garbled-text>` entries, apparently
   malformed/corrupted display-name-derived keys in the catalog/alias data.

Neither was investigated further this session — root-causing them is a distinct, open-ended
task of unknown size (most likely in `CatalogV2Publication.publish`, `TaskNameMapper`, or the
`AccountBuilderTasks` display-name/alias data itself, none of which this session has read), and
is not something the Phase 2 catalog-discovery fix touches or explains. Reported here rather
than silently re-scoped or claimed fixed.

## Net effect on the Phase 2 plan item

- **Closed**: "Never convert a `jar:file:...!/` URI directly to `File`", "add deterministic
  `jar:` resource discovery", "reject unsupported URL protocols", "close every JarFile/stream",
  and the corresponding required tests (discovery from exploded classes; discovery from a real
  jar; identical task set/ordering; package-prefix boundary; inner-class handling; encoded path
  handling; unsupported-protocol rejection; no resource leaks) — all done and passing.
- **Not closed**: "Actual shadow-JAR startup reports `catalog_state: READY`" and (by extension)
  treating Fishing Training's catalog presence as proof of full catalog health — Fishing Training
  itself was not confirmed present/absent in this run's lightweight catalog output and would need
  a targeted check of the `count: 319` task list; the catalog's overall state is DEGRADED for the
  unrelated schema/validation reasons above, not because Fishing Training or the mining package
  failed to be discovered.

## Recommendation

Given this widens scope beyond what "Phase 2" originally described (a resource-protocol bug),
and the remaining phases in the plan (3 onward) require either explicit direction — which exact
branch is "the intended Micro6 integration branch" was not specified — or explicit separate
authorization already called out in the plan itself (the account-record migration is a data
mutation; adopting a production trust key is a security decision), this record stops here for a
status check-in rather than proceeding further unprompted.
