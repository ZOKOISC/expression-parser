$root=(Get-Location).Path
$jc=$null; $jv=$null
$roots=@()
$roots += "$env:ProgramFiles\Eclipse Adoptium"
$roots += "$env:ProgramFiles\Eclipse Foundation"
$roots += "$env:ProgramFiles\Java"
$roots += "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
$roots += "$env:USERPROFILE\.jdks"
$roots += "C:\Program Files\Eclipse Adoptium"
$roots += "C:\Program Files\Eclipse Foundation"
foreach($r in $roots){
  if(-not (Test-Path $r)){ continue }
  $dirs = Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
  foreach($d in $dirs){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    $pcc = Test-Path $cc
    $pjj = Test-Path $jj
    if($pcc -and $pjj){ $jc=$cc; $jv=$jj; break }
  }
  if($jc){ break }
}
if(-not $jc){
  $found = Get-ChildItem -Path "C:\Program Files","C:\Program Files (x86)","$env:LOCALAPPDATA\Programs","$env:USERPROFILE\.jdks" -Filter "jdk*" -Directory -Recurse -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 60
  foreach($d in $found){
    $cc = Join-Path $d.FullName "bin\javac.exe"
    $jj = Join-Path $d.FullName "bin\java.exe"
    $pcc = Test-Path $cc
    $pjj = Test-Path $jj
    if($pcc -and $pjj){ $jc=$cc; $jv=$jj; break }
  }
}
if(-not $jc){ "NO JDK FOUND"; exit 2 }
$out = Join-Path $root "out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null
"JDK: $jc"
"=== compiling whole src tree ==="
& $jc -encoding UTF-8 -d $out -sourcepath (Join-Path $root "src") (Join-Path $root "src\Main.java") 2>&1 | ForEach-Object { $_ }
"javac exit=$LASTEXITCODE"
if($LASTEXITCODE -ne 0){ "BUILD FAIL"; Get-Content (Join-Path $root "compile_err.txt") -ErrorAction SilentlyContinue | Select-Object -First 30; exit 1 }
"=== running Main self-test (headless) ==="
& $jv -cp $out Main 2>&1 | ForEach-Object { $_ }
"java exit=$LASTEXITCODE"