# V5 + E6b · 剧本编辑器观感对齐 + 素材/角色两个 Tab

> 主线写规格，子 agent 执行。这两批都在 `components/script-editor/` 内，**重合度高，由你一个人顺序做完**，不要拆分并行。
> V5 只改观感；E6b 新增两个 Tab。

## 0. 文件边界（越界视为失败）

**只准改** `frontend/src/components/script-editor/` 与 `frontend/src/views/ScriptEditorView.vue`。
不许动 `frontend/src/store/`、`api/`、`data/`、`styles/`、`components/settings/`、`components/game/`——
别的 agent 正在并行改这些地方。

若你发现必须改 `store/` 或 `api/`（比如新 Tab 需要一个 API 方法），
**不要自己改**，在报告里写明「需要 XX 文件加 YY，我没动」。
例外：`api/editor.ts` 允许的改法是**只新增导出函数**、不改既有的。
新 Tab 若要新方法，可以新增在 `frontend/src/api/` 下**新建**一个文件（如 `assets.ts`），
不许改 `editor.ts`。

## 1. 蓝本

| 蓝本 | 用途 |
|---|---|
| `references/lingchat/src/components/script-editor/tabs/AssetsTab.vue` | 素材 Tab |
| `references/lingchat/src/components/script-editor/tabs/CharactersTab.vue` | 角色 Tab |
| `references/lingchat/src/components/script-editor/panels/ScriptListPanel.vue` | 列表卡片观感 |
| `references/lingchat/src/components/script-editor/flow/EventRow.vue` | 事件行密度 |
| `references/lingchat/src/components/script-editor/flow/EventPropertyPanel.vue` | 属性面板间距 |

先 `ls` 确认；名字对不上就在报告里写实际路径。

## 2. V5 · 观感对齐（只改皮）

按顺序比对并修正，逐项记录"改动前 → 改动后"：
1. **剧本列表卡片**（`ScriptListPanel.vue`）：卡片高度、圆角、边框、hover 态、
   标题/副标题字号字重、间距。
2. **章节网格卡片**：同上。
3. **事件行密度**（`EventRow.vue`）：行高、内边距、字号、类型标签色块、
   变量角标、摘要截断。
4. **属性面板间距**（`EventPropertyPanel.vue`）：标签与控件间距、
   分组间距、滚动区内边距。
5. **顶栏**（`EditorHeader.vue`）：高度、图标尺寸、Tab 选中态层次。

**禁区**：不改任何组件的逻辑、不改中文文案、不改 class 的语义结构（只调样式值）。

## 3. E6b · 素材 Tab（新建）

1. 新建 `frontend/src/components/script-editor/tabs/AssetsTab.vue`，
   逐字对照原版 `AssetsTab.vue`（221 行）改写。
2. 5 类素材：背景 `backgrounds` / 立绘 `avatars` / 气泡 `animation` /
   音乐 `musics` / 音效 `audio_effects`（以原版为准；先读原版再定）。
3. 需要后端端点就**新建** `frontend/src/api/assets.ts` + 后端
   `backend/.../api/AssetsController.java`（允许新建，不许改既有 controller）。
4. 素材资产已就位：`frontend/public/audio_effects/`（23 个 wav）、
   `frontend/public/pictures/animation/`（16 个 webp）、
   `frontend/public/backgrounds/`、`frontend/public/avatars/`。
   列目录时优先扫这些 public 目录；**不要去扫 `references/`**（那是源项目，不是运行时资产）。
5. 上传/删除：落 `frontend/public/` 对应子目录，走后端文件写入
   （对齐 E6a 的 appearance 上传做法，先读它的实现再照做）。

## 4. E6b · 角色 Tab（新建）

1. 新建 `frontend/src/components/script-editor/tabs/CharactersTab.vue`，
   逐字对照原版 `CharactersTab.vue`（116 行）改写。
2. 数据源：后端 R1a 已上线的 `/api/editor/characters`
   （列表 / 详情 / 新建 / 保存 / 删除，字段见 R1a 规格 §1）。
   前端需要的方法**新建在 `frontend/src/api/` 下新文件**，或复用已存在的
   `api/characters.ts`（R1b 建的）——**可以改 `api/characters.ts` 新增方法，
   但不许删改它既有的导出**。
3. **不要自己造"面试官"语义**：按智豪裁决，这里就是 LingChat 原版的角色卡管理
   （folder = 目录名，settings.yml 全字段编辑）。

## 5. 约束

1. 不许跑任何 git 命令（含只读）。
2. 不许改 `frontend/src/store/`、既有 `api/editor.ts`、`data/`、`styles/`、
   `components/settings/`、`components/game/`、`backend/`（除允许的新建）。
3. 不许加 `any` / `@ts-ignore` / 删类型。
4. 不许改中文文案。
5. 不许引入新依赖（cropperjs 已在，可复用）。
6. V5 阶段不许动任何逻辑，只调样式。

## 6. 验收

### 6.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 6.2 静态
- `grep -rn "var(--accent-color)" src/components/script-editor/` →
  只允许剩在 `<style>` 块内，class 任意值写法应为 0 命中。
- 顶栏 Tab 数量：改动后应为 **6 个**（章节流程 / 剧本设置 / 校验 / 素材 / 角色 / 外观），
  顺序与「章节流程在最左、其余按原版顺序」一致。

### 6.3 真机（playwright-core + 系统 Chrome，1440×900）
1. 打开 `/script-editor`，点进 `java-strict`。
2. 断言：顶栏 6 个 Tab 均可见；点「素材」能切过去且面板有内容（不报错）；
   点「角色」能切过去且列出 4 张原卡（风雪 / 诺一钦灵 / 松子 / DeepSeek）。
3. 断言：控制台 0 报错。
4. 回归：切回「章节流程」，章节卡与事件行仍在、立绘仍在动（breathing）。
5. 截图存 `screenshots/v5/`：`01-assets-tab.png`、`02-characters-tab.png`、
   `03-flow-regression.png`。

## 7. 报告

改动文件清单（含行数）、构建原始输出末尾、真机 5 步逐条结果、截图路径、
观感逐项「改动前 → 改动后」对照、全部偏离规格之处。诚实报告：没做到的直接说没做到。
