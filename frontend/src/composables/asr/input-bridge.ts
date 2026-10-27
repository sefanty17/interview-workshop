import { asrLog } from "./log";
import { runtime, type AsrInputBridge } from "./state";

/**
 * 注册输入框写入桥（V3b 的 GameDialog 调用：把识别结果写进聊天输入框）。
 *
 * 上游是什么：`registerAsrInputBridge({ getText, setText })`，一次注册读写两个方向。
 * 本项目为什么不同：本批是纯 TS 底座（不接线），V3b 只需要「写入」这一个能力，
 * 所以这里收一个 `(text) => void`，内部包成 `AsrInputBridge`（`getText` 缺省，
 * 拼接基准退化成空串）。
 *
 * 返回注销函数：组件卸载时调用，避免桥指向已卸载组件（partial 写入死 ref——
 * 静默无效但有语义脏点）。
 */
export function registerAsrInputBridge(fn: (text: string) => void): () => void {
  const bridge: AsrInputBridge = { setText: fn };
  runtime.inputBridge = bridge;
  return () => {
    if (runtime.inputBridge === bridge) runtime.inputBridge = null;
  };
}

/**
 * 流式 partial 写入输入框：整体替换语音追加块，不触碰 baseText 之前的内容。
 *
 * 没注册桥时只记一条 debug 日志并丢弃——**不抛错**：识别成功但输入框还没挂载
 * （V3b 之前、或用户刚离开聊天页）是正常情况，抛错会把「识别成功」变成一次失败。
 */
export function writePartial(text: string): void {
  const bridge = runtime.inputBridge;
  if (!bridge) {
    asrLog("[ASR/stream]").info(`writePartial 丢弃（输入框未注册）: len=${text.length}`);
    return;
  }
  bridge.setText(runtime.baseText + text);
}
