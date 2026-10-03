# SC-641 catalog and behavior profiles

Project `C:\Dev\IdeaProjects\ChromaScape`; Shortcut [SC-641](https://app.shortcut.com/leeuwbot/story/641).
Felix task `task_1790966531845_nb35cf2q`, authoritative plan
`note_1790966608780_128cd1d6`. The [complete delivery plan](SC-641-DELIVERY-PLAN.md)
includes scope, exclusions, Given/When/Then acceptance, checklists, architecture, ERD,
API contract, UI states, security decisions and rollout. The linked Excalidraw mockup is
`note_1790966644683_162bc311` and `sc-641/mockup.excalidraw`.

## Operator workflow

Open `/debugger?workspace=ai-player`. Choose an authenticated registry endpoint reference
as the catalog source, then browse Category → Skill / family → canonical task or search
by display name, key or alias. This selection chooses metadata; it does not select an
account or dispatch a run. DISPLAY entries collapse into their canonical target. Alias
collisions and invalid canonical identities fail explicitly.

Browsing requests only lightweight metadata. Selecting a task fetches the existing Micro6
full schema bundle lazily and exposes only the selected task's settings form. Fields come
from Micro6, including its `depends_on` / `options_by_dependency` contract. The form supports
strings, integers, numbers, booleans, selects and multiple selects, with supplied defaults,
ranges, options and conditional visibility. Unknown or unsupported schemas disable saves.
The server re-fetches the current full catalog before every save, checks catalog/schema
identity, validates every active field and supplies authoritative defaults. Field errors
identify the affected setting; identity conflicts require refreshing and reselecting.

Enter a profile name and the existing operator Bearer token to save. The password input is
page memory only; no token is written to local/session storage, profile JSON or logs. Read
routes follow the application's existing public read policy. Mutations inherit the existing
AI-routine operator interceptor (401 when unauthorized; 503 when unconfigured).

Saved versions retain catalog/task/schema/settings/requirements/source/method-fact snapshots,
catalog version/hash, schema hash, canonical settings hash and requirements hash. Historical
versions display retained data in a disabled form. Editing reloads the current schema, preserves settings when the schema hash is unchanged,
and otherwise explicitly asks the operator to review current defaults. Saving creates a new version of the same profile identity. Database transaction advisory locks
serialize version allocation across processes. Database triggers reject updates and deletes.
Version history lists the newest 200 named versions; SC-665's unnamed test seed is excluded.

## Availability and source truth

A temporary registry outage can display persisted last-known-good metadata after an application
restart. It is visibly STALE with its stored timestamp. STALE and DEGRADED details are
browse-only; creating versions requires READY. Authorization, contract, ownership and hash
failures do not fall back to persisted success. Cached upstream replies retain structured failure
codes and their original capture time; masked hard failures cannot be treated as temporary outages. An unavailable source remains unavailable;
an empty result is not presented as a valid catalog. Outdated async responses cannot replace
newer task, catalog or profile selections.

Task source summaries preserve supplied entry/module/commit/tree/build/artifact/snapshot and
component metadata without reading a local branch or synthesizing read sites. Missing source
metadata is UNKNOWN. A Code link needs exact snapshot and manifest identity, AVAILABLE source,
recording/tick evidence and the installed SC-812 workspace. Its URL uses the existing
`workspace=code&codeRecordingId=...&codeTick=...` contract; source authorization remains in the
existing workspace APIs. SC-812 is on a separate integration branch and is absent from this
SC-665 base, so this branch correctly disables that link. No live source navigation was tested.

Optional method facts retain supplied version, HTTPS citations, UTC observation/expiry,
uncertainty and planning values (including ranges and units). Expired or explicitly stale facts
remain UNKNOWN_STALE; missing facts remain UNKNOWN. Nothing computes a best-method score,
chooses a client/account or starts a routine. No external performance values are invented.

## Storage and adoption

Normal application databases receive additive Flyway migration
`db/migration/V32__sc641_catalog_profiles.sql`. It extends the SC-665 profile contract with
nullable columns so the unnamed fixture/older rows remain readable, adds the immutable catalog
snapshot table, and adds native immutability constraints/triggers. Profile saves fail with
`PROFILE_STORAGE_UNAVAILABLE` if the storage migration is absent.

The isolated disposable development location adds `V4__catalog_profiles.sql`. The pinned
identity is **sc641-current-v1**, four versioned migrations, and fingerprint
`sha256:bdff330e5287ae46f766ccc312d7bb36128a8b1b31e4d12e88be345db85ef63a`.
The fingerprint was generated on a new owned PostgreSQL 16 container and reviewed together
with the SQL. Startup verifies it without upgrading a nonempty older development schema.
Adopting this identity requires the explicit reviewed reset from
[the SC-665 schema runbook](SC-665-CURRENT-SCHEMA.md). Stop all users of that disposable database,
confirm its ownership, and use its existing `RESET_AI_PLAYER_DEV` workflow. This delivery
never resets or migrates a pre-existing user database.

Catalog snapshots are append-only and preserve historical metadata. No automatic retention
policy is introduced. Profile/task snapshots contain metadata and configuration, not raw source
files or credentials. Existing recording, dataset and execution provenance remain separate.

## API

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/ai-routine/catalog?endpointRef=...&refresh=false` | Lightweight canonical catalog with explicit state |
| GET | `/api/v1/ai-routine/catalog/tasks/{taskKey}?endpointRef=...&catalogHash=...` | Lazy selected task/schema/source/facts detail |
| GET | `/api/v1/ai-routine/profiles` | Newest named profile-version summaries |
| GET | `/api/v1/ai-routine/profiles/{profileId}/versions/{version}` | Exact immutable historical version |
| POST | `/api/v1/ai-routine/profiles` | Authenticated create/append, HTTP 201 |

POST fields: `name`, `endpointRef`, `taskKey`, `catalogHash`, `schemaHash`, `settings`, optional
`profileId`. Requirements and snapshots are server-owned. Unknown request/settings fields,
invalid types/options/ranges/dependencies/defaults and unsafe numeric precision are rejected.
Failures contain `code`, `message`, and `fieldErrors`; schema-unavailable details disable editing.

## Verification and fixtures

Run with Java 17 and a newly owned disposable PostgreSQL database using the guarded
`SC665_TEST_JDBC_URL` accepted by the existing schema tests:

```bash
npm ci --ignore-scripts
npm test
bash ./gradlew -I docs/sc-641/scoped-quality.gradle spotlessCheck checkstyleMain aiPlayerCatalogTest aiPlayerSchemaTest
```

The 44-test catalog lane includes 19 Micro6 fetcher regressions and an actual
fetcher-to-AI-Player integrity-failure fence, alongside backend contracts, controller/operator
auth and real PostgreSQL profile
versioning/concurrency/immutability/cache persistence and the normal released V32 migration.
The schema regression tests verify the four-migration current schema, all current JPA mappings,
reset reproducibility, schema drift, unsafe targets and transactional provenance invariants.
The real jsdom tests mount the shipped debugger template and execute the shipped UI module.

`CatalogFixture` reuses the existing Micro6 publication golden payload/hashes, originally from
`TestMicro6Catalog`. `fishing-fields.json` is a **test subset** based on statically read
Micro6 `FishTrainingTask.getConfigurableFields` / executor `toArgsSchema` definitions. Fish and
location options are bounded fixture examples, not a claim that a live executor's complete
catalog was sampled. Production never reads these fixtures. No Micro6 build or runtime launch
was performed. Fixture-based browser preview was attempted, but browser access was denied;
visual browser verification remains unrun. Live authenticated Micro6 transport, live source
navigation and Windows checks were not exercised.

## Rollback

Revert the SC-641 UI/API changes and leave additive immutable snapshots intact for review/export.
A down migration or deletion of published versions is not provided. The development identity
must match its pinned code; moving back to SC-665 requires its explicit disposable reset rather
than silently accepting a mixed schema. Apply no reset to a shared or non-disposable database.


## Current-main integration — 2026-10-03

SC-665 PR42 and prerequisite SC-260 PR44 are now merged. This branch reconciles with that main lineage, preserving both Gradle task groups, the Node dependency ignore, the catalog panel and current replay script. It inherits SC-665's development-only V6 current-main mapping/invariant migration; the SC-641 development schema contains five migrations (V1/V2/V3/V4/V6). The measured fingerprint is sha256:93ce9aed90ec352c5729be231588bc35e85bafd61bcb0f6bc50ea3e343d0920f. An existing dedicated development schema must pass the identity/history/fingerprint gate or undergo the documented explicit disposable reset. No shared/user database was reset.

Fedora JavaScript verification on the combined source passed 276 tests with no failures; three opt-in SC-968 native capture/approval/live-source tests were skipped. Those skips are unrelated to catalog behavior and are not counted as passed. CI monitoring and Felix mutations are skipped by user instruction; Windows/live platform qualification is not claimed.

Final combined Fedora backend results: aiPlayerSchemaTest 21/21 passed; aiPlayerCatalogTest 44/44 passed, zero failures/errors/skips. The first catalog run exposed one stale schema-bundle golden hash and four database-search-path failures; the fixture now matches the current Micro6 canonical schema bundle, and the owned-test JDBC URL explicitly selects ai_player_dev. No production validator was relaxed. Final catalog run BUILD SUCCESSFUL in 45s.
