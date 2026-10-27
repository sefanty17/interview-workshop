import { watch } from "vue";

import { asrLog } from "./log";
import { canStartAsr, chatActive } from "./gates";
import {
  onVadTurnEnd,
  start,
  stop,
  updateAsrAvailability,
  toggleAutoListenFunction,
} from "./session";
import { handlePttKeyDown, handlePttKeyUp, handleWindowBlur } from "./ptt";
export { ASR_AUTO_SEND_DELAY_MS, useAsrAutoSend } from "./auto-send";
export { registerAsrInputBridge } from "./input-bridge";
import {
  activeSource,
  asrLockedUntil,
  asrVoiceActive,
  autoListenActive,
  phase,
  runtime,
  voicePlaying,
} from "./state";
export { asrVoiceActive, ASR_DISPLAY_MS } from "./state";
import type { VadEvent } from "../../api/asr";
import { asrStore } from "../../store/asrStore";
import { roomStore } from "../../store/roomStore";
import { isAndroid } from "../../utils/platform";

/**
 * 统一 ASR 输入入口（门面）：两种触发源共用同一会话生命周期。
 *
 * 两种触发源：
 * - Button: GameDialog / ChatInput（桌宠）的 mic 按钮
 * - Auto: asrStore.settings.auto_listen=true 时由能量监测触发
 *
 * 窗口活跃门控：仅当 chatActive=true（聊天页已挂载 + 设置抽屉未开）时启用。
 * 失败降级：mic 不可用时 fail-open（不抛错到用户），退化为手动按钮 + 不录。
 *
 * ── 单例设计 ──────────────────────────────────────────────
 * 状态全部在模块级（asr/state.ts）：App.vue 的初始化实例与 GameDialog /
 * ChatInput 的 mic 实例共享同一会话。若状态放在函数内，两实例各自持有
 * recorder/phase，mic 按钮看不到录音状态、互不感知。
 *
 * ── 采集链路（spec §3.1）─────────────────────────────────
 * 16kHz AudioContext + ScriptProcessor 直接拿 f32 PCM（不经过
 * MediaRecorder webm 编码），停止时合成 16k mono PCM16 WAV 送去识别。
 * auto 模式额外把 PCM 攒成 8192 samples（16 帧）喂 asrVadProcessChunk，
 * 由后端 VAD 做端点检测（turn_candidate → 一轮说话结束）。
 *
 * ── 与上游的传输差异（本批重点）─────────────────────────
 * 上游是 Tauri 桌面端：命令走进程内 IPC、事件走 `listen("asr://...")` 通道。
 * 浏览器里没有事件通道，所以：
 * 1. VAD 事件随 `/vad` 响应体返回，由 recorder.ts 逐条分发给 store → 本文件的 watch；
 * 2. `asr://stream_partial` / `asr:ptt-global` / `asr:ptt-global-status` 三条 listen
 *    全部删除（流式与全局快捷键后端 V8/V4 才做，见各删除点注释）；
 * 3. 「在聊天页」由路由判断改为 `runtime.chatMounted`（GameDialog 挂载时置位）。
 */

/** 同步移动端菜单展开状态（§1.5） */
export function setMobileMenuOpen(open: boolean): void {
  runtime.mobileMenuOpen = open;
  updateAsrAvailability();
}

/** 角色语音（TTS）播放状态同步。
 *  TTS 播放开始 → 停能量监测 + 丢弃在飞 auto 录音（那是在录 AI 的声音）；
 *  播放结束 → 恢复监听。 */
export function setVoicePlaying(playing: boolean): void {
  voicePlaying.value = playing;
  updateAsrAvailability();
}

/**
 * 锁定 ASR 一段时间（识别结果填入 inputMessage 后短暂显示用，§1.10）。
 * 显示期间用户不能再次触发录音（避免 nextTick 期间又来一段覆盖识别结果）。
 */
export function lockAsrForDisplay(ms: number): void {
  asrLockedUntil.value = Date.now() + ms;
  updateAsrAvailability();
}

// ── 惰性初始化（首次调用时执行一次，注册全局监听） ──────────
let initialized = false;
function ensureInit() {
  if (initialized) return;
  initialized = true;
  runtime.asrStore = asrStore;

  // 与后端同步设置：本地只有内存副本（无 localStorage 缓存），进来先拉一次，
  // 否则 active_provider / voice_input_enabled 全是空，识别会走到错误的 provider。
  // load 完成后热键/auto_listen 的 watch 会自动响应新值。
  void asrStore.load().catch((e) => asrLog().warn("load settings failed:", e));

  // VAD 事件（经 store 中转）：上游由 tauri 事件通道写入 store.vadEvent，
  // 本项目由 recorder.ts 从 /vad 响应体里取出后写入同一个字段，这段 watch 不变
  watch(
    () => runtime.asrStore?.vadEvent ?? null,
    (e: VadEvent | null) => {
      if (!e) return;
      if (e.type === "turn_candidate" || e.type === "turn_sealed") {
        void onVadTurnEnd();
      }
    },
  );

  // 流式 partial 实时写入输入框：上游监听 `asr://stream_partial` 事件通道。
  // 本项目删除该 listen——浏览器没有事件通道，且流式要等后端 V8 才有端点
  // （gates.isStreamEnabled() 恒 false，partial 本来就不会来）。
  // V8 接上 WS 后：后端改成 SSE/WS 下发 partial，这里换成 EventSource 或
  // WebSocket 的 message 回调，写入条件仍是「phase 允许 + runtime.inputBridge 已注册」。

  // 按住说话(PTT)：keydown/keyup 开始/结束录音；blur 兜底（失焦丢 keyup）。
  // ensureInit 仅执行一次（App.vue 初始化 + GameDialog/ChatInput 共享）。
  // 安卓端不做快捷键：蓝牙键盘的 keydown 同样会到达 webview（注释"无物理键盘"不成立），
  // 显式不注册监听。
  if (!isAndroid()) {
    window.addEventListener("keydown", handlePttKeyDown);
    window.addEventListener("keyup", handlePttKeyUp);
    window.addEventListener("blur", handleWindowBlur);
  }

  // 上游在这里还监听了两条全局快捷键通道：
  // - `asr:ptt-global`（pressed/released）：OS 级热键，浏览器拿不到窗口失焦后的按键；
  // - `asr:ptt-global-status`（注册状态）：本项目后端没有 /ptt-global 端点。
  // 两者都删，PTT 降级为页面内快捷键（ptt.ts 文件头有完整说明）。

  // 聊天场景变化（§1.6/7）：通过统一 gate 同步录音/能量监测。
  // immediate:true 让首次进入聊天页（或刚初始化）时立刻同步 energy monitor 状态
  watch(
    chatActive,
    (active) => {
      asrLog().info(`chatActive -> ${active}`);
      if (!active) {
        // 离开聊天页 / 打开设置抽屉 = 等同 mic 关闭：暂停 auto 监听
        //（回来需点 mic 重新启用），在飞识别结果由 handle 的 chatActive 检查丢弃
        autoListenActive.value = false;
      }
      // 上游在这里调 asrPttGlobalSetActive(active) 做全局快捷键的界面门控；
      // 本项目没有全局快捷键（也没有该端点），删除该调用。
      updateAsrAvailability();
    },
    { immediate: true },
  );
  // auto_listen 设置开关（用户在设置页切换时立即启停）
  watch(
    () => runtime.asrStore?.settings?.auto_listen,
    (enabled) => {
      asrLog().info(`auto_listen -> ${enabled}`);
      // 模式开关：开 = 功能默认激活；关 = 功能复位（功能开关只在模式开时有意义）
      autoListenActive.value = !!enabled;
      updateAsrAvailability();
    },
    { immediate: true },
  );
  // 语音输入总开关（设置页切换立即生效）
  watch(
    () => runtime.asrStore?.settings?.voice_input_enabled,
    (enabled) => {
      asrLog().info(`voice_input_enabled -> ${enabled}`);
      updateAsrAvailability();
    },
    { immediate: true },
  );
  // 上游的 `gameStore.command`（触摸模式，§1.4）watch 删除：本项目没有触摸指令，
  // 该字段不存在，canStartAsr 里也去掉了对应条件。
  // 对话状态（§1.1-3：thinking/responding；上游 gameStore.currentStatus）
  watch(
    () => roomStore.state,
    (state) => {
      asrLog().info(`roomStore.state -> ${state}`);
      updateAsrAvailability();
    },
    { immediate: true },
  );
  // 剧本选择分支（§1.8；上游 runningScript.choices → 本项目 roomStore.choices）
  watch(
    () => roomStore.choices.length,
    (n) => {
      asrLog().info(`roomStore.choices.length -> ${n}`);
      updateAsrAvailability();
    },
    { immediate: true },
  );
  // 上游的 `gameStore.loadingComplete`（启动动画完成，§1.9）watch 删除：
  // 本项目房间没有「资源加载中」的阻塞态，canStartAsr 里恒视为已完成。
}

export function useAsrInput() {
  ensureInit();
  return {
    phase,
    activeSource,
    start,
    stop,
    canStartAsr,
    autoListenActive,
    toggleAutoListenFunction,
  };
}
