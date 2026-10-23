# E5a · 校验页 + 新建/删除剧本

> 前置：E1~E4 完成（后端 9 端点 + 校验器；前端流程图 / 章节编辑 / 事件四件套 / 剧本设置全绿）。
> 原版蓝本：
> - `references/lingchat/src/components/script-editor/tabs/ValidateTab.vue`（169 行）
> - `references/lingchat/src/components/script-editor/modals/EditorModals.vue`（322 行，**只要 script / chapter 两个分支**，见第 5 节）
> - `references/lingchat/src/components/script-editor/panels/ScriptListPanel.vue`（新建 + 删除按钮）
> - `references/lingchat/src/components/script-editor/panels/EditorHeader.vue`（校验 Tab 的 errorCount 角标）
> - `references/lingchat/src/stores/modules/script-editor/actions.ts`（`deleteScript` 的确认文案与收尾顺序）

## 0. 本批交付

| # | 文件 | 处理 |
|---|---|---|
| 1 | `backend/.../dto/EditorCreateScriptRequest.java` | 新建 |
| 2 | `backend/.../service/ScriptEditorService.java` | 加 `createScript` / `deleteScript` |
| 3 | `backend/.../api/ScriptEditorController.java` | 加 `POST /scripts`、`DELETE /scripts/{key}` |
| 4 | `backend/.../repository/InterviewSessionRepository.java` | 加 `findByScriptId` |
| 5 | `backend/src/test/java/.../editor/ScriptEditorServiceScriptTest.java` | 新建单测 |
| 6 | `frontend/src/api/editor.ts` | 加 `createScript` / `deleteScript` + `CreateScriptBody` |
| 7 | `frontend/src/store/scriptEditor.ts` | 加 `scriptDiagnostics` getter + `createScript` / `deleteScript` action；`ENABLED_TABS` 加 `validate` |
| 8 | `frontend/src/components/script-editor/tabs/ValidateTab.vue` | 新建（原版逐字改写） |
| 9 | `frontend/src/components/script-editor/modals/EditorModals.vue` | 新建（原版裁剪：只留 script / chapter） |
| 10 | `frontend/src/components/script-editor/panels/ScriptListPanel.vue` | 加删除按钮 + 「＋ 新建剧本」按钮 + emit |
| 11 | `frontend/src/components/script-editor/tabs/FlowTab.vue` | 「＋ 新建章节」改成 emit `new-chapter`（删掉 `window.prompt` 那段） |
| 12 | `frontend/src/components/script-editor/panels/EditorHeader.vue` | tabs 加 `validate`（带 errorCount 角标） |
| 13 | `frontend/src/views/ScriptEditorView.vue` | 挂 `EditorModals` + `tabComponents` 加 validate + 接两个 emit |

**不做**：试玩预览、角色/素材/外观 Tab、AI 助手（E5b / E6 / E7）。

## 1. 后端契约

### 1.1 `POST /api/editor/scripts` → 200 + `EditorScriptPackage`

请求体（`EditorCreateScriptRequest`）：

```json
{
  "code": "java-friendly",
  "scriptName": "Java 后端 · 友好一轮",
  "description": "面向校招 Java 后端的基础面",
  "jobCode": "java",
  "level": "校招",
  "companyStyle": "一线互联网大厂",
  "interviewerCode": "friendly",
  "introChapter": "intro"
}
```

校验与默认值（顺序即报错优先级）：

| 字段 | 规则 | 不满足时 |
|---|---|---|
| `code` | trim 后必填；`^[a-z][a-z0-9_-]{1,63}$` | 400 `剧本编码不合法：'xxx'（要求小写字母开头，仅含小写字母/数字/_/-）` |
| `code` | 不能与已有剧本重复 | 400 `剧本编码已存在：xxx` |
| `scriptName` | trim 后为空则用 `code` | — |
| `introChapter` | trim；为空取 `intro`；去掉结尾 `.yaml`；不能含 `\/:*?"<>|` | 400 `开场章节 id 不合法：'xxx'` |
| `interviewerCode` | 非空时按 code 查 `interviewer` 表 | 400 `面试官不存在: 'xxx'` |
| 其余 | jobCode / level / companyStyle 原样存；空串存 null | — |

写入：

- `interview_script` 一行：`orderNum` = 现有最大 `orderNum` + 1（用 `findAllByOrderByOrderNumAsc()` 取末项，空表从 1 开始）；`triggerMode = "manual"`；`enabled = TRUE`；`createdAt = LocalDateTime.now()`；`scriptSettings` / `characters` 留 null。
- `interview_chapter` 一行：`code = introChapter`、`name = "开场"`、`events = INITIAL_EVENTS`（复用类里已有的常量）、`orderNum = 1`。
- 整个方法 `@Transactional`，返回 `toScriptPackage(script)`（复用现有私有方法；若名字不同，按现有 `listScripts` 里构造 `EditorScriptPackage` 的写法保持一致）。

### 1.2 `DELETE /api/editor/scripts/{key}` → 204

顺序（全部在一个 `@Transactional` 里）：

1. `InterviewScript script = requireScript(key)`（不存在 → 404，走现有 `EditorNotFoundException`）。
2. **解绑引用该剧本的场次**：`sessionRepository.findByScriptId(script.getId())` 逐个 `setScriptId(null)` + save。理由：`interview_session.script_id` 是普通列没有外键，不清理的话「删了剧本 → 历史场次点开始面试」会报剧本不存在。
3. `chapterRepository.deleteByScriptId(script.getId())`。
4. `scriptRepository.delete(script)`。

`ScriptEditorService` 构造器需要新增 `InterviewSessionRepository` 依赖（Spring 自动注入，构造器上加参数并赋值即可；`@SpringBootTest` 测试不受影响）。

### 1.3 单测（`ScriptEditorServiceScriptTest`）

仿现有 `ScriptEditorServiceBackupTest` 的写法（`@SpringBootTest @ActiveProfiles("test")`，每个用例后清理自己造的数据）：

| # | 断言 |
|---|---|
| 1 | `createScript` 返回的 package：`key` = 传入 code、`chapterCount` = 1、`scriptName` 正确 |
| 2 | 随后 `readScript` 的 `chapters` 有 1 项，`id` = introChapter；`readChapter` 的 events 长度 1 且 `type == "chapter_end"`、`next_chapter == "end"` |
| 3 | `interviewerCode` 写了 friendly → `readScript().storyConfig.interviewer_code == "friendly"` |
| 4 | 重复 code → `EditorBadRequestException`，消息含「已存在」 |
| 5 | `code = "Java-Strict"`（大写）→ `EditorBadRequestException` |
| 6 | `interviewerCode = "__nope__"` → `EditorBadRequestException` |
| 7 | `deleteScript` 后 `listScripts` 里没有该 key；`chapterRepository.findByScriptIdOrderByOrderNumAsc` 为空 |
| 8 | 删除不存在的 key → `EditorNotFoundException` |

> 单测必须用 `scripts/mvn.sh test` 跑，**不许用裸 `mvn`**。

## 2. 前端 API（`src/api/editor.ts`）

追加：

```ts
/** 新建剧本的请求体（后端 EditorCreateScriptRequest） */
export interface CreateScriptBody {
  code: string;
  scriptName: string;
  description?: string;
  jobCode?: string;
  level?: string;
  companyStyle?: string;
  interviewerCode?: string;
  introChapter?: string;
}

export function createScript(body: CreateScriptBody): Promise<ScriptPackage> {
  return callJson<ScriptPackage>("/api/editor/scripts", {
    ...jsonBody(body),
    method: "POST",
  });
}

export async function deleteScript(key: string): Promise<void> {
  await callVoid(base(key), { method: "DELETE" });
}
```

> 注意 `base(key)` 已经做了 `encodeURIComponent`，不要再套一层。

## 3. store 增补（`src/store/scriptEditor.ts`）

### 3.1 getter

```ts
/** 剧本级（不属于任何章节）的诊断，校验页顶部「剧本整体」一块用 */
export const scriptDiagnostics = computed<Diagnostic[]>(() =>
  (store.report?.diagnostics ?? []).filter((d) => !d.chapter),
);
```

### 3.2 `ENABLED_TABS`

```ts
const ENABLED_TABS: EditorTabKey[] = ["flow", "config", "validate"];
```

### 3.3 action（追加在 `removeChapter` 之后）

```ts
/**
 * 新建剧本。原版顺序：建完 → 刷新列表 → 直接打开新剧本（作者点了「新建」就是想马上写）。
 * 失败不吞异常：由调用方（弹窗）决定是否关闭，这里只负责弹错误窗。
 */
export async function createScript(body: CreateScriptBody): Promise<void> {
  store.saving = true;
  try {
    const pkg = await apiCreateScript(body);
    await loadScripts();
    await openScript(pkg.key);
    notify("已新建剧本");
  } catch (e) {
    notifyError("新建剧本失败", e);
  } finally {
    store.saving = false;
  }
}

/**
 * 删除整个剧本。
 *
 * 与原版一致：先确认 → 若删的是当前打开的剧本先 closeScript（否则页面还挂着一份
 * 已不存在的数据，下一次自动保存会 404）→ 删库 → 刷新列表。
 * 原版删完还要 rescanScripts 让引擎忘掉这个剧本；本项目引擎按库读，没有这一步。
 */
export async function deleteScript(key: string, displayName: string): Promise<void> {
  const ok = await confirmDialog(
    "删除剧本",
    `确定删除剧本「${displayName}」吗？\n\n该剧本的全部章节会被永久删除，无法恢复。`,
  );
  if (!ok) return;
  try {
    if (scriptKey.value === key) closeScript();
    await apiDeleteScript(key);
    await loadScripts();
    notify("已删除剧本");
  } catch (e) {
    notifyError("删除剧本失败", e);
  }
}
```

import 补：`createScript as apiCreateScript`、`deleteScript as apiDeleteScript`、`type CreateScriptBody`。

## 4. ValidateTab（原版逐字改写）

新建 `src/components/script-editor/tabs/ValidateTab.vue`，以原版 169 行为蓝本：

- 删 `useI18n`；文案按第 6 节替换
- import：`import { Icon } from "../../base";`、`import { MenuPage, MenuItem } from "../../ui";`
- store import（只 import 实际用到的这六个，多 import 会被 ESLint/TS 配置当未使用变量）：
```ts
import {
  chapters,
  diagnosticsByChapter,
  openChapter,
  runValidation,
  scriptDiagnostics,
  store,
} from "../../../store/scriptEditor";
```
- 模板里 `store.chapters` → `chapters`、`store.diagnosticsByChapter[c.id]` → `diagnosticsByChapter[c.id]`、
  `store.scriptDiagnostics` → `scriptDiagnostics`（模板自动 unref）
- `store.runValidation()` → `runValidation()`
- 脚本里 `store.openChapter(...)` → `openChapter(...)`
- `store.tab` / `store.level` / `store.chapter` / `store.report` / `store.selectedEvent` 保持不变
- `Diagnostic` 从 `../../../api/editor` 引入
- `eventJump` 的 `{index}` → `` `第 ${d.eventIndex + 1} 个事件` ``

`jumpTo` 与 `openChapterFromValidate` 两个函数**一行不许改逻辑**（先切 flow、再按需 openChapter、失败不动 selectedEvent）。

## 5. EditorModals（裁剪版）

新建 `src/components/script-editor/modals/EditorModals.vue`，以原版 322 行为蓝本，**只保留 `script` 与 `chapter` 两个分支**：

- props：`modal: "script" | "chapter" | null`
- emits：`"update:modal": ["script" | "chapter" | null]`
- 删除：`Toggle` 依赖、`isAdventure` / `boundCharacterFolder` / 角色（`character`）/ 导入角色（`importChar`）/ `importForm` / `charForm` / `editingCharacter`（props）/ 相关 watch
- `MODAL_TITLES` 只留 `script`、`chapter`
- `scriptForm` 改成本项目字段：

```ts
const scriptForm = reactive({
  code: "",
  scriptName: "",
  description: "",
  jobCode: "java",
  interviewerCode: "strict",
  level: "校招",
  companyStyle: "",
});
```

- `confirmModal` 的 script 分支改成 `await createScript({ ...scriptForm })`（store 的 action，自己会刷新列表并打开），成功后清空表单；chapter 分支保持 `await createChapter(chapterForm.id, chapterForm.name)` 并清空。
- 表单 DOM 结构、外层遮罩、标题栏、按钮样式**照抄原版**（`.modal-mask fixed inset-0 z-[9999] ...`），只换里面的字段块。

script 表单字段（本项目没有「羁绊冒险」，故换成本项目的面试参数）：

| 顺序 | 字段 | 控件 | 说明文案 |
|---|---|---|---|
| 1 | 剧本编码 `code` | input | `小写字母开头，可含数字、_ 与 -；作为剧本唯一标识，创建后不改` |
| 2 | 剧本名 `scriptName` | input | `显示在剧本列表与面包屑上` |
| 3 | 简介 `description` | textarea（`min-h-16`） | — |
| 4 | 岗位 `jobCode` | select（`jobs` from `../../../data/mock`，取值 `j.id`） | — |
| 5 | 面试官 `interviewerCode` | select（`interviewers` from `../../../data/interviewers`，取值 `p.code`，显示 `p.name`） | — |
| 6 | 难度 `level` | input | placeholder `校招 / 社招 / 实习` |
| 7 | 公司风格 `companyStyle` | input | placeholder `一线互联网大厂` |

章节表单保持原版两个输入（章节文件名 `id`、显示名 `name`）+ 说明「新章节自带一条「章节结束」，免得一保存就报缺少结束事件。」

## 6. 文案对照表

### ValidateTab
| key | 中文 |
|---|---|
| `validate.menuTitle` | `剧本校验` |
| `validate.revalidate` | `重新校验` |
| `validate.errors` | `错误` |
| `validate.warns` | `警告` |
| `validate.infos` | `提示` |
| `validate.checking` | `正在校验…` |
| `validate.clean` | `没有发现问题，这个剧本可以正常跑起来。` |
| `validate.scriptLevel` | `剧本整体` |
| `validate.passed` | `通过` |
| `validate.open` | `打开` |
| `validate.eventJump` | `第 {index} 个事件` |

### EditorModals
| key | 中文 |
|---|---|
| `editorModals.confirm` | `确定` |
| `editorModals.newScript` | `新建剧本` |
| `editorModals.newChapter` | `新建章节` |
| `editorModals.scriptName` | `剧本名` |
| `editorModals.description` | `简介` |
| `editorModals.descriptionPlaceholder` | `例如：一起看星星` |
| `editorModals.chapterFileName` | `章节文件名` |
| `editorModals.chapterIdHint` | `main2，或 Intro/intro2 放进子目录` |
| `editorModals.displayName` | `显示名` |
| `editorModals.chapterNameExample` | `例如：2 樱花的公园` |
| `editorModals.chapterEndNote` | `新章节自带一条「章节结束」，免得一保存就报缺少结束事件。` |

### ScriptListPanel
| key | 中文 |
|---|---|
| `scriptList.delete` | `删除剧本` |
| `scriptList.newScript` | `＋ 新建剧本` |
| `scriptList.deleteConfirmTitle` | `删除剧本`（store action 里已写死，无需在组件里再写） |

### EditorHeader
| key | 中文 |
|---|---|
| `editorHeader.tabValidate` | `校验` |

## 7. 组件改动

### 7.1 `ScriptListPanel.vue`
- `defineEmits<{ "new-script": [] }>()`
- 卡片右侧（`s.chapterCount` 那段之后）加原版删除按钮：

```vue
          <button
            class="rounded px-[5px] text-[11px] leading-[1.4] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-red-400/15 hover:text-red-300"
            title="删除剧本"
            @click.stop="deleteScript(s.key, s.scriptName)"
          >
            ✕
          </button>
```
（`deleteScript` 从 store import）
- 卡片容器 class 补 `hover:border-[var(--accent-color)]`（已有）+ 保持 `group`
- 末尾加原版「＋ 新建剧本」按钮（本项目没有 `Button type="big"`？有——`components/base/widget/Button.vue` 支持 `type="big"`，按现有用法确认；确认不支持就用 FlowTab 同款小按钮样式）：

```vue
      <button
        class="mt-4 inline-flex w-full items-center justify-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-2 text-[0.85rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:bg-white/[0.12] hover:text-white"
        @click="emit('new-script')"
      >
        ＋ 新建剧本
      </button>
```

### 7.2 `FlowTab.vue`
- 加 `const emit = defineEmits<{ "new-chapter": [] }>()`
- 「＋ 新建章节」按钮 `@click="emit('new-chapter')"`
- **删掉** `createChapterFlow()` 整个函数与 `chapters` / `createChapter` 两个 import（弹窗接管后它们没用了）

### 7.3 `EditorHeader.vue`
- `tabs` 加 `{ key: "validate", label: "校验", icon: "achievement" }`（放在 config 之后）
- `Button` 里 `tab.key === "validate"` 时渲染角标：

```vue
          <span
            v-if="tab.key === 'validate' && store.report && store.report.errorCount > 0"
            class="ml-1 rounded-full bg-red-400/20 px-1.5 text-[0.62rem] leading-[1.5] text-red-300"
            >{{ store.report.errorCount }}</span
          >
```
- `switchTab` 里加：

```ts
  // 原版对校验页做预加载：进页面就先跑一遍，作者不用先点「重新校验」
  if (key === "validate") void runValidation();
```
（`runValidation` 从 store import）

### 7.4 `ScriptEditorView.vue`
- `tabComponents` 加 `validate: ValidateTab`
- `currentTabComponent` 的 `:key` 不用改（E4 已按 level 区分）
- 模板里 `<component>` 上接两个 emit，并在根节点末尾挂弹窗：

```vue
      <component
        :is="currentTabComponent"
        :key="tabKey"
        class="absolute inset-0"
        @new-script="modal = 'script'"
        @new-chapter="modal = 'chapter'"
      />
...
    <EditorModals v-model:modal="modal" />
```
- 脚本里：

```ts
const modal = ref<"script" | "chapter" | null>(null);
```

> `EditorModals` 内部用了 `Teleport to="#app"`，放在根 div 里即可。

## 8. 验收

### 8.1 后端
```bash
bash scripts/mvn.sh test          # 全绿，含新增 8 条断言
```
真机 curl（做完必须清理干净）：

```bash
# 1) 建
curl -s -X POST localhost:8080/api/editor/scripts -H 'Content-Type: application/json' \
  -d '{"code":"tmp-e5a","scriptName":"临时剧本","description":"验收用","jobCode":"java","level":"校招","companyStyle":"一线互联网大厂","interviewerCode":"friendly","introChapter":"intro"}'
# 2) 查：chapters 应 1 项、storyConfig.interviewer_code=friendly
curl -s localhost:8080/api/editor/scripts/tmp-e5a
# 3) 重复建 → 400 且消息含「已存在」
# 4) 非法 code（"Bad Code"）→ 400
# 5) 删
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE localhost:8080/api/editor/scripts/tmp-e5a   # 204
# 6) 再删 → 404
# 7) 列表里没有 tmp-e5a，其它 4 个剧本仍在
```

### 8.2 前端
```bash
cd frontend && npx vue-tsc --noEmit && npm run build
```

### 8.3 真机（8080 + 5173 都在跑）

1. `/script-editor` → 顶部 Tab 变成 **3 个**：章节流程 / 剧本设置 / 校验。
2. 不打开剧本时只有「章节流程」可点，其余置灰。
3. 打开 `java-strict` → 点「校验」→ 顶部显示 `0 错误 · 1 警告 · N 提示`（以实测为准），
   列一张「剧本整体」卡（若有剧本级诊断）+ 6 个章节卡；无诊断的章节显示绿色「通过」且整卡 `opacity 0.55`。
4. 点某条有 `eventIndex` 的诊断 → 自动跳到章节编辑页、选中对应事件、右侧属性面板显示该事件。
   点章节头的「打开」→ 进该章节编辑态。
5. 顶栏「校验」 Tab 上有红色错误数角标（先把某事件 `text` 清空制造一个 error，等 2.5s+ 校验完成，
   角标出现；验收后撤销）。
6. 回剧本列表（面包屑「← 剧本列表」）→ 底部有「＋ 新建剧本」→ 弹窗有 7 个字段 →
   填 `code=tmp-ui`、剧本名「临时剧本」→ 确定 → 列表多一项且**自动进入**新剧本的章节流程页
   （1 章「开场」，卡片显示 1 个事件）。
7. 回列表 → 悬停新剧本卡片 → 右侧出现 `✕` → 点它 → 确认弹窗 → 确定 → 列表里消失。
8. 进入任一剧本 → 章节流程 → 点「＋ 新建章节」→ 弹出「新建章节」弹窗（不再是浏览器 prompt）
   → 填 `id=tmp-ch`、名「临时章节」→ 确定 → 流程图上多一张卡片；验收后点卡片进章节，
   用卡片上的 `✕`（或 `DELETE /chapter?id=tmp-ch`）删掉，确认章节数与开始时一致。
9. 控制台无 404 / 报错。

截图存 `screenshots/e5a/`（至少 4 张：校验页、诊断跳转、新建剧本弹窗、新建章节弹窗）。

**验收后必须还原数据**：`tmp-ui` / `tmp-ch` / 任何临时事件都不许留在库里；
结束时 `curl localhost:8080/api/editor/scripts` 应仍是原来那批剧本（数量、code 与开始时一致），
`java-strict` 的 `intro` 章节事件数与开始时一致。

## 9. 禁区

1. 不许跑任何 `git` 命令（读的也不要）。
2. 后端只能用 `bash scripts/mvn.sh test` / `scripts/mvn.sh package`，**不许裸 `mvn`**。
3. 不许改 `docs/`、`.workbuddy/`、`specs/` 下任何已有文件（含本规格）。
4. 不许只读地"顺手"改 E4 已完成的时间线/事件行/属性面板；如果必须动，先停下写进报告。
5. 不许自创 API 路径：新建是 `POST /api/editor/scripts`，删除是 `DELETE /api/editor/scripts/{key}`。
6. 不许把 `window.prompt` 留在任何新建流程里。
7. 不许为了过类型检查加 `any` / `@ts-ignore`。
8. 不许改 `interview_script` / `interview_chapter` 的 DDL（本批不加列）。
9. 删除剧本时**不许**跳过「解绑场次」那一步（会留下悬空 `script_id`）。

## 10. 交付报告

改动文件清单（含行数）、`mvn test` 结尾原始输出、curl 7 步结果、真机 9 步逐条结果、
截图路径、数据还原前后对比（剧本列表 code 列表、intro 事件数）、以及所有偏离规格之处（含原因）。
