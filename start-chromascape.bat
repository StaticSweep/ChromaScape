@echo off
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/chromascape?sslmode=disable
set SPRING_DATASOURCE_USERNAME=chromascape
set SPRING_DATASOURCE_PASSWORD=chromascape123
gradlew.bat bootRun
