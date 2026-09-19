$root=(Get-Location).Path
$w=Get-Content (Join-Path $root "src\functions\custom\WorkbookIO.java")
"=== WorkbookIO.java TOP 25..48 (LoadedSheet class at 26..44) ==="
for($i=24;$i -lt 48 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"=== confirm my 2 edits landed (grep 'sheet.name()' write + 'ls.name =' read) ==="
Select-String -Path (Join-Path $root "src\functions\custom\WorkbookIO.java") -Pattern 'sheet.name\(\)|ls.name =|setAttribute\("name|strAttr\(sheetEl, "name"\)' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== BookGui doCreateSheet + createSheetView + tabs wiring around 279..300 (get exact addTab + any existing rename) ==="
$g=Get-Content (Join-Path $root "src\BookGui.java")
for($i=278;$i -lt 301 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== BookGui: does SheetView expose sheet? (grep 'class SheetView|Sheet sheet;|final Sheet') ==="
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'class SheetView|Sheet sheet|final SheetView|SheetView createSheetView|private SheetView current|imports JOptionPane' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
