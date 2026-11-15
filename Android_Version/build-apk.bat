@echo off
rem 一键打包：自动使用 JDK17（Gradle 8.0 不支持 JDK21）
rem 用法: build-apk.bat [debug^|release]   不带参数默认 debug
cd /d %~dp0
set "JAVA_HOME=%USERPROFILE%\.sdkman\candidates\java\17.0.20.1-tem"
set "TYPE=%1"
if "%TYPE%"=="" set "TYPE=debug"
call gradlew.bat assemble%TYPE%
echo.
echo APK 输出目录: %CD%\app\build\outputs\apk\%TYPE%\
