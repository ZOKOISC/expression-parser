$root=(Get-Location).Path
$f=Get-Content (Join-Path $root "src\functions\custom\ArrayGetFunction.java")
"=== ArrayGetFunction.java FULL ==="
for($i=0;$i -lt $f.Count;$i++){ "{0,4}: {1}" -f ($i+1),$f[$i] }
"=== CellProvider interface (sheet-level provider used by ArrayGetFunction(2-arg)) ==="
Select-String -Path (Join-Path $root "src\functions\custom\CellProvider.java") -Pattern 'double|Object|at\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== does self-test/README document 3-arg get(sheet,r,c)? grep whole src ==="
Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object {
  $h=Select-String -Path $_.FullName -Pattern 'get\([^)]*,[^)]*,[^)]*\)\s*=|get\(sheet|get\(state|get\(1[,\s][0-9]' -ErrorAction SilentlyContinue
  if($h){ foreach($x in $h){ "{0,4}: {1}  [{2}]" -f $x.LineNumber,$x.Line.Trim(),$_.Name } }
} | Select-Object -First 14