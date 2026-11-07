# SC-274 final evidence (7 November 2026)

Current status for the narrowed acceptance. Evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\`.

## Changed files

ChromaScape, branch `codex/sc274-completion-20261015`:

- `c29346b3`: merge of `origin/main` `6042ccf2`; `V71__sc276` renamed to `V88`. See
  `merge-integration-evidence-20261106.md`.
- `60152629`: `Micro6CanonicalHash` accepts the SC-663 state-bound schema bundle after full
  recomputation, with regression tests and fixture `catalog-full-canonical-model-rev242.json`.

micro6, branch `codex/sc274-producer-20261101`:

- `b806c2f5da` merges clock quality and the human observer; `94ddb42764` fixes a test stub;
  `4c26317365` merges the bounded lure delay. Client artifact
  `sha256:ec4e2e2da7b132b70f3296587009ad69f607c8af6ba62b7e0d3036c0ec9b0cc4`, signed with the
  owner-approved development key; no new key.

Runtime configuration, not source: `debugrecorder.recordHumanActions=false` in the isolated capture
profile, and window title `RuneLite - SC-274 rev242 (executor 8180)` so 8090 targets the game.

## Acceptance results

| Retained row | Result | Evidence |
|---|---|---|
| 3 stable refusals | Closed; rechecked on the merged compiler, byte-identical | `final-60152629\refusal-a.json` |
| 5 incomplete scenarios | Closed; genuine half proven on both recordings, 0 writes, flags identical | `acceptance\row5-genuine-20261107.json` |
| Travel negative | Closed 2 Nov; compiler tests pass on the merge, not separately re-audited | `wp1\coverage.md` |
| Historical `b83c37e5` | Closed 2 Nov | `historical\b83c-verdict.md` |
| Capture | Closed by `recording_cf368b42`: 20/20 checks, ELIGIBLE | `capture\capture-verification.json` |
| Output | Present with provenance or named blockers | `output-completeness-20261107.md` |

Capture detail: 167/167 contiguous and calibrated ticks, no quality codes, maximum alignment error
39.1 ms, 334/334 `spotId` rows, world 380, region 12341, 6 trout + 4 salmon, 10 feathers, +580 XP,
2,827/2,827 frames decoded (100.6 s). Curation `52ebef49-2f08-4a1d-bdb0-cd356bd348ec`, HELD_OUT.

Owner decision 6 November 2026: five fish of any kind meets the target. This run had 6 trout anyway.

Attempts `recording_33831c5d`, `recording_a1a25d0a`, `recording_4ad3073b` and `recording_eb81d200` are
non-acceptance; start and stop records are under `capture\attempt-*`.

## Tests

On deployed commit `60152629`, JDK 17, `--offline`, Windows host, disposable PostgreSQL.

- Full `replayTest`: 913 tests, 16 failed, 27 skipped. All 16 match `wp0\baseline-failures.txt` on name
  and reason. `Sc274CompilerTransactionIntegrationTest` ran.
- `tickdebugger`, Micro6 service, catalog and contract lanes: 866 tests, 17 failed, 16 skipped. 15 are
  `MouseVideo*` and identical on pristine main; `CatalogPostgresTest` and
  `modeSpecificProducerCatalogHashesAreRejected` fail identically on clean `c29346b3`.
- micro6 `debugrecorder` tests and `verifyCaptureContract` pass at `4c26317365`.
- CI has not run: GitHub refused to start jobs because of account billing.

## Risks

- `BehaviorCaptureOutbox` still advances `eventSequence` for actions it then discards in a human
  frame. Enabling `recordHumanActions` during a human Fishing capture freezes the stream with
  `EVENT_SEQUENCE_GAP`. Avoided by configuration, not fixed.
- Calibration gaps appeared on earlier attempts, unexplained; host load is suspected, unproven.
- RSProx decoders are not qualified for revision 242.
- The capture client exited silently at 00:48:49 on 7 November; cause unknown.
- Any database that applied the old `V71__sc276` needs the V88 history-row change.
- The capture is on clone `sc274_capture_merged`; `sc274_capture` predates it.

## Rollback

Recordings and curations are append-only: mark non-acceptance, never delete. Code: `git revert -m 1
c29346b3` and `git revert 60152629`. Client configuration backups:
`windows-handoff-config.pre-delay-20261106.json`, `.pre-observer-20261106.json` and
`.pre-rev242-20261106-2139.json`. Profile backup: `*.properties.pre-recordHumanActions-20261107`.

## Remaining work

PR #108 review and merge; CI billing; Shortcut closure (D6); disposition of the SC-273, SC-712 and
SC-1498 blockers; a code fix for the outbox sequence defect.
