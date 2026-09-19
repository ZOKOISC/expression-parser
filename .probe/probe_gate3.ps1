$root=(Get-Location).Path
$jc=$null;$jv=$null
$pot=@()
$pot += "C:\Program Files\Eclipse Adoptium"
$pot += "C:\Program Files\Eclipse Foundation"
$pot += "C:\Program Files\Java"
$pot += "$env:ProgramFiles\Eclipse Foundation"
$pot += "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
$pot += "$env:USERPROFILE\.jdks"
foreach($p in $pot){
  if(-not (Test-Path $p)){ continue }
  $jcands = Get-ChildItem -Path $p -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
  foreach($d in $jcands){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    if(Test-Path $cc -and Test-Path $jj){ $jc=$cc; $jv=$jj; break }
  }
  if($jc){ break }
}
if(-not $jc){
  $found = Get-ChildItem -Path "C:\Program Files","C:\Program Files (x86)","$env:LOCALAPPDATA\Programs","$env:USERPROFILE\.jdks" -Filter "jdk*" -Directory -Recurse -Depth 2 -ErrorAction SilentlyContinue | Select-Object -First 20
  foreach($d in $found){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    if(Test-Path $cc -and Test-Path $jj){ $jc=$cc; $jv=$jj; break }
  }
}
if(-not $jc){ "NO JDK FOUND - cannot gate"; exit 3 }
$out = Join-Path $root "out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null
"JDK: $jc"
"=== javac whole src ==="
& $jc -encoding UTF-8 -d $out -sourcepath (Join-Path $root "src") (Join-Path $root "src\Main.java") 2>&1
$ec=$LASTEXITCODE
"javac exit=$ec"
if($ec -ne 0){ "BUILD FAIL"; exit 1 }
"=== java Main self-test ==="
& $jv -cp $out Main 2>&1
"java exit=$LASTEXITCODE"