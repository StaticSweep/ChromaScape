# SC-2150 R6 — bounded YouTube route status (2026-11-03)

Status: **deferred**. The four owner decisions in
[`fa13-youtube-acquisition-proposal.md`](fa13-youtube-acquisition-proposal.md) are unanswered, so
nothing was installed, no network namespace or proxy was created, no downloader was run and the
product flag was not enabled.

## Observed configuration

|Check|Value|
|---|---|
|`GET /api/debug/mouse-videos/capabilities` (8090)|`youtubeEnabled=false`|
|`tools.youtubeDownloaderConfigured` (8090)|`false`|
|`CHROMASCAPE_MOUSE_VIDEO_YOUTUBE_DOWNLOADER` in `.env.local`|unset|
|`CHROMASCAPE_MOUSE_VIDEO_YOUTUBE_ENABLED` in `.env.local`|`false` (provisioned explicitly)|

Downloaded clips in the review corpus are historical data, not evidence for the product acquisition
route: they do not prove a permitted downloader, a pinned runtime, egress policy or failure handling.

## Unanswered decisions (the R6 blocker)

1. Downloader pin: tool, exact version, published digest and signature verification, install path.
2. Optional external JavaScript runtime pin (recent yt-dlp needs one for player challenges) — exact
   version and SHA-256, run inside the same sandbox.
3. Test clip choice and its provenance (LICENSE/attribution) for the supported-source import.
4. Host egress setup: the constrained namespace/proxy policy that lets exactly that download path
   work and fails closed everywhere else.

Without all four in writing, R6 cannot start: installing an unpinned downloader or runtime would
violate the story's own provenance rule.

## What is already implemented and tested

The bounded adapter (`MouseVideoYouTubeAcquirer`) exists, its argv pinning is expressed in the
proposal, and the disabled route already fails closed (`503`) with no job created — verified during
this round by the disabled-instance check in
[`verification-evidence-r2-20261103.md`](verification-evidence-r2-20261103.md).

## Owner of the deferred gate

The story owner, for the four D3 decisions. AC2 stays deferred and visibly open until they exist and
the route is proven; YouTube remains `false` in every deployed configuration.
