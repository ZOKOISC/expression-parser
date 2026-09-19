$root=(Get-Location).Path
"=== Sheet.java 18..60 (name field + accessors truth) ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
for($i=17;$i -lt 60 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"=== WorkbookIO.java LoadedSheet 30..44 + write region 90..105 + read region 160..170 ==="
$w=Get-Content (Join-Path $root "src\functions\custom\WorkbookIO.java")
"--- LoadedSheet class ---"
for($i=29;$i -lt 44 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"--- write head (name attr not yet) 90..100 ---"
for($i=89;$i -lt 100 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"--- read head (name attr not yet) 160..170 ---"
for($i=159;$i -lt 170 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"=== BookGui.java regions ==="
$g=Get-Content (Join-Path $root "src\BookGui.java")
"--- buildUi first-sheet + tabs.addTab 108..114 ---"
for($i=107;$i -lt 115 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- menu region 141..152 ---"
for($i=140;$i -lt 153 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- doCreateSheet 279..287 ---"
for($i=278;$i -lt 288 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"--- loadWorkbook sheet loop 470..486 ---"
for($i=469;$i -lt 487 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== SheetBook.java size/get/indexOf 34..46 ==="
$b=Get-Content (Join-Path $root "src\functions\custom\SheetBook.java")
for($i=33;$i -lt 47 -and $i -lt $b.Count;$i++){ "{0,4}: {1}" -f ($i+1),$b[$i] }