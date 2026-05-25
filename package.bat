@echo off
setlocal
cd /d "%~dp0"

echo === Шаг 1/3: Сборка JAR ===
call mvn package -DskipTests -q
if errorlevel 1 (
    echo ОШИБКА при сборке Maven
    pause & exit /b 1
)

echo === Шаг 2/3: Подготовка ===
if exist dist rmdir /s /q dist
if exist _pkg_tmp rmdir /s /q _pkg_tmp
mkdir _pkg_tmp
copy target\drom-price-parser-1.0.0.jar _pkg_tmp\ > nul

echo === Шаг 3/3: Упаковка с JRE ===
jpackage ^
  --type app-image ^
  --name "DromPriceParser" ^
  --app-version 1.0.0 ^
  --input _pkg_tmp ^
  --main-jar drom-price-parser-1.0.0.jar ^
  --dest dist ^
  --java-options "-Xmx512m" ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED"

if errorlevel 1 (
    echo ОШИБКА при создании дистрибутива
    rmdir /s /q _pkg_tmp
    pause & exit /b 1
)

rmdir /s /q _pkg_tmp
copy src\main\resources\application.yml dist\DromPriceParser\ > nul

echo.
echo =====================================================
echo  Готово!
echo  Папка: dist\DromPriceParser\
echo  Запуск: dist\DromPriceParser\DromPriceParser.exe
echo  ВАЖНО: при первом запуске на новой машине
echo  Playwright скачает Chromium (~150 МБ)
echo =====================================================
pause
