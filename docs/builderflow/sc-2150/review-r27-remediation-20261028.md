# SC-2150 — Review of R27 remediation

Date: 2026-10-28. Project: `C:\Dev\IdeaProjects\ChromaScape`.
Task: `task_1792382767281_cd8dbu69`. Windows remains SC-2204.

## Verdict

The working-tree remediation addresses the original R27-2 retry projection defect and adds the
frozen derivation binding required by R27-1. This review does not certify story completion.
The generated-fixture browser receipts now record 60 labels entered and reloaded, sealing,
immutable export and v3 conversion. Those receipts are mechanics evidence, not owner truth,
human-input qualification or detector accuracy. The browser flow was not rerun in this review.

## Finding — visible labels bypass dropped-field validation

`scripts\sc2150\assemble_label_set.py`, `validate_label`, returns from the VISIBLE branch before
checking `dropped`. A genuine converter document modified to contain a VISIBLE/MANUAL_VISIBLE
label with valid coordinates and `dropped: true` is accepted with a regenerated derivation entry
and manifest. The result counts it as VISIBLE and retains both `visible: true` and `dropped: true`.
The existing structural regression tests do not cover this case.

The original frozen manifest correctly rejects that edit. This is an intrinsic validation gap,
not a bypass of an unchanged trusted manifest digest. Validate the boolean dropped field for
every state before returning, and cover VISIBLE with true and non-boolean dropped values.
Only UNKNOWN may be dropped.

## Verified checks

- Fresh `python3 -m unittest discover -s src/test/python -p 'test_*.py'`: 91 passed.
- Fresh `npm test`: 370 passed, zero failures or skips.
- Current assembler against the saved real sealed conversion and its derivation manifest:
  60 labels, 60 ABSENT, required range 0:176 and stride 3 passed.
- Reproduction above: original frozen manifest refused the edit; refrozen manifest accepted
  the contradictory visible/dropped label.
- Retry source now obtains the shared plane-aware `get` result. The new PostgreSQL regression
  compares complete initial/retry bodies and non-null plane/digest.
- `.gitignore` line 83 matches `/scripts/*/`; `git ls-files` omits
  `scripts\sc2150\assemble_label_set.py` and `scripts\sc2150\qualify_review_sessions.mjs`.
  Include both explicitly in the delivery bundle.

## Unrun checks and remaining work

The focused Java rerun was attempted but stalled in kernel disk I/O before Gradle produced any
output. Termination was requested for that review-owned Java process. No Java pass is claimed
for this review; the implementer's 303-test report remains separately attributed evidence.
No real Spring/Chromium rerun or host-boundary proof was performed here.

Continue the authoritative `fedora-remaining-implementation-plan-20261026.md`:

1. Close the dropped-field validation gap and include ignored delivery files.
2. Capture full Fedora containment identity, descendants, control outcomes, drain and deadline
   evidence. The cleanup changes and successful mechanics receipts do not replace this proof.
3. Finish browser delay/lost-response/context/conflict/empty-save/cap/paging/cancellation,
   sealed read-only and authorization/Origin matrices.
4. Freeze the candidate and run the remaining ordinary/native/replay/stabilization/style and
   opt-in corpus checks.
5. Complete F4–F11: owner truth and held-out top-up, detector/observation/evaluation gates,
   acquisition sandbox/proxy qualification, Fedora native round trip, delivery and tracker gates.

## Tracker and source changes

No application source was changed, staged or committed during this review. No service or shared
database was restarted. Felix live-state retrieval did not complete and was cancelled; no Felix
or Shortcut mutation was attempted. The supplied evidence reports Felix `in_progress / spec_ready`
and Shortcut In Progress; these states were not freshly confirmed through the connectors here.
