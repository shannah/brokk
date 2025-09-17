@echo off
setlocal

REM Resolve the directory where this script lives to locate the packaged jdeploy.args
set "SCRIPT_DIR=%~dp0"
set "SRC_FILE=%SCRIPT_DIR%jdeploy.args"

REM Determine the user's home directory in a cross-platform way
set "HOME_DIR="
if defined HOME set "HOME_DIR=%HOME%"
if not defined HOME if defined USERPROFILE set "HOME_DIR=%USERPROFILE%"
if not defined HOME_DIR if defined HOMEDRIVE if defined HOMEPATH set "HOME_DIR=%HOMEDRIVE%%HOMEPATH%"

if not defined HOME_DIR (
  echo Unable to determine HOME directory. Skipping jdeploy.args installation. 1>&2
  exit /b 0
)

set "TARGET_DIR=%HOME_DIR%\.brokk"
set "TARGET_FILE=%TARGET_DIR%\jdeploy.args"

REM If target already exists, do nothing
if exist "%TARGET_FILE%" (
  echo jdeploy.args already exists at "%TARGET_FILE%"; skipping copy.
  exit /b 0
)

REM Ensure target dir exists
if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"

REM Copy the packaged argfile
if not exist "%SRC_FILE%" (
  echo Source argfile not found at "%SRC_FILE%"; skipping copy. 1>&2
  exit /b 0
)

copy /y "%SRC_FILE%" "%TARGET_FILE%" >nul
if errorlevel 1 (
  echo Failed to copy "%SRC_FILE%" to "%TARGET_FILE%" 1>&2
  exit /b 1
)

echo Installed argfile to "%TARGET_FILE%"
exit /b 0
