# V3b · 自由对话主界面骨架对齐原版 MainChat

> 主线写规格，子 agent 执行。只改**模板布局与层级**，不动 script 逻辑、不改文案。

## 0. 蓝本

`references/lingchat/src/components/views/MainChat.vue`（237 行，本批唯一骨架真相源）。

原版 DOM 顺序与关键样式（逐字摘录）：

```html
<div class="main-box">
  <FreeModeTools />
  <div class="main-status-panel">
    <div class="main-status-row"><StatusInfoBar /></div>
    <FullAccessWarning />
  </div>
  <GameBackground />
  <GameRolesStage ref="gameAvatarRef" @audio-ended @audio-started />
  <GameDialog ref="gameDialogRef" @player-continued />
  <div id="menu-panel">
    <ToolActivityStatus v-if="!(gameStore.runningScript && gameStore.runningScript.isRunning)" />
    <Button type="nav" icon="play"  @click="switchAutoMode" :active="uiStore.autoMode" v-show="uiStore.showSettings !== true" />
    <Button v-if="!isMobile()" type="nav" icon="character" @click="goToPetMode" v-show="uiStore.showSettings !== true" />
    <Button type="nav" icon="text" @click="openSettings" v-show="uiStore.showSettings !== true" />
  </div>
  <GameExtraUI />
  <ImageSourcePicker />
  <LoadingTransition v-if="showLoading" @complete />
</div>
```

```css
.main-box {
  position: absolute; height: 100%; width: 100%;
  display: flex; flex-direction: column;
  justify-content: flex-end; align-items: center; overflow: hidden;
}
.main-status-panel {
  position: fixed; top: calc(15px + var(--safe-area-inset-top)); left: 50%;
  z-index: 1001; display: flex; width: min(44rem, calc(100vw - 2rem));
  transform: translateX(-50%); flex-direction: column; align-items: center;
  gap: 8px; pointer-events: none;
}
.main-status-row { display: flex; min-height: 48px; align-items: center; justify-content: center; }
#menu-panel { display: flex; position: fixed; top: calc(15px + var(--safe-area-inset-top)); right: 20px; z-index: 1000; }
```

## 1. 现状（本项目 `frontend/src/views/InterviewRoomView.vue`，759 行）

模板 ①-⑥ 区：
- 外层 `class="relative h-dvh w-full overflow-hidden text-white"`
- ① `GameBackground`
- ② `GameRolesStage`
- ③ 右上角工具条 `absolute top-[15px] right-5 z-30`：进度/计时 + V3a 新加的齿轮按钮 + 「结束面试」`nav-btn`
- ④ 底部 `GameDialog`
- ⑤ `GameExtraUI`
- ⑤b `FreeModeTools` + 三个 fixed 容器分别摆 `StatusInfoBar`（top-1 居中）/ `ToolActivityStatus`（top-2 right-44）/ `FullAccessWarning`（top-12 居中）

## 2. 改动（逐条，做完即止）

1. **外层容器换成原版 `.main-box`**：把根 div 的 class 改为 `main-box`，
   并在文件 `<style>` 段**逐字**放入原版 `.main-box` / `.main-status-panel` /
   `.main-status-row` / `#menu-panel` 四条样式。
   （`text-white` 若因此丢失导致文字变色，把 `text-white` 保留在 class 里。）
2. **调整 DOM 顺序为原版顺序**：
   `FreeModeTools` → `.main-status-panel`（StatusInfoBar 在 `.main-status-row` 内 +
   FullAccessWarning 紧随）→ `GameBackground` → `GameRolesStage` → `GameDialog`
   → `#menu-panel` → `GameExtraUI`。
3. **`.main-status-panel` 收编三个游离 fixed 容器**：
   把原先各自 fixed 的 `StatusInfoBar` / `FullAccessWarning` 两个容器删掉，
   改成放进 `.main-status-panel`（`StatusInfoBar` 进 `.main-status-row`）。
   `ToolActivityStatus` 不进 status-panel，改放 `#menu-panel`（见下）。
4. **`#menu-panel` 落地**：新建 `<div id="menu-panel" ref="menuPanelRef">`，
   内部顺序：`ToolActivityStatus`（保留现有 v-if 判据）
   → 本项目已有的「进度/计时/结束面试」那排（原样搬进来，不改内部）
   → 齿轮按钮（V3a 加的，原样保留）。
   `#menu-panel` 用原版 fixed 定位（top 15 + safe-area，right 20px，z-1000）。
5. **不要动 script**：所有逻辑、computed、watch、事件处理一字不改；
   只重排模板。若某个模板节点上的事件绑定（如 `@send` / `@history` /
   `@choose` / `@finish`）被移动，必须原样带上。
6. **不搬桌宠**：原版第三个 Button 是桌宠入口，本项目不搬（按
   `docs/00-项目方针.md`：不搬桌面端运行时）。`ToolActivityStatus`
   若本项目不存在，就跳过这个组件，不要自己造。

## 3. 约束

1. 不许跑任何 git 命令（含只读）。
2. 不许改 `backend/`（本批是前端线）、`docs/`、`.workbuddy/`、`specs/`（含本规格）。
3. 不许改 `src/components/game/` 下的组件（A1 已验收，别碰）。
4. 不许加 `any` / `@ts-ignore` / 删类型。
5. 不许改任何中文文案。
6. 不许引入新依赖。

## 4. 验收

### 4.1 构建
`cd frontend && npx vue-tsc --noEmit` → 0 error；`npm run build` → 成功。

### 4.2 真机（playwright-core + 系统 Chrome，1440×900）
1. `/` → 开始面试 → 自由面试，进房间（URL `/interview`）。
2. 断言：根容器 class 含 `main-box`；`.main-status-panel` 存在且可见；
   `#menu-panel` 存在且可见。
3. 断言：`.main-status-panel` 内含有 StatusInfoBar 的内容；
   `#menu-panel` 内含齿轮按钮（`button[title="设置"]`）与「结束面试」。
4. 断言：控制台 0 报错；立绘仍然在动（animationName=breathing）——
   确认没把 A1 的成果弄坏。
5. 截图：`screenshots/v3b/01-room-skeleton.png`。

## 5. 报告

改动文件清单（含行数）、构建原始输出末尾、真机 5 步逐条结果、截图路径、
Root 容器 class 改动前后的对照、全部偏离规格之处。诚实报告。
