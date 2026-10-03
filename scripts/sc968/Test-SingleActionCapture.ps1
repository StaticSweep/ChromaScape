$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Acceptance.Runtime.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Runtime PowerShell parse failed' }
$function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Require-CompletedCapture' }, $true)
if ($null -eq $function) { throw 'Missing completion gate' }
Invoke-Expression $function.Extent.Text
foreach ($count in @(0,1,2,4)) {
  $status = [pscustomobject]@{status='COMPLETED';tick_count=20;action_count=$count;recording_id='synthetic';recording_hash=('sha256:' + ('a'*64));control_allowed=$false}
  $failed = $false
  try { Require-CompletedCapture $status 'Synthetic test' 20 } catch { $failed = $true }
  if ($failed -ne ($count -ne 1)) { throw "Incorrect action-count decision for $count" }
}
Write-Output 'PASS all four native single-action cases (synthetic, not runtime evidence)'
