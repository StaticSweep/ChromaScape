# SC-1664 Shortcut update

SC-1664 implementation is complete for review, but the story must remain review-required rather than
Done until the PR is approved and merged. The target-owned lifecycle, exact identity/generation
fencing, bounded RemoteInput calls, lease ownership, cleanup semantics, KInput adapter and controller
integration are implemented on the merged SC-1662/SC-1663 base.

Deterministic unit, isolation and focused launcher/discovery gates pass (213 tests, one expected
Windows-only skip). The ordinary and replay suites introduce no stable failure identity versus the
exact base. Live disposable-client Linux qualification and Windows runtime verification remain
explicitly NOT RUN; if those are waived for this proof of concept, record the waiver in Shortcut and
keep attachment disabled by default. Do not represent a waiver as a PASS.
