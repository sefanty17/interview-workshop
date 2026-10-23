# E7b1 · AI 助手前端地基（api + store + 三个渲染组件）

> 主线写规格，子 agent 执行。E7b 拆两批，本批做**地基**，下一批做三个面板。

## 0. 前置（已就绪，直接用）

- 后端 17 个端点已上线（跑在 8080），SSE 走 `POST /api/editor/agent/conversations/{id}/chat`。
  契约见 `specs/E7a1-AI助手后端基座.md` §2 与 §3.3；已实测：`status` → `message_delta`×N → `done`（带 usage）。
- 事件类型（10 个，snake_case tag）：`status` / `message_delta` / `item_start` / `reasoning` /
  `tool_call` / `tool_result` / `pending_approval` / `done` / `conversation_title` / `error`。

## 1. 蓝本

| 蓝本 | 行数 | 落点 |
|---|---|---|
| `references/lingchat/src/stores/modules/agent/{state,actions,getters,index}.ts` | 112+575+17+17 | `frontend/src/store/agent.ts`（合并成一个 reactive 单例） |
| `references/lingchat/src/api/services/agent.ts`（先 find 确认路径） | — | `frontend/src/api/agent.ts` |
| `references/lingchat/src/components/script-editor/agent/MarkdownText.vue` | 46 | 同名新建 |
| `.../agent/AgentThinkingBlock.vue` | 50 | 同名新建 |
| `.../agent/AgentToolCard.vue` | 140 | 同名新建 |

## 2. 技术栈差异（逐条照做）

1. **无 Pinia** → 合并成一个模块级 `reactive` 单例 + 独立导出的 `computed`/函数，
   照 `src/store/scriptEditor.ts` 的写法。**不要**引入 Pinia。
2. **无 vue-i18n** → `t(...)` 删掉，文案直接写中文（从
   `references/lingchat/src/locales/zh-CN/scriptEditor.ts` 的 `agent*` 节点取原文）。
3. **无 Tauri** → `invoke(...)` 换成 `fetch` 调上面 17 个 REST 端点；
   SSE 用 `fetch` + `ReadableStream`（**先读 `src/api/chat.ts` 现有的 SSE 消费写法，照它的模式写**，
   不要引入 EventSource —— 那个不支持 POST + body）。
4. **state 结构照搬**：`ChatItem` / `ChatRound` / `ToolRun` / `ToolStatus` / `TokenUsage`
   等类型逐字搬（字段名不改），`sealed` 语义与注释一起搬——
   注释里写着「不封口的话，下一项的工具调用会把这一项正文折进折叠的思考块」，
   这是真机踩过的坑，别删。

## 3. 交付清单

### 3.1 `frontend/src/api/agent.ts`
17 个函数，一一对齐后端端点：
`getSettings / saveSettings / getDefaultDirs / listSkills / readSkill /
createConversation / listConversations / deleteConversation / renameConversation /
getMessages / resolveScriptKey / clearConversation / listArtifacts /
startChat(SSE) / stopChat / rewind / resolveApproval`
类型：`AgentSettings` / `AgentDefaultDirs` / `SkillInfo` / `ConversationInfo` /
`PersistedMessage` / `AgentArtifact` / `AgentEvent`（10 个变体的联合类型）。

`startChat` 签名建议：`startChat(conversationId, message, onEvent: (e: AgentEvent) => void, signal?): Promise<void>`，内部把 SSE 的 `data:` 行解析成对象后回调。

### 3.2 `frontend/src/store/agent.ts`
- state：会话列表 / 当前会话 id / 消息列表 / 流式中标记 / 设置 / 默认目录 / 技能列表 / 待审批
- actions：建会话、切会话、删会话、改标题、发消息（调 `startChat` 并按事件归约）、停止、回退、清空、
  审批、加载设置/保存设置
- **事件归约是核心**：
  - `message_delta` → 追加到当前 `ChatItem` 最后一个未封口 `ChatRound.content`
  - `reasoning` → 追加到该 round 的 `reasoning`
  - `item_start` → 封口当前 round（`sealed=true`）并新开一个 round
  - `tool_call` → 在该 round 追加 `ToolRun{status:"running"}`
  - `tool_result` → 按 `callId` 找 ToolRun，置 `status`/`output`
  - `pending_approval` → `status:"pending"` + `requestId`
  - `done` → 该 `ChatItem.streaming=false`，落 `usage`
  - `error` → `ChatItem.error`
  - `status` → `ChatItem.status`
- 错误与中断：`AbortController` 存 store，`stopChat` 同时打后端与 abort 本地流。

### 3.3 三个组件
- `MarkdownText.vue`：46 行逐字搬（依赖什么 markdown 库就先查原版 `package.json`；
  **若本项目没装该库，不要自己装**——用最简的安全渲染（`white-space: pre-wrap` + 基础换行），
  在报告里写明降级，V2 再补）。
- `AgentThinkingBlock.vue`：50 行逐字搬 + 文案中文直写。
- `AgentToolCard.vue`：140 行逐字搬 + 文案中文直写；
  `status` 状态机（running/pending/done/error/denied）配色对齐原版。

## 4. 约束

1. 不许跑任何 git 命令（含只读）。
2. **只准新建**：`frontend/src/api/agent.ts`、`frontend/src/store/agent.ts`、
   `frontend/src/components/script-editor/agent/`（三个 vue）。
   **不许改**既有任何文件（本批不接线，接线在 E7b2）。
3. 不许加 `any` / `@ts-ignore` / 删类型；不许引入新 npm 依赖。
4. 不许动 `backend/`、`store/scriptEditor.ts`、`api/editor.ts`。

## 5. 验收

### 5.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 5.2 真机（不接页面也能验 store）
写一个一次性临时脚本（系统临时目录，不进仓库）：
1. 在浏览器里 import 应用实际使用的 `store/agent.ts` 模块（**注意 HMR 双实例坑**：
   从 `performance` 资源条目解析应用真实使用的模块 URL 再 import）。
2. 建会话 → 发消息 "你好" → 等 `done`。
3. 断言：`ChatItem.streaming === false`、`rounds` 非空、`content` 非空、`usage` 存在。
4. 收尾：删掉该会话，库里无残留。
把断言结果贴进报告。

## 6. 报告

改动文件清单（含行数）、构建原始输出末尾、真机 store 断言结果、
markdown 库是否可用（走了逐字搬还是降级）、全部偏离规格之处。诚实报告。
