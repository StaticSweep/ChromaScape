# Fedora live qualification evidence index

All retained files are redacted and hashed in `SHA256SUMS`. The final code revision is
`9099768d` (`fix(native-tools): commit the executable modes the scripts require`, head of
`codex/fedora-live-qualification` at the time of the remediation evidence refresh); the evidence
commit follows it.

## Normalization

Retained JUnit XML contained trailing spaces and tabs inside captured test output, which made
`git diff --check` fail. Every XML file under `junit-focused/`, `junit-ordinary/`, and
`junit-replay/` was normalized with one deterministic rule: remove trailing spaces and tabs
before every line ending, preserving all other bytes, XML structure, failure messages, stack
traces, test names, timestamps, diagnostic output, and redaction markers. After normalization
all XML still parses and the suite totals are unchanged. Checksums were regenerated only after
normalization and redaction were final.

## Failure-identity comparison (2026-09-19 remediation)

Base (`integration/chromascape-consolidated-20260901` = `7ab2a807`) and candidate suites were run
on the same host, JDK (Corretto 17.0.20.1), SC-967 vectors, Gradle options, and Docker/Postgres
availability. Identities were extracted with `scripts/native/sc1668_failure_identities.py`
(suite, class, method, failure-or-error, exception type, hex-normalized first diagnostic line).
The deltas show introduced/resolved/unchanged sections; no introduced identity is attributable to
this story (none touches `input`, `remoteinput`, or `qualification`). Classification of each
section is documented in `../verification-evidence.md`.

## Top level

| File | Contents |
|---|---|
| `fedora-primary.json` | Machine-readable check matrix for the primary host (superseded-pair note added) |
| `native-patch-declaration.json` | Documented local native patch (identity + reason + paths) |
| `fedora-primary/` | Curated redacted logs for each acceptance criterion |
| `junit-focused/` | Focused input/qualification JUnit XML (145 tests, 0 failures) |
| `junit-ordinary/` | Ordinary suite JUnit XML (2506 tests, 100 failures, 0 errors, 14 skipped) |
| `junit-replay/` | Replay suite JUnit XML (280 tests, 12 failures, 0 errors, 0 skipped) |
| `ordinary-base-failure-identities.txt` | Base ordinary failure identities (61) |
| `ordinary-candidate-failure-identities.txt` | Candidate ordinary failure identities (99) |
| `ordinary-failure-identity-delta.txt` | Ordinary introduced/resolved/unchanged sections |
| `replay-base-failure-identities.txt` | Base replay failure identities (15) |
| `replay-candidate-failure-identities.txt` | Candidate replay failure identities (12) |
| `replay-failure-identity-delta.txt` | Replay introduced/resolved/unchanged sections |
| `SHA256SUMS` | SHA-256 for every retained evidence file |

Note: `junit-ordinary/` and `junit-replay/` XML attribute counts read as
`tests skipped failures errors` (Gradle's attribute order). The earlier review that reported
"14 failures / 100 errors / 0 skipped" transposed the attributes; the element counts (100
`<failure>`, 0 `<error>`, 14 `<skipped>`) and both runs on this host confirm the totals above.

## Primary-host logs

| File | Contents |
|---|---|
| `environment.md` | Host, kernel, session, JDK, client, and artifact identities |
| `native-artifact.log` | Patched pair hashes, repeat-build, staging, and verification |
| `native-stage-diagnostics.log` | Pre-fix failure and post-fix `reflection created` stage lines |
| `x11-window-inventory.log` | The two target-owned windows and their class/state |
| `attachment.log` | Discovery, selection, and successful session open |
| `capture.log` | Three bounded frames with freshness transitions |
| `input.log` | Bounded mouse/keyboard input and held-state verification |
| `release.log` | Successful native release and clean session close |
| `repeated-attachment.log` | Three open/capture/close cycles |
| `target-restart.log` | Old identity fenced; new identity attached |
| `multiple-clients.log` | Deterministic two-client discovery and explicit selection |
| `resize-minimize.log` | Bounded capture across resize and iconify/restore |
| `live-run.md` | Consolidated results and failure-identity deltas (superseded-pair note added) |
| `attachment-failure.md` | **Superseded** pre-fix failure baseline |

## Redaction

Usernames and machine-specific repository/mount paths are replaced with `<home>`/`<repo>` tokens or
omitted. No screenshots, full environment dumps, process memory, or compiled `.so` are retained.
