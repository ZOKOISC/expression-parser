$root=(Get-Location).Path
"=== CellRef.java (sheet-qualified ref type) head ==="
$c=Get-Content (Join-Path $root "src\expr\CellRef.java")
for($i=0;$i -lt [Math]::Min(60,$c.Count);$i++){ "{0,4}: {1}" -f ($i+1),$c[$i] }
"=== expr refs: any grammar that parses a SHEET qualifier? grep Parser/Lexer/ExprParser ==="
$src=Join-Path $root "src"
$m=Select-String -Path (Join-Path $src "BookGui.java") -Pattern 'SheetRef|parseSheetRef|sheetQualified|Tabs?\.|sheetIndex\(\)' -ErrorAction SilentlyContinue
$p=@()
foreach($hit in $m){ "{0,4}: {1}" -f $hit.LineNumber,$hit.Line.Trim() }
"=== does get(row,col) resolve acro-sheets? ArrayGetFunction full ==="
$f=Get-Content (Join-Path $src "functions\custom\ArrayGetFunction.java")
for($i=0;$i -lt $f.Count;$i++){ "{0,4}: {1}" -f ($i+1),$f[$i] }
"=== Expression parser: is there '!sheet' syntax in the grammar (Parser/Tokenizer) ==="
Get-ChildItem (Join-Path $src "expr") -Filter *.java | ForEach-Object {
  $hits=Select-String -Path $_.FullName -Pattern "Sheet|!|\bget\(|\barr\(|sheetRef|CellProvider|book\." | Select-Object -First 5
  if($hits){ "### $($_.Name)"; $hits | ForEach-Object { "  {0,4}: {1}" -f $_.LineNumber,$_.Line.Trim() } }
}