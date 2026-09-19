# Fedora live qualification — primary host final run

> Supersession note (2026-09-19 remediation): the run below was executed against native patch
> `sha256:a7744da1c54a5646b975d9b7adce5891832394b1d84104bb8009ade551107040`. The source patch has
> since been extended with JNI local-reference cleanup for rejected window candidates (current
> identity `sha256:7af654074906652c1bff730071978e56f1e0c6846f217962eedf7196bc9661e6`); the native
> pair for that identity has not been rebuilt, so the PASS claims below describe the superseded
> pair only.

## Identities

| Item | Value |
|---|---|
| Application revision | `f3f75e9e` (final code commit; evidence commit follows) |
| Native source | pinned `5ea3b995f5d13e19c592a6399973fe7dadb5e656` + local patch `sha256:a7744da1c54a5646b975d9b7adce5891832394b1d84104bb8009ade551107040` |
| Library SHA-256 | `b0c8d3655876dd23e931a9df48efc87b96f936758e5a18a27988d9ab49a5fefd` |
| Manifest SHA-256 | `8e9a00cb76c826061dbd2e79e3245c622ade98dfe405277da8ae0c8cb67001f3` |
| Pair identity | `43b38e8f95d8f2dcf2d0ee4dd3ffb86d3cffd2fa975cca4db27970c4d1b03e9d` |
| Host | Fedora Linux 44, kernel `6.19.10-300.fc44.x86_64`, x86-64 |
| Session | GNOME / Wayland (`DISPLAY=:0` via XWayland) |
| JDK | `openjdk 17.0.20.1` (Corretto) |
| Client | Micro6 `microbot-1.8.9.3.jar`, `net.runelite.client.RuneLite`, RuneLite `1.11.8-SNAPSHOT` |

All paths are shown relative to `<workspace>`; personal absolute paths and usernames are redacted.

## Verified results

| Check | Result | Evidence |
|---|---|---|
| Native pair build + repeat build | PASS | `native-artifact.log` (byte-identical repeat) |
| Native metadata tests | PASS | 47/47 |
| Native stage diagnostics | PASS | `native-stage-diagnostics.log` (`reflection created`) |
| Preflight (attachment on) | PASS | `SC1667_PREFLIGHT status=READY errors=0 warnings=0` |
| Focused Java tests | PASS | 145/145 |
| Ordinary suite | PASS (no new failures) | 2506 tests, 100 pre-existing Postgres/Testcontainers failures, 0 in input/qualification |
| Replay suite | PASS (baseline) | 280 tests, 12 pre-existing Docker failures |
| Checkstyle | PASS (no new warnings) | changed production files clean |
| Spotless | PASS (changed files clean) | changed files not in the offender set |
| App boot | PASS | `Started ChromaScapeApplication`; `/debugger` 200 |
| Loopback binding | PASS | `ss` shows only `127.0.0.1:8090` |
| Discovery + selection | PASS | `attachment.log` |
| Attachment | PASS | `attachment.log` |
| Capture (3 frames) | PASS | `capture.log` |
| Bounded input | PASS | `input.log` |
| Release | PASS | `release.log` |
| Repeated attachment (3x) | PASS | `repeated-attachment.log` |
| Target restart fencing | PASS | `target-restart.log` |
| Resize / minimize | PASS | `resize-minimize.log` |
| Multiple clients | PASS | `multiple-clients.log` |

## Failure-identity deltas

The ordinary suite fails only in pre-existing PostgreSQL/Testcontainers integration classes
(`IllegalStateException`/`DockerClientProviderStrategy`); none are in
`com.chromascape.utils.core.input.*` or `com.chromascape.web.qualification.*`. The replay suite fails
only in the same Docker-dependent classes (12), matching the recorded integration baseline. This
story introduces no new failure identities.
