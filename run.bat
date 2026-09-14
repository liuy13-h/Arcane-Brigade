@echo off
rem ============================================================
rem  Arcane Brigade one-click launcher
rem  Needs: a JDK 17 or newer (java and javac reachable from PATH,
rem         or JAVA_HOME pointing at the JDK), plus the JavaFX
rem         21.0.6 platform jars in the local Maven repo. If the
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

set "M2=D:\.m2\repository"
if not exist "%M2%\org\openjfx\javafx-base\21.0.6" set "M2=%USERPROFILE%\.m2\repository"
set "FX=%M2%\org\openjfx"
set "FB=%FX%\javafx-base\21.0.6\javafx-base-21.0.6-win.jar"
set "FG=%FX%\javafx-graphics\21.0.6\javafx-graphics-21.0.6-win.jar"
set "FC=%FX%\javafx-controls\21.0.6\javafx-controls-21.0.6-win.jar"
set "FM=%FX%\javafx-media\21.0.6\javafx-media-21.0.6-win.jar"
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

rem This used to zip core\target\classes into arcane-core.jar, which javac 26
rem cannot read when it comes from PowerShell's Compress-Archive -- the client
rem then fails with "package com.arcanebrigade.core does not exist". Using the
rem classes directory directly works. See build.bat for the full note.


echo Building client ...
if not exist "%CLIENTOUT%" mkdir "%CLIENTOUT%"
pushd client\src\main\java
dir /s /b *.java > "%TEMP%\ab_client_src.txt"
"%JAVAC%" --release 17 -encoding UTF-8 -cp "..\..\..\..\core\target\classes;%FB%;%FG%;%FC%;%FM%" -d "..\..\..\target\classes" "@%TEMP%\ab_client_src.txt"
set "CLIENT_RC=%ERRORLEVEL%"
popd
if not "%CLIENT_RC%"=="0" goto :failbuild
rem javac does NOT copy resources the way Maven does, so sync them by hand.
rem Without this the class/boss/mob art under sprites/ is missing at runtime
rem and the renderer silently falls back to its procedural placeholder art.
rem (Keep every comment in this file ASCII: non-ASCII text in a .bat under
rem  chcp 65001 gets mis-parsed by cmd and leaks a stray command into the run.)
if exist "client\src\main\resources" (
  xcopy /e /i /y /q "client\src\main\resources\*" "%CLIENTOUT%" >nul
)
del /q "%TEMP%\ab_core_src.txt" "%TEMP%\ab_client_src.txt" >nul 2>nul

:run
rem JavaFX 21 has to load as named modules: put the four platform jars on the
rem module path and use --add-modules so every javafx module is visible to the
rem unnamed module (the game code, which sits on the classpath). Without this
rem Application.launch fails with "JavaFX runtime components are missing".
rem
rem The module path lists the jars explicitly rather than pointing at the four
rem 21.0.12 directories. A directory on the module path is scanned wholesale,
rem so the moment a -sources.jar lands in ~/.m2 next to the -win.jar (IntelliJ
rem does this when it downloads sources) the JDK sees two javafx.base modules
rem and dies with module.FindException before main() ever runs.
rem
rem Note: --sun-misc-unsafe-memory-access=allow is a JDK 23+ flag. This machine
rem is on JDK 21, where it makes the JVM refuse to start (black window flash).
rem --enable-native-access is legal on JDK 21.
set "CP=%COREOUT%;%CLIENTOUT%"
rem 模块路径直接列出 win 平台 jar：目录形式会把 sources jar / 空壳主 jar
rem 一起挂进模块层，JVM 报 "Two versions of module javafx.xxx found"。
set "FXMP=%FB%;%FG%;%FC%;%FM%"
rem Keep JavaFX shader/cache files and local settings inside the project output.
set "RUNTIME_HOME=%CD%\client\target\runtime-home"
if not exist "%RUNTIME_HOME%" mkdir "%RUNTIME_HOME%"
echo Launching Arcane Brigade ...
set "SMK="
if defined AB_SMOKE set "SMK=-Dab.smoke=%AB_SMOKE%"
"%JAVAEXE%" --module-path "%FXMP%" --add-modules ALL-MODULE-PATH --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dsun.java2d.dpiaware=true "-Duser.home=%RUNTIME_HOME%" "-Djavafx.cachedir=%RUNTIME_HOME%\.openjfx" %SMK% -cp "%CP%" com.arcanebrigade.client.GameLauncher
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%

:needmaven
echo [ERROR] Missing JavaFX 21.0.6 platform jar: %FB%
echo         Open in IntelliJ and Reload Maven first (internet needed).
goto :fail

:failbuild
echo [ERROR] Compilation failed. See javac errors above.
goto :fail

:fail
pause
endlocal & exit /b 1
