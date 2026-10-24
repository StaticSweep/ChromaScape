# SC-274 / SC-276 candidate publication review - 24 October 2026

## Scope

The user authorized publishing the entire SC-274/SC-276 candidate, including its compiler
dependencies, as a review branch. Publication is not a merge, deployment, acceptance closure
or qualification of the remaining external gates. Felix/Shortcut remain explicitly skipped.

The candidate is based on c791c6d47102bf5971497ab5ece5f48883b79b82. Reviewed follow-up scope:
artifact publication, model error mapping, registered-model and provenance fixtures, fresh-JVM
cleanup and the candidate dependency closure. Existing source/work was preserved. A separately
labelled SC-666 Gradle task is excluded from this publication and retained in the worktree.

## Windows durability remains open

The compatibility fix permits normal Windows publication but catches AccessDeniedException
from the directory force. The file's force occurs **before** Files.createLink. There is no
successful post-link force on this path, and its operating-system check does not constrain
the filesystem to NTFS. Passing ordinary tests does not establish crash/power-loss durability.

The source Javadoc has been corrected to state that limitation. NTFS journaling alone does
not establish that the new link reached stable storage before this method returned.
Microsoft documents cached metadata and explicit flushing/write-through requirements in
[File Caching](https://learn.microsoft.com/en-us/windows/win32/fileio/file-caching).
Java's [FileChannel.force contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
does not make the earlier force a guarantee about a later link operation.

Before production qualification: choose and implement a supported durable publication path,
or explicitly approve a narrower platform/acceptance contract. Preserve POSIX failure behavior;
do not represent this Windows compatibility branch as proven fail-closed durable publication.

## Verification record

Fresh clean-checkout results and remaining gates:
[publication-result-20261024.md](publication-result-20261024.md).

Existing retained JUnit records show artifactCompletionTest 61/61 and fresh-JVM hardening
6/6. Follow-up records report active-model 19/19 and dataset-builder 4/4. Fresh publication
verification is recorded separately under:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024`.
Only the final publication result may claim a fresh rerun or successful remote push.

The pre-publication scan checked 175 candidate files against 18 current sensitive values
from known local configuration/protected storage: no matches and no files over 5 MB.
Ignored environment files and the external protected store are not publication inputs.

## Remaining work

1. Qualify Windows durability; normal-operation green tests do not close this issue.
2. Reproducible package inputs, retained dirty/untracked source provenance, portable dependency
   acquisition, absent owned compiler mirror and the historical 27-path recovery boundary.
3. Remaining advertised native/compiler parity and provenance/privacy/state gates.
4. OS sandbox, full scenario cancellation/CPU/memory/time limits and production throughput;
   closure CLI load/deadline reproducibility remains open.
5. Released migration authority and actual Flyway upgrades/data-preservation/recovery.
6. Sustained clock synchronization, operational ownership and full renewal/expiry/fencing.
7. Supported allocation, original-trust-approved exact-artifact bundle or amendment,
   genuine profile/context/stabilization/capture and authorized login as required.
8. Paired package/Watch/noninterference, human sign-off, final frozen-input matrix and
   separately authorized rollout. Repository-wide Spotless debt remains recorded.

No original acceptance row is closed. Prior failed verification attempts are retained.

## Rollback

Review publication can be superseded by a follow-up commit; never force-push or overwrite
remote history. No shared service, database, trust root or replay-consumer change is part
of publication. Preserve current resources, evidence, fences and unrelated user work.
