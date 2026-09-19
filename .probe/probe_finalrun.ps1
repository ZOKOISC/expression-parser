$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== probe_final: 106..115 (first tab) / 141..158 (menu+wiring) / 280..288 / 348..362 rename-tab-retitle / current() 214 ==="
"--- 106..115 ---"
for($i=105;$i -lt 116 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- 141..158 ---"
for($i=140;$i -lt 159 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- 280..288 doCreateSheet ---"
for($i=279;$i -lt 289 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- grep rename/nav anchors ---"
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'doRenameSheet|setTitleAt|renameTo|current\(\)|sheet.name\(\)' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }