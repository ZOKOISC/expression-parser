$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== doRenameSheet method body (grep then print) ==="
$m=Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'private void doRenameSheet' | Select-Object -First 1
if(-not $m){ "doRenameSheet NOT FOUND"; exit 1 }
$start=$m.LineNumber
for($i=$start-1;$i -lt $start+16 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== tabs field + setTitleAt availability ==="
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'JTabbedPane tabs|setTitleAt\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }