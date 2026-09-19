$root=(Get-Location).Path
"=== expr\VariableNode.java FULL (how a bare identifier resolves) ==="
$v=Get-Content (Join-Path $root "src\expr\VariableNode.java")
for($i=0;$i -lt $v.Count;$i++){ "{0,4}: {1}" -f ($i+1),$v[$i] }
"=== Sheet.java: bindings / variable resolution + where varsArea text lands ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'bindings|setBindingsText|getVariable|resolve\(|VariableNode|loadDefaults|parseVariables|varsToText' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== ArrayGetFunction.java: full sheet-arg branch 73..124 ==="
$a=Get-Content (Join-Path $root "src\functions\custom\ArrayGetFunction.java")
for($i=72;$i -lt 124 -and $i -lt $a.Count;$i++){ "{0,4}: {1}" -f ($i+1),$a[$i] }
"=== SheetBook: is there name->sheet lookup today? ==="
Select-String -Path (Join-Path $root "src\functions\custom\SheetBook.java") -Pattern 'name|indexOf|byName|lookup' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }