@echo off
cd /d F:\Dev\IdeaProjects\ChromaScape
gradlew.bat --no-daemon --console=plain bootRun > F:\Dev\IdeaProjects\ChromaScape\bootrun_live.log 2>&1
