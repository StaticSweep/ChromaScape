# SC-1668: cross-platform qualification and final upstream integration

Shortcut: <https://app.shortcut.com/leeuwbot/story/1668>

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

This PR must remain draft or review-blocked while any required live record is NOT RUN or FAIL. Unit,
fixture, and single-host checks do not waive the story's cross-host acceptance criteria.

## Rollback

Revert the SC-1668 merge commit. On Fedora, stop the identity-fenced owned process through the
SC-1667 lifecycle script before restarting the previous revision. On Windows, follow the canonical
restart runbook. SC-1668 adds no migration or wire-protocol change.
