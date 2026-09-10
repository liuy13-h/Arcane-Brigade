@echo off
rem ============================================================
rem  Arcane Brigade one-click launcher
rem  Needs: a JDK 17 or newer (java and javac reachable from PATH,
rem         or JAVA_HOME pointing at the JDK), plus the JavaFX
rem         21.0.12 platform jars in the local Maven repo. If the
rem         jars are missing, open the project in IntelliJ once and
rem         click "Reload Maven" (needs internet).
rem
rem  This builds core and client with javac --release 17 on EVERY
rem  launch, then runs com.arcanebrigade.client.GameLauncher from
rem  the classpath. Rebuilding each time guarantees the running
rem  classes always match this source (class version 61, runnable
rem  on Java 17 through Java 26+), so a stale or foreign-compiled
rem  build can never be launched by mistake.
rem ============================================================
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "JAVAC="
where javac >nul 2>nul && set "JAVAC=javac"
if not defined JAVAC if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC (
  echo [ERROR] javac not found. Install a JDK 17+ and put its bin folder on PATH.
  goto :fail
)
if "%JAVAC%"=="javac" (set "JAVAEXE=java") else (set "JAVAEXE=%JAVAC:javac.exe=java.exe%")

set "FX=%USERPROFILE%\.m2\repository\org\openjfx"
set "FB=%FX%\javafx-base\21.0.12\javafx-base-21.0.12-win.jar"
set "FG=%FX%\javafx-graphics\21.0.12\javafx-graphics-21.0.12-win.jar"
set "FC=%FX%\javafx-controls\21.0.12\javafx-controls-21.0.12-win.jar"
set "FM=%FX%\javafx-media\21.0.12\javafx-media-21.0.12-win.jar"
if not exist "%FB%" goto :needmaven
if not exist "%FG%" goto :needmaven
if not exist "%FC%" goto :needmaven
if not exist "%FM%" goto :needmaven

set "COREOUT=core\target\classes"
set "CLIENTOUT=client\target\classes"

echo Building core ...
if not exist "%COREOUT%" mkdir "%COREOUT%"
rem Keep source entries relative: javac argument files otherwise misread an
rem absolute path that contains non-ASCII characters.
pushd core\src\main\java
dir /s /b *.java > "%TEMP%\ab_core_src.txt"
"%JAVAC%" --release 17 -encoding UTF-8 -d "..\..\..\target\classes" "@%TEMP%\ab_core_src.txt"
set "CORE_RC=%ERRORLEVEL%"
popd
if not "%CORE_RC%"=="0" goto :failbuild

rem Java's compiler can fail to resolve a directory classpath when this
rem project is stored below a non-ASCII parent folder. Package core locally.
powershell -NoProfile -Command "Remove-Item -LiteralPath 'core\target\arcane-core.jar' -Force -ErrorAction SilentlyContinue; Remove-Item -LiteralPath 'core\target\arcane-core.zip' -Force -ErrorAction SilentlyContinue; Compress-Archive -Path 'core\target\classes\*' -DestinationPath 'core\target\arcane-core.zip' -Force; Move-Item -LiteralPath 'core\target\arcane-core.zip' -Destination 'core\target\arcane-core.jar' -Force"
if errorlevel 1 goto :failbuild

echo Building client ...
if not exist "%CLIENTOUT%" mkdir "%CLIENTOUT%"
pushd client\src\main\java
dir /s /b *.java > "%TEMP%\ab_client_src.txt"
"%JAVAC%" --release 17 -encoding UTF-8 -cp "..\..\..\..\core\target\arcane-core.jar;%FB%;%FG%;%FC%;%FM%" -d "..\..\..\target\classes" "@%TEMP%\ab_client_src.txt"
set "CLIENT_RC=%ERRORLEVEL%"
popd
if not "%CLIENT_RC%"=="0" goto :failbuild
del /q "%TEMP%\ab_core_src.txt" "%TEMP%\ab_client_src.txt" >nul 2>nul

:run
set "CP=%COREOUT%;%CLIENTOUT%;%FB%;%FG%;%FC%;%FM%"
rem Keep JavaFX shader/cache files and the game's local settings inside the build output.
rem This also makes the launcher work in restricted environments where C:\ is not writable.
set "RUNTIME_HOME=%CD%\client\target\runtime-home"
if not exist "%RUNTIME_HOME%" mkdir "%RUNTIME_HOME%"
echo Launching Arcane Brigade ...
set "SMK="
if defined AB_SMOKE set "SMK=-Dab.smoke=%AB_SMOKE%"
"%JAVAEXE%" -Dfile.encoding=UTF-8 -Dsun.java2d.dpiaware=true "-Duser.home=%RUNTIME_HOME%" "-Djavafx.cachedir=%RUNTIME_HOME%\.openjfx" %SMK% -cp "%CP%" com.arcanebrigade.client.GameLauncher
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%

:needmaven
echo [ERROR] Missing JavaFX 21.0.12 platform jar: %FB%
echo         Open in IntelliJ and Reload Maven first (internet needed).
goto :fail

:failbuild
echo [ERROR] Compilation failed. See javac errors above.
goto :fail

:fail
pause
endlocal & exit /b 1
