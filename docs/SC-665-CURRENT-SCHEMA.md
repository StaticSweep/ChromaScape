# SC-665 current AI Player development schema

Project: `C:\Dev\IdeaProjects\ChromaScape`. Story: https://app.shortcut.com/leeuwbot/story/665.
The full scope, exclusions, diagrams, acceptance criteria and implementation/test checklists
are in [the delivery plan](SC-665-DELIVERY-PLAN.md). Felix task `task_1790961222622_ae65zsk9`
is the authoritative tracker record.

SC-641 extends this foundation with V4 catalog/profile snapshots and identity
`sc641-current-v1`. See [the current catalog/profile runbook](SC-641-CATALOG-PROFILES.md)
for the reviewed reset and migration requirements. SC-665 evidence remains historical.

## Storage ownership

Opt into the **single** `ai-player-development` Spring profile. Its entire database must be
explicitly disposable: `chromascape_aiplayer_dev` on a literal loopback address with an
explicit port. All application mappings use `ai_player_dev`; `public` is never the target.
Only `127.0.0.1` or `[::1]` are accepted. DNS names, remote hosts, other database names,
extra connection parameters and mixed profiles are refused. An optional
`?currentSchema=ai_player_dev` is the only accepted URL option.

This separate Flyway location does not replay or modify `db/migration` history. V1 covers
all committed JPA entities, preserves provider/orchestration/source SQL invariants, and
uses timezone-aware source timestamps to match `Instant`. Source manifest JSON maps to
TEXT rather than PostgreSQL large-object OIDs. V2 adds immutable profiles, durable run
bindings, datasets/jobs, evidence FKs, terminal/completeness and identity guards. V3 seeds
one fixed profile fixture and the schema identity. The fixture is test-only, permits no
control, and does not claim a trained model, completed recording or registered candidate.

The baseline targets committed `df6eff61`. Uncommitted downstream changes in another
checkout are excluded. Profile/run/job/dataset tables establish storage contracts; wiring
future feature services to them belongs to their own stories. Legacy database migration,
backfill, mixed-version readers and production upgrade/downgrade are outside this story.

## Bootstrap, verification and explicit reset

Provision an empty, dedicated PostgreSQL 16 database with the exact name above and a user
that owns it. Choose its port explicitly. Never point these commands at a shared instance's
non-disposable database. From the repository root on Fedora, with Java 17:

```bash
export SPRING_PROFILES_ACTIVE=ai-player-development
export AI_PLAYER_DEV_JDBC_URL='jdbc:postgresql://127.0.0.1:5433/chromascape_aiplayer_dev?currentSchema=ai_player_dev'
export AI_PLAYER_DEV_USERNAME='<dedicated database owner>'
read -r -s -p 'Disposable database password: ' AI_PLAYER_DEV_PASSWORD
export AI_PLAYER_DEV_PASSWORD
bash ./gradlew aiPlayerSchema -PschemaAction=bootstrap
bash ./gradlew aiPlayerSchema -PschemaAction=verify
```

The CLI starts no Spring services, clients, gameplay, provider requests or web server.
Application startup using this profile runs the same guarded strategy before JPA and
uses Hibernate **validate**, never create/update. A bean-factory guard rejects unsafe
DDL/schema settings before application beans initialize. Automatic SQL initialization
with mode `always` is refused. The profile disables AI Player control, provider runtime,
orchestration/live delivery, backend integration, replay workers and candidate builds.

To reset, **stop every application instance using this disposable database first**:

```bash
export AI_PLAYER_DEV_RESET_CONFIRM=RESET_AI_PLAYER_DEV
bash ./gradlew aiPlayerSchema -PschemaAction=reset
unset AI_PLAYER_DEV_RESET_CONFIRM
bash ./gradlew aiPlayerSchema -PschemaAction=verify
```

Reset drops only the literal `ai_player_dev` schema, then recreates it and the canonical
fixtures. Every row in that schema is lost, including recorded sessions and evidence.
It refuses additional user schemas and external dependent objects, including public
views, FKs and triggers. Independent public data is preserved. The fixed schema operation
is serialized with a PostgreSQL advisory lock; it does not stop running applications or
protect against a privileged actor concurrently creating external dependencies.

DROP is transactional, then committed before Flyway's grouped transactional rebuild.
Reset is **not one transaction preserving the old schema**: a failed/interrupted rebuild
can leave the disposable schema absent or uninitialized. No success is reported until
identity/history/structure verification passes. Keep applications stopped and retry the
explicit reset. Capture finalization is a separate atomic data transaction: finalize the
physical recording/debug rows first, then mark their exact capture completed and commit.
Rollback leaves no successful capture. Completed capture/artifact identities cannot reopen.

## Identity and version changes

Identity `sc276-current-v2` plus the pinned SHA-256 catalog fingerprint identify the schema.
Verification checks the singleton identity, Flyway's exact eight versioned migrations and
checksums, and physical columns/defaults, relations, constraints, indexes, functions,
triggers, static sequence configuration and views. It ignores row contents and sequence
counters. Column/index/trigger drift, disabled triggers or altered history fail closed with
`AI_PLAYER_SCHEMA_MISMATCH` and this reset procedure. Existing nonempty schemas are
verified; bootstrap never silently upgrades, repairs, baselines or alters them.

For an intentional new development contract, review entity DDL from
`bash ./gradlew aiPlayerSchemaExport`, manually retain SQL-only checks/indexes/triggers,
and review the baseline/identity together. On a newly created disposable test database,
`SC665_TEST_JDBC_URL=<exact loopback URL> bash ./gradlew aiPlayerSchemaFingerprintExport`
prints a **candidate** fingerprint. It does not update the checked-in pin. Review and
commit the pin only with the intended SQL changes, then rerun all acceptance tests.
Existing development data is reset, not migrated. Never weaken validation to retain it.

## Verification and observability

`bash ./gradlew aiPlayerSchemaTest` uses PostgreSQL 16 via Testcontainers. With Podman,
configure the Docker-compatible socket or provide `SC665_TEST_JDBC_URL` for a newly
created disposable database. The test user/password are `sc665` /
`sc665-disposable-test`; these are fixture credentials only. The suite resets that schema
before each test and keeps a public sentinel. Never provide an existing user database.

To repeat the focused formatting/style gate without formatting unrelated code:

```bash
bash ./gradlew -I docs/upstream-integration/sc-665/scoped-quality.gradle spotlessCheck checkstyleMain
```

The init script explicitly limits Spotless to the four schema production classes and
three schema test/export classes. Checkstyle checks the four new production classes and
asserts that source count; the small source-manifest annotation correction is reviewed
as a diff rather than reformatting its existing file.

Successful CLI operations print `AI_PLAYER_SCHEMA_OK` with action/identity. Refusal codes
include `AI_PLAYER_UNSAFE_TARGET`, `AI_PLAYER_RESET_NOT_CONFIRMED`,
`AI_PLAYER_SCHEMA_BUSY`, `AI_PLAYER_UNSAFE_CONFIGURATION` and
`AI_PLAYER_SCHEMA_STARTUP_REJECTED`. A mismatch includes a reset instruction. Credentials
are environment-only and are not included in success messages. SQL immutability and
completeness rejection uses SQLSTATE 23514; FK and uniqueness use 23503 and 23505.

## Risks and rollback

Review/merge this isolated branch before adoption. Do not copy the baseline onto a dirty
checkout whose entity mappings have diverged. Privileged database owners can disable
triggers; startup detects the structural drift, but this is not an authorization boundary
against an administrator. Content hashes bind identities; SQL cannot attest external
file bytes or certify a candidate. The downstream finalization services own that work.

Revert this change and stop using `ai-player-development` for code rollback. Existing
legacy profiles/migrations were not edited. To discard the new disposable data, use the
explicit reset while the new CLI exists or delete the dedicated test database/container.
There is no production database rollback or promise to recover reset development rows.
