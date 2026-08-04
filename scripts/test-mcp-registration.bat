@echo off
setlocal EnableExtensions EnableDelayedExpansion
REM ==============================================================================
REM ChromaScape MCP Registration Verification Script (Windows)
REM ==============================================================================
REM
REM Verifies that ChromaScape is registered as an MCP server with
REM Claude CLI and/or Codex CLI.
REM
REM For Codex, registration metadata is displayed for operator visibility, but
REM the actual pass/fail decision is delegated to verify-codex-mcp-servers.js,
REM which also performs a real MCP handshake. A plain `codex mcp get` result is
REM never treated as success on its own.
REM
REM Usage: test-mcp-registration.bat
REM
REM Exit codes:
REM   0 = Codex verified via handshake and/or Claude registered
REM   1 = neither CLI registered, or Codex verification failed (other state)
REM   2 = Codex startup_timeout_sec mismatch (verifier)
REM   6 = Codex CLI invocation or parsing error (verifier)
REM
REM ==============================================================================

echo ============================================================
echo ChromaScape MCP Registration Verification
echo ============================================================
echo.

REM Track status
set CLAUDE_FOUND=0
set CODEX_OK=0
set VERIFIER_EXIT=0
if "%EXPECTED_STARTUP_TIMEOUT_SEC%"=="" set "EXPECTED_STARTUP_TIMEOUT_SEC=120"

REM Derive paths from this script's location (no hard-coded drives)
set "SCRIPT_DIR=%~dp0"
set "WRAPPER=%SCRIPT_DIR%mcp-bridge\wrapper\server.js"
set "VERIFIER=%SCRIPT_DIR%mcp-bridge\verify-codex-mcp-servers.js"

REM Check Claude CLI
echo Checking Claude CLI registration...
where claude >nul 2>nul
if %errorlevel% neq 0 (
    echo [--] Claude CLI not installed
    goto :check_codex
)

echo      Claude CLI detected
claude mcp get chromascape >nul 2>nul
if %errorlevel% equ 0 (
    REM Verify configuration shape
    set TYPE_CHECK=
    set CMD_CHECK=
    for /f "tokens=*" %%a in ('claude mcp get chromascape 2^>nul ^| findstr /i "Type: stdio"') do set "TYPE_CHECK=%%a"
    for /f "tokens=*" %%b in ('claude mcp get chromascape 2^>nul ^| findstr /i "Command: node"') do set "CMD_CHECK=%%b"
    if defined TYPE_CHECK if defined CMD_CHECK (
        echo [OK]  ChromaScape registered with Claude CLI ^(verified^)
        set CLAUDE_FOUND=1
    ) else (
        echo [!!]  ChromaScape found but configuration invalid
        echo       Expected: Type: stdio, Command: node
    )
) else (
    echo [--] ChromaScape NOT registered with Claude CLI
)

:get_claude_details
if %CLAUDE_FOUND% equ 1 (
    echo.
    echo      Registration details:
    claude mcp get chromascape 2>nul
)

echo.

:check_codex
REM Check Codex CLI
echo Checking Codex CLI registration...
where codex >nul 2>nul
if %errorlevel% neq 0 (
    echo [--] Codex CLI not installed
    goto :show_summary
)

echo      Codex CLI detected
echo      Registration details:
codex mcp get chromascape 2>nul
echo.

echo      Running verification (configuration + live MCP handshake)...
if not exist "%WRAPPER%" (
    echo [!!]  Wrapper not found at: %WRAPPER%
    set VERIFIER_EXIT=1
    goto :codex_done
)
if not exist "%VERIFIER%" (
    echo [!!]  Verifier not found at: %VERIFIER%
    set VERIFIER_EXIT=1
    goto :codex_done
)

node "%VERIFIER%" --servers chromascape --expectedChromascapeWrapper "%WRAPPER%" --expectedChromascapeTimeout "%EXPECTED_STARTUP_TIMEOUT_SEC%" --handshakeTimeoutSec "%EXPECTED_STARTUP_TIMEOUT_SEC%"
set VERIFIER_EXIT=%errorlevel%

:codex_done
if %VERIFIER_EXIT% equ 0 (
    echo [OK]  ChromaScape registered with Codex CLI ^(verified, timeout=%EXPECTED_STARTUP_TIMEOUT_SEC%s^)
    set CODEX_OK=1
) else (
    echo [!!]  ChromaScape Codex registration verification failed ^(exit %VERIFIER_EXIT%^)
    echo       Verify the wrapper path, the registration, and that the MCP handshake succeeds.
)

echo.

:show_summary
echo ============================================================
echo Summary
echo ============================================================
echo.

if %CLAUDE_FOUND% equ 1 (
    echo [OK] Claude CLI: ChromaScape registered
) else (
    where claude >nul 2>nul
    if %errorlevel% neq 0 (
        echo [--] Claude CLI: Not installed
    ) else (
        echo [--] Claude CLI: ChromaScape not registered
    )
)

echo.

if %CODEX_OK% equ 1 (
    echo [OK] Codex CLI: ChromaScape registered
) else (
    where codex >nul 2>nul
    if %errorlevel% neq 0 (
        echo [--] Codex CLI: Not installed
    ) else (
        echo [--] Codex CLI: ChromaScape not registered
    )
)

echo.

REM Show remediation if needed
if %CLAUDE_FOUND% equ 0 (
    where claude >nul 2>nul
    if %errorlevel% equ 0 (
        echo.
        echo To register with Claude CLI:
        echo   cd scripts\mcp-bridge
        echo   .\register-mcp-clients.bat
        echo.
    )
)

if %CODEX_OK% equ 0 (
    where codex >nul 2>nul
    if %errorlevel% equ 0 (
        echo.
        echo To register with Codex CLI:
        echo   cd scripts\mcp-bridge
        echo   .\register-mcp-clients.bat
        echo.
    )
)

REM Exit with appropriate code: propagate verifier failure first
if %CODEX_OK% equ 0 (
    where codex >nul 2>nul
    if %errorlevel% equ 0 (
        if %VERIFIER_EXIT% equ 0 set VERIFIER_EXIT=1
        exit /b %VERIFIER_EXIT%
    )
)

if %CLAUDE_FOUND% equ 1 (
    exit /b 0
)
if %CODEX_OK% equ 1 (
    exit /b 0
)
exit /b 1
