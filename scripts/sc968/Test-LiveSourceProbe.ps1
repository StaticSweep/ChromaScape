[CmdletBinding()]
param([string] $ProductionManifestPath = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Acceptance.Runtime.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw 'Runtime PowerShell parse failed' }
foreach ($name in @('Wait-LiveSourceManifest','Sha256Bytes','BytesEqual','Write-JsonFile')) {
  $function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
  if ($null -eq $function) { throw "Missing helper $name" }
  # Evaluate only these audited helper definitions, never the runtime orchestrator.
  Invoke-Expression $function.Extent.Text
}
function Require([string] $Name) { return 'synthetic-unit-test-environment' }
function Start-Sleep { param($Milliseconds) }
function Invoke-Sc968Http {
  param($BaseUrl, $Path, $RequestHeaders)
  $script:calls++
  return [pscustomobject]@{StatusCode=$script:httpStatus;BodyBytes=$script:remoteBytes;BodyText='synthetic unit response'}
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sc968-source-probe-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$sourceBytes = [Text.Encoding]::UTF8.GetBytes('{"source_manifest_hash":"sha256:canonical-content-only","signature":{"value":"synthetic"}}')
$canonicalHash = 'sha256:canonical-content-only'
if ($ProductionManifestPath) {
  $sourceBytes = [IO.File]::ReadAllBytes($ProductionManifestPath)
  $canonicalHash = ([Text.Encoding]::UTF8.GetString($sourceBytes) | ConvertFrom-Json).source_manifest_hash
}
foreach ($case in @('valid-distinct-hashes','changed-local-bytes','changed-remote-bytes','not-found')) {
  $sourcePath = Join-Path $testRoot "$case-source.json"
  $evidencePath = Join-Path $testRoot "$case-probe.json"
  [IO.File]::WriteAllBytes($sourcePath, $sourceBytes)
  $identity = [pscustomobject]@{SourceManifestPath=$sourcePath;SourceManifestHash=$canonicalHash;SourceManifestFileHash=(Sha256Bytes $sourceBytes);SourceSnapshotId='synthetic-snapshot'}
  if ($identity.SourceManifestHash -eq $identity.SourceManifestFileHash) { throw 'Fixture must distinguish canonical and transport hashes' }
  $script:calls = 0
  $script:httpStatus = 200
  $script:remoteBytes = $sourceBytes
  if ($case -eq 'changed-local-bytes') { [IO.File]::AppendAllText($sourcePath, ' ') }
  if ($case -eq 'changed-remote-bytes') { $script:remoteBytes = [Text.Encoding]::UTF8.GetBytes('changed response') }
  if ($case -eq 'not-found') { $script:httpStatus = 404 }
  $failed = $false
  try { Wait-LiveSourceManifest -ExecutorUrl 'http://synthetic.invalid' -SourceIdentity $identity -EvidencePath $evidencePath -TimeoutSeconds 0 }
  catch { $failed = $true }
  if ($case -eq 'valid-distinct-hashes') {
    if ($failed) { throw 'Valid distinct canonical/transport hashes must pass' }
    $proof = Get-Content -Raw $evidencePath | ConvertFrom-Json
    if ($proof.status -ne 'PASS' -or -not $proof.byte_equal -or $proof.source_manifest_hash -ne $canonicalHash -or $proof.transport_sha256 -ne $identity.SourceManifestFileHash) { throw 'Probe lost a hash trust boundary' }
  } elseif (-not $failed) { throw "Invalid probe accepted: $case" }
  if ($case -eq 'changed-local-bytes' -and $script:calls -ne 0) { throw 'Local tamper must fail before HTTP' }
  Write-Output "PASS $case"
}
Write-Output 'PASS all four native source-probe cases (mock HTTP, no live acceptance claim)'
