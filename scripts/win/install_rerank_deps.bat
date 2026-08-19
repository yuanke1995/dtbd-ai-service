@echo off
REM ============================================================
REM  Install Rerank service dependencies (Windows)
REM  Install into <project root>\.pyenv\target (isolated dir,
REM  avoids pip safe-delete issues under sandbox)
REM  Custom Python: set env PYTHON_BIN (default D:\Python311\python.exe)
REM ============================================================
setlocal
set ROOT=%~dp0..\..
set TARGET=%ROOT%\.pyenv\target
set PY=%PYTHON_BIN%
if "%PY%"=="" set PY=D:\Python311\python.exe

if not exist "%TARGET%" mkdir "%TARGET%"

echo [1/2] Installing torch (CPU, ~200MB)...
"%PY%" -m pip install --target "%TARGET%" torch --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://mirrors.aliyun.com/pypi/simple/
if errorlevel 1 goto :fail

echo [2/2] Installing sentence-transformers + modelscope (wheels, no compile)...
"%PY%" -m pip install --target "%TARGET%" sentence-transformers modelscope -i https://mirrors.aliyun.com/pypi/simple/
if errorlevel 1 goto :fail

echo.
echo Dependencies installed. Next:
echo   1) Download model:  "%PY%" "%ROOT%\scripts\download_model.py"
echo   2) Start service:   "%ROOT%\scripts\win\start_rerank_server.bat"
pause
exit /b 0

:fail
echo Install failed. Check network / error above.
pause
exit /b 1
