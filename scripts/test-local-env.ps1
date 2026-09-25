param()

$ErrorActionPreference = "Stop"

$messages = New-Object System.Collections.Generic.List[string]
function Write-Step {
  param([string]$Message)
  $messages.Add($Message)
}

. "$PSScriptRoot\local-env.ps1"

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("chromascape-env-test-" + [guid]::NewGuid())
$testKeys = @(
  "CHROMASCAPE_ENV_TEST_SINGLE",
  "CHROMASCAPE_ENV_TEST_QUOTED",
  "CHROMASCAPE_ENV_TEST_EQUALS",
  "CHROMASCAPE_ENV_TEST_EXISTING",
  "CHROMASCAPE_ENV_TEST_EMPTY",
  "CHROMASCAPE_ENV_TEST_SECRET"
)
$saved = @{}
foreach ($key in $testKeys) {
  $existing = Get-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
  if ($null -ne $existing) {
    $saved[$key] = $existing.Value
  }
}

function Assert-Equal {
  param(
    [Parameter(Mandatory = $true)]$Expected,
    [Parameter(Mandatory = $true)]$Actual,
    [Parameter(Mandatory = $true)][string]$Message
  )
  if ($Expected -ne $Actual) {
    throw "$Message. Expected '$Expected', got '$Actual'."
  }
}

function Assert-True {
  param(
    [Parameter(Mandatory = $true)][bool]$Condition,
    [Parameter(Mandatory = $true)][string]$Message
  )
  if (-not $Condition) {
    throw $Message
  }
}

function Reset-TestKeys {
  foreach ($key in $testKeys) {
    Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
  }
  $messages.Clear()
}

function Write-EnvFile {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Contents
  )
  $caseDir = Join-Path $root $Name
  New-Item -ItemType Directory -Path $caseDir -Force | Out-Null
  $path = Join-Path $caseDir ".env.local"
  Set-Content -LiteralPath $path -Value $Contents -Encoding UTF8
  return $caseDir
}

function Assert-Throws {
  param(
    [Parameter(Mandatory = $true)][scriptblock]$Action,
    [Parameter(Mandatory = $true)][string]$ExpectedText
  )
  try {
    & $Action
  } catch {
    Assert-True ($_.Exception.Message.Contains($ExpectedText)) "Expected error to contain '$ExpectedText', got '$($_.Exception.Message)'."
    return
  }
  throw "Expected an error containing '$ExpectedText'."
}

try {
  New-Item -ItemType Directory -Path $root -Force | Out-Null

  Reset-TestKeys
  Import-LocalEnvFile -ProjectDir (Join-Path $root "missing")

  Reset-TestKeys
  $dir = Write-EnvFile "basic" @"
# comment

CHROMASCAPE_ENV_TEST_SINGLE=plain-value
CHROMASCAPE_ENV_TEST_QUOTED="C:\Dev\IdeaProjects\micro6"
CHROMASCAPE_ENV_TEST_EQUALS='left=right'
"@
  Import-LocalEnvFile -ProjectDir $dir
  Assert-Equal "plain-value" (Get-Item -LiteralPath "Env:CHROMASCAPE_ENV_TEST_SINGLE").Value "single value"
  Assert-Equal "C:\Dev\IdeaProjects\micro6" (Get-Item -LiteralPath "Env:CHROMASCAPE_ENV_TEST_QUOTED").Value "quoted Windows path"
  Assert-Equal "left=right" (Get-Item -LiteralPath "Env:CHROMASCAPE_ENV_TEST_EQUALS").Value "first equals split"

  Reset-TestKeys
  [Environment]::SetEnvironmentVariable("CHROMASCAPE_ENV_TEST_EXISTING", "process-value", "Process")
  $dir = Write-EnvFile "precedence" "CHROMASCAPE_ENV_TEST_EXISTING=file-value"
  Import-LocalEnvFile -ProjectDir $dir
  Assert-Equal "process-value" (Get-Item -LiteralPath "Env:CHROMASCAPE_ENV_TEST_EXISTING").Value "existing process value wins"

  Reset-TestKeys
  [Environment]::SetEnvironmentVariable("CHROMASCAPE_ENV_TEST_EMPTY", "", "Process")
  $dir = Write-EnvFile "empty-process" "CHROMASCAPE_ENV_TEST_EMPTY=file-value"
  Import-LocalEnvFile -ProjectDir $dir
  Assert-Equal "file-value" (Get-Item -LiteralPath "Env:CHROMASCAPE_ENV_TEST_EMPTY").Value "empty process value is populated"

  Reset-TestKeys
  $dir = Write-EnvFile "missing-equals" "CHROMASCAPE_ENV_TEST_SINGLE"
  Assert-Throws { Import-LocalEnvFile -ProjectDir $dir } "expected KEY=VALUE"

  Reset-TestKeys
  $dir = Write-EnvFile "invalid-key" "CHROMASCAPE-ENV-TEST=bad"
  Assert-Throws { Import-LocalEnvFile -ProjectDir $dir } "invalid variable name"

  Reset-TestKeys
  $dir = Write-EnvFile "opening-quote" 'CHROMASCAPE_ENV_TEST_SINGLE="unterminated'
  Assert-Throws { Import-LocalEnvFile -ProjectDir $dir } "unmatched opening quote"

  Reset-TestKeys
  $dir = Write-EnvFile "closing-quote" 'CHROMASCAPE_ENV_TEST_SINGLE=unterminated"'
  Assert-Throws { Import-LocalEnvFile -ProjectDir $dir } "unmatched closing quote"

  Reset-TestKeys
  $dir = Write-EnvFile "secret-logging" "CHROMASCAPE_ENV_TEST_SECRET=super-secret-value"
  Import-LocalEnvFile -ProjectDir $dir
  Assert-True (($messages -join [Environment]::NewLine) -notmatch "super-secret-value") "environment values must never be logged"

  Write-Host "Local environment loader tests passed."
} finally {
  foreach ($key in $testKeys) {
    Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    if ($saved.ContainsKey($key)) {
      [Environment]::SetEnvironmentVariable($key, $saved[$key], "Process")
    }
  }
  if (Test-Path -LiteralPath $root) {
    Remove-Item -LiteralPath $root -Recurse -Force
  }
}
