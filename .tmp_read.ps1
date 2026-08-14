$file = 'C:\Users\Beheerder\AppData\Local\Temp\buildergoal-launch-5103193429776725900.json'
try {
  $acl = Get-Acl -LiteralPath $file
  Write-Output "OWNER: $($acl.Owner)"
  foreach ($a in $acl.Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights) $($a.AccessControlType)" }
  $content = Get-Content -LiteralPath $file -Raw
  Write-Output "READ_OK: $content"
} catch { Write-Output "READ_FAIL: $($_.Exception.Message)" }
