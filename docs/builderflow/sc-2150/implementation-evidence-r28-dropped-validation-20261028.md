# SC-2150 — R28 dropped-field validation remediation (implementation evidence)

Date: 2026-10-28. Project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`, `change:ui`).
Shortcut: https://app.shortcut.com/leeuwbot/story/2150 (still In Progress; Windows remains SC-2204).

This note records the fix for the validation gap reported in
`review-r27-remediation-20261028.md`. It does not close the remaining Fedora packages.

## Changed files

| File | Change |
|---|---|
| `scripts\sc2150\assemble_label_set.py` | `validate_label` now validates `dropped` for every state before the `VISIBLE` branch can return. A non-boolean value is refused; `dropped` must be exactly `state == "UNKNOWN"`. Genuine converter v3 output still passes because `VISIBLE`/`ABSENT` omit the field (treated as `False`) and `UNKNOWN` emits `true`. |
| `src\test\python\test_sc2150_assemble_label_set.py` | Added four regressions built from genuine converter output and refrozen derivation manifests: `VISIBLE` with `dropped: true`; non-boolean `dropped` values (`"true"`, `1`, `null`); `ABSENT` with `dropped: true`; `UNKNOWN` with `dropped` removed or `false`. |

The previous bug allowed `visible: true, dropped: true` to return from the `VISIBLE` branch before
the dropped check, so a refrozen manifest accepted contradictory truth. The check now runs before
the branch, and only `UNKNOWN` may be dropped.

## Tests

Commands were run from the repository root on Fedora.

### Focused Python

`python3 -m unittest src/test/python/test_sc2150_assemble_label_set.py -v`

- **15 passed, 0 failures** (was 11; four new regression cases).

### Full Python

`python3 -m unittest discover -s src/test/python -p 'test_*.py'`

- **95 passed, 0 failures** (was 91).

### JavaScript

`npm test`

- **370 passed, 0 failures, 0 skipped.**

### Current-candidate verification (2026-10-29)

A fresh run used the current working-tree classes/resources, a new disposable database
`sc2150_complete2_20261028` and private media root, rather than the stale Oct 27 runtime
snapshot. Full results are in `verification-evidence-r28-20261028.md` and
`build\sc2150-r28-candidate.json`.

- Real sealed output assembled: 60 labels, 0/60/0, range 0:176, stride 3.
- Original frozen manifest refused the `visible: true, dropped: true` edit; the refrozen
  manifest refused it with `contradictory dropped/visibility fields`.
- 60-label browser flow, protocol harness, review backend, HTTP fixtures, access lifecycle,
  browser access matrix and sealed read-only checks passed.
- Containment: 61/61 preview jobs succeeded, zero reconciliation/cleanup failures; live
  scopes reached inactive/dead with empty cgroups.
- Java video package: 303 tests, 302 passed, 1 skipped, 0 failed.
- Static checks passed. Full repository suite: 2940 tests, 66 unrelated failures, 16 skipped,
  zero video failures.
- Corpus harness ran 9 dev clips; dev gates still fail recall/absent-false-rate, so no accuracy
  qualification is claimed.

## Delivery inclusion

The assembler and the review-session qualifier are ignored by `.gitignore` (`/scripts/*/`):

- `scripts\sc2150\assemble_label_set.py` — ignored, contains this fix.
- `scripts\sc2150\qualify_review_sessions.mjs` — ignored, contains the bounded preview assertion.
- `src\test\python\test_sc2150_assemble_label_set.py` — untracked, contains the regressions.

A feature delivery bundle must include them explicitly, for example:

```text
git add -f scripts/sc2150/assemble_label_set.py scripts/sc2150/qualify_review_sessions.mjs
git add src/test/python/test_sc2150_assemble_label_set.py
```

The ignored SC-2150 scripts `explain_cursor_track.py`, `qualify_review_backend.mjs` and
`qualify_review_protocol.mjs` were also staged with `git add -f` in this pass. No commit or push
was made.

## Remaining work

Still open per `fedora-remaining-implementation-plan-20261026.md` and the review:

1. F4 independent owner truth/top-up and contamination ledger; no owner labels were supplied.
2. F5/F6 detector/observation qualification; dev gates fail recall and absent false rate.
3. F7 frozen final evaluation, which depends on F4–F6.
4. F8 approved acquisition sandbox, proxy-only egress and product-route proof.
5. F9 genuine owner native trace/frame round trip.
6. F10 disposition of 66 unrelated full-suite failures and a final release-candidate rerun.
7. F11 delivery/PR and validated Felix/Shortcut completion transitions.
8. Windows remains SC-2204.

Felix task `task_1792382767281_cd8dbu69` was read back as `in_progress / spec_ready`, and this
evidence was linked as Felix note `note_1793165477720_4f7fadd8`. Shortcut story 2150 was read
back as In Progress (`workflow_state_id` 500000008). No Felix state transition or Shortcut
mutation was made; the remaining gates above are still open.
