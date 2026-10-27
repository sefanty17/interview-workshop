import { asrLog } from "./log";
import { canStartAsr, chatActive } from "./gates";
import { discardRecording, start, stop, toggleAutoListenFunction } from "./session";
import { getPttState, pttKeyDown, pttKeyUp, resetPtt } from "./ptt-state";
import { activeSource, phase, runtime } from "./state";
import { bindingMatches, isDirKey, parsePttBinding, type ShortcutBinding } from "../../utils/shortcuts";

/** 解析持久化的快捷键绑定；非法 JSON / 缺字段回退默认裸键。
 *  每次按键实时解析（毫秒级），改动设置立即生效，无需缓存失效机制。
 *  解析策略统一走 utils/shortcuts 的 parsePttBinding（设置页显示同款）。 */
function resolvePttBinding(): ShortcutBinding {
  return parsePttBinding(runtime.asrStore?.settings?.ptt_key) ?? { key: "f8" };
}

// ── 按住说话(PTT)快捷键 v2：auto_listen 模式关 = 长按 PTT / 单击 toggle 双语义；
// 模式开 = 快捷键专职切换自动监听启用开关（只动运行态，不碰模式设置）。
// 窗口内生效（/chat 与 /pet，路由门控在 chatActive）。
//
// 本项目的降级（规格 §2.5）：上游在 ptt_global 开启且全局快捷键注册成功时
// **退位**给 OS 级全局事件（`asr:ptt-global` 通道）。浏览器拿不到窗口失焦后的
// 按键，也没有全局注册端点（后端没有 /ptt-global），所以：
// - 窗口内 keydown/keyup 的 PTT 逻辑保留，且**不再退位**——window 监听是本项目
//   唯一的输入源，退位会让 PTT 完全静默；
// - blur 兜底恢复为「无条件生效」（上游在全局模式注册成功时跳过 blur 兜底，
//   因为释放事件由全局回调保证到达；这里没有全局回调，跳过就等于丢录音）；
// - 手势状态机（ptt-state）与两种语义完全不变。
// 注：设置项 ptt_global 仍会被后端保存，但本项目不消费它（设置页 V3b 可标注「浏览器不适用」）。

/** PTT 按下统一入口：门控（总开关/路由/AI 生成/TTS 播放等）+ 状态机推进。 */
export function pttDown() {
  // 总开关门控（用户需求：两种行为都只能在语音输入总开关开启后生效）
  if (!runtime.asrStore?.settings?.voice_input_enabled) return;
  // 行为 2：auto_listen 模式开 → 快捷键专职切换启用开关（toggleAutoListenFunction 只动运行态）。
  // 与行为 1 一致：仅聊天上下文（chatActive）生效——其它界面翻转 autoListenActive
  // 会在进聊天后未经交互启动自动监听
  if (runtime.asrStore?.settings?.auto_listen) {
    if (!chatActive.value) return;
    toggleAutoListenFunction();
    return;
  }
  // 行为 1：模式关 → 长按 PTT / 单击 toggle 双语义
  const sessionActive = phase.value === "recording" && activeSource.value === "button";
  const sessionBusy = phase.value !== "idle";
  const cmd = pttKeyDown(sessionActive, sessionBusy);
  if (cmd.kind === "stop") {
    // toggle-held（单击保持录音）中按下即结束
    stop();
    return;
  }
  if (cmd.kind === "start" || cmd.kind === "restart") {
    // 门控（手动语义）：聊天场景外、抽屉开、AI 生成中、TTS 播放中等 → 静默拒绝；
    // restart = toggle-held 中按下但会话已被外部 discard（AI 进 thinking 等）→ 一次按键重启
    if (!chatActive.value || !canStartAsr({ forManual: true })) {
      // 门控拒绝：状态机复位（不残留 pending），后续 keyup 落空无害
      resetPtt();
      return;
    }
    // 立即开始录音（不丢首字）；PTT_TAP_THRESHOLD_MS 内松开由 keyup 判定为
    // 单击，否则 keyup 时按长按结束
    void start("button").catch((err) => {
      // start 门控拒绝是静默 return 不 throw；只有 getUserMedia 等失败才走这里
      resetPtt();
      asrLog().warn("PTT start failed:", err);
    });
  }
}

/** PTT 松开统一入口：手势判定在 ptt-state，这里只执行动作
 *  （长按结束 = 停止 → 识别 → 按 send_mode 处理）。 */
export function pttUp() {
  const sessionActive = phase.value === "recording" && activeSource.value === "button";
  const cmd = pttKeyUp(sessionActive);
  if (cmd.kind === "stop") {
    stop();
  }
}

export function handlePttKeyDown(e: KeyboardEvent) {
  // 上游在此处有「全局模式已注册 → 窗口内监听退位」的早退；浏览器没有全局快捷键，
  // 退位等于把唯一的输入源关掉，故删除该分支（理由见文件头注释）。
  if (!bindingMatches(resolvePttBinding(), e) || e.repeat) return;
  pttDown();
}

export function handlePttKeyUp(e: KeyboardEvent) {
  // 主键比对（松开瞬间修饰键状态不可靠）；大小写不敏感——KeyboardEvent.key
  // 对功能键返回大写，且 binding.key 可能被手改为大写（v1 硬编码 PTT_KEY='F8'
  // 大写），故双侧归一——与 bindingMatches 的归一约定一致，避免 keydown 匹配
  // 而 keyup 不匹配导致录音挂起。方向键成对匹配（与 keydown 的 isDirKey 语义
  // 一致，避免绑定方向键后 keydown 配对触发、keyup 只比主键导致录音无法结束）
  const binding = resolvePttBinding();
  const k = e.key.toLowerCase();
  const bk = binding.key.toLowerCase();
  const isDir = isDirKey(bk);
  if (isDir ? k !== "arrowup" && k !== "arrowdown" : k !== bk) return;
  pttUp();
}

export function handleWindowBlur() {
  // 上游：全局快捷键注册成功时失焦是常态（释放事件由全局回调保证到达），跳过兜底。
  // 本项目没有全局回调，失焦丢 keyup 就是丢录音 → blur 兜底无条件生效。
  const pttState = getPttState();
  if (pttState === "none") return;
  if (phase.value === "recording" && activeSource.value === "button") {
    if (pttState === "pending" || pttState === "held") {
      // 按住中（pending/held）失焦：keyup 收不到，丢弃录音（不识别，避免把
      // 环境声送去识别），防录音卡死——blur 兜底语义只对"按键物理按下中"成立
      resetPtt();
      asrLog().info("PTT 窗口失焦，丢弃录音");
      discardRecording();
      return;
    }
    // toggle-held（单击保持）：按键早已松开，不存在丢失的 keyup——保留录音且
    // **保留 toggle-held 状态**（审查 P-3：先前无条件 resetPtt 清掉状态后，
    // 回焦按键被 sessionBusy 挡死，录音只能等 60s 硬上限才停）。回焦后按键
    // state 仍是 toggle-held → pttKeyDown 走 stop 分支正常停止；期间会话被
    // 外部 discard（AI 进 thinking 等）则由 resetSession 的 resetPtt 归位。
    // 失焦期间按键停止不可达，但有 60s 硬上限 + 回焦 PTT 可停 + mic 按钮可停
    // 三条逃生路径
    asrLog().info("PTT toggle-held 窗口失焦，保留录音");
    return;
  }
  // 会话已不在录音中（toggle-held 被外部 discard / 门控翻转 / recognizing）：
  // 按键态无承载会话，复位不残留
  resetPtt();
}
