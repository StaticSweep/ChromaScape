<#
.SYNOPSIS
    Human-gated operator client for the one-use SC-1498 bootstrap recording.

.DESCRIPTION
    Drives GameplayBootstrapController (/api/v1/gameplay/bootstrap):

      Run        POST /previews, human confirmation, POST /approvals,
                 POST /approvals/{approvalId}/dispatch
      Status     GET  /runs/{orchestrationId}
      Stop       POST /runs/{orchestrationId}/stop
      Kill       POST /runs/{orchestrationId}/kill   (kill switch, confirmed=true)
      Reconcile  POST /runs/{orchestrationId}/reconcile

    The proposal file selects a persisted SC-1430 provider decision and carries the exact
    observation, execution envelope and snapshot bindings:

      {
        "providerSessionId": "...",
        "providerTurnId": "...",
        "observation": {},
        "executionEnvelope": {},
        "snapshotBindings": {}
      }

    The envelope's authorityId is a draft placeholder: the server binds the durable one-use
    authority at dispatch. The same proposal bytes are sent to preview, approval and dispatch,
    so any change after approval is refused with STALE_BINDING.
#>
[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(ParameterSetName = 'Run', Mandatory = $true)]
    [string]$ProposalPath,

    [Parameter(ParameterSetName = 'Run', Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')]
    [string]$ApprovedBy,

    # The server refuses lifetimes over 15 minutes; the runbook requires five.
    [Parameter(ParameterSetName = 'Run')]
    [ValidateRange(1, 14)]
    [int]$ExpiresInMinutes = 5,

    [Parameter(ParameterSetName = 'Status', Mandatory = $true)]
    [Parameter(ParameterSetName = 'Stop', Mandatory = $true)]
    [Parameter(ParameterSetName = 'Reconcile', Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OrchestrationId,

    [Parameter(ParameterSetName = 'Status', Mandatory = $true)]
    [switch]$Status,

    [Parameter(ParameterSetName = 'Stop')]
    [string]$Reason = 'SC-1498 operator stop',

    [Parameter(ParameterSetName = 'Stop')]
    [switch]$KillSwitch,

    [Parameter(ParameterSetName = 'Reconcile', Mandatory = $true)]
    [switch]$Reconcile,

    [string]$BaseUrl = 'http://127.0.0.1:8090',
    [string]$OperatorKey = $env:GAMEPLAY_BOOTSTRAP_OPERATOR_KEY
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 can serialize arrays as {"value":[...],"Count":n} while the System.Array
# type data is loaded, which would silently change the envelope sent for approval.
Remove-TypeData -TypeName System.Array -ErrorAction SilentlyContinue

if ([string]::IsNullOrWhiteSpace($OperatorKey) -or $OperatorKey.Length -lt 32) {
    throw 'Set GAMEPLAY_BOOTSTRAP_OPERATOR_KEY to the configured 32+ character operator key.'
}

$headers = @{ 'X-Gameplay-Operator-Key' = $OperatorKey }
$api = $BaseUrl.TrimEnd('/') + '/api/v1/gameplay/bootstrap'

function Invoke-Bootstrap {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        $Body = $null,
        [int]$TimeoutSec = 60
    )
    $request = @{
        Method = $Method
        Uri = "$api$Path"
        Headers = $headers
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 100 -Compress))
    }
    try {
        return Invoke-RestMethod @request
    } catch {
        # Surface the controller's stable {code, message} body instead of a bare HTTP status.
        $detail = $_.ErrorDetails.Message
        if (-not [string]::IsNullOrWhiteSpace($detail)) {
            throw "$Method $Path failed: $detail"
        }
        throw
    }
}

function Escape-PathSegment([string]$Value) {
    return [System.Uri]::EscapeDataString($Value)
}

# The detailed availability view requires the operator key; the public one only reports enabled.
$availability = Invoke-Bootstrap -Method Get -Path '/availability'
if (-not $availability.enabled -or -not $availability.operatorKeyConfigured) {
    $reasons = @($availability.blockingReasons) -join '; '
    throw "The SC-1498 operator API is disabled or its operator key is not configured. $reasons"
}

$runPath = '/runs/' + (Escape-PathSegment $OrchestrationId)
switch ($PSCmdlet.ParameterSetName) {
    'Status' {
        Invoke-Bootstrap -Method Get -Path $runPath | ConvertTo-Json -Depth 20
        return
    }
    'Reconcile' {
        Invoke-Bootstrap -Method Post -Path "$runPath/reconcile" | ConvertTo-Json -Depth 20
        return
    }
    'Stop' {
        if ($KillSwitch) {
            $body = @{ reason = $Reason; confirmed = $true }
            Invoke-Bootstrap -Method Post -Path "$runPath/kill" -Body $body | ConvertTo-Json -Depth 20
        } else {
            $body = @{ reason = $Reason }
            Invoke-Bootstrap -Method Post -Path "$runPath/stop" -Body $body | ConvertTo-Json -Depth 20
        }
        return
    }
}

if (-not $availability.providerReady) {
    throw "The SC-1430 provider runtime is not ready: $(@($availability.blockingReasons) -join '; ')"
}

$resolvedProposal = (Resolve-Path -LiteralPath $ProposalPath).Path
$proposal = Get-Content -Raw -Encoding UTF8 -LiteralPath $resolvedProposal | ConvertFrom-Json
foreach ($field in 'providerSessionId', 'providerTurnId', 'observation', 'executionEnvelope', 'snapshotBindings') {
    if ($null -eq $proposal.$field) {
        throw "Proposal $resolvedProposal is missing '$field'. See docs\SC-1498_BOOTSTRAP_RUNBOOK.md section 4."
    }
}

$previewBody = [ordered]@{
    providerSessionId = $proposal.providerSessionId
    providerTurnId = $proposal.providerTurnId
    observation = $proposal.observation
    executionEnvelope = $proposal.executionEnvelope
    snapshotBindings = $proposal.snapshotBindings
}
$preview = Invoke-Bootstrap -Method Post -Path '/previews' -Body $previewBody

Write-Host ''
Write-Host 'Exact one-use SC-1498 approval scope:' -ForegroundColor Yellow
$preview.exactScope | ConvertTo-Json -Depth 100
Write-Host ''
Write-Host "Orchestration: $($preview.orchestrationId)" -ForegroundColor Yellow
Write-Host "Request:       $($preview.requestId)" -ForegroundColor Yellow
Write-Host "Recording:     $($preview.recordingId)" -ForegroundColor Yellow
Write-Host "Scope digest:  $($preview.scopeDigest)" -ForegroundColor Yellow
Write-Host 'Review every value above. Any later change invalidates this approval.' -ForegroundColor Yellow
$confirmation = Read-Host "Type APPROVE $($preview.scopeDigest)"
if ($confirmation -cne "APPROVE $($preview.scopeDigest)") {
    throw 'Exact human approval was not provided. Nothing was approved or dispatched.'
}

# One clock read, millisecond precision, UTC: the server normalizes to microseconds.
$expiresAt = [DateTime]::UtcNow.AddMinutes($ExpiresInMinutes).ToString(
    "yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [System.Globalization.CultureInfo]::InvariantCulture)

$approvalBody = [ordered]@{
    providerSessionId = $proposal.providerSessionId
    providerTurnId = $proposal.providerTurnId
    observation = $proposal.observation
    executionEnvelope = $proposal.executionEnvelope
    snapshotBindings = $proposal.snapshotBindings
    approvedBy = $ApprovedBy
    expiresAt = $expiresAt
    scopeDigest = $preview.scopeDigest
    humanConfirmed = $true
    confirmation = 'CONFIRM'
}
$approval = Invoke-Bootstrap -Method Post -Path '/approvals' -Body $approvalBody
if (-not $approval.created) {
    Write-Warning "Approval $($approval.approvalId) already existed for this scope; it is reused, not recreated."
}
Write-Host "Approval $($approval.approvalId) expires at $($approval.expiresAt)." -ForegroundColor Yellow
Write-Host "Kill switch: .\scripts\sc1498-bootstrap.ps1 -OrchestrationId '$($approval.orchestrationId)' -KillSwitch" -ForegroundColor Yellow

$dispatchBody = [ordered]@{
    approvalId = $approval.approvalId
    observation = $proposal.observation
    executionEnvelope = $proposal.executionEnvelope
    snapshotBindings = $proposal.snapshotBindings
}

# Dispatch performs a bounded terminal wait (default 300000 ms) before it returns.
Write-Host "Dispatching orchestration $($approval.orchestrationId) with one-use approval." -ForegroundColor Yellow
Invoke-Bootstrap -Method Post -Path ('/approvals/' + (Escape-PathSegment $approval.approvalId) + '/dispatch') `
    -Body $dispatchBody -TimeoutSec 420 | ConvertTo-Json -Depth 100
