# P7 migration inventory and release-authority request — 29 October 2026

Local inventory only. No database was connected, no migration was applied, and no history,
checksum or numbering was changed. Deployed release identity remains external authority.

## Source

- Repository: `C:\Dev\IdeaProjects\.worktrees\sc274-completion`
- Branch/HEAD: `codex/sc274-completion-20261015` @ `70e5a9c024b505a945db114a575d4bdf09990a9e`
- Migrations: `src/main/resources/db/migration`
- Full machine-readable inventory (version, file, bytes, SHA-256):
  `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\identity-rerun-20261029T014052Z\migration-inventory.json`

## Inventory result

- 70 migration files, highest numeric version V72.
- Four fractional versions, which Flyway treats as `1.1`, `3.1`, `34.1`, `39.1`:
  `V1_1__sc967_routine_baseline.sql`, `V3_1__sc967_conversation_generation_alignment.sql`,
  `V34_1__durable_debug_table_ownership.sql`, `V39_1__durable_tick_evidence_tables.sql`.
- Absent from this tree: **V36, V53, V54, V55, V56, V57**. The V53–V57 gap is the one named
  in the earlier review; this inventory confirms the files do not exist here.
- No duplicate numeric versions in this tree.

## Files whose release status must be settled

| File | Bytes | SHA-256 (this tree) |
|---|---:|---|
| `V47__builder_goal_recovery_current_contract.sql` | 9175 | `add04a7ca9792785f7e324fd29a08a2e4d7ff7af429e2b68d596189cb0aad21b` |
| `V48__builder_goal_recovery_contract_corrections.sql` | 2986 | `f41a35a754c62b2ddd1a2bf5c8f737bf70c117ab5681a6a28a5a0434b9a8562e` |
| `V49__add_behavior_graph_editor_drafts.sql` | 1632 | `c69d02c41cdf124950995f7a9359054247c0d2ee714c52227752223ba4065728` |
| `V50__builder_goal_recovery_validation_fencing.sql` | 981 | `8139b3d84d30a1b48f06862308620797109966cb762373b1c0cf40d94c75d006` |
| `V58__sc1431_lifecycle_budget_counters.sql` | 1278 | `935c5559340e192fb067948464429284106ccd0700306ada30c21d93b3241002` |
| `V59__sc1431_budget_integrity.sql` | 2022 | `e8f76f6171561bac73311c2b6de79cc689e454fb5db655c8fdf07bf2747e7205` |
| `V60__sc1431_bootstrap_provider_identity.sql` | 1249 | `61d6139ccdaf7a3a77a34819b6a2b304900de9a5c7c3cb880276edda976a0e5a` |
| `V71__sc276_native_outcome_artifacts.sql` | 401 | `7a1dc8ce9926e1a27a138d58c8d6c7a41608dda7f38fdd65aac4116120744306` |
| `V72__sc276_committed_source_cursor.sql` | 566 | `ac729c5536464b6d9a3a88bfd98fa1e451f00d83ab4e541e4e3a1739cff0df08` |

`V50` is specifically called out as *modified* in the earlier review; the checksum above is
this tree's, not necessarily the released one.

## Request to the release owner

Provide, for every supported deployed baseline:

1. The full resolved Flyway history: `version`, `description`, `type`, `script`,
   `checksum`, `installed_on`, `installed_by`, `success`.
2. The exact released SQL bytes for V47–V49, V50, V58–V60, V71–V72 and any released
   V53–V57 (if they exist outside this tree).
3. The supported source → target release pairs, and whether empty install plus in-place
   upgrade is expected for each.
4. Confirmation of which numbering is frozen and which is provably unreleased.
5. The approved rollout and rollback window, and who owns it.

Do not infer release status from local tags, this worktree's history, or a successful empty
install.

## Rehearsal once authority arrives

On an owned disposable PostgreSQL only:

1. Restore or install each supported baseline exactly.
2. Run the application's Flyway `validate` and `migrate` for the candidate.
3. Record `flyway_schema_history` before/after, data-preservation checks for the tables named
   in the acceptance rows, and any validation refusal.
4. Repeat for interruption during migration and for a failed validation.
5. Keep the shared database untouched until a reviewed rollout exists.

`sc666CompletionTest` already proves the disposable-database guard; it does not prove upgrade
compatibility.

## Status

Inventory: complete for this tree. Upgrade design: blocked on released bytes, released
checksums and the supported upgrade matrix. No hidden rewrite, repair or renumber is
permitted to make a rehearsal pass.
