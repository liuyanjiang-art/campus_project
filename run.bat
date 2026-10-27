@echo off
rem 启动校园社团活动 App（Windows）
rem 使用项目内的 .gradle-home 作为 Gradle 数据目录，避免污染系统目录
setlocal
set GRADLE_USER_HOME=%~dp0.gradle-home
cd /d "%~dp0"
echo ============================================================
echo  西南交通大学 校园社团活动发布与报名 App
echo  启动完成后请用浏览器访问 http://localhost:8080
echo ============================================================
call "%~dp0gradlew.bat" run --console=plain
