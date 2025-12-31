@echo off

REM Get the current date and time to create a unique filename
set "TEMP_FILE=%TEMP%\temp_%DATE:~-4%%DATE:~4,2%%DATE:~7,2%_%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%.txt"
set "TEMP_FILE=%TEMP_FILE: =_%"

cd c:\devel\source\klemm-camera-viewer

start %JAVA_HOME%bin\javaw.exe --enable-native-access=ALL-UNNAMED -splash:splash.png  ".\src\main\java\klemm\technology\camera\JustSplash.java" %TEMP_FILE%

call mvn -q install

REM call java.exe                                ^
start javaw.exe                              ^
--enable-native-access=ALL-UNNAMED           ^
-splash:splash.png                           ^
-Dfile.encoding=UTF-8                        ^
-Dstdout.encoding=UTF-8                      ^
-Dstderr.encoding=UTF-8                      ^
-classpath ".\target\classes;.\target\lib\*" ^
-XX:+ShowCodeDetailsInExceptionMessages klemm.technology.camera.SubnetScanner

echo "Reading PID from %TEMP_FILE%"
type "%TEMP_FILE%"
set /p PID=<"%TEMP_FILE%"

REM Check if PID was captured
if defined PID (
    echo Found PID: %PID%

    REM Kill the process using the captured PID
    taskkill /pid %PID% /f
    echo Process killed.
) else (
    echo Failed to get PID from Java process.
)
