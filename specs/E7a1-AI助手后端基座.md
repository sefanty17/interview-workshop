# E7a1 · AI 助手后端基座（事件协议 + 会话落表 + 17 端点）

> 主线写规格，子 agent 执行。本批只做**后端**。前端六件套在 E7b。

## 0. 背景

原版剧本编辑器右侧有一个 AI 助手（Skill Agent），Rust 侧实现在
`references/lingchat/crates/ling-chat-main/src/ai_service/skill_agent/`，共 4901 行：

```
command_executor.rs 784    file_tools.rs 664    tools.rs 735
core.rs           887    db.rs       260    router.rs 300
role.rs           471    skills.rs   200    title.rs  81
events.rs          66    prompts/  22 个 .md
```

前端 `references/lingchat/src/components/script-editor/agent/` 六件套通过
Tauri `invoke` 调这 17 个命令。本项目要把它们转成 REST + SSE。

## 1. 蓝本（先读再写）

| 蓝本 | 用途 |
|---|---|
| `references/lingchat/crates/ling-chat-main/src/ai_service/skill_agent/events.rs` | 流式事件协议（唯一真相源，66 行，逐字搬） |
| `.../db.rs` | 会话/消息表结构与 CRUD |
| `.../config.rs` | 配置加载与沙箱目录解析 |
| `.../mod.rs` | 状态定义（审批 map / 取消标志 / 任务句柄） |
| `references/lingchat/src-tauri/src/app/commands.rs:176-192` | 17 个命令注册清单（命令名以此为准） |
| `references/lingchat/src/components/script-editor/agent/*.vue` | 前端调用方式（参数形状） |
| `.../prompts/*.md` | prompt 模板（本批只搬文件、不接线） |

## 2. 命令 → REST 端点映射（写死，不许自创）

| # | 原版命令 | 本批端点 |
|---|---|---|
| 1 | editor_agent_get_settings | `GET /api/editor/agent/settings` |
| 2 | editor_agent_save_settings | `PUT /api/editor/agent/settings` |
| 3 | editor_agent_get_default_dirs | `GET /api/editor/agent/default-dirs` |
| 4 | editor_agent_list_skills | `GET /api/editor/agent/skills` |
| 4b | editor_agent_read_skill | `GET /api/editor/agent/skills/{name}` |
| 5 | editor_agent_create_conversation | `POST /api/editor/agent/conversations` |
| 6 | editor_agent_list_conversations | `GET /api/editor/agent/conversations` |
| 7 | editor_agent_delete_conversation | `DELETE /api/editor/agent/conversations/{id}` |
| 8 | editor_agent_rename_conversation | `PUT /api/editor/agent/conversations/{id}/title` |
| 9 | editor_agent_get_messages | `GET /api/editor/agent/conversations/{id}/messages` |
| 10 | editor_agent_resolve_script_key | `POST /api/editor/agent/resolve-script-key` |
| 11 | editor_agent_clear_conversation | `POST /api/editor/agent/conversations/{id}/clear` |
| 12 | editor_agent_list_artifacts | `GET /api/editor/agent/conversations/{id}/artifacts` |
| 13 | editor_agent_start_chat | `POST /api/editor/agent/conversations/{id}/chat` → **SSE** |
| 14 | editor_agent_stop_chat | `POST /api/editor/agent/conversations/{id}/stop` |
| 15 | editor_agent_rewind | `POST /api/editor/agent/conversations/{id}/rewind` |
| 16 | editor_agent_resolve_approval | `POST /api/editor/agent/approvals/{requestId}` |

（原版注册清单里是 17 条，含 read_skill；以上合并后为 17 个端点。）

## 3. 交付清单

### 3.1 数据层
新建实体（包名 `cn.edu.zjut.interview.domain`）：

`AgentConversation`（表名 `skill_agent_conversation`）
- `id` Long
- `title` String(128) 可空
- `scriptKey` String(64) 可空 —— 列名 `script_key`
- `createdAt` / `updatedAt` LocalDateTime

`AgentMessage`（表名 `skill_agent_message`）
- `id` Long
- `conversationId` Long —— 列名 `conversation_id`
- `role` String(16)
- `content` CLOB
- `reasoning` CLOB 可空
- `promptTokens` / `completionTokens` / `cachedTokens` Long 可空
- `toolCalls` CLOB 可空（存 JSON 字符串）
- `toolCallId` String(64) 可空 —— 列名 `tool_call_id`
- `createdAt` LocalDateTime

仓储：`AgentConversationRepository`（`findByScriptKey`、`findAllByOrderByUpdatedAtDesc`）、
`AgentMessageRepository`（`findByConversationIdOrderByIdAsc`、`deleteByConversationId`、
`deleteByConversationIdAndIdGreaterThanEqual`）。

### 3.2 DDL
`backend/src/main/resources/schema-h2.sql` 末尾追加两张表的 `CREATE TABLE IF NOT EXISTS`
（H2 语法；将来切 MySQL 只换方言）。**不许改既有表的任何列。**

### 3.3 事件协议
新建 `agent/SkillAgentEvent.java`：严格按 `events.rs` 的
`#[serde(tag = "type", rename_all = "snake_case")]` 语义：
`status` / `message_delta` / `item_start`(index,total,title) / `reasoning` /
`tool_call`(call_id,tool,args,raw_args) / `tool_result`(call_id,tool,ok,output,error) /
`pending_approval`(request_id,tool,args) / `done`(final_text,usage)。
`Usage` 子对象（prompt_tokens / completion_tokens / cached_tokens）。

### 3.4 配置
`agent/SkillAgentConfig.java`：按 `config.rs` 的字段与默认值落地，
从项目既有 `ConfigService`/`ConfigKey` 读（先读 `ConfigService` 与 `ConfigKey` 现状再定；
若没有对应键，新增最小的键，不许硬编码）。

### 3.5 服务与控制器
`agent/SkillAgentService.java`：会话 CRUD + 消息 CRUD + 审批待决 map + 取消标志 + 任务句柄
（对应 `mod.rs` 的 `SkillAgentState`）。
`api/SkillAgentController.java`：`@RequestMapping("/api/editor/agent")`，17 个端点。

**本批 start_chat 只落骨架**：SSE 连接建立后推一条 `status`，再推一条
`message_delta`（内容为占位文案），最后推 `done`。真实 LLM 工具调用循环放 E7a2。
骨架必须能跑通，不许抛异常，不许空流。

## 4. 素材（本批一起拉）

原版素材已确认存在于 `references/lingchat/public/`：
- `audio_effects/`（23 个 `.wav`：厌恶/喜悦/伤心/生气/震惊/害羞/思考/聊天/叹气/疑问/愉快/无语/察觉…）
- `pictures/animation/`（16 个 `.webp` 气泡图：AI思考/高兴/生气2/流泪/流汗/惊讶/疑问/害羞/叹气/察觉/心动/慌乱/紧张/聊天/难为情）

要求：
1. 复制到 `frontend/public/` 下（保持相对路径 `audio_effects/*` 与 `pictures/animation/*`）。
2. 把 `src/controllers/emotion/config.ts` 里被 A1 批填成 `"none"` 的
   `bubbleImage` / `audio` 字段，按原版 `config.ts` 的文件名**逐条还原**成
   `/audio_effects/xxx.wav` 与 `/pictures/animation/xxx.webp`。
3. 原版 `config.ts` 里本来就是 `"none"` 的（如 `认真` / `平静` / `正常`）保持 `"none"`。
4. 复制完核对：命令 `ls frontend/public/audio_effects | wc -l` 应为 23，
   `ls frontend/public/pictures/animation | wc -l` 应为 16。

## 5. 约束

1. 不许跑任何 git 命令（含只读）。
2. 后端只能用 `bash scripts/mvn.sh ...`，**禁止裸 mvn**。
3. 不许改 `specs/`、`docs/`、`.workbuddy/`（含本规格）。
4. 不许改 `frontend/src/` 下除 `controllers/emotion/config.ts` 之外的任何文件。
5. 不许改既有表的列（只准新增两张表）。
6. 不许加 `any` / `@ts-ignore`（本批无 TS）。
7. 不许引入新依赖；prompt md 只搬文件不接线。
8. 单测不许留脏数据。

## 6. 验收

### 6.1 构建
`bash scripts/mvn.sh test` → 全绿（基线 228，只许增加）。

### 6.2 单测（新建 `backend/src/test/java/cn/edu/zjut/interview/agent/SkillAgentServiceTest.java`）
`@SpringBootTest` + `@ActiveProfiles("test")`，至少覆盖：
1. 建会话 → 列表返回 1 条，按 updated_at 倒序
2. 建会话带 scriptKey → `findByScriptKey` 能查到
3. 改标题 → 重新读取为新值
4. 插 3 条消息 → `getMessages` 按 id 升序返回 3 条
5. clear → 消息清空但会话还在
6. rewind(fromId) → 只删 fromId 及之后，之前的保留
7. 删会话 → 会话与其消息一起没了
8. 删除不存在的会话 → 抛异常（404）
9. 收尾清干净临时数据（`@AfterEach` 兜底）

### 6.3 curl（打包重启后）
```bash
# settings 默认可读
curl -s localhost:8080/api/editor/agent/settings
# 会话 CRUD
curl -s -X POST localhost:8080/api/editor/agent/conversations -H 'Content-Type: application/json' -d '{"title":"tmp-conv"}'
curl -s localhost:8080/api/editor/agent/conversations        # 含 tmp-conv
curl -s -X PUT localhost:8080/api/editor/agent/conversations/{id}/title -H 'Content-Type: application/json' -d '{"title":"tmp-conv-2"}'
curl -s localhost:8080/api/editor/agent/conversations/{id}/messages   # []
curl -s -X POST localhost:8080/api/editor/agent/conversations/{id}/clear
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/editor/agent/conversations/{id}   # 204
curl -s localhost:8080/api/editor/agent/conversations            # 不含 tmp-conv
# SSE 骨架
curl -s -N --max-time 4 -X POST localhost:8080/api/editor/agent/conversations/{id}/chat
# 期望：含 status / message_delta / done 三类事件
```
跑完 `game_data/characters/` 只剩 4 张原卡，库里无 `tmp-conv`。

### 6.4 前端（若时间允许）
`vue-tsc --noEmit` 0 error；`npm run build` 成功；房间页与设置页控制台 0 报错。

## 7. 报告

按第 6 节写：改动文件清单（含行数）、mvn test 原始输出末尾、单测清单、curl 逐条结果、
素材复制核对、数据还原情况、全部偏离规格之处。诚实报告。
