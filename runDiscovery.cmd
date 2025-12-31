@echo off


@echo Rebuilding Application
call mvn -q clean install

@echo Starting discovery
call java -classpath "C:\devel\source\klemm-camera-viewer\target\classes;C:\devel\source\klemm-camera-viewer\target\lib\*" klemm.technology.camera.SSDPDiscovery

@echo Done %date% %time%