/**
 * ASR 错误解析与错误类型（本项目版）。
 *
 * 上游是什么：错误以字符串（JSON 或旧的 `CODE|detail`）穿过 Tauri invoke，
 * `parseAsrError` 只负责拆出 `{code, detail}`，人话由 `locales/*.settings.asr.errors`
 * 查表得到。
 *
 * 本项目为什么不同：
 * 1. 后端错误体是 `{code, detail, status}`（见后端 `SpeechExceptionHandler`），
 *    `detail` 本来就是人话（`AsrException` 的中文 message），所以优先用 detail；
 * 2. 前端没有 locale 体系，查表逻辑无处可依，改成 `code → 中文兜底文案` 的本地映射
 *    （detail 缺失时才用得上）；
 * 3. 浏览器侧走 HTTP 而不是进程内 IPC，拿不到 JSON（代理 502、后端宕机等）时
 *    回退成 `HTTP ${status}` / 网络错误文案。
 */

export interface AsrErrorInfo {
  code: string;
  /** 人话（可直接显示给用户）；拿不到时为兜底文案 */
  detail: string;
}

/**
 * 带错误码的 ASR 异常。
 *
 * 为什么需要它：上游 `invoke` 失败抛的是字符串，调用方用 `parseAsrError(err).code`
 * 判断分支；本项目 `fetch` 需要自己携带 code，否则只剩一句 message，
 * `session.ts` 的 `onError(code)` 会把整句人话当成错误码存进 store。
 */
export class AsrError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = "AsrError";
    this.code = code;
  }
}

/**
 * code → 中文兜底文案。
 *
 * 覆盖后端 `AsrErrorKind` 全量 12 个（11 个上游错误码 + 本项目扩展的
 * `ASR_INVALID_SETTINGS`），外加前端自造的两个：网络层失败与「非 JSON 错误体」。
 * 后端新增 code 时这里不用改——`asrErrorText` 会把未知 code 原样带进文案。
 */
const ASR_ERROR_TEXT: Record<string, string> = {
  ASR_MODEL_MISSING: "VAD 模型未找到",
  ASR_ENGINE_LOAD_FAILED: "VAD 引擎加载失败",
  ASR_PROVIDER_NOT_FOUND: "语音服务商标识不存在",
  ASR_PROVIDER_FAILED: "语音服务调用失败",
  ASR_PROVIDER_TIMEOUT: "语音服务请求超时",
  ASR_MISSING_CREDENTIALS: "缺少 API Key 等凭据",
  ASR_INVALID_AUDIO: "音频格式无效",
  ASR_SESSION_BUSY: "已有进行中的语音会话",
  ASR_CANCELED: "语音会话已取消",
  ASR_MIC_DENIED: "麦克风权限被拒绝",
  ASR_STREAMING_UNSUPPORTED: "流式识别暂不支持",
  ASR_INVALID_SETTINGS: "语音设置不合法",
  ASR_NETWORK: "无法连接后端",
  ASR_HTTP_ERROR: "语音服务返回错误",
};

/** code → 中文兜底文案（未知 code 原样带回，便于排查而不是显示空白）。 */
export function asrErrorText(code: string): string {
  return ASR_ERROR_TEXT[code] ?? `语音识别失败（${code}）`;
}

/**
 * 任意错误 → `{code, detail}`。
 *
 * 调用方（`session.ts`）只用 `code` 做 store 记录，`detail` 供 UI 显示，
 * 所以任何输入都必须给出非空 detail，否则设置页会显示一行空白。
 */
export function parseAsrError(raw: unknown): AsrErrorInfo {
  if (raw instanceof AsrError) return { code: raw.code, detail: raw.message };
  const text = raw instanceof Error ? raw.message : typeof raw === "string" ? raw : String(raw ?? "");
  if (!text) return { code: "", detail: asrErrorText("ASR_PROVIDER_FAILED") };
  // 结构化错误体 `{code, detail, status}`：detail 优先（后端给的就是人话）
  try {
    const parsed = JSON.parse(text) as { code?: unknown; detail?: unknown };
    if (parsed && typeof parsed.code === "string" && parsed.code) {
      return {
        code: parsed.code,
        detail:
          typeof parsed.detail === "string" && parsed.detail
            ? parsed.detail
            : asrErrorText(parsed.code),
      };
    }
  } catch {
    /* 不是 JSON：走下面的兜底 */
  }
  // 非结构化文本（含 `HTTP 500` / 网络错误文案）：整串就是人话，code 留空交给调用方降级
  return { code: "", detail: text };
}
