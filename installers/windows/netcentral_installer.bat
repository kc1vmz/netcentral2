@echo off
rem Net Central Installer
rem Copyright (c) 2026 John Rokicki KC1VMZ
rem 
rem This program is free software: you can redistribute it and/or modify
rem it under the terms of the GNU General Public License as published by
rem the Free Software Foundation, either version 3 of the License, or
rem (at your option) any later version.
rem 
rem This program is distributed in the hope that it will be useful,
rem but WITHOUT ANY WARRANTY; without even the implied warranty of
rem MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
rem GNU General Public License for more details.
rem 
rem You should have received a copy of the GNU General Public License
rem along with this program.  If not, see <https://www.gnu.org/licenses/>.
rem
rem http://www.kc1vmz.com
rem 

setlocal enabledelayedexpansion

echo Welcome to the Net Central installer for Microsoft Windows.
echo Copyright (c) 2026 John Rokicki KC1VMZ - Licensed under GPL v3
echo https://www.kc1vmz.com
echo.
echo This installer expects pre-requisite software to be installed - Java 21+
echo Please install these pre-requisites prior to installation.
echo Java can be found at https://www.java.com/en/download/manual.jsp
echo.
pause
SET NC_INSTALL_DIR=%USERPROFILE%\NetCentral
SET NC_TEMP_DIR=%TEMP%\NetCentral
SET NC_DB_DIR=%NC_TEMP_DIR%\db
SET NC_VERSION=2.2.0
SET NC_PORT=8080

REM Prompt the user for input
SET /P "NC_VERSION=What version of Net Central? (Default: %NC_VERSION%): "
SET /P "NC_INSTALL_DIR=Where should Net Central be installed? (Default: %NC_INSTALL_DIR%): "
SET /P "NC_DB_DIR=Where should Net Central database space be located? (Default: %NC_DB_DIR%): "
SET /P "NC_TEMP_DIR=Where should Net Central temp space be located? (Default: %NC_TEMP_DIR%): "
SET /P "NC_PORT=What port should the Net Central HTTP service listen on? (Default: %NC_PORT%): "

pushd .

echo Checking for pre-requisite - Java 21
rem Step 1: Check if Java is installed at all
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java is not installed or not added to your system PATH.
    goto done
)
rem Step 2: Extract the major version number
rem This runs 'java -version', grabs the first line, and parses the version string.
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "java_version=%%g"
)
rem Remove surrounding quotes from the version string (e.g., "17.0.1" -> 17.0.1)
set "java_version=%java_version:"=%"
rem Extract the main major version number before the first dot
for /f "delims=." %%v in ("!java_version!") do (
    set "major_version=%%v"
)
rem Handle legacy naming convention (e.g., Java 8 outputs "1.8", so major is 1)
if "!major_version!"=="1" (
    for /f "tokens=2 delims=." %%v in ("!java_version!") do (
        set "major_version=%%v"
    )
)
rem Step 3: Validate if version is 21 or greater
if !major_version! lss 21 (
    echo [ERROR] Java version is too old. Required: 21 or greater.
    goto done
)

echo Starting installation

echo Creating directories
mkdir %NC_INSTALL_DIR%
mkdir %NC_TEMP_DIR%
mkdir %NC_DB_DIR%

cd %NC_INSTALL_DIR%

echo Downloading built components for version %NC_VERSION% from Github
SET NC_SRC_URL_ROOT=https://github.com/kc1vmz/netcentral2/releases/download/v%NC_VERSION%
curl -L -o netcentral-%NC_VERSION%.jar %NC_SRC_URL_ROOT%\netcentral-%NC_VERSION%.jar
SET NC_SRC_URL_ROOT=

echo Creating startup script
echo @ECHO OFF>> netcentral_start.bat
echo pushd .>> netcentral_start.bat
echo cd %NC_INSTALL_DIR%>> netcentral_start.bat
echo REM Set Environment Variables>> netcentral_start.bat
echo SET NC_DB_DIR=%NC_DB_DIR%>> netcentral_start.bat
echo SET NC_TEMP_DIR=%NC_TEMP_DIR%>> netcentral_start.bat
echo SET NC_HTTP_PORT=%NC_PORT%>> netcentral_start.bat
echo SET NC_INSTALL_DIR=%NC_INSTALL_DIR%>> netcentral_start.bat
echo REM Start Processes>> netcentral_start.bat
echo start java -jar netcentral-%NC_VERSION%.jar>> netcentral_start.bat
echo echo Net Central started>> netcentral_start.bat
echo popd>> netcentral_start.bat

echo Finishing up
popd

SET NC_INSTALL_DIR=
SET NC_TEMP_DIR=
SET NC_VERSION=
SET NC_TEMP_DIR=
SET NC_TILES_DIR=
SET NC_CONFIG=
SET NC_TILE_SERVER=

echo.
echo Installation complete.
echo.
echo Run 'netcentral_start.bat' to start Net Central.
exit /b 0

:done
exit /b 1