$root=(Get-Location).Path
$jdk="$env:JAVA_HOME"
if(-not $jdk -or -not (Test-Path $jdk)){ $jdk="$env:ProgramFiles\Java" }
if(-not (Test-Path (Join-Path $jdk "bin"))){
  $candidate=Get-ChildItem -Path "C:\Program Files\Java","C:\Program Files\Eclipse Adoptium","$env:LOCALAPPDATA\Programs\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
  if($candidate){ $jdk=$candidate.FullName }
}
$jc=Join-Path $jdk "bin\javac.exe"
$jv=Join-Path $jdk "bin\java.exe"
"JDK: $jc"
"EXISTS javac: $(Test-Path $jc)  EXISTS java: $(Test-Path $jv)"
$out=Join-Path $root "out"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null
$src=Join-Path $root "src"
"=== compile (whole tree) ==="
& $jc -encoding UTF-8 -d $out -sourcepath $src (Join-Path $src "Main.java") 2>&1
$code=$LASTEXITCODE
"javac exit: $code"
if($code -ne 0){ "BUILD FAIL"; exit 1 }
"BUILD OK - running Main (self-test gate)..."
& $jv -cp $out Main 2>&1
"main exit: $LASTEXITCODE"