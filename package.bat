@echo off
setlocal
cd /d "%~dp0"

echo.
echo ================================================
echo   Сборка дистрибутива DromPriceParser
echo ================================================
echo.

rem === Проверки ===
where jpackage >nul 2>&1
if errorlevel 1 (
    echo ОШИБКА: jpackage не найден. Установите JDK 21+ и добавьте в PATH.
    pause & exit /b 1
)

rem === Шаг 1: Сборка JAR ===
echo [1/4] Сборка JAR...
call mvn package -DskipTests -q
if errorlevel 1 ( echo ОШИБКА при сборке & pause & exit /b 1 )

rem === Шаг 2: Создание app-image с JRE ===
echo [2/4] Создание app-image с JRE...
if exist dist rmdir /s /q dist
if exist _pkg_tmp rmdir /s /q _pkg_tmp
mkdir _pkg_tmp
copy target\drom-price-parser-1.0.0.jar _pkg_tmp\ > nul

set ICON_ARG=
if exist src\main\resources\icon.ico set ICON_ARG=--icon src\main\resources\icon.ico

jpackage ^
  --type app-image ^
  --name "DromPriceParser" ^
  --app-version 1.0.0 ^
  --input _pkg_tmp ^
  --main-jar drom-price-parser-1.0.0.jar ^
  --dest dist ^
  %ICON_ARG% ^
  --java-options "-Xmx512m" ^
  --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
  --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Dplaywright.browsers.path=$APPDIR/browsers"

if errorlevel 1 ( echo ОШИБКА при создании app-image & rmdir /s /q _pkg_tmp & pause & exit /b 1 )
rmdir /s /q _pkg_tmp

rem === Копируем application.yml рядом с exe (чтобы можно было редактировать настройки) ===
copy src\main\resources\application.yml dist\DromPriceParser\ > nul

rem === Шаг 3: Бандлим Playwright Chromium ===
echo [3/4] Добавляем Chromium в дистрибутив...
set PLAYWRIGHT_SRC=%LOCALAPPDATA%\ms-playwright

if not exist "%PLAYWRIGHT_SRC%" (
    echo.
    echo [WARN] Chromium не найден: %PLAYWRIGHT_SRC%
    echo        Нужно установить его один раз на ЭТОЙ машине:
    echo.
    echo          java -cp target\drom-price-parser-1.0.0.jar com.microsoft.playwright.CLI install chromium
    echo.
    echo        После этого пересоберите дистрибутив.
    echo        Без Chromium приложение скачает его само при первом запуске у коллеги.
    echo.
) else (
    xcopy /e /i /q "%PLAYWRIGHT_SRC%" "dist\DromPriceParser\browsers" > nul
    echo        OK: Chromium добавлен — приложение будет работать без интернета
)

rem === Шаг 4: Упаковка в ZIP ===
echo [4/4] Упаковка в ZIP...
if exist dist\DromPriceParser.zip del dist\DromPriceParser.zip
powershell -NoProfile -Command "Compress-Archive -Path 'dist\DromPriceParser' -DestinationPath 'dist\DromPriceParser.zip'"
if errorlevel 1 ( echo ОШИБКА при создании ZIP & pause & exit /b 1 )

echo.
echo =====================================================
echo  ГОТОВО: dist\DromPriceParser.zip
echo.
echo  Передайте коллеге этот файл. Инструкция:
echo    1. Распаковать DromPriceParser.zip
echo    2. Запустить DromPriceParser.exe
echo    3. Готово — Java и браузер уже внутри
echo =====================================================
pause
