@echo off
setlocal

set MAVEN_VERSION=3.9.14
set BASE_DIR=%~dp0
set MAVEN_HOME=%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%
set MAVEN_ZIP=%BASE_DIR%.mvn\apache-maven-%MAVEN_VERSION%-%RANDOM%-bin.zip

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip'; $zip='%MAVEN_ZIP%'; Invoke-WebRequest -Uri $url -OutFile $zip; Expand-Archive -Force $zip '%BASE_DIR%.mvn'"
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
