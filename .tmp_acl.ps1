$tmp = [System.IO.Path]::GetTempPath()
$file = Join-Path $tmp 'buildergoal-launch-99999-test.json'
Set-Content -LiteralPath $file -Value '{"clientArgs":["--world=360"]}' -Encoding UTF8
# mimic restrictPayloadFile: strip to owner only
$acl = Get-Acl -LiteralPath $file
$owner = $acl.Owner
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule($owner, 'ReadData,WriteData,AppendData,ReadAttributes,WriteAttributes,Delete,ReadPermissions,ChangePermissions,Synchronize', 'Allow')
$acl.SetAccessRule($rule)
$acl.AccessRuleProtection = $true  # remove inherited
Set-Acl -LiteralPath $file $acl
Write-Output "OWNER: $((Get-Acl $file).Owner)"
foreach ($a in (Get-Acl $file).Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights)" }
try { $c = Get-Content -LiteralPath $file -Raw; Write-Output "READ_OK: $c" } catch { Write-Output "READ_FAIL: $($_.Exception.Message)" }
Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
