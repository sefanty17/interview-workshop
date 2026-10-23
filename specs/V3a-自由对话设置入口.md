# V3a · 自由对话主界面：设置入口

> 主线写规格，子 agent 执行。本批**只加一个入口**，不动结构、不动逻辑。

## 0. 诉求来源

智豪反馈：「自由对话左上角是可以进入设置的，我看你也没有」。

原版位置：`references/lingchat/src/components/views/MainChat.vue` 里的
`<div id="menu-panel">`，样式是

```css
#menu-panel {
  display: flex;
  position: fixed;
  top: calc(15px + var(--safe-area-inset-top));
  right: 20px;
  z-index: 1000;
}
```

里面三个 `Button type="nav"`：play（自动）/ character（桌宠）/ text（菜单 → `openSettings`）。
本项目是路由式设置（`/settings`），所以入口做成跳转路由即可，不做浮层面板。

## 1. 现状

`frontend/src/views/InterviewRoomView.vue`：
- 第 98 行已 `import { useRoute, useRouter } from "vue-router";`，第 143 行已 `const router = useRouter();`
- 模板 ③ 区（`absolute top-[15px] right-5 z-30`）是右上角工具条：进度/计时 + `<button class="nav-btn" @click="endNow">结束面试</button>`
- `components/tools/FreeModeTools.vue` 已挂在左上角（场景 + 印象面板）

## 2. 改动

### 2.1 `views/InterviewRoomView.vue`
在右上角工具条里，「结束面试」按钮**之前**新增一个设置按钮：

```html
<button class="nav-btn" @click="openSettings" title="设置">
  <Icon :icon="'settings'" :size="18" />
</button>
```

- 图标组件：本项目 `components/base/widget/Icon.vue`（先确认它支持的名字，挑一个"设置/齿轮"语义的 icon 名；若没有齿轮，用最接近的现有图标，并在报告里写明用了哪个）。
- `openSettings` 函数：`router.push("/settings")`。
- 样式沿用现有 `.nav-btn`，不新造类、不改 `.nav-btn` 本身。
- 别动 `endNow` 按钮，别动进度/计时。

### 2.2 位置说明（写进代码注释，别改结构）
原版这三个按钮在**右上角** `#menu-panel`；本项目右上角已被「进度/计时/结束面试」占用，
故把设置入口并进这一排，保持"右上角一组工具按钮"的原版观感。左上角留给
`FreeModeTools`（原版左上角是 `StatusInfoBar` 之外的工具区，本项目已有，不动）。

## 3. 约束

1. 不许跑任何 git 命令（含只读）。
2. 不许改 `backend/`、`docs/`、`.workbuddy/`、`specs/`（含本规格）。
3. 不许加 `any` / `@ts-ignore` / 删类型。
4. 不许改文案以外的东西：只加一个按钮与一个函数。
5. 不许动 `FreeModeTools.vue`。

## 4. 验收

### 4.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 4.2 真机（playwright-core + 系统 Chrome，1440×900）
1. `/` → 开始面试 → 自由面试，进房间（URL `/interview`）。
2. 断言：页面存在设置按钮（`title="设置"` 或匹配到的按钮元素）。
3. 点击它 → 断言 URL 变成 `http://127.0.0.1:5173/settings`。
4. 断言控制台 0 报错。
5. 截图：`screenshots/v3a/01-room-settings-entry.png`（点击前）、
   `screenshots/v3a/02-after-settings-click.png`（点击后）。

## 5. 报告

改动文件清单（含行数）、构建原始输出末尾、真机 5 步逐条结果、截图路径、
你选了哪个 icon 名、全部偏离规格之处。诚实报告。
