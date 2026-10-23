# E5b · 试玩预览（PreviewStage）

> 依据：`references/lingchat/src/components/script-editor/preview/PreviewStage.vue`（139 行）
> 与 `crates/ling-chat-main/src/api/script_editor/commands.rs:1445-2050`
> （`editor_start_preview` / `PreviewSession` / `editor_stop_preview` / `editor_preview_readiness`）。
> 与 `docs/00-项目方针.md` 冲突时以方针为准。本文只写「本批要改什么」，不写演进历史。

---

## 0. 语义（必须先读，改错方向整批作废）

原版试玩的三条语义，本批必须保住：

1. **走真引擎、真渲染层**。不是另写一套预览解释器：试玩画面 = 正式游玩画面，
   事件逐帧一致。原版靠 `eventQueue` + 全局 `GameStatus`。
2. **与正式游玩的差别只有两点**：`on_script_end(completed = false)`（不记通关）、
   不触发成就/解锁。落到本项目 = **试玩场次不算分、不出现在面试记录里**。
3. **进来搭台、出去还原**。原版试玩与自由对话共用**同一个** `GameStatus`，所以必须
   整体快照 + 还原（`PreviewSession`）。

**本项目的实现差异（必须在代码注释里写明，不算功能缺失）**：
本项目没有全局共享会话状态，每场次的状态天然隔离在 `interview_session` 行里。
因此「快照/还原」退化为 **「专用试玩场次（preview=true）+ 退出即删除」**——
玩家的真实场次一个字节都没被碰过，与原版目标一致，实现更简单。
原版那套 `PreviewSession` 的字段（line_len / generation / scene 快照）**不需要照抄**。

---

## 1. 后端

### 1.1 会话加 `preview` 标记

**文件 A：`backend/src/main/resources/schema-h2.sql`**

1. 在 `CREATE TABLE IF NOT EXISTS interview_session (` 的列清单末尾
   （`created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,` 之后、第一个 `CONSTRAINT` 之前）
   加一行：

```
  preview         BOOLEAN      DEFAULT FALSE,
```

2. 紧接着这张表的 `);` 之后另起一段（幂等，本文件每次启动都跑，老库没有这一列）：

```sql
-- 老库补列（幂等）：schema 每次启动都执行，CREATE TABLE IF NOT EXISTS 不会给已存在的表加列
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS preview BOOLEAN DEFAULT FALSE;
```

**文件 B：`backend/src/main/java/cn/edu/zjut/interview/domain/InterviewSession.java`**
照现有字段的写法（`@Column` + private + getter/setter）加：

```java
/** 试玩场次（剧本编辑器 PreviewStage）：不计分、不出现在场次列表，退出即删。 */
@Column(name = "preview")
private Boolean preview = Boolean.FALSE;
```

getter 名 `isPreview()`、setter 名 `setPreview(Boolean)`（与文件里其它 Boolean 字段一致，
不要自创 `getPreview`）。

**文件 C：`repository/InterviewSessionRepository.java`** 加两条派生查询：

```java
/** 场次列表（不带过滤，排除试玩场次）。 */
List<InterviewSession> findAllByPreviewFalseOrderByCreatedAtDesc();

/** 场次列表（按状态过滤，排除试玩场次）。 */
List<InterviewSession> findByStatusAndPreviewFalseOrderByCreatedAtDesc(String status);
```

**文件 D：`engine/InterviewEngine.java:479-480`** 把 `findAllByOrderByCreatedAtDesc()` /
`findByStatusOrderByCreatedAtDesc(status)` 换成上面那两条（**不要**删掉旧方法，仓库里还有其它调用点就留着；
只有这两行改调用）。

### 1.2 开演支持「从指定章节开始」

**文件 E：`dto/ScriptStartRequest.java`** 改成两参：

```java
public record ScriptStartRequest(String scriptCode, String fromChapter) {}
```

注释补一句：`fromChapter` 为 null/空 = 从 `intro_chapter` 开始（原版 `editor_start_preview` 的
`from_chapter: Option<String>` 同语义）。

**文件 F：`api/ScriptPlayController.java` 的 `start()`**
在 `session.setCurrentChapter(...)` 那一段改成：

```java
String from = request == null ? null : request.fromChapter();
if (from != null && !from.isBlank()) {
    boolean exists = chapterRepository.existsByScriptIdAndCode(script.getId(), from);
    if (!exists) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "剧本没有这一章：" + from);
    }
    session.setCurrentChapter(from);
} else {
    session.setCurrentChapter(script.getIntroChapter());
}
```

`chapterRepository` 若没有 `existsByScriptIdAndCode`，用文件里已有的查询方法先查列表再判空
（`InterviewChapterRepository` 已有按 script 查的方法），**不要新建同名重复方法**。

### 1.3 试玩场次的建与删

**文件 G（新建）：`service/PreviewService.java`**

只做两件事，**不许复制 `ChatService.buildSystemPrompt` 的逻辑**（重复造轮子）——
开场串与开场白一律复用 `chatService.createSession(...)`：

```java
public PreviewSessionView start(String scriptKey, String fromChapter);  // 建试玩场次
public void discard(Long sessionId);                                    // 删试玩场次
```

`start` 步骤（顺序固定）：

1. `scriptRepository.findByCode(scriptKey)`，查不到抛 `EditorNotFoundException`（`editor` 包里已有）。
2. 面试官：`script.getInterviewerId()` → `interviewerRepository.findById(...)；为空则
   `findByCode("strict")`，再为空则 `findFirstByOrderByIdAsc()`。岗位用
   `script.getJobCode()`，空则 `"java"`。
3. `chatService.createSession(new CreateSessionRequest(interviewerCode, jobCode, script.getCode()))`
   —— 复用开场串/开场白/落库那五步。
4. 取出刚建的 session：`sessionRepository.findById(r.sessionId())`，设
   `preview = true`、`title = "试玩 · " + script.getScriptName()`、
   `currentChapter = （fromChapter 非空 ? fromChapter : script.getIntroChapter()）`、
   `eventSequence = 0`，`save`。
5. 建之前先清场：`sessionRepository.findAllByPreviewFalseOrderByCreatedAtDesc()` **反过来**——
   即查出所有 `preview = true` 的场次（用 1.1 里加的查询：`findAllByPreviewFalseOrderByCreatedAtDesc` 不行，
   需要 `findAllByPreviewTrueOrderByCreatedAtDesc`，自己在仓库里加这条）逐个 `discard`，
   避免上一轮试玩残留堆积。
6. 返回 `PreviewSessionView(sessionId, scriptCode, fromChapter, interviewerCode, interviewerName, jobCode)`。

`discard(sessionId)`：
- 该场次 `preview != true` → 直接 return（不删真实场次，防御误调）；
- 删消息：`messageRepository` 若有 `deleteBySessionId(Long)` 就用；没有就
  `findBySessionIdOrderBySeqAsc(...)` 列表后 `deleteAll(list)`（沿用文件里已有的方法名）；
- `sessionRepository.deleteById(sessionId)`。

**文件 H（新建）：`dto/PreviewSessionView.java`** record，字段 camelCase：
`sessionId / scriptCode / fromChapter / interviewerCode / interviewerName / jobCode`。

**文件 I：`api/ScriptEditorController.java`** 挂两个端点（照文件里现有端点的写法与异常处理）：

```
POST   /api/editor/scripts/{key}/preview     body: {"fromChapter": "xxx"(可选)}  → PreviewSessionView
DELETE /api/editor/preview/{sessionId}       → {"ok": true}
```

`fromChapter` 校验：非 `^[A-Za-z0-9_-]{1,64}$` 直接 400（`EditorBadRequestException`）。

### 1.4 单测

**文件 J（新建）：`src/test/java/cn/edu/zjut/interview/editor/PreviewServiceTest.java`**
照 `ScriptEditorServiceScriptTest` 的写法（同包、同样的 `@SpringBootTest` 注解风格）。至少 4 个用例：

1. `start()` 建出的场次 `preview == true`，且 `title` 以「试玩 · 」开头；
2. `start()` 返回的 `sessionId` 查 `GET`-等价的 repository 能查到，且 `scriptId` 指向该剧本；
   `fromChapter` 非空时 `currentChapter` 等于它，空时等于 `introChapter`；
3. 带 `fromChapter` 建的两场，第二次建会把第一次删掉（试玩场次总数恒为 1）；
4. `discard()` 对 `preview=false` 的场次是 no-op（先建一个普通场次，调 discard，断言还在）。
5. 场次列表排除：`InterviewEngine`/repository 的 `findAllByPreviewFalse...` 不含试玩场次
   （用 1 里建的场断言）。

---

## 2. 前端

### 2.1 抽公共：剧本流消费器（**先做，禁止复制粘贴**）

`views/InterviewRoomView.vue:495-632` 的 `runScript()` 是「SSE 事件 → roomStore」的唯一实现。
试玩要用同一份。**新建 `composables/useScriptRunner.ts`**，把这段**原样搬过去**（注释一并保留），
改成导出函数：

```ts
export interface ScriptRunHooks {
  /** 角色标识 → 舞台 code（房间按本场人设解析；试玩按剧本绑定人设解析） */
  resolveRole(raw: unknown): string | null;
  /** 每句落历史（房间写 store.session.messages；试玩不传） */
  recordLine?(line: RoomLine): void;
  /** script:chapter-change */
  onChapter?(chapter: { code: string; name: string } | null): void;
  /** script:progress 的百分比 */
  onProgress?(pct: number): void;
  /** 引擎停在 input / choice 阻塞点 */
  onBlocked?(blocked: boolean): void;
  /** script:end */
  onEnd?(): void;
  /** ai:error */
  onError?(payload: unknown): void;
}

export async function runScriptStream(
  stream: AsyncGenerator<StreamEvent>,
  hooks: ScriptRunHooks,
): Promise<boolean>
```

搬的时候逐字保留每个 `else if` 分支与 `roomStore` 赋值（含 `pendingChoice` 的挂起与
`applyPendingChoice()` 的放出时机、`scriptBlocked` 置位、`buffered` 播放节奏）。
**唯一允许的差异**：房间特有的 `scriptChapter.value` / `scriptProgressPct.value` /
`autoFinish.value` / `pushMessage` 改成对应 hook 调用；hook 没传就跳过（用 `?.`）。

`InterviewRoomView.vue` 里 `runScript` 改成一行包装：

```ts
async function runScript(stream: AsyncGenerator<StreamEvent>): Promise<boolean> {
  return runScriptStream(stream, {
    resolveRole: resolveRoleCode,
    recordLine: (line) =>
      pushMessage(line.role, line.text, line.emotion ? { emotion: line.emotion } : {}),
    onChapter: (c) => (scriptChapter.value = c),
    onProgress: (p) => (scriptProgressPct.value = p),
    onBlocked: (b) => (scriptBlocked = b),
    onEnd: () => (autoFinish.value = true),
    onError: (payload) => console.warn("[interview] ai:error", payload),
  });
}
```

行为必须与改造前**逐分支一致**：`applyPendingChoice` / `scriptBlocked` 两个模块级变量搬进
composable 后，房间侧不再维护它们（`runScript` 里若还有引用就删掉，不要留双份状态）。

### 2.2 API

**`api/chat.ts`**：`startScript(sessionId, scriptCode, fromChapter?)` 多带一个可选参数，
body 为 `{ scriptCode, fromChapter }`（`fromChapter` 为空时不要下发该键）。

**`api/editor.ts`**：加 `PreviewSessionView` 接口（字段与后端 1.3 一致）与两个函数：

```ts
export async function createPreview(key: string, fromChapter?: string): Promise<PreviewSessionView>
export async function discardPreview(sessionId: number): Promise<void>
```

`POST /api/editor/scripts/{key}/preview`（body 只在有 fromChapter 时带键）、
`DELETE /api/editor/preview/{sessionId}`。错误分支照文件里现有函数的写法抛 Error。

### 2.3 store

**`store/scriptEditor.ts`** 加状态与动作（放在现有 state 之后、actions 段落里，命名与现有风格一致）：

```ts
previewing: false,
previewSessionId: null as number | null,
previewInfo: null as PreviewSessionView | null,
previewFromChapter: "" as string,
previewError: "" as string,
```

- `startPreview(fromChapter = "")`：置 `previewing = true`（组件 watch 到再建场，见 2.4），
  同时记 `previewFromChapter`、`previewError = ""`。没打开剧本（`!store.detail`）直接 return。
- `stopPreview()`：置 `previewing = false`（组件 watch 负责清场与删场）。

### 2.4 组件（新建）

**`components/script-editor/preview/PreviewStage.vue`** —— 1:1 照搬原版 139 行，
只改数据获取方式。逐条要求：

- `<Teleport to="#app">`（原版注释照抄；本项目 `#app` 在 `index.html` 里，确认存在）。
- 外层 Transition 的三个 class 原样保留（`duration-[0.25s]` / `cubic-bezier(0.18,0.89,0.32,1)`）。
- 内层 `<div class="main-box">` 里四个组件顺序固定：`GameBackground` / `GameRolesStage` /
  `GameExtraUI` / `GameDialog`（本项目这四者的 import 路径见 `views/InterviewRoomView.vue` 的 import 段，
  不要自己猜）。
- 顶栏：badge「试玩中」+ label + 提示「试玩不会保存成绩与记录」+ 右侧「停止试玩」按钮，
  class 原样（`z-[9990]` / `z-[10000]` / 各 rgba 值一个不许改）。文案用中文常量，
  本项目没有 i18n，**不要引 `vue-i18n`**。
- `label` 计算：剧本名 · 起始章节（`从第 X 章开始`）· `MAIN = 面试官名`（用 `previewInfo`）。

**播放循环**（替代原版 `eventQueue`）：

- `watch(() => store.previewing, ...)`（原版同构）：
  - `true`：
    1. `resetRoom()`；
    2. `const info = await createPreview(scriptKey.value, props.fromChapter || undefined)`
       → 存 `store.previewInfo / previewSessionId`；失败则 `store.previewError = 错误信息` 并
       `store.previewing = false`，**不要抛到控制台就算完**；
    3. `roomStore.showCharacterTitle = info.interviewerName`；
       `setPresentRoles([info.interviewerCode])`；
    4. `await runScriptStream(startScript(info.sessionId, info.scriptCode, props.fromChapter || undefined), hooks)`
       —— hooks 只传 `resolveRole`（非 0 / 非 "MAIN" 的字符串直接当 code；"MAIN" 与数字解析成
       `info.interviewerCode`）、`onEnd`（置 `store.previewAutoEnd = true`，用于顶栏提示「本段已演完」）。
  - `false`：`resetRoom()`；若 `store.previewSessionId != null` 调 `discardPreview(...)` 并置空。
- `GameDialog` 的 `@send`：文本 → `runScriptStream(submitScriptInput(sessionId, text), hooks)`；
  `@finish` → `store.stopPreview()`；`@history` 不接。
- 击屏推进由 `GameDialog` 内部 `advance()` 完成（已在组件里），**不要再写一份**。
- Esc 停止：`onMounted` 挂 `window.addEventListener("keydown")`，`key === "Escape" && store.previewing`
  → `store.stopPreview()`；`onBeforeUnmount` 摘掉。

**`views/ScriptEditorView.vue`**：在 `</div>`（根元素）内、`EditorModals` 之后挂
`<PreviewStage :fromChapter="store.previewFromChapter" />`。

**`components/script-editor/panels/EditorHeader.vue`**：右侧动作区加「试玩」按钮，
class 照抄相邻按钮（不新造样式）；`store.detail` 为空时 disabled。点击 → `store.startPreview("")`。

**`components/script-editor/tabs/FlowTab.vue`**：章节卡片的 hover 动作行里加一项「从本章试玩」
（照该行已有按钮的 class 与尺寸），点击 → `store.startPreview(chapter.id)`。若该行没有现成按钮组，
就在卡片右上角加一个同样大小的小按钮，不要改布局。

### 2.5 禁区

- 不许改 `roomStore.ts` 的字段与既有函数（`resetRoom` / `advance` / `pushLine` 都复用）。
- 不许改 SSE 事件名、payload 字段名（契约由主线把关）。
- 不许给试玩加 scoring / 记录落库（那是课设指标的事，另有批次）。
- 不许引入新依赖。

---

## 3. 验收（主线自跑，子 agent 报告不算数）

1. `cd backend && bash ../scripts/mvn.sh test` → 全绿（跑之前先确认没有 `java -jar` 在跑，
   它锁 `data/interview.mv.db`，测试方法会被顶掉）。
2. 起后端：`cd backend && bash ../scripts/mvn.sh -q -DskipTests package` 后
   `java -jar target/interview-lab-backend-0.1.0.jar`。
3. `curl -X POST localhost:8080/api/editor/scripts/<已有剧本 code>/preview -H 'Content-Type: application/json' -d '{}'`
   → 200，含 `sessionId`；再
   `curl -X POST localhost:8080/api/sessions/<id>/script/start -H 'Content-Type: application/json' -d '{"scriptCode":"<code>"}' -N`
   → 事件流里必须出现 `script:chapter-change` / `script:narration` 或 `ai:reply` / `script:input`（至少各一条）。
4. `curl -X POST localhost:8080/api/editor/scripts/<code>/preview -d '{"fromChapter":"不存在的章"}'` → 400。
5. `curl "localhost:8080/api/sessions"` → 不含刚建的试玩场次。
6. `cd frontend && npx vue-tsc --noEmit` → 0 error；`npx vite build --outDir dist-e5b`（不要直接
   `npm run build`，safe-delete shim 会拦 `dist/` 清空）。
7. 浏览器实测（playwright-core + 系统 Chrome，凭 DOM 断言不是看图）：打开编辑器 → 打开剧本 →
   点「试玩」→ 断言出现「试玩中」badge 与 `.main-box`；点「停止试玩」→ badge 消失、
   `POST /api/editor/preview/{id}` 已发出。
