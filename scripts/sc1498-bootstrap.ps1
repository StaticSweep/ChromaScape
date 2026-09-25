[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(ParameterSetName = 'Run', Mandatory = $true)]
    [string]$ProposalPath,

    [Parameter(ParameterSetName = 'Run', Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')]
    [string]$ApprovedBy,

    [Parameter(ParameterSetName = 'Stop', Mandatory = $true)]
    [string]$StopRequestId,

    [Parameter(ParameterSetName = 'Stop')]
    [string]$Reason = 'SC-1498 operator kill switch',

    [Parameter(ParameterSetName = 'Stop')]
    [switch]$KillSwitch,

    [string]$BaseUrl = 'http://127.0.0.1:8090',
    [string]$OperatorKey = $env:GAMEPLAY_BOOTSTRAP_OPERATOR_KEY
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OperatorKey) -or $OperatorKey.Length -lt 32) {
    throw 'Set GAMEPLAY_BOOTSTRAP_OPERATOR_KEY to the configured 32+ character operator key.'
}

$headers = @{ 'X-Gameplay-Operator-Key' = $OperatorKey }
$api = $BaseUrl.TrimEnd('/') + '/api/v1/gameplay/bootstrap'
$availability = Invoke-RestMethod -Method Get -Uri "$api/availability"
if (-not $availability.enabled -or -not $availability.operatorKeyConfigured) {
    throw 'The SC-1498 operator API is disabled or its operator key is not configured.'
}

if ($PSCmdlet.ParameterSetName -eq 'Stop') {
    $stopBody = @{
        requestId = $StopRequestId
        reason = $Reason
        killSwitch = [bool]$KillSwitch
    } | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Method Post -Uri "$api/stop" -Headers $headers `
        -ContentType 'application/json' -Body $stopBody | ConvertTo-Json -Depth 20
    return
}

$resolvedProposal = (Resolve-Path -LiteralPath $ProposalPath).Path
$proposal = Get-Content -Raw -LiteralPath $resolvedProposal | ConvertFrom-Json
$previewBody = $proposal | ConvertTo-Json -Depth 100
$preview = Invoke-RestMethod -Method Post -Uri "$api/preview" -Headers $headers `
    -ContentType 'application/json' -Body $previewBody

Write-Host ''
Write-Host 'Exact one-use SC-1498 approval scope:' -ForegroundColor Yellow
$preview.exactScope | ConvertTo-Json -Depth 100
Write-Host ''
Write-Host "Scope digest: $($preview.scopeDigest)" -ForegroundColor Yellow
Write-Host 'Review every value above. Any later change invalidates this approval.' -ForegroundColor Yellow
$confirmation = Read-Host "Type APPROVE $($preview.scopeDigest)"
if ($confirmation -cne "APPROVE $($preview.scopeDigest)") {
    throw 'Exact human approval was not provided. Nothing was dispatched.'
}

$approvalBody = @{
    proposal = $proposal
    approvedBy = $ApprovedBy
    expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o')
    approvedScopeDigest = $preview.scopeDigest
    humanConfirmed = $true
} | ConvertTo-Json -Depth 100
$approval = Invoke-RestMethod -Method Post -Uri "$api/approve" -Headers $headers `
    -ContentType 'application/json' -Body $approvalBody

$proposal.envelope.authorityId = $approval.authorityId
$dispatchBody = @{
    approvalId = $approval.authorityId
    proposal = $proposal
} | ConvertTo-Json -Depth 100

Write-Host "Dispatching request $($proposal.envelope.requestId) with one-use authority." -ForegroundColor Yellow
Invoke-RestMethod -Method Post -Uri "$api/dispatch" -Headers $headers `
    -ContentType 'application/json' -Body $dispatchBody | ConvertTo-Json -Depth 100
