# Native checkpoint persistence verification lane

## Current verification, 16 October 2026

The current eight-migration contract is `sc276-current-v2`, with the physically
exported fingerprint
`sha256:4800631e4d50386bf90f374ca1a92c010ec06f0056e742e9af4225312d3126b2`.
Unreleased V8 reconciles the committed native source cursor and current optional
capture identity, quality and combat sections. V1-V7 remain unchanged; missing
historical capture sections stay NULL. Existing nonempty schemas are refused
without automatic upgrade or invented evidence.

An independently owned loopback PostgreSQL 16 fixture verified all **9 persistence
cases and 22 full schema cases**, with zero failures, errors or skips. This includes
strict validation of every current application entity and the startup guard.
Published snapshot readback checks exact canonical bytes and their content address;
it does not depend on Java's LongNode/IntNode choice after JSON parsing. The exact
owned container was stopped and removed successfully. Archived XML, source hashes,
commands and ownership/cleanup evidence are in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-db-current-suites-result.json`.
The actual fingerprint export is recorded beside it in
`native-db-v8-current-schema-export-result.json`.

These results verify the explicit external-fixture alternative below. They do not
qualify published recording admission, fresh native continuation, recovery/HTTP
export or the production OS sandbox. The following V7 investigation describes
earlier evidence and is superseded by this V8 result where indicated.

Run `gradlew.bat replayNativeCheckpointPersistenceTest` explicitly. This task is
separate from ordinary unit/replay tests and existing certification tasks. Missing
Docker/PostgreSQL prerequisites fail; they are not silently skipped. It uses only
Testcontainers-owned PostgreSQL 16 and a JUnit-owned temporary artifact directory.
Never point it at a shared or application database.

An explicit test-only alternative accepts `SC276_NATIVE_CHECKPOINT_JDBC_URL` for
the same **newly owned** loopback `chromascape_aiplayer_dev` database as the schema
lane, with its fixed sc665 fixture credentials. The URL is checked by the existing
strict target validator; remote/DNS/shared-name/extra-option targets fail. Before
launching tests, its owning orchestrator must check the exact container ID, random
ownership label and loopback mapped port, and must stop only that owned ID in
`finally`. This mode does not use or disable Ryuk: the external fixture owns its
own cleanup. Without that explicit variable, the normal Testcontainers fixture
and its cleanup still run. Missing prerequisites or rejected targets fail; none
of these cases is skipped. This alternative is authored but not yet executed.

The lane applies the full `db/ai-player-current` Flyway location with grouping,
clean disabled, no baseline, no out-of-order migration and strict Hibernate
`validate`. Its entity and repository scans cover replay only: this is not full
application entity validation or the AI-player schema startup guard. The older
replay integration lane stages selected legacy migrations and does not establish
current-schema compatibility.

Source inspection found that the current V1-V6 schema predates legacy V63/V64/V71
restore bindings, transition ordinals and CHAIN/PROJECTION/OUTCOME artifact kinds.
The V7 candidate adds those nullable bindings and exact kind/ordinal checks without
changing V1-V6 or backfilling invented authority. It proposes development identity
`sc276-current-v1`. The first V1-V6 smoke failed during Ryuk startup, before
PostgreSQL or SQL ran. Its already-compiled inputs were separately preserved
before these source changes. No baseline result applies to the V7 candidate.

An exact-ID/random-label-verified, disposable PostgreSQL 16 fixture applied all
seven migrations and exported the real physical catalog fingerprint
`sha256:ac376ee8f50ad205ffd67a00eae6e27ccbe5f670de14e1c9162675be60bf48fc`.
The strict production guard identity/count and checked-in pin now match that
reviewed export. The exporter stopped its own fixture successfully. Evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-20261015\native-db-owned-schema-export-result.json`.
This export alone is not full-application or startup-guard verification; both
the full schema lane and persistence cases still require actual test results.
The replay-only smoke prints `SC276_CURRENT_SCHEMA_FINGERPRINT` using the actual
catalog function. Never fabricate a pin, edit released baselines or weaken
validation to hide drift.

Candidate cases cover database-clock claims/leases, real transaction-proxied
persistence and heartbeat services, atomic snapshot/context/reference registration,
stale/wrong/expired/cancelling fences, duplicate-checkpoint rollback, actual file
publication/readback, report preparation, finalization reservation/receipt checks
and owner-scoped idempotent branching. A database fixture is deliberately
DRAFT, INCOMPLETE and HELD_OUT with AUTHORED_TEST_ONLY provenance. It is inserted
below public admission to test persistence and never presented as published
compiler authority or an eligible recording.

A separate SQL-constraint case accepts CHAIN/PROJECTION/OUTCOME, retains nullable
legacy ordinals, and requires SQLSTATE 23514 with the exact named constraint for
unknown kinds and negative ordinals. Its SQL-only artifact references stay STAGING
and do not claim published file bytes. Nine persistence/schema cases are authored;
none has a new passing result yet. A strict schema regression also requires an
existing V1-V6 contract to be rejected without applying V7 or altering the public
sentinel; that regression is pending the real identity/pin reconciliation too.

These cases use controlled snapshot transport and an unused recovery-service
double. They are not fresh-native-process plus database continuation, recovery or
HTTP/export qualification. The prior guarded native continuation lane has the
opposite boundary (actual native processes but mocked storage). Both boundaries
must be joined and verified before claiming a durable native handoff. The
production Linux sandbox remains unchanged; no Windows unsandboxed production
fallback is permitted. New candidate cases remain unverified until their exact
task results are recorded.
