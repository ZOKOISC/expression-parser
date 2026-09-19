$g=Get-Content (Join-Path (Get-Location).Path "src\BookGui.java")
"=== 107..114 first-sheet tab-title line ==="
for($i=106;$i -lt 115 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 141..153 menu (rename item?) ==="
for($i=140;$i -lt 154 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 279..293 doCreateSheet ==="
for($i=278;$i -lt 294 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 466..486 loadWorkbook sheet loop ==="
for($i=465;$i -lt 487 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== current() + doRenameSheet exist? grep ==="
Select-String -Path (Join-Path (Get-Location).Path "src\BookGui.java") -Pattern 'doRenameSheet|private SheetView current\(\)|void doRenameSheet' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }