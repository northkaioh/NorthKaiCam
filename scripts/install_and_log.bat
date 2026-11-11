@echo off
setlocal
cd /d "%~dp0"
set OUT=%~dp0rear_debug.txt
set FULL=%~dp0rear_logcat_full.txt
set ADB=
for /f "delims=" %%i in ('where adb 2^>nul') do if not defined ADB set "ADB=%%i"
for %%p in ("C:\platform-tools\adb.exe" "C:\adb\adb.exe" "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" "%USERPROFILE%\platform-tools\adb.exe" "%USERPROFILE%\Downloads\platform-tools\adb.exe" "C:\Program Files (x86)\Minimal ADB and Fastboot\adb.exe" "C:\Program Files\platform-tools\adb.exe") do if not defined ADB if exist %%p set "ADB=%%~p"
if not defined ADB (
  for /f "delims=" %%i in ('dir /s /b C:\adb.exe 2^>nul') do if not defined ADB set "ADB=%%i"
)
echo ADB=%ADB% > "%OUT%"
if not defined ADB ( echo ADB NAO ENCONTRADO >> "%OUT%" & goto :eof )
"%ADB%" devices >> "%OUT%" 2>&1
echo ==== INSTALL ==== >> "%OUT%"
"%ADB%" install -r "%~dp0camera\GCam_SRK_RearScreen_v6.apk" >> "%OUT%" 2>&1
"%ADB%" shell input keyevent KEYCODE_WAKEUP
"%ADB%" logcat -c
"%ADB%" shell "mkdir -p /sdcard/Download/Northkai/xml && cp -n /sdcard/HyperCam/Strike/* /sdcard/Download/Northkai/xml/" >> "%OUT%" 2>&1
echo ==== START ==== >> "%OUT%"
"%ADB%" shell am force-stop com.google.android.srkaiohcam
"%ADB%" shell am start -n com.google.android.srkaiohcam/com.google.android.apps.camera.legacy.app.activity.main.CameraActivity >> "%OUT%" 2>&1
timeout /t 10 /nobreak >nul
echo ==== DISPLAYS ==== >> "%OUT%"
"%ADB%" shell "dumpsys display | grep -E 'mDisplayId|DisplayDeviceInfo|mState=|mBaseDisplayInfo' | head -40" >> "%OUT%" 2>&1
"%ADB%" logcat -d -v time > "%FULL%" 2>&1
echo ==== LOG FILTRADO ==== >> "%OUT%"
findstr /i "RearPreview subscreen SubScreen 0xfffffa CAMERA_CALL AndroidRuntime FATAL SecurityException Presentation" "%FULL%" >> "%OUT%"
echo ==== PASTA XML ==== >> "%OUT%"
"%ADB%" shell ls -la /sdcard/Download/Northkai/xml/ >> "%OUT%" 2>&1
"%ADB%" shell ls /sdcard/HyperCam/Strike/ >> "%OUT%" 2>&1
echo ==== FIM ==== >> "%OUT%"
