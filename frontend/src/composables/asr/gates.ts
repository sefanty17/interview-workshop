import { computed } from "vue";

import { asrLockedUntil, runtime, voicePlaying } from "./state";
import { roomStore } from "../../store/roomStore";
import { settingsUi } from "../../store/settingsUi";

/**
 * 聊天场景是否可用（上游 /chat 与 /pet 都算聊天场景；设置抽屉打开时不可用）。
 *
 * 上游：`(route.path === "/chat" || route.path === "/pet") && !uiStore.showSettings`。
 * 本项目：没有多路由，「在聊天里」= GameDialog 已挂载（`runtime.chatMounted`，
 * V3b 挂载/卸载时置位）；设置抽屉沿用 `settingsUi.showSettings`（同名同义）。
 */
export const chatActive = computed(() => {
  return runtime.chatMounted === true && !settingsUi.showSettings;
});

// ── ASR 可用性门控（§1 全 12 项） ──────────────────────────────
// 综合判定当前能否启动 ASR 录音（所有禁用条件取 OR）。编号表仅罗列全部
// 条件，与代码执行顺序无关：
// 1-3. roomStore.state ∈ {thinking, responding}（上游 currentStatus 同义）
// 4.    —— 已删：上游 `command === 'touch'`（触摸指令），本项目没有该指令
// 5.    showMobileMenu === true（移动端菜单展开）
// 6.    聊天场景未挂载（上游 route.path !== '/chat'）
// 7.    settingsUi.showSettings === true
// 8.    roomStore.choices.length > 0（剧本选择分支）
// 9.    —— 已删：上游 `loadingComplete === false`（启动动画未完成），本项目无此阻塞态
// 10.   显示锁未过期（识别结果填入后短暂禁止再触发；ignoreLock 供监测启停跳过）
// 11.   语音输入总开关关（自动与手动录音都被挡——总开关是整体语音输入开关）
// 12.   TTS 播放中（外放语音会被误识别）
// 任何一项满足即视为不可用。start() / startEnergyMonitor RMS 触发 / 按钮 enable 都查它。
// forManual=true（手动 mic 录音）：仅跳过 10 显示锁——锁防的是 auto 触发覆盖识别
// 结果（手动是用户主动，不受锁限）；总开关一律生效。ignoreLock=true（监测启停用）：
// 锁只挡"触发录音"，不挡"监测启停"——否则识别完成后锁一设监测就停、锁过期无人复活。
export function canStartAsr(opts: { ignoreLock?: boolean; forManual?: boolean } = {}): boolean {
  const { ignoreLock = false, forManual = false } = opts;
  // 6 + 7：路由/抽屉门控（chatActive 已是这两项的合成）
  if (!chatActive.value) return false;
  // 1-3：核心对话状态（上游 gameStore.currentStatus → 本项目 roomStore.state）
  if (roomStore.state !== "input") return false;
  // 5：移动端菜单展开。4（触摸模式 command === "touch"）删掉——本项目没有触摸指令，
  // 这条条件永远为假，留着只会让人以为有这条路径
  if (runtime.mobileMenuOpen) return false;
  // 8：剧本选择分支（上游 runningScript?.choices → 本项目 roomStore.choices）
  if (roomStore.choices.length > 0) return false;
  // 9：LoadingTransition 启动动画未完成——删掉。本项目房间没有「资源加载中」的阻塞态，
  // 恒视为已完成；若保留一个恒 true 的判断只会让人以为存在加载门控
  // 11：语音输入总开关——整体语音输入开关（自动与手动都被挡）
  if (!runtime.asrStore?.settings?.voice_input_enabled) return false;
  // 12：角色语音（TTS）播放中（外放 TTS 进麦克风 → 误识别 AI 自己的话）
  if (voicePlaying.value) return false;
  // 10：识别结果短暂显示锁（fill_only 填入 inputMessage 到自动 send 的窗口期）。
  // ignoreLock=true 供 updateAsrAvailability 用：锁只挡"触发录音"，不挡"监测启停"——
  // 否则识别完成后锁一设监测就停、锁过期无人复活（触发后死锁）。
  // forManual=true（手动 mic）跳过锁：显示锁防的是 auto RMS 自动触发覆盖识别结果，
  // 手动点击是用户主动（fill_only 持续录入），不受锁限。
  if (!ignoreLock && !forManual && Date.now() < asrLockedUntil.value) return false;
  return true;
}

/**
 * 流式是否生效：恒 false。
 *
 * 上游读 `settings.stream_enabled` + 当前生效模型的流式能力（模型级权威判定）；
 * 本项目后端要到 V8 才做 WS 流式端点（`/streaming/**` 目前 404），返回 true 会让
 * `session.ts` 走进流式分支、`asrStartStreaming` 直接抛「不支持」，用户点了录音就报错。
 *
 * V8 接 WS 后把这里改成读 `settings.stream_enabled`，并恢复上游的模型能力判定
 * （`asrStore.models.find(m => m.id === sel)?.supports_streaming`）。
 */
export function isStreamEnabled(): boolean {
  return false;
}

/** SSE 类结果流式 provider 判定（llama-asr 起步；名字带 llama 是历史遗留）。
 *  语义：整段 WAV 上传 + SSE 增量 partial（stop 后到达），与 qwen WS 真流式
 *  （边录边发 PCM）分流。
 *
 *  本项目：`isStreamEnabled()` 恒 false，所以这里恒 false（走整句识别）。
 *  保留函数体是为了 V8 接上后端后只需改 isStreamEnabled 一处。
 *
 *  ⚠️ 扩展点：新增 SSE 类 provider（如未来的 openai-compatible 泛化入口）
 *  必须同步加进此集合——llama-asr 与它同链路（不建 WS 会话、stop 时整段上传、
 *  partial 在 recognizing 阶段放行）。 */
export function isLlamaStream(): boolean {
  const sseProviders = ["llama-asr"];
  return (
    sseProviders.includes(runtime.asrStore?.settings?.active_provider ?? "") && isStreamEnabled()
  );
}
