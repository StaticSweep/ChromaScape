<#
.SYNOPSIS
Loads key=value pairs from an optional .env.local file into the current
process environment without overwriting existing non-empty variables.
#>

function Import-LocalEnvFile {
  param(
    [Parameter(Mandatory = $true)][string]$ProjectDir
  )

  $envPath = Join-Path $ProjectDir ".env.local"
  if (-not (Test-Path -LiteralPath $envPath)) {
    Write-Step "No .env.local found at $envPath; skipping local environment import."
    return
  }

  $imported = New-Object System.Collections.Generic.List[string]
  $lines = @(Get-Content -LiteralPath $envPath)
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $lineNumber = $i + 1
    $line = [string]$lines[$i]
    if ([string]::IsNullOrWhiteSpace($line)) {
      continue
    }
    $trimmed = $line.Trim()
    if ($trimmed.StartsWith("#")) {
      continue
    }
    $equalsIndex = $trimmed.IndexOf("=")
    if ($equalsIndex -lt 0) {
      throw "Malformed line $lineNumber in ${envPath}: expected KEY=VALUE assignment."
    }
    $key = $trimmed.Substring(0, $equalsIndex).Trim()
    if ($key -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
      throw "Malformed line $lineNumber in ${envPath}: invalid variable name '$key'."
    }
    $rawValue = $trimmed.Substring($equalsIndex + 1).Trim()
    $quote = ""
    if ($rawValue.Length -gt 0) {
      $first = $rawValue[0]
      $last = $rawValue[$rawValue.Length - 1]
      if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
        $quote = $first
        $rawValue = $rawValue.Substring(1, $rawValue.Length - 2)
      } elseif ($first -eq '"' -or $first -eq "'") {
        throw "Malformed line $lineNumber in ${envPath}: unmatched opening quote for variable '$key'."
      } elseif ($last -eq '"' -or $last -eq "'") {
        throw "Malformed line $lineNumber in ${envPath}: unmatched closing quote for variable '$key'."
      }
    }
    if ($quote -ne "" -and $rawValue.Contains($quote)) {
      throw "Malformed line $lineNumber in ${envPath}: unmatched $quote in value for variable '$key'."
    }
    $existing = Get-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    if ($null -ne $existing -and -not [string]::IsNullOrEmpty($existing.Value)) {
      Write-Step "Skipping $key (already set in process environment)."
      continue
    }
    Set-Item -LiteralPath "Env:$key" -Value $rawValue
    $imported.Add($key)
  }

  if ($imported.Count -gt 0) {
    Write-Step "Imported local environment variables: $($imported -join ', ')"
  } else {
    Write-Step "No new local environment variables imported from .env.local."
  }
}
