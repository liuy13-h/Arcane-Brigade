@echo off
rem Arcane Brigade - one-click launcher
rem Builds automatically if core\target\classes / client\target\classes are missing.
setlocal

cd /d "%~dp0"

rem ---------- auto build if the compiled classes are gone ----------
if not exist "core\target\classes\com\arcanebrigade\core\World.class" goto :needbuild
if not exist "client\target\classes\com\arcanebrigade\client\GameLauncher.class" goto :needbuild
goto :launch

:needbuild
echo [run] Compiled classes not found - building first ...
call "%~dp0build.bat"
if errorlevel 1 exit /b 1

:launch
set "JDK_DIR=D:\develop"
if not exist "%JDK_DIR%\bin\java.exe" set "JDK_DIR=%JAVA_HOME%"
if not exist "%JDK_DIR%\bin\java.exe" set "JDK_DIR="
set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
if "%JDK_DIR%"=="" set "JAVA_EXE=java"

set "M2=%USERPROFILE%\.m2\repository"
set "FX=%M2%\org\openjfx"

set "CP=core\target\classes;client\target\classes"
set "CP=%CP%;%M2%\com\arcanebrigade\core\0.1.0-SNAPSHOT\core-0.1.0-SNAPSHOT.jar"
set "CP=%CP%;%FX%\javafx-controls\21.0.12\javafx-controls-21.0.12.jar"
set "CP=%CP%;%FX%\javafx-controls\21.0.12\javafx-controls-21.0.12-win.jar"
set "CP=%CP%;%FX%\javafx-graphics\21.0.12\javafx-graphics-21.0.12.jar"
set "CP=%CP%;%FX%\javafx-graphics\21.0.12\javafx-graphics-21.0.12-win.jar"
set "CP=%CP%;%FX%\javafx-base\21.0.12\javafx-base-21.0.12.jar"
set "CP=%CP%;%FX%\javafx-base\21.0.12\javafx-base-21.0.12-win.jar"

echo Launching Arcane Brigade ...
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dsun.java2d.dpiaware=true -cp "%CP%" com.arcanebrigade.client.GameLauncher

if errorlevel 1 (
  echo.
  echo [ERROR] Game exited with an error.
  echo         Try running build.bat first and read its output.
  pause
)
endlocal
