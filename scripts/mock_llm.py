#!/usr/bin/env python3
"""假的 OpenAI 兼容 LLM：只为验证 prompt 里到底塞了什么。

为什么需要它：DeepSeek 的 key 失效时（HTTP 401）整条对话链路跑不通，
但 K4 要验证的恰恰是「请求体的 system 里有知识块、库里的台词表里没有」。
把 base-url 指到这里，请求体原样落盘，再回一段合法的 SSE，
就能在不依赖真实模型的情况下把注入链路验穿。

用法（仅本地验证，不入库）：
    python scripts/mock_llm.py --port 9999 --out .workbuddy/tmp/llm-requests
"""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPLY_PARTS = ["内存泄漏的排查", "一般先看 jstat 的 GC 曲线，", "再用 jmap 导出堆快照做支配树分析。"]


class Handler(BaseHTTPRequestHandler):
    out_dir = Path(".workbuddy/tmp/llm-requests")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        # 落盘原始请求体：验证方 grep system 里有没有 [岗位知识参考资料]
        self.out_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%H%M%S") + f"-{int(time.time() * 1000) % 1000:03d}"
        (self.out_dir / f"req-{stamp}.json").write_bytes(raw)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for part in REPLY_PARTS:
            frame = {"choices": [{"delta": {"content": part}, "index": 0}]}
            self.wfile.write(f"data: {json.dumps(frame, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9999)
    ap.add_argument("--out", default=".workbuddy/tmp/llm-requests")
    args = ap.parse_args()
    Handler.out_dir = Path(args.out)
    Handler.out_dir.mkdir(parents=True, exist_ok=True)
    print(f"mock llm on http://127.0.0.1:{args.port} -> {Handler.out_dir}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
