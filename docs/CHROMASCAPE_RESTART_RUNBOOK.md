# ChromaScape Restart Runbook

## Summary

Use this document as the canonical reference for restarting ChromaScape.

Default restart path:

```cmd
cd /d C:\Dev\IdeaProjects\ChromaScape
scripts\restart-chromascape.bat
```

Run restart commands from Windows, not WSL.

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
