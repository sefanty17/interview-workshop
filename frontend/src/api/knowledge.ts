/**
 * 岗位知识库 API（后端 K1 写入 + K3 检索）。
 *
 * 端点：
 * - `GET    /api/knowledge/docs`                      文档列表（含片段/向量计数）
 * - `POST   /api/knowledge/docs/{code}/markdown`      上传 markdown，后端切分后按指纹同步
 * - `POST   /api/knowledge/docs/{code}/reindex`       重建向量
 * - `DELETE /api/knowledge/docs/{code}`               删文档
 * - `GET    /api/knowledge/search`                    混合检索（试搜）
 * - `GET/PUT /api/interviewers/{code}/knowledge`      角色挂了哪些文档
 *
 * 写法与 `api/settings.ts` 一致：原生 fetch、失败抛带后端中文文案的 Error，不引第三方库。
 */

/** 知识库文档视图，字段与后端 KnowledgeDocView 一一对应。 */
export interface KnowledgeDocView {
  code: string;
  title: string;
  category: string | null;
  sourcePath: string | null;
  description: string | null;
  enabled: boolean;
  chunkCount: number;
  vectorCount: number;
  model: string | null;
  dim: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface KnowledgeHit {
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

export interface KnowledgeSearchResult {
  query: string;
  mode: string;
  scopeSize: number;
  topK: number;
  hits: KnowledgeHit[];
}

export interface ChunkSyncResult {
  count: number;
  reused: number;
  inserted: number;
  deleted: number;
  reembedded: number;
}

/** 从失败响应里挖出后端的 `error` / `message` 文案（拿不到就退回状态码）。 */
async function readError(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { error?: string; detail?: string; message?: string };
        // Spring 的 ResponseStatusException 落成 ProblemDetail，中文原因在 detail 里；
        // 只读 error/message 会拿到 "Bad Request"，用户看不懂为什么失败
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

/** 统一的请求包装：网络层失败转成中文 Error，非 2xx 抛后端文案。 */
async function request(path: string, init?: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "无法连接后端");
  }
  if (!response.ok) {
    throw new Error(await readError(response));
  }
  return response;
}

/** 文档列表。 */
export async function listKnowledgeDocs(): Promise<KnowledgeDocView[]> {
  const response = await request("/api/knowledge/docs");
  return (await response.json()) as KnowledgeDocView[];
}

/** 上传一篇 markdown 原文，后端切分并按指纹增量同步片段。 */
export async function uploadMarkdown(
  code: string,
  body: {
    title?: string;
    category?: string;
    sourcePath?: string;
    markdown: string;
  },
): Promise<ChunkSyncResult> {
  const response = await request(`/api/knowledge/docs/${encodeURIComponent(code)}/markdown`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return (await response.json()) as ChunkSyncResult;
}

/** 重建该文档的全部向量。 */
export async function reindexDoc(code: string): Promise<{ count: number }> {
  const response = await request(`/api/knowledge/docs/${encodeURIComponent(code)}/reindex`, {
    method: "POST",
  });
  return (await response.json()) as { count: number };
}

/** 删除文档（片段与向量由后端级联清理）。 */
export async function deleteKnowledgeDoc(code: string): Promise<void> {
  await request(`/api/knowledge/docs/${encodeURIComponent(code)}`, { method: "DELETE" });
}

/**
 * 混合检索（试搜）。
 *
 * 查询串一律走 `URLSearchParams`：中文查询直接拼进 URL 会被 Tomcat 按非法字符拒掉（400），
 * 编码交给它统一处理。
 */
export async function searchKnowledge(
  q: string,
  opts?: {
    interviewerCode?: string;
    jobCode?: string;
    topK?: number;
    mode?: "hybrid" | "bm25" | "vector";
    minVector?: number;
    minBm25?: number;
  },
): Promise<KnowledgeSearchResult> {
  const params = new URLSearchParams({ q });
  if (opts?.interviewerCode) params.set("interviewerCode", opts.interviewerCode);
  if (opts?.jobCode) params.set("jobCode", opts.jobCode);
  if (opts?.topK != null) params.set("topK", String(opts.topK));
  if (opts?.mode) params.set("mode", opts.mode);
  if (opts?.minVector != null) params.set("minVector", String(opts.minVector));
  if (opts?.minBm25 != null) params.set("minBm25", String(opts.minBm25));

  const response = await request(`/api/knowledge/search?${params.toString()}`);
  return (await response.json()) as KnowledgeSearchResult;
}

/** 某个考官当前挂了哪些文档（后端回 `{ docCodes: [...] }`）。 */
export async function getRoleKnowledge(interviewerCode: string): Promise<string[]> {
  const response = await request(
    `/api/interviewers/${encodeURIComponent(interviewerCode)}/knowledge`,
  );
  const body = (await response.json()) as { docCodes?: string[] };
  return body.docCodes ?? [];
}

/** 覆盖式保存某个考官挂载的文档集合（后端回 204，无 body）。 */
export async function saveRoleKnowledge(
  interviewerCode: string,
  docCodes: string[],
): Promise<void> {
  await request(`/api/interviewers/${encodeURIComponent(interviewerCode)}/knowledge`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ docCodes }),
  });
}
