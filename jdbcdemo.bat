@echo off
cd /d "%~dp0"

rem Frissiti a PATH-ot, ha uj telepites utan nem talalna a parancsokat
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "PATH=%%b;%PATH%"

echo Forditas...
javac -encoding UTF-8 -d out -sourcepath src src/graph/JdbcPersistenceExample.java
if errorlevel 1 (
    echo Hiba a forditas soran.
    pause
    exit /b 1
)

rem A JDBC illesztoprogramot a CLASSPATH-hoz kell adni, pl.:
rem   set CLASSPATH=C:\drivers\sqlite-jdbc.jar;out
echo JDBC pelda futtatasa...
java -cp "out;%CLASSPATH%" graph.JdbcPersistenceExample %*
pause
