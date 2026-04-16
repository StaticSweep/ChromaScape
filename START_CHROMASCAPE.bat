@echo off
REM ============================================================
REM ChromaScape Startup Script
REM ============================================================
REM This script starts ChromaScape with MCP bridge auto-start
REM ============================================================

echo.
echo ============================================================
echo Starting ChromaScape with MCP Bridge Auto-Start
echo ============================================================
echo.
echo Watch for these SUCCESS indicators:
echo   - MCP Bridge Auto-Start: ENABLED
echo   - MCP Bridge Process: RUNNING
echo   - Provider Available: YES
echo.
echo After startup completes, open your browser to:
echo   http://localhost:8090/debugger
echo.
echo Press Ctrl+C to stop ChromaScape and the MCP bridge
echo ============================================================
echo.

cd /d "%~dp0"
gradlew.bat bootRun

pause
