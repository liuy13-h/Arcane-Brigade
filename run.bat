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
rem  launch, then runs com.arcanebrigade.client.GameLauncher.
rem  Rebuilding each time guarantees the running classes always match
rem  this source (class file version 61, runnable on Java 17 through
rem  Java 26+), so a stale or foreign-compiled build can never be
rem  launched by mistake.
rem
rem  Keep every line of this file ASCII-only. cmd re-reads a batch file at
rem  a byte offset once `chcp` (below) changes the code page, and any
rem  multi-byte text after that point makes it resume parsing mid-line -
rem  fragments of the comments then get run as commands. That really
rem  happened here with Chinese comments, so they are English now.
rem ============================================================
rem ------------------------------------------------------------------
rem  Chinese-path fix: `dir /s /b` emits FULLY-QUALIFIED source paths,
rem  so the javac @argfiles written below contain this project's
rem  non-ASCII directory name. JDK18+ reads @argfiles as UTF-8, and on a
rem  non-UTF-8 console code page that aborts the build with
rem  MalformedInputException. Switch to UTF-8 (65001) up front so the
rem  argfile writer and the javac reader agree.
rem ------------------------------------------------------------------
chcp 65001 >nul
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

rem Local repo: prefer D:\.m2 (some dev machines keep Maven there),
rem fall back to the standard per-user location. No hand-editing needed.
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
rem javac does not copy resources the way Maven does, so sync them by hand.
rem The hero/boss/mob art under resources/sprites is read at runtime from the
rem classpath; without this step Sprites silently falls back to its procedural
rem stand-in drawings (no error printed). build.bat does the same - keep in sync.
if exist "client\src\main\resources" (
  xcopy /e /i /y /q "client\src\main\resources\*" "%CLIENTOUT%" >nul
)
del /q "%TEMP%\ab_core_src.txt" "%TEMP%\ab_client_src.txt" >nul 2>nul

:run
set "CP=%COREOUT%;%CLIENTOUT%"
rem Load JavaFX 21 as named modules so Application.launch can find them;
rem otherwise it reports "JavaFX runtime components are missing".
rem Point --module-path at the four platform JAR FILES, not at their folders:
rem the local Maven repo also holds javafx-*-sources.jar beside each -win.jar,
rem and a folder on the module path exposes both, which aborts the JVM with
rem "Two versions of module javafx.base found".
rem --enable-native-access is legal on JDK 21. Do NOT add
rem --sun-misc-unsafe-memory-access: that flag is JDK 23+, and on JDK 21 the
rem JVM exits at once - the black console window that flashes and disappears.
set "FXMP=%FB%;%FG%;%FC%;%FM%"
echo Launching Arcane Brigade ...
set "SMK="
if defined AB_SMOKE set "SMK=-Dab.smoke=%AB_SMOKE%"
"%JAVAEXE%" --module-path "%FXMP%" --add-modules ALL-MODULE-PATH --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dsun.java2d.dpiaware=true %SMK% -cp "%CP%" com.arcanebrigade.client.GameLauncher
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
