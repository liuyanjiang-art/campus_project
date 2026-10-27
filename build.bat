@echo off
rem 构建并运行全部测试
setlocal
set GRADLE_USER_HOME=%~dp0.gradle-home
cd /d "%~dp0"
call "%~dp0gradlew.bat" build --console=plain
