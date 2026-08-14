$tmp = [System.IO.Path]::GetTempPath()
$file = Join-Path $tmp 'buildergoal-nameicacls2.json'
Set-Content -LiteralPath $file -Value '{"clientArgs":["--world=360"]}' -Encoding UTF8
icacls $file /inheritance:r /grant:r "CZC8518C27\Beheerder:(R,W,D)" 2>&1 | Out-String | Write-Output
$acl = Get-Acl -LiteralPath $file
Write-Output "OWNER: $($acl.Owner)"
foreach ($a in $acl.Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights)" }
try { $c = Get-Content -LiteralPath $file -Raw; Write-Output "READ_OK: $c" } catch { Write-Output "READ_FAIL: $($_.Exception.Message)" }
Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
