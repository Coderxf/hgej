#!/usr/bin/env bash
# 一键打包：自动使用 JDK17（Gradle 8.0 不支持 JDK21）
# 用法: ./build-apk.sh [debug|release]   不带参数默认 debug
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.20.1-tem"
TYPE="${1:-debug}"
./gradlew "assemble${TYPE^}"
echo ""
echo "APK 输出目录: $(pwd)/app/build/outputs/apk/${TYPE}/"
