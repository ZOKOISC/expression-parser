$root=(Get-Location).Path
$w=Get-Content (Join-Path $root "src\functions\custom\WorkbookIO.java")
"=== A) WorkbookIO write method 76..138 (to append name attr on <sheet>) ==="
for($i=75;$i -lt 138 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"=== B) WorkbookIO fromXml LoadedSheet 150..210 (to read name attr) ==="
for($i=149;$i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
