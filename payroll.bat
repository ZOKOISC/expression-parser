@echo off
cd /d "%~dp0"

rem Frissiti a PATH-ot, ha uj telepites utan nem talalna a parancsokat
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"

echo Forditas...
javac -encoding UTF-8 -d out -sourcepath src src/apps/payroll/PayrollApp.java
if errorlevel 1 (
    echo Hiba a forditas soran.
    pause
    exit /b 1
)

echo Payroll alkalmazas inditasa...
java -cp out apps.payroll.PayrollApp 2> payroll_err.log
pause
