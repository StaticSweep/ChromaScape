# SC-2150 independent review — protocol/plane/assembler increment (2026-10-27)

Project: `C:\Dev\IdeaProjects\ChromaScape`.
Task: `task_1792382767281_cd8dbu69`. Windows remains separate in SC-2204.
Reviewed implementation evidence: `implementation-evidence-review-protocol-20261026.md`;
replayed Felix note `note_1793089242067_5aa4aeed`.

Verdict: **not done**. The increment contains useful implemented changes, but two reproducible
code defects remain. The complete Fedora story also has open qualification packages. No package,
acceptance criterion or tracker state is newly completed by this review.

## Findings

### R27-1 — P1: assembler can certify altered or internally invalid labels

Locations: `scripts\sc2150\assemble_label_set.py:70`, `:189`, `:263`.

`identity_of` trusts local `independence_verified`/`independent_protocol_verified` booleans and
copies session/export/revision/checksum strings. It does not verify that the supplied labels
still represent those sealed corrections. `assemble` only checks that the origin belongs to a
manual-origin set and the state belongs to its state set; it does not require the two to agree,
validate finite source/output-bounded coordinates, or verify the label projection against sealed
content. Hashing the current input and current labels creates a new digest, not evidence that
they match the previous authenticated conversion.

Fresh reproductions:

1. Produced a v3 document with the current converter against its controlled sealed-response test
   fixture, then changed one ABSENT/MANUAL_HIDDEN label to VISIBLE with `x=1000000000`, `y=-3`.
   Kept all session/revision/export/provenance fields and the sealed-export digest unchanged.
   The assembler accepted it with a required range and emitted
   `independent_protocol_verified=true`.
2. A complete declared sealed range with an empty edited label list was accepted with
   `require_ranges=['0:29']` and the same independence claim. This is not a demand to label every
   raw frame; it demonstrates that the claimed sealed label coverage can be deleted without
   checking the original sealed projection. Optional stride validation does not establish that
   provenance link.

Required correction: validate state/origin agreement, coordinate types/finiteness/bounds and
range semantics; bind each input's label projection to verified sealed data or an independently
trusted frozen derivation manifest with expected hashes. Do not infer provenance from self-set
flags or fresh hashes of the possibly edited input. Add tampered-converter-output and
removed-label regression cases. This blocks treating F27I06/F27T07 as complete.

### R27-2 — P2: identical session creation retry drops its coordinate-plane document

Locations: `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoReviewSessionService.java:461`, `:469`, `:582`.

The new-session response uses `get`/`row`, whose SQL selects
`coordinate_plane_identity::text AS coordinate_plane_json`. The retry branch instead uses
`SELECT *` and passes that row to `view`, which only reads `coordinate_plane_json`.
Consequently the plane becomes null on an identical retry even though its stored digest remains.

A fresh probe compiled the current sources and used real PostgreSQL plus the existing session
fixture with valid plane facts. Result:

```json
{"firstCreated":true,"retryCreated":false,"sameId":true,
 "firstPlane":{"contract":"coordinate-plane/v1","displayWidth":1920,"displayHeight":1080},
 "retryPlane":null,"sameBody":false}
```

The 201/200 status distinction is implemented correctly; the immutable identity metadata in the
retry response is incomplete. Use the same row projection/get path for both responses and test
plane/digest equality on a valid-plane retry, not only ID and `created` flags.

## Runtime verification blocker

I started a private loopback Spring instance against a dedicated PostgreSQL database and ran
`scripts\sc2150\qualify_review_sessions.mjs` with generated fixture footage.

- The original helper's five-second image assertion timed out while a preview was pending. This
  alone was not classified as an application regression.
- Repeated the same scenario from a build-only helper copy with only the exact-image assertion
  allowance raised to 60 seconds and progress logging added.
- That run saved **five labels**, then stopped at **frame 15**. The UI reported
  `Exact preview needs operator reconciliation`; the actual preview job
  `35d0d78e-2c98-4ac0-a36c-c8e8ee95e522` was `NEEDS_RECONCILIATION` with
  `errorCode=CLEANUP_UNCONFIRMED`.
- Private server diagnostics said owned process cleanup could not be confirmed and process state
  was ambiguous. The runner failed closed; I did not declare cleanup successful, force a job
  state, or bypass containment to finish the browser flow.

The 60-label reload/seal/export/converter scenario therefore **did not pass**. Its root cause
has not been isolated to this increment; it remains an actual Fedora verification blocker, not
an invented source-code attribution. No real footage or owner labels were used.

## Improvements verified or retained

- MouseVideoController requires the policy service; the optional bypasses are removed.
- Fresh controller tests prove 201 for creation and 200 for identical reuse.
- Coordinate-plane/v1 derivation, V48 fields, policy/export inclusion and stale-plane refusal are
  present; legacy null planes remain unqualified at conversion.
- Converter source-size/output-size refusals and assembler overlap/identity/origin-set checks are
  present and their current tests pass. Those checks do not close R27-1.
- The outbox was replayed idempotently after Felix became available. Full note content was fetched
  back and compared; no state, checklist or Shortcut comment/transition was requested or changed.

## Tests actually performed by this review

- Fresh complete JS suite: **370 passed, zero failures/skips**.
- Fresh complete Python suite: **87 passed, zero failures**.
- Fresh selected Java tests using current privately compiled production/test sources:
  **23 passed, zero skips**: 3 coordinate-plane, 2 session-controller,
  13 session PostgreSQL and 5 migration tests.
- Fresh real-PostgreSQL session retry counterexample: reproduced R27-2.
- Current-converter-to-assembler tampering counterexample: reproduced R27-1.
- Actual private Spring/PostgreSQL/Chromium flow: attempted twice, incomplete as described above.

Java review compilation used Java17 and the project's `-parameters` metadata. An initial review
harness compilation omitted that flag and caused two MVC argument-binding failures; it was
corrected and the five affected unit checks passed. That harness error is not an application
finding. Cached project dependencies and remaining current build artifacts supplied the rest of
the runtime; this was not a fresh unfiltered Gradle/application rebuild.

The implementer's recorded whole-video **301 tests / 300 passed / one corpus skip** is retained as
its evidence, not claimed as independently rerun here. I did not rerun the whole video package,
CdiStartupRegressionTest, full ordinary Java/native/stabilization suite, formatting/static suite,
real held-out scoring, product YouTube security flow or native owner capture.

## Evidence and changes

Private evidence: `C:\Dev\IdeaProjects\ChromaScape\build\sc2150-review-20261027\`:

- `source-hashes.json`, `python.log`, `js.log`;
- `java-unit-corrected.log`, `java-pg-tests.log`;
- `session-plane-retry-results.json`, `session-plane-retry.log`, current-source probe helpers;
- `assembler-probe-results.json`, `converter-to-assembler-probe.json`, original/altered fixture
  documents and reproduction helper;
- `browser-60-label.log`, `browser-long.log`, build-only longer-timeout browser helper;
- `runtime-failed-jobs.json`, private server diagnostics and `cleanup.json`.

Application source files were unchanged during review; captured source hashes were checked again.
Review changes consist of this report, its linked Felix note and replay/move of the provided
pending outbox record to `docs\builderflow\felix-sync\synced\sc-2150\`.
No commit/push, normal application restart, label fabrication, detector evaluation, checklist
completion or Shortcut mutation occurred. An unrelated older systemd scope was left untouched.

## Remaining work and delivery state

1. Correct R27-1 and R27-2 and retain the new regression evidence.
2. Investigate the exact-preview cleanup reconciliation and complete the real 60-label flow plus
   delayed nonce/POST/history, read-only, conflict/cap and registered auth/nonce/Origin matrix.
3. Complete the remaining Fedora plan packages: owner independent truth and fresh held-out top-up;
   dev tooling/cursor and additional methods/parity; frozen evaluation; constrained product YouTube
   acquisition; actual Fedora containment/native round trip; full regressions/readiness/rollback;
   and evidence-backed handoff.

Authoritative plan: `fedora-remaining-implementation-plan-20261026.md`, Felix
`note_1793017313598_71a46088`. Keep SC-2150 `in_progress / spec_ready` and Shortcut In Progress;
Windows remains SC-2204. The completed TODOs in the increment are implementation claims, not
proof of whole-story completion or review acceptance.
