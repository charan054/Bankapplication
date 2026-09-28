@echo off
cd /d D:\charan\Bankapplication
set JAVA_HOME=C:\Users\vidya\.jdks\corretto-23.0.2
set PATH=%JAVA_HOME%\bin;%PATH%
D:\charan\Bankapplication\mvnw.cmd spring-boot:run
