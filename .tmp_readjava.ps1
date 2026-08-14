$file = Get-ChildItem -Path $env:TEMP -Filter 'buildergoal-acltest-*.json' | Select-Object -First 1
if (-not $file) { Write-Output "NO FILE"; exit }
Write-Output "FILE: $($file.FullName)"
Write-Output "OWNER: $((Get-Acl $file.FullName).Owner)"
foreach ($a in (Get-Acl $file.FullName).Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights) $($a.AccessControlType)" }
try { $c = Get-Content -LiteralPath $file.FullName -Raw; Write-Output "READ_OK: $c" } catch { Write-Output "READ_FAIL: $($_.Exception.Message)" }
