# SC-2150 FA13 — YouTube acquisition: proposal awaiting approval

Status: **DRAFT, not approved.** Nothing in this document is installed, enabled or executed.
`mouse-video.youtube-enabled` stays `false` until the owner approves each item below.

FA13 (plan §11): "Given approved downloader pins/public clip and constrained egress, when YouTube
acquisition succeeds or fails, then provenance, stable outcomes, limits, cancellation and cleanup
are verified with no cookies/authentication bypass."

## 1. Downloader pin (needs approval)

| Item | Proposed value |
|---|---|
| Tool | yt-dlp standalone Linux build `yt-dlp_linux` (self-contained; no system Python packages) |
| Version | `2026.08.19` — the newest release older than two weeks on 2026-10-22 (2026.10.21 is one day old and is deliberately not proposed) |
| SHA-256 | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` (from the release's published `SHA2-256SUMS`, fetched 2026-10-22) |
| Install path | operator-owned, read-only, e.g. `/opt/chromascape/tools/yt-dlp-2026.08.19/yt-dlp_linux` (not under the repository or a user-writable directory) |
| Config | `mouse-video.youtube-downloader=<path>`, `mouse-video.youtube-downloader-sha256=58162f9b…230a` (the adapter already refuses to run on a hash mismatch) |

Before approval, also verify `SHA2-256SUMS.sig` against yt-dlp's published signing key, so the pin
does not rest on one HTTPS fetch alone. I have not done that.

**Additional runtime dependency to decide:** recent yt-dlp releases need an external JavaScript
runtime (for example Deno) to solve YouTube's player challenges. If 2026.08.19 needs one for the
test clip, that runtime needs its own exact version and SHA-256 pin, and must run inside the same
sandbox. This has not been checked yet. I will only check it after the pin is approved, because
checking means running the downloader.

## 2. CLI compatibility to verify after approval

The adapter (`MouseVideoYouTubeAcquirer`) passes exactly:
`--no-playlist --no-progress --no-cookies --no-config --no-exec --restrict-filenames
--max-filesize <maxUploadBytes> --merge-output-format mp4 -o <workspace>/acquired.%(ext)s -- <url>`.

Plan: run `yt-dlp_linux --version` and `--help`, assert every flag is accepted, then add a
fixture-backed test that pins this argv against the approved version. Note: `--merge-output-format
mp4` needs ffmpeg on the downloader's PATH. On Fedora that is the system `ffmpeg-free` build, which
can mux but has no libx264; muxing does not need an encoder.

## 3. Test clip and provenance (needs owner choice)

Recommendation: the owner uploads **one of their own cursor-free OSRS recordings** (for example
`recordings/recording_fa5228cb.mp4`, about 66 s of gameplay) to their own YouTube channel as
**public or unlisted**, after checking that no account name or email is visible in the clip. Some login
frames in these recordings do show an account email.

Why: the owner holds the rights, the expected content is known exactly, and the clip can be removed
afterwards. A third-party video would need a separate source and use rights review. The verification
records the video ID, acquisition time, the acquired bytes' SHA-256 and the provider metadata, and
asserts that provenance alone grants no human-input or training eligibility (AC8).

Also needed, for the failure branches: one removed or invalid video ID (unavailable), and one
private or members-only video (auth required, refused without cookies). Rate-limit handling stays
fixture-tested only. Provoking it live is not proposed.

## 4. Egress policy (needs approval and host provisioning)

Plan §11 requires egress control that the downloader cannot bypass. URL string checks alone are not
enough, because of DNS rebinding and downloader subrequests. Proposal:

1. **Separate acquisition sandbox.** The downloader runs under the existing restricted launcher
   (bwrap/systemd-run) in its own network namespace. Decoding and analysis keep **no network at all**.
2. **Proxy-only egress.** The acquisition namespace's only route is to a local filtering HTTPS proxy
   (for example squid with CONNECT-only ACLs). The downloader is given `--proxy http://127.0.0.1:<port>`.
   Even without that flag it cannot reach anything else, because the namespace has no other route.
3. **Proxy rules:**
   - CONNECT to port 443 only.
   - Host allowlist: `youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtu.be`,
     `*.googlevideo.com`, `*.ytimg.com`, `*.ggpht.com`.
   - The proxy resolves hostnames itself and refuses loopback, RFC 1918, CGNAT, link-local, ULA,
     multicast and `169.254.169.254`. This blocks DNS rebinding, because the IP check happens at
     connect time on the address actually used.
   - A bounded connection count and a total byte cap equal to `maxUploadBytes` plus overhead.
4. **No credentials.** No cookies, `.netrc` or browser profile are mounted. `HOME` is an empty
   tmpfs. The adapter's `--no-cookies --no-config` stays in place.
5. **Cleanup.** The workspace is removed on success, failure and cancellation. Cancellation kills the
   sandbox's whole process tree, which is already qualified for decoder children and must be
   re-qualified for the downloader.

Host changes needed (owner approval): install and configure the proxy, and create the network
namespace and route. None of this has been done.

## 5. Verification once approved (FI11/FT11)

- Pin: a hash mismatch is refused; the approved binary is accepted.
- Live, owner clip: success, one immutable source identity, exact SHA-256, retries without
  duplicates, and cancellation during the download with cleanup confirmed.
- Live failures: an unavailable ID returns `SOURCE_UNAVAILABLE`; a private or members-only video
  returns `SOURCE_AUTH_REQUIRED`, with no cookie fallback.
- Egress: from inside the sandbox, connections to 127.0.0.1, 10.0.0.0/8, 169.254.169.254, a
  non-allowlisted host, and a rebinding hostname that resolves to a private address are all refused
  and logged by the proxy.
- Limits: `--max-filesize` and the post-download size check both refuse oversize media.

## What the owner needs to answer

1. Approve or replace the pin `yt-dlp_linux 2026.08.19` / `58162f9b…230a`.
2. If a JavaScript runtime turns out to be required, approve pinning one (to be proposed with its hash).
3. Choose the test clip: own upload (recommended) or a named third-party video.
4. Approve the host egress setup in §4 (proxy plus network namespace), or name an alternative.
