# SC-2150 Remaining Observability and rollout

### Observability

Add bounded counters for session create/refusal/seal, assisted exposure, Blind policy refusals,
save retries/conflicts/context mismatch, late-edit flushes, export/converter provenance refusals,
provider outcome/egress denial, containment cleanup and unqualified capability requests. Retain
existing job/intake/sweeper counters. Use stable low-cardinality labels; never label metrics by
source UUID, URL, actor or local path. Logs may carry correlated IDs in private diagnostics but no
credentials, raw frames, source URLs with secrets or tool stdout in public errors. Record save
latency, queued-edit age and review queue coverage without inferring reviewer performance.

Readiness exposes configured, installed, runnable, isolated and qualified separately for the
host, cursor/other methods and provider. It must not advertise a template's presence as an
accuracy certificate. Alerts/runbook actions cover repeated cleanup uncertainty, pending saves,
disk admission exhaustion, missing tools and a growing Felix outbox.

## 17. Rollout, rollback and final handoff

### Rollout

Apply the forward migration first. Deploy server support with qualification features off and
legacy inspection preserved. Deploy the new client only after additive responses/DTOs exist.
Run isolated/real-host qualification against the exact release candidate. Enable review-session
qualification for the test corpus, then qualified visual/provider capabilities only after their
own gates. Keep ordinary import, provider and training/control readiness independent; imported
evidence always retains `nativeTelemetry=false`, `exactTicksAvailable=false`, `controlAllowed=false`
and `trainingEligible=false` within this story.

### Rollback

Disable new review qualification and YouTube admission before stopping workers. Flush or visibly
retain active review drafts; drain jobs or leave durable fenced/reconciliation outcomes. Restore
the prior deployable client/server artifact, not a partial-tree checkpoint pretending to be a
release. Keep additive schema and historical bytes; do not reverse migrations destructively.
Prevent old clients from writing fake independent provenance. Reconcile stale queued identities
as `IDENTITY_CHANGED` when required; do not run them under a different method silently. Dismount
only verified empty owned work volumes and preserve referenced media/exports. Rehearse against
a disposable database/media copy and record exact rollback/restart checks on required hosts.
