/**
 * 剧本编辑器状态中枢（规格 E2 §3）。
 *
 * 原版是 Pinia 的 `defineStore("scriptEditor")`，本项目没有 Pinia：
 * 照 `store/appStore.ts` 的写法，模块级 `reactive` 单例 + 导出 action 函数 +
 * 导出 computed。组件只读 `store`，改状态一律走下面的 action。
 */

import { computed, reactive, watch } from "vue";
import {
  createChapter as apiCreateChapter,
  createScript as apiCreateScript,
  deleteChapter as apiDeleteChapter,
  deleteScript as apiDeleteScript,
  getSchema,
  listGlobalBackgrounds,
  listScripts,
  readChapter,
  readScript,
  uploadEditorBg as apiUploadEditorBg,
  validateScript,
  writeChapter,
  writeConfig,
  type AssetFileWeb,
  type AssetIndex,
  type ChapterContent,
  type ChapterEdge,
  type CreateScriptBody,
  type Diagnostic,
  type EventSpec,
  type PreviewSessionView,
  type ScriptDetail,
  type ScriptEventData,
  type ScriptPackage,
  type ScriptSchema,
  type StoryConfig,
  type ValidationReport,
} from "../api/editor";
import { firstVisibleIndex } from "../composables/useEventFolding";
import { notify } from "./appStore";
import { alertDialog, confirmDialog } from "./dialogStore";

/** 原版 8 个 Tab。本批只渲染 flow，其余 key 保留在类型里，E3 起逐个启用。 */
export type EditorTabKey =
  | "flow" | "config" | "characters" | "assets"
  | "validate" | "agent-chat" | "agent-settings" | "appearance";

/**
 * 没打开剧本时仍然能进的 Tab（对齐原版 `EditorHeader.vue:125` 与 `:183-186`）。
 *
 * <p>原版白名单是 `flow / agent-chat / agent-settings / appearance` 四项 ——
 * AI 助手与助手设置不依赖"当前打开了哪个剧本"，作者可以先进去聊剧本大纲，
 * 聊完再决定打开或新建哪一本。
 *
 * <p>本项目此前只写了 `flow / appearance`，AI 助手被挡在门外（2026-10-25 修）。
 * 这个常量必须被 **三处** 同时消费，漏一处就还是进不去：
 * ① `EditorHeader.switchTab` 的守卫、② Tab 按钮的 `disabled`、
 * ③ `ScriptEditorView.currentTabComponent` 的回落判断。
 */
export const NO_SCRIPT_TABS: EditorTabKey[] = [
  "flow", "agent-chat", "agent-settings", "appearance",
];

export const UNDO_LIMIT = 100;
export const AUTOSAVE_DELAY = 800;      // 自动保存防抖
export const VALIDATE_DELAY = 2500;     // 校验比保存重，单独更长防抖

interface UndoFrame {
  chapterId: string;
  name?: string;
  events: ScriptEventData[];
  /** 该帧对应的选中下标，撤销后光标回到原处 */
  selected: number;
}

// ---- 编辑器背景（E6a，对齐原版 DEFAULT_EDITOR_BG）----

const EDITOR_BG_LS_KEY = "script-editor-editorbg";
export const DEFAULT_EDITOR_BG = { path: "", blur: 12, dim: 0.3 };
type EditorBg = { path: string; blur: number; dim: number };

/** localStorage 读 JSON，merge 默认值，blur 限 0~24、dim 限 0~0.9，坏数据回落默认 */
function loadEditorBg(): EditorBg {
  try {
    const raw = localStorage.getItem(EDITOR_BG_LS_KEY);
    if (!raw) return { ...DEFAULT_EDITOR_BG };
    const parsed = JSON.parse(raw) as Partial<EditorBg> | null;
    const p = parsed ?? {};
    const clamp = (v: unknown, lo: number, hi: number, d: number) =>
      typeof v === "number" && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d;
    return {
      path: typeof p.path === "string" ? p.path : DEFAULT_EDITOR_BG.path,
      blur: clamp(p.blur, 0, 24, DEFAULT_EDITOR_BG.blur),
      dim: clamp(p.dim, 0, 0.9, DEFAULT_EDITOR_BG.dim),
    };
  } catch {
    return { ...DEFAULT_EDITOR_BG };
  }
}

export const store = reactive({
  // ---- 后端数据 ----
  schema: null as ScriptSchema | null,
  scripts: [] as ScriptPackage[],
  detail: null as ScriptDetail | null,
  chapter: null as ChapterContent | null,

  // ---- 界面态 ----
  loading: false,
  /** "flow" = 章节流程；"chapter" = 章节编辑（本批恒为 flow，E4 启用 chapter） */
  level: "flow" as "flow" | "chapter",
  /** 本批只有 flow */
  tab: "flow" as EditorTabKey,
  selectedEvent: 0,
  /** 时间线是否把「转场 / AI 互动轮次」折成一行（原版持久化到 localStorage，本项目只做内存态） */
  foldCompounds: true,
  /** 事件属性栏展开（临时态，E4 用到） */
  propsExpanded: false,
  propsWidth: 720,

  // ---- 保存态 ----
  dirty: false,
  saving: false,
  lastSavedAt: null as number | null,

  // ---- 撤销栈 ----
  undoStack: [] as UndoFrame[],
  redoStack: [] as UndoFrame[],

  // ---- 校验 ----
  report: null as ValidationReport | null,

  // ---- 试玩（E5b，PreviewStage）----
  /** 试玩层是否打开（组件 watch 到 true 才去建场，见 PreviewStage.vue） */
  previewing: false,
  /** 后端试玩场次 id；null = 还没建出来。退出时据此删场 */
  previewSessionId: null as number | null,
  /** 建场返回体（面试官 / 剧本 / 起始章节），顶栏 label 与舞台人设都用它 */
  previewInfo: null as PreviewSessionView | null,
  /** 点「试玩」时选定的起始章节，空串 = 从 intro_chapter 开始 */
  previewFromChapter: "" as string,
  /** 建场失败的文案（不静默吞掉，见 PreviewStage.start） */
  previewError: "" as string,
  /** 收到 script:end：顶栏提示「本段已演完」。试玩不结束场次、不落成绩 */
  previewAutoEnd: false,

  // ---- 编辑器背景（E6a）----
  editorBg: loadEditorBg() as EditorBg,
  /** 强制 <img>/url() 重取的版本号（对齐原版 bgImageSrc 的 ?v= 做法） */
  bgVersion: 0,
  /** 全局背景清单（内置 + 剧本自带 + 编辑器上传，后端合并返回） */
  globalBgFiles: [] as AssetFileWeb[],
});

/** 本批真正上屏的 Tab：章节流程 + 剧本设置 + 校验 + 外观。`store.tab` 落到这个列表外时回落到 flow。 */
const ENABLED_TABS: EditorTabKey[] = ["flow", "config", "validate", "assets", "characters", "agent-chat", "agent-settings", "appearance"];

// editorBg 整体持久化（含 blur/dim 滑块改动）
watch(
  () => store.editorBg,
  (v) => localStorage.setItem(EDITOR_BG_LS_KEY, JSON.stringify(v)),
  { deep: true },
);

// ============================================================
// getters
// ============================================================

export const scriptKey = computed(() => store.detail?.package.key ?? "");
export const chapters = computed(() => store.detail?.chapters ?? []);
export const canUndo = computed(() => store.undoStack.length > 0);
export const canRedo = computed(() => store.redoStack.length > 0);

/**
 * 原版这些是 Pinia 的 getters，组件里写 `store.xxx`；本项目 store 是 reactive 对象，
 * getters 只能是独立导出的 computed —— 五个字段编辑器统一改读下面这几个名字。
 */

/** 当前剧本的章节选项：全部章节 + 「结束」哨兵 */
export const chapterOptions = computed<{ value: string; label: string }[]>(() => [
  ...chapters.value.map((c) => ({ value: c.id, label: c.name ? `${c.name}（${c.id}）` : c.id })),
  { value: "end", label: "剧本结束" },
]);

/** 角色选项：MAIN + 剧本内 characters */
export const characterOptions = computed<{ value: string; label: string }[]>(() => {
  const out = [{ value: "MAIN", label: "MAIN（当前主角）" }];
  for (const c of store.detail?.characters ?? []) {
    out.push({ value: c.roleKey, label: c.aiName || c.roleKey });
  }
  return out;
});

/** 已知变量名（来自校验报告的 variables），供条件/赋值输入框自动补全 */
export const variables = computed<string[]>(() => store.report?.variables ?? []);

/** 本剧本素材索引 */
export const assets = computed<AssetIndex>(
  () => store.detail?.assets ?? { background: [], music: [], sound: [], ambient: [], pic: [] },
);

/**
 * 全局素材：本项目没有全局素材接口（后端 editor_list_global_assets 无对应端点），
 * 恒空。FieldRow 因此只渲染「导入到剧本」的下拉，不渲染「导入到全局」按钮。
 */
export const globalAssets = computed<AssetIndex>(() => ({
  background: [],
  music: [],
  sound: [],
  ambient: [],
  pic: [],
}));

/** 章节跳转边，来自校验报告（后端 ScriptValidator.checkGraph 已算好，前端不重复图算法） */
export const edges = computed<ChapterEdge[]>(() => store.report?.edges ?? []);

/** 开场章节 id：story_config.intro_chapter 去掉可能的 .yaml 后缀，缺省 main */
export const introChapter = computed<string>(() => {
  const raw = store.detail?.storyConfig?.intro_chapter;
  return typeof raw === "string" ? raw.replace(/\.yaml$/, "") : "main";
});

/** 事件类型 → schema 定义，EventRow / EventPropertyPanel 按 typeKey 查色和字段 */
export const eventSpecs = computed<Record<string, EventSpec>>(() => {
  const out: Record<string, EventSpec> = {};
  for (const e of store.schema?.events ?? []) out[e.typeKey] = e;
  return out;
});

/** 当前章节的诊断，按事件下标归组，供时间线打标与字段红字 */
export const chapterDiagnostics = computed<Record<number, Diagnostic[]>>(() => {
  const out: Record<number, Diagnostic[]> = {};
  const chapter = store.chapter;
  if (!store.report || !chapter) return out;
  for (const d of store.report.diagnostics) {
    if (d.chapter !== chapter.id || d.eventIndex === undefined) continue;
    (out[d.eventIndex] ??= []).push(d);
  }
  return out;
});

/** 按章节聚合的错误/警告数，供流程图卡片角标 */
export const diagnosticsByChapter = computed<
  Record<string, { errors: number; warns: number; infos: number }>
>(() => {
  const out: Record<string, { errors: number; warns: number; infos: number }> = {};
  for (const c of store.detail?.chapters ?? []) out[c.id] = { errors: 0, warns: 0, infos: 0 };
  for (const d of store.report?.diagnostics ?? []) {
    if (!d.chapter) continue;
    const slot = (out[d.chapter] ??= { errors: 0, warns: 0, infos: 0 });
    if (d.severity === "error") slot.errors++;
    else if (d.severity === "warn") slot.warns++;
    else slot.infos++;
  }
  return out;
});

/** 剧本级（不属于任何章节）的诊断，校验页顶部「剧本整体」一块用 */
export const scriptDiagnostics = computed<Diagnostic[]>(() =>
  (store.report?.diagnostics ?? []).filter((d) => !d.chapter),
);

/**
 * MAIN 的展示名。
 * 本项目 `boundCharacterFolder` 恒 null（没有 character/<角色>/ 布局），所以跳过原版
 * 「全局角色库匹配」那一段，直接走原版最后的兜底：story_config.script_settings.user_name
 * 有值就显示玩家名，否则字面 MAIN（原版 i18n 兜底值也是 "MAIN"）。
 */
export const mainRoleDisplayName = computed<string>(() => {
  const settings = store.detail?.storyConfig?.script_settings;
  const userName =
    settings && typeof settings === "object"
      ? (settings as Record<string, unknown>).user_name
      : undefined;
  if (typeof userName === "string" && userName.trim()) return userName.trim();
  return "MAIN";
});

// ============================================================
// 计时器（模块级，不进 reactive：不是渲染数据）
// ============================================================

let saveTimer: number | undefined;
let validateTimer: number | undefined;
/** 校验请求在飞时再来一次只记一个 flag，避免连点「重新校验」打出一串请求 */
let validateInFlight = false;
let validateQueued = false;

// ============================================================
// 提示
// ============================================================

export function notifyError(message: string, err: unknown): void {
  void alertDialog(message, String(err));
}

// ============================================================
// 读取
// ============================================================

export async function initEditor(): Promise<void> {
  if (!store.schema) {
    try {
      store.schema = await getSchema();
    } catch (e) {
      notifyError("读取编辑器 schema 失败", e);
    }
  }
  await loadScripts();
}

export async function loadScripts(): Promise<void> {
  store.loading = true;
  try {
    store.scripts = await listScripts();
  } catch (e) {
    notifyError("读取剧本列表失败", e);
  } finally {
    store.loading = false;
  }
}

export async function openScript(key: string): Promise<void> {
  await flushPendingSave();
  store.loading = true;
  try {
    store.detail = await readScript(key);
    store.level = "flow";
    store.chapter = null;
    store.selectedEvent = 0;
    resetHistory();
    if (!ENABLED_TABS.includes(store.tab)) store.tab = "flow";
    // 章节卡片上的出口描述来自 report.edges，所以打开就要跑一次校验
    void runValidation();
  } catch (e) {
    notifyError("打开剧本失败", e);
  } finally {
    store.loading = false;
  }
}

export function closeScript(): void {
  store.detail = null;
  store.chapter = null;
  store.report = null;
  store.level = "flow";
  // 回到列表时 Tab 也归位，否则顶栏会高亮「剧本设置」而屏幕上是剧本列表
  store.tab = "flow";
  resetHistory();
}

/**
 * 打开一章并进入章节编辑态。
 *
 * 选中项用 `firstVisibleIndex` 而不是 0：官方剧本每章开头都是一个被折叠的转场块，
 * 选 0 会出现「右侧显示字段、左侧那行是收起的」。
 *
 * 顺手切到 flow Tab：从校验页/章节预览跳进来时，不切的话内容打开了但用户还看着旧页面。
 */
export async function openChapter(chapterId: string): Promise<boolean> {
  const key = scriptKey.value;
  if (!key) return false;
  await flushPendingSave();
  try {
    const content = await readChapter(key, chapterId);
    store.chapter = content;
    resetHistory();
    store.selectedEvent = firstVisibleIndex(content.events, store.foldCompounds);
    store.tab = "flow";
    store.level = "chapter";
    return true;
  } catch (e) {
    notifyError("打开章节失败", e);
    return false;
  }
}

/**
 * 回到章节流程图。
 *
 * 刻意先落盘再校验：流程图画的是 `report.edges`，而 runValidation 读的是**磁盘**。
 * 改完「下一章」立刻退回来时，自动保存的 800ms 与校验的 2.5s 都还没到点，图上仍是旧连线。
 */
export async function backToFlow(): Promise<void> {
  store.level = "flow";
  await flushPendingSave();
  await runValidation();
}

// ============================================================
// 保存
// ============================================================

export function setChapterName(name: string): void {
  const chapter = store.chapter;
  if (!chapter) return;
  pushUndo();
  chapter.name = name.trim() === "" ? chapter.id : name;
  markDirty();
}

export function markDirty(): void {
  store.dirty = true;
  if (saveTimer) window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => {
    saveTimer = undefined;
    void saveNow();
  }, AUTOSAVE_DELAY);
  // 校验读的是磁盘，比自动保存慢 2.5s 起跳，落盘之后再扫，看到的就是新状态
  scheduleValidation();
}

export async function saveNow(): Promise<void> {
  const key = scriptKey.value;
  const chapter = store.chapter;
  if (!key || !chapter) return;
  if (saveTimer) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
  store.saving = true;
  try {
    await writeChapter(key, chapter.id, { name: chapter.name, events: chapter.events });
    store.dirty = false;
    store.saving = false;
    store.lastSavedAt = Date.now();
    syncChapterSummary();
  } catch (e) {
    store.saving = false;
    notifyError("保存章节失败", e);
  }
}

/** 有未落盘的改动就立刻写。切剧本 / 离开编辑器前调。 */
export async function flushPendingSave(): Promise<void> {
  if (!store.dirty) return;
  await saveNow();
}

/** 把当前章节的名字 / 事件数同步回流程图用的摘要，免得卡片上还是旧数字 */
function syncChapterSummary(): void {
  const chapter = store.chapter;
  if (!chapter || !store.detail) return;
  const summary = store.detail.chapters.find((c) => c.id === chapter.id);
  if (!summary) return;
  summary.eventCount = chapter.events.length;
  summary.name = chapter.name;
}

/**
 * 改一个事件的某个字段。空串 / undefined 表示「删掉这个键」（引擎的「不设置」语义）。
 *
 * 这条「空串 → 删键」是原版 `patch()` 的核心语义（CompositeField 里同样是
 * `v === "" || v === false` 就 delete）。写成 `ev[key] = ""` 会让引擎把空串当成
 * 一个真实值，`field.required_missing` 之类的校验结果就跟作者看到的表单不一致了。
 */
export function setEventField(index: number, key: string, value: unknown): void {
  const ch = store.chapter;
  if (!ch || !ch.events[index]) return;
  pushUndo();
  const ev = ch.events[index];
  if (value === "" || value === null || value === undefined) delete ev[key];
  else ev[key] = value;
  markDirty();
}

/** 整体替换一个事件（切换事件类型时用） */
export function replaceEvent(index: number, next: ScriptEventData): void {
  const ch = store.chapter;
  if (!ch || !ch.events[index]) return;
  pushUndo();
  ch.events[index] = next;
  markDirty();
}

/** 新建一个符合 schema 的空事件骨架（原版 actions.ts:417 逐字对照） */
export function blankEvent(typeKey: string): ScriptEventData {
  const spec = eventSpecs.value[typeKey];
  const ev: ScriptEventData = { type: typeKey };
  if (!spec) return ev;
  for (const f of spec.fields) {
    if (!f.required || !f.enabled) continue;
    switch (f.kind) {
      case "choice_options":
        ev[f.key] = [{ text: "", actions: [] }];
        break;
      case "branch_options":
        ev[f.key] = [];
        break;
      case "var_options":
        ev[f.key] = [{ actions: [{ type: "set_var", content: "" }] }];
        break;
      case "bool":
        ev[f.key] = false;
        break;
      case "select":
        ev[f.key] = f.options?.[0] ?? "";
        break;
      case "character":
        ev[f.key] = "MAIN";
        break;
      default:
        ev[f.key] = "";
    }
  }
  // 章节结束默认指向「剧本结束」，否则一插入就报「linear 但没写下一章」
  if (typeKey === "chapter_end") ev.next_chapter = "end";
  return ev;
}

/** 默认插到**最后一条 chapter_end 之前**，不是数组末尾（理由见原版注释） */
function defaultInsertIndex(): number {
  const events = store.chapter?.events ?? [];
  for (let i = events.length - 1; i >= 0; i--) {
    if (events[i]?.type === "chapter_end") return i;
  }
  return events.length;
}

export function insertEvent(typeKey: string, at?: number): void {
  const chapter = store.chapter;
  if (!chapter) return;
  pushUndo();
  const index = at ?? defaultInsertIndex();
  chapter.events.splice(index, 0, blankEvent(typeKey));
  store.selectedEvent = index;
  markDirty();
}

export function removeEvent(index: number): void {
  const chapter = store.chapter;
  if (!chapter) return;
  pushUndo();
  chapter.events.splice(index, 1);
  store.selectedEvent = Math.max(0, Math.min(index, chapter.events.length - 1));
  markDirty();
}

export function duplicateEvent(index: number): void {
  const chapter = store.chapter;
  if (!chapter) return;
  const src = chapter.events[index];
  if (!src) return;
  pushUndo();
  chapter.events.splice(index + 1, 0, JSON.parse(JSON.stringify(src)) as ScriptEventData);
  store.selectedEvent = index + 1;
  markDirty();
}

export function moveEvent(from: number, to: number): void {
  const chapter = store.chapter;
  if (!chapter) return;
  if (from === to || from < 0 || to < 0) return;
  if (from >= chapter.events.length || to >= chapter.events.length) return;
  pushUndo();
  const [ev] = chapter.events.splice(from, 1);
  chapter.events.splice(to, 0, ev);
  store.selectedEvent = to;
  markDirty();
}

/**
 * 整段移动（折叠块的 ▲▼）：先抠出来再插入会让 dest 往前挪，
 * 所以「往后搬」的情况要显式减掉抠走的长度。原版 actions.ts:512 逐字对照。
 */
export function moveEventRange(from: number, count: number, dest: number): void {
  const chapter = store.chapter;
  if (!chapter) return;
  const list = chapter.events;
  if (count < 1 || from < 0 || from + count > list.length) return;
  if (dest >= from && dest < from + count) return; // 落回自己身上
  pushUndo();
  const block = list.splice(from, count);
  const at = dest > from ? dest - count : dest;
  list.splice(at, 0, ...block);
  store.selectedEvent = at;
  markDirty();
}

/**
 * 保存剧本设置（story_config）。
 *
 * 后端只回 204，没有回读，所以本地把 `detail.storyConfig` 合并更新 —— 但**不动**
 * `detail.package` 与 `chapters`：`script_name` 改了以后侧边栏 / 面包屑仍是打开时
 * 的那份快照，下次重新打开剧本才会刷新（避免列表项与当前编辑对象错位）。
 */
export async function saveStoryConfig(config: StoryConfig): Promise<void> {
  const key = scriptKey.value;
  if (!key) return;
  store.saving = true;
  try {
    await writeConfig(key, config);
    // 后端只回 204，本地把 detail.storyConfig 合并更新；chapters 里 intro 之类的引用不改
    if (store.detail) store.detail.storyConfig = { ...store.detail.storyConfig, ...config };
    store.dirty = false;
    store.lastSavedAt = Date.now();
  } catch (e) {
    await notifyError("保存剧本设置失败", e);
  } finally {
    store.saving = false;
  }
}

// ============================================================
// 撤销 / 重做
// ============================================================

function snapshot(): UndoFrame | null {
  const chapter = store.chapter;
  if (!chapter) return null;
  return {
    chapterId: chapter.id,
    name: chapter.name,
    events: JSON.parse(JSON.stringify(chapter.events)) as ScriptEventData[],
    selected: store.selectedEvent,
  };
}

export function pushUndo(): void {
  const frame = snapshot();
  if (!frame) return;
  store.undoStack.push(frame);
  if (store.undoStack.length > UNDO_LIMIT) store.undoStack.shift();
  // 新的改动让 redo 失效
  store.redoStack.length = 0;
}

export function undo(): void {
  const chapter = store.chapter;
  if (!chapter || store.undoStack.length === 0) return;
  const frame = store.undoStack.pop();
  const current = snapshot();
  if (current) store.redoStack.push(current);
  if (frame) applyFrame(frame);
}

export function redo(): void {
  const chapter = store.chapter;
  if (!chapter || store.redoStack.length === 0) return;
  const frame = store.redoStack.pop();
  const current = snapshot();
  if (current) store.undoStack.push(current);
  if (frame) applyFrame(frame);
}

function applyFrame(frame: UndoFrame): void {
  const chapter = store.chapter;
  if (!chapter || frame.chapterId !== chapter.id) return;
  chapter.name = frame.name ?? chapter.name;
  chapter.events = frame.events;
  store.selectedEvent = Math.min(frame.selected, Math.max(0, frame.events.length - 1));
  markDirty();
}

function resetHistory(): void {
  store.undoStack.length = 0;
  store.redoStack.length = 0;
  store.dirty = false;
  if (saveTimer) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
}

// ============================================================
// 校验
// ============================================================

/** 结构改动后的防抖校验（2500ms）。手动点「重新校验」请直接调 runValidation()。 */
export function scheduleValidation(): void {
  if (validateTimer) window.clearTimeout(validateTimer);
  validateTimer = window.setTimeout(() => {
    validateTimer = undefined;
    void runValidation();
  }, VALIDATE_DELAY);
}

/**
 * 跑一次校验。
 *
 * 防抖策略：进入时先取消排队中的 scheduleValidation，保证「立即执行」语义；
 * 若已有请求在飞，则只记一个 queued 标志，等它回来再补跑一次 —— 连点按钮
 * 最多打两次请求，而不是一串。
 */
export async function runValidation(): Promise<void> {
  if (validateTimer) {
    window.clearTimeout(validateTimer);
    validateTimer = undefined;
  }
  const key = scriptKey.value;
  if (!key) return;
  if (validateInFlight) {
    validateQueued = true;
    return;
  }
  validateInFlight = true;
  try {
    store.report = await validateScript(key);
  } catch (e) {
    notifyError("校验失败", e);
  } finally {
    validateInFlight = false;
    if (validateQueued) {
      validateQueued = false;
      void runValidation();
    }
  }
}

// ============================================================
// 章节增删
// ============================================================

/** 重新读一遍剧本详情，只刷新章节列表（storyConfig 等其它字段保持引用不变） */
async function refreshChapters(): Promise<void> {
  const key = scriptKey.value;
  if (!key) return;
  try {
    const detail = await readScript(key);
    if (store.detail) store.detail.chapters = detail.chapters;
  } catch (e) {
    notifyError("刷新章节列表失败", e);
  }
}

export async function createChapter(chapterId: string, name: string): Promise<void> {
  const key = scriptKey.value;
  if (!key) return;
  try {
    await apiCreateChapter(key, chapterId, name);
    await refreshChapters();
    await runValidation();
    notify("已新建章节");
  } catch (e) {
    notifyError("新建章节失败", e);
  }
}

export async function removeChapter(chapterId: string): Promise<void> {
  const key = scriptKey.value;
  if (!key) return;
  const ok = await confirmDialog("删除章节", `确定删除章节「${chapterId}」吗？删除后不可恢复。`);
  if (!ok) return;
  try {
    await apiDeleteChapter(key, chapterId);
    if (store.chapter?.id === chapterId) {
      store.chapter = null;
      store.level = "flow";
      resetHistory();
    }
    await refreshChapters();
    await runValidation();
  } catch (e) {
    notifyError("删除章节失败", e);
  }
}

/**
 * 新建剧本。原版顺序：建完 → 刷新列表 → 直接打开新剧本（作者点了「新建」就是想马上写）。
 *
 * @returns 是否成功。失败在这里弹错误窗并返回 false，由调用方（弹窗）决定关不关 ——
 *          关了就丢掉作者刚填的内容，不如留在原地把编码改掉再试一次。
 */
export async function createScript(body: CreateScriptBody): Promise<boolean> {
  store.saving = true;
  try {
    const pkg = await apiCreateScript(body);
    await loadScripts();
    await openScript(pkg.key);
    notify("已新建剧本");
    return true;
  } catch (e) {
    notifyError("新建剧本失败", e);
    return false;
  } finally {
    store.saving = false;
  }
}

/**
 * 删除整个剧本。
 *
 * 与原版一致：先确认 → 若删的是当前打开的剧本先 closeScript（否则页面还挂着一份
 * 已不存在的数据，下一次自动保存会 404）→ 删库 → 刷新列表。
 * 原版删完还要 rescanScripts 让引擎忘掉这个剧本；本项目引擎按库读，没有这一步。
 */
export async function deleteScript(key: string, displayName: string): Promise<void> {
  const ok = await confirmDialog(
    "删除剧本",
    `确定删除剧本「${displayName}」吗？\n\n该剧本的全部章节会被永久删除，无法恢复。`,
  );
  if (!ok) return;
  try {
    if (scriptKey.value === key) closeScript();
    await apiDeleteScript(key);
    await loadScripts();
    notify("已删除剧本");
  } catch (e) {
    notifyError("删除剧本失败", e);
  }
}

// ============================================================
// 试玩（E5b）
// ============================================================

/**
 * 打开试玩层。只置状态，真正的建场（POST /preview）由 `PreviewStage` 的 watch 做
 * ——必须先挂上渲染层再开演，否则引擎开头的几句会打在空舞台上（原版同构：
 * 搭台在 startPreview，组件的 watch 只负责放行事件队列）。
 */
export function startPreview(fromChapter = ""): void {
  if (!store.detail) return;
  store.previewFromChapter = fromChapter;
  store.previewError = "";
  store.previewAutoEnd = false;
  store.previewing = true;
}

/** 关试玩层；删场与清舞台由 `PreviewStage` 的 watch 收尾。 */
export function stopPreview(): void {
  store.previewing = false;
}

// ============================================================
// 编辑器背景（E6a）
// ============================================================

/** 换背景路径；bgVersion++ 强制 <img>/url() 重取（对齐原版 ?v= 缓存击穿） */
export function setEditorBgPath(path: string): void {
  store.editorBg = { ...store.editorBg, path };
  store.bgVersion++;
}

/** 回默认值并清掉持久化 */
export function resetEditorBg(): void {
  store.editorBg = { ...DEFAULT_EDITOR_BG };
  store.bgVersion++;
  localStorage.removeItem(EDITOR_BG_LS_KEY);
}

/**
 * 上传裁剪后的背景 dataURL，成功后立刻启用，返回 url；失败返回 null（对齐原版语义）。
 */
export async function uploadEditorBgData(dataUrl: string, name: string): Promise<string | null> {
  try {
    const { url } = await apiUploadEditorBg(dataUrl, name);
    setEditorBgPath(url);
    notify("背景图已设置");
    return url;
  } catch (e) {
    notifyError("设置背景图失败", e);
    return null;
  }
}

/** 拉取全局背景清单（内置 + 剧本自带 + 编辑器上传） */
export async function refreshGlobalBgFiles(): Promise<void> {
  try {
    store.globalBgFiles = await listGlobalBackgrounds();
  } catch (e) {
    notifyError("加载全局背景列表失败", e);
  }
}

