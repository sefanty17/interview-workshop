# 规格 D · 剧本编辑器改为「章节 + 事件」模型

## 背景（必读）

本项目方针：**前端 1:1 复刻 LingChat，数据格式 1:1 对齐**。
后端数据基座已经按 LingChat 剧本引擎建好：`interview_script` + `interview_chapter`，事件类型照搬
`references/lingchat/data/game_data/scripts/**/Chapters/*.yaml`。

但前端 `ScriptEditorView.vue` 现在还在用**旧的 steps 三步模型**（intro/question/followup/closing），
与数据库对不上。本任务就是把这个页面改成章节 + 事件模型。

**只改前端。不要动后端、不要动 `data/` 目录、不要新增依赖。**

## 已准备好的数据源

`frontend/src/data/scripts.ts` 已经生成好了（不要手改，它是从 `data/seed/scripts.json` 转的）。
它导出：

```ts
export type EventType = "ai_dialogue" | "input" | "narration" | "free_dialogue" | "modify_character" | "chapter_end";
export type EndType = "linear" | "branching" | "ai_judged";

export interface ScriptEvent {
  type: EventType;
  character?: string;   // ai_dialogue / modify_character，主面试官固定 "MAIN"
  prompt?: string;      // ai_dialogue 给 LLM 的提示词
  hint?: string;        // input 的占位提示（本项目扩展）
  text?: string;        // narration 旁白
  action?: string;      // modify_character: show_character / hide_character
  duration?: number;    // modify_character
  end_type?: EndType;   // chapter_end
  next_chapter?: string;
  options?: { name: string; next_chapter?: string; condition?: string }[];
}

export interface InterviewChapter { code: string; name: string; order_num: number; events: ScriptEvent[]; }
export interface InterviewScript {
  code: string; script_name: string; intro_chapter: string; description: string;
  recommand_start?: string; interviewer_code?: string; order_num: number;
  job_code?: string; level?: string; company_style?: string;
  script_settings?: { total_questions?: number; time_limit?: number; follow_up_depth?: number };
  chapters: InterviewChapter[];
}

export const seedScripts: InterviewScript[];
export function findScript(code: string): InterviewScript | undefined;
export function nextChapterOf(ch: InterviewChapter): string | undefined;
```

种子规模：4 个剧本 / 21 个章节 / 88 个事件（其中 ai_dialogue 31、input 31、chapter_end 21、narration 5）。

## 改动清单

### 1. `frontend/src/data/mock.ts`

- 删除 `StepType`、`ScriptStep`、`Script` 三个类型（约 129–145 行）。
- 删除 `export const seedScripts: Script[] = [...]` 整块（约 543 行到文件里那个数组结束）。
- **不要**动 `personas`、`jobs`、`questions`、`defaultSettings`、`SiteSettings` 等其它导出。

### 2. `frontend/src/store/appStore.ts`

- 删掉 `seedScripts` 从 `../data/mock` 的导入，改成 `import { seedScripts } from "../data/scripts";`
  和 `import type { InterviewScript, ScriptEvent } from "../data/scripts";`
- `Persisted.scripts` 类型改为 `InterviewScript[]`。
- `createScript()` 改为返回一个新的 `InterviewScript`：

```ts
export function createScript(): InterviewScript {
  const n = store.scripts.length + 1;
  return {
    code: `custom-${Date.now()}`,
    script_name: `未命名面试剧本 ${n}`,
    intro_chapter: "intro",
    description: "新的面试流程",
    order_num: n,
    job_code: store.settings.jobId,
    chapters: [
      {
        code: "intro",
        name: "开场",
        order_num: 1,
        events: [
          { type: "ai_dialogue", character: "MAIN", prompt: "以面试官身份开场，请候选人做一分钟自我介绍。" },
          { type: "input", hint: "一分钟自我介绍" },
          { type: "chapter_end", end_type: "linear", next_chapter: "" },
        ],
      },
    ],
  };
}
```

- 保留 `saveScripts()`、`deleteScript(id)`，但 `deleteScript` 的参数语义变成 **script.code**
  （`store.scripts.findIndex((s) => s.code === code)`）。
- localStorage 版本键 `STORAGE_KEY` **不要改**。但 `load()` 里要兼容旧数据：
  如果 `parsed.scripts` 里的元素没有 `chapters` 字段，就丢弃它回落到 `seedScripts`
  （写成 `parsed.scripts?.every((s: any) => Array.isArray(s.chapters)) ? parsed.scripts : seedScripts` 之类）。

### 3. `frontend/src/components/settings/tabs/AdventureTab.vue`

字段改名：`s.name` → `s.script_name`，`s.desc` → `s.description`，`:key="s.id"` → `:key="s.code"`，
`startScript(s.name)` → `startScript(s.script_name)`。其它不动。

### 4. `frontend/src/views/ScriptEditorView.vue` —— 整页重写

外壳仍然用 `PageShell`（`title="剧本编辑器"`），页头右侧保留动作按钮组（照现在的样式）。
整体两栏：左栏剧本列表（`w-[280px]` 固定），右栏编辑区（`flex-1 min-w-0`）。
左右都是玻璃卡：`rounded-2xl border border-white/12 bg-white/[0.06] backdrop-blur-xl`。

#### 4.1 左栏 · 剧本列表

- 标题「剧本列表」+ 数量。
- 每行：`script_name`（粗体白）、下面一行 tag（`job_code`、`level`、`{chapters.length} 章`）、
  `description` 两行截断。选中行高亮：`border-[#79d9ff]/50 bg-[#79d9ff]/[0.08]`。
- 底部「＋ 新建剧本」按钮，用 `.btn-big`，点击 `createScript()` 后 push 进 `store.scripts` 并选中。
- 每行右侧一个小的删除按钮（`✕`，hover 变红），点击走 `confirmDialog("删除剧本", "确定删除「xxx」吗？删除后不可恢复。")`，
  确认后 `deleteScript(code)`，若删的是当前选中项则切到第一个。

#### 4.2 右栏 · 三个 Tab

Tab 条样式照抄项目里已有的写法（圆角胶囊，选中 `bg-[#79d9ff]/20 text-[#79d9ff]`）：
`章节流程` / `剧本设置` / `校验`。

**A. 章节流程**

上半部分是章节流程横条（flex wrap，卡片式）：

- 每章一张卡：`code`（等宽小字灰）+ `name`（白粗体）+ `{events.length} 个事件`。
- 卡片底部标注出口：`nextChapterOf(ch)` 有值 → `→ {next_chapter}`；
  `chapter_end.end_type === "ai_judged"` → `AI 判定 → a / b`（列出 options 的 name）；
  `end_type === "branching"` → `条件分支 → ...`；
  没有 `chapter_end` → 红色 `⚠ 无章节结束`。
- `code === script.intro_chapter` 的卡片加「开场」角标（`bg-[#79d9ff]/20 text-[#79d9ff]`）。
- 没被任何 `next_chapter` / `options[].next_chapter` 指向、又不是 intro 的章节，标注红色「无人进入」。
- 点击卡片切换下半部分显示的事件时间线，选中卡片高亮。

下半部分是**当前章节的事件时间线**：

- 顶部一行：章节名 + `code` + 「＋ 插入事件」下拉（6 种：台词 ai_dialogue / 等待回答 input / 旁白 narration /
  自由对话 free_dialogue / 角色出场 modify_character / 章节结束 chapter_end）。
  插入位置规则：`chapter_end` 只能有一条，插在列表末尾；其它类型插在 `chapter_end` 之前。
  下拉用已有的 `LcModal` 或简单的绝对定位面板都行，**不许用 window.prompt**。
- 每个事件一行卡片：左侧类型徽标（不同颜色：台词蓝、input 青、旁白灰、自由对话紫、角色橙、章节结束粉），
  右侧上移 / 下移 / 删除三个小图标按钮（用 `↑ ↓ ✕` 字符即可）。
  按事件类型渲染可编辑字段，字段用 `.glass-input`：
  - `ai_dialogue`：`character` 输入框（默认 `MAIN`）+ `prompt` 多行 textarea。
  - `input`：`hint` 输入框（占位提示）。
  - `narration`：`text` 多行 textarea。
  - `free_dialogue`：`prompt` 多行 textarea。
  - `modify_character`：`action` 下拉（`show_character` / `hide_character`）+ `character` 输入 + `duration` 数字。
  - `chapter_end`：
    - `end_type` 下拉（`linear 直接跳转` / `branching 条件分支` / `ai_judged AI 判定`）；
    - `linear` 时显示「下一章」下拉（本剧本所有章节 code，可空 = 结束）；
    - `branching` / `ai_judged` 时显示 `prompt` textarea + `options` 列表：
      每行 `name` 输入 + `next_chapter` 下拉 + 删除；底部「＋ 添加分支」。
- 删除事件要 `confirmDialog` 二次确认。

**B. 剧本设置**

用 `MenuItem`（`size="large"`）分两组：

- 「基本信息」：`script_name`、`code`（只读灰底）、`description`（多行）、`intro_chapter`（下拉，选本剧本章节）。
- 「面试参数」：`job_code`（下拉，取自 `mock.ts` 的 `jobs`）、`level`、`company_style`、`recommand_start`、
  `interviewer_code`（下拉，取自 `data/interviewers.ts` 的 `interviewers`，显示名称 + 角色），
  `script_settings.total_questions` / `time_limit` / `follow_up_depth` 三个数字输入。

**C. 校验**

纯计算，列出问题，格式：`[错误|提醒] 章节 code：说明`。

- `intro_chapter` 不在章节列表里 → 错误。
- 某章 `next_chapter`（含 options 里的）指向不存在的 code → 错误。
- 某章没有 `chapter_end` → 错误。
- `ai_judged` / `branching` 缺 `prompt` 或 `options.length < 2` → 错误。
- 某章 `events.length === 0` → 提醒。
- 除 intro 外某章没有任何入口 → 提醒。
- 全部通过时显示「未发现问题」+ 绿勾。

#### 4.3 页头动作

- `保存`：`saveScripts()` + `notify("剧本已保存")`。
- `导入`：`<input type="file" accept=".json" hidden>`，读 JSON 后校验有 `chapters` 数组才 push，
  失败走 `alertDialog("导入失败", "文件不是合法的剧本 JSON（缺少 chapters）")`。
- `导出`：把当前剧本 `JSON.stringify(script, null, 2)` 存成 Blob 下载，文件名 `{code}.json`。
- `新建`：同左栏的新建按钮。

## 约束（违反即返工）

- **禁止** `window.alert` / `window.confirm` / `window.prompt`，一律走 `store/dialogStore.ts` 的
  `alertDialog` / `confirmDialog`。
- **禁止**新增 npm 依赖。
- 只用 Tailwind v4 原子类 + `styles/lingchat.css` 里已有的类（`.glass-input`、`.btn-big`、`.nav-btn`）。
  **不要**把 `-translate-x-1/2` 和 inline `transform` 混用（Tailwind v4 会生成独立的 `translate` 属性，两者叠加会飞）。
- 不要改路由、不要改 `PageShell.vue`、`MenuItem.vue` 的对外接口。
- 中文文案，风格与现有页面一致（白字 + 白/10 玻璃 + `#79d9ff` 主色）。

## 顺带两个小修（同一个提交里做掉）

1. `frontend/src/views/RecordsView.vue`：
   `activePersona` 改成用 `findInterviewer(store.session.personaId)`（来自 `../data/interviewers`），
   显示 `persona.name` + `persona.displayRole`；删掉对 `mock.ts` 里 `personas` 的依赖（如果没别处用）。
2. `frontend/src/components/ui/LineChart.vue`：
   y 轴不要固定 `min: 0, max: 100`，改成按数据自适应：
   `min: Math.max(0, Math.min(...values) - 10)`、`max: Math.min(100, Math.max(...values) + 10)`，
   且 min === max 时上下各让 5。这样 82/76/88 这种分数不会被压成贴顶的一条线。

## 验收标准（自查后再报告）

1. `cd frontend && npx vue-tsc --noEmit` → **0 error**。
2. dev server（`npm run dev`，5173）打开 `/script-editor`：
   - 左栏列出 4 个剧本，名字分别是「Java 后端 · 严格三轮」「Web 前端 · 标准两轮」「Python 后端 · 压力面」「Java 后端 · 专家面」（以实际种子为准）。
   - 点第一个剧本 → 章节流程显示 6 张章节卡，首张有「开场」角标。
   - 点任一章节卡 → 下面时间线切换，事件数量与该章 `events.length` 一致。
   - 「校验」Tab 对 4 个种子剧本都应显示「未发现问题」。
   - 修改某条 `ai_dialogue` 的 prompt → 刷新页面后仍在（localStorage）。
3. 用 Playwright 截图 3 张存到 `docs/design/screenshots/pages/`：
   `script-editor.png`（章节流程）、`script-editor-config.png`（剧本设置）、`script-editor-validate.png`（校验）。

完成后回报：改了哪些文件、行数变化、tsc 结果、截图自查结论。不要贴大段代码。
