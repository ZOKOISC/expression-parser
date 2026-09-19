$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== probe BookGui EXACT lines 106..114 (first tab) ==="
for($i=105;$i -lt 115 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 142..156 (menu wiring) ==="
for($i=141;$i -lt 157 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 205..214 (SheetView class/fields) ==="
for($i=204;$i -lt 215 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 278..301 (doCreateSheet + doDeleteSheet) ==="
for($i=277;$i -lt 302 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== 466..487 (loadWorkbook sheet loop) ==="
for($i=465;$i -lt 488 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doCreateSheet/rename method anchor: doDeleteSheet end 297..301 ==="
for($i=296;$i -lt 302 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }