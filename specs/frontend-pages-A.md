# 施工规格 A：设置面板 10 个 Tab

工作目录：`D:\cxdownload\javaee课设\frontend`

## 铁律

1. 只改 `frontend/src` 内文件，不碰 `references/`
2. **只用**下面列出的基础组件拼装，禁止自造新样式体系、禁止引第三方 UI 库
3. 颜色只用 `#79d9ff`（主题色）+ `white/10` 玻璃底 + 语义色（emerald 选中 / red 删除 / cyan 加入）
4. 不改路由、不改首页、不改 `PersonaTab.vue`
5. 所有修改即改即存（调 `updateSettings`），不做保存按钮
6. 最后必须 `npm run build` 零错误

## 可用的基础组件（已存在，直接用）

| 组件 | 路径 | API |
| --- | --- | --- |
| `MenuItem` | `components/settings/MenuItem.vue` | `title`、`icon`（emoji 字符串）、`size`（`large`=100% 宽 / `small`=半宽并排）、默认插槽 |
| `LcModal` | `components/ui/LcModal.vue` | `v-model:show` 用 `:show` + `@close`；`title`、`width`（默认 28rem）；默认插槽放内容，`footer` 插槽放按钮 |
| `AppDialog` | 已全局挂在 `App.vue` | 用 `import { alertDialog, confirmDialog } from "../../store/dialogStore"`；`await confirmDialog("标题","正文")` 返回 boolean |
| `SettingRow` | `components/ui/SettingRow.vue` | `label`、`hint`；默认插槽放右侧控件 |
| `LcSlider` | `components/ui/LcSlider.vue` | `v-model`（number）、`min`、`max`、`step`、`unit` |
| `LcToggle` | `components/ui/LcToggle.vue` | `v-model`（boolean） |
| `LcSelect` | `components/ui/LcSelect.vue` | `v-model`、`options: {value,label}[]` |
| `glass-input` | `styles/lingchat.css` | 全局类名，用于文本输入 |
| `btn-big` | `styles/lingchat.css` | 全宽大按钮 |

半宽并排写法：两个 `MenuItem size="small"` 直接相邻，`MenuItem` 自带 `inline-block` 与 `margin-right`，无需外层包裹。

## 数据层改动

在 `src/data/mock.ts` 的 `SiteSettings` 接口**追加**下列字段（全部给默认值，写进 `defaultSettings`），不要改已有字段：

```ts
// 通用
fontFamily: string;        // "system"
textSpeed: number;         // 50  0-100
autoAdvanceDelay: number;  // 1200 ms
mergeThreshold: number;    // 0 字，0=关闭
mergeDelay: number;        // 300 ms
inlineAction: boolean;     // true
replyCount: number;        // 1  1-3
idleRemind: boolean;       // false
rememberPreference: boolean; // true
soundEffect: boolean;      // true

// 背景
sceneId: string;           // "classroom"
starOn: boolean;           // true
meteorOn: boolean;         // true
particleFps: number;       // 60
weather: string;           // "none"

// 声音
outputDevice: string;      // "default"
inputDevice: string;       // "default"
volumeSpeaker: number;     // 80
volumeSfx: number;         // 60
volumeBgm: number;         // 40
volumeAchievement: number; // 70
volumeAmbient: number;     // 30
bgmOn: boolean;            // true
bgmTrack: string;          // "calm"
ambientOn: boolean;        // false
ambientTrack: string;      // "none"
ttsOn: boolean;            // false
ttsSpeed: number;          // 50
ttsVoice: string;          // "default"
asrOn: boolean;            // false

// 高级设置
llmBaseUrl: string;        // "https://api.deepseek.com"
llmKey: string;            // ""
llmModel: string;          // "deepseek-chat"
temperature: number;       // 80  显示为 0.80
maxTokens: number;         // 2048
promptPreset: string;      // "default"
```

`updateSettings(patch)` 已存在，直接用它。

## 逐页规格

文件统一放 `src/components/settings/tabs/`，命名 `TextTab.vue` / `BackgroundTab.vue` / `SoundTab.vue` / `AchievementTab.vue` / `AdvanceTab.vue` / `LogTab.vue` / `PluginsTab.vue` / `AdventureTab.vue` / `RulesTab.vue` / `SaveTab.vue`。

### 1. AdventureTab（剧本冒险，原型 SettingsAdventure）

- `MenuItem large「剧本总览」`：两行纯文本 —— `已装剧本 4 个` / `进行中 1 个`（读 `store.scripts.length` 与 `store.session.active`）
- `MenuItem large「独立剧本」`：剧本列表，每行 `标题 + 简介 + 右侧「开始」胶囊钮`，数据读 `store.scripts`
- `MenuItem large「剧本面试（测试中）」`：说明文字 + `btn-big`「进入剧本面试」（点击 `notify("剧本面试将在后续版本接入")`）
- `MenuItem small「创意工坊」`：`btn-big`「打开创意工坊」→ `router.push("/workshop")`
- `MenuItem small「创建自己的剧本」`：`btn-big`「新建剧本」→ `router.push("/script-editor")`

### 2. TextTab（通用，原型 SettingsText）

- `MenuItem large「界面字体」`：`LcSelect`（系统默认/思源黑体/更纱黑体）
- `MenuItem large「文字显示速度」`：`LcSlider` 0-100
- `MenuItem large「自动推进延迟」`：`LcSlider` 200-3000 step 100 unit "ms"
- `MenuItem large「显示文字样本」`：一行玻璃底预览框，内容「面试官：请简单介绍一下你自己。」
- `MenuItem small「台词合并阈值」`：`LcSlider` 0-100 unit "字"
- `MenuItem small「台词合并续打延迟」`：`LcSlider` 100-1000 step 50 unit "ms"
- `MenuItem small「追问提示合并方式」`：`LcSelect`（内联/换行/隐藏）
- `MenuItem small「面试官单次发言条数」`：`LcSlider` 1-3
- `MenuItem small「内联动作文本」`：`LcToggle`
- `MenuItem small「久坐提醒」`：`LcToggle`
- `MenuItem small「记住我的面试偏好」`：`LcToggle`
- `MenuItem small「语音音效」`：`LcToggle`
- `MenuItem large「关于」`：三行灰字 —— `面试工坊 v1.0` / `基于 LingChat 前端复刻` / `浙江工业大学 · 计算机学院 · JavaEE 课设`
- `MenuItem large「返回首页」`：`btn-big` → `router.push("/")`

### 3. BackgroundTab（背景，原型 SettingsBackground）

- `MenuItem large「面试场景」`：三张场景卡横排（`grid grid-cols-3 gap-3`），每张 = 16:9 玻璃底方块 + 场景名（天空教室/图书馆/办公室）；选中卡 `border-[#79d9ff]` + 底部主题色小条
- `MenuItem large「粒子效果」`：三个 `SettingRow` —— 星星 `LcToggle`、流星 `LcToggle`、粒子帧率 `LcSelect`（30/60/120 FPS）
- `MenuItem small「天气」`：`LcSelect`（无/雨/雪），标题下加 `text-xs text-white/50` 注「暂只保存设置」
- `MenuItem small「实时预览」`：一个 16:9 深色方块，居中显示当前场景名 + `text-xs` 分辨率占位
- `MenuItem large「性能检测」`：说明一行 + `btn-big`「开始检测」（点击后 `notify("检测完成，当前设备建议开启粒子效果")`）

### 4. SoundTab（声音，原型 SettingsSound）

六个音量区块用 `SettingRow` + `LcSlider`，每行右侧额外一个 `34px` 圆形试听钮（`btn-icon` 风格，图标用 `♪`）：

- `MenuItem large「音频设备」`：两个 `SettingRow` —— 输出设备、输入设备，均 `LcSelect`（默认设备）
- `MenuItem large「音量」`：五个 `SettingRow` —— 面试官语音 80 / 操作音效 60 / 背景乐 40 / 成就音效 70 / 环境音 30
- `MenuItem large「背景音乐」`：`SettingRow` 开关 `LcToggle` + `SettingRow` 曲目 `LcSelect`（静谧/轻快/专注）
- `MenuItem small「环境音」`：`LcToggle` + `LcSelect`（无/键盘声/雨声）
- `MenuItem small「语音合成」`：`LcToggle` + `LcSlider` 语速 + `LcSelect` 音色
- `MenuItem large「语音识别」`：`SettingRow` 开关 `LcToggle`，`hint="使用浏览器语音识别，需麦克风权限"`

### 5. AchievementTab（成就，原型 SettingsAchievement）

- `MenuItem large「成就列表（实验）」`：2 列卡片网格，6 个成就：

| 名称 | 描述 | 解锁 |
| --- | --- | --- |
| 初次面试 | 完成第一场模拟面试 | 是 |
| 连答三题 | 连续三题不卡壳 | 是 |
| 压力面幸存者 | 完成一次压力面 | 否 |
| 高分通过 | 单场得分 ≥ 85 | 否 |
| 七日连练 | 连续七天练习 | 否 |
| 题海漫游 | 累计完成 50 题 | 否 |

卡片：`rounded-xl border p-4`，未解锁 `opacity-50` + 右上 `🔒`；已解锁 `border-[#79d9ff]/40` + 主题色小条。

### 6. SaveTab（存档，原型 SettingsSave）

- `MenuItem large「创建新存档」`：一行 —— `glass-input` 输入框（placeholder「给这次面试起个名字」）+ 绿色创建钮（`px-4 py-2 rounded-lg bg-emerald-500`，文字「创建」）
- `MenuItem large「存档列表」`：2 列卡片网格，读 `store.records`（`InterviewRecord[]`）。每张卡：

```
┌ 左：h-28 w-24 深色预览块（岗位名首字）  右：flex-1 ┐
│  等宽小字 No.1 · 2026.10.20 11:30
│  标题（text-lg font-bold，双击变输入框改名）
│  ─ ─ ─ ─ ─ ─ ─ ─ ─（虚线 border-b border-dashed）
│  最后一条对话摘录（italic text-xs line-clamp-2）
│  [读取 蓝] [覆盖 绿] [删除 红]  ← 三个 rounded-full px-3 py-1 text-xs
└──────────────────────────────────────────────┘
```

进行中（`store.session.active`）额外：`border-[#79d9ff]` + 左上角 `当前` 胶囊。
按钮行为：读取 → `router.push("/interview")`；覆盖 → `confirmDialog` 确认后 `notify`；删除 → `confirmDialog` 后从 `store.records` 移除并 `persist()`。

### 7. AdvanceTab（高级设置，原型 SettingsAdvance）

- `MenuItem large「LLM 接口」`：`SettingRow` × 4 —— Base URL（glass-input）、API Key（`type="password"` glass-input）、模型 `LcSelect`（deepseek-chat / deepseek-reasoner / gpt-4o-mini）、提示词预设 `LcSelect`
- `MenuItem large「生成参数」`：`SettingRow` × 2 —— 温度 `LcSlider` 0-100（右侧显示 `(v/100).toFixed(2)`）、最大 token `LcSlider` 256-8192 step 256
- `MenuItem small「导出配置」`：`btn-big` → 下载 JSON（`Blob` + `URL.createObjectURL`）
- `MenuItem small「导入配置」`：`btn-big` → 触发隐藏 `input[type=file]`，读取后 `updateSettings`
- `MenuItem large「危险操作」`：红框区块（`border border-red-500/30 bg-red-500/5`），标题红字「重置全部设置」，`btn-big` 红色 → `confirmDialog` 后 `resetSettings()`

### 8. LogTab（日志，原型 SettingsLog）

- `MenuItem large「日志」`：顶部工具栏（4 个级别 chips：全部/信息/警告/错误 + 右侧「清空」「导出」小钮），下方深色终端区：
  `mt-3 h-72 overflow-auto rounded-lg bg-black/35 p-3 font-mono text-xs leading-6`
  内容用 mock 8 行（`[12:01:03] [INFO] 面试会话已创建` 等），级别色：INFO white/70、WARN amber-300、ERROR red-400
- chips 选中态：`bg-[#79d9ff]/15 border-[#79d9ff] text-[#79d9ff]`，未选：`bg-white/6 border-white/10 text-white/50`

### 9. PluginsTab（插件，原型 SettingsPlugins）

- `MenuItem large「已装插件」`：3 张卡片（知识点包·Java 后端题库 v1.2 / 压力面题库包 v1.0 / 面试报告模板 v0.9），每张：图标方块 + 名 + 版本 `text-xs` + 来源 + 右侧 `LcToggle`
- `MenuItem small「从压缩包导入」`：`btn-big` 灰显 + `text-xs text-white/40` 注「P2 接入」

### 10. RulesTab（面试规则，新增）

- `MenuItem large「岗位与知识点」`：`SettingRow` 目标岗位 `LcSelect`（读 `jobs`）；下一行知识点 chips（读当前 job 的 `knowledgePoints`，多选，选中态 `border-[#79d9ff] bg-[#79d9ff]/15 text-[#79d9ff]`，未选 `bg-white/6 text-white/50`），点击调 `toggleKp`
- `MenuItem small「题量」`：`LcSlider` 3-10 unit "题"
- `MenuItem small「时长」`：`LcSlider` 10-30 unit "分"
- `MenuItem small「追问深度」`：`LcSlider` 0-4 unit "层"
- `MenuItem small「语音开关」`：`LcToggle` + hint「面试过程中启用语音播报」

## 主视图改动

`src/views/SettingsView.vue`：

1. 引入 10 个 Tab 组件
2. 内容区按 `activeTab` 渲染对应组件，替换掉现在的 `MenuItem + 「本区块待迁移」` 占位
3. 保留现有的 `Transition` 滑入动画与 `PersonaTab` 分支
4. `tabs` 数组顺序不变

## 验收

```bash
cd D:\cxdownload\javaee课设\frontend
npm run build          # 零错误
```

然后逐个 Tab 目检（dev server 在 5173）：
- 每个 Tab 都有内容，无「待迁移」字样
- 半宽 `MenuItem` 成对并排、不换行
- 滑块拖动有数值联动
- 开关点击有状态变化
- 删除/重置类操作会弹确认框
