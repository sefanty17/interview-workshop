import { activeSource, phase, runtime } from "./state";
import { asrLog } from "./log";
import { asrStreamAudioChunk, asrVadProcessChunk } from "../../api/asr";

/** 拆除录音链路（不触发 recognize） */
export function teardownRecorder() {
  try {
    runtime.processor?.disconnect();
  } catch {
    /* ignore */
  }
  runtime.processor = null;
  void runtime.audioCtx?.close().catch(() => {});
  runtime.audioCtx = null;
  runtime.stream?.getTracks().forEach((t) => t.stop());
  runtime.stream = null;
  runtime.pcmBuffer = [];
  runtime.vadPending = [];
  runtime.streamPending = [];
  vadSentBatches = 0;
  if (runtime.asrStore) runtime.asrStore.setMicState("idle");
}

// ── VAD 流（auto 模式）：攒够一批（16 帧）才喂后端 ──
// 上游是什么：进程内 IPC，每 512 samples（30ms @16k）调一次 asr_vad_process_chunk，
// 一次 invoke 只花几十微秒。本项目为什么不同：换成 HTTP 后按帧发，30 秒录音要
// 900+ 次往返（每次 2~3ms），累计多出 2~3 秒延迟，VAD 判定会明显滞后于真实说话
// 进度。所以这里攒到 VAD_BATCH_SAMPLES（8192 samples = 16 帧 ≈ 512ms）才发一批，
// 请求数降到原来的 1/16；后端 /vad 本来就支持一次收多帧（它按 512 切帧处理）。
const VAD_FRAME_SAMPLES = 512;
const VAD_BATCH_SAMPLES = VAD_FRAME_SAMPLES * 16;

// 严格串行单飞：一批完成才发下一批。VAD 的噪声底/静音计数依赖顺序输入——
// 并发 fire-and-forget 会导致后端先后顺序错乱，prob 结果无意义
// （表现：VAD 永不触发 SpeechStarted / TurnCandidate）。
let vadSending = false;
/** 诊断：已发送的 VAD 批数（用于降频日志） */
let vadSentBatches = 0;

/** 把一批样本送给后端，并把响应体里的事件分发给原来处理事件的回调。 */
function sendVadBatch(block: number[]) {
  vadSending = true;
  // 诊断日志：前 10 批 + 此后每次翻倍节奏打一条（批量后频率已降到 ~2/s）
  if (vadSentBatches < 10 || vadSentBatches % 33 === 0) {
    asrLog("[ASR/VAD]").info(`feedVad #${vadSentBatches} 发送 ${block.length} samples`);
  }
  vadSentBatches++;
  asrVadProcessChunk(block)
    .then((res) => {
      // 上游事件走 Tauri 事件通道（listen("asr://...") → store 写入 → index.ts 的 watch）；
      // 本项目事件随响应体同步返回（后端差异 C），所以在这里逐条分发给同一批回调。
      for (const ev of res.events ?? []) {
        if (ev.type === "speech_started") {
          // 上游由事件通道直接调 store.onSpeechStarted；这里加 phase 守卫：
          // 响应是异步回来的，停止录音后迟到的这批事件不能把 micState 拉回 recording
          if (phase.value === "recording") runtime.asrStore?.setMicState("recording");
        } else if (ev.type === "turn_candidate") {
          runtime.asrStore?.onTurnCandidate(ev);
        } else if (ev.type === "turn_sealed") {
          runtime.asrStore?.onTurnSealed(ev);
        }
        // silence_started：上游无对应处理，忽略
      }
    })
    .catch((e) => {
      // VAD 失败不阻塞录音，但错误不能静默——暴露给调试者
      asrLog("[ASR/VAD]").warn("feedVad 失败:", e);
    })
    .finally(() => {
      vadSending = false;
      feedVad();
    });
}

export function feedVad() {
  if (!runtime.asrStore || phase.value !== "recording" || activeSource.value !== "auto") return;
  if (vadSending || runtime.vadPending.length < VAD_BATCH_SAMPLES) return;
  sendVadBatch(runtime.vadPending.splice(0, VAD_BATCH_SAMPLES));
}

/**
 * 停止录音时把不足一批的剩余样本也发出去。
 *
 * 为什么必须发：最后一批常常正好是「说完之后的静音」，VAD 要靠它判出
 * silence / turn_candidate 才能切出最后一句；丢掉尾巴会让结尾的话识别不到。
 * 后端对不足一帧的尾巴按帧丢弃（不补零），所以直接发整段余量即可。
 *
 * 调用时机：`session.ts::stop()` 里、`teardownRecorder()` 清空 vadPending 之前，
 * 且必须在把 phase 改成 `recognizing` 之前（feedVad/sendVadBatch 按 recording 守卫）。
 * 不等待它的返回：会话已经开始收尾，这批事件迟到也无害（phase 守卫会拦住）。
 */
export function flushVad() {
  if (!runtime.asrStore || activeSource.value !== "auto") return;
  if (runtime.vadPending.length === 0) return;
  const rest = runtime.vadPending.splice(0, runtime.vadPending.length);
  asrLog("[ASR/VAD]").info(`flushVad 发送剩余 ${rest.length} samples`);
  // 串行单飞期间（vadSending=true）直接发会与在飞那批乱序：等它发完再发
  const send = () => {
    if (vadSending) {
      window.setTimeout(send, 20);
      return;
    }
    sendVadBatch(rest);
  };
  send();
}

// ── 流式识别音频流（stream 模式）：与 VAD 同节奏喂后端 WebSocket ──
// 与 feedVad 相同串行单飞：批之间不保证顺序，WebSocket 帧必须保序。
// 本项目：流式未接入（gates.isStreamEnabled 恒 false），asrStreamAudioChunk 必抛
// 「不支持」——所以这条路径当前不可达，保留是为了 V8 接 WS 后不用改结构。
let streamSending = false;
export function feedStream() {
  if (!runtime.asrStore || phase.value !== "recording") return;
  if (streamSending || runtime.streamPending.length < 512) return;
  const block = runtime.streamPending.splice(0, 512);
  streamSending = true;
  asrStreamAudioChunk(block)
    .catch((e) => asrLog("[ASR/stream]").warn("发送音频块失败:", e))
    .finally(() => {
      streamSending = false;
      feedStream();
    });
}
