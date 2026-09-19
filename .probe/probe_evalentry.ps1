$root=(Get-Location).Path
"=== Sheet.java: ALL method signatures (evaluate entry that assembles bindings map) ==="
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'public |private ' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 60
"=== the single choke point candidates: any method that takes/touches 'bindings' map + calls Expression.parse/evaluate. grep binds+eval co-occurrence ==="
Select-String -Path (Join-Path $root "src\functions\custom\Sheet.java") -Pattern 'new LinkedHashMap<String, Object>\(bindings\)|Map<String, Object> eff|evaluate\(|Expression\.parse\(|bindings\(\)' | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 40