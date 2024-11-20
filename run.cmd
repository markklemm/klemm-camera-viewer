@echo off

cd c:\devel\source\klemm-camera-viewer

call mvn clean install

call java.exe                                ^
-splash:splash.png                           ^
-Dfile.encoding=UTF-8                        ^
-Dstdout.encoding=UTF-8                      ^
-Dstderr.encoding=UTF-8                      ^
-classpath "C:\devel\source\klemm-camera-viewer\target\classes;C:\devel\source\klemm-camera-viewer\target\lib\*" ^
-XX:+ShowCodeDetailsInExceptionMessages klemm.technology.camera.RtspStreamViewer