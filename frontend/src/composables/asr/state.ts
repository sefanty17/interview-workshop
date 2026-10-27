import { ref, shallowReactive, shallowRef } from "vue";
import type { AsrSource } from "../../api/asr";
import type { asrStore as asrStoreInstance } from "../../store/asrStore";

/** 输入框读写桥（V3b 的 GameDialog 注册，供 partial 写入 / 拼接基准读取）。
 *
 * 上游是什么：`{ getText, setText }`，GameDialog 通过 `registerAsrInputBridge(b)` 整体注册。
 * 本项目为什么不同：本批不接线（纯 TS 底座），V3b 只需要提供「写入函数」这一个能力，
 * 所以 `registerAsrInputBridge(fn)` 收一个 `(text) => void`；`getText` 变成可选的，
 * 没注册时拼接基准退化成空串（`session.ts` 的 `baseText` 因此不会取到脏值）。 */
export interface AsrInputBridge {
  setText: (v: string) => void;
  getText?: () => string;
}

/** 能量监测句柄（raf 循环 + 独立采集流，stop 时统一回收） */
export interface EnergyMonitorHandle {
  ctx: AudioContext;
  raf: number;
  stream: MediaStream;
}

// ── 模块级单例状态 ──────────────────────────────────────────
// 状态全部在模块级（非函数内）：App.vue 的初始化实例与 GameDialog /
// ChatInput 的 mic 实例共享同一会话。
//
// 两种形态：
// - ref 类（响应式，export const）：phase/activeSource/voicePlaying 等，
//   消费方经 `.value` 读写（ref 对象本身不变，import 绑定只读无碍）
// - 非响应式可变状态集中在 runtime 容器（export const 对象，成员可变）：
//   ES module 的 import 绑定只读，跨模块直接赋值非法——容器成员是
//   跨模块共享可写状态的唯一合法形态（模块化重构，逻辑不变）
//
// 本项目的一处偏差：容器包了 shallowReactive。
// 上游的 runtime 是普通对象，能工作是因为 `runtime.route` 本身是 vue-router 的
// 响应式对象；本项目把「路由判定」换成了普通布尔字段 `chatMounted`（见 gates.ts
// 的 chatActive computed），普通对象的字段变化不会触发 computed 与 watch，
// chatActive 会永远停在第一次求值的结果上。shallowReactive 只代理顶层成员，
// 嵌套的 MediaStream / AudioContext / PCM 数组不被代理——
// 录音链路（push 上万次采样）不承担代理开销，对象身份也不变。
export const phase = ref<"idle" | "recording" | "recognizing">("idle");
export const activeSource = shallowRef<AsrSource | null>(null);

export const runtime = shallowReactive({
  /** 本次录音累积的 f32 PCM（16kHz mono） */
  pcmBuffer: [] as number[],
  /** 待喂 VAD 的积累块（本项目：凑满 8192 samples = 16 帧才发，见 recorder.ts） */
  vadPending: [] as number[],
  /** 待喂流式 WS 的积累块（与 vadPending 同节奏，session/recorder 共享） */
  streamPending: [] as number[],
  stream: null as MediaStream | null,
  audioCtx: null as AudioContext | null,
  processor: null as ScriptProcessorNode | null,
  energyMon: null as EnergyMonitorHandle | null,
  /** auto 触发去重：能量触发后不再重复触发，直到本轮会话结束 */
  autoTriggered: false,
  /** 移动端菜单展开状态（GameDialog 在 watch 中同步，§1.5 判定） */
  mobileMenuOpen: false,
  /** 输入框桥：V3b 的 GameDialog 注册，供 partial 实时写入 / 拼接基准读取 */
  inputBridge: null as AsrInputBridge | null,
  /** 录音开始时的输入框内容快照（拼接语义的基准：partial 只追加在这之后） */
  baseText: "",
  /**
   * 聊天场景是否挂载。
   *
   * 上游用 `route.path === "/chat" || route.path === "/pet"` 判断；本项目没有多路由，
   * 「在聊天里」= GameDialog 已挂载 → 由 V3b 在 GameDialog 挂载/卸载时置位。
   * 默认 false：没进过聊天页时 ASR 一律不可用（与上游「不在 /chat 就不可用」同义）。
   */
  chatMounted: false,
  /** 惰性依赖（首次 useAsrInput() 调用时初始化，见 index.ts ensureInit） */
  asrStore: null as typeof asrStoreInstance | null,
});

/** 短暂显示锁：识别后填入 inputMessage 到自动 send 之间的窗口期，期间 auto 触发禁用（§1.10）。
 *  ref 化（非普通变量）：canStartMic 等 computed 依赖它，锁过期后能自动重算解锁。 */
export const asrLockedUntil = ref(0);
/** fill_only 模式：识别结果填入输入框后短暂锁定 ASR 的毫秒数（§1.10）。
 *  防 auto_listen 立即再触发录音、覆盖刚填入的内容（手动触发不受此锁限制）。
 *  导出供 GameDialog / ChatInput（经共享 useChatInput）的 asr-text 监听复用（同一语义）。 */
export const ASR_DISPLAY_MS = 400;
/** 录音硬上限（samples）：1 分钟 @ 16kHz。达到后自动 stop()——
 *  防止按钮长按/异常会话无限录音（VAD 端 max_segment_frames 同为 60s，两处对齐；
 *  有界也顺带解决长时间录音时 pcmBuffer 的无限内存增长）。 */
export const MAX_RECORD_SAMPLES = 60 * 16000;
/** 能量监测启动缓冲期兜底值（毫秒）：未加载设置时用 100ms。
 *  实际值来自 asrStore.settings.energy_warmup_ms（设置页可自定义，
 *  0 = 无缓冲）。voicePlaying 门控已保证 TTS 播放期间完全不监听，
 *  此缓冲期只兜底播放结束瞬间的残响尾巴。 */
export const ENERGY_WARMUP_MS = 100;
/** 角色语音（TTS）播放中（GameRolesStage 桌面/桌宠通过 setVoicePlaying 同步）：
 *  外放 TTS 会被麦克风捕获 → RMS 触发 → VAD 判定为人声 → 误识别 AI 自己的话。
 *  播放期间 ASR 整体禁用（canStartAsr 门控 + handle drop），播完才恢复。
 *  ref 化：canStartMic 等 computed 依赖它，播完 setVoicePlaying(false) 自动解锁。 */
export const voicePlaying = ref(false);
/** 语音会话进行中（GameDialog 据此 readonly 输入框，语音期间禁止手动输入）。
 *  前缀 asr 用于与同模块的 voicePlaying（TTS 播放中）在组件顶层导入时消歧。 */
export const asrVoiceActive = ref(false);
/** 功能开关（运行态）：auto_listen 模式开启时由 mic/快捷键切换——监听激活/暂停。
 *  不持久化、不改 auto_listen 模式设置。 */
export const autoListenActive = ref(false);
