# SC-2150 acceptance amendment — assisted-first release

Status: **approved — Route A (assisted-first)**. Decision recorded 2026-11-03 for R5 of the
[remaining plan](remaining-plan-20261102.md), on the strength of the executed receipts below.

## Approval

|Item|Value|
|---|---|
|Decision|**Route A — assisted-first release**, with formal independent detector qualification deferred|
|Decided by|Story owner (SC-2150), in the 2026-11-03 delivery session|
|Basis|The owner's standing decision “Switch to automatic assisted review” (2026-11-02) plus the executed R1.3/R2/R3 receipts listed under *Executed evidence*|
|Route B rejected|Independent qualification is not abandoned, it is deferred with named owners below; the paused 12-section manual campaign is not restarted|
|Explicit non-claims|Assisted suggestions are detector output, **not** human truth, **not** sealed labels and **never** training-eligible|

This approval changes no `qualified` flag, no held-out policy membership and no training admission.
Any future accuracy claim must name the candidate, profile, truth source and evaluation set.

### Executed evidence referenced by this approval

|Receipt|Path|
|---|---|
|Assisted execution, cancellation, recovery, quota|[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md)|
|Assisted UI interaction checks (R1.3)|same document, section “R1.3 assisted UI interaction receipt”; artifacts in `build/sc2150-closeout/r1-ui/`|
|Durable 8090 runtime and write smoke|[`r3-8090-restore-20261102.md`](r3-8090-restore-20261102.md)|

## Decision (as taken)

Ship SC-2150's external-video workflow as **assisted suggestions**, with formal independent
detector qualification deferred to a separately owned follow-up. Assisted output stays
machine-predicted, unsealed and ineligible for training admission.

Route B (retain the original independent qualification gate) was not selected; the third-party
blind annotation dependency therefore stays open but non-blocking for this release.

## Proposed scope

| Criterion | Proposed treatment | Basis |
|---|---|---|
| AC1 upload/source/proxy/index | Complete | Existing implementation plus the deployed smoke test in R3 |
| AC2 supported YouTube acquisition | **Deferred** — owner: story owner; target: the R6 follow-up after the four D3 pins exist | YouTube stays disabled (`youtubeEnabled=false`); see [`r6-youtube-status-20261103.md`](r6-youtube-status-20261103.md) |
| AC3 UUID/body idempotency | Complete | Existing import proof plus normalized automatic-analysis reuse in R1/R7 |
| AC4 decoder/resource bounds | Complete | [R2 measurement and quota receipts](verification-evidence-r2-20261103.md): 597 samples, 107.5 MiB workspace against the 1 GiB bound, `QUOTA_EXCEEDED` terminal at 32 MiB, containment 3/3 |
| AC5 cursor quality/determinism | **Assisted only** — owner: story owner; target: separately owned accuracy follow-up (median ≤ 4 px, p95 ≤ 12 px, absent false ≤ 1 %, cut-F1 ≥ 0.90, D-RECALL 95 %) | Deterministic assisted profile reproduced (597 samples, 592 visible, 5 ambiguous); independent accuracy is not claimed |
| AC6 hidden/unsupported/cuts/VFR | Complete as declared support | Unknown/unsupported outcomes remain explicit; claimed cases are limited to the documented template geometry |
| AC7 immutable manual review | Complete | 44 retained labels and their lineage preserved; assisted and human provenance stay distinct |
| AC8 training admission | Complete | Admission remains false by default; assisted data is not admitted |
| AC9 cancellation/restart fencing | Complete | [R2 cancellation and restart receipts](verification-evidence-r2-20261103.md): both cancel points settle `CANCELLED` with confirmed cleanup, the crashed claim becomes `NEEDS_RECONCILIATION` at generation 3 and only settles through the operator reconcile action |
| AC10 access/nondisclosure | Complete | Held-out prediction refusal (HTTP 403) and owner/access checks retained |
| AC11 no invented native facts | Complete | Video, trace, generated and human provenance remain separate through R4/R7 exports |
| AC12 usable catalog/browser/native regression | **Partially deferred** — browser/catalog half complete, native half open; owner: story owner (Micro6 login window); target: the next W12 window | Catalog and assisted navigation verified in a real browser ([R1.3 receipt](verification-evidence-r2-20261103.md)); the native regression waits for R4 ([`r4-native-status-20261103.md`](r4-native-status-20261103.md)) |
| AC13 exact source-frame mapping | Complete | Frame-index proof retained; changed UI and native alignment verified in R4/R7 |
| AC14 evidence-backed claims | Complete with revised claim | Suggestions are reported as unqualified predictions, never calibrated accuracy |

## Explicitly deferred follow-up (each with an owner)

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
