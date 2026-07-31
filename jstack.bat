@echo off
set JSTACK=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\jstack.exe
"%JSTACK%" %1 > F:\Dev\IdeaProjects\ChromaScape\jstack_out.txt 2>&1
echo exit=%ERRORLEVEL% >> F:\Dev\IdeaProjects\ChromaScape\jstack_out.txt
