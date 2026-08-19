#!/usr/bin/env bash
# ============================================================
#  一键启动本地 Rerank 服务（macOS / Linux，OpenAI 兼容 /v1/rerank, 端口 7997）
#  模型目录: <项目根>/.pyenv/model/bge-reranker-v2-m3
#  依赖目录: <项目根>/.pyenv/target
#  自定义 Python: export PYTHON_BIN=/path/to/python3
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="${PYTHON_BIN:-$(command -v python3 || echo python3)}"
MODEL="$ROOT/.pyenv/model/bge-reranker-v2-m3"

if [ ! -f "$MODEL/config.json" ]; then
  echo "模型不存在，请先执行: $PY $ROOT/scripts/download_model.py"
  exit 1
fi

export PYTHONPATH="$ROOT/.pyenv/target"
echo "启动 Rerank 服务 :7997 （Ctrl+C 停止）..."
exec "$PY" "$ROOT/scripts/rerank_server.py" --model "$MODEL" --port 7997
