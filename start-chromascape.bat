@echo off
set SPRING_DATASOURCE_URL=jdbc:postgresql://172.29.153.221:5433/chromascape
set SPRING_DATASOURCE_USERNAME=chromascape
set SPRING_DATASOURCE_PASSWORD=chromascape123
gradlew.bat bootRun
