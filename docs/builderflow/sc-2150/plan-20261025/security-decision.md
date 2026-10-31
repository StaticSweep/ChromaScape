# SC-2150 Remaining Security Decision Record

Status: proposed implementation decision; no enforcement or qualification is claimed by this plan.

### Security decision record

- Server sessions and structured provenance are chosen over a reason-string-only fix because
  dataset integrity survives exports, browser reloads and direct API calls.
- Legacy evidence remains immutable/UNKNOWN, not rewritten as independent. Outside-application
  viewing cannot be proved absent; annotation attestations and contamination ledgers remain explicit.
- Same-origin principal/nonce protections remain on new mutation paths. Trusted corpus manifests
  are operator inputs; caller metadata never grants dataset policy privileges.
- Acquisition egress is isolated from decoding; host/network provisioning is an explicit operator
  prerequisite. No cookies, authentication bypass or machine credential capture is introduced.
- Hard memory/swap, process, disk/time/output limits and publication fencing remain required on
  every supported host. Failed verification disables the affected capability.

## 10. P7: constrained product YouTube acquisition

Primary files: `MouseVideoYouTubeAcquirer`, `MouseVideoProcessRunner`, `MouseVideoImportService`,
configuration/capabilities and a new acquisition-specific launcher interface. Reuse immutable
source registration, digest-bound request identity, job leases, cancellation and quota accounting.

1. Turn the existing proposal into a concrete approved tool/runtime/public-clip/egress manifest.
   The existing yt-dlp 2026.08.19 hash is recorded; verify signatures and exact CLI compatibility
   locally. Pin any needed JavaScript runtime independently. No unattended latest-version install.
2. Add a separate acquisition sandbox. On Linux, provision an operator-owned network namespace
   whose only reachable service is the filtering proxy. Do not enable networking on the decoder
   sandbox and do not treat a downloader `--proxy` flag as enforcement by itself.
3. Proxy-only HTTPS CONNECT to approved provider/CDN hosts and port 443. Resolve on the proxy;
   reject loopback, RFC1918, CGNAT, link-local, ULA, multicast, metadata and rebinding destinations
   at connection time, including IPv4-mapped IPv6. Verify redirects and every subrequest route.
   Bound DNS/connect/read time, connections, total bytes and overall job resources.
4. Run a fixed server-owned argv with caller data limited to a canonical video ID. Verify the
   pin immediately before launch; expose no plugins, user configuration, cookies, browser
   profiles, netrc, credentials, arbitrary output paths or arbitrary executable flags. Validate
   every proposed flag against the pinned executable. A missing dependency fails closed.
5. Acquire bounded metadata first and refuse playlists, live/upcoming streams, unavailable and
   authentication-required material. Parse only whitelisted bounded fields; verify returned video
   identity equals the requested canonical ID. Do not trust provider filenames or stdout JSON.
6. Download to a generated workspace on a hard bounded volume. Bound per-file and aggregate
   bytes, duration and disk use. Accept only the expected complete media file, not a thumbnail,
   sidecar, symlink or `.part`. Hash exact downloaded bytes and retain acquisition time, provider
   ID/URL, selected format, downloader/runtime pins and reviewed source/use evidence.
7. Publish through the existing fenced import path. Retried requests do not duplicate sources;
   cancellations/lease loss stop the acquisition containment, prevent late publication and retain
   a truthful cancellation/reconciliation outcome. Keep provider errors sanitized and durable.
8. Qualify the real `/imports/youtube` route on the approved public clip: success, duplicate retry,
   private/removed/unavailable inputs, oversize, timeout, cancellation, address/redirect/DNS
   attacks, credential absence and cleanup. Rate-limits may use controlled fixtures rather than
   provoking a provider. Keep shipped `youtube-enabled=false` until all required evidence exists.

## 11. P8: native trace and Windows

### Native trace round trip

The owner operates the client/login and recording. Enable the existing frame-timing sidecar and
Micro6 trace sender only for the planned session. Verify the actual selected runtime delivers
trace to the intended recording, reconcile PTS/frame/trace clocks with ≤50 ms skew, and report
drops, duplicates and unknowns. Export and convert with `label_from_mouse_trace.py`; score a
known generated cursor path and compare exact ordinals. These are bot/crosshair regression
labels, not evidence of human mouse movement. Do not dispatch gameplay to manufacture a run.
Restore the prior sidecar/trace settings and stop only task-owned resources.

### Windows implementation if Windows remains required

Current restricted execution refuses Windows. A Windows smoke test alone cannot turn that into
support. Add a `MouseVideoExecutionContainment` platform interface and native Windows adapter
using JNA/Win32 APIs already available to the project:

- Create a Job Object before child execution; configure kill-on-job-close, job/process memory,
  active-process and tested CPU controls. Create the process suspended, assign it to the Job,
  then resume. Disallow descendant breakaway. Failed assignment fails before media work.
- Add a no-network AppContainer/lowbox execution profile with no network capabilities, explicit
  read-only runtime/input grants and only the task workspace writable. A restricted token alone
  does not establish the filesystem/network boundary. Do not inherit operator environment,
  account credentials, home/profile files or broad writable media-store access.
- Provide an operator-provisioned hard bounded job volume, for example a fixed-size per-job
  VHDX pool with private ACLs and temp on the same volume. A disk-usage polling loop is not a hard
  quota. Validate provisioning, exact mount identity and empty containment before reuse/dismount.
- Handle native command-line quoting and handles without a shell. Capture descendants, limits,
  cancellation, output drains, parent JVM death, stale leases and orphan volume cleanup with
  explicit receipts. Keep required readiness false when any enforcement cannot be demonstrated.
- Acquisition needs its own approved Windows proxy/firewall sandbox design; never reuse the
  no-network decoder token with unrestricted host networking to make YouTube succeed.

Run the HTTP/browser/preview/ownership/cancel/restart/resource/trace suite natively on Windows
with actual executable hashes, filesystem semantics and live parity. Linux evidence cannot close
Windows gates. If the host or provisioning is unavailable, record the external dependency and
leave that acceptance open; do not silently revise the supported-host promise or waive it.
