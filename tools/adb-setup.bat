@echo off
rem Run adb-setup.ps1 with the execution policy bypassed for this process only.
rem Example: adb-setup.bat -Apply
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0adb-setup.ps1" %*
pause
