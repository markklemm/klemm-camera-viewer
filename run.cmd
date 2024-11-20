@echo off

cd c:\devel\source\klemm-camera-viewer

IF EXIST splash.pid del /q splash.pid > nul 2>&1
start "" "%JAVA_HOME%\bin\javaw.exe" -splash:splash.png  ".\src\main\java\klemm\technology\camera\JustSplash.java"

call mvn clean install

REM Read the PID from the file
set /p PID=<splash.pid

REM Echo the found PID
echo Found PID: %PID%

start javaw.exe                                ^
-splash:splash.png                           ^
-Dfile.encoding=UTF-8                        ^
-Dstdout.encoding=UTF-8                      ^
-Dstderr.encoding=UTF-8                      ^
-classpath "C:\devel\source\klemm-camera-viewer\target\classes;C:\devel\source\klemm-camera-viewer\target\lib\*" ^
-XX:+ShowCodeDetailsInExceptionMessages klemm.technology.camera.RtspStreamViewer

REM Kill the process using the PID
taskkill /pid %PID% /f
IF EXIST splash.pid del /q splash.pid > nul 2>&1