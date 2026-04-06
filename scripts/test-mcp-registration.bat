@echo off
setlocal EnableExtensions EnableDelayedExpansion
REM ==============================================================================
REM ChromaScape MCP Registration Verification Script (Windows)
REM ==============================================================================
REM
REM Verifies that ChromaScape is registered as an MCP server with
REM Claude CLI and/or Codex CLI.
REM
REM Usage: test-mcp-registration.bat
REM
REM ==============================================================================

echo ============================================================
echo ChromaScape MCP Registration Verification
echo ============================================================
echo.

REM Track status
set CLAUDE_FOUND=0
set CODEX_FOUND=0
set TIMEOUT_MISMATCH=0
if "%EXPECTED_STARTUP_TIMEOUT_SEC%"=="" set "EXPECTED_STARTUP_TIMEOUT_SEC=90"

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
codex mcp get chromascape >nul 2>nul
if %errorlevel% equ 0 (
    REM Verify configuration shape
    set TRANSPORT_CHECK=
    set ENABLED_CHECK=
    set TIMEOUT_CHECK=
    set ACTUAL_TIMEOUT=
    for /f "tokens=*" %%a in ('codex mcp get chromascape 2^>nul ^| findstr /i "transport: stdio"') do set "TRANSPORT_CHECK=%%a"
    for /f "tokens=*" %%b in ('codex mcp get chromascape 2^>nul ^| findstr /i "enabled: true"') do set "ENABLED_CHECK=%%b"
    for /f "tokens=*" %%c in ('codex mcp get chromascape 2^>nul ^| findstr /i "startup_timeout_sec:"') do set "TIMEOUT_CHECK=%%c"
    for /f "tokens=3" %%d in ('codex mcp get chromascape 2^>nul ^| findstr /i "startup_timeout_sec:"') do set "ACTUAL_TIMEOUT=%%d"
    if defined TRANSPORT_CHECK if defined ENABLED_CHECK if defined TIMEOUT_CHECK (
        if defined ACTUAL_TIMEOUT (
            REM Strict numeric comparison via PowerShell (120 == 120.0, 120 != 120.9)
            set "TIMEOUT_MATCH="
            for /f %%r in ('powershell -NoProfile -Command "try { $a=[double]('!ACTUAL_TIMEOUT!'); $b=[double]('!EXPECTED_STARTUP_TIMEOUT_SEC!'); if ([math]::Abs($a - $b) -le 1e-9) { '1' } else { '0' } } catch { 'ERR' }"') do set "TIMEOUT_MATCH=%%r"
            if not "!TIMEOUT_MATCH!"=="1" (
                echo [!!]  startup_timeout_sec mismatch: expected=!EXPECTED_STARTUP_TIMEOUT_SEC!, actual=!ACTUAL_TIMEOUT!
                set TIMEOUT_MISMATCH=1
            ) else (
                echo [OK]  ChromaScape registered with Codex CLI ^(verified, timeout=!ACTUAL_TIMEOUT!s^)
            )
        ) else (
            echo [OK]  ChromaScape registered with Codex CLI ^(verified^)
        )
        set CODEX_FOUND=1
    ) else (
        echo [!!]  ChromaScape found but configuration invalid
        echo       Expected: transport: stdio, enabled: true, startup_timeout_sec: ^<number^>
    )
) else (
    echo [--] ChromaScape NOT registered with Codex CLI
)

:get_codex_details
if %CODEX_FOUND% equ 1 (
    echo.
    echo      Registration details:
    codex mcp get chromascape 2>nul
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

if %CODEX_FOUND% equ 1 (
    echo [OK] Codex CLI: ChromaScape registered
    if %TIMEOUT_MISMATCH% equ 1 (
        echo [!!]          startup_timeout_sec does not match expected=%EXPECTED_STARTUP_TIMEOUT_SEC%
        echo               Re-register with: set MCP_STARTUP_TIMEOUT_SEC=%EXPECTED_STARTUP_TIMEOUT_SEC%
    )
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

if %CODEX_FOUND% equ 0 (
    where codex >nul 2>nul
    if %errorlevel% equ 0 (
        echo.
        echo To register with Codex CLI:
        echo   cd scripts\mcp-bridge
        echo   .\register-mcp-clients.bat
        echo.
    )
)

REM Exit with appropriate code
if %CLAUDE_FOUND% equ 1 (
    if %TIMEOUT_MISMATCH% equ 1 exit /b 2
    exit /b 0
)
if %CODEX_FOUND% equ 1 (
    if %TIMEOUT_MISMATCH% equ 1 exit /b 2
    exit /b 0
)
exit /b 1
