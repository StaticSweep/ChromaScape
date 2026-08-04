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
     Builder Goal launches. If unset, Spring falls back to `../micro6` relative to the ChromaScape
     project directory.
   - `BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID`: launcher machine UUID treated as the local
     ChromaScape host. If unset, the launcher hostname is used instead.

3. Restart ChromaScape from Windows (see below). The restart script loads `.env.local` before
   starting `bootRun`. Existing process environment variables are never overwritten.

`.env.local` loader behavior:

- Ignores empty lines and lines starting with `#`.
- Splits each assignment on the first `=`.
- Trims the key and optional matching quotes around the value.
- Rejects malformed or empty variable names with a line-numbered error.
- Does not overwrite a non-empty process environment variable.
- Never prints variable values; only imported variable names are logged.

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
2. The executor becomes dispatch-ready.
3. Fishing Training can be published.

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
