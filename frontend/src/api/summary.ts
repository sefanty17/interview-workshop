/**
 * 对话总结 API（issue #4）。
 *
 * 端点：
 * - `GET  /api/sessions/{id}/summary`  取已有总结；没生成过 → 404（前端转成 null）
 * - `POST /api/sessions/{id}/summary`  重新生成（仅剧本场次；自由对话会返回 400）
 *
 * 与 `api/evaluation.ts` 同一写法：原生 fetch、失败抛带后端中文文案的 Error。
 * 字段名与后端 `SummaryResult` 一一对应，中间不做重命名。
 */

/** 单轮记录，对应后端 `SummaryResult.QuestionNote`。 */
export interface SummaryQuestionNote {
  index: number;
  question: string;
  /** 核心论点 */
  points: string[];
  /** 卡壳点 */
  blockers: string[];
  /** 作答深度：深 / 中 / 浅 */
  depth: string;
}

/** 知识点覆盖，对应后端 `SummaryResult.Coverage`。 */
export interface SummaryCoverage {
  covered: string[];
  missing: string[];
}

/** 用词记录，对应后端 `SummaryResult.Vocabulary`。 */
export interface SummaryVocabulary {
  good: string[];
  improve: string[];
}

/** 一场面试的对话总结，对应后端 `SummaryResult`。 */
export interface DialogueSummary {
  sessionId: number;
  /** ok = 生成成功；failed = 生成失败（error 里有原因） */
  status: "ok" | "failed";
  overview: string | null;
  questions: SummaryQuestionNote[];
  coverage: SummaryCoverage;
  vocabulary: SummaryVocabulary;
  model: string | null;
  createdAt: string | null;
  error: string | null;
}

/** 从失败响应里挖出后端的 `detail` 文案（与评估线同一实现）。 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { detail?: string; message?: string; error?: string };
        const message = body.detail ?? body.message ?? body.error;
        if (message) return message;
      } catch {
        return text.slice(0, 200);
      }
    }
  } catch {
    // 读 body 都失败，往后退到状态码
  }
  return `HTTP ${response.status}`;
}

/**
 * 取已有总结。
 *
 * 404 是「还没生成」的正常状态（结束面试时后台还在跑），返回 `null` 交给调用方轮询；
 * 其余非 2xx 一律抛错。
 */
export async function fetchSummary(sessionId: number): Promise<DialogueSummary | null> {
  let response: Response;
  try {
    response = await fetch(`/api/sessions/${encodeURIComponent(String(sessionId))}/summary`);
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as DialogueSummary;
}

/** 触发 / 重跑总结（覆盖式，一场只有一份）。非 2xx 抛后端 `detail` 文案。 */
export async function runSummary(sessionId: number): Promise<DialogueSummary> {
  let response: Response;
  try {
    response = await fetch(`/api/sessions/${encodeURIComponent(String(sessionId))}/summary`, {
      method: "POST",
    });
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as DialogueSummary;
}
