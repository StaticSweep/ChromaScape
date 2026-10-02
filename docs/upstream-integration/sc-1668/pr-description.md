# SC-1668: cross-platform qualification and final upstream integration

Shortcut: <https://app.shortcut.com/leeuwbot/story/1668>

Base revision: `ab8c4ccf43e09ce5d52b5e75a712e672984d1561`.

Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`.

## Integration story chain

- [SC-1661 — source reconciliation](https://app.shortcut.com/leeuwbot/story/1661)
- [SC-1662 — Linux native build/load](https://app.shortcut.com/leeuwbot/story/1662)
- [SC-1663 — process discovery/selection](https://app.shortcut.com/leeuwbot/story/1663)
- [SC-1664 — input attachment/lifecycle](https://app.shortcut.com/leeuwbot/story/1664)
- [SC-1665 — rendered-buffer capture](https://app.shortcut.com/leeuwbot/story/1665)
- [SC-1666 — recording/debugger/remote-launch](https://app.shortcut.com/leeuwbot/story/1666)
- [SC-1667 — Fedora startup/preflight](https://app.shortcut.com/leeuwbot/story/1667)
- [SC-1668 — cross-host qualification/delivery](https://app.shortcut.com/leeuwbot/story/1668)

## Summary

- closes the final SC-1661-owned code adaptations for pre-Spring AWT mode, host-native RuneLite
  profile resolution, and one-time script initialization;
- adds an atomic, fail-closed evidence contract for two distinct Fedora hosts and one Windows host;
- records final dispositions for all 15 upstream-only commits;
- provides the qualification and rollback procedures for the integrated SC-1662–SC-1667 surface.

## Verification

- focused SC-1668 Java regressions: PASS;
- SC-1668 evidence regressions: PASS;
- native metadata and Fedora preflight fixtures: PASS;
- combined input/capture/recording/launcher/startup regressions: PASS;
- ordinary and replay suites: inherited baseline failures only; exact counts in
  `verification-evidence.md`;
- Checkstyle: exits zero with inherited warnings; no SC-1668 warnings;
- Spotless: inherited offender set remains; no SC-1668 Java offender;
- whitespace and privacy scans: required before review publication.

## Required before merge

- Fedora primary live qualification: NOT RUN;
- Fedora secondary live qualification: NOT RUN;
- Windows live qualification: NOT RUN;
- strict three-host validator: BLOCKED until all three records are PASS at the same revision.

The earlier Fedora native build produced artifact SHA-256
`114bf72d2dc6d5b7000d24026188665cf56de7a069be947661a6c3786f3d0787`; each final Fedora host
must record the exact artifact it actually qualifies. Exact client versions are likewise pending
the live host records and are not inferred from the earlier build.

This PR must remain draft or review-blocked while any required live record is NOT RUN or FAIL. Unit,
fixture, and single-host checks do not waive the story's cross-host acceptance criteria.

## Rollback

Revert the SC-1668 merge commit. On Fedora, stop the identity-fenced owned process through the
SC-1667 lifecycle script before restarting the previous revision. On Windows, follow the canonical
restart runbook. SC-1668 adds no migration or wire-protocol change.
