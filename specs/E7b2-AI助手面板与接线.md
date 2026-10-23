# E7b2 · AI 助手三个面板 + 接线（收尾批）

> 主线写规格，子 agent 执行。这是 E7 的最后一块。

## 0. 前置（已就绪）

- 后端 17 端点 + 真实 LLM 工具循环，245 测试全绿，SSE 实测 `status → message_delta → done(usage)`。
- E7b1 已完成并验收：`api/agent.ts`(365)、`store/agent.ts`(727)、
  `components/script-editor/agent/{MarkdownText(18), AgentThinkingBlock(49), AgentToolCard(143)}`。
  注意：`MarkdownText` 是**降级版**（本项目没装 markdown 库），只做基础换行渲染。
- 编辑器现有 6 个 Tab：章节流程 / 剧本设置 / 校验 / 素材 / 角色 / 外观。

## 1. 蓝本

| 蓝本 | 行数 | 落点 |
|---|---|---|
| `references/lingchat/src/components/script-editor/agent/AgentChatPanel.vue` | 595 | 同名新建 |
| `.../agent/AgentChapterPreview.vue` | 350 | 同名新建 |
| `.../agent/AgentSettingsPanel.vue` | 271 | 同名新建 |
| `references/lingchat/src/components/script-editor/EditorHeader.vue` | — | 对照加 Tab |
| `references/lingchat/src/components/views/ScriptEditor.vue` | — | 对照接线方式 |

## 2. 交付清单

### 2.1 三个面板（逐字改写）
- **`AgentChatPanel.vue`**：会话列表侧栏 + 消息流 + 输入框 + 停止按钮 + 工具卡渲染。
  依赖 `store/agent.ts` 与 `api/agent.ts`，**不要另起状态**。
- **`AgentChapterPreview.vue`**：章节内容预览（在消息流里展示助手产出的章节草稿，
  支持"应用到章节"）。只读展示 + 一个应用按钮，不自己做 Diff 引擎。
- **`AgentSettingsPanel.vue`**：助手设置（provider / sandboxDir / autoApproveCommands /
  allowAnyPath / maxToolRounds / systemPrompt / enableThinking），走 `getSettings`/`saveSettings`。

**技术栈差异（逐条照做）**：
1. 无 i18n：`t(...)` 删掉，文案直接写中文（从
   `references/lingchat/src/locales/zh-CN/scriptEditor.ts` 的 `agent*` 节点取原文）。
2. 无 Tauri：`invoke`/`convertFileSrc` 全部换成 `api/agent.ts` 的函数；
   文件路径类展示直接用后端返回的字符串。
3. 无 Pinia：状态全部从 `store/agent.ts` 取（E7b1 已建）。
4. 无 markdown 库：用 E7b1 的 `MarkdownText`（降级版），别自己装库。

### 2.2 接线
1. **`EditorHeader.vue`**：tabs 数组加两项
   `{ key: "agent-chat", label: "AI 助手", icon: <选一个现有语义合适的> }`、
   `{ key: "agent-settings", label: "助手设置", icon: "setting" }`。
   位置按原版 8 Tab 顺序：flow / config / characters / assets / validate / agent-chat / agent-settings / appearance
   **但既有 6 个 Tab 的相对顺序不要动**（V5 已验收），只在 `appearance` 之前插入这两个。
   同时删掉注释里"agent-chat / agent-settings 不在课设范围"那句错误说明。
2. **`views/ScriptEditorView.vue`**：在 Tab 切换区渲这两个面板
   （`agent-chat` → `AgentChatPanel`，`agent-settings` → `AgentSettingsPanel`）。
   面板在章节编辑态（`store.level === 'chapter'`）下的行为：保持不变（AI 助手只在流程层显示）。
3. **`store/scriptEditor.ts`**：`ENABLED_TABS` 数组补上 `"agent-chat", "agent-settings"`
   （本批**允许**改这一行）。

## 3. 约束

1. 不许跑任何 git 命令（含只读）。
2. 允许改的文件：`components/script-editor/agent/`（新建两个面板）、
   `components/script-editor/panels/EditorHeader.vue`、`views/ScriptEditorView.vue`、
   `store/scriptEditor.ts`（仅 `ENABLED_TABS` 一行）。
   **不许改** `api/`、`store/agent.ts`（已验收）、`backend/`、`components/game/`、`components/settings/`。
3. 不许加 `any` / `@ts-ignore` / 删类型；不许引入新 npm 依赖。
4. 不许改中文文案以外的既有内容；不许动 SSE 事件协议与后端。

## 4. 验收

### 4.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 4.2 真机（playwright-core + 系统 Chrome，1440×900）
1. 打开 `/script-editor` → 点进 `java-strict`。
2. 断言：顶栏 **8 个 Tab**，且「AI 助手」「助手设置」可见。
3. 点「AI 助手」→ 断言面板渲染（会话列表或空态 + 输入框），控制台 0 报错。
4. 在输入框发一条「你好」→ 等 15 秒 → 断言：
   - 消息流出现 user 消息 + assistant 回复（文本非空）；
   - 若出现工具卡，其状态为 done/error（不是永久 running）。
5. 点「助手设置」→ 断言表单渲染出 7 个字段，改一个值保存 → 刷新 → 值仍在（读写通）。
6. 截图存 `screenshots/e7b2/`：`01-agent-chat.png`、`02-agent-reply.png`、`03-agent-settings.png`。
7. 收尾：删掉验收时建的会话，库里无残留（`curl --noproxy '*' localhost:8080/api/editor/agent/conversations` 应与开始时一致）。

## 5. 报告

改动文件清单（含行数）、构建原始输出末尾、真机 7 步逐条、截图路径、
AI 助手实测回复原文摘要（证明真的通了）、数据还原、全部偏离规格之处。诚实报告。
