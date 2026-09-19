# Fedora live qualification evidence index

All retained files are redacted and hashed in `SHA256SUMS`. The final code revision is `f3f75e9e`;
the evidence commit follows it.

## Top level

| File | Contents |
|---|---|
| `fedora-primary.json` | Machine-readable check matrix for the primary host |
| `native-patch-declaration.json` | Documented local native patch (identity + reason + paths) |
| `fedora-primary/` | Curated redacted logs for each acceptance criterion |
| `junit-focused/` | Focused input/qualification JUnit XML (145 tests, 0 failures) |
| `junit-ordinary/` | Ordinary suite JUnit XML (2506 tests, 100 pre-existing failures, 14 skips) |
| `junit-replay/` | Replay suite JUnit XML (280 tests, 12 pre-existing failures) |
| `SHA256SUMS` | SHA-256 for every retained evidence file |

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
| `live-run.md` | Consolidated results and failure-identity deltas |
| `attachment-failure.md` | **Superseded** pre-fix failure baseline |

## Redaction

Usernames and machine-specific repository/mount paths are replaced with `<home>`/`<repo>` tokens or
omitted. No screenshots, full environment dumps, process memory, or compiled `.so` are retained.
