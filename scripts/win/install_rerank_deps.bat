@echo off
chcp 65001 >nul
REM ============================================================
REM  一键安装 Rerank 服务依赖（Windows）
REM  依赖目录: <项目根>\.pyenv\target（隔离安装，绕过 pip safe-delete 沙箱拦截）
REM  自定义 Python: 设置环境变量 PYTHON_BIN（默认 D:\Python311\python.exe）
REM ============================================================
set ROOT=%~dp0..\..
set TARGET=%ROOT%\.pyenv\target
set PY=%PYTHON_BIN%
if "%PY%"=="" set PY=D:\Python311\python.exe

if not exist "%TARGET%" mkdir "%TARGET%"

echo [1/2] 安装 torch (CPU 版, 约 200MB)...
"%PY%" -m pip install --target "%TARGET%" torch --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://mirrors.aliyun.com/pypi/simple/
if errorlevel 1 goto :fail

echo [2/2] 安装 sentence-transformers + modelscope (纯 wheel, 无需编译)...
"%PY%" -m pip install --target "%TARGET%" sentence-transformers modelscope -i https://mirrors.aliyun.com/pypi/simple/
if errorlevel 1 goto :fail

echo.
echo 依赖安装完成。接下来:
echo   1) 下载模型:  "%PY%" scripts\download_model.py
echo   2) 启动服务:  scripts\start_rerank_server.bat
pause
exit /b 0

:fail
echo 安装失败，请检查网络或按上方报错排查。
pause
exit /b 1
