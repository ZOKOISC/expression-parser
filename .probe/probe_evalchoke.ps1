$root=(Get-Location).Path
"=== [A] Sheet.java: EVERY 'bindings' read across the whole file (choke point find) ==="
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'bindings\b|bindings\(\)|bindings\.get|bindings\.put|Map<String, Object> b|new LinkedHashMap<String, Object>|effectiveBindings' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 30
"=== [B] the exact lines 237..245 of Sheet.java that BAKED '30' as a constant (the parseBindings NUMERIC-double ruin). Need: what did Main's self-test expect to prove variable write-back = cell text? dump 232..246 ==="
$s=Get-Content (Join-Path $root "src\functions\custom\Sheet.java")
for($i=231;$i -lt 247 -and $i -lt $s.Count;$i++){ "{0,4}: {1}" -f ($i+1),$s[$i] }
"=== [C] how ArrayGetFunction.evaluate 3-arg gets BINDINGS for the target sheet's own cell evaluation => does get(Budget,1,4) need the TARGET sheet's bindings too? It returns the raw cell VALUE (cell.getValue()) so NO - it only needs cellMap slot = book.get(sheetIdx). Confirm evaluateOn returns cell.getValue() and the param-0 sheetIdx === cells provider used is book.get(sheetIdx) -> then array-get lookup uses p.at() 1based. Full ArrayGetFunction 112..124 already known; re-verify the exact get-target cell via evaluateOn 126..140 ==="
Select-String -Path (Join-Path $root "src\functions\custom\ArrayGetFunction.java") -Pattern 'evaluateOn|book\.get|asDouble\(params.get\(0\)|return cell\.getValue|table\.get\(' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() }
"=== [D] VariableNode consumer chain: who CALLS VariableNode.evaluate with the bindings map? grep all passers of bindings into evaluate across expr package (to find the one caller = FunctionValue/Derived evaluator where we must inject sheet bindings) ==="
Get-ChildItem (Join-Path $root "src\expr") -Filter *.java | ForEach-Object {
  $h=Select-String -Path $_.FullName -Pattern 'evaluate\(bindings|\.evaluate\(bindings,|evaluate\(Map<String, Object> bindings'
  if($h){ "### $($_.Name)"; $h | ForEach-Object { "  {0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } }
}
"=== [E] The registry synthetic block: how BookGui builds/refreshes varsArea + does setBindingsText get called per sheet on rename (need hook). grep BookGui for setBindingsText + varsArea + bindings ==="
Select-String -Path (Join-Path $root "src\BookGui.java") -Pattern 'setBindingsText|parseBindings|varsArea\.setText|bindings\(\)\.clear|refreshBindings|sheet\.setBindings|getBindingsText' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 25