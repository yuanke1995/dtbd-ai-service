@echo off
REM ============================================================
REM  Start local Rerank service (Windows)
REM  OpenAI compatible /v1/rerank on port 7997
REM  Model dir: <project root>\.pyenv\model\bge-reranker-v2-m3
REM  Deps dir : <project root>\.pyenv\target
REM  Custom Python: set env PYTHON_BIN (default D:\Python311\python.exe)
REM ============================================================
setlocal
set ROOT=%~dp0..\..
set PY=%PYTHON_BIN%
if "%PY%"=="" set PY=D:\Python311\python.exe

set PYTHONPATH=%ROOT%\.pyenv\target
set MODEL=%ROOT%\.pyenv\model\bge-reranker-v2-m3

if not exist "%MODEL%\config.json" (
    echo Model not found. Run first: "%PY%" "%ROOT%\scripts\download_model.py"
    pause
    exit /b 1
)

echo Starting Rerank service on :7997 ^(keep this window open^)...
"%PY%" "%ROOT%\scripts\rerank_server.py" --model "%MODEL%" --port 7997
pause
