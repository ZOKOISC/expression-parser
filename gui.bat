@echo off
cd /d "%~dp0"

rem Frissiti a PATH-ot, ha uj telepites utan nem talalna a parancsokat
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"

echo Forditas...
javac -d out -sourcepath src src/Main.java src/Gui.java
if errorlevel 1 (
    echo Hiba a forditas soran.
    pause
    exit /b 1
)

echo GUI inditasa...
java -cp out Gui 2> debug_err.log
pause