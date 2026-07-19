@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM ----------------------------------------------------------------------------

@REM ----------------------------------------------------------------------------
@REM Apache Maven Wrapper startup script, version 3.3.3
@REM ----------------------------------------------------------------------------

@ECHO OFF
SETLOCAL

SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
SET "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"
SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

IF NOT "%JAVA_HOME%"=="" GOTO javaHomeSet
SET "JAVA_EXE=java.exe"
%JAVA_EXE% -version >NUL 2>&1
IF %ERRORLEVEL% EQU 0 GOTO javaFound
ECHO The JAVA_HOME environment variable is not defined correctly, and no java command could be found. 1>&2
GOTO error

:javaHomeSet
SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
IF EXIST "%JAVA_EXE%" GOTO javaFound
ECHO The JAVA_HOME environment variable is not defined correctly: %JAVA_HOME% 1>&2
GOTO error

:javaFound
IF EXIST "%WRAPPER_JAR%" GOTO runWrapper

SET "WRAPPER_URL="
SET "WRAPPER_VERSION="
FOR /F "usebackq tokens=1,* delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
  IF "%%A"=="wrapperUrl" SET "WRAPPER_URL=%%B"
  IF "%%A"=="wrapperVersion" SET "WRAPPER_VERSION=%%B"
)
IF NOT "%WRAPPER_URL%"=="" GOTO downloadWrapper
SET "WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/%WRAPPER_VERSION%/maven-wrapper-%WRAPPER_VERSION%.jar"

:downloadWrapper
IF "%MVNW_VERBOSE%"=="true" ECHO Downloading Maven Wrapper JAR from %WRAPPER_URL%
POWERSHELL -NoProfile -ExecutionPolicy Bypass -Command "$wc = New-Object Net.WebClient; if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD) { $wc.Credentials = New-Object Net.NetworkCredential($env:MVNW_USERNAME, $env:MVNW_PASSWORD) }; $wc.DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
IF %ERRORLEVEL% NEQ 0 GOTO error

:runWrapper
"%JAVA_EXE%" %MAVEN_OPTS% %MAVEN_DEBUG_OPTS% -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
IF %ERRORLEVEL% NEQ 0 GOTO error
GOTO end

:error
SET ERROR_CODE=1
GOTO quit

:end
SET ERROR_CODE=0

:quit
ENDLOCAL & EXIT /B %ERROR_CODE%
