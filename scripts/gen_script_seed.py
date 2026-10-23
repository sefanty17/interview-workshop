#!/usr/bin/env python3
"""生成面试剧本种子数据。

结构 1:1 对齐 LingChat 剧本引擎：
  story_config.yaml  → interview_script
  Chapters/*.yaml    → interview_chapter（events 为事件数组）
事件类型与字段照搬：ai_dialogue / input / narration / chapter_end。

chapter_end 支持三种跳转（同 LingChat）：
  linear     直接 next_chapter
  branching  按 options[].condition 对 variable_info 求值
  ai_judged  交给 LLM 在 options[].name 里选一个

输出：
  data/seed/scripts.json     结构化数据（给后端/前端读）
  data/seed/scripts-h2.sql   H2 种子
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_JSON = ROOT / "data" / "seed" / "scripts.json"
OUT_SQL = ROOT / "data" / "seed" / "scripts-h2.sql"

sys.stdout.reconfigure(encoding="utf-8")


def ai(prompt: str) -> dict:
    return {"type": "ai_dialogue", "character": "MAIN", "prompt": prompt}


def inp(hint: str = "") -> dict:
    return {"type": "input", "hint": hint}


def narration(text: str) -> dict:
    return {"type": "narration", "text": text}


def end_linear(next_chapter: str) -> dict:
    return {"type": "chapter_end", "end_type": "linear", "next_chapter": next_chapter}


def end_judged(prompt: str, names: list[str]) -> dict:
    # options 必须带 next：原版 chapter_end 事件先按 LLM 回复匹配 name，
    # 取不到 next 就一路回落到「end」，整个剧本会当场终止。
    return {
        "type": "chapter_end",
        "end_type": "ai_judged",
        "prompt": prompt,
        "options": [{"name": n, "next": n} for n in names],
    }


SCRIPTS = [
    {
        "code": "java-strict",
        "script_name": "Java 后端 · 严格三轮",
        "intro_chapter": "intro",
        "description": "面向校招 Java 后端：基础扎实度、项目真实性、场景设计三层递进，答得含糊会被当场追问。",
        "recommand_start": "选择「严格技术面」面试官时",
        "interviewer_code": "strict",
        "order_num": 1,
        "job_code": "java",
        "level": "校招",
        "company_style": "一线互联网大厂",
        "script_settings": {"total_questions": 8, "time_limit": 40, "follow_up_depth": 3},
        "chapters": [
            {
                "code": "intro",
                "name": "破冰与自我介绍",
                "order_num": 1,
                "events": [
                    narration("面试开始。录音与计时已开启。"),
                    ai("以面试官身份开场，请候选人做一分钟自我介绍。三句以内，不要寒暄客套。"),
                    inp("一分钟自我介绍"),
                    ai("从自我介绍里挑一个项目，追问一个具体细节：他在其中具体负责哪部分、遇到的最大问题是什么。"),
                    inp("项目里你负责的部分"),
                    end_linear("basic"),
                ],
            },
            {
                "code": "basic",
                "name": "基础技术",
                "order_num": 2,
                "events": [
                    ai("提问 JVM 内存分区：堆、栈、方法区分别放什么，什么情况下会 OOM。要求说清依据，不接受背诵式回答。"),
                    inp(),
                    ai("针对上一题回答里含糊的点追问一次。若他答得完整，改问 HashMap 扩容机制与并发场景下的问题。"),
                    inp(),
                    ai("提问 Spring 事务在哪些情况下会失效，至少举两个场景并说明原理。"),
                    inp(),
                    end_judged(
                        "评估候选人在基础题阶段的表现。若 3 题里至少 2 题说出了关键点，选 deep 进入项目深挖；"
                        "若明显吃力，选 basic_more 再补一道基础题。",
                        ["deep", "basic_more"],
                    ),
                ],
            },
            {
                "code": "basic_more",
                "name": "基础补充",
                "order_num": 3,
                "events": [
                    ai("补一道基础题：MySQL 索引为什么用 B+ 树，而不是红黑树或哈希表。"),
                    inp(),
                    end_linear("deep"),
                ],
            },
            {
                "code": "deep",
                "name": "项目深挖",
                "order_num": 4,
                "events": [
                    ai("挑候选人简历里最有分量的项目，追问架构：为什么这么分层、数据量涨十倍后哪里会先垮。"),
                    inp(),
                    ai("针对上一题的技术选型，追问替代方案与代价。连续追问不超过 3 次，第 3 次后必须换角度。"),
                    inp(),
                    end_linear("scenario"),
                ],
            },
            {
                "code": "scenario",
                "name": "场景设计",
                "order_num": 5,
                "events": [
                    ai("场景设计题：设计支持十万并发的秒杀下单接口，讲清限流、库存扣减、超卖防护。"),
                    inp(),
                    end_linear("close"),
                ],
            },
            {
                "code": "close",
                "name": "反问与收尾",
                "order_num": 6,
                "events": [
                    narration("面试进入收尾阶段。"),
                    ai("请候选人提问。回答完后给出两条针对本场表现的具体建议，不要说套话，然后结束面试。"),
                    inp("你有什么想问的？"),
                    end_linear("end"),
                ],
            },
        ],
    },
    {
        "code": "frontend-standard",
        "script_name": "Web 前端 · 常规流程",
        "intro_chapter": "intro",
        "description": "面向校招前端：从项目切入基础与框架原理，节奏平缓，允许候选人充分表达。",
        "recommand_start": "选择「温和 HR 面」面试官时",
        "interviewer_code": "friendly",
        "order_num": 2,
        "job_code": "frontend",
        "level": "校招",
        "company_style": "成长型互联网公司",
        "script_settings": {"total_questions": 6, "time_limit": 30, "follow_up_depth": 2},
        "chapters": [
            {
                "code": "intro",
                "name": "自我介绍与项目概览",
                "order_num": 1,
                "events": [
                    narration("面试开始，放松一点，当作聊天。"),
                    ai("用轻松的语气请候选人做自我介绍，并说说最近做得最有成就感的一个项目。"),
                    inp("自我介绍 + 一个项目"),
                    end_linear("project"),
                ],
            },
            {
                "code": "project",
                "name": "项目经历",
                "order_num": 2,
                "events": [
                    ai("就候选人说的项目，追问三个点：技术栈选型、他遇到的性能瓶颈、最后怎么解决的。一次只问一个。"),
                    inp(),
                    ai("根据上一题的回答继续追问一层，最多追问两次；他答不上来就给个方向提示，不要打断。"),
                    inp(),
                    end_linear("basic"),
                ],
            },
            {
                "code": "basic",
                "name": "技术基础",
                "order_num": 3,
                "events": [
                    ai("提问：事件循环里宏任务与微任务的执行顺序，举一段代码说清输出。"),
                    inp(),
                    ai("提问 Vue 3 响应式原理：Proxy 相比 Object.defineProperty 解决了什么问题。"),
                    inp(),
                    end_judged(
                        "评估候选人的基础掌握程度。若两题都能说出原理，选 perf 进入性能优化；否则选 close 直接收尾。",
                        ["perf", "close"],
                    ),
                ],
            },
            {
                "code": "perf",
                "name": "性能优化",
                "order_num": 4,
                "events": [
                    ai("提问：一个首屏 5 秒的页面，你会按什么顺序排查和优化？要求给出可执行的步骤。"),
                    inp(),
                    end_linear("close"),
                ],
            },
            {
                "code": "close",
                "name": "反问与收尾",
                "order_num": 5,
                "events": [
                    ai("请候选人提问，回答后给两条具体建议，然后结束。"),
                    inp("你有什么想问的？"),
                    end_linear("end"),
                ],
            },
        ],
    },
    {
        "code": "python-pressure",
        "script_name": "Python 算法 · 压力面",
        "intro_chapter": "intro",
        "description": "面向校招算法岗：节奏快、追问密，考察抗压与思维严密性，卡壳只给一次提示。",
        "recommand_start": "选择「压力面」面试官时",
        "interviewer_code": "pressure",
        "order_num": 3,
        "job_code": "python",
        "level": "校招",
        "company_style": "高频面试的头部大厂",
        "script_settings": {"total_questions": 7, "time_limit": 25, "follow_up_depth": 5},
        "chapters": [
            {
                "code": "intro",
                "name": "快速破冰",
                "order_num": 1,
                "events": [
                    narration("面试开始，节奏会很快。"),
                    ai("简短开场，直接问：用三句话说明你最擅长的一个技术方向，不要铺垫。"),
                    inp("三句话"),
                    end_linear("algo"),
                ],
            },
            {
                "code": "algo",
                "name": "算法与复杂度",
                "order_num": 2,
                "events": [
                    ai("出题：给一个无序数组找出第 K 大的数，先说思路再说复杂度，最后说优化空间。每问一步都要等他答完再问下一步。"),
                    inp(),
                    ai("针对他的方案连续追问：时间复杂度还能不能降、空间换时间是否值得、边界条件怎么处理。每次只问一个。"),
                    inp(),
                    end_linear("python"),
                ],
            },
            {
                "code": "python",
                "name": "Python 语言特性",
                "order_num": 3,
                "events": [
                    ai("提问：GIL 对多线程的影响，什么场景下多进程比多线程更合适。"),
                    inp(),
                    ai("追问：装饰器与闭包的区别，写一个带参数的装饰器要考虑什么。"),
                    inp(),
                    end_judged(
                        "评估候选人。若两题都答到关键点，选 deep 加压；若有一题完全卡住，选 close 收尾。",
                        ["deep", "close"],
                    ),
                ],
            },
            {
                "code": "deep",
                "name": "深度追问",
                "order_num": 4,
                "events": [
                    ai("追问：如果一个 Python 服务 CPU 打满，你怎么定位？给出完整的排查链路。"),
                    inp(),
                    end_linear("close"),
                ],
            },
            {
                "code": "close",
                "name": "收尾",
                "order_num": 5,
                "events": [
                    ai("不给缓冲，直接问：本场你觉得自己哪道题答得最差，为什么。然后给一条建议并结束。"),
                    inp(),
                    end_linear("end"),
                ],
            },
        ],
    },
    {
        "code": "java-expert",
        "script_name": "Java 后端 · 技术专家面",
        "intro_chapter": "intro",
        "description": "面向校招但按专家标准：只问原理与权衡，不讲结论讲依据，考察技术判断力。",
        "recommand_start": "选择「技术专家面」面试官时",
        "interviewer_code": "expert",
        "order_num": 4,
        "job_code": "java",
        "level": "校招（专家标准）",
        "company_style": "技术驱动型团队",
        "script_settings": {"total_questions": 7, "time_limit": 45, "follow_up_depth": 4},
        "chapters": [
            {
                "code": "intro",
                "name": "背景与方向",
                "order_num": 1,
                "events": [
                    narration("面试开始。我会从基础到系统设计逐层提问。"),
                    ai("请候选人说明自己的技术方向与最熟悉的一块，并给一个能证明的例子。"),
                    inp("技术方向 + 例子"),
                    end_linear("principle"),
                ],
            },
            {
                "code": "principle",
                "name": "原理层",
                "order_num": 2,
                "events": [
                    ai("提问：synchronized 与 ReentrantLock 在实现层面的差别，各自适用什么场景。要求讲清 AQS 的作用。"),
                    inp(),
                    ai("追问：从偏向锁到重量级锁的升级过程，为什么要设计这套升级机制。"),
                    inp(),
                    end_linear("design"),
                ],
            },
            {
                "code": "design",
                "name": "系统设计",
                "order_num": 3,
                "events": [
                    ai("设计题：一个日均千万级请求的短链服务，讲清存储选型、缓存策略、ID 生成方案与容量估算。"),
                    inp(),
                    ai("针对他的设计挑一个薄弱环节追问：如果缓存全部失效会发生什么，你怎么防。"),
                    inp(),
                    end_linear("tradeoff"),
                ],
            },
            {
                "code": "tradeoff",
                "name": "权衡与取舍",
                "order_num": 4,
                "events": [
                    ai("提问：让你在强一致与高可用之间做选择，你会怎么向业务方解释这个取舍？给出判断依据。"),
                    inp(),
                    end_linear("close"),
                ],
            },
            {
                "code": "close",
                "name": "收尾与建议",
                "order_num": 5,
                "events": [
                    ai("请候选人提问。之后给两条具体、可执行的改进建议，说明依据，然后结束。"),
                    inp("你有什么想问的？"),
                    end_linear("end"),
                ],
            },
        ],
    },
]


def sql_str(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def main() -> None:
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(SCRIPTS, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        "-- 面试剧本种子（H2）",
        "-- 由 scripts/gen_script_seed.py 生成，请勿手改。",
        "-- 结构对齐 LingChat: story_config.yaml + Chapters/*.yaml",
        "",
    ]

    for s in SCRIPTS:
        cols = [
            "code", "script_name", "intro_chapter", "description", "recommand_start",
            "order_num", "trigger_mode", "job_code", "level", "company_style", "script_settings",
        ]
        vals = [
            sql_str(s["code"]), sql_str(s["script_name"]), sql_str(s["intro_chapter"]),
            sql_str(s["description"]), sql_str(s["recommand_start"]), sql_str(s["order_num"]),
            sql_str("manual"), sql_str(s["job_code"]), sql_str(s["level"]),
            sql_str(s["company_style"]),
            sql_str(json.dumps(s["script_settings"], ensure_ascii=False)),
        ]
        iv = f"(SELECT id FROM interviewer WHERE code = {sql_str(s['interviewer_code'])})"
        lines.append(
            f"INSERT INTO interview_script ({', '.join(cols)}, interviewer_id) "
            f"VALUES ({', '.join(vals)}, {iv});"
        )

        for ch in s["chapters"]:
            events = json.dumps(ch["events"], ensure_ascii=False)
            lines.append(
                "INSERT INTO interview_chapter (script_id, code, name, order_num, events) "
                f"VALUES ((SELECT id FROM interview_script WHERE code = {sql_str(s['code'])}), "
                f"{sql_str(ch['code'])}, {sql_str(ch['name'])}, {ch['order_num']}, {sql_str(events)});"
            )
        lines.append("")

    OUT_SQL.write_text("\n".join(lines), encoding="utf-8")

    print(f"{'code':20} {'章节':>4} {'事件':>4}  名称")
    for s in SCRIPTS:
        ev = sum(len(c["events"]) for c in s["chapters"])
        print(f"{s['code']:20} {len(s['chapters']):>4} {ev:>4}  {s['script_name']}")
    print(f"\nJSON → {OUT_JSON.relative_to(ROOT)}")
    print(f"SQL  → {OUT_SQL.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
