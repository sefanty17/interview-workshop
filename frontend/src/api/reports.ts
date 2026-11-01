/**
 * 成长曲线与提升路径 API（课设指标 ⑥）。
 *
 * 端点：
 * - `GET /api/reports/trend`  剧本场次的分维度趋势 + 短板推荐
 *
 * 字段名与后端 `TrendView` 一一对应，中间不做重命名；只用 fetch，失败抛带中文文案的 Error。
 */

/** 单个维度（复用评估维度口径：technical / depth / logic / fit）。 */
export interface TrendDimension {
  code: string;
  label: string;
  score: number;
  comment: string | null;
}

/** 曲线上的一个点（一场已出评估的剧本面试）。 */
export interface TrendPoint {
  sessionId: number;
  /** `yyyy-MM-dd HH:mm:ss`，展示时再截断 */
  date: string;
  title: string;
  jobCode: string | null;
  overallScore: number;
  dimensions: TrendDimension[];
}

/** 一条知识库命中（与后端 `KnowledgeHit` 同形）。 */
export interface TrendKnowledgeHit {
  chunkId: number;
  docId: number;
  docCode: string;
  docTitle: string;
  heading: string;
  text: string;
  score: number;
  vectorScore: number;
  bm25Score: number;
}

/** 一个短板维度的推荐清单。 */
export interface TrendRecommendation {
  dimCode: string;
  label: string;
  averageScore: number;
  /** 实际拿去检索的查询串（维度评语优先），答辩时可解释推荐依据 */
  query: string;
  hits: TrendKnowledgeHit[];
}

export interface TrendView {
  /** 画折线所需的最少场次 */
  requiredPoints: number;
  points: TrendPoint[];
  recommendations: TrendRecommendation[];
}

/** 拉趋势；后端不可达时抛错，由调用方决定是否静默降级。 */
export async function fetchTrend(): Promise<TrendView> {
  let response: Response;
  try {
    response = await fetch("/api/reports/trend");
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  return (await response.json()) as TrendView;
}
