<template>
  <div class="main-box text-white">
    <!-- ① 工具条（左上角：场景 + 面试印象；原版 MainChat 第一个渲染的组件） -->
    <FreeModeTools />

    <!-- ② 顶部状态面板（原版 .main-status-panel）：StatusInfoBar 在 .main-status-row 内，
         FullAccessWarning 紧随；收编原先三个游离 fixed 容器中的两个 -->
    <div class="main-status-panel">
      <div class="main-status-row">
        <StatusInfoBar />
      </div>
      <FullAccessWarning />
    </div>

    <!-- ③ 背景（背景图 + 压暗层） -->
    <GameBackground />

    <!-- ④ 立绘（背景之上、对话框之下，底部对齐；按在场角色渲染多槽位，缩放/偏移取角色设定） -->
    <GameRolesStage />

    <!-- ⑤ 对话框（底部，z-10 压在立绘上） -->
    <div class="absolute inset-x-0 bottom-0 z-10">
      <GameDialog @send="handleSend" @finish="endNow" @history="historyOpen = true" />
    </div>

    <!-- ⑥ 右上角菜单面板（原版 #menu-panel）：活动状态 + 进度/计时/结束面试 + 设置入口（V3a） -->
    <div id="menu-panel" ref="menuPanelRef">
      <div class="hidden items-center xl:flex">
        <ToolActivityStatus />
      </div>
      <div class="text-right [text-shadow:0_2px_4px_rgba(0,0,0,0.2)]">
        <div class="flex items-baseline justify-end gap-2 text-sm font-bold">
          <template v-if="isScript">
            <span v-if="scriptChapter" class="text-[#79d9ff]">{{ scriptChapter.name }}</span>
            <span v-else>第 <span class="text-[#79d9ff]">{{ currentNo }}</span> / {{ questionPool.length }} 题</span>
          </template>
          <template v-else>
            <span class="text-white/85">自由面试</span>
            <span class="text-white/40">·</span>
            <span class="text-white/70">第 {{ answeredCount + 1 }} 轮</span>
          </template>
          <span class="text-white/40">·</span>
          <span class="font-mono tabular-nums text-white/85">{{ timerText }}</span>
        </div>
        <div v-if="isScript" class="mt-2 ml-auto h-[3px] w-40 overflow-hidden rounded-full bg-white/15">
          <div class="h-full rounded-full bg-[#79d9ff] transition-all duration-500" :style="{ width: (scriptChapter ? scriptProgressPct : progress) + '%' }" />
        </div>
      </div>
      <button class="nav-btn" @click="endNow">结束面试</button>
      <button class="nav-btn" title="设置" @click="openSettings">
        <Icon :icon="'setting'" :size="18" />
      </button>
    </div>

    <!-- ⑦ 附加 UI 层（章节 / 选项 / 完成提示 / 声效面板 / 频谱） -->
    <GameExtraUI @choose="submitChoice" />

    <!-- ⑧ 历史对话面板（正文只显示当前一句，全文在这里看） -->
    <Teleport to="body">
      <Transition
        enter-active-class="transition duration-200 ease-out"
        enter-from-class="opacity-0"
        leave-active-class="transition duration-150 ease-in"
        leave-to-class="opacity-0"
      >
        <div v-if="historyOpen" class="fixed inset-0 z-[100] flex items-center justify-center p-6">
          <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="historyOpen = false" />
          <div class="glass-effect relative z-10 flex max-h-[72vh] w-full max-w-2xl flex-col rounded-3xl p-6">
            <div class="mb-4 flex items-center justify-between">
              <h3 class="text-xl font-black tracking-tight text-[#79d9ff]">历史对话</h3>
              <button
                class="cursor-pointer border-none bg-transparent p-1 text-2xl leading-none font-bold text-white/60 transition-colors hover:text-white"
                @click="historyOpen = false"
              >
                ×
              </button>
            </div>
            <div class="custom-scroll flex-1 space-y-4 overflow-y-auto pr-2">
              <p v-if="!roomStore.lines.length" class="text-sm text-white/50">还没有对话。</p>
              <div v-for="(line, i) in roomStore.lines" :key="i">
                <div
                  class="mb-1 text-[11px] font-bold tracking-[0.18em]"
                  :class="line.role === 'ai' ? 'text-[#79d9ff]' : 'text-white/45'"
                >
                  {{ line.role === "ai" ? persona.name : "我" }}
                  <span v-if="line.role === 'ai' && line.emotion" class="ml-2 tracking-normal text-[#ff77dd]">
                    {{ line.emotion }}
                  </span>
                </div>
                <p class="text-sm leading-relaxed whitespace-pre-line text-white/85">{{ line.text }}</p>
                <p v-if="line.motion" class="mt-1 text-xs whitespace-pre-line text-[#9ca3af]">{{ line.motion }}</p>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { GameBackground, GameDialog, GameExtraUI, GameRolesStage } from "../components/game";
import FreeModeTools from "../components/tools/FreeModeTools.vue";
import StatusInfoBar from "../components/tools/StatusInfoBar.vue";
import ToolActivityStatus from "../components/tools/ToolActivityStatus.vue";
import FullAccessWarning from "../components/tools/FullAccessWarning.vue";
import { dimensionNames, jobs, questions as questionBank, type InterviewRecord } from "../data/mock";
import { findInterviewer } from "../data/interviewers";
import { confirmDialog } from "../store/dialogStore";
import {
  createSession,
  fetchSessionDetail,
  finishSession,
  isBackendUp,
  sendMessage,
  startScript,
  submitScriptChoice,
  submitScriptInput,
  type ReplyEvent,
  type SessionDetail,
  type StreamEvent,
} from "../api/chat";
import {
  addRecord,
  clearSession,
  persist,
  pushMessage,
  resumeSession,
  startSession,
  store,
  type ChatMessage,
} from "../store/appStore";
import {
  initRoomViewport,
  pushLine,
  resetRoom,
  roomStore,
  setPresentRoles,
  speakAs,
  type RoomLine,
} from "../store/roomStore";
import { attachRoomAudio } from "../store/roomAudio";
import Icon from "../components/base/widget/Icon.vue";
import { applyPendingChoice, runScriptStream } from "../composables/useScriptRunner";
import { cardForCode, ensureCharactersLoaded } from "../store/characters";

const router = useRouter();
const route = useRoute();

const historyOpen = ref(false);
const finished = ref(false);
const autoFinish = ref(false);
const elapsed = ref(0);

const persona = computed(() => findInterviewer(store.session.personaId));
const job = computed(() => jobs.find((j) => j.id === store.session.jobId) ?? jobs[0]);

/**
 * 当前角色的后端角色卡（summary）。
 *
 * 演出用的是 LingChat 原卡（自由对话里风雪就是那只猫娘），所以舞台副标题取原卡的
 * `ai_subtitle`，而不是「严格技术面」这类面试官角色名。卡还没到位时为 undefined，
 * 各处写副标题时回落到本地静态卡的 `subtitle`（两者本应一致）。
 */
const card = computed(() => cardForCode(persona.value.code));

/** 剧本面试才有固定题量；自由面试不设上限，用户点「结束面试」才收尾 */
const isScript = computed(() => store.session.mode === "script");

/** 题库：先按知识点偏好排序。剧本面试截取 settings.questionCount 道，自由面试用整份循环 */
const questionPool = computed(() => {
  const all = questionBank.filter((q) => q.jobId === store.session.jobId);
  const preferred = all.filter((q) => store.settings.kps.includes(q.kp));
  const rest = all.filter((q) => !preferred.includes(q));
  const ordered = [...preferred, ...rest];
  if (!isScript.value) return ordered;
  const target = Math.max(3, Math.min(8, store.settings.questionCount));
  return ordered.slice(0, target);
});

const progress = computed(() => {
  if (!isScript.value || !questionPool.value.length) return 0;
  return Math.min(100, (store.session.questionIndex / questionPool.value.length) * 100);
});

const currentNo = computed(() =>
  Math.min(store.session.questionIndex + 1, questionPool.value.length || 1),
);

const answeredCount = computed(() =>
  isScript.value
    ? Math.min(store.session.questionIndex, questionPool.value.length)
    : store.session.questionIndex,
);

const timerText = computed(() => {
  const total = elapsed.value;
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
});

/** 当前情绪：优先取当前说话角色的情绪；旁白/玩家句回落到「当前句及之前最近的一句 AI 台词」 */
const currentEmotion = computed(() => {
  const speaker = roomStore.activeSpeaker;
  if (speaker && roomStore.roleEmotions[speaker]) return roomStore.roleEmotions[speaker];
  for (let i = Math.min(roomStore.cursor, roomStore.lines.length - 1); i >= 0; i--) {
    const line = roomStore.lines[i];
    if (line.role === "ai") return line.emotion ?? "正常";
  }
  return "正常";
});

/**
 * 把后端给的角色标识落成舞台 code。
 * - `null` / `0`：旁白 / 求职者，不切说话人
 * - 字符串：剧本事件里的 code（MAIN 由后端 roleId() 解析；万一漏了解析，按本场人设兜底）
 * - 数字：interviewer.id（ai:reply 的 senderRoleId 口径），本场只有一位人设，按人设兜底
 */
function resolveRoleCode(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === "number") return raw === 0 ? null : persona.value.code;
  const code = String(raw).trim();
  if (!code || code === "0") return null;
  if (code === "MAIN") return persona.value.code;
  return code;
}

// 情绪同时驱动标题栏标签（GameDialog 读 roomStore）
watch(currentEmotion, (e) => (roomStore.showCharacterEmotion = e), { immediate: true });

// 每句台词上屏时把舞台切到它的说话人（原版 dialogue-processor 逐条设 currentInteractRoleId）
watch(
  () => roomStore.cursor,
  () => {
    const line = roomStore.lines[roomStore.cursor];
    if (!line?.speaker) return;
    speakAs(line.speaker, line.emotion);
  },
);
watch(
  persona,
  (p) => {
    roomStore.showCharacterTitle = p.name;
    roomStore.showCharacterSubtitle = card.value?.aiSubtitle ?? p.subtitle;
    // 单人在场时换人设 = 换舞台上的那位；多角色场景不动 presentRoles
    if (roomStore.presentRoles.length <= 1) setPresentRoles([p.code]);
  },
  { immediate: true },
);

// 角色卡是异步到的：到位后刷新副标题（晚了的那一下只有副标题需要改）
watch(card, (c) => {
  roomStore.showCharacterSubtitle = c?.aiSubtitle ?? persona.value.subtitle;
});

// ───────────────────── 符号解析（本批最简版） ─────────────────────
// TODO(S3): 换成 SymbolParser
function parseSymbols(raw: string): { text: string; motion?: string; emotion?: string } {
  const emotion = raw.match(/【([^】]*)】/)?.[1];
  const motion = raw.match(/（([^）]*)）/)?.[1];
  const text = raw.replace(/【([^】]*)】/g, "").replace(/（([^）]*)）/g, "").trim();
  return { text, motion, emotion };
}

function emitAi(
  raw: string,
  extra: { kind?: "text" | "question"; meta?: NonNullable<ChatMessage["meta"]> } = {},
) {
  const parsed = parseSymbols(raw);
  pushMessage("ai", parsed.text, { ...extra, emotion: parsed.emotion });
  pushLine({
    role: "ai",
    text: parsed.text,
    motion: parsed.motion,
    emotion: parsed.emotion,
    final: true,
    // 本地假回复没有 senderRoleId，说话人就是本场人设
    speaker: persona.value.code,
  });
}

// ───────────────────── 假回复（S3 换真 LLM） ─────────────────────
function currentQuestion() {
  const pool = questionPool.value;
  if (!pool.length) return undefined;
  const idx = isScript.value ? store.session.questionIndex : store.session.questionIndex % pool.length;
  return pool[idx];
}

function kpName(id: string) {
  return job.value.knowledgePoints.find((k) => k.id === id)?.name ?? id;
}

function feedbackFor(q: (typeof questionBank)[number], answer: string) {
  const short = answer.trim().length < 26;
  if (short) {
    return "可以再展开一点吗？我想听到你的判断依据，而不只是结论。先说你的方案，再说为什么这样选、代价是什么。";
  }
  const follow = q.followUps[0] ?? "这个方案的边界在哪里";
  const hints = [
    `方向是对的。但如果继续往下问，我会关心：${/[。？?！!]$/.test(follow) ? follow : follow + "。"}`,
    `你提到了关键点。再补一层取舍会更好：${q.reference.split("；")[0]}。`,
    "这个回答结构可以更清晰：先给结论，再给两到三条理由，最后说你怎么验证。",
  ];
  return hints[store.session.questionIndex % hints.length];
}

/** 第一问：开场后第一次出题（session + room 同步） */
function askOpeningQuestion() {
  const q = currentQuestion();
  if (!q) return;
  emitAi(`【认真】${q.text}`, {
    kind: "question",
    meta: {
      index: store.session.questionIndex + 1,
      total: questionPool.value.length,
      kp: kpName(q.kp),
      type: q.type,
      difficulty: q.difficulty,
    },
  });
}

/** 一轮 AI 回复：点评一句 + 下一问一句（单句推进，逐句 ▼） */
function runAiTurn(q: (typeof questionBank)[number] | undefined, answer: string) {
  if (finished.value) return;
  const short = answer.trim().length < 26;
  const first = roomStore.lines.length;

  if (q) {
    const emotion = short ? "担心" : "认真";
    const motion = short ? "（微微前倾，等你把话说完）" : "（低头记了两笔）";
    emitAi(`【${emotion}】${motion}${feedbackFor(q, answer)}`);
  }

  store.session.questionIndex += 1;
  persist();

  const next = currentQuestion();
  const poolDone = isScript.value && store.session.questionIndex >= questionPool.value.length;

  if (next && !poolDone) {
    emitAi(`【认真】${next.text}`, {
      kind: "question",
      meta: {
        index: store.session.questionIndex + 1,
        total: questionPool.value.length,
        kp: kpName(next.kp),
        type: next.type,
        difficulty: next.difficulty,
      },
    });
  } else {
    emitAi("【平静】（合上笔记本）今天先到这里，感谢你的时间。报告已经生成。");
    autoFinish.value = true;
  }

  // 从本轮第一句开始播；pushLine 已把 cursor 推到末句，这里显式回拨
  roomStore.cursor = first;
  roomStore.state = "responding";
}

// ───────────────────── 交互 ─────────────────────
let timer: number | undefined;
let pending: number | undefined;

// ───────────────────── 真后端（B3）：会话 id 与降级开关 ─────────────────────
const sessionId = ref<number | null>(null);

/** 本场绑定的剧本 code；null = 自由对话。决定用户发言走 /script/input 还是 /messages */
const boundScriptCode = ref<string | null>(null);

// ───────────────────── 剧本驱动（script 模式专用，free 模式不经过） ─────────────────────
/** 当前章节（script:chapter-change 带来的 code/name），null = 后端剧本未连接 */
const scriptChapter = ref<{ code: string; name: string } | null>(null);
/** 章节内进度百分比（script:progress） */
const scriptProgressPct = ref(0);

/** 进房间时建会话；失败就返回 false，发送走原有的本地假回复 */
async function ensureServerSession(): Promise<boolean> {
  if (sessionId.value !== null) return true;
  if (!(await isBackendUp())) return false;
  try {
    // 剧本模式带上「剧本冒险」里选中的剧本 code；自由模式不传（后端不再按岗位自动绑剧本）
    const scriptCode =
      store.session.mode === "script" ? localStorage.getItem("bondScript") || undefined : undefined;
    const result = await createSession({
      interviewerCode: store.settings.personaId,
      jobId: store.settings.jobId,
      scriptCode,
    });
    sessionId.value = result.sessionId;
    boundScriptCode.value = scriptCode ?? null;
    // 开场白以刚建出来的场次为准（startSession 会先在本地塞一条同文案的开场，这里换成后端的）
    const seededOpeningOnly =
      store.session.messages.length === 1 &&
      store.session.messages[0].role === "ai" &&
      !roomStore.lines.some((line) => line.role === "user");
    if (seededOpeningOnly && result.opening?.message) {
      store.session.messages[0].text = result.opening.message;
      store.session.messages[0].emotion = result.opening.emotion;
      persist();
      resetRoom();
      setPresentRoles([persona.value.code]);
      pushLine({
        role: "ai",
        text: result.opening.message,
        emotion: result.opening.emotion,
        final: true,
        speaker: persona.value.code,
      });
      roomStore.cursor = 0;
    } else if (!roomStore.lines.length && result.opening?.message) {
      pushMessage("ai", result.opening.message, { emotion: result.opening.emotion });
      pushLine({
        role: "ai",
        text: result.opening.message,
        emotion: result.opening.emotion,
        final: true,
        speaker: persona.value.code,
      });
      roomStore.cursor = roomStore.lines.length - 1;
    }
    return true;
  } catch {
    sessionId.value = null;
    return false;
  }
}

/**
 * 走后端流式对话。返回 true 表示这条路径已经产出了台词（不要再走假回复）；
 * false 表示后端不可用 / 一个字都没收到，交回 handleSend 走降级。
 *
 * 台词先收在 buffer 里，`status:reset` 后一次性入队，再从第一句起逐句播放——
 * 这样每一句都能被读到（房间一次只显示一句，边收边播会把前一句直接顶掉）。
 */
async function sendToBackend(text: string): Promise<boolean> {
  if (!(await ensureServerSession())) return false;
  const id = sessionId.value as number;
  const buffered: RoomLine[] = [];
  try {
    for await (const ev of sendMessage(id, text)) {
      if (ev.event === "ai:reply") {
        const data = ev.data as ReplyEvent;
        if (!data?.message) continue;
        buffered.push({
          role: "ai",
          text: data.message,
          motion: data.motionText || undefined,
          emotion: data.emotion,
          final: data.isFinal,
          speaker: resolveRoleCode(data.senderRoleId) ?? undefined,
        });
      } else if (ev.event === "ai:error") {
        console.warn("[interview] ai:error", ev.data);
      }
      // status:reset 只表示流结束，播放逻辑在下面统一处理
    }
  } catch (error) {
    console.warn("[interview] 后端对话失败", error);
    sessionId.value = null;
    if (!buffered.length) return false;
  }

  if (!buffered.length) return false;

  for (const line of buffered) {
    pushMessage("ai", line.text, { emotion: line.emotion });
  }
  const first = roomStore.lines.length;
  buffered.forEach((line) => pushLine(line));
  roomStore.cursor = first;
  roomStore.state = "responding";

  store.session.questionIndex += 1;
  persist();
  return true;
}

/**
 * 消费一段剧本事件流（开演 / 输入 / 选项三条入口共用）。返回 true 表示后端已经接管
 * 这次提交（产出了台词，或已停在某处等输入），不要再走本地假回复；false 交回 handleSend 降级。
 *
 * 实现整段在 `composables/useScriptRunner.ts`：试玩（PreviewStage）用的是同一份，
 * 避免两处各抄一份事件处理逻辑后慢慢漂移。房间只负责把 hook 接到自己的落点。
 */
async function runScript(stream: AsyncGenerator<StreamEvent>): Promise<boolean> {
  return runScriptStream(stream, {
    resolveRole: resolveRoleCode,
    recordLine: (line) =>
      pushMessage(line.role, line.text, line.emotion ? { emotion: line.emotion } : {}),
    onChapter: (c) => (scriptChapter.value = c),
    onProgress: (p) => (scriptProgressPct.value = p),
    onEnd: () => (autoFinish.value = true),
    onError: (payload) => console.warn("[interview] ai:error", payload),
  });
}

/**
 * 是否全新场次（后端还没演过任何剧本台词）。
 *
 * 注意：规格写的是「detail.messages 为空」，但后端 createSession 必落两行
 * （system 提示词 seq=0、开场白 seq=1），messages 永远不为空；故按
 * 「除 system 外至多只有开场白一句，且剧本进度仍在起点」判定。
 */
function isFreshScene(detail: SessionDetail): boolean {
  const spoken = (detail.messages ?? []).filter((m) => m.role !== "system");
  return spoken.length <= 1 && detail.eventSequence === 0;
}

/**
 * script 模式进房间：全新场次直接开演（startScript）；已推进过的场次保守停在输入态等用户发言续跑。
 */
async function enterScriptScene(): Promise<void> {
  const id = sessionId.value;
  if (id === null) {
    roomStore.state = "input";
    return;
  }

  let detail: SessionDetail | null = null;
  try {
    detail = await fetchSessionDetail(id);
  } catch (error) {
    console.warn("[interview] 拉取场次详情失败", error);
  }

  // 以后端为准：只有真的绑上了剧本（scriptCode 非空）才把发言走剧本入口
  if (detail) boundScriptCode.value = detail.scriptCode ?? null;
  const scriptCode = boundScriptCode.value;
  if (!scriptCode) {
    roomStore.state = "input";
    return;
  }

  if (detail && isFreshScene(detail)) {
    // 全新场次：清掉 startSession 预埋的开场句，让脚本从第一章第一句开演
    resetRoom();
    // 舞台上默认站本场人设；剧本要加人/换人由 script:modify-character 接管
    setPresentRoles([persona.value.code]);
    store.session.messages = [];
    persist();
    await runScript(startScript(id, scriptCode));
    return;
  }

  // 刷新续跑：本地 syncRoomFromSession 的结果为准，不重放，也不自动开演
  roomStore.state = "input";
}

/**
 * @param viaChoice true = 这次提交来自选项点击，走剧本的 choice 入口（强制按选项文案匹配）
 */
async function handleSend(text: string, viaChoice = false) {
  if (finished.value || !text) return;
  // 提交即收起选项层（原版脚本分支同样是「提交成功后 options 清空触发渐隐」）；
  // 新一轮若有 script:choice 会重新放出来
  if (roomStore.choices.length) {
    roomStore.choices = [];
    roomStore.allowFree = false;
    roomStore.choiceDuration = null;
  }
  pushMessage("user", text);
  pushLine({ role: "user", text, final: false });
  roomStore.state = "thinking";

  // 后端在线 → 真模型：绑了剧本走 /script/*，否则走自由对话 /messages；
  // 都没走通时保留原来的本地假回复（降级不能删）
  let ok: boolean;
  const id = sessionId.value;
  if (boundScriptCode.value !== null && id !== null) {
    // 阻塞标记由 runScriptStream 每次提交时自行复位（原先是这里置 false，语义相同）
    ok = await runScript(viaChoice ? submitScriptChoice(id, text) : submitScriptInput(id, text));
  } else {
    ok = await sendToBackend(text);
  }
  if (ok) return;

  const q = currentQuestion();
  pending = window.setTimeout(() => runAiTurn(q, text), 900);
}

/**
 * 点选 script:choice 的选项：先用选中文本入队（照原版 selectChoice：先记消息再提交），
 * 再走剧本的 choice 入口（后端强制按文本匹配 pendingChoice）。disabled 项永远不提交。
 */
function submitChoice(text: string) {
  if (finished.value) return;
  const picked = roomStore.choices.find((c) => c.text === text);
  if (picked?.disabled) return;
  void handleSend(text, true);
}

/** 剧本模式最后一句读完（回到输入态）即收尾；挂起的选项也在这一刻放出 */
watch(
  () => roomStore.state,
  (state) => {
    if (state !== "input") return;
    applyPendingChoice();
    if (autoFinish.value && !finished.value) void finish();
  },
);

/** V3a：原版 MainChat 的 #menu-panel 里第三个按钮就是 openSettings。本项目是路由式设置页，直接跳过去 */
function openSettings() {
  router.push("/settings");
}

async function endNow() {
  if (finished.value) return;
  if (await confirmDialog("结束面试", "结束本场面试并生成报告？")) await finish();
}

function clamp(n: number) {
  return Math.max(0, Math.min(100, Math.round(n)));
}

async function finish() {
  if (finished.value) return;
  finished.value = true;
  if (timer) window.clearInterval(timer);
  if (pending) window.clearTimeout(pending);

  const pool = questionPool.value;
  const total = Math.max(1, answeredCount.value);
  // 自由面试题库会循环，按取模还原实际问过的题序
  const answered = Array.from({ length: total }, (_, i) => pool[isScript.value ? i : i % (pool.length || 1)])
    .filter((q): q is (typeof questionBank)[number] => !!q);
  const base = 79 + (answered.length % 4);
  const dims = dimensionNames.map((name, i) => ({
    name,
    score: clamp(base + [5, -3, 7, 8, 1][i]),
  }));
  const mockScore = clamp(dims.reduce((sum, d) => sum + d.score, 0) / dims.length);

  // 后端在线 → 用后端算出的总分；调用失败则退回本地 mock 分数（降级不能删）
  let score = mockScore;
  if (sessionId.value !== null) {
    try {
      const result = await finishSession(sessionId.value);
      if (typeof result.score === "number") score = clamp(result.score);
    } catch (error) {
      console.warn("[interview] 结束场次失败，沿用本地分数", error);
    }
  }

  const record: InterviewRecord = {
    id: `r-${Date.now()}`,
    jobName: job.value.name,
    personaName: `${persona.value.name} · ${persona.value.displayRole}`,
    date: new Date().toLocaleString("zh-CN", { hour12: false }).replace(/\//g, "-"),
    duration: timerText.value,
    score,
    dimensions: dims,
    questions: answered.map((q, i) => ({
      id: q.id,
      question: q.text,
      kp: kpName(q.kp),
      score: clamp(base + ((i * 7) % 11) - 4),
      comment: i % 2 === 0 ? "方向正确，关键点覆盖到了，边界条件还可以再补。" : "回答能自洽，但缺少可量化的验证。",
      suggest: q.followUps[0] ?? "补充一个真实项目中的例子与结果。",
    })),
    advice: [
      "回答时先给结论，再给两到三条理由，最后说验证方式。",
      "项目题准备数字：QPS、耗时、错误率、成本，任何一个都比「很快」有说服力。",
      "遇到不会的问题，先说思路和边界，再承认没做过，别硬编。",
    ],
  };

  addRecord(record);
  clearSession();
  router.push(`/report/${record.id}`);
}

/** 把 session.messages 还原成房间句子队列（刷新/挂载恢复）
 *  一条消息内的空行分段拆成独立句子，保证「一次只显示一句」 */
function syncRoomFromSession() {
  resetRoom();
  // 舞台默认单人：本场人设（单立绘居中，与改多槽位之前完全一致）；
  // 多人在场由 script:modify-character 的 presentRoles 覆盖
  setPresentRoles([persona.value.code]);
  for (const m of store.session.messages) {
    const parsed = parseSymbols(m.text);
    const segments = parsed.text
      .split(/\n{2,}/)
      .map((s) => s.trim())
      .filter(Boolean);
    segments.forEach((text, i) => {
      pushLine({
        role: m.role,
        text,
        motion: i === 0 ? parsed.motion : undefined,
        emotion: m.emotion ?? parsed.emotion,
        final: true,
      });
    });
  }
}

let disposeViewport: (() => void) | undefined;
/** 剧本音轨驱动（script:music / sound / ambient → HTMLAudio） */
let disposeAudio: (() => void) | undefined;

onMounted(async () => {
  // 角色卡（显示层数据源）：懒加载一次即可，失败不拦房间演出
  void ensureCharactersLoaded();
  if (route.query.resume) resumeSession();
  // 直接访问 /interview（没有 session 内容）时兜底建一场，沿用已有 mode（缺省 free）
  if (!store.session.messages.length) {
    startSession(store.settings.jobId, store.settings.personaId, store.session.mode ?? "free");
  }
  store.session.active = true;
  if (!store.session.startedAt) store.session.startedAt = Date.now();
  elapsed.value = Math.floor((Date.now() - store.session.startedAt) / 1000);
  timer = window.setInterval(() => {
    if (store.session.startedAt) {
      elapsed.value = Math.floor((Date.now() - store.session.startedAt) / 1000);
    }
  }, 1000);

  disposeViewport = initRoomViewport();
  disposeAudio = attachRoomAudio();
  roomStore.showCharacterTitle = persona.value.name;
  roomStore.showCharacterSubtitle = card.value?.aiSubtitle ?? persona.value.subtitle;
  roomStore.showCharacterEmotion = "正常";

  syncRoomFromSession();

  // 调试钩子（验收用，保留）：控制台 window.__setPresentRoles(["strict", "friendly"]) 造多角色场景
  (window as unknown as Record<string, unknown>).__setPresentRoles = (codes: unknown) =>
    setPresentRoles(Array.isArray(codes) ? codes.map((c) => String(c)) : []);

  // 后端可用 → 用模型开场白并等用户输入；不可用 → 保留原来的本地开场问题
  const online = await ensureServerSession();
  if (online) {
    // 剧本模式：接剧本引擎（advance 链路）
    if (store.session.mode === "script") {
      await enterScriptScene();
      return;
    }
    roomStore.cursor = Math.max(0, roomStore.lines.length - 1);
    roomStore.state = roomStore.lines.length ? "responding" : "input";
    return;
  }

  const hasQuestion = store.session.messages.some((m) => m.kind === "question");
  if (!hasQuestion) {
    // 开场白 + 第一问一起排队，从头逐句推进
    askOpeningQuestion();
    roomStore.cursor = 0;
  }
  roomStore.state = "responding";

  if (!roomStore.lines.length) roomStore.state = "input";
});

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
  if (pending) window.clearTimeout(pending);
  disposeViewport?.();
  disposeAudio?.();
  delete (window as unknown as Record<string, unknown>).__setPresentRoles;
  resetRoom();
  historyOpen.value = false;
});
</script>

<style>
.main-box {
  position: absolute;
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  overflow: hidden;
}

.main-status-panel {
  position: fixed;
  top: calc(15px + var(--safe-area-inset-top));
  left: 50%;
  z-index: 1001;
  display: flex;
  width: min(44rem, calc(100vw - 2rem));
  transform: translateX(-50%);
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

.main-status-row {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: center;
}

#menu-panel {
  display: flex;
  position: fixed;
  top: calc(15px + var(--safe-area-inset-top));
  right: 20px;
  z-index: 1000;
}
</style>
