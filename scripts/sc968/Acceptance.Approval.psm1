Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Wait-Sc968HumanPatchApproval {
  param(
    [Parameter(Mandatory=$true)][string] $ProposalId,
    [Parameter(Mandatory=$true)][string] $ProposalHash,
    [Parameter(Mandatory=$true)][string] $Directory,
    [int] $TimeoutSeconds = 7200
  )
  New-Item -ItemType Directory -Force -Path $Directory | Out-Null
  $requestPath = Join-Path $Directory 'human-patch-approval-request.json'
  $responsePath = Join-Path $Directory 'human-patch-approval-response.json'
  if ((Test-Path -LiteralPath $requestPath) -or (Test-Path -LiteralPath $responsePath)) {
    throw 'Refusing stale human patch approval files'
  }
  $request = [ordered]@{
    schema='sc968-human-patch-approval/v1'
    request_id=[Guid]::NewGuid().ToString('N')
    proposal_id=$ProposalId
    proposal_sha256=$ProposalHash
    requested_at=[DateTimeOffset]::UtcNow.ToString('o')
  }
  $request | ConvertTo-Json | Set-Content -LiteralPath $requestPath -Encoding UTF8
  Write-Host "HUMAN PATCH APPROVAL REQUIRED: review patch-proposal.json in $Directory."
  Write-Host "Only after explicit human approval, create $responsePath with the request fields, decision=APPROVE, and human_confirmation containing that human's response. REJECT aborts."
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    if (Test-Path -LiteralPath $responsePath) {
      $response = Get-Content -LiteralPath $responsePath -Raw | ConvertFrom-Json
      foreach ($key in @('schema','request_id','proposal_id','proposal_sha256','requested_at')) {
        if ($response.$key -cne $request[$key]) { throw "Human approval identity mismatch: $key" }
      }
      if ($response.decision -cne 'APPROVE' -or [string]::IsNullOrWhiteSpace([string]$response.human_confirmation)) {
        throw 'Patch was not explicitly human-approved'
      }
      return $response
    }
    Start-Sleep -Milliseconds 250
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw 'Timed out waiting for explicit human patch approval'
}

Export-ModuleMember -Function Wait-Sc968HumanPatchApproval
