$root=(Get-Location).Path
$base=Join-Path $root "src\functions\custom"
"=== [1] Sheet.java fields 24..30 + accessors 44..60 ==="
$s=Get-Content (Join-Path $base "Sheet.java")
for($i=23;$i -lt 30 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"..."
for($i=43;$i -lt 61 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"=== [2] WorkbookIO: LoadedSheet class 30..45 + name write/read ==="
$w=Get-Content (Join-Path $base "WorkbookIO.java")
for($i=29;$i -lt 43 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"--- name write @ sheetEl (around 93..101) ---"
for($i=92;$i -lt 105 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"--- name read (grep) ---"
Select-String -Path (Join-Path $base "WorkbookIO.java") -Pattern 'ls.name|\.name =' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== [3] BookGui: buildUi first tab 104..118 + doCreateSheet 200..216 + doCreateSheet tab title + menu region 140..170 ==="
$g=Get-Content (Join-Path $root "src\BookGui.java")
"--- buildUi first-sheet block ---"
for($i=103;$i -lt 119 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- menu items -- grep added tabs / JMenuItem in 140..172 ---"
for($i=140;$i -lt 172 -and $i -lt $g.Count;$i++){ if($g[$i] -match 'JMenu|JMenuItem|add\(|addTab|doCreateSheet|tabs'){ "{0,4}: {1}" -f ($i+1),$g[$i] } }
"--- doCreateSheet whole (grep lines) ---"
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'doCreateSheet|addTab|setStatus\("Sheet|createSheetView|loadWorkbook' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 25