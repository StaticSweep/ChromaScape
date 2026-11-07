# P8 clock, grant renewal and operating ownership — 29 October 2026

Local measurements and a runbook. No host time change, service restart, grant issuance, fence
reset or credential operation was performed.

## Measurements actually taken

| Probe | Command | Result |
|---|---|---|
| Windows time service | `w32tm /query /status` | **not started** (`0x80070426`) — the host has no active Windows time synchronization |
| Host UTC | `(Get-Date).ToUniversalTime()` | `2026-10-29T01:58:08Z` |
| External reference | HTTP `Date` from `https://www.cloudflare.com` | local − reference ≈ **+3.29 s**, measured once; HTTP `Date` has 1-second granularity and network latency, so this is a coarse indication, not a skew figure |
| Disposable container | `docker run --rm --network none alpine:3.20 date +%s` | within **≤1 s** of the host midpoint (1-second resolution); containers share the host kernel clock |

The disposable container was created and removed by `--rm`; no shared service was touched.

## Interpretation and limits

- The old 45–48-second skew is **not** reproduced by this coarse probe; current difference is a
  few seconds at most. This does not establish a stable offset, jitter, or that the host clock
  is correct — the Windows time service is not running at all.
- Issuer, registry and in-JVM clocks were **not** measured. They may share the host clock, but
  that is an assumption to verify, not evidence.
- No renewal, revocation, expiry, restart-fence or atomic-replacement behavior was exercised.

## Policy observed in code

`ExecutorEndpointRef.from` (`src/main/java/com/chromascape/web/debug/buildergoal/service/ExecutorEndpointRef.java:40-43`)
requires:

- `issued_at` not more than **30 s** in the future;
- `expires_at` strictly after `now`;
- `expires_at` no more than **120 s** after `issued_at`.

The earlier plan's "five-second preflight" is not visible in this path; re-confirm which
component it refers to before treating it as current policy. Do not widen the 30 s/120 s bounds
to make a failing clock pass.

## Required owners and decisions

| Area | Owner to name | Decision needed |
|---|---|---|
| Host/VM time sync | platform owner | whether to run `w32time` or an equivalent, and with which source |
| Registry availability | registry owner | expected uptime, routing and failover |
| Grant issuance/renewal | issuer owner | cadence, retry, revocation, monitoring |
| Secrets | security owner | rotation and storage for protected credentials |
| Incident response | operations owner | clock/paging/expiry alerts and escalation |

## Measurement procedure once owners exist

1. Record each layer's clock at the same instant, three times over ten minutes:
   Windows host, container/VM, issuer JVM, registry JVM, and one external reference.
2. Record `issued_at`/`expires_at`/`fencing_generation` from a real endpoint reference and
   compute remaining time from **measured** skew, not the nominal TTL.
3. Exercise: timely renewal, issuer restart, revoked grant, expired grant, stale generation,
   atomic replacement, and durable fence persistence across restart.
4. Keep fail-closed behavior on every failure. Never reset generation high-water state to make
   an old grant valid.

## Status

Host time synchronization is currently **not configured**. Owner assignment, skew/jitter
measurement and the renewal lifecycle remain open. Local preparation is complete; no production
claim is made.
