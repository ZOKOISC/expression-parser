$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== first-tab region 106..115 ==="
for($i=105;$i -lt 116 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doCreateSheet 279..288 ==="
for($i=278;$i -lt 289 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== loadWorkbook sheet loop 469..482 ==="
for($i=468;$i -lt 483 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doRenameSheet() + current() + tabs.setTitleAt grep ==="
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'doRenameSheet|setTitleAt|SheetView current\(|private SheetView current|sheet.name\(\)|setName\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }