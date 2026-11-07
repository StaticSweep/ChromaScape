# SC-274 output completeness on the genuine capture (7 November 2026)

Supersedes `output-completeness-20261104.md` for the Output row. Source: `recording_cf368b42`, 167
canonical frames, measured in `acceptance\output-measures-recording_cf368b42.json`. The story accepts
recording-side evidence with provenance, or a named blocker. No manifest exists: the compiler needs a
registered causal capability, so `scenario_manifests` is 0 and the manifest half stays with SC-2273.

| Output element | Recording-side evidence | Status |
|---|---|---|
| Player tile and plane | 167/167 | Present, OBSERVED |
| Skills | 167/167 | Present, OBSERVED |
| HP, prayer, run energy | Present in `playerState` | Present, OBSERVED |
| Inventory and equipment | 167/167 | Present, OBSERVED |
| Bank contents | `replayCapture.bank.status=incomplete`, bank not open | Named blocker: not observed; SC-2273 |
| Varps and quest state | `replayCapture.questVars` 167/167 | Present for the captured subset; full hidden state is G1, SC-2273 |
| Task state | `taskState.stage=UNKNOWN` | Named blocker: producer records no task stage |
| NPC and Fishing-spot state | 167/167; `spotId` on 334/334 spot rows | Present, OBSERVED |
| Objects and ground items | 167/167 | Present, OBSERVED |
| World and region | `worldId`, `regionId`, `plane` 167/167 | Present, OBSERVED |
| Collision and world-view reference | Pinned simulator content only | Named blocker: `world-number-mapping-20261104.md` |
| Seed and fixed-clock origin | Absent from a recording | Named blocker: compiler output, capability gate |
| Allowed semantic actions and gates | 1 `INTERACT_NPC_LURE` event; `semanticSnapshot.legalActionIds` | Present, observed |
| Stop, success, failure, limits | Terminal frame, explicit stop; limits are compiler output | Partly present; limits blocked by the capability gate |
| Source evidence mapping | `runContext`, `contextHash`, `sourceExecutionRefs`, `contentHash` | Present |
| Provenance labels | Frame origin HUMAN, CLIENT_EVENT; manifest labels are compiler output | Partly present; labels blocked by the capability gate |
| Content checksum, rejection list | Frame `contentHash`; 9 refusal cases byte-identical | Present |

## No raw input dependency

Top-level `inputs` is absent from 167/167 frames, nested `replayCapture.inputs` is empty in 167/167,
and no `packets` field is present. The manifest and conversion packages read neither `replayCapture`
nor `inputs`.

## World mapping (P3.4)

The capture records `worldId` 380 and `regionId` 12341 on every tick. No live-to-simulator mapping
exists and none was invented; the blocker stands.
