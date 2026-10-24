/**
 * 对话 API（规格 B3-BE §4.1）。
 *
 * 只用 fetch：SSE 用 POST，EventSource 不支持 POST，所以自己按 `\n\n` 切块解析。
 * 后端走 vite 的 /api 代理，前端不接触 API Key。
 */

/** `ai:reply` 的 data，字段与后端 ReplyEvent 一一对应。 */
export interface ReplyEvent {
  type: string;
  isFinal: boolean;
  duration: number;
  message: string;
  originalMessage: string;
  emotion: string;
  originalTag: string;
  motionText?: string;
  character: string;
  displayName: string;
  displaySubtitle: string;
  userMessageSeq?: number;
  /** 说话角色：0 = 求职者、N = 面试官 id、null = 旁白（后端 ReplyEvent.senderRoleId） */
  senderRoleId?: number | string | null;
}

/** 一条 SSE 事件（event 名 + 已解析的 data）。 */
export interface StreamEvent {
  event: string;
  data: any;
}

export interface CreateSessionPayload {
  interviewerCode: string;
  jobId: string;
  /** 指定剧本编码（后端 CreateSessionRequest.scriptCode）；留空则建纯自由会话，要演剧本再调 startScript */
  scriptCode?: string;
}

export interface CreateSessionResult {
  sessionId: number;
  interviewer: { code: string; name: string; displayRole: string; emotion: string };
  opening: { message: string; emotion: string; seq: number };
  systemPromptLength: number;
}

export interface SessionMessage {
  seq: number;
  role: string;
  content: string;
  emotion: string | null;
  action: string | null;
  originalTag: string | null;
  /** 说话角色：0 = 玩家、N = 角色 id、null = 旁白 / system（对齐 line.sender_role_id） */
  senderRoleId: number | null;
  /** 本轮最终句的思考链；其余行 null（对齐 line.thinking） */
  thinking: string | null;
  /** 玩家说的是哪个身份（对齐 line.identity_id），非玩家行 null */
  identityId: string | null;
  /** 历史里显示成谁说的（对齐 line.display_name）；玩家行必填，旁白 / system 行 null */
  displayName: string | null;
  /** 本场第几条用户消息（1-based，只对 role=user 有值）；批次 8 的回溯按钮用它定位 */
  userMessageSeq: number | null;
}

/** `GET /api/sessions/{id}` 的返回体（规格 S4 §改动1-4）。
 *
 *  `messages` 不在此规格的字段清单里，但后端 `SessionDetailView` 确实带它，
 *  且规格「改动 4 · 3」要用 `detail.messages` 判断场次是否全新，故补上。 */
export interface SessionDetail {
  id: number;
  title: string;
  status: string;
  score: number | null;
  jobCode: string;
  currentChapter: string;
  eventSequence: number;
  /** 已绑定的剧本编码；null = 自由对话场次（没调过 /script/start 或已 /script/stop） */
  scriptCode: string | null;
  interviewerCode: string;
  messages: SessionMessage[];
}

/** `script:narration` 的 data（字段名照抄后端 SseEventSink，不许改）。 */
export interface ScriptNarrationEvent {
  text: string;
}

/** `script:input` 的 data。 */
export interface ScriptInputEvent {
  hint: string;
}

/** `script:modify-character` 的 data（ModifyCharacterPayload，字段名照后端 record）。 */
export interface ScriptModifyCharacterEvent {
  /** 说话角色标识；MAIN 已被后端解析成本场面试官 code */
  characterId?: string | null;
  emotion?: string | null;
  /** show_character / hide_character，缺省不改舞台 */
  action?: string | null;
  /** 变更之后的在场角色（多槽位渲染的唯一真相源） */
  presentRoles?: string[] | null;
  /** 转场时长（秒）；后端当前不下发，缺省按原版 0.5s */
  duration?: number | null;
}

/** `script:chapter-change` 的 data。 */
export interface ScriptChapterChangeEvent {
  code: string;
  name: string;
}

/** `script:progress` 的 data。 */
export interface ScriptProgressEvent {
  chapter: string;
  eventIndex: number;
  lineCount: number;
  vars: Record<string, unknown>;
}

// ─────────────── 剧本舞台事件（规格 B7 任务二） ───────────────
// 事件名照抄 LingChat responses.rs:14-28 的 event_names；payload 全部 serde
// `rename_all = "camelCase"`，字段名以 responses.rs 各 struct 为唯一真相源。

/** 本批新增的 8 个剧本舞台事件名（顺序与 spec 一致，一个都不许改）。 */
export const SCRIPT_STAGE_EVENTS = [
  "script:player",
  "script:background",
  "script:background-effect",
  "script:music",
  "script:sound",
  "script:ambient",
  "script:present-pic",
  "script:choice",
] as const;

/** 8 个舞台事件名之一。 */
export type ScriptStageEvent = (typeof SCRIPT_STAGE_EVENTS)[number];

/** 已登记的剧本事件全集（含 S4 那批），用于 parseSseStream 的漂移告警。 */
const KNOWN_SCRIPT_EVENTS = new Set<string>([
  ...SCRIPT_STAGE_EVENTS,
  "script:narration",
  "script:input",
  "script:modify-character",
  "script:chapter-change",
  "script:progress",
  "script:end",
]);

/** `script:player` 的 data（PlayerPayload）。 */
export interface ScriptPlayerPayload {
  text: string;
  displayName?: string;
  duration?: number;
}

/** `script:background` 的 data（BackgroundPayload）。 */
export interface ScriptBackgroundPayload {
  imagePath: string;
  /** 转场时长（秒） */
  transition: number;
  duration?: number;
}

/** `script:background-effect` 的 data（BackgroundEffectPayload）。 */
export interface ScriptBackgroundEffectPayload {
  effect: string;
  duration?: number;
}

/** `script:music` 的 data（MusicPayload）。 */
export interface ScriptMusicPayload {
  musicPath: string;
  /** 播放速度倍率（1.0 原速），缺省按 1.0 处理 */
  playbackSpeed?: number;
  duration?: number;
}

/** `script:sound` 的 data（SoundPayload）。 */
export interface ScriptSoundPayload {
  soundPath: string;
  duration?: number;
}

/** `script:ambient` 的 data（AmbientPayload）。 */
export interface ScriptAmbientPayload {
  ambientPath: string;
  /** 单轨音量 0-100，缺省 100 */
  volume?: number;
  /** 是否循环，缺省 true（后端字段名就叫 loop） */
  loop?: boolean;
  /** true = 淡出停止该轨（ambientPath 为空则全部停止） */
  stop?: boolean;
  /** 是否淡入淡出，缺省 true */
  fade?: boolean;
  duration?: number;
}

/** `script:present-pic` 的 data（PresentPicPayload）。 */
export interface ScriptPresentPicPayload {
  imagePath: string;
  /** 缩放倍率，缺省 1 */
  scale?: number;
  duration?: number;
}

/** `script:choice` 的单个选项（ChoiceItem）：条件不满足时 disabled + reason=lock_hint。 */
export interface ScriptChoiceItem {
  text: string;
  disabled: boolean;
  reason?: string;
}

/** `script:choice` 的 data（ChoicePayload）。 */
export interface ScriptChoicePayload {
  choices: ScriptChoiceItem[];
  /** true 时玩家可在输入框自由作答（后端会把文本转投给 choice 匹配） */
  allowFree: boolean;
  duration?: number;
}

/** 后端是否活着（规格 §4.2 的分支条件）；1.5s 内没响应就当没起。 */
export async function isBackendUp(timeoutMs = 1500): Promise<boolean> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch("/api/health", { signal: controller.signal });
    return response.ok;
  } catch {
    return false;
  } finally {
    window.clearTimeout(timer);
  }
}

/** 建会话：system 串在这个接口里拼一次并落库。 */
export async function createSession(payload: CreateSessionPayload): Promise<CreateSessionResult> {
  const response = await fetch("/api/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    throw new Error(`createSession 失败：HTTP ${response.status}`);
  }
  return (await response.json()) as CreateSessionResult;
}

/** 拉历史消息（刷新恢复用）。 */
export async function fetchMessages(sessionId: number, afterSeq = 0): Promise<SessionMessage[]> {
  const response = await fetch(`/api/sessions/${sessionId}/messages?afterSeq=${afterSeq}`);
  if (!response.ok) {
    throw new Error(`fetchMessages 失败：HTTP ${response.status}`);
  }
  return (await response.json()) as SessionMessage[];
}

/** 拉场次详情（剧本进度 / 已落库台词，刷新续跑用）。 */
export async function fetchSessionDetail(sessionId: number): Promise<SessionDetail> {
  const response = await fetch(`/api/sessions/${sessionId}`);
  if (!response.ok) {
    throw new Error(`fetchSessionDetail 失败：HTTP ${response.status}`);
  }
  return (await response.json()) as SessionDetail;
}

/** `POST /api/sessions/{id}/finish` 的返回体（规格 S5 改动 4）。 */
export interface FinishResult {
  id: number;
  status: string;
  score: number | null;
}

/** `GET /api/sessions` 的列表项（规格 S5 改动 4）。 */
export interface SessionSummary {
  id: number;
  title: string;
  status: string;
  score: number | null;
  jobCode: string;
  scriptCode: string;
  interviewerCode: string;
  startedAt: string | null;
  endedAt: string | null;
}

/** 结束场次并取回后端算出的总分（规格 S5 改动 4）。 */
export async function finishSession(sessionId: number): Promise<FinishResult> {
  const response = await fetch(`/api/sessions/${sessionId}/finish`, { method: "POST" });
  if (!response.ok) {
    throw new Error(`finishSession 失败：HTTP ${response.status}`);
  }
  return (await response.json()) as FinishResult;
}

/** 拉场次列表；`status` 为空则不过滤（规格 S5 改动 4）。 */
export async function fetchSessions(status?: string): Promise<SessionSummary[]> {
  const response = await fetch(`/api/sessions${status ? `?status=${status}` : ""}`);
  if (!response.ok) {
    throw new Error(`fetchSessions 失败：HTTP ${response.status}`);
  }
  return (await response.json()) as SessionSummary[];
}

/** 解析一个 SSE 块：`event: xxx\ndata: {...}`。 */
function parseEvent(raw: string): StreamEvent | null {
  let event = "message";
  const dataLines: string[] = [];
  for (const line of raw.split("\n")) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }
  if (!dataLines.length) return null;
  const text = dataLines.join("\n");
  try {
    return { event, data: JSON.parse(text) };
  } catch {
    return { event, data: text };
  }
}

/**
 * 读 SSE 响应体，按 `\n\n` 切块并逐条 yield。
 *
 * 调用方需先做 `!response.ok || !response.body → throw`，这里只负责解析。
 */
async function* parseSseStream(response: Response): AsyncGenerator<StreamEvent> {
  if (!response.body) return;

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      // SSE 允许 \r\n；payload 里的 \r 一定被转义，直接抹掉最省事
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, "");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const raw = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const parsed = parseEvent(raw);
        if (parsed) {
          // 剧本事件在此登记：SCRIPT_STAGE_EVENTS 是 B7 任务二的 8 个舞台事件，
          // 解析层只认名不改 data（字段见各 Payload 接口），逐条原样透传给 store。
          // 名字对不上说明两端契约漂移，出声而不是静默丢掉。
          if (parsed.event.startsWith("script:") && !KNOWN_SCRIPT_EVENTS.has(parsed.event)) {
            console.warn("[chat] 未登记的剧本事件", parsed.event);
          }
          yield parsed;
        }
        boundary = buffer.indexOf("\n\n");
      }
    }
    const tail = parseEvent(buffer);
    if (tail) yield tail;
  } finally {
    reader.releaseLock();
  }
}

/**
 * 发一条消息，逐条 yield SSE 事件（ai:thinking / ai:reply / status:reset / ai:error）。
 *
 * 这是**自由对话**入口（原版 `api/chat.rs` 的 send_chat_message）：只跟模型聊，
 * 不推进剧本。剧本的推进与输入走下面的 startScript / submitScriptInput。
 *
 * 注意：generator 在返回前一直持有连接，调用方 `for await` 读完即结束。
 */
export async function* sendMessage(sessionId: number, text: string): AsyncGenerator<StreamEvent> {
  const response = await fetch(`/api/sessions/${sessionId}/messages`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (!response.ok || !response.body) {
    throw new Error(`sendMessage 失败：HTTP ${response.status}`);
  }

  yield* parseSseStream(response);
}

/**
 * 开演剧本，逐条 yield SSE 事件（原版 `api/script.rs` 的 start_script）。
 *
 * 后端先把场次绑到该剧本、进度归零，再从章节首事件跑到第一个阻塞点。
 * 事件序（实测）：script:chapter-change → script:progress → script:narration →
 * script:progress → ai:thinking → ai:reply×N → status:reset → script:progress → script:input。
 *
 * `fromChapter` 有值 = 从该章开演（原版 `editor_start_preview` 的 `from_chapter` 同语义，
 * 后端 ScriptStartRequest.fromChapter）；不传则从剧本的 intro_chapter 开始。
 */
export async function* startScript(
  sessionId: number,
  scriptCode: string,
  fromChapter?: string,
): AsyncGenerator<StreamEvent> {
  const body: { scriptCode: string; fromChapter?: string } = { scriptCode };
  // 空值不下发该键：后端把 null/空串都当「从 intro_chapter 开始」，但别让契约多出一种写法
  if (fromChapter) body.fromChapter = fromChapter;
  const response = await fetch(`/api/sessions/${sessionId}/script/start`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok || !response.body) {
    throw new Error(`startScript 失败：HTTP ${response.status}`);
  }

  yield* parseSseStream(response);
}

/** 收演剧本、回到自由对话（解绑 script_id、清空进度与变量）；没有事件流。 */
export async function stopScript(sessionId: number): Promise<void> {
  const response = await fetch(`/api/sessions/${sessionId}/script/stop`, { method: "POST" });
  if (!response.ok) {
    throw new Error(`stopScript 失败：HTTP ${response.status}`);
  }
}

/**
 * 剧本输入框提交（原版 script_submit_input）。
 *
 * 后端只在「确实有事件在等输入」时接受；否则回一个 `ai:error`（不落用户台词、不推进进度）。
 */
export async function* submitScriptInput(
  sessionId: number,
  text: string,
): AsyncGenerator<StreamEvent> {
  const response = await fetch(`/api/sessions/${sessionId}/script/input`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
  });
  if (!response.ok || !response.body) {
    throw new Error(`submitScriptInput 失败：HTTP ${response.status}`);
  }

  yield* parseSseStream(response);
}

/** 选项点击（原版 script_submit_choice）：强制按选项文本匹配，不受 allowFree 限制。 */
export async function* submitScriptChoice(
  sessionId: number,
  choice: string,
): AsyncGenerator<StreamEvent> {
  const response = await fetch(`/api/sessions/${sessionId}/script/choice`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ choice }),
  });
  if (!response.ok || !response.body) {
    throw new Error(`submitScriptChoice 失败：HTTP ${response.status}`);
  }

  yield* parseSseStream(response);
}
