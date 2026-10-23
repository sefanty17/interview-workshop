# R1b · 前端角色卡接入（原版 characters 1:1）

> 主线写规格，子 agent 执行。本批只做**前端**。后端端点 R1a 已上线跑在 8080。

## 0. 背景

智豪裁决：**不存在「自由对话 / 面试」两条分开的角色链路，一切 1:1 仿照 LingChat。**
自由对话里角色就是 LingChat 原版的角色卡（风雪 = 那只猫娘），不该被"面试官"语义覆盖。

现状问题（已定位）：`views/InterviewRoomView.vue` 有三处把原卡字段换成了面试语义：

| 位置 | 现状 | 原版应为 |
|---|---|---|
| 第 231 行、第 700 行 | `roomStore.showCharacterSubtitle = persona.value.displayRole` | 原卡 `ai_subtitle`（前端 `subtitle`） |
| 第 217 行附近 | 开场白 `${persona.opening}\n\n—— 当前岗位：${job.name} · ${persona.displayRole}` | 原卡人设自带的开场，不追加岗位后缀 |

本批把前端接到 R1a 的角色卡 API 上，并把上述三处改回原卡语义。
**只动显示层与数据接入，不动对话链路的事件协议、不动 prompt 组装。**

## 1. R1a 后端契约（已 curl 实测）

```
GET    /api/editor/characters                → 200 [CharacterCardSummary]
GET    /api/editor/characters/{folder}       → 200 CharacterCard
POST   /api/editor/characters/{folder}       → 201
PUT    /api/editor/characters/{folder}       → 204
DELETE /api/editor/characters/{folder}       → 204
```

`CharacterCard` 字段（实测 keys）：
`folder, aiName, aiSubtitle, userName, userSubtitle, title, info, scale, offsetX, offsetY,
bubbleTop, bubbleLeft, avatarMode, voiceModels, bodyPart, clothes, rawYaml`

`folder` 取值：`风雪 / 诺一钦灵 / 松子 / DeepSeek`；对应前端 code：
`strict / friendly / pressure / expert`（映射写死在前端常量里）。

`CharacterCardSummary` 字段：`folder, aiName, aiSubtitle, userName, userSubtitle, title, avatarMode`。

## 2. 交付文件

### 2.1 新建 `src/api/characters.ts`

对齐 `src/api/editor.ts` 的风格与 baseURL 处理方式（先读它再写）。

```ts
export interface CharacterCardSummary {
  folder: string;
  aiName: string;
  aiSubtitle?: string;
  userName?: string;
  userSubtitle?: string;
  title?: string;
  avatarMode?: string;
}

export interface CharacterCard extends CharacterCardSummary {
  info?: string;
  scale?: number;
  offsetX?: number;
  offsetY?: number;
  bubbleTop?: number;
  bubbleLeft?: number;
  voiceModels?: Record<string, unknown>;
  bodyPart?: unknown;
  clothes?: unknown;
  rawYaml?: string;
}

export async function fetchCharacters(): Promise<CharacterCardSummary[]>
export async function fetchCharacter(folder: string): Promise<CharacterCard>
export async function createCharacter(folder: string, fields?: Record<string, unknown>): Promise<void>
export async function saveCharacter(folder: string, body: { fields?: Record<string, unknown>; rawYaml?: string }): Promise<void>
export async function deleteCharacter(folder: string): Promise<void>
```

folder 走 `encodeURIComponent`。

### 2.2 新建 `src/store/characters.ts`

项目**没有 Pinia**，用模块级 `reactive` 单例 + 独立导出的 `computed`，照 `src/store/scriptEditor.ts` 的写法。

state：`cards: CharacterCardSummary[]`、`loaded: boolean`、`loading: boolean`、`error: string | null`
action：`ensureCharactersLoaded()` —— 幂等懒加载（已有数据或正在加载则直接返回），失败只写 error、不抛。
getter：
- `characterCards`
- `characterByFolder(folder)`（函数式 getter，导出普通函数即可）
- `cardForCode(code)`：code → folder → 对应 summary

### 2.3 改 `src/views/InterviewRoomView.vue`

1. 在 `onMounted` 开头（或 setup 里）调用 `ensureCharactersLoaded()`。
2. 新增 computed `card`（当前角色卡的 summary：`cardForCode(persona.value.code)`）。
3. 第 231 行与第 700 行的
   `roomStore.showCharacterSubtitle = persona.value.displayRole;`
   改成
   ```ts
   roomStore.showCharacterSubtitle = card.value?.aiSubtitle ?? persona.value.subtitle;
   ```
   （`card` 未加载完时回退到本地卡 subtitle，两者本应一致。）
4. 第 628 行 `personaName` 里的 `· ${persona.value.displayRole}` **保留不动**——
   那是写进面试记录的历史数据，带面试角色名是合理的，视觉上不出现在舞台上。
5. 开场白：找到 `ensureServerSession` / 本地开场里拼接
   `—— 当前岗位：${job.name} · ${persona.displayRole}` 的那处（约第 217 行），
   改成只有 `persona.opening`，不追加岗位后缀。
   若该拼接还用于「剧本模式」的旁白提示，则只在**无剧本（自由对话）**时去掉后缀；
   有剧本时保留。判据：看该处是否能拿到 script，拿不到就是自由对话。
   **拿不准就在报告里写明你选了哪种，不要猜着改。**

### 2.4 可选（有余力再做，不做要说明）
首页 `HomeView` 的固定立绘 `alona.png` 与当前角色卡联动。
先确认原版 `MainMenu.vue` 是不是固定 alona——如果是，**不要改**，在报告里说明"原版即固定"。

## 3. 约束

1. 不许跑任何 git 命令（含只读）。
2. 不许改 `backend/`、`docs/`、`.workbuddy/`、`specs/`（含本规格）。
3. 不许改 `data/interviewers.ts`（生成产物）与 `scripts/*.py`。
4. 不许加 `any` / `@ts-ignore` / 删类型。
5. 不许动对话链路的事件协议（`ai:reply` 等）与 prompt 组装。
6. 新 store 的命名与风格必须跟 `scriptEditor.ts` 一致；不许自创状态管理方案。

## 4. 验收

### 4.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 4.2 静态
- `grep -rn "from \"../api/characters\"\|from \"../../api/characters\"" src/` 有命中（说明真的接上了，不是写了不用）
- 第 231 / 700 行附近不再出现 `displayRole` 作为舞台副标题来源

### 4.3 真机（playwright-core + 系统 Chrome）
1. 后端 8080 在跑；前端 5173 在跑（若没起就 `npm run dev`）。
2. 打开 `/`，进「开始面试 → 自由面试」，进房间。
3. 断言：立绘旁/对话框里的副标题文本 = 当前角色卡 `ai_subtitle`
   （strict 应为 `LingChat Studio`），**不是** `严格技术面`。
4. 断言：控制台 0 报错；Network 里 `GET /api/editor/characters` 有一次 200。
5. 截图存 `screenshots/r1b/01-room-subtitle.png`。

## 5. 报告

按第 4 节写：改动文件清单（含行数）、构建原始输出末尾、真机 5 步逐条结果、
截图路径、全部偏离规格之处（含原因）。诚实报告：没做到的直接说没做到。
