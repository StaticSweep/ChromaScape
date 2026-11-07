# SC-274 / SC-276 follow-up review - 25 October 2026

Reviewed local HEAD a2da370e and the durability implementation introduced by 50781cd0.
Review only: no production-code edits, commits, pushes, WSL start, host resets or tracker writes.

## Delivered progress

The current code addresses the previous three findings: admission uses the root's JVM/OS
locks; marker publication does not replace a competing marker; missing/null/inconsistent
markers fail closed on populated roots; operations revalidate admission; new directories
are created individually with parent-force calls. Explicit legacy adoption remains DEVELOPMENT.

Windows retained XML reports 31 store cases, zero failures, four POSIX skips. The resolution
record documents Linux positives and the two-JVM race passing; that Linux run was not rerun
by this review. The three repository integration passes and native recovery ownership remain
valid prior progress. Power-loss behavior remains untested, separate from those unit results.

## New P1: reused orphan is not re-forced after failed object publication

In ImmutableArtifactStore.put, reuse of an existing blob verifies its bytes but does not
force its containing directory. publish(blob, content) can create that blob and then throw
when the directory force fails, intentionally retaining unreferenced content. The next put
then reuses that orphan and acknowledges a new manifest without completing the failed force.
Re-forcing only the existing manifest's directory does not cover this first-reference retry.

A fresh fault/call-order probe compiled the current store source under JDK 17 and recorded:

- firstPutRefused=true, ARTIFACT_DURABILITY_UNAVAILABLE;
- orphanRetained=true;
- retryReturnedManifest=true;
- blobDirectoryForcedDuringRetry=false.

Required fix: before acknowledging a reused object under STRICT, ensure its object-directory
persistence requirement is satisfied. Cover both the new-reference and existing-reference/
dependency paths wherever prior failed publication can be encountered. Preserve valid shared
blobs. Add failure-on-object-directory, repeated-failure and successful-retry regressions;
rerun target-platform store tests. Do not remove orphan retention merely to hide the gap.

Evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261025\remaining-review-20261024T232041Z`.
The copied product source is hashed in source.json. Probe source, compilation arguments,
logs and probe-results.json are retained. The UTC attempt name is on 24 October; local review
date is 25 October. The injected directory-force seam establishes ordering/acknowledgement
behavior, not physical power-loss persistence.

## Identity and authoritative runtime qualification are different gates

The certifiable identity validates committed/pinned inputs. The production descriptor still
combines isCertifiable(identity) with AUTHORITATIVE_RUNTIME_QUALIFICATION.complete, which
remains false. This is why the nine reported session cases fail despite the clean identity.
Keep the gate false until its requirements are actually implemented and reviewed.

The source itself identifies unfinished engineering work:

- runtime-module-guard.ts covers CJS; ESM/bootstrap/JSON/general reads and race-free loading
  are explicitly not fully qualified;
- runtime-closure-manifest.ts declares Node API denial, not OS sandbox qualification, and
  leaves module/read/resource, OS egress/cancellation and oracle acceptance gaps;
- authoritative-replay-capabilities.ts advertises CAST_SPELL without a method contract,
  has no explicit Fishing method, and deliberately keeps authoritative qualification false.

Stopping all local work at this gate is not justified. Locally actionable work includes:

1. Define the exact closure, platform, method and oracle acceptance matrix.
2. Implement verified-byte loading for supported paths and fail-closed refusal for unsupported
   ESM/dynamic/native/bootstrap paths; add deliberate violation and race tests.
3. Qualify the actual OS launch boundary with negative fixtures and implement measured
   resource/cancellation controls. Existing bubblewrap launch success is supporting evidence,
   not proof of all OS boundaries.
4. Complete Fishing/CAST_SPELL contracts and supported-method refusal/parity tests. Keep
   glassblowing unadvertised until its promised evidence exists.
5. Build the differential/oracle harness with provenance-pinned references and explicit
   missing-input failures. Genuine independent references remain an external prerequisite;
   authored fixtures must not be relabelled as independent fidelity evidence.
6. Replace the blanket qualification flag only after final input/closure/platform/method/
   oracle requirements are satisfied. No environment flag or caller assertion can substitute.

## Remaining work in priority order

1. Fix and regress the orphan-directory retry gap; retain Linux STRICT positives and run
   the actual-platform cases after the fix. Power-loss testing remains a separate open claim.
2. Implement the locally actionable closure/OS/method/harness work above. Do not wait for a
   new authorization merely to scope work already covered by the remaining implementation plan.
3. Re-run the nine failing session cases against qualified inputs; retain the one reported
   worker-only pass. Four real-client/provider cases still need their named prerequisites.
4. Finish reproducible Linux packaging, final resolver/cache/content closure and cold/warm/
   concurrent production budget/performance evidence.
5. Obtain released migration authority, sustained clock/renewal ownership, supported allocation,
   original-trust-approved bundle, genuine context/capture and authorized user participation.
6. Qualify paired Watch, observer noninterference and human sign-off; resolve scoped style
   requirements, freeze final pins, run the full matrix and obtain delivery authorization.

All original acceptance rows remain open. The most recent changes are local; this review
does not imply that they or the native candidate were pushed. Preserve the unrelated
SC-666 Gradle hunk, native branch and recovery bundle.
