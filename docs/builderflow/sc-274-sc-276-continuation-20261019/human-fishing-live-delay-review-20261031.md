# SC-274 live manual Fishing delay review

## Live deployment and first preflight

The owned Windows 8090 capture service was replaced with committed ChromaScape `793597062a4cc87ecd2e1be78787b14d48a2f8a7`, PID 56688, preserving database `sc274_capture`. Replay consumption remains `worker=false`.

The isolated client was replaced with clean Micro6 `3f5db7a84dc96dd378f2f977deb351c4472d6bfe`, artifact `sha256:a7bfb5282bd3560469b531940a0613ded32b5d679eebe52dfc03d69b014b4a33`, 85,443,144 bytes, source bundle `srcbundle_1efcaf398ceb82d71e71b623`, snapshot `srcsnap_88cb440a3a8ed24ad03af0ce`. It uses the previously approved development signer; no replacement key was generated or passed to the serving process. The original client JAR was preserved.

Fresh actual allocation `45b55440-b3aa-4f01-a5db-3ddc58a5bcba`, executor `80f47d75-8c56-4d7f-bf5d-39522d99e764`, source PID 135968. The supported saved profile loaded and the expected account logged in. Only the owned catalog bridge was replaced to pin this executor/artifact; its two route checks passed. Source verification: all 34 files and signature verified; real ChromaScape verifier 15/15. Capture readiness 14/14.

Existing behavior profile `1a67835e-3c32-4d95-94ab-46b537ff5687` gained immutable version 2 pinned to the new artifact; version 1 remains intact.

Idle preflight `recording_dc742e08`, session `d44be190-fb77-4113-95a4-dc6992ee8219`, is explicitly NOT_TRAINING. It finalized normally: 75 raw/canonical ticks, 75 calibrated ticks, no quality codes, no dropped/unknown frames, complete measured feature values, final terminal true/pending zero. No semantic interaction occurred. This proves transport/clock/stop mechanics, not a Fishing scenario.

## Genuine first attempt and defect found

The user moved beside the spot, then made one Lure interaction in `recording_8027ae49`, session `bc9787c3-cfc9-4568-99b2-05cb28927e9f`. Normal automatic stop preserved the attempt after the observer reported an unobserved transition.

The recorded sequence is precise:

| Tick | Animation | Moving | Meaning |
|---|---|---|---|
| 1714 | -1 | false | Recorded idle baseline |
| 1715 | -1 | true | Lure callback observed, one intent pending, one path step |
| 1716 | -1 | false | Previous one-tick window expired |
| 1717–1718 | -1 | false | No fishing animation yet |
| 1719 | 623 | false | Fishing animation and first fish delta observed |
| 1720 | 623 | false | Normal explicit terminal frame |

Thus the implemented one-tick transition bound was too short for the real client/server interaction. The attempt contains 113 canonical/raw ticks, all 113 calibrated, no clock quality codes or dropped frames, but five unknown frames and `FISHING_TRANSITION_UNOBSERVED`. Eligibility correctly remains `CAPTURE_INCOMPLETE`. Nothing was backfilled or upgraded, and the observed catch is not retroactively converted into an accepted intent.

Public evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\human-fishing-coverage\attempt-recording_8027ae49`, `live-transition-delay-recording_8027ae49.json`. Raw snapshots remain under the protected private evidence root.

## Verified correction pending activation

Use a maximum six-tick pending interval, covering the observed four-tick delay with a bounded margin. Retain exactly one event and the exact live NPC throughout. Consumed, automated, ambiguous, replaced, missing and expired observations still fail closed. Do not change ordinary event staleness: the specialized human witness alone can use this interval, and the compiler must verify every intermediate frame has the one pending intent and matching target.

Source changes are built in the new isolated `C:\Dev\IdeaProjects\.worktrees\sc274-human-fishing-delay-20261031` so the live client JAR cannot be overwritten. Add a source-generated delayed serializer fixture, strict timeout/boundary regressions, receiving-side and compiler pending-continuity checks, and disposable PostgreSQL restart/idempotency cases for both immediate and delayed witnesses.

The final Windows checks passed: Micro6 111/111, recording/provenance 199/199 (including both immediate and delayed source-serializer fixtures through disposable PostgreSQL), and selected converter/compiler 67/67. Zero failures/errors/skips. The ordinary event-staleness bound remains one tick; the six-tick exception requires the validated specialized human witness, and every intervening pending frame/target is checked.

Corrected clean source commit `132fde8c4e492ac4ff7876cd5e76d66b04623c8b` was built and signed with the same existing approved development signer. Artifact `sha256:5f3e4bb9cc889803fe2c9ec201a704d531d2fba40373b752f521bdf7b2e4bcec`, 85,443,180 bytes; bundle `srcbundle_75de9b0c2828381d410266f8`. No new key was generated. Evidence and XML: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\human-fishing-coverage\delay-correction`.

Until the signed replacement is actually activated, the running artifact remains the one-tick build above. A fresh genuine capture is required; this correction cannot repair missing observations in the existing attempt. The original story's provider-origin/bank/travel/privacy and other unmet gates remain open.
