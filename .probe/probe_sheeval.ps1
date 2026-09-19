$root=(Get-Location).Path
"=== Sheet.java: every Expression.parse / getBindings / evaluate( call site + the map passed ==="
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'Expression.parse|\.evaluate\(|bindings\(\)|bindings|varsText|setBindingsText|parseBindings|evalBindings' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 30
"=== the ONE evaluate entry that feeds VariableNode: show 360..410 (recomputeCell/evalCell) ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
for($i=358;$i -lt 411 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"=== FunctionRegistry: how bindings map is carried into VariableNode (grep registry for 'bindings' field/get/set used during parse+evaluate) ==="
Select-String -Path (Join-Path $root "src\functions\custom\FunctionRegistry.java") -Pattern 'bindings|bindings\(\)|Map<String, Object>|setBindings|lookupSpec|paramsFromText|arrayGetSpec|getSpec' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 25