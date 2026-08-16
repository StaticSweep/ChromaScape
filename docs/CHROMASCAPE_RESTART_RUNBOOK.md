# ChromaScape Restart Runbook

## Summary

Use this document as the canonical reference for restarting ChromaScape.

Default restart path:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
scripts\restart-chromascape.bat
```

Run restart commands from Windows, not WSL.

## Local Machine Configuration (.env.local)

Machine-specific settings live in `C:\Dev\IdeaProjects\ChromaScape\.env.local`. This file is
git-ignored; `.env.example` documents the supported values.

Setup:

1. Copy `.env.example` to `.env.local`:

   ```cmd
   copy .env.example .env.local
   ```

2. Set the local Micro6 project path (and optionally the local machine UUID):

   ```dotenv
   BUILDER_GOAL_MICRO6_PROJECT_DIR=C:\Dev\IdeaProjects\micro6
   BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID=1d391d65-6c97-5b77-b676-964f157e8c73
   ```

   - `BUILDER_GOAL_MICRO6_PROJECT_DIR`: absolute path to the Micro6 workspace used for local
     Builder Goal launches. If unset, Spring falls back to `../micro6` resolved relative to the
     process working directory; the restart script starts Gradle with the ChromaScape project as
     that directory, so `../micro6` resolves to the sibling of the ChromaScape project.
   - `BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID`: launcher machine UUID treated as the local
     ChromaScape host. If unset, the launcher hostname is used instead.
   - `BUILDER_GOAL_MICRO6_VALIDATION_TIMEOUT_MS`: bounded timeout for canonical Gradle task
     discovery before an existing Micro6 process is replaced; defaults to 300000 ms to allow a
     cold composite workspace to configure its included builds.

   - Builder Goal local launches use the production launcher at
     `C:\Dev\IdeaProjects\micro6\launch-buildergoal-client.ps1`. ChromaScape verifies the launcher
     contract (`-ContractVersion`, requires `contract_version == 2`) before any preflight or
     process cleanup. See `docs\BUILDER_GOAL_LAUNCHER_CONTRACT.md` for the full contract. There is
     no executable launcher copy under ChromaScape `docs\`; the Micro6 copy is the source of truth.

3. Restart ChromaScape from Windows (see below). The restart script loads `.env.local` before
   starting `bootRun`. Existing process environment variables are never overwritten.

`.env.local` loader behavior:

- Reads the file with `Get-Content -LiteralPath`.
- Ignores empty lines and lines starting with `#`.
- Accepts keys matching `^[A-Za-z_][A-Za-z0-9_]*$`.
- Splits each assignment on the first `=`.
- Strips one layer of optional matching single/double quotes around the value; `=` and Windows
  backslashes inside values are preserved.
- Rejects malformed variable names, missing `=`, and unmatched opening or closing quotes with a
  line-numbered error.
- Does not overwrite a non-empty process environment variable; an empty existing process variable
  is populated from `.env.local`.
- Never prints variable values or the contents of `.env.local`; only imported variable names are
  logged.

The parser is implemented in `scripts/local-env.ps1`. Run its isolated verification without
starting or stopping ChromaScape:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\test-local-env.ps1
```

Configuration precedence (highest first):

1. Explicit restart-script parameters, where supported.
2. Existing process/Windows environment variables.
3. `.env.local`.
4. Spring defaults from `application.yml`.

## Preferred Restart Path

The preferred command is:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
scripts\restart-chromascape.bat
```

This wrapper calls `scripts/restart-chromascape.ps1` and performs the full recovery flow:

- checks that the database is reachable at `localhost:5433`
- stops stale ChromaScape-related `java`, `cmd`, and `gradle` processes
- verifies port `8090` is free
- starts `gradlew.bat --no-daemon bootRun`
- waits for ChromaScape to bind `8090`
- probes `http://localhost:8090/debugger`
- logs startup output to `C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log`

Use this path whenever ChromaScape is stuck, half-running, or needs a clean restart.

## Manual Startup Fallbacks

If the restart wrapper is unavailable, use one of these:

1. Direct Gradle start:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat bootRun
```

2. Startup helper:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
START_CHROMASCAPE.bat
```

3. Log-to-file helper:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
run_chromascape.bat
```

That helper writes to `C:\Dev\IdeaProjects\ChromaScape\chromascape_output.log`.

Use raw `gradlew.bat bootRun` for first start or when debugging startup interactively.

## Stop Then Restart

If ChromaScape is already running in a visible console:

- press `Ctrl+C` in that console

If the process is hung, detached, or unclear:

- run `scripts\restart-chromascape.bat`

The wrapper already force-stops stale ChromaScape-related processes before relaunching.

## Health Checks After Restart

Verify these URLs:

- `http://localhost:8090`
- `http://localhost:8090/debugger`

Optional MCP bridge health:

- `http://localhost:8765/health`

Expected healthy state:

- port `8090` is listening
- `/debugger` loads
- the restart wrapper prints `Restart complete`

## Builder Goal Runner Verification

After restart, confirm the Builder Goal local launch configuration loaded the machine-local
Micro6 path:

```powershell
Get-Content C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log -Tail 2000 |
  Select-String 'Builder Goal local launch config loaded'
```

Expected:

```text
Builder Goal local launch config loaded: projectDir=C:\Dev\IdeaProjects\micro6, ...
```

Then open `http://localhost:8090/debugger` and confirm:

1. Launch Host selects `CZC8518C27` as the local host.

## Builder Goal dispatch and relaunch contract

Publish requests must omit `targetBaseUrl`; ChromaScape selects exactly one non-stale, dispatch-ready
registry client and revalidates that client's `/health` identity immediately before `/execute`.
Caller-supplied nonblank target overrides return `TARGET_OVERRIDE_NOT_ALLOWED`. Empty settings are
accepted as `{}`; non-empty settings require the selected executor's usable catalog schema.

To force a replacement launch, submit `/api/debug/builder-goals/launch-account` with
`forceRelaunch: true`. ChromaScape performs validation and compile/preflight before stopping the
verified owned process, records the phase history, and starts the replacement with a fresh launch
attempt. A preflight failure must leave the existing owned process running.

## Real Fishing publication is currently BLOCKED

The authoritative launcher/catalog account record still contains the profile-derived hash
`3720a9a3b5c9f57b`, while the AccountConnector-derived canonical identity for the selected real
account is `b01c7f006bace664`. The account-record migration has NOT been performed, so:

- Do NOT select `b01c7f006bace664`; it is absent from the catalog and a catalog lookup with it will
  fail.
- Do NOT bypass the identity mismatch with the account name; name matching is only a fallback when
  no expected hash exists at all.
- Real Fishing Training publication and launch remain blocked until the account-record migration is
  executed and recorded with evidence (old hash, new hash, source, timestamp, operator).

While blocked, the executor should still report `account_hash_scheme=accountconnector/v1`, but the
actual and expected hashes will not match the catalog record and `selectedAccountEligible` will be
`false`. This is the expected fail-closed state; do not override it.

Only after the migration is complete and recorded may the operator confirm:

1. Select the migrated canonical game-account hash `b01c7f006bace664` (now present in the catalog).
2. The executor reports `account_hash_scheme=accountconnector/v1`, actual and expected hashes match,
   and the selected-account eligibility is true.
3. The executor becomes dispatch-ready on the requested world.
4. Fishing Training can be published.

Machines endpoint (HTTP 200 with the local machine marked `localMachine: true`):

```powershell
Invoke-RestMethod http://localhost:8090/api/debug/builder-goals/machines |
  ConvertTo-Json -Depth 8
```

## Common Failures

### Database preflight failed

Meaning:

- Postgres is not reachable at `localhost:5433`

Action:

- start the database first
- rerun `scripts\restart-chromascape.bat`

### Port 8090 is still in use

Meaning:

- another process is still bound to ChromaScape's port

Action:

- stop the conflicting process
- rerun the restart wrapper

### bootRun exited before opening port 8090

Meaning:

- Spring Boot failed during startup

Action:

- inspect `C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log`

### Java / Gradle version mismatch

Meaning:

- this repo requires Java 17 or newer

Action:

- switch to Java 17+
- rerun the restart command

## Future Agent Note

For future reference:

- prefer `scripts\restart-chromascape.bat` over raw `bootRun` when asked to restart ChromaScape
- use this document as the source of truth for restart behavior
