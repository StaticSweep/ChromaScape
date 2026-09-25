# SC-810 Phase 5b — `:client:check` (2026-09-11)

## Result: `BUILD FAILED in 11m 15s`. Not called green — documented honestly per this plan's own instruction.

Command: `gradlew.bat :client:check --no-daemon`, native Windows, from `micro6-sc810-integration`
at commit `95e7bd20da618f0c4b4919b0009ca26f1f53e5a6`.

## `checkstyleMain` — FAILED (real parser error, not a style violation)

```
[ant:checkstyle] AiRoutineCapturePlugin.java:408:12: unexpected token: switch
```
The Checkstyle tool version bundled with this build cannot parse a modern Java `switch`
expression at that line — a grammar/Java-version incompatibility in the codebase's existing
tooling, not a style issue and not something introduced this session (no file in this path was
touched). Everything after that parser error in the same task's plain-text output is a large
list of ordinary style findings (`UnnecessaryImport`, `SystemPrintln`, `UnusedPrivateMethod`,
etc.) across many unrelated files — pre-existing, informational, not examined line-by-line here
since the task already fails outright on the parser error regardless.

## `checkstyleTest` — FAILED (same class of parser error)

```
[ant:checkstyle] HarnessReportIntegrityTest.java:216:14: unexpected token: matrixNode
```
Same root cause: Checkstyle's parser rejects a construct in this test file it doesn't
understand syntactically.

## `pmdMain` — 17,503 violations, does not fail the build

`runelite-client/build.gradle.kts`'s `pmd { isIgnoreFailures = true }` (set project-wide,
pre-existing) means PMD is informational only by design here. 17,503 violations across a
~275k-line legacy fork (RuneLite + microbot) is a pre-existing backlog, not something in scope
to triage or fix under SC-810. Full report: `runelite-client/build/reports/pmd/main.html`
(not committed — build output).

## `:client:test` — FAILED

Same 5 failures as the standalone `:client:test` run, same root causes — see
`closure-plan-phase5-verification-20260911.md`. `Sc810RealBundleVerificationTest` still passed.

## Assessment

None of `:client:check`'s three failure sources are fixable within SC-810's repository-hygiene
and dispatch-readiness scope, and none touch Fishing Training, source-bundle
generation/signing, or the real-bundle verification test:

- The two Checkstyle parser errors are a tooling/Java-version compatibility gap spanning the
  whole codebase.
- PMD is explicitly non-blocking by existing project configuration.
- The 5 test failures are the same pre-existing, already-classified schema-drift and
  Lombok-processorpath-wiring issues.

`:client:check` is FAILED and stays FAILED. This is reported as-is, not worked around, not
silently reduced in scope, and not claimed green — consistent with this phase's explicit
instruction.
