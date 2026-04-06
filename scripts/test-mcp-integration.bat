@echo off
REM ============================================================
REM ChromaScape MCP Bridge Integration Test
REM ============================================================
REM
REM This script validates that the MCP bridge auto-starts
REM correctly when ChromaScape launches.
REM
REM Prerequisites:
REM   - Node.js installed
REM   - PostgreSQL running (docker-compose up postgres)
REM   - Claude CLI installed OR ANTHROPIC_API_KEY set
REM
REM ============================================================

echo ============================================================
echo ChromaScape MCP Bridge Integration Test
echo ============================================================
echo.

REM Clean up any existing .env to test auto-generation
echo [1/5] Cleaning up existing .env (testing auto-generation)...
if exist scripts\mcp-bridge\.env (
  del scripts\mcp-bridge\.env
  echo       Deleted existing .env file
) else (
  echo       No existing .env file found
)

echo.
echo [2/5] Starting ChromaScape (MCP bridge will auto-start)...
echo       This will open a new window. Wait 60 seconds for startup.
echo.

start cmd /k "gradlew.bat bootRun"

echo       Waiting for startup (60 seconds)...
timeout /t 60 /nobreak >nul

echo.
echo [3/5] Testing ChromaScape health...
curl -s http://localhost:8090/actuator/health 2>nul | findstr "UP" >nul
if %ERRORLEVEL% == 0 (
  echo       ✓ ChromaScape is UP
) else (
  echo       ✗ ChromaScape is NOT responding
  echo.
  echo       Check the ChromaScape window for errors.
  pause
  exit /b 1
)

echo.
echo [4/5] Testing MCP Bridge health...
curl -s http://localhost:8765/health 2>nul | findstr "healthy" >nul
if %ERRORLEVEL% == 0 (
  echo       ✓ MCP Bridge is healthy
) else (
  echo       ✗ MCP Bridge is NOT responding
  echo.
  echo       Check ChromaScape logs for "[Bridge]" entries.
  pause
  exit /b 1
)

echo.
echo [5/5] Checking auto-generated .env...
if exist scripts\mcp-bridge\.env (
  echo       ✓ .env file was auto-generated
  echo.
  echo       Contents:
  type scripts\mcp-bridge\.env | findstr /v "^#" | findstr /v "^$"
) else (
  echo       ✗ .env file was NOT created
  pause
  exit /b 1
)

echo.
echo ============================================================
echo Integration Test PASSED
echo ============================================================
echo.
echo Next steps:
echo   1. Open http://localhost:8090/debugger
echo   2. Execute Patch Loop for a failed script
echo   3. Verify analysis completes successfully
echo.
echo To stop ChromaScape, close the window or press Ctrl+C
echo ============================================================

pause
