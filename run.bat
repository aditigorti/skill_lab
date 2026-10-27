@echo off
rem Compiles and starts the Shared Equipment Scheduler (Windows).
rem Double-click this file, or type  .\run.bat  in PowerShell inside this folder.
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
  echo.
  echo Compiling failed. Check that the Java JDK is installed: type  javac -version
  pause
  exit /b 1
)
java -cp out Main %*
pause
