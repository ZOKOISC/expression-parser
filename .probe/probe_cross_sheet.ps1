$root=(Get-Location).Path
"=== functions that mention SheetRef / book / get() across sheets ==="
Get-ChildItem (Join-Path $root "src\functions") -Recurse -Filter *.java | ForEach-Object {
  $hits=Select-String -Path $_.FullName -Pattern 'sheetIndex|CellRef|book\.|get\(|SheetRef|array|ARR|registry.register' | Select-Object -First 6
  if($hits){ "### $($_.Name)"; $hits | ForEach-Object { "  {0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } }
}
"=== how the self-test actually references other sheets (grep Main) ==="
Select-String -Path (Join-Path $root "src\Main.java") -Pattern "TAXBASE|\[2,0\]|\[1,0\]|a\[1|a\[2|sheet=|SheetBook" | ForEach-Object { "{0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } | Select-Object -First 16