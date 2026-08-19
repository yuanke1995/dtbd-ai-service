#!/usr/bin/env bash
# ============================================================
#  一键安装 Rerank 服务依赖（macOS / Linux）
#  依赖目录: <项目根>/.pyenv/target（隔离安装，不污染系统 Python）
#  自定义 Python: export PYTHON_BIN=/path/to/python3（默认取 PATH 中的 python3）
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="$ROOT/.pyenv/target"
PY="${PYTHON_BIN:-$(command -v python3 || echo python3)}"

mkdir -p "$TARGET"

echo "[1/2] 安装 torch (CPU 版)..."
"$PY" -m pip install --target "$TARGET" torch \
  --index-url https://download.pytorch.org/whl/cpu \
  --extra-index-url https://mirrors.aliyun.com/pypi/simple/

echo "[2/2] 安装 sentence-transformers + modelscope..."
"$PY" -m pip install --target "$TARGET" sentence-transformers modelscope \
  -i https://mirrors.aliyun.com/pypi/simple/

echo ""
echo "依赖安装完成。接下来:"
echo "  1) 下载模型:  $PY scripts/download_model.py"
echo "  2) 启动服务:  scripts/start_rerank_server.sh"
