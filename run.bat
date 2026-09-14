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

rem Local repo: prefer D:\.m2 (some dev machines keep Maven there),
rem fall back to the standard per-user location. No hand-editing needed.
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
rem javac 路径不会像 Maven 那样复制 resources，手动同步一次，
rem 否则 sprites 下的职业/Boss/小怪素材在运行期找不到，会退回程序化兜底形象
if exist "client\src\main\resources" (
  xcopy /e /i /y /q "client\src\main\resources\*" "%CLIENTOUT%" >nul
)
del /q "%TEMP%\ab_core_src.txt" "%TEMP%\ab_client_src.txt" >nul 2>nul

:run
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
