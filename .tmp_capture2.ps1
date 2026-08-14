# Fast watcher: copy any fresh payload file to a safe dir before cleanup deletes it
$safe = 'C:\Dev\IdeaProjects\ChromaScape\payload-capture'
New-Item -ItemType Directory -Force -Path $safe | Out-Null
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
  $files = @(Get-ChildItem -Path $env:TEMP -Filter 'buildergoal-launch-*.json' -ErrorAction SilentlyContinue)
  foreach ($f in $files) {
    if ((Get-Date) - $f.LastWriteTime -lt [TimeSpan]::FromSeconds(3)) {
      $dest = Join-Path $safe $f.Name
      try { Copy-Item -LiteralPath $f.FullName -Destination $dest -Force; Write-Output "COPIED: $($f.Name)" } catch { Write-Output "COPY_ERR: $($_.Exception.Message)" }
    }
  }
  Start-Sleep -Milliseconds 200
}
