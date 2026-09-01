# Replay database operations

Replay playback uses four PostgreSQL keyset indexes over the existing debugger tables:

- `idx_debug_sessions_playback_keyset`
- `idx_debug_sessions_playback_account_keyset`
- `idx_tick_snapshots_playback_session_tick_id`
- `idx_tick_logs_playback_session_tick_id`

Migration `V22__replay_playback_catalog_pagination.sql` creates these indexes idempotently when
the debugger tables already exist. On a fresh database, Flyway can reach V22 before Hibernate has
created those tables. `ReplayPlaybackIndexConvergence` runs after the Hibernate entity manager is
available and applies the same definitions, then checks PostgreSQL's reported definitions for drift.
Repeated application startup is therefore safe and does not add duplicate indexes.

The deployed application database user must retain the schema DDL authority already implied by
Hibernate schema management (`spring.jpa.hibernate.ddl-auto`). The convergence step deliberately
fails application startup when PostgreSQL denies `CREATE INDEX`; it must not silently run without
the playback indexes. Non-PostgreSQL databases are a no-op. No duplicate JPA `@Table(indexes=...)`
declarations are used for these indexes.

## Permission and rollback

The application role needs `USAGE` on the target schema and `CREATE` on that schema, in addition
to the normal table and sequence privileges. Grant those privileges through the database
administration process before deploying a version that enables playback index convergence; do not
work around a denied DDL operation by disabling the convergence check.

To roll back the application, stop the affected version and deploy the previous application
version. Leave the four indexes in place: they are additive, owned by the database migration, and
safe for the previous application to ignore. If the migration itself must be rolled back, take a
database backup first, stop all application instances, and have a database administrator remove
only the four named playback indexes with `DROP INDEX IF EXISTS` in one transaction. Do not edit
Flyway history or delete the V22 migration; a subsequent forward deployment must reapply and verify
the definitions. Restore the DDL grants before restarting the replay-enabled version.
