$root=(Get-Location).Path
"=== [1] SheetBook.java FULL (cellMap provider / sheets list / name lookup? 39..46) ==="
$b=Get-Content (Join-Path $root "src\functions\custom\SheetBook.java")
for($i=0;$i -lt $b.Count;$i++){ "{0,4}: {1}" -f ($i+1),$b[$i] }
"=== [2] Sheet.java: bindings wiring (setBindingsText + parse lines + variable read) ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'bindings|Bindings|varsText|Variables|parse|VariableNode|registry\.' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 30
"=== [3] VariableNode.java FULL (how a bare name resolves + where bindings map lives) ==="
$v=Get-Content (Join-Path $root "src\expr\VariableNode.java")
for($i=0;$i -lt $v.Count;$i++){ "{0,4}: {1}" -f ($i+1),$v[$i] }
"=== [4] what renders TAXBASE (the bracket syntax) -> ArrayGetFunction consumers + Sheet.currentProvider ?? grep get/sheetIndex ===="
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'currentProvider|provider\(\)|setProvider|CellProvider|sheetIndex\(\)' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 20