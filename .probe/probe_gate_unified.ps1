$root=(Get-Location).Path
$jc=$null; $jv=$null
"=== seek JDK (Eclipse Adoptium / wildcard) ==="
$roots=@()
$roots += "C:\Program Files\Eclipse Adoptium"
$roots += "C:\Program Files\Eclipse Foundation"
$roots += "C:\Program Files\Java"
$roots += "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
$roots += "$env:LOCALAPPDATA\Programs\Eclipse Foundation"
$roots += "$env:USERPROFILE\.jdks"
$roots += "$env:ProgramW6432\Eclipse Adoptium"
foreach($r in $roots){
  if(-not (Test-Path $r)){ continue }
  $dirs = Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | Sort-Object { try { [version]($_.Name -replace 'jdk-?','') } catch { [version]'0' } } -Descending
  foreach($d in $dirs){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    if((Test-Path $cc) -and (Test-Path $jj)){ $jc=$cc; $jv=$jj; break }
  }
  if($jc){ break }
}
if(-not $jc){
  "wildcard fallback..."
  $found = Get-ChildItem "C:\Program Files","C:\Program Files (x86)","$env:LOCALAPPDATA\Programs","$env:USERPROFILE\.jdks","$env:LOCALAPPDATA\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | Select-Object -First 60
  foreach($d in $found){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    if((Test-Path $cc) -and (Test-Path $jj)){ $jc=$cc; $jv=$jj; break }
  }
}
if(-not $jc){ "NO JDK FOUND - cannot gate"; exit 3 }
"javac: $jc"
"java:  $jv"
$out = Join-Path $root "out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sf = Join-Path $root "src"
"=== javac whole src tree (Main.java entry, -sourcepath) ==="
& $jc -encoding UTF-8 -d $out -sourcepath $sf (Join-Path $sf "Main.java") 2>&1 | ForEach-Object { $_ }
$ec = $LASTEXITCODE
"javac exit=$ec"
if($ec -ne 0){ "=== BUILD FAILURE (javac) ==="; exit 1 }
"=== java -cp out Main (self-test) ==="
& $jv -cp $out Main 2>&1 | ForEach-Object { $_ }
"java exit=$LASTEXITCODE"
"========== GATE VERDICT =========="
if($LASTEXITCODE -eq 0){ "GATE: PASS - All checks passed." } else { "GATE: FAIL" }
exit $LASTEXITCODE