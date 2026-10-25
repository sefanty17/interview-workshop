# 补齐 skill_agent 的 preplan / hub 总纲（对齐上游 `5a778fab`）

上游基准：**唯一真相源 `5a778fab0b34101ca4cf75ae361a4b1858847fb1`（LingChat dev）**。
所有 `ref=` 必须写这个完整 sha，禁止写 `dev`。

## 背景（为什么做）

用户报的第三个问题：原版 AI 会先判断用户需求，再判断这些需求有没有**前置条件**；
用户说「直接编写并落盘」时，隐性的前置是「写大纲」「写正文」，原版会把这些前置
**按队列插到前面先做**。本项目完全没做。

机制在上游两处：

1. `crates/ling-chat-main/src/ai_service/skill_agent/core.rs:361-405 preplan`
   开跑前逐项 `role::reconcile`，返回 `Handoff::Prerequisite { first }` 时
   `planned.push((idx, first, handoff))` —— **用前置任务顶替本项先做**；
   `Explain(why)` 则跳过并记进 `skipped`。
2. `core.rs:409-434 recheck_handoff` —— 真跑该项前用真实事实再判一次，
   前置已补完就改回原任务。

本项目 `ScriptFacts.reconcile`（1:1 已迁好）只被 `SkillAgentService` 在逐项循环里调过一次，
**没有 preplan、没有换任务、没有 skipped**。源码注释自认：「我方没有 preplan 的跳过项 → 空表」。

连带问题：router 的**流程总纲 hub 永远是 null**（`SkillAgentService` 硬编码传 `null`），
而「每件事的前置（排队列时按这个顺序）」整段就写在总纲里。

## 必读参考（先读再动手）

- `.workbuddy/ref/core.rs` —— 看 `preplan`（约 358-405 行）与 `recheck_handoff`（409-434）
- `.workbuddy/ref/evidence.rs` —— 看 `Pending`（150-190）与 `facts_of`（192-241）
- `.workbuddy/ref/role.rs` —— `reconcile`（377+）
- `.workbuddy/ref/router.rs` —— `route`（66-114）、`build_router_system`（117-152）
- `.workbuddy/ref/role_action_vocab.md` —— ACTION_VOCAB 原文
- `.workbuddy/ref/role_routing_land_rules.md` —— ROUTING_LAND_RULES 原文（5 条）
- `.workbuddy/ref/ScriptEditor.vue` —— 前端宿主（仅供理解，本次**不改前端**）

---

# 改动清单（按文件）

## A. `backend/src/main/java/cn/edu/zjut/interview/agent/ScriptFacts.java`

现有可复用符号（**不要重写**）：`chapterKey(String)`（约 281 行）、
`chapterIdsIn(String)`（约 305 行）、`collect(Path, String, List<String>)`（206 行）、
`planChaptersOf(String)`（262 行）。约 272 行已有一个 `sameChapter` 形态的方法，先看再用。

### A1. 新增 `Pending`（对齐 `evidence.rs:150-190`）

```java
/** 预计产物：preplan 逐项累积，让后一项能看见前一项"将要"造出来的东西。 */
public record Pending(List<String> landed, List<String> drafted, boolean design) {
    public Pending {
        landed = landed == null ? List.of() : List.copyOf(landed);
        drafted = drafted == null ? List.of() : List.copyOf(drafted);
    }
    public static Pending empty() { return new Pending(List.of(), List.of(), false); }
    /** 这一项做完会多出什么（对齐 evidence.rs::Pending::of）。 */
    public static Pending of(TaskKind kind, String target) { ... }
    /** 合并：landed / drafted 去重保序，design 取或。 */
    public Pending add(Pending other) { ... }
}
```

`of` 的分支（逐字对齐 `evidence.rs:161-181`）：
- `WRITE_CHAPTER` → `landed = chapterIdsIn(target)`
- `DRAFT_CHAPTER` → `drafted = chapterIdsIn(target)`
- `OUTLINE` / `REVSE_OUTLINE`（即 `REVISE_OUTLINE`）→ `design = true`
- 其余 → `empty()`

`chapterIdsIn(null)` 必须返回空表（防御）。

### A2. 新增 `factsOf`（对齐 `evidence.rs:192-241`）

```java
public static ScriptFacts factsOf(StageFacts snap, String target, Pending pending)
```

逐条对齐（**顺序与语义不许自己发挥**）：

```
wanted   = chapterIdsIn(target)             // target 空 → 空表
sameChapter(a,b): ka=chapterKey(a), kb=chapterKey(b)
                  ka 或 kb 为空 → a.trim().equals(b.trim())；否则 ka.equals(kb)
landed(id) = snap.written().stream().anyMatch(w -> sameChapter(w, id))
             || pending.landed().stream().anyMatch(w -> sameChapter(w, id))
present  = wanted.map(landed)
targetExists = wanted.isEmpty() ? null : present.any(true)
hasDesign = pending.design() || designFileNonEmpty(snap.scriptDir())
            // 「文件在且非空」：snap.scriptDir() 为 null 时 false
next     = snap.plan() 里第一个 !landed(id)
hasPlan  = !snap.plan().isEmpty()
hasWritten = !snap.written().isEmpty() || !pending.landed().isEmpty()
hasNext  = next.isPresent()
targetPartial = present.any(true) && present.any(false)
targetIsNext  = next.isPresent() && wanted.any(w -> sameChapter(w, next.get()))
hasDraft = pending.drafted().stream().anyMatch(d -> wanted.contains(d))
           || chapterDraftPresent(snap.scriptDir(), wanted)
```

`chapterDraftPresent`：若 `wanted` 全为空 key → false；否则用**现有实现**判
`.agent/chapter-details.md` 里有没有这些章的小节（`collect` 内部已在做，抽成静态方法复用，
不要另写一套解析）。`snap.scriptDir()` 为 null → false。

`StageFacts` 是 record：`scriptKey / scriptDir / design / plan / written / constraints`。

---

## B. `backend/src/main/java/cn/edu/zjut/interview/agent/StagePrompt.java`

新增（文件头注释里那句「除 `hub_doc` 之外」要一并改掉）：

```java
/** 流程总纲文件名（对齐 stage/prompt.rs::HUB_DOC）。 */
private static final String HUB_DOC = "lingchat-script-editor/SKILL.md";

/** 流程总纲全文；读不到返回 null（对齐 stage/prompt.rs:285-289 hub_doc）。 */
public static String hubDoc(Path skillsDir) {
    if (skillsDir == null) return null;
    Path f = skillsDir.resolve(HUB_DOC);
    try {
        String t = Files.readString(f, StandardCharsets.UTF_8);
        return t.isBlank() ? null : t.stripTrailing();
    } catch (IOException | RuntimeException e) {
        return null;
    }
}
```
（`stripTrailing()` 是 Java 11+；本项目 Java 17，可用。若已 import `java.nio.file.*` 与
`StandardCharsets` 就不要重复加。）

---

## C. `backend/src/main/java/cn/edu/zjut/interview/agent/SkillAgentRouter.java`

### C1. `route()` 加总纲守卫（对齐 `router.rs:70-73`）

在现有 `userMsg` 空检查**之后**、`!llm.isUsable()` 检查**之前**插入：

```java
if (input.hubDoc() == null || input.hubDoc().isBlank()) {
    log.warn("[SkillAgentRouter] 流程总纲缺失（技能库不完整），这一轮只回话");
    return RoutePlan.chatDueTo("技能库不完整：读不到流程总纲");
}
```

### C2. `ACTION_VOCAB` 换成原文

把现有那段"等价精简文案"**整段替换**为 `.workbuddy/ref/role_action_vocab.md` 的全文
（三段：【两个动作词，别混用】【chapter-details.md 里写什么】【用户说「撤回…」】）。
用 Java 文本块 `"""`，**内容逐字照抄，一个字都不要改写**；注释改成写明出处
`prompts/role_action_vocab.md`（上游 `role_action_vocab.md`）。

### C3. `ROUTING_LAND_RULES` 换成原文

同样整段替换为 `.workbuddy/ref/role_routing_land_rules.md` 的 **5 条**（`- **只有下面三种情况…`
/ `- **其余一律先写出来给他看…` / `- **只要「大纲」的那几章…` / `- **「改 YAML」只排已经落盘的章…`
/ `- **一项只做一件事…`），逐字照抄。

**注意**：原文里含 `{}` 之外的花括号吗？没有。但含反引号与中文引号，文本块内无需转义。
若文本块里出现 `\` 需转义——先通读确认。

---

## D. `backend/src/main/java/cn/edu/zjut/interview/agent/SkillAgentService.java`

### D1. 总纲传参（约 622-625 行）

```java
SkillAgentRouter.RoutePlan plan = router.route(new SkillAgentRouter.RouteInput(
        userMsg,
        recentTurnsText(conversationId),
        null,                       // ← 改成 StagePrompt.hubDoc(skillsDir)
        ...
```
第 3 个实参改成 `StagePrompt.hubDoc(skillsDir)`。
`skillsDir` 在该方法作用域内已存在（后面 `buildSystemPrompt(config, allowed, sandboxDir, skillsDir, ...)`
就在用它）。**不要**改成别的变量名。

### D2. 新增 `preplan`（对齐 `core.rs:361-405`）

新增私有 record + 私有方法（放在 `runTurn` 附近，紧挨着它）：

```java
/** preplan 后的一项：kind 可能是前置任务（顶替了用户原本点的那件）。 */
private record PlannedItem(int originalIndex, SkillAgentRouter.TaskKind kind,
                           String target, ScriptFacts.Handoff handoff) {}
```

```java
private PreplanResult preplan(String scriptKey, Path sandboxDir,
                              List<SkillAgentRouter.QueueItem> items, ChapterLander lander)
```
返回值用一个小 record 装 `(List<PlannedItem> planned, List<String> skipped)`。

逻辑（**逐句对齐 core.rs:361-405**）：

```java
List<PlannedItem> planned = new ArrayList<>();
List<String> skipped = new ArrayList<>();
ScriptFacts.Pending pending = ScriptFacts.Pending.empty();
for (int idx = 0; idx < items.size(); idx++) {
    QueueItem item = items.get(idx);
    Path dir = scriptDir(sandboxDir, scriptKey);               // 与现有调用同款
    List<String> written = lander.writtenChapterIds(scriptKey, dir);
    StageFacts snap = StageFacts.collect(scriptKey, dir, written);
    ScriptFacts.Handoff h = ScriptFacts.reconcile(
            item.kind(), ScriptFacts.factsOf(snap, item.target(), pending));
    switch (h.kind()) {
        case EXPLAIN -> skipped.add(item.kind().label() + "：" + h.note());
        case PREREQUISITE -> {
            pending = pending.add(ScriptFacts.Pending.of(h.first(), item.target()))
                             .add(ScriptFacts.Pending.of(item.kind(), item.target()));
            planned.add(new PlannedItem(idx, h.first(), item.target(), h));
        }
        default -> {
            pending = pending.add(ScriptFacts.Pending.of(item.kind(), item.target()));
            planned.add(new PlannedItem(idx, item.kind(), item.target(), h));
        }
    }
}
if (planned.isEmpty() && !items.isEmpty()) {
    QueueItem item = items.get(0);
    Path dir = scriptDir(sandboxDir, scriptKey);
    List<String> written = lander.writtenChapterIds(scriptKey, dir);
    StageFacts snap = StageFacts.collect(scriptKey, dir, written);
    ScriptFacts.Handoff h = ScriptFacts.reconcile(
            item.kind(), ScriptFacts.factsOf(snap, item.target(), ScriptFacts.Pending.empty()));
    planned.add(new PlannedItem(0, item.kind(), item.target(), h));
}
```

`item.kind().label()` 是否存在先确认；没有就用 `item.kind().key()`（并在注释里写明差异）。

### D3. 主循环改成遍历 `planned`

现状（约 664-766 行）：
- `List<String> skipped = List.of();`（664 行，注释"我方没有 preplan 的跳过项 → 空表"）→ **删掉**，改用 preplan 返回的 skipped
- `for (int idx = 0; idx < items.size(); idx++)`（672 行）→ 改成遍历 `planned`

改动要点：
- `kind` 取 `p.kind()`，**target 取 `p.target()`**（前置项沿用用户点的章号，对齐 `planned.push((idx, first, handoff))` 只换 kind 不换 target）
- `idx` 仍用 `p.originalIndex()`（序号、missedItems、`itemHeader(n, total, ...)` 的 n 用它 +1）
- 总数仍用 `items.size()`（`itemHeader` 与 `handoffDigest` 的 total 参数，对齐 `plan.items.len()`）
- `land`（662 行派生）**保持不变**，仍按原 `items` 算
- `StageQueue.writeQueue(queueDir, items, turnStart, lander)`（741 / 757 行）**仍传原 `items`**，不改

### D4. `recheck_handoff`（对齐 `core.rs:409-434`）

在现有第 683-687 行那次
```java
ScriptFacts itemFacts = ScriptFacts.collect(itemScriptDir, target, ...);
ScriptFacts.Handoff handoff = ScriptFacts.reconcile(kind, itemFacts);
```
**之后**插入（此时 `kind` 是 preplan 给的、可能已是被顶替的前置任务）：

```java
// 前置补完后原任务可能已经能做了：用真实事实再判一次，能就改回原任务（对齐 core.rs:409-434）
if (handoff.kind() == ScriptFacts.Handoff.Kind.PREREQUISITE) {
    QueueItem original = items.get(p.originalIndex());
    if (original != null) {
        ScriptFacts againFacts = ScriptFacts.collect(itemScriptDir, original.target(),
                lander.writtenChapterIds(runContext.scriptKey(), itemScriptDir));
        ScriptFacts.Handoff again = ScriptFacts.reconcile(original.kind(), againFacts);
        if (again.kind() == ScriptFacts.Handoff.Kind.PROCEED
                || again.kind() == ScriptFacts.Handoff.Kind.PROCEED_NOTE) {
            log.info("[SkillAgentService] 会话 {} 第 {} 项前置已补完，改回原任务 {}",
                    conversationId, idx + 1, original.kind().key());
            kind = original.kind();
            handoff = again;
        }
    }
}
```
`kind` 目前是局部变量，改成可重新赋值（若原来是 `final` 或用了 `var` 就地调整）。
`allowed`（689 行的 `toolsFor(kind, handoff)`）必须在 recheck **之后**算——检查顺序，
若现在 `allowed` 在 reconcile 之后就已经算了，把 recheck 插到 `allowed` 之前。

### D5. `skipped` 传给任务块

第 697 行 `StagePrompt.buildTaskBlock(new StagePrompt.TaskBlockInput(..., skipped, allowed))`
现在传 `List.of()`，改成 preplan 的 `skipped`。

---

## 禁区（违反即返工）

1. **不许跑任何 git 命令**（clone / log / commit / push 全禁）——提交归主 agent。
2. **不许用裸 `mvn`**，只能用 `scripts/mvn.sh`（先确认它存在）。
3. **不许改前端**（`frontend/` 下任何文件都不碰）。
4. **不许改 `schema-h2.sql`**、不许加数据库迁移。
5. **不许改 SSE 事件名**（`ai:reply` 等一批，改了前端收不到）。
6. **不许动 `.workbuddy/memory` 之外的历史文档**，也不要新建 md。
7. `game_data/skills/lingchat-script-editor/SKILL.md` 已由主 agent 导入，**不要改动它**。
8. 不要顺手重构别的方法；改不完的地方留注释写清。

## 验收标准（主 agent 会自己复核，别只报"通过了"）

1. `scripts/mvn.sh test` —— **全绿**，且报告最终那行 `Tests run: N, Failures: 0, Errors: 0`
   （注意：`target/surefire-reports/` 会残留已删测试类的陈旧 xml，按 xml 汇总会算大，
   **只认 mvn 最后那行数字**）。
2. 新增单测（放在 `backend/src/test/java/cn/edu/zjut/interview/agent/` 下，命名 `PreplanTest.java`），
   至少覆盖：
   - `Pending.of(WRITE_CHAPTER, "03")` → landed=["03"]，drafted 空，design=false
   - `Pending.of(DRAFT_CHAPTER, "03")` → drafted=["03"]
   - `Pending.of(OUTLINE, "")` → design=true
   - `factsOf`：snap 无设计稿 + pending.design=true → `hasDesign()==true`
   （即"预计产物"要能被后一项看见）
   - `preplan` 的核心断言：**items = [write_chapter@03]、磁盘无正文 → 第一项 kind 变成
     `DRAFT_CHAPTER`**（这就是用户报的"隐性前置插到前面"）
   - `preplan`：`items = [revise_outline]`、无设计稿 → 第一项 kind 变成 `OUTLINE`
   - `preplan`：`items = [revise_chapter@03]`、无任何已落盘章节 → 该项被跳过、进 `skipped`
3. `hubDoc` 单测：`skillsDir` 指向 `backend/../game_data/skills` 时能读到非空文本；
   指到空目录返回 null。
4. 报告里要写明：`Tests run:` 的具体数字、新增测试文件名、每个改动文件的行号区间。
