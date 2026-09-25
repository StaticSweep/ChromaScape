# SC-1668 Shortcut update

Implementation is ready for review, but the story is not complete.

Draft PR: <https://github.com/ikiddoi/ChromaScape/pull/12>

Delivered:

- the final upstream-owned AWT startup, host-native profile-path, and first-cycle adaptations;
- a fail-closed three-host evidence format and eight regression cases;
- a two-Fedora/one-Windows operator runbook;
- a final disposition for all 15 upstream-only commits;
- tested rollback instructions and an integration PR checklist.

Local verification passes for focused Java, evidence-schema, native metadata, Fedora preflight,
and the integrated input/capture/recording/launcher surface. Ordinary and replay suites retain their
documented inherited failures.

Still required and never represented as PASS: live qualification on Fedora primary, a distinct
Fedora secondary host, and Windows. The strict validator remains blocked until all three records
reference the same application revision and every required case passes. Keep the story In Progress
and the PR draft/review-blocked until that evidence exists.
