# SC-2150 — switch the current footage to automatic assisted review

Current follow-through: [full remaining delivery plan](remaining-plan-20261102.md).

On 2026-11-02 the owner stopped the manual qualification workflow and explicitly selected
**“Switch to automatic assisted review”**, acknowledging that this footage would no longer
be independent held-out truth.

## Scope and preserved evidence

- Current section: GrimsEpicGames s1, source `90c0ef48-73a4-466d-9f83-93c2bef43e2f`.
- Withdraw the whole recording group `yt-NTe3PTTBhuU` (all three sections) from the active
  independent corpus before exposing predictions. Other recording groups remain blind.
- Preserve the 44 saved manual labels and their revision history. Session
  `606b14b6-38af-4f99-a5ac-335f1b5b2416` was closed as `ABANDONED`, not sealed.
- A database snapshot, the original policy, the manual-session receipt and the withdrawal
  receipt are retained privately in `build/sc2150-assisted/`.
- Historical database corpus memberships remain unchanged. Removing this group from the
  active policy makes it unassigned and eligible only for `DETECTOR_ASSISTED` review; this
  does not reassign a held-out recording to the dev qualification corpus.

## Automatic output

The initial run used `visual/v1`, `cursor-template/v8` and the frozen `arrow` template
(`sha256:ac2e3337c770108440c868d03ad3e133c9ed56e83eb9743179634a19f49819fd`).
Automatic cursor positions remain machine predictions with visibility/confidence and frame
lineage. They must not be exported or reported as independent human ground truth.

The review runtime now loads the pinned cursor template and uses a 512 MiB per-job storage
budget (256 MiB for the bounded job filesystem, 256 MiB for private temporary storage). Its
former 128 MiB budget allowed only a 64 MiB job filesystem: 60 sample PNGs from this clip
alone occupied 3.7 MB, exceeding that filesystem's capacity over the full 1,790-frame clip.
The first analysis failed with `DECODER_FAILED`; original-media probing and a direct sample
decode both succeeded. The increased budget retains sandboxing and hard storage bounds.

On retry all 1,790 PNG samples were produced (102,574,284 bytes). The browser now follows
the verified server `forcedBlind` policy rather than an obsolete `heldout` marker in the
import description. The focused JS suite passes 38/38, including policy precedence and the
held-out fallback. A live check confirms another recording group still returns prediction
HTTP 403 and offers only `WITHOUT_SUGGESTIONS`.

That first full run completed but returned 1,790 ambiguous cursor observations because
calibration failed at 480 px analysis width. A separate 12-frame diagnostic at 960 px
calibrated successfully (source scale 1.297, 6/12 visible detections). No threshold or
frozen qualification input was changed. The assisted runtime now samples at 10 fps and
960 px width, with a 1 GiB total job storage bound, and retains the original arrow template.
These are assisted-review settings, not a new claim about W10 qualification.

The generic template's 960 px diagnostic included interface-icon false matches. It was
therefore replaced for this clip with a template extracted from source frame 3, near an
existing human-labelled cursor point, with transparent background and a dark outline mask.
Template SHA-256: `14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`;
crop `[1356,724,12,19]`, hotspot `[0,0]`. Derivation and private image artifacts are in
`build/sc2150-assisted/clip-arrow-provenance.json` and `clip-arrow.png`. The same 12-frame
diagnostic calibrated at source scale 1.1149 and returned 11 visible detections. These are
development diagnostics, not independently verified accuracy measurements. The generic
960 px full run was explicitly cancelled and confirmed settled before the template switch.
The frozen qualification template files and freeze document are untouched.

The workspace also exposes a **Generate automatic cursor track** action and a
**Show automatic suggestions** toggle. The action remains disabled for independent
held-out sources and while analysis is running. Corrections are optional.

## Completed assisted run

- Analysis `092bd3af-9059-4db8-92be-c65aafa41b8e`, job
  `ad731316-2d4c-4754-b51c-e2a324ccf93e`: `SUCCEEDED`, progress 100, no error.
- 597 samples across the 1,790-frame clip (step 3, about 10 Hz), 630 total observations.
- Cursor output: **592 VISIBLE suggestions, 5 AMBIGUOUS**. These are detector outputs,
  not 592 independently verified correct positions.
- First suggestion: source frame 0, `(1372, 725)`, detector confidence 0.9715.
- Full machine observations and summary are retained privately at
  `build/sc2150-assisted/{automatic-observations,summary}.json`.
- The 44 manual labels remain in their original abandoned session and revision history.

## Suggestion navigation

The owner found that ordinary single-frame stepping hid the marker on unsampled frames.
When automatic suggestions are enabled outside manual review, the frame controls now read
**Previous suggestion / Next suggestion** and step between actual visible predictions,
skipping unsampled and uncertain frames. With suggestions hidden they return to ordinary
frame stepping. No interpolated positions are fabricated. A status beside the preview
explains loading, missing samples, uncertainty and the current position/confidence.
Focused JS checks pass 39/39, including navigation across gaps and end boundaries.

The former 12-section blind labelling plan is paused by this decision. The dev rehearsal
does not constitute a completed W10 held-out evaluation. Any future independent evaluation
must exclude this withdrawn recording group and reassess corpus coverage before proceeding.
