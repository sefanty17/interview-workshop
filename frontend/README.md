# 面试工坊 · 前端 Demo

第 16 题「AI 模拟面试与能力提升软件」的前端初版。只做页面、路由、交互与 mock 数据，**不接后端、不调用真实模型/ASR**。

## 运行

```bash
npm install
npm run dev
# http://127.0.0.1:5173
```

构建：

```bash
npm run build
npm run preview
```

## 页面

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | 首页 | 开始面试、继续面试、面试记录、面试设置、剧本编辑器、创意工坊 |
| `/interview` | 面试房间 | 题目卡、文字对话、计时、mock 追问、结束生成报告 |
| `/settings` | 面试设置 | 面试官人设 / 知识点注入 / 面试规则 / 模型与语音 |
| `/records` | 面试记录 | 历史列表、成长曲线、平均分 |
| `/report/:id` | 面试报告 | 总分、五维雷达、逐题复盘、改进建议、练习计划 |
| `/script-editor` | 剧本编辑器 | 步骤编排、JSON 预览、导入导出 |
| `/workshop` | 创意工坊 | 剧本卡片、搜索、标签筛选（占位） |

## 设计来源

- 首页壳层参考 LingChat 的 `MainMenu` / `StartPage` / `StartList` / `StartItem` 布局与交互节奏；
- Tailwind 工具类已改写为语义 CSS（`src/styles/menu.css`）；
- 去掉了 Tauri、游戏 store、Live2D、BGM 等依赖；
- 二次元人物替换为 CSS 全息面试官，避免与面试场景冲突。

## 数据与状态

- 全部 mock 数据在 `src/data/mock.ts`；
- 设置、剧本、记录、进行中的会话保存在 `localStorage`（key: `interview-lab-v1`）；
- 「继续面试」依赖本地保存的 session；
- 报告页支持 `window.print()` 导出/打印。

## 目录

```
src/
  components/menu/     首页菜单与全息人物
  components/ui/       ConsoleShell、雷达图、折线图
  views/               7 个页面
  data/mock.ts         人设、岗位、题目、记录、剧本
  store/appStore.ts    localStorage 状态
  styles/              tokens / base / menu / console / views
```

## 未实现（留给后端接入）

- Spring Boot API、登录、题库管理；
- LLM 面试官、追问策略、真实评分；
- ASR/TTS；
- 多用户/教师端；
- 平台（钉钉/超星）嵌入。
