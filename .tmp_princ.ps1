try {
  # Mimic Java's UserPrincipalLookupService.lookupPrincipalByName(user.name)
  $u = New-Object System.Security.Principal.NTAccount('Beheerder')
  $sid = $u.Translate([System.Security.Principal.SecurityIdentifier])
  Write-Output "NTAccount Beheerder -> SID $sid"
  $owner = (Get-Acl -LiteralPath (Join-Path $env:TEMP 'buildergoal-launch-5103193429776725900.json')).Owner
  Write-Output "leftover file owner: $owner"
} catch { Write-Output "ERR: $($_.Exception.Message)" }
