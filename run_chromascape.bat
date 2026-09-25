@echo off
cd /d "%~dp0"
gradlew.bat bootRun > chromascape_output.log 2>&1
pause
