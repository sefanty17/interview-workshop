#!/usr/bin/env python3
"""LingChat 角色卡 → 面试工坊数据基座。

迁移范围（只搬数据，不搬运行时）：
  1. data/game_data/characters/{角色}/settings.yml 的 14 个字段 → interviewers.json
  2. avatar/*.webp（每角色 21 张）→ frontend/public/avatars/{code}/
  3. 生成 H2 建表脚本 + 种子数据

不迁移：body_part（Live2D 摸头多边形）、voice_models（TTS 声库）、
        clothes、live2d、*_p（桌宠参数）、system_prompt_example_old（日文旧版）。

用法：
    python scripts/migrate_character_cards.py
"""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "references" / "lingchat" / "data" / "game_data" / "characters"
OUT_JSON = ROOT / "data" / "seed" / "interviewers.json"
OUT_SCHEMA = ROOT / "data" / "schema" / "schema-h2.sql"
OUT_DATA = ROOT / "data" / "seed" / "data-h2.sql"
OUT_EMOTION = ROOT / "data" / "seed" / "emotions.json"
AVATAR_DEST = ROOT / "frontend" / "public" / "avatars"

# 目录名 → 内部 code / 面试定位 / 开场白（人设本身 1:1 原样带入，这里只加业务外壳字段）
ROLE_MAP = {
    "风雪": {
        "code": "strict",
        "display_role": "严格技术面",
        "opening": "坐吧。今天不问废话，先说说你自己——一分钟，超时我会打断。",
        "follow_up": 3,
        "speed": 1,
        "sort_order": 1,
    },
    "诺一钦灵": {
        "code": "friendly",
        "display_role": "温和 HR 面",
        "opening": "欢迎来面试～别紧张，我们就当随便聊聊你最近做过的东西。",
        "follow_up": 2,
        "speed": 2,
        "sort_order": 2,
    },
    "松子": {
        "code": "pressure",
        "display_role": "压力面",
        "opening": "来啦？那开始吧——我可不一定会给你好脸色哦。",
        "follow_up": 4,
        "speed": 2,
        "sort_order": 3,
    },
    "DeepSeek": {
        "code": "expert",
        "display_role": "技术专家面",
        "opening": "面试开始。我会从基础到系统设计逐层提问，请尽量给出依据。",
        "follow_up": 3,
        "speed": 1,
        "sort_order": 4,
    },
}

TOP_KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):(.*)$")


def parse_scalar(raw: str):
    """解析单行标量：去引号、识别 null / 布尔 / 数字。"""
    v = raw.strip()
    if v in ("", "null", "~", "NULL"):
        return None
    if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
        return v[1:-1]
    if v.lower() in ("true", "false"):
        return v.lower() == "true"
    try:
        return int(v)
    except ValueError:
        pass
    try:
        return float(v)
    except ValueError:
        pass
    return v


def parse_top_level(text: str) -> dict:
    """只解析顶层键，块标量按缩进收集，嵌套结构整体跳过。

    足够覆盖 settings.yml 的形态（顶层键全部顶格、值只有单行标量 / 块标量 / 嵌套对象三种）。
    """
    lines = text.split("\n")
    out: dict = {}
    i, n = 0, len(lines)

    while i < n:
        line = lines[i]
        if not line.strip() or line.lstrip().startswith("#"):
            i += 1
            continue
        m = TOP_KEY_RE.match(line)
        if not m:
            i += 1
            continue

        key, rest = m.group(1), m.group(2).strip()
        i += 1

        if rest.startswith("|"):
            block: list[str] = []
            while i < n:
                cur = lines[i]
                if cur.strip() == "":
                    block.append("")
                    i += 1
                    continue
                if not cur.startswith((" ", "\t")):
                    break
                block.append(cur)
                i += 1
            indents = [len(b) - len(b.lstrip()) for b in block if b.strip()]
            base = min(indents) if indents else 0
            block = [b[base:] if len(b) >= base else "" for b in block]
            while block and block[-1] == "":
                block.pop()
            val = "\n".join(block)
            if rest == "|":
                val += "\n"
            out[key] = val
        elif rest == "":
            # 嵌套对象或空值：吃掉所有缩进行
            while i < n and (lines[i].startswith((" ", "\t")) or lines[i].strip() == ""):
                i += 1
            out[key] = None
        else:
            out[key] = parse_scalar(rest)

    return out


def sql_str(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def build_interviewers() -> list[dict]:
    rows = []
    for folder in sorted(SRC.iterdir()):
        if not folder.is_dir():
            continue
        settings = folder / "settings.yml"
        if not settings.exists():
            continue
        meta = ROLE_MAP.get(folder.name)
        if meta is None:
            print(f"[skip] 未登记的目录：{folder.name}")
            continue

        data = parse_top_level(settings.read_text(encoding="utf-8"))
        name = data.get("ai_name") or folder.name

        rows.append(
            {
                "code": meta["code"],
                "name": name,
                "subtitle": data.get("ai_subtitle"),
                "card_title": data.get("title"),
                "card_info": data.get("info"),
                "persona_prompt": data.get("system_prompt"),
                "example_dialogue": data.get("system_prompt_example"),
                "thinking_message": data.get("thinking_message"),
                # 源卡多为 live2d，本项目统一用静态情绪图（21 张 webp）
                "avatar_mode": "image",
                "scale": data.get("scale") if isinstance(data.get("scale"), (int, float)) else 1.0,
                "offset_x": data.get("offset_x") if isinstance(data.get("offset_x"), (int, float)) else 0,
                "offset_y": data.get("offset_y") if isinstance(data.get("offset_y"), (int, float)) else 0,
                "bubble_top": data.get("bubble_top") if isinstance(data.get("bubble_top"), int) else 5,
                "bubble_left": data.get("bubble_left") if isinstance(data.get("bubble_left"), int) else 25,
                # 原卡里的「我」的称呼，原样保留；实际使用的昵称走 user_profile
                "user_alias": data.get("user_name"),
                "user_alias_subtitle": data.get("user_subtitle"),
                "display_role": meta["display_role"],
                "opening": meta["opening"],
                "follow_up": meta["follow_up"],
                "speed": meta["speed"],
                "sort_order": meta["sort_order"],
                "source_folder": folder.name,
            }
        )
    return rows


# 情绪全集：LLM 的【情绪】只能取这些值，缺图用「正常」补位。
EMOTIONS = [
    "正常", "平静", "高兴", "兴奋", "害羞", "心动", "紧张", "慌张", "担心", "害怕",
    "惊讶", "疑惑", "无奈", "生气", "厌恶", "伤心", "羞耻", "自信", "认真", "调皮",
]
FALLBACK = "正常"

# 别名映射：原角色卡的人设/范例里自由发挥的情绪词（实测出现 冷淡/盯着/正经/正经 等），
# 以及模型常见同义词，统一折叠到上面 20 个枚举。
EMOTION_ALIASES = {
    "默认": "正常", "普通": "正常", "自然": "正常", "中性": "正常", "盯着": "正常",
    "平静": "平静", "淡定": "平静", "冷淡": "平静", "冷漠": "平静", "冷静": "平静",
    "高兴": "高兴", "开心": "高兴", "愉快": "高兴", "喜悦": "高兴", "欣慰": "高兴",
    "兴奋": "兴奋", "激动": "兴奋", "雀跃": "兴奋",
    "害羞": "害羞", "羞怯": "害羞", "腼腆": "害羞", "脸红": "害羞",
    "心动": "心动", "喜欢": "心动", "甜蜜": "心动",
    "紧张": "紧张", "忐忑": "紧张", "局促": "紧张",
    "慌张": "慌张", "慌乱": "慌张", "手忙脚乱": "慌张",
    "担心": "担心", "忧虑": "担心", "关切": "担心", "心疼": "担心",
    "害怕": "害怕", "恐惧": "害怕", "惊恐": "害怕", "发怵": "害怕",
    "惊讶": "惊讶", "吃惊": "惊讶", "震惊": "惊讶", "意外": "惊讶",
    "疑惑": "疑惑", "困惑": "疑惑", "不解": "疑惑", "歪头": "疑惑",
    "无奈": "无奈", "无语": "无奈", "苦笑": "无奈", "叹气": "无奈",
    "生气": "生气", "愤怒": "生气", "恼火": "生气", "恼怒": "生气", "不满": "生气",
    "厌恶": "厌恶", "嫌弃": "厌恶", "反感": "厌恶", "鄙视": "厌恶",
    "伤心": "伤心", "难过": "伤心", "失落": "伤心", "沮丧": "伤心", "委屈": "伤心",
    "羞耻": "羞耻", "害羞难当": "羞耻", "难为情": "羞耻",
    "自信": "自信", "得意": "自信", "骄傲": "自信", "挑衅": "自信",
    "认真": "认真", "正经": "认真", "严肃": "认真", "专注": "认真", "沉思": "认真",
    "调皮": "调皮", "淘气": "调皮", "狡黠": "调皮", "坏笑": "调皮", "俏皮": "调皮",
}


def copy_avatars(rows: list[dict]) -> list[str]:
    logs = []
    AVATAR_DEST.mkdir(parents=True, exist_ok=True)
    for row in rows:
        src_dir = SRC / row["source_folder"] / "avatar"
        if not src_dir.is_dir():
            logs.append(f"[warn] 无 avatar 目录：{row['source_folder']}")
            continue
        dest = AVATAR_DEST / row["code"]
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True, exist_ok=True)
        files = sorted(p for p in src_dir.iterdir() if p.suffix.lower() in (".webp", ".png", ".jpg"))
        for f in files:
            shutil.copy2(f, dest / f.name)

        # 缺图补齐：源角色不一定 20 张表情都齐（实测 钦灵 缺「平静」）
        fallback_file = dest / f"{FALLBACK}.webp"
        filled = []
        if fallback_file.exists():
            for emo in EMOTIONS:
                target = dest / f"{emo}.webp"
                if not target.exists():
                    shutil.copy2(fallback_file, target)
                    filled.append(emo)
        else:
            logs.append(f"[warn] {row['code']} 连「{FALLBACK}.webp」都没有，无法补位")

        tail = f"，补位 {len(filled)} 张（{'/'.join(filled)}）" if filled else ""
        logs.append(f"[ok] {row['code']}: {len(files)} 张{tail} → {dest.relative_to(ROOT)}")
    return logs


SCHEMA_SQL = """-- 面试工坊 · 数据基座（H2）
-- 迁自 LingChat: role / save / line 三张核心表，按面试情境裁剪并归一化。
-- 换 MySQL 只需把 IDENTITY 换成 AUTO_INCREMENT、CLOB 换成 TEXT。

DROP TABLE IF EXISTS interview_message;
DROP TABLE IF EXISTS interview_session;
DROP TABLE IF EXISTS interviewer;
DROP TABLE IF EXISTS user_profile;

CREATE TABLE interviewer (
  id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code             VARCHAR(32)  NOT NULL UNIQUE,
  name             VARCHAR(64)  NOT NULL,
  subtitle         VARCHAR(64),
  card_title       VARCHAR(128),
  card_info        CLOB,
  persona_prompt   CLOB         NOT NULL,
  example_dialogue CLOB,
  thinking_message VARCHAR(128),
  avatar_mode      VARCHAR(16)  DEFAULT 'image',
  scale            DOUBLE       DEFAULT 1.0,
  offset_x         DOUBLE       DEFAULT 0,
  offset_y         DOUBLE       DEFAULT 0,
  bubble_top       INT          DEFAULT 5,
  bubble_left      INT          DEFAULT 25,
  user_alias       VARCHAR(64),
  user_alias_subtitle VARCHAR(64),
  display_role     VARCHAR(64),
  opening          VARCHAR(512),
  follow_up        INT          DEFAULT 2,
  speed            INT          DEFAULT 1,
  sort_order       INT          DEFAULT 0,
  enabled          BOOLEAN      DEFAULT TRUE,
  created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_profile (
  id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  nickname       VARCHAR(64)  NOT NULL,
  signature      VARCHAR(128),
  job_target     VARCHAR(32),
  school         VARCHAR(128),
  major          VARCHAR(128),
  grade          VARCHAR(32),
  student_no     VARCHAR(32),
  resume_summary CLOB,
  created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE interview_session (
  id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  title          VARCHAR(255) NOT NULL,
  user_id        BIGINT       NOT NULL,
  interviewer_id BIGINT       NOT NULL,
  job_code       VARCHAR(32)  NOT NULL,
  kps            VARCHAR(512),
  question_count INT          DEFAULT 6,
  time_limit     INT          DEFAULT 25,
  status         VARCHAR(16)  DEFAULT 'running',
  score          INT,
  started_at     TIMESTAMP,
  ended_at       TIMESTAMP,
  created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_sess_iv   FOREIGN KEY (interviewer_id) REFERENCES interviewer(id)
);

CREATE TABLE interview_message (
  id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  session_id BIGINT      NOT NULL,
  seq        INT         NOT NULL,
  role       VARCHAR(16) NOT NULL,
  content    CLOB        NOT NULL,
  emotion    VARCHAR(32),
  action     CLOB,
  msg_type   VARCHAR(16) DEFAULT 'text',
  tokens     INT,
  created_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_msg_sess FOREIGN KEY (session_id) REFERENCES interview_session(id)
);
"""

USER_PROFILE_SEED = {
    "nickname": "蔡智豪",
    "signature": "浙江工业大学 · 计算机学院",
    "job_target": "java",
    "school": "浙江工业大学",
    "major": "软件工程",
    "grade": "2025-2026 · 软工4班",
    "student_no": "302024315025",
    "resume_summary": "软件工程专业本科在读，主修 Java 后端方向。",
}


def write_schema() -> None:
    OUT_SCHEMA.parent.mkdir(parents=True, exist_ok=True)
    OUT_SCHEMA.write_text(SCHEMA_SQL, encoding="utf-8")


def write_seed_sql(rows: list[dict]) -> None:
    cols = [
        "code", "name", "subtitle", "card_title", "card_info", "persona_prompt",
        "example_dialogue", "thinking_message", "avatar_mode", "scale", "offset_x",
        "offset_y", "bubble_top", "bubble_left", "user_alias", "user_alias_subtitle",
        "display_role", "opening", "follow_up", "speed", "sort_order",
    ]
    lines = ["-- 面试工坊 · 种子数据（H2）", "-- 由 scripts/migrate_character_cards.py 生成，请勿手改。", ""]

    for row in rows:
        values = ", ".join(sql_str(row[c]) for c in cols)
        lines.append(f"INSERT INTO interviewer ({', '.join(cols)}) VALUES ({values});")
        lines.append("")

    up = USER_PROFILE_SEED
    up_cols = list(up.keys())
    lines.append(
        f"INSERT INTO user_profile ({', '.join(up_cols)}) VALUES "
        f"({', '.join(sql_str(up[c]) for c in up_cols)});"
    )
    lines.append("")
    OUT_DATA.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    rows = build_interviewers()
    if not rows:
        raise SystemExit("没有解析到任何角色卡，检查源路径")

    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    OUT_EMOTION.write_text(
        json.dumps(
            {
                "emotions": EMOTIONS,
                "fallback": FALLBACK,
                "aliases": EMOTION_ALIASES,
                "note": "LLM 输出的【情绪】必须先经 aliases 折叠到 emotions 枚举，再拼立绘路径。",
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    for log in copy_avatars(rows):
        print(log)

    write_schema()
    write_seed_sql(rows)

    print("\n=== 迁移摘要 ===")
    print(f"{'code':10} {'name':10} {'人设字数':>8} {'范例字数':>8}  来源目录")
    for r in rows:
        print(
            f"{r['code']:10} {r['name']:10} "
            f"{len(r['persona_prompt'] or ''):>8} {len(r['example_dialogue'] or ''):>8}  {r['source_folder']}"
        )
    print(f"\nJSON  → {OUT_JSON.relative_to(ROOT)}")
    print(f"EMO   → {OUT_EMOTION.relative_to(ROOT)}")
    print(f"SCHEMA→ {OUT_SCHEMA.relative_to(ROOT)}")
    print(f"SEED  → {OUT_DATA.relative_to(ROOT)}")
    print(f"立绘  → {AVATAR_DEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
