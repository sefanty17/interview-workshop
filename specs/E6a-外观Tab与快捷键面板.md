# E6a · 外观 Tab + 快捷键面板 + 裁剪弹窗（编辑器剩余功能 · 第一批）

> 对照原版：`references/lingchat/src/components/script-editor/{tabs/AppearanceTab.vue, panels/ShortcutHelpPanel.vue, modals/ImageCropModal.vue}` + `utils/shortcuts.ts` + `views/ScriptEditor.vue`。
> 本批范围：appearance Tab 全链路（含后端 2 个端点）、ShortcutHelpPanel + 快捷键基建。
> **不做**：CharactersTab / AssetsTab（E6b，等裁决）、agent 六件套（E7）。
> 一比一原则：布局/结构/类名/数值照抄原版，允许改的只有：① vue-i18n → 中文直写（词条照抄 `references/lingchat/src/locales/zh-CN/scriptEditor.ts`）；② Tauri 文件协议 → HTTP/浏览器等价物（下文逐条写死）；③ `text-brand`/`bg-brand` → `[var(--accent-color)]` / `bg-[var(--accent-color)]/XX`（现有 EditorHeader 的既有做法）。

## 三条禁令（违反即废弃本轮）

1. 不许跑任何 git 命令。
2. 不许用裸 `mvn`，后端构建/测试只能 `cd backend && bash ../scripts/mvn.sh test`。
3. 不许改这些文件：`frontend/src/api/chat.ts`、`frontend/src/views/InterviewRoomView.vue`、`backend/src/main/java/cn/edu/zjut/interview/engine/**`、`stream/**`、`data/mock.ts`、`.workbuddy/memory/**`、`docs/**`。

---

## 1. 新建 `frontend/src/utils/shortcuts.ts`

把 `references/lingchat/src/utils/shortcuts.ts`（182 行）**逐字照抄**，只改 import 路径（若无外部依赖则不改）。保留全部导出：`ShortcutAction` / `ShortcutBinding` / `DEFAULT_SHORTCUTS` / `SHORTCUT_ACTIONS` / `bindingsEqual` / `captureFromEvent` / `formatBinding` / `sanitizeShortcuts`（以及原文件里其他一切导出）。先通读原文件确认没有 Tauri/平台依赖再抄。

## 2. 新建 `frontend/src/store/editorShortcuts.ts`

原版键位存在 `useSettingsStore().shortcuts`（`references/lingchat/src/stores/modules/settings/index.ts:79`），我们设置面板没有这个 store，等价实现落新文件：

```ts
import { reactive, watch } from "vue";
import { DEFAULT_SHORTCUTS, sanitizeShortcuts, ... } from "@/utils/shortcuts"; // 路径按项目别名写法对齐现有文件（现有文件用相对路径）

type Shortcuts = Record<ShortcutAction, ShortcutBinding>;

const LS_KEY = "script-editor-shortcuts";

function load(): Shortcuts { /* localStorage 读 JSON，merge 进 DEFAULT_SHORTCUTS 后 sanitizeShortcuts，坏数据回落默认 */ }

export const editorShortcuts = reactive({
  shortcuts: load() as Shortcuts,
  /** 支持 "shortcuts"（整组）与 "shortcuts.<action>" 两种 path，ShortcutHelpPanel 要用 */
  update(path: string, value: unknown) { /* path==="shortcuts" 整组替换；否则按 . 后的 action 单项替换 */ },
});

watch(() => editorShortcuts.shortcuts, (v) => localStorage.setItem(LS_KEY, JSON.stringify(v)), { deep: true });
```

## 3. 新建 `frontend/src/components/script-editor/panels/ShortcutHelpPanel.vue`

照抄原版 `ShortcutHelpPanel.vue`（179 行）。逐条替换：
- `useI18n` → 删；所有 `t("scriptEditor.shortcutHelp.*")` 用 `references/lingchat/src/locales/zh-CN/scriptEditor.ts` 里 `shortcutHelp` 段的中文原文直写（`openTable`/`save`/…/`restoreDefault`/`recording`/`conflict`/`singleKeyBlocked`/`customizeHint`/`title` 全要）。
- `useSettingsStore()` → `editorShortcuts`（import 自 `../../../store/editorShortcuts`，相对路径按本项目惯例）。`settings.shortcuts[a]` → `editorShortcuts.shortcuts[a]`；`settings.update(...)` → `editorShortcuts.update(...)`；`settings.update("shortcuts", {...})` → `editorShortcuts.update("shortcuts", {...})`。
- 其余（Teleport `#app`、捕获模式、冲突检测、Transition、类名）一比一。

## 4. 新建 `frontend/src/components/script-editor/modals/ImageCropModal.vue`

照抄原版（127 行）。逐条替换：
- **prop 改名**：`srcPath: string` → `srcUrl: string`（网页版拿到的是 `URL.createObjectURL(file)`，见 §6）。`const imgSrc = convertFileSrc(props.srcPath)` → `const imgSrc = props.srcUrl`。
- 删 `@tauri-apps/api/core` import。
- `cropperjs`：先跑 `cd frontend && npm install cropperjs@1.6.2 --save`。**若 npm 安装失败**，立即停手并在报告里写明，不要用别的库替代。
- confirm 输出名：仍从 `props.srcUrl` 取不到原名 —— AppearanceTab 会在调用处把原始文件名传进来。prop 增加 `srcName: string`（网页版新增，属实现方式差异），confirm 里 `const raw = props.srcName`，其余逻辑（`_crop.webp`、webp 0.92、maxWidth 1920）不变。
- 其余一比一（含 scoped 覆写 cropperjs 暗色主题那 5 条）。

## 5. 新建 `frontend/src/components/script-editor/tabs/AppearanceTab.vue`

照抄原版（182 行）。逐条替换：
- i18n → 中文直写（`appearance` 段：`intro`/`menuTitle`/`current`/`pick`/`resetTitle`/`reset`/`resetDesc`/`pickHint`/`fromLibrary`/`emptyLibrary`/`builtin`/`blur`/`clear`/`soft`/`dim`/`bright`/`dark`/`dimHint`/`visual`，全部取 zh-CN 词条原文）。
- Tauri `openDialog` → 隐藏 `<input type="file" accept="image/png,jpeg,webp,bmp,gif">` + `ref`，`pickImage()` 改为触发 click，`change` 事件里取 `File`：
  - `cropSrc.value = URL.createObjectURL(file)`，同时 `srcName.value = file.name`；
  - 弹窗关闭/确认后 `URL.revokeObjectURL(...)`。
- `ImageCropModal` 传 `:src-url="cropSrc" :src-name="cropName"`，confirm 回调 `(dataUrl, name) => store.uploadEditorBgData(dataUrl, name)` 不变。
- `Slider` / `Icon` / `MenuItem` 用本项目现有组件（原版 import 的 `@/components/base`、`@/components/ui` → 相对路径 `../../base`、`../../ui`；确认本项目 base 里有 `Slider`，**没有就停手报告**）。
- store 侧 `store.editorBg` / `store.globalBgFiles` / `setEditorBgPath` / `resetEditorBg` / `uploadEditorBgData` / `refreshGlobalBgFiles` 全部照用（§7 实现）。

## 6. `frontend/src/api/editor.ts` 追加

文件末尾追加（沿用现有 `requestJson`/fetch 封装风格，看文件里现有函数怎么写就怎么写）：

```ts
/** 网页版 AssetFile：Tauri 绝对路径换成可访问的 HTTP URL（实现方式差异） */
export interface AssetFileWeb { name: string; url: string; size: number }

export function uploadEditorBg(dataUrl: string, name: string): Promise<{ url: string }> { /* POST /api/editor/appearance/background, JSON body */ }
export function listGlobalBackgrounds(): Promise<AssetFileWeb[]> { /* GET /api/editor/appearance/backgrounds */ }
```

## 7. `frontend/src/store/scriptEditor.ts` 追加

- state：`editorBg: { path: string; blur: number; dim: number }`（默认 `{ path: "", blur: 12, dim: 0.3 }`，对齐原版 `DEFAULT_EDITOR_BG`）、`bgVersion: number`（初值 0）、`globalBgFiles: AssetFileWeb[]`（初值 `[]`）。
- `editorBg` 整体持久化：localStorage 键 `script-editor-editorbg`，load 时 merge 默认值、blur 限 0~24、dim 限 0~0.9。
- actions（挂进现有 return 对象）：
  - `setEditorBgPath(path)`：改 path，`bgVersion++`（强制 `<img>/url()` 重取，对齐原版 `bgImageSrc` 的 `?v=` 做法）。
  - `resetEditorBg()`：回默认值，`bgVersion++`，并删 localStorage。
  - `uploadEditorBgData(dataUrl, name)`：调 `api/editor.uploadEditorBg`，成功后 `setEditorBgPath(返回的 url)`，返回 url；失败返回 null（对齐原版语义）。
  - `refreshGlobalBgFiles()`：调 `listGlobalBackgrounds()` 填 `globalBgFiles`。
- `ENABLED_TABS` 加 `"appearance"`（`store/scriptEditor.ts:105`）。

## 8. 后端：`ScriptEditorController` + `ScriptEditorService` 各加一组

落盘位置：**与剧本背景导入同一条路**。先看 `importer/` 里把剧本 Assets 复制到 `frontend/public/backgrounds/scripts/<code>/` 的现有代码怎么解析 frontend/public 根路径（找 `SCRIPT_ASSET_PREFIX` 的消费点），复用同一解析逻辑。

- `POST /api/editor/appearance/background`，JSON body `{ "dataUrl": "data:image/webp;base64,...", "name": "xxx_crop.webp" }`：
  - `name` 白名单校验 `^[A-Za-z0-9_-]+\.webp$`，不匹配 400；
  - dataUrl 必须以 `data:image/` 开头，取逗号后 base64 解码，解码失败 400；
  - 写入 `<frontend/public>/backgrounds/editor/<name>`（目录不存在就建）；
  - 返回 `{ "url": "/backgrounds/editor/<name>" }`。
- `GET /api/editor/appearance/backgrounds`：合并三处，返回 `List<AssetFileWeb>`（字段 name/url/size）：
  1. 内置 3 张：`ResourceMapping.BACKGROUNDS` 的 value 即 url，name 取文件名，size 读真实文件（`frontend/public` 下）；
  2. `backgrounds/scripts/*`：每张图一条（url = `/backgrounds/scripts/<code>/<file>`）；
  3. `backgrounds/editor/*`：每张一条。
  按 url 排序。任一目录缺失跳过，不报错。
- 单测 `ScriptEditorAppearanceTest`（放现有测试包，风格照 `ScriptEditorServiceScriptTest`）：① 合法上传落盘且返回 url；② 非法 name 400；③ list 包含刚上传的文件。用 `@TempDir` 或测试专用根，不许污染真实 `frontend/public`（看现有测试怎么隔离 IO，照做）。

## 9. 接线（3 个文件）

### 9.1 `frontend/src/components/script-editor/panels/EditorHeader.vue`
- `tabs` 数组加 `{ key: "appearance", label: "外观", icon: "palette" }`（若 base 的 IconType 没有 `palette`，看 Icon 组件的类型定义与现有图标名，选最接近的并在报告里写明；不许改 Icon 组件本身，除非它支持注册新图标——先查）。
- disabled 逻辑：`!store.detail && tab.key !== "flow"` → `!store.detail && !["flow", "appearance"].includes(tab.key)`（原版 EditorHeader:125 同规则）。
- 238 行注释处落实「快捷键」按钮：样式照原版 EditorHeader（`text-[var(--accent-color)]` 等价替换），`@click="emit('toggle-shortcut-help')"`，`title` 显示 `formatBinding(editorShortcuts.shortcuts.shortcutHelp)`。
- 撤销/重做按钮的 `title` 加键位提示：`(formatBinding(editorShortcuts.shortcuts.undo))` / `redo`（原版 245/253 行做法）。

### 9.2 `frontend/src/views/ScriptEditorView.vue`
- import `AppearanceTab`、`ShortcutHelpPanel`、`editorShortcuts`、`formatBinding`。
- tab 组件映射加 `appearance: menuTab(AppearanceTab)`，`menuTab` 包装函数照原版抄（`h(MenuPage, null, () => h(inner))`，放进本文件）。
- `tabKey` 计算串加 appearance + level（现有逻辑怎么拼就怎么扩）。
- `<ShortcutHelpPanel :visible="shortcutHelp" @close="shortcutHelp = false" />`，`shortcutHelp` ref 挂在 toggle-shortcut-help 上。
- `onKey` 改造：`isTyping` 判断保留；save/undo/redo 三组键位从硬编码改为 `editorShortcuts.shortcuts` + utils/shortcuts 的匹配能力（原版 `views/ScriptEditor.vue` 的 keydown 怎么做的就照做——先读原文件对应段，如果原版是集中式匹配器就照抄匹配方式；保持「在输入框里让位给浏览器原生撤销」的现有注释语义）。
- 背景接 editorBg：现有 `.bg-layer` 结构不动，`<div class="bg-layer" :style="{ '--bg-blur': ..., '--bg-dim': ... }">`，`bg-image` 的 style 绑定 `backgroundImage`（`store.editorBg.path ? url(--?v=bgVersion) : none`），CSS 里 `--bg-blur`/`--bg-dim` 参与原有 filter/遮罩（照原版 `ScriptEditor.vue` 的 `.bg-layer/.bg-image/.bg-dim-mask` CSS 抄齐缺失部分，渐变兜底保留）。
- onMounted 时 `void store.refreshGlobalBgFiles()`（ AppearanceTab 自己也会拉，双保险可省——按原版为准：原版只在 AppearanceTab onMounted 拉，那就只放组件里）。

## 10. 验收（主线亲测，子 agent 自查后写报告）

1. `cd frontend && npx vue-tsc --noEmit` → 0 error。
2. `cd frontend && npx vite build --outDir dist-e6a` → 成功（目录留在 .gitignore 规则内）。
3. `cd backend && bash ../scripts/mvn.sh test` → 全绿且含新测试。
4. 自查后端：curl 上传一个 1x1 webp base64 → 返回 url；GET backgrounds 含它；非法 name 400。
5. 报告格式：逐文件列出「新增/修改 + 行数 + 与原版的差异点」；npm install cropperjs 是否成功；未完成项单独列。

## 11. 环境备注

- Bash 里不要用反引号内联脚本；写文件一律用编辑工具。
- `npm run build` 会被 safe-delete shim 拦 dist 清空 → 用 `npx vite build --outDir dist-e6a`。
- 后端如果已在跑会锁 target jar：先确认 `mvn.sh test` 是否报锁，报锁就说明占用，写进报告不要强杀。
