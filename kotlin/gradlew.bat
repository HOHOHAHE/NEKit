@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve links: %0 may be a link
set TARGET_FILE=%0

:loop
@for /f "tokens=*" %%a in ('dir /b /a:l "%TARGET_FILE%" 2^>nul') do (
    @set TARGET_FILE_SYMLINK_TARGET=%%a
)

if defined TARGET_FILE_SYMLINK_TARGET (
    @set TARGET_FILE=%TARGET_FILE_SYMLINK_TARGET%
    @set TARGET_FILE_SYMLINK_TARGET=
    @goto loop
)

@rem For MinGW, ensure paths are in UNIX format before anything is touched
if not "%MINGW_HOME%" == "" (
    if not "%APP_HOME%" == "" (
        if exist "%MINGW_HOME%\bin\pathconv.exe" (
            call "%MINGW_HOME%\bin\pathconv.exe" -u "%APP_HOME%"
            @set APP_HOME=%ERRORLEVEL%
        )
    )
    if not "%JAVA_HOME%" == "" (
        if exist "%MINGW_HOME%\bin\pathconv.exe" (
            call "%MINGW_HOME%\bin\pathconv.exe" -u "%JAVA_HOME%"
            @set JAVA_HOME=%ERRORLEVEL%
        )
    )
)

@rem Setup the JAVA_HOME prerequisite
if not defined JAVA_HOME (
    @rem If we have a jacoboni style java (e.g. from an IDE)
    if exist "%APP_HOME%\..\java\bin\java.exe" (
        set JAVA_HOME=%APP_HOME%\..\java
    ) else (
        @rem Look for java in path
        for %%i in (java.exe) do set JAVACMD=%%~$PATH:i
        if defined JAVACMD (
            @rem Startup script should not depend on JAVACMD
            set JAVACMD=
            goto okJava
        ) else (
            echo JAVA_HOME is not set and no 'java' command could be found in your PATH.
            echo.
            echo Please set the JAVA_HOME variable in your environment to match the
            echo location of your Java installation.
            goto error
        )
    )
)

:okJava
if not defined JAVACMD (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
)

if not exist "%JAVACMD%" (
    echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
    echo.
    echo Please set the JAVA_HOME variable in your environment to match the
    echo location of your Java installation.
    goto error
)

@rem Set up the CLASSPATH
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
set CLASSPATH=%WRAPPER_JAR%

@rem Execute Gradle
"%JAVACMD%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:error
set ERRORLEVEL=1
if "%OS%"=="Windows_NT" @endlocal
exit /b %ERRORLEVEL%

:eof
if "%OS%"=="Windows_NT" @endlocal
exit /b %ERRORLEVEL%
