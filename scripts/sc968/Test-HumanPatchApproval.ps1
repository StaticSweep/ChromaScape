$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$module = Import-Module (Join-Path $PSScriptRoot 'Acceptance.Approval.psm1') -Force -PassThru
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sc968-approval-test-' + [Guid]::NewGuid().ToString('N'))
foreach ($case in @('approve','reject','wrong-proposal','wrong-hash','wrong-request','empty-confirmation','timeout','stale')) {
  $directory = Join-Path $testRoot $case
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  & $module {
    param($testDirectory, $testCase)
    $script:testDirectory = $testDirectory
    $script:testCase = $testCase
    # Replace only the clock tick; exercise the real request/response validation.
    function script:Start-Sleep {
      param($Milliseconds)
      if ($script:testCase -eq 'timeout') { return }
      $request = Get-Content -Raw (Join-Path $script:testDirectory 'human-patch-approval-request.json') | ConvertFrom-Json
      $request | Add-Member decision 'APPROVE'
      $request | Add-Member human_confirmation 'Synthetic approval unit test only'
      switch ($script:testCase) {
        'reject' { $request.decision = 'REJECT' }
        'wrong-proposal' { $request.proposal_id = 'another-proposal' }
        'wrong-hash' { $request.proposal_sha256 = 'sha256:wrong' }
        'wrong-request' { $request.request_id = 'old-request' }
        'empty-confirmation' { $request.human_confirmation = '' }
      }
      $request | ConvertTo-Json | Set-Content (Join-Path $script:testDirectory 'human-patch-approval-response.json') -Encoding UTF8
    }
  } $directory $case
  if ($case -eq 'stale') { '{}' | Set-Content (Join-Path $directory 'human-patch-approval-response.json') }
  $failed = $false
  try {
    $timeout = if ($case -eq 'timeout') { 0 } else { 5 }
    $response = Wait-Sc968HumanPatchApproval -ProposalId 'test-proposal' -ProposalHash 'sha256:test-only' -Directory $directory -TimeoutSeconds $timeout
    if ($response.decision -ne 'APPROVE') { throw 'Expected approval response' }
  } catch { $failed = $true }
  if (($case -eq 'approve' -and $failed) -or ($case -ne 'approve' -and -not $failed)) { throw "Human approval regression: $case" }
  Write-Output "PASS $case"
}
Write-Output 'PASS all eight native human-approval cases (synthetic unit evidence only)'
