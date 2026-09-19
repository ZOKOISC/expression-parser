$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
$m=Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'doRenameSheet|doDeleteSheet|setTitleAt|current\(\)' | ForEach-Object { $_.LineNumber }
"matches: $($m -join ',')"
"--- doRenameSheet body (find its line, print +12) ---"
$start=($m | Where-Object { $_ -gt 300 } | Select-Object -First 1)
if(-not $start){ $start=(Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'private void doRenameSheet').LineNumber }
for($i=$start-2;$i -lt $start+16 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- menu wiring (grep) ---"
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'doRenameSheet|setTitleAt' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }