@echo off
rem Arcane Brigade - one-click build
rem Builds core + client into core\target\classes and client\target\classes.
rem Tries Maven first (also installs the jars into ~/.m2); if Maven is not
rem usable it falls back to plain javac, which is enough for run.bat.
setlocal

cd /d "%~dp0"

rem ---------- locate java ----------
set "JDK_DIR=D:\develop"
if not exist "%JDK_DIR%\bin\java.exe" set "JDK_DIR=%JAVA_HOME%"
if not exist "%JDK_DIR%\bin\java.exe" set "JDK_DIR="
set "JAVA_EXE=%JDK_DIR%\bin\java.exe"
if "%JDK_DIR%"=="" set "JAVA_EXE=java"
set "JAVAC_EXE=%JDK_DIR%\bin\javac.exe"
if "%JDK_DIR%"=="" set "JAVAC_EXE=javac"

rem ---------- locate maven ----------
set "MVN_HOME=D:\develop\apache-maven-3.9.16"
if not exist "%MVN_HOME%\bin\m2.conf" if not "%MAVEN_HOME%"=="" set "MVN_HOME=%MAVEN_HOME%"
if not exist "%MVN_HOME%\bin\m2.conf" if not "%M2_HOME%"=="" set "MVN_HOME=%M2_HOME%"
if not exist "%MVN_HOME%\bin\m2.conf" set "MVN_HOME="

set "BUILT="

if not "%MVN_HOME%"=="" goto :maven
where mvn >nul 2>nul
if %errorlevel%==0 goto :mvncmd
goto :javac

:maven
echo [build] Using Maven at %MVN_HOME%
set "CW="
for %%f in ("%MVN_HOME%\boot\plexus-classworlds-*.jar") do set "CW=%%~f"
if "%CW%"=="" goto :mvncmd
"%JAVA_EXE%" -classpath "%CW%" "-Dclassworlds.conf=%MVN_HOME%\bin\m2.conf" "-Dmaven.home=%MVN_HOME%" "-Dlibrary.jansi.path=%MVN_HOME%\lib\jansi-native" "-Dmaven.multiModuleProjectDirectory=%CD%" org.codehaus.plexus.classworlds.launcher.Launcher -o install -DskipTests
if not errorlevel 1 set "BUILT=1"
goto :check

:mvncmd
echo [build] Using mvn from PATH
call mvn -o install -DskipTests
if not errorlevel 1 set "BUILT=1"
goto :check

:javac
echo [build] Maven not found - falling back to javac
if not exist core\target\classes mkdir core\target\classes
if not exist client\target\classes mkdir client\target\classes

dir /s /b core\src\main\java\*.java > "%TEMP%\ab_core_src.txt"
rem --release 17 pins the class file version to 61. Without it a JDK newer than
rem 17 emits a higher version and IDEA (project SDK 17) rejects the build with
rem "class file has wrong version 70.0, should be 61.0". run.bat does the same.
"%JAVAC_EXE%" --release 17 -encoding UTF-8 -d core\target\classes @"%TEMP%\ab_core_src.txt"
if errorlevel 1 goto :fail

set "M2=D:\.m2\repository"
if not exist "%M2%\org\openjfx\javafx-base\21.0.12" set "M2=%USERPROFILE%\.m2\repository"
rem Use the -win classifier jars. The plain javafx-*.jar artifacts carry no
rem classes (the platform bits live in the classified ones), so compiling
rem against them fails with "package javafx.scene.image does not exist".
rem run.bat uses the same set - keep the two in sync.
set "FXCP=%M2%\org\openjfx\javafx-controls\21.0.12\javafx-controls-21.0.12-win.jar;%M2%\org\openjfx\javafx-graphics\21.0.12\javafx-graphics-21.0.12-win.jar;%M2%\org\openjfx\javafx-base\21.0.12\javafx-base-21.0.12-win.jar;%M2%\org\openjfx\javafx-media\21.0.12\javafx-media-21.0.12-win.jar"
dir /s /b client\src\main\java\*.java > "%TEMP%\ab_client_src.txt"
"%JAVAC_EXE%" --release 17 -encoding UTF-8 -cp "core\target\classes;%FXCP%" -d client\target\classes @"%TEMP%\ab_client_src.txt"
if errorlevel 1 goto :fail
rem javac does NOT copy resources (art, walk GIFs, boss art, icon are all read
rem from /sprites/... on the classpath), so sync them by hand here. The Maven
rem path above already does this via maven-resources-plugin.
if exist "client\src\main\resources" xcopy /e /i /y /q "client\src\main\resources\*" "client\target\classes\" >nul
set "BUILT=1"

:check
if "%BUILT%"=="" goto :fail
if not exist "core\target\classes\com\arcanebrigade\core\World.class" goto :fail
if not exist "client\target\classes\com\arcanebrigade\client\GameLauncher.class" goto :fail
echo [build] OK
endlocal
exit /b 0

:fail
echo.
echo [ERROR] Build failed. See the messages above.
echo         Make sure JDK 21 is installed (D:\develop or JAVA_HOME).
pause
endlocal
exit /b 1
