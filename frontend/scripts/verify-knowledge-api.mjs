/**
 * 验收：知识库 API 客户端（K5）的请求构造与错误处理。
 *
 * 为什么值得单独验：这一层最容易犯两类错，且页面上一眼看不出来——
 * ① 中文查询参数没编码（Tomcat 直接 400，只在真跑时暴露）；
 * ② 后端回 204 无 body 时仍然 `res.json()`（会抛 SyntaxError）。
 *
 * 做法：用 esbuild 把 `src/api/knowledge.ts` 打成临时 ESM，替换全局 fetch 成桩，
 * 断言 URL / method / body。不依赖后端、不依赖浏览器。
 *
 * 跑法（在 frontend/ 下）：node scripts/verify-knowledge-api.mjs
 */
import { build } from "esbuild";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const SRC = path.resolve("src/api/knowledge.ts");
const OUT = path.join(os.tmpdir(), `knowledge-api-${Date.now()}.mjs`);

await build({
  entryPoints: [SRC],
  outfile: OUT,
  bundle: true,
  format: "esm",
  platform: "node",
  logLevel: "silent",
});

const api = await import(pathToFileURL(OUT).href);
fs.rmSync(OUT, { force: true });

// ---------------- fetch 桩 ----------------

/** 记录每次调用的入参，并按 id 返回预设响应。 */
const calls = [];
let scripted = null;

globalThis.fetch = async (input, init = {}) => {
  calls.push({ url: String(input), init });
  if (scripted instanceof Error) throw scripted;
  const { status = 200, body = null } = scripted ?? {};
  // 204 不能带 body：Response 构造器会直接抛「Invalid response status code 204」
  if (status === 204 || body === null) {
    return new Response(null, { status });
  }
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
};

let passed = 0;
function check(name, condition, extra = "") {
  if (!condition) {
    console.error(`  ✗ ${name}${extra ? `：${extra}` : ""}`);
    process.exitCode = 1;
    return;
  }
  passed++;
  console.log(`  ✓ ${name}`);
}

// ---------------- 用例 ----------------

console.log("知识库 API 客户端：");

// 1. 中文查询必须编码
scripted = { body: { query: "内存", mode: "hybrid", scopeSize: 28, topK: 5, hits: [] } };
calls.length = 0;
await api.searchKnowledge("内存泄漏怎么排查", { jobCode: "java", mode: "bm25", topK: 3 });
check("检索走 GET /api/knowledge/search", calls[0].url.startsWith("/api/knowledge/search?"));
check(
  "中文查询被 URL 编码（不是裸中文）",
  calls[0].url.includes("q=%E5%86%85%E5%AD%98%E6%B3%84%E6%BC%8F%E6%80%8E%E4%B9%88%E6%8E%92%E6%9F%A5"),
  calls[0].url,
);
check("附带 jobCode / mode / topK", calls[0].url.includes("jobCode=java")
  && calls[0].url.includes("mode=bm25") && calls[0].url.includes("topK=3"), calls[0].url);

// 2. 文档列表解析
scripted = { body: [{ code: "java-jvm-memory", title: "JVM", chunkCount: 4, vectorCount: 4, dim: 512 }] };
const docs = await api.listKnowledgeDocs();
check("文档列表解析出数组", Array.isArray(docs) && docs.length === 1 && docs[0].code === "java-jvm-memory");

// 3. 上传 markdown：POST 到 /markdown，body 带原文
scripted = { body: { count: 4, reused: 3, inserted: 1, deleted: 1, reembedded: 0 } };
calls.length = 0;
const sync = await api.uploadMarkdown("java-jvm-memory", {
  title: "JVM", category: "java", sourcePath: "a.md", markdown: "# 标题\n正文",
});
check("上传走 POST .../markdown", calls[0].url === "/api/knowledge/docs/java-jvm-memory/markdown"
  && calls[0].init.method === "POST", `${calls[0].url} ${calls[0].init.method}`);
check("body 带 markdown 原文", JSON.parse(calls[0].init.body).markdown === "# 标题\n正文");
check("增量明细被解析", sync.inserted === 1 && sync.reused === 3);

// 4. 角色挂载保存：204 无 body 不能炸
scripted = { status: 204 };
calls.length = 0;
await api.saveRoleKnowledge("strict", ["a", "b"]);
check("挂载保存走 PUT", calls[0].url === "/api/interviewers/strict/knowledge"
  && calls[0].init.method === "PUT", calls[0].url);
check("body 是覆盖式 docCodes", JSON.parse(calls[0].init.body).docCodes.length === 2);
check("204 无 body 不抛异常", true);

// 5. 删除文档：204 无 body
scripted = { status: 204 };
calls.length = 0;
await api.deleteKnowledgeDoc("probe");
check("删除走 DELETE", calls[0].init.method === "DELETE" && calls[0].url === "/api/knowledge/docs/probe");

// 6. 后端错误文案原样抛出
scripted = { status: 400, body: { detail: "文档 code 不能为空" } };
let message = "";
try {
  await api.listKnowledgeDocs();
} catch (e) {
  message = e.message;
}
check("后端 detail 被原样带出", message === "文档 code 不能为空", message);

// 7. 连不上后端
scripted = new TypeError("fetch failed");
message = "";
try {
  await api.listKnowledgeDocs();
} catch (e) {
  message = e.message;
}
check("网络错误被转成 Error", message === "fetch failed", message);

console.log(`\n${passed} 条断言全过`);
