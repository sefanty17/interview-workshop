"""把 data/seed/scripts.json 转成 frontend/src/data/scripts.ts。

数据源与后端种子完全同源（interview_script / interview_chapter 两张表），
前端只做只读镜像，改剧本仍走后端接口。幂等，可反复跑。
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "data" / "seed" / "scripts.json"
OUT = ROOT / "frontend" / "src" / "data" / "scripts.ts"

HEADER = """// 由 scripts/gen_scripts_ts.py 从 data/seed/scripts.json 生成，不要手改。
// 结构与后端 interview_script / interview_chapter 两张表一一对应，
// 事件类型照搬 LingChat 剧本引擎（story_config.yaml + Chapters/*.yaml）。

export type EventType =
  | "ai_dialogue"
  | "input"
  | "narration"
  | "free_dialogue"
  | "modify_character"
  | "chapter_end";

export type EndType = "linear" | "branching" | "ai_judged";

export interface ScriptEvent {
  type: EventType;
  /** ai_dialogue / modify_character：角色代号，主面试官固定 MAIN */
  character?: string;
  /** ai_dialogue：给 LLM 的提示词 */
  prompt?: string;
  /** input：输入框占位提示（本项目扩展字段） */
  hint?: string;
  /** narration：旁白文本 */
  text?: string;
  /** modify_character：show_character / hide_character */
  action?: string;
  /** modify_character / background：时长（秒） */
  duration?: number;
  /** chapter_end：跳转方式 */
  end_type?: EndType;
  /** chapter_end + linear：下一章 code */
  next_chapter?: string;
  /** chapter_end + branching/ai_judged：候选分支，顺序即优先级。
   *  branching 认 condition / next / default；ai_judged 认 name / next / default。
   *  next 缺失时引擎会回落到「剧本结束」，所以编辑时必须能填。 */
  options?: { name?: string; next?: string; condition?: string; default?: boolean }[];
}

export interface InterviewChapter {
  code: string;
  name: string;
  order_num: number;
  events: ScriptEvent[];
}

export interface ScriptSettings {
  total_questions?: number;
  time_limit?: number;
  follow_up_depth?: number;
}

export interface InterviewScript {
  code: string;
  script_name: string;
  intro_chapter: string;
  description: string;
  recommand_start?: string;
  interviewer_code?: string;
  order_num: number;
  job_code?: string;
  level?: string;
  company_style?: string;
  script_settings?: ScriptSettings;
  chapters: InterviewChapter[];
}

"""


def main() -> None:
    scripts = json.loads(SRC.read_text(encoding="utf-8"))
    body = (
        "export const seedScripts: InterviewScript[] = "
        + json.dumps(scripts, ensure_ascii=False, indent=2)
        + ";\n\n"
        + 'export function findScript(code: string): InterviewScript | undefined {\n'
        + "  return seedScripts.find((s) => s.code === code);\n"
        + "}\n\n"
        + "/** 章节跳转表的唯一来源：每章最后那条 chapter_end */\n"
        + "export function nextChapterOf(ch: InterviewChapter): string | undefined {\n"
        + "  const end = ch.events.find((e) => e.type === \"chapter_end\");\n"
        + "  if (!end) return undefined;\n"
        + "  if (end.end_type === \"linear\") return end.next_chapter;\n"
        + "  return undefined;\n"
        + "}\n"
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(HEADER + body, encoding="utf-8")

    total_ch = sum(len(s["chapters"]) for s in scripts)
    total_ev = sum(len(c["events"]) for s in scripts for c in s["chapters"])
    print(f"OK -> {OUT.relative_to(ROOT)}")
    print(f"剧本 {len(scripts)} 个 / 章节 {total_ch} 个 / 事件 {total_ev} 个")


if __name__ == "__main__":
    main()
