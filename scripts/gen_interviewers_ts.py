"""data/seed/interviewers.json → frontend/src/data/interviewers.ts

前端只带展示字段（人设全文留给后端），减小打包体积。
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = json.loads((ROOT / "data/seed/interviewers.json").read_text(encoding="utf-8"))
out = ROOT / "frontend/src/data/interviewers.ts"

sys.stdout.reconfigure(encoding="utf-8")

FIELDS = ["code", "name", "subtitle", "cardTitle", "cardInfo", "displayRole",
          "opening", "followUp", "speed", "scale", "offsetX", "offsetY",
          "bubbleTop", "bubbleLeft", "userAlias"]

def camel(d, k):
    m = {"card_title": "cardTitle", "card_info": "cardInfo", "display_role": "displayRole",
         "follow_up": "followUp", "offset_x": "offsetX", "offset_y": "offsetY",
         "bubble_top": "bubbleTop", "bubble_left": "bubbleLeft", "user_alias": "userAlias"}
    return d.get(m.get(k, k))

rows = []
for r in src:
    rows.append({
        "code": r["code"],
        "name": r["name"],
        "subtitle": r.get("subtitle") or "",
        "cardTitle": r.get("card_title") or "",
        "cardInfo": (r.get("card_info") or "").replace("\n", " / "),
        "displayRole": r.get("display_role") or "",
        "opening": r.get("opening") or "",
        "followUp": r.get("follow_up", 2),
        "speed": r.get("speed", 1),
        "scale": r.get("scale", 1.0),
        "offsetX": r.get("offset_x", 0),
        "offsetY": r.get("offset_y", 0),
        "bubbleTop": r.get("bubble_top", 5),
        "bubbleLeft": r.get("bubble_left", 25),
        "userAlias": r.get("user_alias") or "",
    })

def q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'

body = []
for r in rows:
    lines = ["  {"]
    for k, v in r.items():
        lines.append(f"    {k}: {q(v) if isinstance(v, str) else v},")
    lines.append("  },")
    body.append("\n".join(lines))

ts = f'''// 角色卡数据（由 scripts/gen_interviewers_ts.py 生成，勿手改）
// 源：references/lingchat/data/game_data/characters/*/settings.yml
// 人设全文与范例留在后端 interviewers.json，这里只放展示字段。

export type InterviewerCode = "strict" | "friendly" | "pressure" | "expert";

export interface Interviewer {{
  code: InterviewerCode;
  name: string;
  subtitle: string;
  cardTitle: string;
  cardInfo: string;
  displayRole: string;
  opening: string;
  followUp: number;
  speed: number;
  scale: number;
  offsetX: number;
  offsetY: number;
  bubbleTop: number;
  bubbleLeft: number;
  userAlias: string;
}}

export const interviewers: Interviewer[] = [
{chr(10).join(body)}
];

/** LLM 输出的【情绪】必须落在这 20 个词里，否则回落「正常」。 */
export const EMOTIONS = [
  "正常", "平静", "高兴", "兴奋", "害羞", "心动", "紧张", "慌张", "担心", "害怕",
  "惊讶", "疑惑", "无奈", "生气", "厌恶", "伤心", "羞耻", "自信", "认真", "调皮",
] as const;

export type Emotion = (typeof EMOTIONS)[number];

/** 立绘路径：/avatars/{{code}}/{{情绪}}.webp */
export function avatarUrl(code: string, emotion?: string): string {{
  const e = EMOTIONS.includes(emotion as Emotion) ? emotion : "正常";
  return `/avatars/${{code}}/${{e}}.webp`;
}}

export function findInterviewer(code: string): Interviewer {{
  return interviewers.find((p) => p.code === code) ?? interviewers[0];
}}
'''

out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(ts, encoding="utf-8")

print(f"{'code':10} {'name':10} 立绘缩放  偏移")
for r in rows:
    print(f"{r['code']:10} {r['name']:10} {r['scale']:>6}  ({r['offsetX']}, {r['offsetY']})")
print(f"\n→ {out.relative_to(ROOT)}  ({len(ts)} 字符)")
