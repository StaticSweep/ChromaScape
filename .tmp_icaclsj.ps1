$tmp = $env:TEMP
java -cp $tmp IcaclsTest 2>&1
# now read the produced file from a separate process
$file = Get-ChildItem -Path $tmp -Filter 'buildergoal-icacls-*.json' | Select-Object -First 1
if ($file) { try { $c = Get-Content -LiteralPath $file.FullName -Raw; Write-Output "READ_OK: $c" } catch { Write-Output "READ_FAIL: $($_.Exception.Message)" } }
