#!/usr/bin/env python3
"""知识库检索评测：跑评测集，对比单路与混合，并给出阈值标定依据。

为什么要这个脚本：阈值（minVector / minBm25）不能拍脑袋，
「问今天天气不注入」和「问内存泄漏必须查到」是两条互相拉扯的约束，
中间那条线只能用数据定。这份脚本输出三样东西：
  1. 三种模式的 Hit@1 / Hit@3 / MRR；
  2. 正样本「最高分」与负样本「最高分」的分布，两堆分得开才有阈值可用；
  3. 按候选阈值网格搜一遍，给出「正样本全过 + 负样本全拦」可行区间。

用法：
    python scripts/eval_knowledge.py                      # 用默认阈值，跑三模式对比
    python scripts/eval_knowledge.py --calibrate          # 额外输出阈值标定表
    python scripts/eval_knowledge.py --api http://127.0.0.1:8080
"""

import argparse
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = ROOT / "data" / "eval" / "knowledge-questions.json"
DEFAULT_API = "http://127.0.0.1:8080"


def fetch(api, q, job, mode, top_k, min_vector=None, min_bm25=None, w_vector=None, w_bm25=None):
    params = {"q": q, "topK": str(top_k), "mode": mode}
    if job:
        params["jobCode"] = job
    if min_vector is not None:
        params["minVector"] = str(min_vector)
    if min_bm25 is not None:
        params["minBm25"] = str(min_bm25)
    if w_vector is not None:
        params["wVector"] = str(w_vector)
    if w_bm25 is not None:
        params["wBm25"] = str(w_bm25)
    url = f"{api}/api/knowledge/search?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def first_rank(hits, doc_code):
    """期望文档在结果里的名次（1 起），没出现返回 0。"""
    for i, hit in enumerate(hits, start=1):
        if hit.get("docCode") == doc_code:
            return i
    return 0


def evaluate(api, positives, mode, top_k):
    """测「排序质量」，所以放开门槛（minVector=-1）：阈值是另一件事，混在一起测会看不清。"""
    hit1 = hit3 = 0
    mrr = 0.0
    misses = []
    for case in positives:
        data = fetch(api, case["q"], case["job"], mode, top_k, min_vector=-1.0, min_bm25=-1.0)
        rank = first_rank(data["hits"], case["doc"])
        if rank == 1:
            hit1 += 1
        if 1 <= rank <= 3:
            hit3 += 1
        if rank > 0:
            mrr += 1.0 / rank
        else:
            misses.append(case)
    n = len(positives)
    return {
        "mode": mode,
        "hit1": hit1 / n,
        "hit3": hit3 / n,
        "mrr": mrr / n,
        "misses": misses,
    }


def top_scores(api, q, job):
    """不过阈值拿全部候选，取两路的最高原始分。"""
    data = fetch(api, q, job, "hybrid", 5, min_vector=-1.0, min_bm25=-1.0)
    hits = data.get("hits") or []
    if not hits:
        return 0.0, 0.0
    return (max(h["vectorScore"] for h in hits), max(h["bm25Score"] for h in hits))


def sweep_weights(api, positives, top_k):
    """两路权重扫描：等权为什么不行、降到多少才不拖后腿，用数据说话。"""
    print("\n=== RRF 权重扫描（Hit@1 / MRR，BM25 权重固定 1.0）===")
    print(f"{'向量权重':>10}{'Hit@1':>10}{'Hit@3':>10}{'MRR':>10}")
    for w in (0.0, 0.2, 0.3, 0.4, 0.5, 0.7, 1.0):
        hit1 = hit3 = 0
        mrr = 0.0
        for case in positives:
            data = fetch(api, case["q"], case["job"], "hybrid", top_k,
                         min_vector=-1.0, min_bm25=-1.0, w_vector=w, w_bm25=1.0)
            rank = first_rank(data["hits"], case["doc"])
            if rank == 1:
                hit1 += 1
            if 1 <= rank <= 3:
                hit3 += 1
            if rank > 0:
                mrr += 1.0 / rank
        n = len(positives)
        print(f"{w:>10.1f}{hit1 / n:>9.1%}{hit3 / n:>10.1%}{mrr / n:>10.3f}")


def calibrate(api, positives, negatives):
    """正样本 vs 负样本的最高分分布 + 阈值网格。"""
    print("\n=== 阈值标定 ===")
    print(f"{'类型':<6}{'查询':<34}{'最高向量分':>10}{'最高BM25':>10}")
    pos_rows, neg_rows = [], []
    for case in positives:
        v, b = top_scores(api, case["q"], case["job"])
        pos_rows.append((v, b))
        print(f"{'正':<6}{case['q'][:32]:<34}{v:>10.3f}{b:>10.2f}")
    for q in negatives:
        v, b = top_scores(api, q, None)
        neg_rows.append((v, b))
        print(f"{'负':<6}{q[:32]:<34}{v:>10.3f}{b:>10.2f}")

    pos_min_v, pos_min_b = min(r[0] for r in pos_rows), min(r[1] for r in pos_rows)
    neg_max_v, neg_max_b = max(r[0] for r in neg_rows), max(r[1] for r in neg_rows)
    print(f"\n正样本最低分：向量 {pos_min_v:.3f} / BM25 {pos_min_b:.2f}")
    print(f"负样本最高分：向量 {neg_max_v:.3f} / BM25 {neg_max_b:.2f}")

    # 判定规则是「向量过阈 或 BM25 过阈」——两路都放的负样本就是真误注入，
    # 所以可行区必须满足：对每个负样本，两路都不过阈。
    print("\n候选阈值网格（判定规则：向量≥阈值 或 BM25≥阈值 即注入）")
    print(f"{'向量阈值':>10}{'BM25阈值':>10}{'正样本通过':>12}{'负样本误注入':>14}")
    best = None
    for vt in [0.15, 0.20, 0.22, 0.25, 0.28, 0.30, 0.32, 0.35, 0.40]:
        for bt in [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0]:
            passed = sum(1 for v, b in pos_rows if v >= vt or b >= bt)
            wrong = sum(1 for v, b in neg_rows if v >= vt or b >= bt)
            if wrong == 0 and passed == len(pos_rows):
                # 可行区间里挑「离两边都远」的：正样本最低分与阈值、阈值与负样本最高分的较小间隔最大
                margin = min(pos_min_v - vt, vt - neg_max_v)
                if best is None or margin > best[0]:
                    best = (margin, vt, bt)
            print(f"{vt:>10.2f}{bt:>10.2f}{passed:>8}/{len(pos_rows):<4}{wrong:>10}/{len(neg_rows):<4}")
    if best is not None:
        print(f"\n推荐：minVectorScore={best[1]:.2f}  minBm25Score={best[2]:.1f}"
              f"（正样本全过、负样本全拦，两侧最小间隔 {best[0]:.3f}）")
    else:
        print("\n没有一组阈值能同时满足「正样本全过 + 负样本全拦」，需要改检索或降要求。")


def main():
    ap = argparse.ArgumentParser(description="知识库检索评测")
    ap.add_argument("--api", default=DEFAULT_API)
    ap.add_argument("--top-k", type=int, default=10)
    ap.add_argument("--calibrate", action="store_true", help="额外跑阈值标定")
    ap.add_argument("--sweep", action="store_true", help="额外跑两路权重扫描")
    args = ap.parse_args()

    spec = json.loads(QUESTIONS.read_text(encoding="utf-8"))
    positives, negatives = spec["positives"], spec["negatives"]

    print(f"评测集：正样本 {len(positives)} 条，负样本 {len(negatives)} 条，目标 {args.api}")
    results = [evaluate(args.api, positives, mode, args.top_k)
               for mode in ("bm25", "vector", "hybrid")]

    print(f"\n{'模式':<8}{'Hit@1':>10}{'Hit@3':>10}{'MRR':>10}")
    for r in results:
        print(f"{r['mode']:<8}{r['hit1']:>10.1%}{r['hit3']:>10.1%}{r['mrr']:>10.3f}")

    hybrid = next(r for r in results if r["mode"] == "hybrid")
    if hybrid["misses"]:
        print("\n混合检索未命中的问题：")
        for case in hybrid["misses"]:
            print(f"  · {case['q']}  期望 {case['doc']}")

    wrong = 0
    for q in negatives:
        data = fetch(args.api, q, None, "hybrid", 5)
        if data["hits"]:
            wrong += 1
            print(f"  ! 误注入：{q} -> {data['hits'][0]['docCode']}")
    print(f"\n负样本误注入：{wrong}/{len(negatives)}（默认阈值）")

    if args.calibrate:
        calibrate(args.api, positives, negatives)
    if args.sweep:
        sweep_weights(args.api, positives, args.top_k)


if __name__ == "__main__":
    sys.exit(main())
