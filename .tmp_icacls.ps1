$tmp = [System.IO.Path]::GetTempPath()
$file = Join-Path $tmp 'buildergoal-launch-icacls-test.json'
Set-Content -LiteralPath $file -Value '{"clientArgs":["--world=360"]}' -Encoding UTF8
# Full replace: remove inheritance, grant ONLY owner
icacls $file /inheritance:r /grant:r "*S-1-5-21-757207029-2423374295-4159724514-1001:(R,W,D)" | Out-Null
Write-Output "=== owner ==="
(Get-Acl $file).Owner
Write-Output "=== ACEs ==="
foreach ($a in (Get-Acl $file).Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights)" }
try { $c = Get-Content -LiteralPath $file -Raw; Write-Output "READ_OK: $c" } catch { Write-Output "READ_FAIL: $($_.Exception.Message)" }
Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
