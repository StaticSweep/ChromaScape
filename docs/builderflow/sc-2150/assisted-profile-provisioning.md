# SC-2150 assisted analysis profile and cursor-template provisioning

Status: implemented for R1 of the
[remaining plan](remaining-plan-20261102.md). This document describes the effective
assisted-review profile and how a fresh runtime provisions the private cursor template. It
does not claim independent detector accuracy.

## Effective profile

The server owns every setting that changes analysis output. A newly scheduled analysis uses:

| Setting | Property | Assisted Fedora value |
|---|---|---|
| Algorithm | fixed | `visual/v1` |
| Cursor method | fixed | `cursor-template/v8` |
| Toolchain pin | `visual-toolchain-pin` | `mouse-video-visual/1` |
| Sampling rate | `visual-sample-fps` | 10 fps (assisted); 30 fps default |
| Sample width | `visual-sample-max-width` | 960 px (assisted); 480 px default |
| Frame cap | `max-visual-frames` | 3,600 |
| Cursor template | `visual-cursor-template` | private PNG, pinned by SHA-256 |
| Template pack | `visual-cursor-template-pack-id` | `clip-arrow/v1` for the assisted clip |
| Hotspot | `visual-cursor-hotspot-x/-y` | `0,0` |
| Storage bound | `max-job-disk-bytes` | 1 GiB total (assisted); 4 GiB default |

`GET /api/debug/mouse-videos/capabilities` reports this as `analysisProfile`, including the
resolved pack id, the template's actual SHA-256, the pinned digest, its provisioning state
(`NOT_CONFIGURED`, `AVAILABLE`, `MISSING`, `DIGEST_MISMATCH`) and the thresholds. Read that
report instead of assuming a request label describes the bytes.

The profile is part of the analysis identity: changing any listed setting or the template
content produces a different identity and a new analysis. Completed analyses and their
artifacts keep the identity they were scheduled under.

## Provisioning a template pack

The template PNG and the source media are private. Keep both outside Git; commit only this
document and the provisioning script.

1. Derive the template from an exact source frame and crop:

   ```bash
   python3 scripts/sc2150/cursor_template_pack.py seed \
     --source /private/path/source.mp4 \
     --ordinal 3 \
     --crop 1356,724,12,19 \
     --pack-id clip-arrow/v1 \
     --out /private/path/clip-arrow.png \
     --manifest /private/path/clip-arrow-provenance.json
   ```

   The manifest records the source SHA-256, frame ordinal, crop, hotspot, mask rule and the
   output digest. The reviewed mask is the white cursor body (every channel above 150 with a
   channel range below 45) plus its dark outline (maximum channel below 80); background is
   transparent.

2. Verify the provisioned bytes:

   ```bash
   python3 scripts/sc2150/cursor_template_pack.py verify \
     --template /private/path/clip-arrow.png \
     --manifest /private/path/clip-arrow-provenance.json
   ```

3. Configure the runtime in `.env.local` (see `.env.example`):

   ```dotenv
   CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_TEMPLATE=/private/path/clip-arrow.png
   CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_TEMPLATE_PACK_ID=clip-arrow/v1
   CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_TEMPLATE_SHA256=<digest from the manifest>
   CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_HOTSPOT_X=0
   CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_HOTSPOT_Y=0
   ```

4. Restart ChromaScape on the Fedora host (`scripts/start-fedora-dev.sh`) and confirm
   `analysisProfile.templateState` is `AVAILABLE` with the expected digest.

A missing file or a changed file under a pinned digest fails analysis scheduling with a clear
provisioning error instead of silently running without cursor suggestions or with other bytes.
With no template configured at all, the feature reports cursor tracking as unavailable and
analyses remain explicitly template-less.

## Request identity and reuse

- `POST /sources/{id}/analyses` accepts an optional `templatePackId`. Omitting it selects the
  configured pack; naming that pack is equivalent, so both callers reuse the same completed
  analysis. Naming any other pack is refused with `INVALID_REQUEST` rather than recorded as if
  different bytes were used.
- The request digest covers the source checksum, algorithm, resolved pack id, configuration
  digest and toolchain digest. An equivalent completed analysis is returned `READY`; a changed
  body under the same request identifier is a conflict.
- The template bytes are frozen into the content-addressed store at scheduling and re-verified
  before execution, so a later change at the configured path cannot alter queued work.

## Supported and unknown cases

The tracker is defined by geometry and contrast, not by a promise about every cursor theme or
video:

- Supported when the displayed cursor sprite is visible, at least roughly 12 opaque pixels at
  the analysis scale, and distinguishable from nearby interface art by motion.
- Reported `AMBIGUOUS` when the best match is below threshold or a distinct runner-up is within
  the ambiguity margin. Reported `UNSUPPORTED` when no template is configured.
- Unknown or unsupported: cursors that are fully transparent, blended into a same-colour
  background, hidden behind opaque UI, below the renderable scale, or replaced by OS-scaled
  themes not represented by the provisioned template. A different theme or scale needs its own
  reviewed template pack and identity.

## Existing assisted pack

The current assisted clip uses `clip-arrow/v1`:

- Source: `90c0ef48-73a4-466d-9f83-93c2bef43e2f`, original digest
  `sha256:b3a226568aaa0fb67261414c3fcea157a1bf7ce0df05fa468db2e95ad7b7e34d`.
- Source frame ordinal `3`, crop `[1356,724,12,19]`, hotspot `[0,0]`.
- Template SHA-256 `14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`.

Seeding that frame and crop with this script reproduces those template pixels and that digest
(verified locally). The frozen W10 qualification template files are separate and unchanged.
This pack was selected during assisted review; it is not an independent accuracy claim.
