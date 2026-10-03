# FEDORA-03 implementation evidence

## Changed files

| File | Purpose |
|---|---|
| `scripts/fedora/certify-live-recording-debugger.sh` | Live qualification harness: private non-root install, native pair overlay, disposable Postgres, packaged runtime with the qualification controller, explicit selection, and the 20-case matrix with fail-closed records |
| `scripts/qualification/fedora03_evidence.py` | Fail-closed FEDORA-03 record contract and release gate (exact versions, package/native hashes, patch identity, package revision binding) |
| `scripts/qualification/ws_probe.py` | Dependency-free RFC 6455 probe for `/ws/viewport` frames and debugger messages |
| `scripts/qualification/tests/test_fedora03_evidence.py` | 11 contract regressions (fail-closed init, unknown/missing check refusal, complete gate, revision/hash validation, atomic writes) |
| `scripts/qualification/tests/test_ws_probe.py` | 8 probe regressions (RFC accept digest, handshake, frame parser, masking, truncation) |
| `scripts/fedora/tests/live-recording-debugger-regressions.sh` | 19 harness regressions (evidence CLI, probe failure, argument refusals) |
| `build.gradle.kts` | `fedora03LiveQualificationTests` task |
| `.gitignore` | whitelist the four new tracked scripts |
| `docs/upstream-integration/fedora-03/**` | plan, runbook, rollback, this evidence, verification evidence, story update |

No Java source, database migration, configuration default, security setting or shipped package
payload was changed. The native pair remains out of the package; the provenance marker stays
`unresolved`.

## Tests

| Command | Result |
|---|---|
| `bash scripts/fedora/tests/live-recording-debugger-regressions.sh` | 19 passed, 0 failed |
| `python3 -B -m unittest discover -s scripts/qualification/tests -t .` | 37 passed, 0 failed (FEDORA-03 + FEDORA-02 + SC-1668 contracts) |
| `bash -n scripts/fedora/certify-live-recording-debugger.sh` | clean |
| `bash scripts/fedora/tests/packaging-regressions.sh` | unchanged FEDORA-01/02 suite (run in the verification pass) |
| `./gradlew --offline fedora03LiveQualificationTests fedora02EvidenceTests fedoraPackagingRegressions` | see verification evidence |
| Live matrix on the installed tree | 17 PASS, 3 NOT_RUN, 0 FAIL; record `VALID`, release gate correctly refuses the 3 NOT_RUN cases |

## Remaining work

- Debugger tick session, recording link and session video export need the Micro6 client connected to
  ChromaScape's tick stream (Builder Goal / remote-launch qualification follow-up).
- Capture and recording were qualified against the rendering login screen; an in-game session should
  be repeated on a logged-in client.
- The recording lane's duplicate-frame accounting (4 frames over ~45 s while the viewport delivered
  ≈11 fps) should be reviewed by the recording owners.
- The main `micro6` checkout still needs the `component` plugin port and the ANTLR plugin
  dependency committed on its own branch so the canonical launch works without a sibling worktree.
