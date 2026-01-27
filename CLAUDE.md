# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run application (MUST use Windows, not WSL, for screen capture)
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat bootRun"

# Run tests
./gradlew test

# Code quality (run before committing)
./gradlew spotlessApply    # Auto-format (Google Java Format)
./gradlew checkstyleMain   # Lint check (Google Checkstyle)

# Clean
./gradlew clean            # Remove build artifacts
./gradlew cleanChromascape # Remove .chromascape directory
./gradlew cleanAll         # Full cleanup
```

**Server:** http://localhost:8090
**Debugger:** http://localhost:8090/debugger

**Database:** PostgreSQL via Docker
```bash
docker-compose up postgres
```

## Architecture

ChromaScape is a colour-based game automation framework with a Spring Boot web interface.

**Three-layer design:**

1. **`utils/`** - Core automation logic (platform-independent)
   - `utils/core/screen/` - Screen capture, colour detection, image recognition
   - `utils/core/input/` - Mouse/keyboard input via KInput native DLLs
   - `utils/core/state/` - State tracking (StateManager singleton, BotState enum)
   - `utils/actions/` - High-level bot actions (Idler, ItemDropper, PointSelector)
   - `utils/domain/` - Game-specific logic (OCR, walker, zones)

2. **`web/`** - Spring Boot HTTP/WebSocket services
   - `web/viewport/` - Live screen feed via WebSocket (`/ws/viewport`)
   - `web/recording/` - Video recording (FFmpeg-based MP4)
   - `web/logs/` - Log ingestion, storage, replay
   - `web/debug/` - Tick-based debugger, AI inspector, timeline
   - `web/state/` - Bot state broadcasting
   - `web/integration/` - LeeuwBot API, Micro6 client integrations

3. **Frontend** - Static HTML/CSS/JS in `resources/templates/` and `resources/static/`

**Key patterns:**
- Static singletons (`StateManager`, `ViewportManager`) decouple core from web
- WebSocket streaming for real-time updates (`/ws/logs`, `/ws/viewport`, `/ws/overlay`, `/ws/bot-state`)
- Producer-consumer with `BlockingQueue` for recording service
- Listener interfaces (`BotStateListener`, `ViewportListener`) for reactive updates

## Code Conventions

- **Style:** Google Java Format (Spotless) + Google Checkstyle
- **Javadoc:** Required for public APIs and classes
- **Logging:** Use Log4j2 (configured in `log4j2.xml`)
- **Exceptions:** Create specific exception types (e.g., `RecordingAlreadyInProgressException`)

## Important Notes

- **When restarting ChromaScape:** Only kill the ChromaScape process (port 8090), not all java.exe processes. Other Java/JAR applications may be running.
- **Screen capture requires Windows:** The native KInput DLLs and screen capture only work on Windows, not WSL.
- **Config:** Application settings in `application.yml` (DB, logging, LeeuwBot integration, screen capture FPS)

## Known Limitations

### Recording Timestamp Precision

**Achievable Synchronization:** 100-500ms precision for log-to-video sync during replay.

**Why Not Better:**
- Log timestamps are captured asynchronously when written (not at event time)
- Video timeupdate events fire at 15-250ms intervals (browser-dependent)
- Network latency for API requests (~20-100ms)
- Database query time for time-window fetches

**Legacy Recordings:**
Recordings created before the timing fix (commit bf45d59) have `startTime` captured ~500-1000ms before the first video frame, causing logs to appear early during replay. Re-record sessions if precise timing is critical.

**Tick-Based Logs:**
Tick logs from Micro6 are correlated to game ticks, which provide frame-accurate alignment for tick-based analysis regardless of timestamp precision.
