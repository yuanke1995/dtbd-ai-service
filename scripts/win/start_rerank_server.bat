@echo off
chcp 65001 >nul
REM ============================================================
REM  一键启动本地 Rerank 服务（Windows，OpenAI 兼容 /v1/rerank, 端口 7997）
REM  模型目录: <项目根>\.pyenv\model\bge-reranker-v2-m3
REM  依赖目录: <项目根>\.pyenv\target
REM  自定义 Python: 设置环境变量 PYTHON_BIN（默认 D:\Python311\python.exe）
REM ============================================================
set ROOT=%~dp0..\..
set PY=%PYTHON_BIN%
if "%PY%"=="" set PY=D:\Python311\python.exe

set PYTHONPATH=%ROOT%\.pyenv\target
set MODEL=%ROOT%\.pyenv\model\bge-reranker-v2-m3

if not exist "%MODEL%\config.json" (
    echo 模型不存在，请先执行: "%PY%" "%ROOT%\scripts\download_model.py"
    pause
    exit /b 1
)

echo 启动 Rerank 服务 :7997 （保持本窗口开启）...
"%PY%" "%ROOT%\scripts\rerank_server.py" --model "%MODEL%" --port 7997
pause
