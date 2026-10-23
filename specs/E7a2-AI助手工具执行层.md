# E7a2 · AI 助手工具执行层（8 工具 + 沙箱 + 路由 + LLM 循环）

> 主线写规格，子 agent 执行。本批只做**后端**，在 E7a1 基座之上接线。

## 0. 前置

E7a1 已完成：`agent/` 包骨架、两张表（`skill_agent_conversation` / `skill_agent_message`）、
`SkillAgentEvent` 事件协议、17 个端点（`start_chat` 目前是三条占位事件的 SSE 骨架）。
本批把 `start_chat` 换成**真实的 LLM 工具调用循环**，并落地工具实现。

## 1. 蓝本

| 蓝本 | 行数 | 用途 |
|---|---|---|
| `references/lingchat/crates/ling-chat-main/src/ai_service/skill_agent/tools.rs` | 735 | 工具定义与分发 |
| `.../file_tools.rs` | 664 | 文件工具（list/read/write/edit/delete） |
| `.../command_executor.rs` | 784 | 命令执行与审批 |
| `.../router.rs` | 300 | 意图路由 |
| `.../skills.rs` | 200 | 技能库扫描 |
| `.../core.rs` | 887 | 运行循环（本批主战场） |

## 2. 工具清单（8 个，严格对齐 `tools.rs:183-192`）

| 工具名 | 行为 | 蓝本行 |
|---|---|---|
| `list_skills` | 列出技能库 | tools.rs:183 |
| `read_skill` | 读某个技能内容 | tools.rs:184 |
| `validate_script` | 跑剧本校验器 | tools.rs:185 |
| `list_files` | 列目录 | tools.rs:186 |
| `read_file` | 读文件 | tools.rs:187 |
| `write_file` | 写文件 | tools.rs:188 |
| `edit_file` | 编辑文件 | tools.rs:189 |
| `delete_file` | 删文件 | tools.rs:190 |

**`execute_command`（tools.rs:192）本批不做**，此前已确认暂缓实现（前置依赖与授权未定）。
在工具分发处留一个显式分支：收到 `execute_command` 时返回
`{ ok:false, error:"execute_command 未启用" }`，并在代码注释里写明原因。
**不要偷偷实现它。**

## 3. 交付清单

### 3.1 工具层（新建，放 `agent/` 包）
- `SkillAgentTools.java`：工具定义（name / description / JSON Schema 参数）+ 分发
  （对应 `tools.rs` 的 `tool_*` 与 `name_refusal` 归一化拒绝信息）
- `SkillAgentFileTools.java`：五个文件工具的实现
  （对应 `file_tools.rs`，含路径归一化与越界拒绝）

**沙箱约束（必须做）**：
- 所有文件工具的根目录取自 `SkillAgentConfig.resolveSandboxDir()`；
- 入参 path 必须 `normalize()` 后 `startsWith(root)`，否则返回
  `{ ok:false, error:"路径越界" }`，不许抛异常；
- 拒绝信息用词照搬 `tools.rs` 的 `name_refusal` / `tools_draft_scope_refusal_info.md`
  / `tools_foreign_package_refusal_info.md` 三个来源的措辞（先读再写）。

### 3.2 路由层
- `SkillAgentRouter.java`：对应 `router.rs`（提示词见
  `prompts/router_build_router_system.md`），只搬**逻辑骨架与本项目的落点**，
  不要照抄 Rust 类型签名。

### 3.3 LLM 循环（改 `SkillAgentService`）
把 `start_chat` 的占位三条事件换成真实循环：
1. 发 `status`（"思考中…"）
2. 调 LLM（走项目既有 LLM 客户端，先读 `ChatService`/现有 LLM 封装，
   **复用它，不要新写一套 HTTP 调用**）
3. 有 `reasoning` → 推 `reasoning` 事件
4. 有文本增量 → 推 `message_delta`
5. 有工具调用 → 先推 `tool_call`，执行后推 `tool_result`，
   再带着结果回到第 2 步（受 `maxToolRounds` 限制，`-1` 表示不限）
6. 结束推 `done`（带 `usage`）
7. 每次运行前重置 `cancelled`；循环中检查它，被 stop 就退出并发 `status`（"已停止生成"）

**审批**：`execute_command` 未启用，故 `pending_approval` / `resolve_approval`
本批保持 E7a1 的骨架语义即可，但 map 与取消/超时路径要写好，别留 TODO。

### 3.4 prompt 文件位置修复（必须做）
E7a1 把 26 个 md 放到了 `src/main/java/.../agent/prompts/`，maven **不会**把它们打进 jar。
本批把它们**移动**到 `backend/src/main/resources/agent/prompts/`，
并相应调整读取路径（用 classpath 读取，不要依赖磁盘绝对路径）。
移动后确认 `src/main/java/.../agent/prompts/` 已不存在。

## 4. 约束

1. 不许跑任何 git 命令（含只读）。
2. 后端只能用 `bash scripts/mvn.sh ...`，**禁止裸 mvn**。
3. **只准动** `backend/src/main/java/cn/edu/zjut/interview/agent/…`、
   `backend/src/main/resources/agent/prompts/…`、`backend/src/main/resources/schema-h2.sql`（仅追加）。
   前端 `frontend/` 一律不许动（别的 agent 在改）。
4. 不许改既有表的任何列。
5. 不许引入新依赖。
6. 不许加 `any` / `@ts-ignore`（本批无 TS）。
7. 不许实现 `execute_command`。
8. 单测不许留脏数据。

## 5. 验收

### 5.1 构建
`bash scripts/mvn.sh test` → 全绿（基线 237，只许增加）。

### 5.2 单测
在 `SkillAgentServiceTest` 之外新建 `backend/src/test/java/.../agent/SkillAgentToolsTest.java`，覆盖：
1. `list_skills` 在空目录返回 `[]`，有 2 个技能目录时返回 2 条且字段齐全
2. `read_skill` 读到内容与落盘一致；不存在的技能 → `ok:false` 且 error 非空
3. `write_file` 后 `read_file` 能读回同样内容
4. `edit_file` 把 old 替换成 new；old 不存在 → `ok:false`
5. `delete_file` 后文件不存在；再删 → `ok:false`
6. **路径越界**：`../` 与绝对路径都被拒，且不抛异常
7. `validate_script` 对已知坏剧本返回非空诊断；对正常剧本 `errorCount` 为 0
8. 未知工具名 → `ok:false` 且 error 含拒绝措辞
9. 收尾清空测试数据

### 5.3 静态
- `test -d backend/src/main/java/cn/edu/zjut/interview/agent/prompts` 应为**假**（已移走）
- `ls backend/src/main/resources/agent/prompts | wc -l` → 26

### 5.4 curl（打包重启后，全部 `--noproxy "*"`）
```bash
curl -s -X POST localhost:8080/api/editor/agent/conversations -H 'Content-Type: application/json' -d '{"title":"tmp-a2"}'
# 记下 id，下面用 {id}
curl -s -N --max-time 8 -X POST localhost:8080/api/editor/agent/conversations/{id}/chat \
     -H 'Content-Type: application/json' -d '{"message":"你好"}'
# 期望：一条 status、若干 message_delta、一条 done（带 usage 或 null）
curl -s localhost:8080/api/editor/agent/conversations/{id}/messages
# 期望：至少 1 条 user + 1 条 assistant
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/editor/agent/conversations/{id}   # 204
curl -s localhost:8080/api/editor/agent/conversations      # 不含 tmp-a2
```
若 DeepSeek 不可用（离线/限流），SSE 必须走**降级路径**：至少发一条 `status` 说明失败原因 +
一条 `done`，**不许空流、不许挂住、不许 500**。在报告里写明你走的是正常路径还是降级路径。

## 6. 报告

改动文件清单（含行数）、mvn test 原始输出末尾、单测清单、curl 逐条结果、
prompt 文件移动前后路径对照、走的是正常还是降级路径、数据还原情况、
全部偏离规格之处。诚实报告：没做到的直接说没做到。
