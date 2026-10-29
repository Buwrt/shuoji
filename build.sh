#!/usr/bin/env bash
# 紫罗兰工具箱（手机版）一键构建脚本
# 用法：
#   ./build.sh debug     构建调试版 APK
#   ./build.sh release   构建发布版 APK（存在 keystore.properties 时自动签名）
#   ./build.sh clean     清理构建产物
set -e

TYPE="${1:-debug}"
GRADLE="gradle"
if [ -x "./gradlew" ]; then
  GRADLE="./gradlew"
fi

echo "==> 检查 Java 环境"
if ! java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])'; then
  echo "错误：需要 JDK 17 或更高版本，当前为："
  java -version 2>&1 | head -1
  exit 1
fi

if [ ! -f local.properties ] && [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "错误：请先设置 Android SDK 路径，例如："
  echo "  echo 'sdk.dir=/Users/你的用户名/Library/Android/sdk' > local.properties"
  exit 1
fi

case "$TYPE" in
  clean)
    echo "==> 清理"
    $GRADLE clean
    ;;
  release)
    echo "==> 构建发布版 APK"
    $GRADLE :app:assembleRelease
    echo "==> 产物：app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "==> 构建调试版 APK"
    $GRADLE :app:assembleDebug
    echo "==> 产物：app/build/outputs/apk/debug/app-debug.apk"
    ;;
esac

echo "==> 完成"
