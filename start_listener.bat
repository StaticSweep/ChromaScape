@echo off
REM ChromaScape Trigger Listener Startup Script
REM This starts the Python HTTP listener that waits for Docker container triggers

echo Starting ChromaScape trigger listener...
cd C:\Users\Beheerder\IdeaProjects\ChromaScape
python trigger_listener.py
