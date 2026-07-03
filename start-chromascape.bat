@echo off
set "SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/chromascape?sslmode=disable"
set "SPRING_DATASOURCE_USERNAME=chromascape"
set "SPRING_DATASOURCE_PASSWORD=chromascape123"
set "BUILDER_GOAL_MICRO6_WORLD=360"
gradlew.bat --no-daemon bootRun -x processResources
