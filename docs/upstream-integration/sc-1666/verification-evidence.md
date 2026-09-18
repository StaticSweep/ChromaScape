# SC-1666 verification evidence

| Verification | Result | Evidence |
|---|---|---|
| Base includes SC-1661 through SC-1665 | PASS | integration merge `c734e23d` |
| Host-qualified routing identity | PASS | `FrameSourceBindingTest` |
| Same PID on different hosts cannot collide | PASS | `FrameSourceBindingTest`, `RecordingSourceFenceTest` |
| Selection-generation transition fencing | PASS | `UnifiedFrameCaptureSourceFenceTest` |
| Anonymous frame cannot bypass a bound source | PASS | `UnifiedFrameCaptureSourceFenceTest` |
| Windows capture selection race refuses closed | PASS (deterministic) | `ScreenManagerWindowFrameCaptureTest` |
| Initial-frame readiness pins source | PASS | `RecordingServiceInitialFrameReadinessTest` |
| Recording rejects a displaced source | PASS | `RecordingSourceFenceTest` |
| Actual MP4 start/encode/stop/finalize | PASS | `RecordingServiceRoundTripTest`; non-empty MP4 |
| Existing recording/viewport/native/launcher regressions | PASS | 292 tests, 0 failures/errors, 1 Windows-only skip |
| Ordinary suite | BASELINE | 2,425 tests, 111 established failures, 14 skips; eight then-current SC-1666 additions all passed |
| Replay suite | BASELINE | 280 tests, same 13 established environment/platform failures |
| Candidate formatting | PASS | Google Java Format 1.17 |
| Repository Checkstyle task | PASS with baseline warnings | task exit 0; zero warnings in changed production files |
| Whitespace | PASS | `git diff --check` |
| Live two-client/two-host routing | NOT RUN | requires qualified live clients on both Fedora hosts |
| Live remote Builder Goal capture readiness | NOT RUN | no live remote launch used for this implementation pass |
| Windows runtime regression | NOT RUN | deterministic fencing covered; execution deferred to Windows |
| PR review and merge | REVIEW REQUIRED | [PR #10](https://github.com/ikiddoi/ChromaScape/pull/10) is open; merge NOT RUN |

The focused total contains all nine SC-1666 tests. The ordinary suite was run immediately before
the final round-trip test was added; that test then passed both alone and inside the final focused
gate. The ordinary suite remains red for the established PostgreSQL/Testcontainers/Spring-context,
contract-fixture, Windows-only process-launch, and real-process timing failures. No SC-1666 test
failed. The replay failure identities and count match the SC-1665 baseline.
