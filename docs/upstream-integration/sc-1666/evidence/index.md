# SC-1666 evidence index

No native binaries, recordings, credentials, process command lines, machine-specific paths, or
generated build outputs are committed as evidence.

- `../implementation-plan.md` — frozen executed plan and scope.
- `../frame-routing-contract.md` — identity, lifecycle, cache, recording, and host-ownership rules.
- `../implementation-evidence.md` — implemented surface and compatibility statement.
- `../verification-evidence.md` — deterministic, baseline, and NOT RUN matrix.
- `../shortcut-story-update.md` — review-required Shortcut handoff text.

JUnit XML and the MP4 round-trip artifact remain generated locally. Only aggregate results and the
test identities needed for review are retained in source control.
