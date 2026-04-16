@echo off
setlocal enabledelayedexpansion

:: ==========================================
:: 配置区域
:: ==========================================
SET MODULE_NAME=manager
SET KEYSTORE_FILE=release.jks
SET KEYSTORE_ALIAS=release
SET KEYSTORE_PASS=123456
SET APK_NAME=manager-release
:: ==========================================

echo [*] Starting Build Process...

:: 1. 编译 Release APK
call gradlew.bat :%MODULE_NAME%:assembleRelease
if %ERRORLEVEL% NEQ 0 (
    echo [!] Gradle build failed!
    pause
    exit /b %ERRORLEVEL%
)

:: 2. 定位生成的未签名 APK
SET UNSIGNED_APK=%MODULE_NAME%\build\outputs\apk\release\%MODULE_NAME%-release-unsigned.apk
if not exist "%UNSIGNED_APK%" (
    :: 有些 AGP 版本生成的文件名可能不带 -unsigned
    SET UNSIGNED_APK=%MODULE_NAME%\build\outputs\apk\release\%MODULE_NAME%-release.apk
)

echo [*] Found APK: %UNSIGNED_APK%

:: 3. 检查 Android SDK 路径 (用于查找 build-tools)
if "%ANDROID_HOME%"=="" (
    echo [!] ANDROID_HOME environment variable not set!
    echo [!] Please set it or edit this script.
    pause
    exit /b 1
)

:: 找到最新的 build-tools
for /f "delims=" %%i in ('dir /b /ad /on "%ANDROID_HOME%\build-tools"') do set BUILD_TOOLS_VER=%%i
SET BUILD_TOOLS_PATH=%ANDROID_HOME%\build-tools\%BUILD_TOOLS_VER%
echo [*] Using Build-Tools: %BUILD_TOOLS_VER%

:: 4. 检查/生成 Keystore
if not exist "%KEYSTORE_FILE%" (
    echo [*] Generating new keystore: %KEYSTORE_FILE%
    keytool -genkey -v -keystore %KEYSTORE_FILE% -alias %KEYSTORE_ALIAS% -keyalg RSA -keysize 2048 -validity 10000 -storepass %KEYSTORE_PASS% -keypass %KEYSTORE_PASS% -dname "CN=Zheng, OU=Inject, O=Zheng, L=SH, S=SH, C=CN"
)

:: 5. Zipalign 优化
echo [*] Running zipalign...
"%BUILD_TOOLS_PATH%\zipalign.exe" -v -f 4 "%UNSIGNED_APK%" "%APK_NAME%-aligned.apk"

:: 6. Apksigner 签名 (v1+v2+v3)
echo [*] Signing APK with v1, v2, and v3 schemes...
"%BUILD_TOOLS_PATH%\apksigner.bat" sign --ks %KEYSTORE_FILE% --ks-pass pass:%KEYSTORE_PASS% --ks-key-alias %KEYSTORE_ALIAS% --key-pass pass:%KEYSTORE_PASS% --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "%APK_NAME%-signed.apk" "%APK_NAME%-aligned.apk"

:: 7. 验证签名
echo [*] Verifying signature...
"%BUILD_TOOLS_PATH%\apksigner.bat" verify -v "%APK_NAME%-signed.apk"

echo.
echo ==========================================
echo [+] Success! Final APK: %APK_NAME%-signed.apk
echo ==========================================
del "%APK_NAME%-aligned.apk"
pause
