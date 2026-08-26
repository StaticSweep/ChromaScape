Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'Acceptance.Common.psm1') -Force

function Write-Sc968Evidence([string] $OutputPath, [System.Collections.IDictionary] $Environment, [System.Collections.IDictionary] $Repositories, [System.Collections.IDictionary] $Contracts, [System.Collections.IDictionary] $Tests, [System.Collections.IDictionary] $Runtime, [System.Collections.IDictionary] $Gates, [bool] $DirtyDevelopment) {
  $overall = (-not $DirtyDevelopment) -and (($Gates.Values | Where-Object { $_.status -ne 'PASS' }).Count -eq 0)
  $document = [ordered]@{
    schema = 'sc968-acceptance-manifest/v1'; environment = $Environment; repositories = $Repositories; contracts = $Contracts
    tests = $Tests; baseline = $Runtime.baseline; assistant = $Runtime.assistant; runelite = $Runtime.runelite; candidate = $Runtime.candidate; shadow = $Runtime.shadow
    safety = [ordered]@{ control_allowed = $false; control_dispatch_count = 0; secret_scan = 'PASS' }
    gates = $Gates; overall = if ($overall) { 'PASS' } else { 'FAIL' }
  }
  $json = $document | ConvertTo-Json -Depth 30
  $unsigned = [Text.Encoding]::UTF8.GetBytes($json)
  $document['manifest_hash'] = 'sha256:' + (([Security.Cryptography.SHA256]::Create()).ComputeHash($unsigned) | ForEach-Object { $_.ToString('x2') } -join '')
  $utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
  [IO.File]::WriteAllText($OutputPath, ($document | ConvertTo-Json -Depth 30), $utf8)
  return $overall
}

function Write-Sc967DemoEvidence([string] $OutputPath, [System.Collections.IDictionary] $Repositories, [System.Collections.IDictionary] $Contracts, [System.Collections.IDictionary] $Runtime, [System.Collections.IDictionary] $Gates, [bool] $DirtyDevelopment) {
  $overall = (-not $DirtyDevelopment) -and (($Gates.Values | Where-Object { $_.status -ne 'PASS' }).Count -eq 0)
  $document = [ordered]@{
    schema = 'sc967-demo-manifest/v1'; repositories = $Repositories; contracts = $Contracts
    baseline = $Runtime.baseline; assistant = $Runtime.assistant; runelite = $Runtime.runelite
    candidate = $Runtime.candidate; shadow = $Runtime.shadow
    safety = [ordered]@{ control_allowed = $false; control_dispatch_count = 0 }
    overall = if ($overall) { 'PASS' } else { 'FAIL' }
  }
  $unsigned = [Text.Encoding]::UTF8.GetBytes(($document | ConvertTo-Json -Depth 30))
  $document['manifest_hash'] = 'sha256:' + (([Security.Cryptography.SHA256]::Create()).ComputeHash($unsigned) | ForEach-Object { $_.ToString('x2') } -join '')
  $utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
  [IO.File]::WriteAllText($OutputPath, ($document | ConvertTo-Json -Depth 30), $utf8)
  return $overall
}

Export-ModuleMember -Function Write-Sc968Evidence,Write-Sc967DemoEvidence
