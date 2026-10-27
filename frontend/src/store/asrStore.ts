/**
 * ASR 设置与运行态 store（上游 `stores/modules/settings/asr.ts` 的轻量等价物）。
 *
 * 上游用 pinia + persist（localStorage 缓存 + Tauri store 落盘）；本项目不需要那层：
 * 设置的唯一真相在后端（`AsrSettingsService` + 数据库），前端只做「内存副本 + 保存」。
 *
 * 三条刻意为之的约定：
 * 1. **不做字段默认值**：`settings` 初始为 null，默认值的单一真相在后端
 *    （`AsrSettings.defaults()`）。前端自己补一套默认值，两边一改就漂移。
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

/** 取消挂起的 debounce 保存（flush 里先取消，避免「刚 flush 完又被旧 timer 覆盖」）。 */
function cancelPendingSave(): void {
  if (saveTimer !== undefined) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
}

export const asrStore = reactive({
  settings: null as AsrSettings | null,
  models: [] as ModelInfo[],
  loading: false,
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

  /** 从后端拉一次（进入聊天页 / 打开设置页时调用）。 */
  async load(): Promise<void> {
    asrStore.loading = true;
    try {
      const settings = await asrGetSettings();
      asrStore.settings = settings;
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
    // 还没 load 完就没有可合并的基准：直接丢弃（否则会把空对象当整份设置存回后端，
    // 把用户已有的 provider 配置清光）
    if (!current) return;

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
    const settings = asrStore.settings;
    if (!settings) return;
    await asrSetSettings(settings);
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
