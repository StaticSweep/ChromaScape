# SC-2150 Remaining API Contract

## 6. API contract and compatibility

Keep the existing authenticated owner scope and nonce/Origin guard on mutations. Anonymous
requests are 401; another owner's objects are nondisclosing 404. Public errors contain stable
codes and correlation IDs, not paths, tool output or credentials. The following are additive
changes; validate concrete DTO fields and reject unknown security-sensitive input fields.

| Route | Request / behavior | Success / important refusal |
|---|---|---|
| `GET /sources/{sourceId}/review-policy?analysisId=...` | owner-bound policy, range capabilities, eligible modes, reason codes; no predictions | 200; 404 wrong binding |
| `POST /sources/{sourceId}/review-sessions` | requestId, analysisId, frameIndexChecksum, mode, corpusManifestDigest, ordinal range | 201 new / 200 identical; 409 changed UUID body; 422 ineligible blind |
| `GET /review-sessions/{sessionId}` | immutable identity, state and latest acknowledged revision | 200; 404 cross-owner |
| `POST /review-sessions/{sessionId}/seal` | requestId and exact acknowledged revision/content digest | 200; 409 concurrent/unacknowledged version; 422 incomplete required provenance |
| Existing `POST /sources/{sourceId}/reviews` | existing fields plus sessionId, clientEditVersion, snapshotDigest, provenance envelope | 201 new / 200 identical; 400 malformed; 409 base/body/context conflict; 422 policy/geometry refusal |
| Existing frame/index/preview reads | blind-safe original frame evidence, context checks and bounded paging | existing 200/202 behavior; never a prediction overlay |
| Existing observation/artifact reads | server exposure policy shared by every prediction-bearing route | permitted dev/evaluator reads; 403 policy refusal with no prediction bytes |
| Existing `POST /sources/{sourceId}/exports` | optional `contractVersion=export/v2`, exact reviewRevisionId and sessionId | immutable new/reused export; refuses mismatched owner/source/analysis/session |

The review POST response adds `{sourceId, analysisId, sessionId, revisionId,
appliedSourceRevision, acknowledgedClientEditVersion, snapshotDigest, created}`. A repeated
request returns the same immutable acknowledgement, even if the global source later advances.
Do not calculate a new revision client-side by adding one to whichever response arrives last.

```yaml
openapi: 3.1.0
info:
  title: SC-2150 additive review-session contract
  version: review-session-v1
paths:
  /api/debug/mouse-videos/sources/{sourceId}/review-sessions:
    post:
      summary: Create an owner-bound immutable review context
      parameters:
        - {name: sourceId, in: path, required: true, schema: {type: string, format: uuid}}
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [requestId, analysisId, frameIndexChecksum, mode, startOrdinal, endOrdinal]
              properties:
                requestId: {type: string, format: uuid}
                analysisId: {type: string, format: uuid}
                frameIndexChecksum: {type: string}
                mode: {enum: [DETECTOR_ASSISTED, WITHOUT_SUGGESTIONS]}
                corpusManifestDigest: {type: [string, 'null']}
                startOrdinal: {type: integer, minimum: 0}
                endOrdinal: {type: integer, minimum: 0}
      responses:
        '201': {description: New pinned session}
        '200': {description: Identical idempotent creation}
        '401': {description: Principal required}
        '404': {description: Source or analysis not available to owner}
        '409': {description: Request identity conflict}
        '422': {description: Policy or bounds refuse session}
```

This excerpt and the route/DTO table define the planned additive changes; implementation must
expand the repository contract with full request/response/error schemas before client rollout.

### Export and converter changes

Keep old exports retrievable byte-for-byte. New `export/v2` identities include the exact revision
UUID, session identity, provenance digest and policy digest. Resolve an explicitly sealed revision
by ID rather than requiring it to be the latest global source revision. Add a bounded review
history/session filter so the client need not fetch all source history to find its own labels.

Update `scripts\sc2150\labels_from_review_export.py` to accept legacy inspection exports and
new session-backed qualification exports. Emit a new label schema version for provenance-rich
truth; do not silently change `sc2150-fa12-labels/v2` semantics. Held-out conversion requires an
eligible sealed WITHOUT_SUGGESTIONS session, matching actor/media/index/range/digests, complete
origin records and no assisted label lineage. A CLI flag or reason string cannot override this.
Legacy UNKNOWN remains usable for explicitly unqualified inspection/dev evidence only.

For a multi-session clip, assemble a hashed label-set index of immutable export/revision refs;
reject incompatible indices, overlapping conflicting labels, missing required ranges and mixed
protocol claims. Keep dropped/unlabelled/ambiguous counts explicit; never interpolate truth.
