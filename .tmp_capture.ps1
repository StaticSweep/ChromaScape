# Poll for a fresh buildergoal-launch file and dump its ACL before cleanup
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
  $files = @(Get-ChildItem -Path $env:TEMP -Filter 'buildergoal-launch-*.json' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt (Get-Date).AddSeconds(-5) })
  if ($files.Count -gt 0) {
    $f = $files[0]
    Write-Output "FOUND: $($f.FullName) owner=$((Get-Acl $f).Owner)"
    foreach ($a in (Get-Acl $f).Access) { Write-Output "ACE: $($a.IdentityReference) $($a.FileSystemRights) $($a.AccessControlType)" }
    break
  }
  Start-Sleep -Milliseconds 500
}
