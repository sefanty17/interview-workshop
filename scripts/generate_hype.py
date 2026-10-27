#!/usr/bin/env python3
"""HyPE：给每个片段生成「这段话能回答哪些问题」，参与检索索引（K6）。

为什么这么做：BM25 与 hashing 向量都吃字面。用户的问法（「堆和栈分别放什么东西」）
和正文的写法和标题（「运行时内存区域划分」）字面重合很低，于是查不到。
把「可能的问题」也放进索引，等于提前把用户的说法写进语料。

两种来源：
  --from-seed  data/knowledge/hype.json（选填；键是 `文档code|小节名`，值是问题数组）
  --llm        调 OpenAI 兼容 /chat/completions 现场生成，需要 key

生成结果写回库的方式：把原片段 + hype 一起 PUT 给 /chunks（后端按指纹判断要不要重算向量）。

用法：
    python scripts/generate_hype.py --dry-run                # 看会生成什么，不写库
    python scripts/generate_hype.py --from-seed              # 用种子问题写库
    python scripts/generate_hype.py --llm --limit 5          # 只对前 5 个片段调模型，省钱
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KNOWLEDGE_DIR = ROOT / "data" / "knowledge"
SEED_FILE = KNOWLEDGE_DIR / "hype.json"
DEFAULT_API = "http://127.0.0.1:8080"

PROMPT = """下面是一段面试知识库的正文。请生成 3 个「这段正文能够回答的问题」，
要求：像真实候选人会问的那样口语化，不要照抄标题，不要编号，每行一个问题，只输出问题。

标题：{heading}
正文：
{text}
"""


def http_json(method, url, payload=None, timeout=60):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw.strip() else None


def load_docs(api):
    """从库里读全部文档 + 各自片段（正文与标题是真相源，以库为准）。"""
    return http_json("GET", f"{api}/api/knowledge/docs")


def load_seed():
    if not SEED_FILE.exists():
        return {}
    return json.loads(SEED_FILE.read_text(encoding="utf-8"))


def section_of(heading):
    """面包屑取最后一段：`H1 > H2` -> `H2`；只有 H1 时就是它本身。"""
    if not heading:
        return ""
    return heading.split(">")[-1].strip()


def ask_llm(base_url, api_key, model, chunk, timeout=60):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": PROMPT.format(
            heading=chunk["heading"] or "", text=chunk["text"])}],
        "temperature": 0.3,
        "max_tokens": 400,
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        node = json.loads(resp.read().decode("utf-8"))
    content = node["choices"][0]["message"]["content"]
    lines = [line.strip(" -·*0123456789.、") for line in content.splitlines()]
    return [line for line in lines if line]


def main():
    ap = argparse.ArgumentParser(description="HyPE 假设问题生成与写库")
    ap.add_argument("--api", default=os.environ.get("KNOWLEDGE_API", DEFAULT_API))
    ap.add_argument("--from-seed", action="store_true", help="用 data/knowledge/hype.json 的问题")
    ap.add_argument("--llm", action="store_true", help="调 OpenAI 兼容模型现场生成")
    ap.add_argument("--base-url", default=os.environ.get("HYPE_BASE_URL", "https://api.deepseek.com"))
    ap.add_argument("--api-key", default=os.environ.get("DEEPSEEK_API_KEY", ""))
    ap.add_argument("--model", default=os.environ.get("HYPE_MODEL", "deepseek-chat"))
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 个片段（试跑省钱）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not args.from_seed and not args.llm:
        raise SystemExit("要么 --from-seed，要么 --llm（也可以两个都给）")

    seed = load_seed() if args.from_seed else {}
    docs = load_docs(args.api)
    total_chunks = 0
    total_with_hype = 0
    processed = 0

    for doc in docs:
        code = doc["code"]
        # 库里没有「列出片段」的接口，用 search 拿不到全部，所以这里按 md 原文重新切一遍
        md = KNOWLEDGE_DIR / (doc["category"] or "") / (code.split("-", 1)[1] + ".md")
        if not md.exists():
            print(f"  跳过 {code}（找不到源文件 {md}）")
            continue
        chunks = split_markdown(md.read_text(encoding="utf-8"))
        payload = []
        for chunk in chunks:
            total_chunks += 1
            questions = seed.get(f"{code}|{section_of(chunk['heading'])}")
            if questions is None and args.llm:
                if args.limit and processed >= args.limit:
                    questions = None
                else:
                    if not args.api_key:
                        raise SystemExit("--llm 需要 --api-key 或环境变量 DEEPSEEK_API_KEY")
                    try:
                        questions = ask_llm(args.base_url, args.api_key, args.model, chunk)
                        processed += 1
                    except (urllib.error.URLError, KeyError, ValueError) as e:
                        print(f"  ! {code} / {chunk['heading']} 生成失败：{e}")
                        questions = None
            if questions:
                total_with_hype += 1
            payload.append({
                "heading": chunk["heading"],
                "text": chunk["text"],
                "hype": "\n".join(questions) if questions else None,
            })

        if args.dry_run:
            sample = [p for p in payload if p["hype"]][:2]
            print(f"  [dry] {code}：{len(payload)} 段，其中有假设问题 "
                  f"{sum(1 for p in payload if p['hype'])} 段")
            for p in sample:
                print(f"        · {p['heading']}\n          {p['hype'].replace(chr(10), ' / ')}")
            continue

        result = http_json("PUT", f"{args.api}/api/knowledge/docs/{code}/chunks", {"chunks": payload})
        print(f"  写入 {code}：{result['count']} 段（复用 {result['reused']} / 新增 "
              f"{result['inserted']} / 删除 {result['deleted']} / 重算向量 {result['reembedded']}）")

    print(f"\n片段 {total_chunks} 个，其中有假设问题 {total_with_hype} 个")
    if args.dry_run:
        print("[dry-run] 未写库")
    else:
        print(f"库现状：{http_json('GET', f'{args.api}/api/knowledge/health')}")


def split_markdown(text):
    """与后端 MarkdownChunker / import_knowledge.py 同规则（见那边注释）。"""
    lines = text.replace("\r\n", "\n").split("\n")
    h1 = h2 = None
    sections = []
    buf = []

    def flush():
        body = "\n".join(buf).strip()
        buf.clear()
        if not body:
            return
        if h2:
            heading = f"{h1} > {h2}" if h1 else h2
        else:
            heading = h1 or ""
        sections.append({"heading": heading, "text": body})

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("# "):
            flush()
            h1, h2 = stripped[2:].strip(), None
        elif stripped.startswith("## "):
            flush()
            h2 = stripped[3:].strip()
        else:
            buf.append(line)
    flush()

    out = []
    for section in sections:
        body = section["text"]
        if len(body) <= 900:
            out.append(section)
            continue
        parts, cur = [], ""
        for para in [p.strip() for p in body.split("\n\n") if p.strip()]:
            if cur and len(cur) + len(para) + 2 > 900:
                parts.append(cur)
                cur = para
            else:
                cur = f"{cur}\n\n{para}" if cur else para
        if cur:
            if parts and len(cur) < 40:
                parts[-1] = parts[-1] + "\n\n" + cur
            else:
                parts.append(cur)
        out.extend({"heading": section["heading"], "text": p} for p in parts)
    return out


if __name__ == "__main__":
    sys.exit(main())
