$root=(Get-Location).Path
$w=Get-Content (Join-Path $root "src\functions\custom\WorkbookIO.java")
"=== WorkbookIO.java total lines: " + $w.Count + " ==="
"=== WORKBOOK WRITE: around createElement('sheets') / createElement('sheet') / appendChild / attributes (whole write method) ==="
for($i=0; $i -lt $w.Count; $i++){
  if($w[$i] -match 'toXml|LoadedSheet|LoadedWorkbook|fromXml'){ "--- marker {0,4}: {1}" -f ($i+1),$w[$i].Trim() }
}
"=== workbook write region: find 'sheets' element creation (search doc.createElement + appendChild) ==="
for($i=0; $i -lt $w.Count; $i++){
  if($w[$i] -match 'appendChild|setAttribute|createElement|\.attr|writeAttribute'){
    "=== hit {0,4}: {1}" -f ($i+1),$w[$i].Trim()
  }
}
