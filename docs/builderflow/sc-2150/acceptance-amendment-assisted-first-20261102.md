# SC-2150 acceptance amendment — assisted-first release (draft for owner approval)

Status: **draft, not approved**. Prepared for R5 Route A of the
[remaining plan](remaining-plan-20261102.md). It does not close any criterion by itself.

## Decision requested

Approve shipping SC-2150's external-video workflow as **assisted suggestions**, with formal
independent detector qualification deferred to a separately owned follow-up. Assisted output
stays machine-predicted, unsealed and ineligible for training admission.

If this is not approved, R5 Route B applies: retain the original independent qualification gate
and leave the third-party blind annotation dependency open while manual labelling is paused.

## Proposed scope

| Criterion | Proposed treatment | Basis |
|---|---|---|
| AC1 upload/source/proxy/index | Complete | Existing implementation plus the deployed smoke test in R3 |
| AC2 supported YouTube acquisition | **Deferred** | YouTube remains disabled; R6 needs the concrete D3 decisions and a proven route |
| AC3 UUID/body idempotency | Complete | Existing import proof plus normalized automatic-analysis reuse in R1/R7 |
| AC4 decoder/resource bounds | Complete | Existing bounds refreshed for the 960 px / 10 fps assisted profile in R2 |
| AC5 cursor quality/determinism | **Assisted only** | Deterministic assisted profile reproduced and documented; independent accuracy is not claimed |
| AC6 hidden/unsupported/cuts/VFR | Complete as declared support | Unknown/unsupported outcomes remain explicit; claimed cases are limited to the documented template geometry |
| AC7 immutable manual review | Complete | 44 retained labels and their lineage preserved; assisted and human provenance stay distinct |
| AC8 training admission | Complete | Admission remains false by default; assisted data is not admitted |
| AC9 cancellation/restart fencing | Complete | Existing mechanics plus the R2/R7 refresh for the assisted profile |
| AC10 access/nondisclosure | Complete | Held-out prediction refusal (HTTP 403) and owner/access checks retained |
| AC11 no invented native facts | Complete | Video, trace, generated and human provenance remain separate through R4/R7 exports |
| AC12 usable catalog/browser/native regression | **Partially deferred** | Catalog and automatic navigation ship; the real native regression waits for R4 |
| AC13 exact source-frame mapping | Complete | Frame-index proof retained; changed UI and native alignment verified in R4/R7 |
| AC14 evidence-backed claims | Complete with revised claim | Suggestions are reported as unqualified predictions, never calibrated accuracy |

## Explicitly deferred follow-up

1. Independent, source-separated detector accuracy under the frozen candidate and the agreed
   gates (median ≤ 4 px, p95 ≤ 12 px, absent false ≤ 1 %, cut-F1 ≥ 0.90, D-RECALL 95 %).
2. The real native recording/trace round trip (R4) and its native regression receipt.
3. The bounded YouTube product route (R6), including the D3 pins/runtime/clip/egress decisions.
4. Any claim that assisted suggestions are human truth, sealed labels or training-eligible data.

The deferred items require an owner, a target milestone and retained visibility; they are not
silently dropped.

## Conditions of the amendment

- `qualified` capability flags, held-out policy membership and training admission do not change.
- The withdrawn recording group `yt-NTe3PTTBhuU` stays excluded from independent evaluation.
- The assisted profile, template pack and digests are recorded for every shipped analysis.
- Any future accuracy claim names the candidate, profile, truth source and evaluation set.
