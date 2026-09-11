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
if not exist "%M2%\org\openjfx\javafx-base\21.0.12" set "M2=%USERPROFILE%\.m2\repository"
set "FX=%M2%\org\openjfx"
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
dir /s /b "core\src\main\java\*.java" > "%TEMP%\ab_core_src.txt"
"%JAVAC%" --release 17 -encoding UTF-8 -d "%COREOUT%" "@%TEMP%\ab_core_src.txt"
if errorlevel 1 goto :failbuild

echo Building client ...
if not exist "%CLIENTOUT%" mkdir "%CLIENTOUT%"
dir /s /b "client\src\main\java\*.java" > "%TEMP%\ab_client_src.txt"
"%JAVAC%" --release 17 -encoding UTF-8 -cp "%COREOUT%;%FB%;%FG%;%FC%;%FM%" -d "%CLIENTOUT%" "@%TEMP%\ab_client_src.txt"
if errorlevel 1 goto :failbuild
rem javac 路径不会像 Maven 那样复制 resources，手动同步一次，
rem 否则 sprites 下的职业/Boss/小怪素材在运行期找不到，会退回程序化兜底形象
if exist "client\src\main\resources" (
  xcopy /e /i /y /q "client\src\main\resources\*" "%CLIENTOUT%" >nul
)
del /q "%TEMP%\ab_core_src.txt" "%TEMP%\ab_client_src.txt" >nul 2>nul

:run
set "CP=%COREOUT%;%CLIENTOUT%;%FB%;%FG%;%FC%;%FM%"
echo Launching Arcane Brigade ...
set "SMK="
if defined AB_SMOKE set "SMK=-Dab.smoke=%AB_SMOKE%"
"%JAVAEXE%" --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dfile.encoding=UTF-8 -Dsun.java2d.dpiaware=true %SMK% -cp "%CP%" com.arcanebrigade.client.GameLauncher
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
