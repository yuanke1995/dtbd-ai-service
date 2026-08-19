# -*- coding: utf-8 -*-
"""
下载 bge-reranker-v2-m3 模型（modelscope 国内源）到 .pyenv/model
用法: D:\\Python311\\python.exe scripts\\download_model.py
（依赖 modelscope，需先执行 install_rerank_deps.bat 或手动装到 .pyenv\\target）
"""
import os
import sys

TARGET_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".pyenv", "target")
MODEL_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".pyenv", "model", "bge-reranker-v2-m3")

if os.path.isdir(TARGET_DIR) and TARGET_DIR not in sys.path:
    sys.path.insert(0, TARGET_DIR)

print(f"[rerank] 模型下载到: {MODEL_DIR}", flush=True)
from modelscope import snapshot_download

snapshot_download("BAAI/bge-reranker-v2-m3", local_dir=MODEL_DIR)
print("[rerank] 模型下载完成", flush=True)
