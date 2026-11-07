# SC-274 item 1 — registry reachability isolation and address fix (18 October 2026)

Continuation of `../sc-274-sc-276-continuation-20261016/plan.md` and
`worker-startup-followup.md`. Felix is skipped at the user's request. This is local
evidence; no whole-story acceptance or certification is claimed.

## Root cause (precise)

Every observed `registry_unreachable` failure is on the Windows `127.0.0.1` path, whose
owning process is `wslrelay.exe` (WSL localhost relay); the Docker backend proxy on
`0.0.0.0:8084` showed zero observed failures in the same runs. This isolates the **path**.
It does not prove the relay's internal defect, and it does not prove that disabling
`localhostForwarding` would permanently fix it. TCP handshakes succeed on every address;
the stall is in the HTTP response, not DNS/connect. On this host `localhost` resolves to
`::1` then `127.0.0.1` (client-dependent fallback); the mule container's own health is
fine. Full measurements: `registry-address-root-cause.md`.

## Change

`ChromaScape\.env.local` (git-ignored): added
`MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL=http://192.168.1.6:8084` — the Docker-backend
address already used by `builder-goal.launcher.base-url` for this mule service. No source,
migration, or credential change.

## Acceptance results

- Candidate reaches the authenticated registry with **no error payload**:
  `/api/micro6/executors` → `{"executors":[],"source":"leeuwbotapi_authenticated_runtime_registry","count":0}`.
- 40/40 discovery calls after the fix, zero error payloads.
- **Cold connection proven:** after a 150 s idle the candidate's pooled connection (local
  port 54127) had dropped; the next call created a **new local port 50633** to
  `192.168.1.6`, returning 200 with no error payload. Earlier 75 s and 180 s idles behaved
  the same (200, no error).
- `/debugger` 200, `/xrsps-sessions` 200, anonymous `/api/account-tasks/stabilization/overview` 401.

This establishes **connectivity only**. An empty runtime list is not a source bundle,
catalog, profile or capture.

## Restart persistence

Restart ran through the documented wrapper (`start-candidate-continuation.ps1` →
`scripts\restart-chromascape.ps1`); old pids 83144/135044/32712 stopped, new pid 195400,
debugger/LAN probe 200, exit 0. The wrapper's own import of the worktree `.env.local`
logs `No .env.local found … skipping` (the worktree has none), so the override is applied
by the start script's import of `ChromaScape\.env.local`; replaying that import in
isolation yields `MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL = http://192.168.1.6:8084`,
and `stabilization-tokens.env` supplies `MICRO6_RUNTIME_SELECTION_REGISTRY_AUTH_TOKEN`.
Behavioural proof this launch used the override: candidate pid 195400 holds outbound
connections to `192.168.1.6:8084`.

## Replay-consumer protection (configuration + observation)

`Replay configuration loaded: enabled=true, worker=false` (boot log). Additional checks:

- No separate replay/worker consumer process is running (only the Micro6 client, pid
  129736, and unrelated helpers).
- `replay_attempts` holds exactly 9 terminal rows (4 `SUCCEEDED`, 5 `FAILED`, all
  `fencing_generation=1`, `terminal_at` in early October) with **0 active leases**.
- Two snapshots taken roughly ten minutes apart are byte-identical (sha256
  `25fdd474610c965ce985023dd9b29cc71abed33029324dcc5516bc99bf3a60a0`; files
  `replay-attempts-snapshot-a.txt` / `-b.txt`), so **no consumption was observed**.

This is evidence of no observed consumption with the worker disabled; it is not an
independent proof that protection is absolute.

## Tests

Read-only probes, soaks, connection-table checks and DB snapshots only; no Java/native
suite was re-run (configuration-only change). Artifacts: `registry-address-isolation.*`,
`registry-soak.ps1`, `soak-8084-*.json`, `soak-8092-*.json`,
`candidate-http-after-address-fix.json`, `candidate-registry-address-fix-verification.json`,
`replay-attempts-snapshot-a.txt`/`-b.txt`, `replay-runs-state-a.txt`,
`registry-address-root-cause.md`.

## Risks

- The address is host-LAN-specific; if the host LAN IP changes the config must follow.
- The WSL relay still owns `127.0.0.1:8084`; other clients using `127.0.0.1` remain
  exposed to the stall. The durable root fix (disable `wsl2.localhostForwarding`) needs
  `wsl --shutdown`, which is prohibited, and is not proven.
- Intermittency means a single green call is weak evidence; the soaks, the cold-connection
  port diff, and the connection table are the actual evidence.

## Rollback

Remove the `MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL` line from
`ChromaScape\.env.local` and restart with the documented wrapper. No database, migration
or source change was made; no unrelated dirty work was touched.
