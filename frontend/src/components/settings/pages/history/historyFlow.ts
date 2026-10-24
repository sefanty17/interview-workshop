/**
 * 历史对话流的纯逻辑（对齐上游 `SettingsHistory.vue` 的 `parseSegments` / `groupedHistory`）。
 *
 * <p>上游把这两段内联写在组件里；本项目抽成独立模块，好处是能用 node 直接跑断言
 * （见 `frontend/scripts/verify-history-flow.mjs`），不靠肉眼看页面。
 * 逻辑与逐行语义不变，属「实现方式差异」，不是功能偏差。
 *
 * <p>基准：`SlimeBoyOwO/LingChat@5a778fab`，`src/components/settings/pages/SettingsHistory.vue`。
 */

export interface Segment {
  type: "dialogue" | "action";
  text: string;
}

export interface LineEntry {
  segments: Segment[];
  /** TODO(批次8)：回溯定位用（对齐 `user_message_seq`） */
  userMessageSeq?: number;
  thinking?: string;
}

export interface HistoryBlock {
  displayName: string;
  isNarration: boolean;
  lines: LineEntry[];
  userMessageSeq?: number;
  /** 该块（一轮生成）的思考链，取块内最后一条非空值 */
  thinking?: string;
}

/** 旁白 / 系统：不冠名、不加书名号、整段斜体 */
export const NARRATION_NAMES = new Set(["", "旁白", "系统", "Narrator", "System"]);

/** 全角括号动作标记，prompt 里定死的格式，不许改成半角 */
export const ACTION_RE = /（[^）]*）/;

/** 每页显示的台词数量 */
export const PAGE_SIZE = 100;

/** 去掉结尾句号（旁白不剥） */
export function stripTrailPeriod(text: string): string {
  return text.replace(/[。]+$/, "");
}

/** 正文按 `（…）` 切段，`actionPart` 追加成一个 action 段。 */
export function parseSegments(
  raw: string,
  actionPart: string | undefined,
  isNarration: boolean,
): Segment[] {
  const segments: Segment[] = [];
  let remaining = raw;
  let match: RegExpExecArray | null;

  while ((match = ACTION_RE.exec(remaining)) !== null) {
    if (match.index > 0) {
      let text = remaining.substring(0, match.index);
      if (!isNarration) text = stripTrailPeriod(text);
      if (text.trim()) segments.push({ type: "dialogue", text });
    }
    remaining = remaining.substring(match.index + match[0].length);
  }

  remaining = remaining.trim();
  if (remaining) {
    if (!isNarration) remaining = stripTrailPeriod(remaining);
    segments.push({ type: "dialogue", text: remaining });
  }

  if (actionPart) {
    segments.push({ type: "action", text: actionPart });
  }

  return segments;
}

/** 一条后台台词在页面上的形状（只取分组要用的字段，避免耦合整个 DTO）。 */
export interface FlowMessage {
  role: string;
  content: string;
  action?: string | null;
  displayName?: string | null;
  thinking?: string | null;
  userMessageSeq?: number | null;
}

/**
 * 把一页台词合并成块：**连续同 `displayName` 且同 `isNarration` 的行合成一个块**。
 *
 * <p>块内 `thinking` 取最后一条非空值；`userMessageSeq` 只在块里还没值时补上
 * （对齐上游 `groupedHistory`）。
 */
export function groupLines(messages: FlowMessage[]): HistoryBlock[] {
  const blocks: HistoryBlock[] = [];

  for (const msg of messages) {
    if (!msg.content || msg.content.trim() === "") continue;

    const isNarration = NARRATION_NAMES.has(msg.displayName || "");

    const name =
      isNarration ? "" : msg.displayName || (msg.role === "user" ? "我" : "未知的声音");

    // 用户输入不做（…）动作切分，原样显示
    const segments =
      msg.role === "user"
        ? [{ type: "dialogue" as const, text: msg.content }]
        : parseSegments(msg.content, msg.action ?? undefined, isNarration);

    const entry: LineEntry = {
      segments,
      userMessageSeq: msg.userMessageSeq ?? undefined,
      thinking: msg.thinking ?? undefined,
    };

    const last = blocks.length > 0 ? blocks[blocks.length - 1] : null;
    if (last && last.displayName === name && last.isNarration === isNarration) {
      if (typeof entry.userMessageSeq === "number" && last.userMessageSeq === undefined) {
        last.userMessageSeq = entry.userMessageSeq;
      }
      if (entry.thinking) {
        last.thinking = entry.thinking;
      }
      last.lines.push(entry);
    } else {
      blocks.push({
        displayName: name,
        isNarration,
        lines: [entry],
        userMessageSeq: entry.userMessageSeq,
        thinking: entry.thinking,
      });
    }
  }

  return blocks;
}
