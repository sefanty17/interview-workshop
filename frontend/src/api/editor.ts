/**
 * 剧本编辑器 API —— 对齐后端 cn.edu.zjut.interview.api.ScriptEditorController（11 个端点）。
 *
 * 与原版（Tauri invoke 的 26 个 editor_* 命令）的差异：
 * - 本批做 11 个：schema / 剧本列表 / 新建剧本 / 剧本详情 / 删除剧本 / 章节读写增删 / 配置写 / 校验。
 * - 章节 id 走 query（`?id=`）而不是路径段：章节 id 可能含 `/`（如 `Intro/main`）。
 */

export type FieldKind =
  | "text" | "textarea" | "number" | "bool" | "select"
  | "character" | "emotion" | "chapter" | "asset"
  | "choice_options" | "branch_options" | "var_options"
  | "condition" | "deprecated";

export interface FieldSpec {
  key: string;
  label: string;
  kind: FieldKind;
  required: boolean;
  /** 仅 kind === "asset" 时有值：background / pic / music / sound / ambient */
  assetKind?: string;
  /** kind === "select" 的候选项；与 optionLabels 对齐（可能为空，此时直接显示 options 原文） */
  options?: string[];
  optionLabels?: string[];
  placeholder?: string;
  /** 引擎真实默认值的人类可读描述，供「不设置」时展示 */
  defaultDesc?: string;
  hint?: string;
  /** false 时禁用并展示 hint */
  enabled: boolean;
}

export interface EventSpec {
  typeKey: string;
  label: string;
  /** 事件面板的归类：叙事 / AI / 交互 / 流程 / 演出 / 声音 / 成就 */
  category: string;
  /** 时间线上的语义色，十六进制 */
  color: string;
  fields: FieldSpec[];
}

export interface ActionSpec { typeKey: string; label: string; hint: string; allowedIn: string[] }
export interface UnlockConditionSpec { typeKey: string; label: string; fields: FieldSpec[] }
export interface ConditionSyntax { supported: string[]; unsupported: string[]; note: string }

export interface ScriptSchema {
  events: EventSpec[];
  commonFields: FieldSpec[];
  storyConfigFields: FieldSpec[];
  actionTypes: ActionSpec[];
  unlockConditionTypes: UnlockConditionSpec[];
  placeholderFields: string[];
  conditionSyntax: ConditionSyntax;
}

export interface ScriptPackage {
  key: string;
  layout: string;
  folderName: string;
  boundCharacterFolder?: string;
  scriptName: string;
  description: string;
  isAdventure: boolean;
  chapterCount: number;
  /** false = 引擎没加载（本项目恒 true） */
  loadedByEngine: boolean;
}

export interface ChapterSummary {
  id: string;
  name?: string;
  /** 子目录（章节 id 含 `/` 时的前缀），顶层章节为 undefined */
  group?: string;
  eventCount: number;
}

export interface AssetIndex {
  background: string[]; music: string[]; sound: string[]; ambient: string[]; pic: string[];
}

export interface ScriptCharacter {
  folder: string; roleKey: string; aiName: string; systemPrompt: string;
  emotions: string[]; clothes: string[]; previewImage?: string; globalAvatar: boolean;
}

/** story_config 的摊平对象，键名沿用原版 snake_case */
export interface StoryConfig {
  script_name?: string;
  description?: string;
  recommand_start?: string;
  intro_chapter?: string;
  job_code?: string;
  level?: string;
  company_style?: string;
  interviewer_code?: string;
  script_settings?: Record<string, unknown>;
  [k: string]: unknown;
}

export interface ScriptDetail {
  package: ScriptPackage;
  storyConfig: StoryConfig;
  chapters: ChapterSummary[];
  assets: AssetIndex;
  characters: ScriptCharacter[];
}

/** 一个事件。字段名与 YAML 一致（camelCase 与 snake_case 混用是引擎现状） */
export type ScriptEventData = { type: string; [k: string]: unknown };

export interface ChapterContent {
  id: string;
  name: string;
  events: ScriptEventData[];
}

export type Severity = "error" | "warn" | "info";

export interface Diagnostic {
  severity: Severity;
  code: string;
  message: string;
  /** 章节 id；剧本级问题为 undefined */
  chapter?: string;
  /** 事件下标（0 起） */
  eventIndex?: number;
  field?: string;
}

export interface ChapterEdge {
  from: string;
  to: string;
  isEnd: boolean;
  /** 分支条件 / AI 分支名；linear 时无 */
  label?: string;
  endType: string;
}

export interface ValidationReport {
  diagnostics: Diagnostic[];
  errorCount: number;
  warnCount: number;
  infoCount: number;
  variables: string[];
  edges: ChapterEdge[];
}

/** 章节写回体的形状（原版 `extra` 本项目无对应列，忽略） */
export interface ChapterWriteBody {
  name: string;
  events: ScriptEventData[];
}

/** 编辑器 API 失败：`status` 有值 = 后端明确拒绝，`message` 是后端中文文案。 */
export class EditorApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "EditorApiError";
    this.status = status;
  }
}

/** 后端错误体是 `{error, message}`（见 ScriptEditorController 的两个 ExceptionHandler）。 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { error?: string; message?: string };
        const message = body.message ?? body.error;
        if (message) return message;
      } catch {
        return text.slice(0, 200);
      }
    }
  } catch {
    // 读 body 失败就退回状态码
  }
  return `HTTP ${response.status}`;
}

/** 发请求并做统一的失败包装；返回原始 response 供调用方决定怎么读 body。 */
async function send(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new EditorApiError(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) throw new EditorApiError(await readError(response), response.status);
  return response;
}

async function callJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  return (await response.json()) as T;
}

async function callVoid(path: string, init?: RequestInit): Promise<void> {
  await send(path, init);
}

/** 写操作的公共请求参数（只有写操作带 body）。 */
function jsonBody(value: unknown): RequestInit {
  return {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(value),
  };
}

/** 章节 id 可能含 `/`，必须整体转义后放进 query。 */
const q = (value: string): string => encodeURIComponent(value);

const base = (key: string): string => `/api/editor/scripts/${q(key)}`;

// ============================================================
// 9 个端点
// ============================================================

export function getSchema(): Promise<ScriptSchema> {
  return callJson<ScriptSchema>("/api/editor/schema");
}

export function listScripts(): Promise<ScriptPackage[]> {
  return callJson<ScriptPackage[]>("/api/editor/scripts");
}

export function readScript(key: string): Promise<ScriptDetail> {
  return callJson<ScriptDetail>(base(key));
}

export function readChapter(key: string, chapterId: string): Promise<ChapterContent> {
  return callJson<ChapterContent>(`${base(key)}/chapter?id=${q(chapterId)}`);
}

export async function writeChapter(
  key: string,
  chapterId: string,
  body: ChapterWriteBody,
): Promise<void> {
  await callVoid(`${base(key)}/chapter?id=${q(chapterId)}`, jsonBody(body));
}

export function createChapter(
  key: string,
  chapterId: string,
  name: string,
): Promise<ChapterContent> {
  return callJson<ChapterContent>(`${base(key)}/chapter`, {
    ...jsonBody({ chapterId, name }),
    method: "POST",
  });
}

export async function deleteChapter(key: string, chapterId: string): Promise<void> {
  await callVoid(`${base(key)}/chapter?id=${q(chapterId)}`, { method: "DELETE" });
}

export async function writeConfig(key: string, config: StoryConfig): Promise<void> {
  await callVoid(`${base(key)}/config`, jsonBody({ config }));
}

export function validateScript(key: string): Promise<ValidationReport> {
  return callJson<ValidationReport>(`${base(key)}/validate`);
}

// ============================================================
// 剧本增删（E5a）
// ============================================================

/** 新建剧本的请求体（后端 EditorCreateScriptRequest） */
export interface CreateScriptBody {
  code: string;
  scriptName: string;
  description?: string;
  jobCode?: string;
  level?: string;
  companyStyle?: string;
  interviewerCode?: string;
  introChapter?: string;
}

export function createScript(body: CreateScriptBody): Promise<ScriptPackage> {
  return callJson<ScriptPackage>("/api/editor/scripts", {
    ...jsonBody(body),
    method: "POST",
  });
}

export async function deleteScript(key: string): Promise<void> {
  await callVoid(base(key), { method: "DELETE" });
}

// ============================================================
// 试玩场次（E5b）
// ============================================================

/**
 * 试玩场次（后端 `dto/PreviewSessionView`）。
 *
 * 原版试用全局 `PreviewSession` 做「快照/还原」；本项目每场次的状态天然隔离在
 * interview_session 行里，所以退化为「专用试玩场次（preview=true）+ 退出即删除」：
 * 玩家的真实场次一个字节都没被碰过。
 */
export interface PreviewSessionView {
  sessionId: number;
  scriptCode: string;
  /** 请求里带的起始章节；null/空 = 从剧本的 intro_chapter 开演 */
  fromChapter: string | null;
  interviewerCode: string;
  interviewerName: string;
  jobCode: string;
}

/** 建一个试玩场次（后端顺带清掉上一轮残留的试玩场次）。 */
export function createPreview(key: string, fromChapter?: string): Promise<PreviewSessionView> {
  return callJson<PreviewSessionView>(`${base(key)}/preview`, {
    // fromChapter 为空时不下发该键（后端按 intro_chapter 开演）
    ...jsonBody(fromChapter ? { fromChapter } : {}),
    method: "POST",
  });
}

/** 删掉试玩场次（退出试玩时调；后端对 preview=false 的场次是 no-op）。 */
export async function discardPreview(sessionId: number): Promise<void> {
  await callVoid(`/api/editor/preview/${sessionId}`, { method: "DELETE" });
}

// ============================================================
// 外观（E6a）：编辑器背景上传 / 全局背景列表
// ============================================================

/** 网页版 AssetFile：Tauri 绝对路径换成可访问的 HTTP URL（实现方式差异） */
export interface AssetFileWeb {
  name: string;
  url: string;
  size: number;
}

/** 上传裁剪后的背景（dataURL + 文件名），返回可直接访问的 url。 */
export function uploadEditorBg(dataUrl: string, name: string): Promise<{ url: string }> {
  return callJson<{ url: string }>("/api/editor/appearance/background", {
    ...jsonBody({ dataUrl, name }),
    method: "POST",
  });
}

/** 合并内置 / 剧本自带 / 编辑器上传三处的全局背景清单。 */
export function listGlobalBackgrounds(): Promise<AssetFileWeb[]> {
  return callJson<AssetFileWeb[]>("/api/editor/appearance/backgrounds");
}
