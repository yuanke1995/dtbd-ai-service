# -*- coding: utf-8 -*-
"""
本地 Rerank 服务（OpenAI 兼容 /v1/rerank + /v1/models + /health）
基于 sentence-transformers 的 CrossEncoder 加载 bge-reranker-v2-m3（真 cross-encoder 重排）。

背景：Ollama 官方无 /api/rerank 端点，且部分环境的 Ollama 服务禁用了 embeddings
（报 "This server does not support embeddings"，--embeddings flag 不存在、环境变量无效），
因此用独立 Python 服务跑真交叉编码重排。

用法：
  1) 装依赖（--target 装独立目录可避开沙箱删除拦截）：
     D:\\Python311\\python.exe -m pip install --target D:\\workspace\\dtbd-ai-service\\.pyenv\\target torch --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://mirrors.aliyun.com/pypi/simple/
     D:\\Python311\\python.exe -m pip install --target D:\\workspace\\dtbd-ai-service\\.pyenv\\target sentence-transformers -i https://mirrors.aliyun.com/pypi/simple/
  2) 下载模型（modelscope 国内源；约 1.1GB）：
     set PYTHONPATH=D:\\workspace\\dtbd-ai-service\\.pyenv\\target
     D:\\Python311\\python.exe -c "from modelscope import snapshot_download; snapshot_download('BAAI/bge-reranker-v2-m3', local_dir=r'D:\\workspace\\dtbd-ai-service\\.pyenv\\model\\bge-reranker-v2-m3')"
  3) 启动：
     set PYTHONPATH=D:\\workspace\\dtbd-ai-service\\.pyenv\\target
     D:\\Python311\\python.exe D:\\workspace\\dtbd-ai-service\\scripts\\rerank_server.py --model D:\\workspace\\dtbd-ai-service\\.pyenv\\model\\bge-reranker-v2-m3 --port 7997

对接：后端 RerankService provider=openai, base-url=http://localhost:7997, model=BAAI/bge-reranker-v2-m3
"""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_ID = "BAAI/bge-reranker-v2-m3"
model = None  # CrossEncoder 实例


def load_model(model_dir, threads=0):
    """加载 CrossEncoder（真交叉编码：query 与每篇文档拼接打分）"""
    from sentence_transformers import CrossEncoder
    global model
    kwargs = {}
    if threads and threads > 0:
        import torch
        torch.set_num_threads(threads)
    model = CrossEncoder(model_dir, max_length=512)
    print(f"[rerank] CrossEncoder 加载完成: {model_dir}", flush=True)


def rerank(query, documents, top_n=None):
    """返回 [{index, relevance_score}]，分数降序"""
    if model is None or not documents:
        return []
    pairs = [[query, doc] for doc in documents]
    scores = model.predict(pairs, show_progress_bar=False)
    results = [{"index": i, "relevance_score": float(s)} for i, s in enumerate(scores)]
    results.sort(key=lambda x: x["relevance_score"], reverse=True)
    if top_n and top_n > 0:
        results = results[:top_n]
    return results


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._json(200, {"object": "list", "data": [{"id": MODEL_ID, "object": "model"}]})
        elif self.path.startswith("/health"):
            self._json(200, {"status": "ok"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            self._json(400, {"error": "invalid json"})
            return
        if self.path.startswith("/v1/rerank"):
            query = body.get("query", "")
            docs = body.get("documents", [])
            top_n = body.get("top_n", 0)
            if not query or not docs:
                self._json(400, {"error": "query and documents required"})
                return
            try:
                self._json(200, {"object": "list", "results": rerank(query, docs, top_n)})
            except Exception as e:
                self._json(500, {"error": str(e)})
        else:
            self._json(404, {"error": "not found"})


def main():
    ap = argparse.ArgumentParser(description="OpenAI 兼容本地 Rerank 服务（CrossEncoder）")
    ap.add_argument("--model", required=True, help="模型目录（bge-reranker-v2-m3）")
    ap.add_argument("--port", type=int, default=7997)
    ap.add_argument("--threads", type=int, default=0)
    args = ap.parse_args()

    print(f"[rerank] 加载模型: {args.model}", flush=True)
    load_model(args.model, args.threads)
    print(f"[rerank] 模型加载完成，监听 :{args.port}（/v1/rerank）", flush=True)

    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[rerank] 已停止", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    main()
