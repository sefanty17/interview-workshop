# 施工规格 B：4 个独立页 + 首页二级菜单

工作目录：`D:\cxdownload\javaee课设\frontend`

## 铁律

1. 只改 `frontend/src` 内文件，不碰 `references/`
2. 只用现有组件拼装：`PageShell` / `MenuItem` / `LcModal` / `AppDialog` / `SettingRow` / `LcSlider` / `LcToggle` / `LcSelect` / `LineChart` / `RadarChart`
3. 主题色唯一 `#79d9ff`；玻璃底 `rgba(255,255,255,0.1)`；语义色 emerald=确认、red=删除、cyan=主操作
4. 不改路由定义、不改 `SettingsView` / `PersonaTab` / 10 个 Tab
5. 最后必须 `npm run build` 零错误

## 可用基础件

| 组件 | 路径 | 说明 |
| --- | --- | --- |
| `PageShell` | `components/ui/PageShell.vue` | 独立页外壳：天空背景 + 压暗 + 顶部返回钮 + 居中标题 + 大玻璃容器。props：`title`、`backTo`（默认 `/`）；`actions` 插槽放右侧按钮 |
| `MenuItem` | `components/settings/MenuItem.vue` | 玻璃区块卡，`size="small"` 两个并排 |
| `LcModal` | `components/ui/LcModal.vue` | 通用弹窗，`:show` + `@close` + `title` + `width`，`footer` 插槽 |
| `AppDialog` | 全局已挂 | `confirmDialog(title, msg): Promise<boolean>`、`alertDialog` |
| `LineChart` / `RadarChart` | `components/ui/*.vue` | ECharts 封装，已是主题色。props 见文件顶部 |
| `btn-big` / `glass-input` | `styles/lingchat.css` | 全局类 |

所有页面统一：内容包在 `<PageShell>` 里，内部用 `MenuItem` 堆叠。

---

## 1. RecordsView（面试记录，`/records`）

完全重写现有 `views/RecordsView.vue`（现为 ConsoleShell 后台风，删除）。

结构：

```
<PageShell title="面试记录">
  <MenuItem large「进行中的面试」 icon="▶"  v-if="store.session.active">
    高亮卡：border-[#79d9ff] + 左上「进行中」胶囊
    岗位名 · 面试官名 · 第 N 题 · 已进行 mm:ss
    右侧 [继续面试] emerald 胶囊钮 → router.push("/interview")
  </MenuItem>

  <MenuItem large「历史面试」 icon="▤" v-if="records.length">
    2 列卡片网格，每张：
      No.1 · 2026.10.19                 ← font-mono text-[10px] text-white/40
      Java 后端 · 林工                    ← text-base font-bold
      ─ ─ ─ ─ ─ ─ ─ ─ ─                 ← border-b border-dashed border-white/10 my-2
      82 分 · 5 题 · 18 分钟            ← 「82 分」text-2xl text-[#79d9ff] font-bold，其余 text-xs text-white/50
      "最后一条对话摘录…"                ← italic text-xs text-white/40 line-clamp-2
      [查看报告 蓝] [删除 红]            ← rounded-full px-3 py-1 text-xs
    卡片：rounded-xl border border-white/12 bg-white/[0.05] p-4
         hover:-translate-y-[3px] hover:border-[#79d9ff]/50 transition-all duration-300
    空态（无记录）：居中 py-16 text-sm text-white/40「还没有面试记录」+ btn-big「去开始一场面试」
  </MenuItem>

  <MenuItem large「成长曲线」 icon="↗" v-if="records.length >= 2">
    <LineChart :data="..." />   高度 240px
  </MenuItem>
</PageShell>
```

- 「查看报告」→ `router.push("/report/" + r.id)`
- 「删除」→ `await confirmDialog("删除这条记录？","删除后不可恢复。")` → 从 `store.records` 移除 + `persist()`
- 数据来源：`store.records`（`InterviewRecord[]`，字段查 `data/mock.ts`）

## 2. ReportView（面试报告，`/report/:id`）

重写现有 `views/ReportView.vue`。

```
<PageShell title="面试报告">
  <MenuItem large「总分」 icon="◎">
    左右两栏 flex gap-8：
      左：总分环（用 CSS conic-gradient 或 ECharts 饼图）直径 140px，中心 82 大字 + 「总分」
      右：RadarChart 五维（技术正确性/知识深度/逻辑严谨/岗位匹配/表达呈现），高 260px
  </MenuItem>

  <MenuItem large「逐题复盘」 icon="≡">
    每题一张卡（rounded-lg border border-white/10 bg-white/[0.04] p-3 mb-3）：
      题号徽标 + 题目（text-sm text-white）+ 右下 得分（text-[#79d9ff] font-bold）
      下方两行：你的回答（text-xs text-white/60，line-clamp-2）
                点评（text-xs text-white/80，左 2px 主题色竖条）
  </MenuItem>

  <MenuItem small「亮点」 icon="✓">  列表 3 条，每条前置 emerald 圆点
  <MenuItem small「不足」 icon="!" >  列表 3 条，每条前置 red 圆点
  <MenuItem large「改进建议」 icon="✎"> 有序列表，每条：建议 + 练习方向
  <MenuItem large>  btn-big「打印 / 导出 PDF」→ window.print()
</PageShell>
```

- 数据：`store.records.find(r => String(r.id) === route.params.id)`，取不到则显示空态「报告不存在」+ 返回
- 报告页要能打印：在 `<style>` 里加 `@media print { 背景改白、文字改黑 }`

## 3. ScriptEditorView（剧本编辑器，`/script-editor`）

重写现有 `views/ScriptEditorView.vue`，保留其**数据操作逻辑**（步骤增删改/上下移/导入导出），只换皮。

```
<PageShell title="剧本编辑器">
  <template #actions>
    三个小胶囊钮：[保存][导入][导出]，最右 [试玩] emerald
  </template>

  顶部一行：剧本名（glass-input，可编辑）+ 剧本说明
  Tab 切换（两个 chip，样式同日志页级别 chips）：
    [步骤列表] [JSON 预览]

  步骤列表：
    每步一张卡（rounded-lg border border-white/10 bg-white/[0.04] p-3 mb-3）：
      左：序号圆标 + 类型徽标（intro 蓝/basic 青/deep 紫/close 灰）
      中：标题（可编辑 input）+ 正文（textarea，2 行高）
      右：↑ ↓ ✕ 三个 34px 图标钮（✕ 用 red hover）
    底部 + 「添加步骤」虚线卡（border-2 border-dashed border-white/15）

  JSON 预览：
    深色代码块（bg-black/35 rounded-lg p-3 font-mono text-xs leading-6）
    顶部右侧「复制」小钮
</PageShell>
```

- 步骤编辑弹窗可选，直接在卡内编辑即可
- 「保存」→ `saveScripts()`；「导入」→ 隐藏 file input 读 JSON；「导出」→ Blob 下载
- 「试玩」→ `notify("剧本试玩将在对话主线接入后开放")`
- 数据：`store.scripts`，当前编辑项存在本地 ref

## 4. WorkshopView（创意工坊，`/workshop`）

重写现有 `views/WorkshopView.vue`，保留筛选逻辑。

```
<PageShell title="创意工坊">
  工具栏（flex justify-between）：
    左：分类 chips [全部][Java][前端][压力面][行为面]
    右：排序 toggle（bg-white/5 容器内两个小钮 [最热][最新]，激活 bg-white/10）
  chips 选中：bg-[#79d9ff]/15 border-[#79d9ff] text-[#79d9ff]
  chips 未选：bg-white/6 border-white/10 text-white/50

  卡片网格 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4：
    每张（rounded-xl border border-white/12 bg-white/[0.05] p-4，hover 上浮 + 主题色描边）：
      标题（text-base font-bold）+ 右上「已装」胶囊（可选）
      简介（text-xs text-white/50 line-clamp-2）
      标签行（小胶囊）
      底行：作者 + 题数 + [查看] 蓝钮 [安装] emerald 钮
  点「查看」→ LcModal 弹详情：标题 + 长简介 + 步骤数 + 适用岗位 + [安装]
  数据：`workshopItems`（`data/mock.ts`）
</PageShell>
```

## 5. 首页二级菜单（02）

改 `views/HomeView.vue`：**只加菜单状态，不动图层结构、视差、样式**。

要求：

1. 新增 `const menu = ref<"main" | "start">("main")` 与 `slideName = ref("slide-left")`
2. 菜单区（现有 `StartItem` 列表）按状态渲染两组：
   - `main`：开始面试 / 继续面试 / 面试记录 / 面试设置 / 剧本编辑器 / 创意工坊
   - `start`：自由面试 / 剧本面试（禁用）/ 返回
3. 「开始面试」→ `slideName = "slide-left"; menu = "start"`
4. 「返回」→ `slideName = "slide-right"; menu = "main"`
5. 用 `<Transition :name="slideName" mode="out-in">` 包住菜单组，CSS：

```css
.slide-left-enter-active, .slide-left-leave-active,
.slide-right-enter-active, .slide-right-leave-active { transition: transform 0.4s cubic-bezier(0.7,0,0.2,1), opacity 0.4s; }
.slide-left-enter-from { transform: translateX(120%); opacity: 0; }
.slide-left-leave-to { transform: translateX(-120%); opacity: 0; }
.slide-right-enter-from { transform: translateX(-120%); opacity: 0; }
.slide-right-leave-to { transform: translateX(120%); opacity: 0; }
```

6. 「自由面试」→ `router.push("/interview")`
7. 「剧本面试」→ `StartItem` 的 `disabled` 态（`opacity-50`，不可点），title 下方无额外文字
8. **保持现有 StartItem 的字号/间距/hover/字体不变**，只换文案与列表内容

## 验收

```bash
cd D:\cxdownload\javaee课设\frontend
npm run build
```

目检项：
- `/records`、`/report/1`、`/script-editor`、`/workshop` 都是深色玻璃页，有返回钮
- 四个页面没有任何后台风（无左侧 sidebar、无 `console-*` 类）
- 首页点「开始面试」能滑到二级菜单，「返回」能滑回
- 无内容时显示空态而不是白屏
- 删除/安装等操作有确认框或 toast

禁止：出现 `ConsoleShell` 组件引用。
