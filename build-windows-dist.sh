#!/bin/bash
set -e
cd "$(dirname "$0")"

APP_NAME="DromPriceParser"
JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jre_x64_windows_hotspot_21.0.6_7.zip"

MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
JAVA_HOME="/Users/vladislav/Library/Java/JavaVirtualMachines/temurin-22.0.2/Contents/Home"

echo ""
echo "================================================"
echo "  Сборка Windows дистрибутива $APP_NAME"
echo "================================================"
echo ""

# [1] Сборка JAR + exe через Maven + Launch4j
echo "[1/4] Сборка JAR и Windows exe..."
JAVA_HOME="$JAVA_HOME" "$MVN" package verify -DskipTests -Pwindows-dist -q
echo "      OK"

# [2] Windows JRE (кешируем)
echo "[2/4] Подготовка Windows JRE..."
mkdir -p .build-cache
if [ ! -f ".build-cache/jre-windows.zip" ]; then
    echo "      Скачиваем Temurin 21 для Windows (~55 МБ)..."
    curl -sL --progress-bar "$JRE_URL" -o .build-cache/jre-windows.zip
    echo "      OK"
else
    echo "      Уже скачан (кеш)"
fi

# [3] Сборка папки дистрибутива
echo "[3/4] Сборка дистрибутива..."
DIST="dist-windows/$APP_NAME"
rm -rf dist-windows
mkdir -p "$DIST"

cp "target/$APP_NAME.exe" "$DIST/"
cp src/main/resources/application.yml "$DIST/"

# Распаковать Windows JRE → переименовать в jre/
unzip -q .build-cache/jre-windows.zip -d "$DIST/"
JRE_DIR=$(ls "$DIST/" | grep -vE "\.(exe|yml)$" | head -1)
mv "$DIST/$JRE_DIR" "$DIST/jre"

# [4] ZIP
echo "[4/4] Создание ZIP..."
mkdir -p dist
(cd dist-windows && zip -qr "../dist/$APP_NAME.zip" "$APP_NAME/")

echo ""
echo "================================================"
echo "  ГОТОВО: dist/$APP_NAME.zip"
echo ""
echo "  Передайте коллеге:"
echo "  1. Распаковать $APP_NAME.zip"
echo "  2. Запустить $APP_NAME.exe"
echo "  При первом запуске Chromium скачается"
echo "  автоматически (~150 МБ, только один раз)"
echo "================================================"
echo ""
