# SC-1665 verification evidence

This matrix distinguishes deterministic verification from live-client and host-specific checks.

| Verification | Result | Evidence |
|---|---|---|
| Integration base contains SC-1662 through SC-1664 | PASS | Base `6b17b722` |
| Java compilation | PASS | JDK 17 focused Gradle run |
| BGRA pixel/orientation/dimension conversion | PASS | `RemoteInputFrameDecoderTest` |
| Detached BufferedImage/Mat round-trip | PASS | `FrameImageAdapterTest` |
| Invalid dimension and malformed-buffer bounds | PASS | decoder and session tests |
| Null image buffer refuses capture | PASS | `RemoteInputSessionTest` |
| Resize during copy refuses capture | PASS | `RemoteInputSessionTest` |
| Freshness state transitions | PASS | `FrameFreshnessTrackerTest` |
| Runtime source ownership | PASS | `TargetFrameRuntimeTest` |
| Screen-space crop compatibility | PASS | `ScreenManagerRenderedBufferTest` |
| Linux facade with missing Win32 libraries | PASS | `ScreenManagerRenderedBufferTest` |
| Focused regression suite | PASS | 174 tests, 0 failures, 0 errors, 1 platform skip |
| Candidate formatting | PASS | Google Java Format 1.17 dry-run on every changed Java file |
| Candidate Checkstyle | PASS | no warning names a changed SC-1665 file; repository baseline remains noisy |
| Whitespace | PASS | `git diff --check` |
| Ordinary regression identity | PASS | 2,417 tests / 111 failures / 14 skips; 16 added tests pass; no SC-1665 failure |
| Ordinary timing-failure comparison | PASS | the one count delta reproduces on exact base `6b17b722` |
| Replay regression identity | PASS | 280 tests / same 13 established environmental failures |
| Covered/minimized live client capture | NOT RUN | no qualified attached client used yet |
| Paused live rendering classification | NOT RUN | requires the same qualified client |
| Windows runtime verification | NOT RUN | deferred to Windows |
| PR review/merge | NOT RUN | implementation is still local and uncommitted |

No live or platform-specific criterion is marked PASS without execution in that environment.

The ordinary task remains red for the established unavailable Postgres/Testcontainers services,
contract-fixture drift, Windows-only process launch and one real-process cleanup timing assertion.
That timing assertion also fails when run alone from the exact integration base in a disposable
checkout, so it is not an SC-1665 failure identity. The 16-test increase over SC-1664's 2,401-test
candidate total is exactly the added SC-1665 regression set.
