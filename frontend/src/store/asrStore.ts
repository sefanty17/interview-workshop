/**
 * ASR 设置与运行态 store（上游 `stores/modules/settings/asr.ts` 的轻量等价物）。
 *
 * 上游用 pinia + persist（localStorage 缓存 + Tauri store 落盘）；本项目不需要那层：
 * 设置的唯一真相在后端（`AsrSettingsService` + 数据库），前端只做「内存副本 + 保存」。
 *
 * 三条刻意为之的约定：
 * 1. **默认值的单一真相仍在后端**（`AsrSettings.defaults()`）。但 `settings` 初值不能是
 *    null：设置页模板直接读 `store.settings.voice_input_enabled`，null 会渲染崩。
 *    所以这里放一份与后端 `defaults()` 逐字段一致的本地默认（`DEFAULT_SETTINGS`），
 *    `load()` 成功后整体替换——它只是 load 返回前的兜底，不是第二套默认值。
 *    改动后端默认值时必须同步改 `DEFAULT_SETTINGS`（两处漂移会表现为「改完设置页先闪一下旧值」）。
 * 2. `patch` 对 `provider_configs` 逐 provider 浅合并，不能整体替换（详见方法注释）。
 * 3. 保存失败一律抛出/记 `lastError`，绝不静默吞——「设置看起来保存成功、重启没了」
 *    是最难查的一类 bug。
 */

import { reactive } from "vue";
import {
  asrGetSettings,
  asrListModels,
  asrSetSettings,
  type AsrSettings,
  type ModelInfo,
  type ProviderConfig,
  type VadEvent,
} from "../api/asr";
import { parseAsrError } from "../utils/asrError";

/** 保存 debounce：设置页拖动滑块时不能每一帧发一次 PUT。 */
const SAVE_DEBOUNCE_MS = 500;

let saveTimer: number | undefined;

/**
 * 单个 provider 的空配置：字段初值与后端 `new AsrProviderConfig()` 一一对应。
 * 默认 provider 清单与后端 `AsrRegistry.PROVIDER_ORDER` 同序同名，设置页切服务商时
 * 不会拿到 undefined。
 */
function emptyProviderConfig(): ProviderConfig {
  return { api_keys: {}, api_key: "", endpoint: "", ws_endpoint: "", model: "", region: "" };
}

/**
 * 本地默认设置：**逐字段对齐后端 `AsrSettings.defaults()`**（11 个上游字段 + 6 个
 * 本项目扩展字段）。只在 `load()` 返回前给模板兜底，load 成功即被整体替换。
 * 注意 `voice_input_enabled` 是 false（后端全新用户默认关语音输入），别「顺手」改成 true。
 */
const DEFAULT_SETTINGS: AsrSettings = {
  active_provider: "qwen-asr",
  auto_listen: false,
  send_mode: "fill_only",
  stream_enabled: false,
  voice_input_enabled: false,
  vad_silence_ms: 800,
  energy_warmup_ms: 100,
  vad_debug_log: false,
  ptt_key: '{"key":"f8"}',
  ptt_global: false,
  provider_configs: {
    "qwen-asr": emptyProviderConfig(),
    "llama-asr": emptyProviderConfig(),
    "mock-asr": emptyProviderConfig(),
  },
  // ── 本项目扩展字段（上游没有）──
  wake_word_enabled: false,
  wake_word: "你好面试官",
  asr_continuous: true,
  asr_mic_gate: true,
  asr_mic_threshold: 0.5,
  asr_mic_device: "default",
};

/** 取消挂起的 debounce 保存（flush 里先取消，避免「刚 flush 完又被旧 timer 覆盖」）。 */
function cancelPendingSave(): void {
  if (saveTimer !== undefined) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
}

export const asrStore = reactive({
  settings: DEFAULT_SETTINGS as AsrSettings,
  models: [] as ModelInfo[],
  loading: false,
  /**
   * 是否已经成功从后端 load 过一次。
   *
   * why 需要：`settings` 不再是 null（见文件头第 1 条），`patch` 也就失去了
   * 「没 load 完就丢弃」的天然闸门。若用户在 load 尚未返回时动一下设置，
   * 就会把本地默认值整份 PUT 覆盖掉后端已有配置（provider 的 Key 一起没）。
   * 所以闸门改成显式的这个标志。
   */
  loaded: false,
  /** 最近一次失败的错误码（`ASR_xxx`）；成功识别/保存后由 clearError 清掉 */
  lastError: null as string | null,
  /**
   * 最近一条 VAD 事件。
   *
   * 上游是 tauri 事件通道写它、`index.ts` 用 watch 转成 `onVadTurnEnd()`；
   * 本项目事件随 `/vad` 响应体返回，由 `recorder.ts` 写它，watch 逻辑不变
   * （保留这条中转，是为了让「事件 → 结束一轮录音」的链路与上游一致）。
   */
  vadEvent: null as VadEvent | null,
  /** 麦克风状态（设置页/按钮显示用；上游同名字段） */
  micState: "idle" as "idle" | "recording" | "denied",

  /** 最近一次识别/测试的结果文本。运行时态，不落库（重启即清空）。 */
  lastResult: "",
  /** 桌面端模型下载进度。本项目无本地模型，恒为空对象；保留字段是为了让设置页模板不用改。 */
  modelStates: {} as Record<string, { installed: boolean; progress: number }>,

  /** 从后端拉一次（进入聊天页 / 打开设置页时调用）。 */
  async load(): Promise<void> {
    asrStore.loading = true;
    try {
      const settings = await asrGetSettings();
      asrStore.settings = settings;
      asrStore.loaded = true;
      // 模型清单拉失败不能连带 load 失败：本地 llama-asr 没启动是常态，
      // 让它把整个设置读崩掉，用户连改 provider 的机会都没有
      await asrStore.reloadModels(settings.active_provider).catch((e) => {
        console.warn("[ASR] 模型清单拉取失败:", e);
      });
    } finally {
      asrStore.loading = false;
    }
  },

  /** 本地立即生效 + 500ms debounce 落库（设置页拖动滑块不能每帧发一次 PUT）。 */
  patch(partial: Partial<AsrSettings>): void {
    const current = asrStore.settings;
    // 还没 load 完就没有可合并的基准：直接丢弃（否则会把本地默认值整份存回后端，
    // 把用户已有的 provider 配置清光）
    if (!asrStore.loaded) return;

    const next: AsrSettings = { ...current, ...partial };
    if (partial.provider_configs) {
      // 逐 provider 浅合并：整体替换会把**别的** provider 的配置冲掉
      //（设置页只改当前那一个，请求体里的 provider_configs 往往只有它）
      const merged: Record<string, ProviderConfig> = { ...current.provider_configs };
      for (const [id, cfg] of Object.entries(partial.provider_configs)) {
        merged[id] = { ...merged[id], ...cfg };
      }
      next.provider_configs = merged;
    }
    asrStore.settings = next;

    cancelPendingSave();
    saveTimer = window.setTimeout(() => {
      saveTimer = undefined;
      // debounce 触发时没有调用方可以接错误：写 lastError（设置页据此提示）+ 控制台。
      // 需要「保存失败必须让用户知道」的场合（保存按钮）走 flush()，那里是直接抛。
      void asrStore.flush().catch((e) => {
        const info = parseAsrError(e);
        asrStore.lastError = info.code || info.detail;
        console.warn("[ASR] 保存设置失败:", e);
      });
    }, SAVE_DEBOUNCE_MS);
  },

  /** 立刻落库（不 debounce，用于「保存」按钮 / 关面板前 flush）。失败直接抛出。 */
  async flush(): Promise<void> {
    cancelPendingSave();
    // 同 patch：没 load 成功过就没有「用户当前设置」可言，把本地默认值存回去等于清库
    if (!asrStore.loaded) return;
    await asrSetSettings(asrStore.settings);
  },

  /**
   * 重拉模型清单。`region` 传表单里当前选中的地域（见 `asrListModels`）。
   *
   * 失败时清空清单**并抛出**：由调用方决定怎么提示。静默吞掉会让设置页显示
   * 「没有模型」，用户以为服务端就这么点能力。
   */
  async reloadModels(providerId?: string, region?: string): Promise<void> {
    const id = providerId ?? asrStore.settings?.active_provider;
    if (!id) {
      asrStore.models = [];
      return;
    }
    try {
      asrStore.models = await asrListModels(id, region);
    } catch (e) {
      asrStore.models = [];
      throw e;
    }
  },

  /** VAD 事件：一轮说话结束的候选 / 定稿（`index.ts` 的 watch 据此结束录音） */
  onTurnCandidate(e: VadEvent): void {
    asrStore.vadEvent = e;
  },
  onTurnSealed(e: VadEvent): void {
    asrStore.vadEvent = e;
  },
  onError(code: string): void {
    asrStore.lastError = code;
  },
  /** 识别/连接成功路径清除错误（设置页状态面板据此转绿；失败只写不清会一直红） */
  clearError(): void {
    asrStore.lastError = null;
  },
  setMicState(s: "idle" | "recording" | "denied"): void {
    asrStore.micState = s;
  },
});
