@echo off
cd /d "C:\Users\29507\Documents\trae_projects\campusproject"
set GRADLE_USER_HOME=C:\Users\29507\Documents\trae_projects\campusproject\.gradle-home
set HOST=0.0.0.0
set PORT=8080
call gradlew.bat run --console=plain > "C:\Users\29507\Documents\trae_projects\campusproject\.run\backend.log" 2>&1
