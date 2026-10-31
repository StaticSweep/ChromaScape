# SC-2150 — R28 current-candidate verification evidence

Date: 2026-10-29. Project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`, `change:ui`).
Candidate manifest: `build\sc2150-r28-candidate.json` (`sha256:b4b88a09…`).
Shortcut: https://app.shortcut.com/leeuwbot/story/2150 (In Progress; Windows remains SC-2204).

This evidence was produced against the current working-tree classes and resources, not the older
Oct 27 runtime snapshot. The candidate used `build\classes\java\main`,
`build\resources\main` and `build\sc2150-review-20261027\production`, with a fresh disposable
PostgreSQL database `sc2150_complete2_20261028` and private media root under `/tmp`.

## Acceptance results

- **Assembler positive:** real sealed export converted and assembled: **60 labels**, 0 VISIBLE /
  60 ABSENT / 0 UNKNOWN, covered range **0:176**, required stride **3** passed.
- **Assembler tamper:** original frozen manifest refused the edited document by digest; a
  manifest refrozen around `visible: true, dropped: true` was refused with
  `ordinal 0 has contradictory dropped/visibility fields`. No output was written.
- **Browser primary flow:** 60 labels entered, 60 reloaded, sealed, immutable export and v3
  conversion; `predictionResponsesBeforeSeal=0`; zero page errors; session
  `0d5a8b1c-0abc-4a5f-a3ce-7697bed28c4e`.
- **Browser protocol harness:** exact retry, exposed blind, reopen exposure and storage-failure
  paths passed; zero page errors.
- **Review backend:** browser POST 201, identical retry same revision, changed retry 409,
  independent-claim 400, pinned repeatable export, legacy export unchanged; zero page errors.
- **HTTP synthetic qualification:** CFR, uneven VFR, burst VFR, 90-degree rotation, encoded crop
  and bitstream crop all passed; PASS.
- **Access lifecycle:** prepare passed 22 access checks (anonymous 401, other owner 404, expired
  claim fenced, two unexpired claims preserved). Capacity passed: full owner rejected before body
  bytes, identical retry accepted, changed retry 409.
- **Browser access matrix:** visible point and independent-page conflict, draft retention,
  reload/save, source-use, history, region selection, keyboard navigation, 390 px layout, axe zero
  violations, immutable export, and lost-worker cancellation reconciliation all passed.
- **Sealed read-only:** hidden/undo/remove disabled; save/H produced no draft or history change;
  direct session POST 409 and second seal 400; zero page errors.
- **Origin/authorization matrix:** correct Origin 409 (sealed write baseline), missing Origin 409,
  wrong Origin 403, missing nonce 403, bogus nonce 403, anonymous read 401, other-owner read 404,
  other-owner write 404.
- **Containment:** 61/61 preview jobs SUCCEEDED, zero FAILED/NEEDS_RECONCILIATION, zero
  `CLEANUP_UNCONFIRMED`/reconciliation log lines. Live probe captured two owned scopes with
  `systemd-run`/`bwrap` identities, 3 tasks each, then `inactive/dead` with empty cgroups. No new
  scope survived; the four empty `chromascape-video-*` scopes still present are pre-existing
  Oct 27 availability-probe residue, not members of this run.
- **Java video package:** 303 tests, 302 passed, 0 failed, 1 skipped (opt-in corpus harness before
  the explicit corpus run).
- **Corpus harness:** 9 dev predictions written with `SC2150_FA12_CORPUS=build/sc2150-fa12`.
- **Dev scorer:** median 0.0 px, p95 2.15 px, cuts F1 1.0; recall 96.57% and absent false rate
  12.95% on supported clips. The dev gates are **NOT passed**; this corpus is spent and is not
  independent truth.
- **Static checks:** `spotlessCheck`, `checkstyleMain`, `checkstyleTest` passed (Checkstyle
  warnings only, 4794).
- **Rollback:** checkpoint restore dry-run against a scratch extraction of
  `refs/codex/sc-2150/20261021T152511Z-verified` completed with exit 0; no apply.

## Tests

| Check | Result |
|---|---|
| `python3 -m unittest discover -s src/test/python -p 'test_*.py'` | 95 passed, 0 failed |
| `npm test` | 370 passed, 0 failed, 0 skipped |
| `./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*'` | 303 tests, 302 passed, 1 skipped, 0 failed |
| `SC2150_FA12_CORPUS=build/sc2150-fa12 … MouseVideoCursorCorpusHarnessTest` | 9 clips, 0 failed |
| `./gradlew test` (full repository) | 2940 tests, **66 failed**, 16 skipped; **0 video failures** |
| `./gradlew spotlessCheck checkstyleMain checkstyleTest` | passed |
| Browser primary / protocol / backend / access / sealed read-only | passed |
| HTTP + access lifecycle + containment probes | passed |

Full-suite failures are outside the video package. The `CdiStartupRegressionTest` context failure
was reproduced and attributed to duplicate `objectMapper` bean definitions in unrelated
`BootstrapRecordingCoordinatorTest` and `BootstrapApprovalStorePostgresTest` test configurations
(`BeanDefinitionOverrideException`). Other failures are in gameplay contract, Micro6 catalog,
routine/source, fishing and simulator packages. They are not SC-2150 regressions, but the full
repository suite is not green.

## Risks

- **No independent owner truth:** F4 still has 3 full held-out videos against a target of at least
  6, and no owner labels. No accuracy, recall or held-out qualification is claimed.
- **Detector gates fail:** the dev scorer fails recall and absent-false-rate gates. Held-out was
  not scored.
- **Synthetic mechanics only:** browser labels are GENERATED fixtures, not human input or owner
  truth.
- **Full suite red:** 66 unrelated failures remain; a release candidate cannot be called fully
  green.
- **Acquisition/native packages open:** F8 has no approved public clip/egress sandbox; F9 has no
  owner native capture round trip.
- **Read-only status text:** on reload the sealed session's queue status overwrites the transient
  “sealed and read-only” message. Controls and backend writes are still correctly refused; this is
  a UI messaging gap, not a write bypass.

## Rollback

- Stop only the task-owned JVMs (ports 18190–18192) and do not restart the normal 8090 service.
- Drop only the disposable `sc2150_complete2_20261028` database and private media root after
  retaining evidence.
- Checkpoint restore remains a dry-run rehearsal; applying it requires stopping affected workers
  and an explicit rollback decision.
- Windows qualification and rollback remain on SC-2204.

## Remaining work

1. F4 independent owner truth/top-up and contamination ledger.
2. F5/F6 detector/observation qualification and hard negatives.
3. F7 frozen final evaluation (cannot start before F4–F6).
4. F8 approved acquisition sandbox, proxy-only egress and product-route proof.
5. F9 genuine owner native trace/frame round trip.
6. F10 disposition of the 66 unrelated full-suite failures and final release-candidate rerun.
7. F11 delivery/PR and validated Felix/Shortcut completion transitions; no completion transition
   was made here.
