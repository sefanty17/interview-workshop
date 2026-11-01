/**
 * 表达分析 API（后端 `ExpressionController`，文本版）。
 *
 * 端点：
 * - `GET /api/sessions/{id}/expression`  现算现取，无缓存也无副作用
 *
 * 字段名与后端 `ExpressionReport` 一一对应，中间不做重命名。
 * 与 `api/evaluation.ts` 的差别：这里没有 POST——算分不调大模型，
 * 「查」就是「算」，不需要「先 GET 再触发生成」两段式。
 */

/** 单项指标，对应后端 `ExpressionReport.Metric`。 */
export interface ExpressionMetric {
  /** 固定四项：filler 口头禅 / sentence 句子组织 / pace 作答节奏 / richness 表达充分度 */
  code: string;
  label: string;
  /** 该项得分；`null` = 本场数据不足（如没有时间戳时的作答节奏），界面显示「—」 */
  score: number | null;
  /** 观测值的可读形式，如「2.6 次/百字」「平均 5.0 秒」 */
  value: string;
  comment: string;
}

/** 一场面试的表达分析，对应后端 `ExpressionReport`。 */
export interface ExpressionReport {
  sessionId: number;
  sampleCount: number;
  overallScore: number;
  /** 数据来源，目前恒为 `text` */
  mode: string;
  metrics: ExpressionMetric[];
  /** 出现最多的口头禅，形如 `嗯×4`；没有则空数组 */
  topFillers: string[];
  suggestions: string[];
  note: string;
}

/**
 * 取表达分析。
 *
 * 自由对话场次与作答不足 2 次的场次后端返回 400 —— 这是正常的「不适用」而不是故障，
 * 所以返回 `null` 让报告页安静地隐藏该板块，不弹错误。
 */
export async function fetchExpression(sessionId: number): Promise<ExpressionReport | null> {
  let response: Response;
  try {
    response = await fetch(`/api/sessions/${encodeURIComponent(String(sessionId))}/expression`);
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (response.status === 400 || response.status === 404) return null;
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as ExpressionReport;
}
