/**
 * 剧本编辑器 AI 助手（Skill Agent）状态中枢（规格 E7b1 §3.2）。
 *
 * 原版是 Pinia 的 `defineStore("agent", ...)`，拆在
 * `stores/modules/agent/{state,getters,actions,index}.ts` 四个文件里；本项目没有 Pinia：
 * 照 `store/scriptEditor.ts` 的写法合成一个模块级 `reactive` 单例 + 导出 action 函数 +
 * 导出 computed。state 字段名、round 分段规则、`sealed` 语义全部照搬，注释里的真机坑一并保留。
 *
 * 对话与设置在 DB / 后端持久化，前端只做会话内缓存，不进 localStorage。
 * 本批（E7b1）只交付地基，不接线到页面（接线是 E7b2）。
 */

import { computed, reactive } from "vue";
import {
  clearConversation as apiClearConversation,
  createConversation as apiCreateConversation,
  deleteConversation as apiDeleteConversation,
  getDefaultDirs,
  getMessages,
  getSettings,
  listConversations,
  listSkills,
  renameConversation as apiRenameConversation,
  resolveApproval as apiResolveApproval,
  resolveScriptKey as apiResolveScriptKey,
  rewind as apiRewind,
  saveSettings as apiSaveSettings,
  startChat,
  stopChat as apiStopChat,
  type AgentDefaultDirs,
  type AgentEvent,
  type AgentSettings,
  type ConversationInfo,
  type PersistedMessage,
  type SkillInfo,
  type TokenUsage,
} from "../api/agent";
import { notify } from "./appStore";
import { scriptKey } from "./scriptEditor";

// ============================================================
// 类型（原版 state.ts 逐字搬）
// ============================================================

/** 工具调用状态机。 */
export type ToolStatus = "running" | "pending" | "done" | "error" | "denied";

export interface ToolRun {
  /** 后端生成的 call id，用于匹配 tool_result。 */
  callId: string;
  tool: string;
  args: Record<string, unknown>;
  status: ToolStatus;
  output?: string;
  requestId?: string;
  /** LLM 返回的原始参数 JSON（可能被截断/非法）。 */
  rawArgs?: string;
}

/** 一条 assistant 回复的片段：思考链 + 流式文本 + 随后的工具调用。 */
export interface ChatRound {
  /** 回复正文（工具调用前的叙述也在这里）。 */
  content: string;
  /** 思考链（thinking 模式开启时才有；已持久化，旧数据缺失时为空）。 */
  reasoning?: string;
  toolRuns: ToolRun[];
  /**
   * 已封口：本段是**一项的产出**（或它的标题），后面的工具调用不许再挂进来。
   * 不封口的话，下一项的第一次工具调用会把这一项的正文变成"工具轮"，
   * 正文就被折进折叠的思考块里看不见了（真机：一轮 4 项只看得到最后一项）。
   */
  sealed?: boolean;
}

export interface ChatItem {
  id: string;
  role: "user" | "assistant";
  content: string;
  rounds: ChatRound[];
  streaming: boolean;
  status?: string;
  error?: string;
}

/** 待审批的一条工具调用（`pendingApprovals` 派生用）。 */
export interface PendingApproval {
  requestId: string;
  tool: string;
  args: Record<string, unknown>;
}

/** 设置默认值；`maxToolRounds: -1` = 无上限，`enableThinking: null` = 跟随 provider。 */
export const emptySettings = (): AgentSettings => ({
  providerId: null,
  sandboxDir: null,
  autoApproveCommands: false,
  allowAnyPath: false,
  maxToolRounds: -1, // -1 = 无上限
  systemPrompt: null,
  enableThinking: null, // null = 跟随模型 provider 默认
});

// ============================================================
// state（原版 state.ts 的 15 个 ref → 一个 reactive 单例）
// ============================================================

export const store = reactive({
  conversations: [] as ConversationInfo[],
  currentId: null as number | null,
  items: [] as ChatItem[],
  streaming: false,
  sending: false,
  status: "",
  /** 每次事件自增，供视图触发滚动。 */
  version: 0,
  lastUsage: null as TokenUsage | null,
  totalTokens: 0,
  /** 累计输入 token（会话内累计，切会话从历史恢复，与 totalTokens 同语义）。 */
  totalPromptTokens: 0,
  /** 累计输出 token。 */
  totalCompletionTokens: 0,
  /** 累计缓存命中 token（输入中命中 cache read 的部分）。 */
  totalCachedTokens: 0,
  settings: emptySettings() as AgentSettings,
  skills: [] as SkillInfo[],
  defaultDirs: null as AgentDefaultDirs | null,
  loading: false,
});

// ============================================================
// getters（原版 getters.ts，改成独立导出的 computed）
// ============================================================

export const hasContent = computed(() => store.items.length > 0);

export const currentConversation = computed<ConversationInfo | null>(
  () => store.conversations.find((c) => c.id === store.currentId) ?? null,
);

/**
 * 当前消息列表里所有「等待审批」的工具调用（规格 §3.2 的「待审批」state）。
 *
 * 原版把待审批挂在 `ToolRun.status === "pending" + requestId` 上，没有单独的表；
 * 这里不另存一份（会有两份真相），按 status 派生。
 */
export const pendingApprovals = computed<PendingApproval[]>(() => {
  const out: PendingApproval[] = [];
  for (const item of store.items) {
    for (const round of item.rounds) {
      for (const run of round.toolRuns) {
        if (run.status === "pending" && run.requestId) {
          out.push({ requestId: run.requestId, tool: run.tool, args: run.args });
        }
      }
    }
  }
  return out;
});

// ============================================================
// 模块级运行态（不是渲染数据，不进 reactive）
// ============================================================

let idCounter = 0;
const nextId = () => `m-${Date.now()}-${++idCounter}`;

/** 当前正在生成的 assistant 消息 id；跨 turn 复位。 */
let activeAssistantId: string | null = null;
/**
 * 本轮流式所属的会话 id。用户切到别的会话时，事件仍属于这个会话 ——
 * 视图只在「显示的就是它」时才更新，避免把内容画到别的会话上。
 */
let activeConvId: number | null = null;
/** 当前 turn 的本地中断句柄；turn 结束后置空。 */
let abortController: AbortController | null = null;
/**
 * 本轮已结束标志：finish/finishWithError/cancel 后置 true，handleEvent 直接忽略
 * 后续迟到事件（后端 abort 前已推入连接的事件）。不解除订阅是为了让迟到的
 * done/error 也走守卫而不是被当成异常。
 */
let finished = false;

const safeParse = (s: string): Record<string, unknown> => {
  try {
    return JSON.parse(s) as Record<string, unknown>;
  } catch {
    return {};
  }
};

const itemText = (m: ChatItem): string =>
  m.role === "user" ? m.content : m.rounds.map((r) => r.content).join("");

// ============================================================
// 会话
// ============================================================

/** 进入面板时初始化：拉设置/技能/会话，自动建会话并绑定当前剧本 key。 */
export async function initForEditor(): Promise<void> {
  store.loading = true;
  try {
    store.settings = await getSettings();
    store.skills = await listSkills();
    store.defaultDirs = await getDefaultDirs();
    store.conversations = await listConversations();
    // 流式进行中：面板重挂载（切标签页回来）时不能走 switchConversation——
    // 它会中断当前流，且 DB 里这一轮还没落库（后端轮结束时才写入），
    // 重建 items 会丢掉已流出的思考/输出。这里保留进行中的 store 状态，
    // 流式事件继续写入 items，面板直接渲染；流结束后自然落库，下次进入正常加载。
    if (store.streaming && store.currentId != null) return;
    if (store.conversations.length === 0) {
      await createConversation();
    } else {
      // 自动切到最近更新的会话
      await switchConversation(store.conversations[0].id);
    }
  } finally {
    store.loading = false;
  }
}

export async function createConversation(): Promise<ConversationInfo> {
  // 不打断正在跑的会话（运行时 UI 已禁用「新建会话」入口）
  const key = scriptKey.value || null;
  const conv = await apiCreateConversation(key);
  store.conversations.unshift(conv);
  await switchConversation(conv.id);
  return conv;
}

export async function switchConversation(id: number): Promise<void> {
  // 切会话不打断正在跑的任务：事件按所属会话路由（见 handleEvent）。
  store.currentId = id;
  const msgs = await getMessages(id);
  store.items = rebuildItems(msgs);
  restoreUsage(msgs);
  // 切回正在跑的会话：补一条流式项重新挂上，否则后续事件会被静默丢弃。
  if (store.streaming && id === activeConvId) {
    activeAssistantId = nextId();
    store.items.push({
      id: activeAssistantId,
      role: "assistant",
      content: "",
      rounds: [],
      streaming: true,
    });
  }
  store.status = "";
  store.version++;
}

export async function deleteConversation(id: number): Promise<void> {
  // 运行中不删（UI 已禁用入口）
  if (store.streaming) return;
  await apiDeleteConversation(id);
  store.conversations = store.conversations.filter((c) => c.id !== id);
  if (store.currentId === id) {
    store.currentId = null;
    store.items = [];
    if (store.conversations.length > 0) {
      await switchConversation(store.conversations[0].id);
    } else {
      await createConversation();
    }
  }
}

/** 重命名会话：写库后更新本地列表项标题。 */
export async function renameConversation(id: number, title: string): Promise<void> {
  const trimmed = title.trim();
  if (!trimmed) return;
  await apiRenameConversation(id, trimmed);
  const conv = store.conversations.find((c) => c.id === id);
  if (conv) conv.title = trimmed;
}

export async function clearConversation(): Promise<void> {
  if (store.currentId == null) return;
  // 运行中不清空（UI 已禁用入口）
  if (store.streaming) return;
  await apiClearConversation(store.currentId);
  store.items = [];
  store.totalTokens = 0;
  store.totalPromptTokens = 0;
  store.totalCompletionTokens = 0;
  store.totalCachedTokens = 0;
  store.lastUsage = null;
  store.version++;
}

/**
 * 回溯（撤回重发）：删除该消息及其后所有消息，把对话回退到该消息发送前。
 * 正在生成时先停止；删除后重新加载历史以恢复 items 与用量统计。
 */
export async function rewindMessage(item: ChatItem): Promise<void> {
  if (store.currentId == null) return;
  const dbId = Number(item.id.replace(/^p-/, ""));
  if (Number.isNaN(dbId)) return;
  // 运行中不回溯（UI 已禁用入口）
  if (store.streaming) return;
  await apiRewind(store.currentId, dbId);
  const msgs = await getMessages(store.currentId);
  store.items = rebuildItems(msgs);
  restoreUsage(msgs);
  store.version++;
}

// ============================================================
// 对话
// ============================================================

export async function sendMessage(text: string): Promise<void> {
  const content = text.trim();
  if (!content || store.streaming || store.currentId == null) return;

  const userItem: ChatItem = {
    id: nextId(),
    role: "user",
    content,
    rounds: [],
    streaming: false,
  };
  store.items.push(userItem);
  activeAssistantId = nextId();
  store.items.push({
    id: activeAssistantId,
    role: "assistant",
    content: "",
    rounds: [],
    streaming: true,
  });

  store.streaming = true;
  store.sending = true;
  store.status = "思考中…";
  store.version++;
  finished = false;

  // 记下本轮所属会话
  const ownerId = store.currentId;
  activeConvId = ownerId;
  abortController = new AbortController();
  const signal = abortController.signal;

  try {
    await startChat(ownerId, content, (event) => handleEvent(event, ownerId), signal);
  } catch (err) {
    // 本地 abort 是 cancel() 的正常收尾路径，不能再盖一个错误上去
    if (!finished) finishWithError(String(err));
  } finally {
    // 连接被对端直接掐断（没收到 done）时也要把界面收回可发送状态
    if (!finished) finish(activeAssistantId);
  }

  // 原版用 start_chat 的返回值拿用户消息的 DB id；SSE 响应体带不了，
  // 这里在流结束后反查一次，把临时 id 换成 `p-<id>`，回溯删除才能定位到这条。
  try {
    const dbId = await findUserMessageId(ownerId, content);
    if (dbId != null) userItem.id = `p-${dbId}`;
  } catch (err) {
    console.warn("[Agent] 反查用户消息 id 失败（只影响回溯）:", err);
  }
}

/** 从历史消息里找最近一条内容相同的用户消息，返回它的 DB id。 */
async function findUserMessageId(conversationId: number, content: string): Promise<number | null> {
  const msgs = await getMessages(conversationId);
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if (m.role === "user" && (m.content ?? "").trim() === content) return m.id;
  }
  return null;
}

function handleEvent(event: AgentEvent, ownerId: number | null): void {
  // 本轮已结束（停止/完成/出错）后忽略迟到事件，防止停止后界面还在被写入。
  // conversation_title 例外：它由后端后台任务在 Done 之后才推送（会话自动命名），
  // 与流式内容无关，必须放行否则列表永远刷新不到新标题。
  if (finished && event.type !== "conversation_title") return;

  // 事件属于 ownerId 的会话；已切走时只收尾全局状态，不写当前视图。
  if (ownerId == null || store.currentId !== ownerId) {
    switch (event.type) {
      case "done":
        finish(null, event.final_text || undefined, event.usage ?? null);
        break;
      case "error":
        finishWithError(event.message);
        notify("AI 助手出错：" + event.message);
        break;
      case "conversation_title":
        applyTitle(ownerId, event.title);
        break;
      default:
        break;
    }
    return;
  }

  const msg = currentAssistant();
  switch (event.type) {
    case "status":
      store.status = event.content;
      if (msg) msg.status = event.content;
      break;
    case "item_start": {
      if (!msg) break;
      // 一项的开始 = 一段的边界：把上一段封口（后面的工具调用不许再挂进它），
      // 再新起一段承载这一项的标题与正文。不封口的话，前一项的正文会被
      // 这一项的工具调用卷进"工具轮"，折叠起来就看不见了。
      const prev = msg.rounds[msg.rounds.length - 1];
      if (prev) prev.sealed = true;
      msg.rounds.push({ content: event.title, toolRuns: [], sealed: true });
      break;
    }
    case "message_delta": {
      if (!msg) break;
      const last = msg.rounds[msg.rounds.length - 1];
      if (last && last.toolRuns.length === 0) {
        last.content += event.content;
      } else {
        // 上一段以工具调用结尾 → 新开一段
        msg.rounds.push({ content: event.content, toolRuns: [] });
      }
      break;
    }
    case "reasoning": {
      // 思考链：累积到当前轮；若上一轮以工具调用结尾则新开一轮承载思考。
      if (!msg) break;
      const last = msg.rounds[msg.rounds.length - 1];
      if (last && last.toolRuns.length === 0) {
        last.reasoning = (last.reasoning ?? "") + event.content;
      } else {
        msg.rounds.push({ content: "", reasoning: event.content, toolRuns: [] });
      }
      break;
    }
    case "tool_call": {
      if (!msg) break;
      let round = msg.rounds[msg.rounds.length - 1];
      // 上一段已封口（一项做完了 / 那是标题段）→ 工具另起一段，别把正文卷进"工具轮"
      if (!round || round.sealed) {
        round = { content: "", toolRuns: [] };
        msg.rounds.push(round);
      }
      round.toolRuns.push({
        callId: event.call_id,
        tool: event.tool,
        args: event.args,
        status: "running",
        rawArgs: event.raw_args,
      });
      store.status = `正在调用工具: ${event.tool}`;
      break;
    }
    case "pending_approval": {
      store.status = "等待你的审批…";
      if (!msg) break;
      // 审批紧随对应 tool_call 到达：找最后一条该工具的 running run
      let run: ToolRun | undefined;
      for (let i = msg.rounds.length - 1; i >= 0 && !run; i--) {
        run = msg.rounds[i].toolRuns.find((r) => r.tool === event.tool && r.status === "running");
      }
      if (run) {
        run.status = "pending";
        run.requestId = event.request_id;
      } else {
        let round = msg.rounds[msg.rounds.length - 1];
        // 同上：封口的段（一项做完了）不再接新工具
        if (!round || round.sealed) {
          round = { content: "", toolRuns: [] };
          msg.rounds.push(round);
        }
        round.toolRuns.push({
          callId: `approval-${event.request_id}`,
          tool: event.tool,
          args: event.args,
          status: "pending",
          requestId: event.request_id,
        });
      }
      break;
    }
    case "tool_result": {
      const run = msg ? findRun(msg, event.call_id) : undefined;
      if (run) {
        run.status =
          !event.ok && event.output.includes("已拒绝") ? "denied" : event.ok ? "done" : "error";
        run.output = event.output;
      }
      store.status = "";
      break;
    }
    case "done":
      finish(activeAssistantId, event.final_text || undefined, event.usage ?? null);
      break;
    case "conversation_title":
      // 后端首轮自动生成标题后推送，刷新侧栏列表；迟到事件已被 finished 守卫丢弃
      applyTitle(ownerId, event.title);
      break;
    case "error":
      finishWithError(event.message);
      break;
  }
  store.version++;
}

/** 把标题写到指定会话（后台命名任务可能在 Done 之后、用户已切走时才推）。 */
function applyTitle(convId: number | null, title: string): void {
  const conv = store.conversations.find((c) => c.id === convId);
  if (conv) conv.title = title;
}

/**
 * 重拉会话列表：一轮结束后后端可能刚补绑了剧本 key（老会话兜底）或自动命名了标题。
 * 本地已知的标题不被后端的空值覆盖 —— 自动命名任务与本次刷新存在竞态。
 */
export async function refreshConversations(): Promise<void> {
  if (store.streaming) return;
  try {
    const known = new Map(store.conversations.map((c) => [c.id, c.title]));
    const list = await listConversations();
    store.conversations = list.map((c) => ({
      ...c,
      title: c.title ?? known.get(c.id) ?? null,
    }));
  } catch (err) {
    console.warn("[Agent] 刷新会话列表失败:", err);
  }
}

/** 会话归属的剧本 key（后端会从历史写入路径反推），供只读章节预览使用。 */
export function resolveScriptKey(conversationId: number): Promise<string | null> {
  return apiResolveScriptKey(conversationId);
}

function currentAssistant(): ChatItem | undefined {
  return store.items.find((m) => m.id === activeAssistantId);
}

function findRun(msg: ChatItem, callId: string): ToolRun | undefined {
  for (const round of msg.rounds) {
    const run = round.toolRuns.find((r) => r.callId === callId);
    if (run) return run;
  }
  return undefined;
}

function finish(assistantId: string | null, finalText?: string, usage?: TokenUsage | null): void {
  finished = true;
  const msg = store.items.find((m) => m.id === assistantId);
  if (msg) {
    msg.streaming = false;
    // 只有完全没流式过时才用最终文本填段
    if (finalText && finalText.length > 0 && itemText(msg).length === 0) {
      if (msg.rounds.length === 0) msg.rounds.push({ content: "", toolRuns: [] });
      msg.rounds[msg.rounds.length - 1].content = finalText;
    }
  }
  if (usage) {
    store.lastUsage = usage;
    store.totalTokens += usage.total_tokens;
    store.totalPromptTokens += usage.prompt_tokens;
    store.totalCompletionTokens += usage.completion_tokens;
    store.totalCachedTokens += usage.cached_tokens;
  }
  store.streaming = false;
  store.sending = false;
  store.status = "";
  activeAssistantId = null;
  activeConvId = null;
  abortController = null;
  void refreshConversations();
  store.version++;
}

function finishWithError(message: string): void {
  finished = true;
  const msg = currentAssistant();
  if (msg) {
    msg.streaming = false;
    msg.error = message;
  }
  store.streaming = false;
  store.sending = false;
  store.status = "";
  activeAssistantId = null;
  activeConvId = null;
  abortController = null;
  store.version++;
}

/**
 * 停止这一轮生成（对应原版 `cancel()`）。
 *
 * 与原版的差异：原版只发 `editor_agent_stop_chat`，通道由后端关闭；
 * 本项目还要 abort 本地的 fetch 流，否则连接会挂到后端 complete 才断。
 */
export async function cancel(): Promise<void> {
  if (!store.streaming) return;
  // 先置结束标志再请求停止：abort 前已收到的迟到事件直接被守卫丢弃
  finished = true;
  const ownerId = store.currentId;
  if (ownerId != null) {
    try {
      await apiStopChat(ownerId);
    } catch (err) {
      // 停止请求失败不能阻塞前端收尾（界面必须立刻回到可发送状态）
      console.warn("[Agent] 停止请求失败:", err);
    }
  }
  abortController?.abort();
  finish(activeAssistantId);
}

export async function resolveApproval(requestId: string, allowed: boolean): Promise<void> {
  await apiResolveApproval(requestId, allowed);
  const msg = currentAssistant();
  const run = msg?.rounds.flatMap((r) => r.toolRuns).find((r) => r.requestId === requestId);
  if (run) {
    run.status = allowed ? "running" : "denied";
    if (!allowed) run.output = "已拒绝执行";
  }
  store.version++;
}

// ============================================================
// 设置
// ============================================================

export async function loadSettings(): Promise<void> {
  store.settings = await getSettings();
}

export async function loadSkills(): Promise<void> {
  store.skills = await listSkills();
}

export async function saveSettings(): Promise<void> {
  // 原版写完只弹提示不回读；本项目端点会回读一遍，顺手用返回体覆盖本地，省一次 GET
  store.settings = await apiSaveSettings(store.settings);
  notify("剧本导师设置已保存，下次对话生效");
}

// ============================================================
// 历史重建
// ============================================================

/**
 * 从历史消息恢复用量统计：DB 只落 prompt/completion/cached 三列（assistant 消息），
 * 累计值按各列求和，「本轮」取最后一条有用量记录的消息。
 * 旧库/旧消息无 token 列时为 null，保持 0/null 不显示。
 */
function restoreUsage(msgs: PersistedMessage[]): void {
  let total = 0;
  let prompt = 0;
  let completion = 0;
  let cached = 0;
  let last: TokenUsage | null = null;
  for (const m of msgs) {
    if (m.role === "assistant" && m.promptTokens != null && m.completionTokens != null) {
      prompt += m.promptTokens;
      completion += m.completionTokens;
      cached += m.cachedTokens ?? 0;
      total += m.promptTokens + m.completionTokens;
      last = {
        prompt_tokens: m.promptTokens,
        completion_tokens: m.completionTokens,
        total_tokens: m.promptTokens + m.completionTokens,
        cached_tokens: m.cachedTokens ?? 0,
      };
    }
  }
  store.totalTokens = total;
  store.totalPromptTokens = prompt;
  store.totalCompletionTokens = completion;
  store.totalCachedTokens = cached;
  store.lastUsage = last;
}

/** 把后端返回的消息重建成 UI 的 ChatItem（assistant 按 tool_calls 拆 round，tool 结果挂回对应 run）。 */
function rebuildItems(msgs: PersistedMessage[]): ChatItem[] {
  const items: ChatItem[] = [];
  let current: ChatItem | null = null;
  for (const m of msgs) {
    if (m.role === "user") {
      current = null;
      items.push({
        id: `p-${m.id}`,
        role: "user",
        content: m.content ?? "",
        rounds: [],
        streaming: false,
      });
    } else if (m.role === "assistant") {
      const round: ChatRound = {
        content: m.content ?? "",
        // 思考链已持久化；旧库数据为 null，按无思考链处理
        reasoning: m.reasoning ?? undefined,
        toolRuns: (m.toolCalls ?? []).map((tc) => ({
          callId: tc.id,
          tool: tc.function.name,
          args: safeParse(tc.function.arguments),
          status: "done" as const,
          rawArgs: tc.function.arguments,
          output: "（工具已执行，结果见下方）",
        })),
      };
      if (current && current.role === "assistant") {
        current.rounds.push(round);
      } else {
        current = {
          id: `p-${m.id}`,
          role: "assistant",
          content: "",
          rounds: [round],
          streaming: false,
        };
        items.push(current);
      }
    } else if (m.role === "tool") {
      const run = current?.rounds.flatMap((r) => r.toolRuns).find((r) => r.callId === m.toolCallId);
      if (run) run.output = m.content ?? "";
    }
  }
  return items;
}
