$who = whoami.exe
Write-Output "WHOAMI: $who"
$id = [System.Security.Principal.WindowsIdentity]::GetCurrent()
Write-Output "CURRENT_ID: $($id.Name)"
Write-Output "CURRENT_SID: $($id.User.Value)"
$file = Get-ChildItem -Path $env:TEMP -Filter 'buildergoal-acltest-*.json' | Select-Object -First 1
Write-Output "--- file ACE principals ---"
foreach ($a in (Get-Acl $file.FullName).Access) {
  try { $sid = (New-Object System.Security.Principal.NTAccount($a.IdentityReference.Value)).Translate([System.Security.Principal.SecurityIdentifier]).Value; Write-Output "ACE: $($a.IdentityReference.Value) -> SID $sid" } catch { Write-Output "ACE: $($a.IdentityReference.Value) -> translate fail" }
}
