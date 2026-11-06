# R8.4 gap B — required-mode patch applicability — 2026-10-25

The SC-2106 R8.4 audit (`micro6/docs/builderflow/sc-2106/r8-4-managed-tests-fail-not-skip-audit-2026-10-23.md`)
identified one in-slice check that skipped instead of failing:
`PatchCandidateWorkflowServiceTest#canonicalCandidatePatchAppliesToConfiguredMicro6Checkout`.

## Change

- The test now reads `sc968.required`. When `SC968_MICRO6_VERIFY_ROOT` is absent and the
  property is true, it fails with `SC968_MICRO6_VERIFY_ROOT is required in required mode`.
  The ordinary suite still skips, so it stays portable.
- New required task `:sc968PatchApplicabilityTest` sets `sc968.required=true`, fails on zero
  matching tests, and fails if any test is skipped.

## Verification

With the checkout configured:

```
SC968_MICRO6_VERIFY_ROOT=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/worktrees/micro6-sc2104-sc2106 \
  ./gradlew --offline :sc968PatchApplicabilityTest \
  -Psc967VectorsPath=<contracts>/sc967/ed25519-vectors.json
```

`BUILD SUCCESSFUL` — 13 tests, 0 failures, 0 skips.

Without the variable:

```
env -u SC968_MICRO6_VERIFY_ROOT ./gradlew --offline :sc968PatchApplicabilityTest --rerun ...
```

`BUILD FAILED` — `canonicalCandidatePatchAppliesToConfiguredMicro6Checkout` failed at
`PatchCandidateWorkflowServiceTest.java:261`; exit 1.

## Remaining R8.4 gap

Gap C is unchanged: the managed R4/R5/R6 tests do not exist until SC-1914/SC-1916/
SC-279/SC-1991 deliver their slices. R8.4 therefore stays partial even though every
existing in-slice check now fails closed.
