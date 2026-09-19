$root=(Get-Location).Path
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
"=== Sheet.java 17..60 (has name field + accessors?) ==="
for($i=16;$i -lt 60 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"=== WorkbookIO 30..45 + write-name region 93..102 + read-name region 157..167 ==="
$w=Get-Content (Join-Path $root "src\functions\custom\WorkbookIO.java")
"--- 30..45 ---"
for($i=29;$i -lt 45 -and $i -lt $w.Count;$i++){ "{0,4}: {1}" -f ($i+1),$w[$i] }
"--- write-name region (grep 'sheet.name()|setAttribute(\"name\"') ---"
Select-String -Path (Join-Path $root "src\functions\custom\WorkbookIO.java") -Pattern 'sheet.name\(\)|"name"|ls.name|LoadedSheet' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }