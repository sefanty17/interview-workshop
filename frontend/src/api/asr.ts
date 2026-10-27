/**
 * 语音识别（ASR）API。
 *
 * 上游是什么：`api/services/asr.ts` 用 `invoke()` 调 Tauri 命令（进程内 IPC）。
 * 本项目为什么不同：后端是 Spring，接口在 `/api/speech/asr/*`；写法与
 * `api/knowledge.ts` 一致——原生 fetch、失败抛带中文的 `AsrError`，不引 axios。
 *
 * 端点（后端 `AsrController`，已实测通过）：
 * ```
 * GET    /api/speech/asr/settings                  → AsrSettings（snake_case）
 * PUT    /api/speech/asr/settings                  → 整份覆盖
 * GET    /api/speech/asr/providers                 → ProviderInfo[]
 * GET    /api/speech/asr/models?providerId=&region= → ModelInfo[]
 * GET    /api/speech/asr/status                    → AsrStatus
 * POST   /api/speech/asr/test                      → AsrTestResult
 * POST   /api/speech/asr/vad                       → VadBatchResult
 * POST   /api/speech/asr/recognize                 → AsrResult（multipart，字段名 wav）
 * POST   /api/speech/asr/sessions                  → 开会话；已有会话 409
 * POST   /api/speech/asr/sessions/stop             → 结束会话（幂等）
 * DELETE /api/speech/asr/sessions                  → 取消会话（幂等）
 * ```
 * 错误体统一 `{code:"ASR_xxx", detail:"人话", status:400}`。
 *
 * 下面的类型定义从上游原样搬入（与后端 JSON 一一对应，字段一个都没改）；
 * 仅 `AsrStatus` 补了后端多返回的四个键（见该接口注释）。
 */

import { AsrError, asrErrorText, parseAsrError } from "../utils/asrError";

export type AsrSource = "button" | "auto";
export type SendMode = "fill_only" | "auto_send";
export type AsrPhase = "idle" | "recording" | "recognizing";

export interface AsrResult {
  text: string;
  language?: string;
  confidence?: number;
  provider_id: string;
}

export interface ProviderConfig {
  /**
   * 按地域分存的 API Key，键为地域 id（"cn-beijing" / "ap-southeast-1"）。
   *
   * 各地域的 Key 互相独立、不能混用；设置页只显示当前地域的那一个框，
   * 读写都落在这里（`api_keys[当前地域]`）。
   */
  api_keys?: Record<string, string>;
  /** 历史字段：分地域存储之前的唯一 Key。后端仍作为兜底读取，设置页不再写入 */
  api_key: string;
  /** 非实时（同步）端点 */
  endpoint: string;
  /** 实时（流式）WebSocket 端点；与 endpoint 独立（协议不同，用户可能只覆盖其一） */
  ws_endpoint?: string;
  model: string;
  /** DashScope 地域 id（"cn-beijing" / "ap-southeast-1"）；空/未知 = 默认地域 */
  region?: string;
  // 热词不在这里：热词逐角色（存数据库），按调用经识别命令的 `hotwords`
  // 参数传入，见下方 HotwordInput。
}

/** 模型对应的端点类型：选中该模型时把对应端点字段填成当前地域的默认值 */
export type EndpointKind = "http" | "ws";

export interface ModelInfo {
  id: string;
  display_name: string;
  supports_streaming: boolean;
  is_default: boolean;
  /**
   * 端点预设类型。只给类型不给完整 URL —— 端点是地域相关的，
   * 实际地址从 activeProviderInfo.regions 按当前地域取（避免两处真相）。
   * null = 不干预端点（本地 llama-asr 的地址与模型无关）。
   */
  endpoint_kind?: EndpointKind | null;
}

/** 与后端 region.rs 的 AsrRegionInfo 对齐：某地域的端点默认值 */
export interface AsrRegionInfo {
  id: string;
  label: string;
  http_endpoint: string;
  ws_endpoint: string;
}

/** 一次识别调用的热词：纯词（默认权重）或带权重对象 */
export type HotwordInput = string | { text: string; weight?: number };

export interface AsrSettings {
  active_provider: string;
  auto_listen: boolean;
  send_mode: SendMode;
  stream_enabled: boolean;
  voice_input_enabled: boolean;
  /** VAD 静音计时（毫秒）：停止说话后静音该时长才结束一轮录音（默认 800） */
  vad_silence_ms: number;
  /** 能量监测启动缓冲期（毫秒）：TTS 播完恢复监听后该时长内不触发录音（默认 100，0=无缓冲） */
  energy_warmup_ms: number;
  /** 是否输出逐帧 VAD 能量检测日志（frame/prob/len，默认关，排查识别不触发时打开） */
  vad_debug_log: boolean;
  /** 语音输入快捷键（ShortcutBinding JSON 字符串，默认 {"key":"f8"}） */
  ptt_key: string;
  /** 失去焦点快捷键可用（全局快捷键）：窗口不在前台时快捷键仍可用（默认关） */
  ptt_global: boolean;
  provider_configs: Record<string, ProviderConfig>;

  // ── 本项目扩展字段（上游 AsrSettings 没有这 6 个）────────────────────
  // 后端的 `AsrSettings` 里是单独一段「本项目扩展字段」；这里保持同名同序。
  // 存储已通、执行未接：唤醒词常驻监听与麦克风设备枚举都不在 #8 范围，
  // 目前只有设置页读写它们（换设备/换浏览器不再丢）。

  /** 唤醒词开关 */
  wake_word_enabled: boolean;
  /** 唤醒词文本 */
  wake_word: string;
  /** 连续识别：识别完成后继续监听下一轮 */
  asr_continuous: boolean;
  /** 静音门控：低于 asr_mic_threshold 的能量不送识别 */
  asr_mic_gate: boolean;
  /** 麦克风能量门限（0~1） */
  asr_mic_threshold: number;
  /** 麦克风设备 id；"default" = 系统默认设备 */
  asr_mic_device: string;
}

/** 与后端 `provider.rs` 的 `ConfigFieldKind`（snake_case 字符串）严格对齐 */
export type ConfigFieldKind =
  | "text"
  | "password"
  | "number"
  | "boolean"
  | "select"
  /** 密码框，但值按地域分开存在 `provider_configs[id].api_keys[当前地域]` */
  | "password_map";

/** select 字段的一个选项 */
export interface AsrConfigFieldOption {
  value: string;
  label: string;
}

export interface AsrConfigField {
  key: string;
  label: string;
  kind: ConfigFieldKind;
  required: boolean;
  default_value?: string;
  placeholder?: string;
  hint?: string;
  /** 仅 kind === "select" 时有值 */
  options?: AsrConfigFieldOption[];
}

export interface ProviderInfo {
  id: string;
  display_name: string;
  /** 简短描述（设置页服务商选择旁展示） */
  description?: string;
  config_fields: AsrConfigField[];
  supports_streaming: boolean;
  /**
   * 可选地域列表（含各地域端点默认值）。空/缺省 = 该 provider 无地域概念
   * （如本地 llama-asr），不渲染地域下拉、切地域联动也不生效。
   */
  regions?: AsrRegionInfo[];
}

/**
 * VAD 事件（上游 `VadEvent`，字段原样）。
 *
 * 上游走 Tauri 事件通道推送，本项目改成随 `/vad` 响应体同步返回（后端
 * `VadEventView` 同名同值）；后端 `turn_sealed` 还多带 `start_frame`/`end_frame`，
 * 前端不用，故不加进类型（多出来的 JSON 键不影响反序列化）。
 */
export interface VadEvent {
  type: "speech_started" | "silence_started" | "turn_candidate" | "turn_sealed";
  silence_ms?: number;
}

/**
 * 一批 VAD 处理的响应体（本项目新增，上游无对应物）。
 *
 * 为什么需要：上游 `asr_vad_process_chunk` 返回 void、事件走通道；浏览器没有通道，
 * 事件只能跟着响应体回来，否则「停止说话」永远收不到。
 */
export interface VadBatchResult {
  /** 本批最后一帧的语音概率（0~1） */
  speech_prob: number;
  /** 处理完本批后的帧号（样本 = frame × 512） */
  frame: number;
  /** 本批发生的全部事件，可能为空 */
  events: VadEvent[];
}

/**
 * ASR 运行时状态（设置页状态面板）。
 *
 * 前两个字段来自上游 `AsrStatus`；后四个是本项目后端多返回的（`AsrSessionService.AsrStatus`），
 * 状态面板需要 `provider_ready` 判断「配好了没有」、需要 `session_id` 对齐会话。
 */
export interface AsrStatus {
  /** VAD 模型是否加载成功（session 存在 = init_asr 完成） */
  vad_loaded: boolean;
  /** 全局快捷键注册是否健康（ptt_global=true 且实际已注册） */
  ptt_global_ok: boolean;
  /** 当前生效的 provider id */
  active_provider: string;
  /** 服务端记录的会话来源（button / auto）；无会话为 null */
  active_source: AsrSource | null;
  /** 服务端会话 id；无会话为 null */
  session_id: string | null;
  /** 当前 provider 的凭据/配置是否齐全（缺 key 时 false） */
  provider_ready: boolean;
}

/** 探活结果（后端 `AsrController.TestView`）。 */
export interface AsrTestResult {
  ok: boolean;
  /** 实际用到的 provider */
  providerId: string;
  /** 调用耗时（毫秒） */
  latencyMs: number;
  /** 识别出的文本（静音探活下通常是空串） */
  message: string;
}

/** 全局快捷键注册状态（`asr:ptt-global-status` 事件载荷的判别字段）。
 *
 * 三态必须分清：`inactive`（开关关 / 不在聊天界面）是**正常状态**，不应提示；
 * 只有 `failed` 才是真失败。历史上后端用单个 `ok: boolean` 同时表达这两者，
 * 关闭开关时设置页会误报「全局快捷键注册失败：」（reason 为空）。
 *
 * 本项目现状：浏览器拿不到窗口失焦后的按键，全局快捷键降级为「页面内快捷键」，
 * 该状态没有承载物（类型保留，供 V4 后端补端点后复用）。
 */
export type PttGlobalState = "registered" | "inactive" | "failed";

export interface PttGlobalStatus {
  state: PttGlobalState;
  /** 仅 `failed` 时非空：注册失败的原因。 */
  reason: string;
}

// ──────────────────────────── HTTP 层 ────────────────────────────

const BASE = "/api/speech/asr";

/**
 * 统一请求包装：网络层失败转成 `AsrError("ASR_NETWORK")`，
 * 非 2xx 把后端 `{code, detail, status}` 折成 `AsrError`。
 */
async function request(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, init);
  } catch (e) {
    const detail = parseAsrError(e).detail;
    throw new AsrError("ASR_NETWORK", detail || asrErrorText("ASR_NETWORK"));
  }
  if (!response.ok) throw await readAsrError(response);
  return response;
}

/**
 * 读错误体。后端错误体固定 `{code, detail, status}`；读不成 JSON（代理 502、
 * 后端 500 的默认页）时回退 `HTTP ${status}`，绝不给用户看一段 HTML。
 */
async function readAsrError(response: Response): Promise<AsrError> {
  const fallback = `HTTP ${response.status}`;
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { code?: unknown; detail?: unknown };
        if (typeof body.code === "string" && body.code) {
          const detail =
            typeof body.detail === "string" && body.detail ? body.detail : asrErrorText(body.code);
          return new AsrError(body.code, detail);
        }
      } catch {
        /* 不是 JSON：用回退文案 */
      }
    }
  } catch {
    /* 读 body 都失败，仍用回退文案 */
  }
  return new AsrError("ASR_HTTP_ERROR", fallback);
}

/** POST/PUT 的 JSON body 头（GET/DELETE 不带，避免触发 preflight 之外的多余判定）。 */
const JSON_HEADERS = { "Content-Type": "application/json" } as const;

// ──────────────────────────── 会话 ────────────────────────────

/**
 * 开启服务端会话（对应上游 `asr_start_listening`）。
 *
 * 服务端只有一个会话，已有会话时返回 409 `ASR_SESSION_BUSY`；
 * 返回值（session_id 等）本批不用，V3b 需要再放开返回类型。
 */
export async function asrStartListening(source: AsrSource): Promise<void> {
  await request("/sessions", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ source }),
  });
}

/**
 * 结束会话（对应上游 `asr_stop_listening`）。
 *
 * 后端幂等：无会话时 200 + 空体；`source` 后端不处理（只有一个会话，
 * 没有「按来源结束」的歧义），但保留参数以对齐上游签名与调用点。
 */
export async function asrStopListening(_source: AsrSource): Promise<void> {
  await request("/sessions/stop", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ source: _source }),
  });
}

/** 取消会话（不产出识别结果；切页面/离开时的清理）。幂等。 */
export async function asrCancel(): Promise<void> {
  await request("/sessions", { method: "DELETE" });
}

// ──────────────────────────── VAD ────────────────────────────

/**
 * 批量喂 VAD（对应上游 `asr_vad_process_chunk`）。
 *
 * 与上游的两点差异（都是传输方式，不是语义）：
 * 1. 上游每 512 样本（一帧）调一次进程内 IPC；这里一次收多帧（`recorder.ts`
 *    攒够 8192 样本 = 16 帧才发一批），否则 30 秒录音要 900+ 次 HTTP 往返；
 * 2. 上游返回 void、事件走 Tauri 通道；这里事件在响应体 `events` 里，
 *    由 `recorder.ts` 逐条分发给原来处理事件的回调。
 *
 * 后端只处理完整帧（`pcm.length % 512 != 0` 时尾部丢弃，不补零），
 * 所以停止录音时最后一批不足 8192 的样本也可以直接发。
 */
export async function asrVadProcessChunk(pcm: number[]): Promise<VadBatchResult> {
  const response = await request("/vad", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ pcm }),
  });
  return (await response.json()) as VadBatchResult;
}

// ──────────────────────────── 识别 ────────────────────────────

/** `hotwords` 是热词的**唯一入口**（ASR 设置页已无热词设置）：热词逐角色、
 *  存数据库，接入时在这里传入即可，provider 侧无需改动。目前一律省略。 */
export async function asrRecognizeWav(params: {
  providerId: string;
  wavBytes: number[];
  languageHint?: string | null;
  hotwords?: HotwordInput[] | null;
}): Promise<AsrResult> {
  const form = new FormData();
  // 后端 @RequestParam("wav") MultipartFile：字段名必须是 wav，文件名不影响解析
  form.append(
    "wav",
    new Blob([new Uint8Array(params.wavBytes)], { type: "audio/wav" }),
    "recording.wav",
  );
  // 有值才 append：空串会被后端当成「显式传了一个空 providerId」之外的噪音
  if (params.providerId) form.append("providerId", params.providerId);
  if (params.languageHint) form.append("languageHint", params.languageHint);
  // hotwords 后端 /recognize 目前不接收（热词按角色存库，V4 再接），不 append

  const response = await request("/recognize", { method: "POST", body: form });
  return (await response.json()) as AsrResult;
}

/** 结果流式识别（上游 llama-asr SSE：整段 WAV 上传 → partial 事件 → final）。
 *
 *  后端没有对应端点（V8 才做 WS/SSE 流式），所以显式抛错而不是打到一个
 *  不存在的 URL 上。调用点在 `session.ts` 的 `isLlamaStream()` 分支，
 *  而 `isStreamEnabled()` 恒 false → 这条路径当前不可达。 */
export async function asrRecognizeWavStream(_params: {
  providerId: string;
  wavBytes: number[];
  hotwords?: HotwordInput[] | null;
}): Promise<AsrResult> {
  throw new AsrError("ASR_STREAMING_UNSUPPORTED", "结果流式识别尚未接入（后端 V8）");
}

// ──────────────────────────── 元数据 / 设置 ────────────────────────────

export async function asrListProviders(): Promise<ProviderInfo[]> {
  const response = await request("/providers");
  return (await response.json()) as ProviderInfo[];
}

/**
 * 拉取某 provider 的模型清单。
 *
 * `region` 传表单里**当前选中**的地域：模型清单是按地域过滤的，而保存设置
 * 有 debounce（500ms），不传的话后端会按上一次落盘的地域返回——切地域后列表
 * 要等下次打开设置页才更新（表现为"少/多一个模型"）。缺省/空 = 用落盘的配置。
 */
export async function asrListModels(providerId: string, region?: string): Promise<ModelInfo[]> {
  const params = new URLSearchParams({ providerId });
  if (region) params.set("region", region);
  const response = await request(`/models?${params.toString()}`);
  return (await response.json()) as ModelInfo[];
}

export async function asrGetSettings(): Promise<AsrSettings> {
  const response = await request("/settings");
  return (await response.json()) as AsrSettings;
}

/** 整份覆盖保存（后端校验失败 400 `ASR_INVALID_SETTINGS`）。 */
export async function asrSetSettings(settings: AsrSettings): Promise<void> {
  await request("/settings", {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(settings),
  });
}

export async function asrGetStatus(): Promise<AsrStatus> {
  const response = await request("/status");
  return (await response.json()) as AsrStatus;
}

/** 探活：后端拿 1 秒静音跑一次 provider.recognize，验证「配好了没有」。 */
export async function asrTestProvider(providerId: string): Promise<AsrTestResult> {
  const response = await request("/test", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify({ providerId }),
  });
  return (await response.json()) as AsrTestResult;
}

// ──────────────────────────── 流式（后端 V8） ────────────────────────────

/**
 * 流式识别的四个函数：保留签名（`session.ts` / `recorder.ts` 引用了它们，
 * 删掉会编译不过），但后端要到 V8 才做 WS 端点，函数体统一明确抛错。
 *
 * 为什么不删：`session.ts` 的 `if (isStreamEnabled() && !isLlamaStream())` 分支是
 * 上游骨架的一部分，V8 接上后端只需把 `gates.ts::isStreamEnabled()` 改成读
 * `settings.stream_enabled`，这里再补实现即可。
 */
async function notImplemented(): Promise<never> {
  throw new AsrError("ASR_STREAMING_UNSUPPORTED", "流式识别尚未接入（后端 V8）");
}

export async function asrStartStreaming(_params: {
  providerId: string;
  languageHint?: string | null;
  hotwords?: HotwordInput[] | null;
}): Promise<void> {
  return notImplemented();
}

export async function asrStreamAudioChunk(_pcm: number[]): Promise<void> {
  return notImplemented();
}

export async function asrStopStreaming(): Promise<AsrResult> {
  return notImplemented();
}

/** 丢弃流式会话（异常路径清理用；不影响非流式在飞识别） */
export async function asrCancelStreaming(): Promise<void> {
  return notImplemented();
}
