$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== buildUi first-sheet block 107..114 ==="
for($i=106;$i -lt 115 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== createSheetView 174..202 ==="
for($i=173;$i -lt 203 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doCreateSheet 280..288 ==="
for($i=279;$i -lt 289 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== loadWorkbook sheet-loop 468..482 ==="
for($i=467;$i -lt 483 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doDeleteSheet 200..208 (current() context) ==="
for($i=199;$i -lt 209 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== menu block 141..151 ==="
for($i=140;$i -lt 152 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== current() method 209..211 ==="
for($i=208;$i -lt 212 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }