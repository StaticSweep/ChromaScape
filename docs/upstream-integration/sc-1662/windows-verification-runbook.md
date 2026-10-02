# SC-1662 — Windows verification runbook

## Purpose

Verify acceptance criterion 6 on native Windows: SC-1662 must leave the existing KInput DLL
packaging and backend intact. Linux RemoteInput is development-only in this proof of concept and is
not expected in a packaged distribution.

Run this from Windows, not WSL, against:

```text
C:\Dev\IdeaProjects\ChromaScape-sc1662-native
```

Until this run completes, Windows remains **NOT RUN**. On 2026-09-17 it was explicitly waived as a
blocking criterion for completion of the private proof of concept; this runbook is retained for the
later Windows follow-up. The waiver is not a PASS and must never be reported as one.

## Prerequisites

- A JDK 17 `JAVA_HOME`; `java -version` must report 17.
- `C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json` present.
- The tracked fixtures present at:
  - `third_party\KInput\KInput\KInput\bin\Release\KInput.dll`
  - `third_party\KInput\KInput\KInputCtrl\bin\Release\KInputCtrl.dll`
- A populated Gradle cache for `--offline`. If dependency resolution fails only because the cache is
  cold, repeat the same command once without `--offline`, then rerun it offline and retain both
  outcomes.

Do not start ChromaScape and do not attach to a client process. This procedure validates task
wiring, DLL placement and changed-code tests; it does not exercise input injection.

## PowerShell procedure

Open PowerShell and run:

```powershell
Set-Location 'C:\Dev\IdeaProjects\ChromaScape-sc1662-native'

$env:SC967_VECTORS_PATH = 'C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json'
$evidence = 'docs\upstream-integration\sc-1662\evidence'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

if (-not (Test-Path -LiteralPath $env:SC967_VECTORS_PATH -PathType Leaf)) {
    throw "Missing SC-967 vectors: $env:SC967_VECTORS_PATH"
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JAVA_HOME does not contain bin\java.exe: $env:JAVA_HOME"
}
if ((& $java -version 2>&1 | Out-String) -notmatch 'version "17\.') {
    throw 'JAVA_HOME must select JDK 17'
}
```

### 1. Enumerate protected tasks

```powershell
& .\gradlew.bat --offline tasks --console=plain 2>&1 |
    Tee-Object -FilePath "$evidence\r7-windows-tasks.log"
if ($LASTEXITCODE -ne 0) { throw 'Gradle task enumeration failed' }

$tasks = Get-Content -LiteralPath "$evidence\r7-windows-tasks.log" -Raw
foreach ($name in @(
    'copyNativeLibraries',
    'replayTest',
    'replayCertificationTest',
    'replayStressCertificationTest',
    'cleanChromascape',
    'cleanAll'
)) {
    if ($tasks -notmatch [regex]::Escape($name)) { throw "Missing protected task: $name" }
}
```

### 2. Verify KInput DLL copying and wiring

Remove only the two generated destination copies, force the real copy task to execute, and compare
the copied bytes with the tracked fixtures:

```powershell
$kinputSource = 'third_party\KInput\KInput\KInput\bin\Release\KInput.dll'
$controlSource = 'third_party\KInput\KInput\KInputCtrl\bin\Release\KInputCtrl.dll'
$kinputTarget = 'build\dist\KInput.dll'
$controlTarget = 'build\dist\KInputCtrl.dll'

Remove-Item -Force -ErrorAction SilentlyContinue $kinputTarget, $controlTarget

& .\gradlew.bat --offline copyNativeLibraries --rerun-tasks --console=plain 2>&1 |
    Tee-Object -FilePath "$evidence\r7-windows-copy-native.log"
if ($LASTEXITCODE -ne 0) { throw 'copyNativeLibraries failed' }

foreach ($path in @($kinputTarget, $controlTarget)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing copied DLL: $path" }
}
if ((Get-FileHash $kinputSource -Algorithm SHA256).Hash -ne
    (Get-FileHash $kinputTarget -Algorithm SHA256).Hash) { throw 'KInput.dll hash mismatch' }
if ((Get-FileHash $controlSource -Algorithm SHA256).Hash -ne
    (Get-FileHash $controlTarget -Algorithm SHA256).Hash) { throw 'KInputCtrl.dll hash mismatch' }

& .\gradlew.bat --offline processResources jar --rerun-tasks --console=plain 2>&1 |
    Tee-Object -FilePath "$evidence\r7-windows-packaging-wiring.log"
if ($LASTEXITCODE -ne 0) { throw 'processResources/jar packaging wiring failed' }
```

The copy task must execute rather than report `SKIPPED`, and both destination hashes must equal
their corresponding tracked source hashes.

### 3. Compile and run the SC-1662 focused suite

```powershell
& .\gradlew.bat --offline cleanTest test `
    --tests '*NativeArtifactResolverTest' `
    --tests '*RemoteInputLibraryLoaderTest' `
    --tests '*RemoteInputManifestTest' `
    --tests '*RemoteInputSmokeVerifierTest' `
    --console=plain 2>&1 |
    Tee-Object -FilePath "$evidence\r7-windows-focused-tests.log"
if ($LASTEXITCODE -ne 0) { throw 'SC-1662 focused tests failed' }
```

Expected result: 47 tests, 0 failures, 0 errors, 0 skips. These tests do not load
`libRemoteInput.so`; platform-specific Linux resolution is exercised through injected boundaries.

### 4. Record integrity and final state

```powershell
Get-ChildItem -LiteralPath $evidence -Filter 'r7-windows-*.log' |
    Sort-Object Name |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    } |
    Set-Content -Encoding ascii "$evidence\r7-windows-sha256.txt"

git diff --check
git status --short
```

Review the logs for usernames, machine-specific paths and account identifiers before retaining
them. Redact private path components consistently, then regenerate `r7-windows-sha256.txt` after
redaction. Do not rewrite any run-1 through run-6 evidence.

## Pass criteria

Windows verification is PASS only when all of the following are true:

1. Protected tasks are enumerated.
2. `copyNativeLibraries` executes on Windows and does not report `SKIPPED`.
3. `KInput.dll` and `KInputCtrl.dll` exist in `build\dist` and exactly match the tracked fixtures.
4. `processResources` and `jar` complete with the existing copy dependency intact.
5. The focused SC-1662 suite passes 47/47.
6. The retained Windows logs have reviewed, current SHA-256 values.

Add the outcome and hashes to `evidence\index.md` and `verification-evidence.md`. If any condition
fails, leave criterion 6 as FAIL/NOT RUN as appropriate and record the exact failure; do not replace
execution with inference from the Linux run.
