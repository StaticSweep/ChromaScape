@echo off
REM Helper script to install Java 17 for ChromaScape
echo Opening Java 17 download page in browser...
echo.
echo Please download and install "Eclipse Temurin JDK 17" (Windows x64 JDK)
echo.
start https://adoptium.net/temurin/releases/?version=17
echo.
echo After installation, update trigger_listener.py line 17:
echo JAVA_17_PATH = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.XX-hotspot"
echo.
pause
