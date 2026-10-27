@echo off
rem Compiles the program and the tests, then runs the tests (Windows).
rem Double-click this file, or type  .\run-tests.bat  in PowerShell inside this folder.
cd /d "%~dp0"
if not exist out-test mkdir out-test
javac -encoding UTF-8 -d out-test src\*.java test\*.java
if errorlevel 1 (
  echo.
  echo Compiling failed. Check that the Java JDK is installed: type  javac -version
  pause
  exit /b 1
)
java -cp out-test SchedulerTests
pause
