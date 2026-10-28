/**
 * 多维评估报告 API（后端 E1 已交付，本文件是前端 E2 的取数入口）。
 *
 * 端点：
 * - `GET  /api/sessions/{id}/evaluation`  取已有评估；场次没评估过 → 404（前端转成 null）
 * - `POST /api/sessions/{id}/evaluate`    触发 / 重跑评估（会真的调一次大模型，别乱点）
 *
 * 字段名与后端 `EvaluationResult` / `DimensionScore` 一一对应，中间不做重命名。
 * 写法与 `api/knowledge.ts` 一致：原生 fetch、失败抛带后端中文文案的 Error，不引第三方库。
 */

/** 单个维度的分与评语，对应后端 `EvaluationResult.DimensionScore`（code/label/score/comment）。 */
export interface DimensionScore {
  code: string;
  label: string;
  score: number;
  comment: string | null;
}

/** 一次评估的完整结果，对应后端 `EvaluationResult`。后端可空字段一律落成 `| null`。 */
export interface EvaluationResult {
  sessionId: number;
  jobCode: string | null;
  overallScore: number | null;
  summary: string | null;
  strengths: string[];
  weaknesses: string[];
  suggestions: string[];
  dimensions: DimensionScore[];
  model: string | null;
  createdAt: string | null;
}

/**
 * 从失败响应里挖出后端的 `detail` 文案。
 *
 * `EvaluationException` 被控制器落成 `{code, detail, status}`（见后端 EvaluationController），
 * 中文原因只在 `detail` 里；读不到就退回 `HTTP {status}`，保证用户至少看到状态码。
 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { detail?: string; message?: string; error?: string };
        const message = body.detail ?? body.message ?? body.error;
        if (message) return message;
      } catch {
        // 不是 JSON（比如 Spring 默认错误页），原样截一段
        return text.slice(0, 200);
      }
    }
  } catch {
    // 读 body 都失败，往后退到状态码
  }
  return `HTTP ${response.status}`;
}

/**
 * 取已有评估。
 *
 * 404 是「还没评估过」的正常状态而不是错误，所以这里返回 `null` 交给调用方去触发 POST；
 * 其余非 2xx 一律抛错（会被报告页落成可见的重试按钮）。
 */
export async function fetchEvaluation(sessionId: number): Promise<EvaluationResult | null> {
  let response: Response;
  try {
    response = await fetch(`/api/sessions/${encodeURIComponent(String(sessionId))}/evaluation`);
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as EvaluationResult;
}

/** 触发 / 重跑评估（覆盖式，一场最终只有一份）。非 2xx 抛后端 `detail` 文案。 */
export async function runEvaluation(sessionId: number): Promise<EvaluationResult> {
  let response: Response;
  try {
    response = await fetch(`/api/sessions/${encodeURIComponent(String(sessionId))}/evaluate`, {
      method: "POST",
    });
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) throw new Error(await readError(response));
  return (await response.json()) as EvaluationResult;
}
