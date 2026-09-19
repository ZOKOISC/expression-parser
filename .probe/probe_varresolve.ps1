$root=(Get-Location).Path
"=== VariableNode.java FULL ==="
$v=Get-Content (Join-Path $root "src\expr\VariableNode.java")
for($i=0;$i -lt $v.Count;$i++){ "{0,4}: {1}" -f ($i+1),$v[$i] }
"=== Sheet.setBindingsText + varsText parsing (bindings map) ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'setBindingsText|bindings\.put|bindings\.clear|parseBinding|Pattern\.compile|split\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== FunctionRegistry lookups for 'name' (VariableNode consumers) ==="
$r=Get-Content (Join-Path $root "src\functions\custom\FunctionRegistry.java")
Select-String -Path (Join-Path $root "src\functions\custom\FunctionRegistry.java") -Pattern 'bindings|lookupVariable|resolveVariable|Map<String, ?Object>|getVariable|bind\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== where does the parser get variable VALUES for evaluation? grep expr Parser/Evaluator for VariableNode creation + bindings ==="
Get-ChildItem (Join-Path $root "src\expr") -Filter *.java | ForEach-Object {
  $hits=Select-String -Path $_.FullName -Pattern 'new VariableNode|VariableNode\(|bindings|lookupVariable|registry\.lookup|provider\.\w*\(name' -ErrorAction SilentlyContinue
  if($hits){ "### $($_.Name)"; $hits | ForEach-Object { "  {0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } }
} | Select-Object -First 30
"=== Main: how varsText drives TAXBASE/rate (bindings text used by createSheetView? recheck 108..113 createSheetView + doCreateSheet) ==="
$m=Get-Content (Join-Path $root "src\Main.java")
Select-String -Path (Join-Path $root "src\Main.java") -Pattern 'TAXBASE\[|rate = |setBindingsText|"rate' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 12