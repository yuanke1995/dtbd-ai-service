@echo off
REM ============================================================
REM  Start backend jar (ai-doc-assistant) - Windows
REM  JAR: <project root>\target\ai-doc-assistant.jar
REM  Before first run: fill DB_PASSWORD / AI_TRUSTED_TOKEN below
REM  (same values as your IDEA run config / platform gateway)
REM ============================================================
setlocal
set ROOT=%~dp0..\..
set JAR=%ROOT%\target\ai-doc-assistant.jar

if not exist "%JAR%" (
    echo JAR not found. Build first:  mvn package -DskipTests
    pause
    exit /b 1
)

REM ---- edit these two lines (no quotes) ----
set DB_PASSWORD=OceanBase_123#
set AI_TRUSTED_TOKEN=ai-doc-internal-token
REM -----------------------------------------

REM ---- optional overrides (uncomment to change) ----
REM set REDIS_PORT=6380
REM set AI_VISION_BASE_URL=http://localhost:11434
REM set AI_RERANK_BASE_URL=http://localhost:7997
REM set AI_RERANK_ENABLED=true
REM set AI_QUERY_REWRITE_ENABLED=true

REM ---- port check: stop any running instance first ----
netstat -ano | findstr ":8090" >nul
if not errorlevel 1 (
    echo.
    echo ============================================
    echo   ERROR: Port 8090 is already in use.
    echo   A backend is still running. Stop it first:
    echo     - IDEA: click the red Stop square
    echo     - or:  taskkill /F /PID 12345 -- replace with real PID
    echo   Then run this script again.
    echo ============================================
    echo.
    timeout /t 10 >nul
    exit /b 1
)

REM ---- JDK: prefer JAVA_HOME, fallback to PATH ----
set JAVA_CMD=java
if exist "%JAVA_HOME%\bin\java.exe" set JAVA_CMD=%JAVA_HOME%\bin\java.exe

echo Starting ai-doc-assistant on :8090 (keep this window open)...
"%JAVA_CMD%" -Xms256m -Xmx1024m -jar "%JAR%"
pause
