$root=(Get-Location).Path
$jc=$null;$jv=$null
$toolchains=@()
$toolchains += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue
$toolchains += Get-ChildItem "C:\Program Files\Eclipse Foundation" -Directory -ErrorAction SilentlyContinue
$toolchains += Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue
$toolchains += Get-ChildItem "$env:ProgramFiles\Zulu" -Directory -ErrorAction SilentlyContinue
$toolchains += Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue
foreach($td in ($toolchains | Sort-Object Name -Descending)){
  $c=Join-Path $td.FullName "bin\javac.exe"; $j=Join-Path $td.FullName "bin\java.exe"
  if(Test-Path $c -and Test-Path $j){ $jc=$c; $jv=$j; break }
}
"jc=$jc"
"jv=$jv"
if(-not $jc -or -not $jv){ "NO JDK FOUND"; exit 2 }
$out=Join-Path $root "out"
if(Test-Path $out){ Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$lof=Join-Path $root "javac_log.txt"
& $jc -encoding UTF-8 -d $out -sourcepath (Join-Path $root "src") (Join-Path $root "src\Main.java") *>&1 | Out-File $lof
$ec=$LASTEXITCODE
"javac exit=$ec"
if($ec -eq 0){
  & $jv -cp $out Main *>&1 | Out-File (Join-Path $root "java_log.txt")
  "java exit=$LASTEXITCODE"
  $jr=Get-Content (Join-Path $root "java_log.txt")
  $jr | ForEach-Object { $_ }
  if($LASTEXITCODE -eq 0){ "GATE: PASS" } else { "GATE: FAIL" }
} else {
  "--- javac log head ---"
  Get-Content $lof | Select-Object -First 40
  "GATE: FAIL (compile)"
}