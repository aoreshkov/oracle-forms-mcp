@rem OFMCP_JAVA_HOME names the Java installation to run on, ahead of JAVA_HOME. A client that can
@rem only set variables for us (the MCPB bundle's settings) can then choose a Java without clearing
@rem the JAVA_HOME the user already has: mapping an empty setting onto JAVA_HOME would hide it.
@rem Empty, or a ${user_config.*} placeholder the client left unsubstituted, means unset. Spliced in
@rem by the startScripts task in server/build.gradle.kts.
if not defined OFMCP_JAVA_HOME goto ofmcpJavaHomeDone
set OFMCP_JAVA_HOME=%OFMCP_JAVA_HOME:"=%
if not defined OFMCP_JAVA_HOME goto ofmcpJavaHomeDone
if not "%OFMCP_JAVA_HOME:${user_config.=%"=="%OFMCP_JAVA_HOME%" goto ofmcpJavaHomeDone

if exist "%OFMCP_JAVA_HOME%\bin\java.exe" goto ofmcpJavaHomeFound
@rem The installation's bin folder.
if exist "%OFMCP_JAVA_HOME%\java.exe" for %%i in ("%OFMCP_JAVA_HOME%\..") do set OFMCP_JAVA_HOME=%%~fi
if exist "%OFMCP_JAVA_HOME%\bin\java.exe" goto ofmcpJavaHomeFound

1>&2 echo.
1>&2 echo ERROR: OFMCP_JAVA_HOME is set to %OFMCP_JAVA_HOME%, which has no bin\java.exe.
1>&2 echo.
1>&2 echo Set it (in the desktop bundle: the Java installation setting) to the folder of a
1>&2 echo Java 25+ installation, the one that contains bin\java.exe, or clear it to use JAVA_HOME
1>&2 echo or the java on PATH.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel

:ofmcpJavaHomeFound
set JAVA_HOME=%OFMCP_JAVA_HOME%
:ofmcpJavaHomeDone

