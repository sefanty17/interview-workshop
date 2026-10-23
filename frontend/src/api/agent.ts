/**
 * 剧本编辑器 AI 助手（Skill Agent）的后端接口（规格 E7b1 §3.1）。
 *
 * 原版是 Tauri 的 17 个 `editor_agent_*` invoke 命令（`api/services/agent.ts`），
 * 本项目换成 REST：`/api/editor/agent/**`（端点表见 `specs/E7a1-AI助手后端基座.md` §2）。
 * 一轮对话是 SSE（`POST /conversations/{id}/chat`），用 `fetch` + `ReadableStream` 读，
 * 不用 EventSource —— 它不支持 POST + body（同 `api/chat.ts` 的取舍）。
 *
 * 只做协议封装，不含业务逻辑；状态归约在 `store/agent.ts`。
 */

/** 所有端点的公共前缀（后端 `api/SkillAgentController.java` 的 @RequestMapping）。 */
const BASE = "/api/editor/agent";

// ============================================================
// DTO
// ============================================================

/** Agent 设置（沙箱目录为空表示默认 data/）。 */
export interface AgentSettings {
  providerId: string | null;
  sandboxDir: string | null;
  autoApproveCommands: boolean;
  allowAnyPath: boolean;
  maxToolRounds: number;
  systemPrompt: string | null;
  /** 思考模式覆盖；null 表示跟随模型 provider 默认（独立于主对话 LLM 设置）。 */
  enableThinking: boolean | null;
}

/** 发现的技能。 */
export interface SkillInfo {
  name: string;
  description: string;
  location: string;
  path: string;
}

/** 技能 SKILL.md 内容。 */
export interface SkillContent {
  name: string;
  baseDirectory: string;
  content: string;
}

/** 一个对话会话。 */
export interface ConversationInfo {
  id: number;
  title: string | null;
  /** 创建会话时打开的剧本 key（可为 null）。 */
  scriptKey: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 历史消息里的一条工具调用（库里是 JSON，后端反序列化成对象数组）。 */
export interface PersistedToolCall {
  id: string;
  type: string;
  function: { name: string; arguments: string };
}

/** 持久化消息（OpenAI 格式）。 */
export interface PersistedMessage {
  id: number;
  role: "user" | "assistant" | "tool" | "system";
  content: string | null;
  /** assistant 的思考链（仅展示，不参与 LLM 上下文；旧数据为 null）。 */
  reasoning: string | null;
  /** 产生该消息那一轮 LLM 调用的 token 用量（仅 assistant 消息有；旧数据为 null）。 */
  promptTokens: number | null;
  completionTokens: number | null;
  /** 输入中命中缓存（cache read）的 token 数（旧数据为 null）。 */
  cachedTokens: number | null;
  toolCalls: PersistedToolCall[] | null;
  toolCallId: string | null;
  createdAt: string;
}

/** 设置面板展示的默认目录。 */
export interface AgentDefaultDirs {
  dataDir: string;
  skillsDir: string;
  sandboxDir: string;
}

/** 剧本包 `.agent/` 下的一份流程产物（设计稿、任务队列、用户约束……）。 */
export interface AgentArtifact {
  name: string;
  content: string;
}

/** Token 用量。 */
export interface TokenUsage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  /** 输入中命中缓存（cache read）的 token 数；未上报时为 0。 */
  cached_tokens: number;
}

/**
 * 后端流式事件（`SkillAgentEvent.java` 的 serde tag="type"，snake_case）。
 *
 * 10 个变体，与 `references/lingchat/crates/.../skill_agent/events.rs` 一一对应。
 */
export type AgentEvent =
  | { type: "status"; content: string }
  | { type: "message_delta"; content: string }
  /** 一项的开始：这是一段的边界（一轮可能逐项做多件事），收到就要另起一段。 */
  | { type: "item_start"; index: number; total: number; title: string }
  | { type: "reasoning"; content: string }
  | {
      type: "tool_call";
      call_id: string;
      tool: string;
      args: Record<string, unknown>;
      raw_args: string;
    }
  | {
      type: "tool_result";
      call_id: string;
      tool: string;
      ok: boolean;
      output: string;
      error: string | null;
    }
  | {
      type: "pending_approval";
      request_id: string;
      tool: string;
      args: Record<string, unknown>;
    }
  | { type: "done"; final_text: string; usage: TokenUsage | null }
  | { type: "conversation_title"; title: string }
  | { type: "error"; message: string };

/** 已登记的 10 个事件名；解析到别的 `type` 说明两端契约漂移，出声而不是静默吞掉。 */
const KNOWN_EVENTS = new Set<string>([
  "status",
  "message_delta",
  "item_start",
  "reasoning",
  "tool_call",
  "tool_result",
  "pending_approval",
  "done",
  "conversation_title",
  "error",
]);

// ============================================================
// 内部：HTTP 小工具
// ============================================================

/** 发一个 JSON 请求并解析返回体；204 / 空 body 返回 undefined。 */
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, init);
  if (!response.ok) {
    throw new Error(`${init?.method ?? "GET"} ${path} 失败：HTTP ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

/** POST 一个 JSON body（body 可省略）。 */
function post<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  return http<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
}

/** 无返回体的命令式端点（delete / rename / stop / rewind / approval）。 */
async function command(path: string, method: string, body?: unknown): Promise<void> {
  await http<void>(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

// ============================================================
// 设置
// ============================================================

/** `GET /settings`。 */
export const getSettings = (): Promise<AgentSettings> => http<AgentSettings>("/settings");

/** `PUT /settings`：后端保存后回读一遍，前端拿返回体覆盖本地。 */
export const saveSettings = (settings: AgentSettings): Promise<AgentSettings> =>
  http<AgentSettings>("/settings", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(settings),
  });

/** `GET /default-dirs`（数据根 / 技能库 / 沙箱）。 */
export const getDefaultDirs = (): Promise<AgentDefaultDirs> =>
  http<AgentDefaultDirs>("/default-dirs");

// ============================================================
// 技能
// ============================================================

/** `GET /skills`：技能库里的全部技能（目录不存在时为空数组）。 */
export const listSkills = (): Promise<SkillInfo[]> => http<SkillInfo[]>("/skills");

/** `GET /skills/{name}`：单个技能的 SKILL.md 全文；不存在 404。 */
export const readSkill = (name: string): Promise<SkillContent> =>
  http<SkillContent>(`/skills/${encodeURIComponent(name)}`);

// ============================================================
// 会话
// ============================================================

/**
 * `POST /conversations`：新建会话。
 *
 * 原版只传 `scriptKey`（标题恒为 null，首轮回复后自动生成）；后端多收一个可选 `title`。
 */
export const createConversation = (
  scriptKey: string | null,
  title?: string,
): Promise<ConversationInfo> =>
  post<ConversationInfo>("/conversations", title === undefined ? { scriptKey } : { scriptKey, title });

/** `GET /conversations`：最近更新在前。 */
export const listConversations = (): Promise<ConversationInfo[]> =>
  http<ConversationInfo[]>("/conversations");

/** `DELETE /conversations/{id}`：连带删掉它的消息。 */
export const deleteConversation = (conversationId: number): Promise<void> =>
  command(`/conversations/${conversationId}`, "DELETE");

/** `PUT /conversations/{id}/title`：重命名会话（标题非空后不再自动生成）。 */
export const renameConversation = (conversationId: number, title: string): Promise<void> =>
  command(`/conversations/${conversationId}/title`, "PUT", { title });

/** `GET /conversations/{id}/messages`：按 id 升序。 */
export const getMessages = (conversationId: number): Promise<PersistedMessage[]> =>
  http<PersistedMessage[]>(`/conversations/${conversationId}/messages`);

/**
 * `POST /resolve-script-key`：会话归属的剧本 key。
 *
 * 后端包了一层 `{ scriptKey }`（裸 null 会是空 body，前端分不清「没绑到」和「请求失败」）。
 */
export async function resolveScriptKey(conversationId: number): Promise<string | null> {
  const view = await post<{ scriptKey: string | null }>("/resolve-script-key", { conversationId });
  return view.scriptKey ?? null;
}

/** `POST /conversations/{id}/clear`：清空消息（会话本身保留）。 */
export const clearConversation = (conversationId: number): Promise<void> =>
  command(`/conversations/${conversationId}/clear`, "POST");

/**
 * `GET /conversations/{id}/artifacts`：会话所属剧本包 `.agent/` 下的流程产物。
 *
 * 注意入参是**会话 id**（原版是 scriptKey，后端改成从会话反查剧本）。
 */
export const listArtifacts = (conversationId: number): Promise<AgentArtifact[]> =>
  http<AgentArtifact[]>(`/conversations/${conversationId}/artifacts`);

// ============================================================
// 对话
// ============================================================

/** 解析一个 SSE 块：`data: {...}`（Spring 的 SseEmitter 只写 data 行，不带 event 名）。 */
function parseFrame(raw: string): AgentEvent | null {
  const dataLines: string[] = [];
  for (const line of raw.split("\n")) {
    if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
  }
  if (!dataLines.length) return null;
  const text = dataLines.join("\n");
  try {
    return JSON.parse(text) as AgentEvent;
  } catch {
    return null;
  }
}

/**
 * 读 SSE 响应体，按 `\n\n` 切块并逐条 yield（照 `api/chat.ts` 的 parseSseStream）。
 *
 * 调用方需先做 `!response.ok || !response.body → throw`，这里只负责解析。
 */
async function* parseAgentSse(response: Response): AsyncGenerator<AgentEvent> {
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
        const event = parseFrame(raw);
        if (event) {
          if (!KNOWN_EVENTS.has(event.type)) {
            console.warn("[agent] 未登记的事件类型", event.type);
          }
          yield event;
        }
        boundary = buffer.indexOf("\n\n");
      }
    }
    const tail = parseFrame(buffer);
    if (tail) yield tail;
  } finally {
    reader.releaseLock();
  }
}

/**
 * 开始一轮对话，逐条回调 SSE 事件（`POST /conversations/{id}/chat`）。
 *
 * 与原版 `startAgentChat` 的差异：原版 invoke 的**返回值**是本次用户消息的 DB id
 * （用于回溯定位），SSE 响应体没法带这个值 —— store 在流结束后用 `getMessages`
 * 反查一次补齐 id（见 `store/agent.ts` 的 `sendMessage`）。
 *
 * `signal` 用于本地中断：abort 后 fetch 抛 AbortError，本函数直接向外冒泡。
 */
export async function startChat(
  conversationId: number,
  message: string,
  onEvent: (event: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`${BASE}/conversations/${conversationId}/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message }),
    signal,
  });
  if (!response.ok || !response.body) {
    throw new Error(`startChat 失败：HTTP ${response.status}`);
  }
  for await (const event of parseAgentSse(response)) onEvent(event);
}

/** `POST /conversations/{id}/stop`：停止这一轮生成。 */
export const stopChat = (conversationId: number): Promise<void> =>
  command(`/conversations/${conversationId}/stop`, "POST");

/** `POST /conversations/{id}/rewind`：删掉 id >= messageId 的消息（回溯到该消息发送前）。 */
export const rewind = (conversationId: number, messageId: number): Promise<void> =>
  command(`/conversations/${conversationId}/rewind`, "POST", { messageId });

/** `POST /approvals/{requestId}`：决断一条待审批的工具调用。 */
export const resolveApproval = (requestId: string, allowed: boolean): Promise<void> =>
  command(`/approvals/${encodeURIComponent(requestId)}`, "POST", { allowed });
