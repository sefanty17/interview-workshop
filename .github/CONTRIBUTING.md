# 协作说明

这个仓库是浙江工业大学计算机学院 JavaEE 课程设计项目，欢迎提 issue 和 PR。

## 分支

| 分支 | 用途 |
|---|---|
| `main` | 始终可运行。合并前必须通过下面的检查 |
| `feat/<批次号>-<简述>` | 新功能，例：`feat/E7b3-助手历史面板` |
| `fix/<简述>` | 缺陷修复 |

功能分支从 `main` 切出，完成后向 `main` 提 PR。

## 提交前必做

**前端类型检查**（硬门槛，必须 0 error）

```bash
cd frontend
npx vue-tsc --noEmit
```

**后端单测**

```bash
cd backend
bash ../scripts/mvn.sh test
```

当前基线：后端 245 个测试全绿，前端 `vue-tsc` 0 error。

## 代码约定

**前端**

- 不引入 Pinia、vue-i18n、markdown 库。store 用模块级 `reactive` 单例 + 独立导出 computed，见 `frontend/src/store/`。
- 样式优先用 `frontend/src/styles/lingchat.css` 里已定义的设计令牌（`@theme` 块）。**Tailwind v4 下未定义的 `--color-*` 会让 `bg-brand/*` 这类类名静默失效**——不报错、不生成样式、页面上就是缺一块。新增颜色必须先加进 `@theme`。
- 组件形态、尺寸、间距、动效曲线对齐 LingChat 原版，改动前先确认原版怎么写的。

**后端**

- 单测用 `@SpringBootTest` + `@ActiveProfiles("test")`。
- 表结构唯一生效源是 `backend/src/main/resources/schema-h2.sql`，改表必须同步。
- 不引入 WebClient / OkHttp，LLM 流式用 `java.net.http.HttpClient` + `BodyHandlers.ofLines()`。

**跨端契约（最容易被改坏的地方）**

SSE 事件名 `ai:reply` / `ai:thinking` / `status:reset` / `ai:error` / `script:narration` / `script:input` / `script:chapter-change` / `script:progress` / `script:end` / `script:modify-character` 与 LingChat 严格一致，前端 `frontend/src/api/chat.ts` 和 `views/InterviewRoomView.vue` 按这批名字消费。**改任何一个名字都会让房间收不到台词。** 缺 `status:reset` 前端会永久停在等待态。

Skill Agent 的 10 类事件变体同理，见 `backend/src/main/java/cn/edu/zjut/interview/agent/SkillAgentEvent.java`。

## 不要提交

- `application-local.yml`、`.env` —— 密钥
- `node_modules/`、`backend/target/`、`dist*/` —— 构建产物
- `references/` —— LingChat 参照源码，只读不入库
- `docs/课设材料/`、根目录 `*.docx` —— 报告与答辩材料，含个人信息

## PR 要求

- 标题说清改了什么，例：`fix(room): 情绪解析漏掉「冷淡」别名`
- 描述里写：改动的文件、验证方式（跑了什么命令、看到什么结果）
- 一个 PR 只做一件事。顺手重构请另开 PR

## 许可

贡献的代码按 [AGPL-3.0](../LICENSE) 分发。提交 PR 即表示同意以此协议发布。
