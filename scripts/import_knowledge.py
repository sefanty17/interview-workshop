#!/usr/bin/env python3
"""岗位知识库导入脚本：Markdown → 切分 → 写库（走 HTTP 接口，不碰 H2 文件锁）。

设计要点（每条都有理由，改之前先读）：

1. **按 Markdown 标题切，不按定长切**。一个 `##` 小节天然就是「一个知识点」，
   切出来的 chunk 语义完整、heading 能当面包屑用。定长切会把一句话劈成两半。
2. **文件级幂等**。每篇 md 算一个内容指纹，和本地 `.knowledge-index.json` 比对，
   没变就整篇跳过（不调接口、不算向量）。用户随手改一个 md 不会导致全量重建。
3. **走 HTTP 不用 JDBC**。H2 是文件库，后端在跑时独占，脚本直连会 `Database may be
   already in use`。
4. **纯标准库**。本机 pip 直连与清华源都 502，装不了 requests。

用法：
    python scripts/import_knowledge.py --dry-run          # 只看切分结果，不写库
    python scripts/import_knowledge.py                     # 全量导入
    python scripts/import_knowledge.py --force             # 忽略指纹强制重传
    python scripts/import_knowledge.py --role strict,expert  # 导入后挂到角色卡
    python scripts/import_knowledge.py --selftest          # 只跑切分逻辑断言，不需要后端
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

# 单个 chunk 的目标上限。超了就按空行分段二次切。
# 依据：HashingEmbeddingProvider 对长文本做 bigram 哈希，块越长特征越糊；
# 生成侧要能塞进 prompt，400~900 字的中文段落既完整又不挤占上下文。
MAX_CHUNK_CHARS = 900
# 二次切分时小于这个长度的段并入上一段，避免出现「一句话一个 chunk」的碎片。
MIN_CHUNK_CHARS = 40

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "data" / "knowledge"
INDEX_FILE = DEFAULT_DIR / ".knowledge-index.json"
DEFAULT_API = "http://127.0.0.1:8080"


# ---------------------------------------------------------------- 切分

def parse_doc(text):
    """把一篇 md 切成 [(heading, body)]。

    heading 是面包屑形态：`H1 > H2`；H1 之前的引言段 heading 取 H1 本身。
    `###` 及更细的层级不切分——它们是同一知识点内部的展开，切碎反而丢上下文。
    """
    lines = text.replace("\r\n", "\n").split("\n")
    h1 = None
    h2 = None
    sections = []
    buf = []

    def flush():
        body = "\n".join(buf).strip()
        if body:
            if h2:
                heading = f"{h1} > {h2}" if h1 else h2
            else:
                heading = h1 or ""
            sections.append((heading, body))
        buf.clear()

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("# "):
            flush()
            h1 = stripped[2:].strip()
            h2 = None
        elif stripped.startswith("## "):
            flush()
            h2 = stripped[3:].strip()
        elif stripped.startswith("#"):
            # ### 及以下：当正文，不切
            buf.append(line)
        else:
            buf.append(line)
    flush()
    return sections


def split_long(heading, body):
    """超长小节按空行分段二次切；不足 MIN 的段并入上一段。"""
    if len(body) <= MAX_CHUNK_CHARS:
        return [(heading, body)]
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    out = []
    cur = ""
    for para in paragraphs:
        if cur and len(cur) + len(para) + 2 > MAX_CHUNK_CHARS:
            out.append((heading, cur))
            cur = para
        else:
            cur = f"{cur}\n\n{para}" if cur else para
    if cur:
        if out and len(cur) < MIN_CHUNK_CHARS:
            out[-1] = (heading, out[-1][1] + "\n\n" + cur)
        else:
            out.append((heading, cur))
    return out


def chunk_document(text):
    """md 全文 → chunk 列表。这是脚本的核心，--selftest 会断言它的行为。"""
    chunks = []
    for heading, body in parse_doc(text):
        for h, b in split_long(heading, body):
            chunks.append({"heading": h, "text": b})
    return chunks


def content_fingerprint(text):
    """整篇内容指纹（sha1 前 16 位）。变动检测用，和 Java 侧的 chunk 级 fnv 不是一回事。"""
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


# ---------------------------------------------------------------- HTTP

def http_json(method, url, payload=None, timeout=30):
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = raw
        return e.code, body
    except urllib.error.URLError as e:
        raise SystemExit(f"连不上后端 {url}：{e.reason}\n后端没起就先 `bash scripts/dev.sh`")


# ---------------------------------------------------------------- 主流程

def collect_docs(base_dir):
    """扫 <base>/<category>/<name>.md，返回 [(code, category, path, title, text)]。"""
    docs = []
    for category_dir in sorted(p for p in base_dir.iterdir() if p.is_dir()):
        if category_dir.name.startswith("."):
            continue
        for md in sorted(category_dir.glob("*.md")):
            text = md.read_text(encoding="utf-8")
            title = md.stem
            for line in text.split("\n"):
                if line.strip().startswith("# "):
                    title = line.strip()[2:].strip()
                    break
            code = f"{category_dir.name}-{md.stem}".lower().replace("_", "-")
            docs.append((code, category_dir.name, md, title, text))
    return docs


def load_index():
    if INDEX_FILE.exists():
        try:
            return json.loads(INDEX_FILE.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return {}
    return {}


def save_index(index):
    INDEX_FILE.parent.mkdir(parents=True, exist_ok=True)
    INDEX_FILE.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")


def ensure_doc(api, code, title, category, path):
    """建文档；已存在（409）就当成功——脚本靠 code 做 upsert。"""
    payload = {
        "code": code,
        "title": title,
        "category": category,
        "sourcePath": str(path.relative_to(ROOT)).replace("\\", "/"),
        "description": None,
    }
    status, body = http_json("POST", f"{api}/api/knowledge/docs", payload)
    if status in (200, 201):
        return True
    if status == 409:
        return True
    raise SystemExit(f"建文档失败 {code}：HTTP {status} {body}")


def upload_chunks(api, code, chunks):
    """整篇提交，后端按指纹做增量同步：命中的片段复用，只有变了的才重算向量。"""
    status, body = http_json("PUT", f"{api}/api/knowledge/docs/{code}/chunks", {"chunks": chunks})
    if status not in (200, 201):
        raise SystemExit(f"写片段失败 {code}：HTTP {status} {body}")
    return body


def merge_roles(api, role_codes, doc_codes):
    """把本次导入的 doc 并进角色的勾选（接口是整份覆盖，所以先读再合并）。"""
    for role in role_codes:
        status, body = http_json("GET", f"{api}/api/interviewers/{role}/knowledge")
        existing = body.get("docCodes", []) if isinstance(body, dict) else []
        merged = sorted(set(existing) | set(doc_codes))
        status, _ = http_json("PUT", f"{api}/api/interviewers/{role}/knowledge", {"docCodes": merged})
        if status not in (200, 204):
            raise SystemExit(f"挂角色失败 {role}：HTTP {status}")
        print(f"  角色 {role}：{len(existing)} → {len(merged)} 篇")


def run_selftest():
    """切分逻辑自测：不需要后端。断言的是「行为」不是「不报错」。"""
    md = """# 测试文档

开头的引言段，没有二级标题。

## 第一节

正文一。

## 第二节

正文二。

### 三级不切

三级标题下的内容应该归到第二节里。
"""
    chunks = chunk_document(md)
    assert len(chunks) == 3, f"应切 3 段，实际 {len(chunks)}"
    assert chunks[0]["heading"] == "测试文档", chunks[0]["heading"]
    assert chunks[1]["heading"] == "测试文档 > 第一节"
    assert chunks[2]["heading"] == "测试文档 > 第二节"
    assert "三级不切" in chunks[2]["text"], "### 内容必须并入二级节"

    # 每段 128 字 × 10 段 = 1298 字，必须明显超过 MAX_CHUNK_CHARS 才能验证二次切分。
    # 之前写成 6 段（778 字）没触发切分，断言失败过——测试数据自己得先够长。
    long_body = "\n\n".join(["这是一段用于测试切分的正文内容。" * 8] * 10)
    long_md = f"# 长文档\n\n## 超长节\n\n{long_body}\n"
    parts = chunk_document(long_md)
    assert len(parts) > 1, "超长节必须被二次切分"
    assert all(len(p["text"]) <= MAX_CHUNK_CHARS + 50 for p in parts), "二次切分后仍超长"
    assert all(p["heading"] == "长文档 > 超长节" for p in parts), "二次切分必须保留同一面包屑"

    assert content_fingerprint("abc") == content_fingerprint("abc")
    assert content_fingerprint("abc") != content_fingerprint("abd")
    print("selftest OK：3 条断言组全过（标题切分 / 超长二次切 / 指纹稳定性）")


def main():
    ap = argparse.ArgumentParser(description="岗位知识库导入")
    ap.add_argument("--dir", default=str(DEFAULT_DIR))
    ap.add_argument("--api", default=os.environ.get("KNOWLEDGE_API", DEFAULT_API))
    ap.add_argument("--role", default="", help="导入后挂到这些角色卡（逗号分隔）")
    ap.add_argument("--force", action="store_true", help="忽略指纹，强制重传")
    ap.add_argument("--dry-run", action="store_true", help="只打印切分结果，不写库")
    ap.add_argument("--selftest", action="store_true", help="只跑切分自测")
    args = ap.parse_args()

    if args.selftest:
        run_selftest()
        return

    base = Path(args.dir)
    if not base.is_dir():
        raise SystemExit(f"目录不存在：{base}")

    docs = collect_docs(base)
    if not docs:
        raise SystemExit(f"{base} 下没有 <分类>/<名字>.md 结构")

    index = load_index()
    roles = [r.strip() for r in args.role.split(",") if r.strip()]
    imported, skipped, total_chunks = [], [], 0

    print(f"扫描到 {len(docs)} 篇文档，目标 {args.api}")
    for code, category, path, title, text in docs:
        chunks = chunk_document(text)
        fp = content_fingerprint(text)
        unchanged = index.get(code, {}).get("fingerprint") == fp
        if unchanged and not args.force:
            print(f"  跳过 {code}（内容未变，{len(chunks)} 段）")
            skipped.append(code)
            continue
        if args.dry_run:
            print(f"  [dry] {code} → {len(chunks)} 段")
            for c in chunks[:3]:
                print(f"        · {c['heading']}  ({len(c['text'])} 字)")
            if len(chunks) > 3:
                print(f"        · … 还有 {len(chunks) - 3} 段")
            continue
        ensure_doc(args.api, code, title, category, path)
        res = upload_chunks(args.api, code, chunks)
        n = res.get("count", 0)
        total_chunks += n
        index[code] = {"fingerprint": fp, "chunks": n, "title": title, "category": category}
        save_index(index)
        print(f"  导入 {code}：{n} 段（复用 {res.get('reused', 0)} / "
              f"新增 {res.get('inserted', 0)} / 删除 {res.get('deleted', 0)} / "
              f"重算向量 {res.get('reembedded', 0)}）")
        imported.append(code)

    if args.dry_run:
        print("\n[dry-run] 未写库、未改索引")
        return

    if roles and imported:
        print("挂到角色卡：")
        merge_roles(args.api, roles, imported)

    print(f"\n完成：导入 {len(imported)} 篇 / {total_chunks} 段，跳过 {len(skipped)} 篇")
    if imported:
        status, body = http_json("GET", f"{args.api}/api/knowledge/health")
        print(f"库现状：{body}")


if __name__ == "__main__":
    sys.exit(main())
