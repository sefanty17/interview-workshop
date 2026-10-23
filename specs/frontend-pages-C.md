# 施工规格 C：面试房间 + 人设卡换数据源 + 两处修正

工作目录：`D:\cxdownload\javaee课设\frontend`

## 铁律

1. 只改 `frontend/src`，不碰 `references/`
2. 只用现有组件与 Tailwind 内联类，不装依赖、不新增 CSS 文件（允许往 `styles/lingchat.css` 追加 keyframes）
3. 主题色唯一 `#79d9ff`
4. 最后 `npm run build` 零错误

## 前置：新数据源已就绪

`src/data/interviewers.ts` 已生成，导出：

```ts
export type InterviewerCode = "strict" | "friendly" | "pressure" | "expert";
export interface Interviewer { code, name, subtitle, cardTitle, cardInfo,
  displayRole, opening, followUp, speed, scale, offsetX, offsetY, bubbleTop, bubbleLeft, userAlias }
export const interviewers: Interviewer[];
export const EMOTIONS: readonly string[];          // 20 个情绪词
export function avatarUrl(code: string, emotion?: string): string;  // → /avatars/strict/正常.webp
export function findInterviewer(code: string): Interviewer;
```

立绘已就位：`public/avatars/{strict|friendly|pressure|expert}/` 各 21 张（中文情绪文件名 + 头像.webp）。

---

## 1. PersonaTab 换数据源

文件：`src/components/settings/PersonaTab.vue`

现状用的是 `data/mock.ts` 里的旧 `personas`（林工/周老师/陈总/顾博，纯文字头像）。改成新角色卡：

- 数据源改为 `interviewers`，不再 import `personas`
- 卡片头像：`<img :src="avatarUrl(p.code)" class="h-full w-full object-cover" />`，圆形 96px，`border-2 border-indigo-400/50`
- 卡片主标题：`p.displayRole`（严格技术面 / 温和 HR 面 / 压力面 / 技术专家面）
- 卡片副标题：`p.name`（风雪 / 钦灵 / 松子 / DeepSeek）
- 卡片描述：`p.cardInfo`（注意是 `/` 分隔的一行，用 `line-clamp-3`）
- 「选择」按钮逻辑不变：`setPersona(p.code)`；已选态 emerald
- 选中判定：`store.settings.personaId === p.code`

详情弹窗（保留现有三段结构，改内容 + 加一段）：

1. 基本信息：姓名 / 定位（displayRole）/ 卡片标题（cardTitle）/ 副标题（subtitle）
2. 提问风格：保留 `quote` 展示位，但改为显示 `cardInfo`
3. 开场白：`p.opening`
4. **新增「面试编排」**（只读，本轮不做编辑）：
   ```
   岗位：{当前 job.name}
   题量：{store.settings.questionCount} 题
   时长：{store.settings.timeLimit} 分钟
   追问深度：{store.settings.followUpDepth} 层
   [去编排] ← 小胶囊钮，router.push("/script-editor")
   ```
   标题竖条颜色用 `bg-emerald-500`（与前三段区分）

弹窗顶部头像同样换成 `avatarUrl(p.code)`。

## 2. InterviewRoomView 重写

文件：`src/views/InterviewRoomView.vue`。**整体重写视觉，保留全部数据逻辑**（`sessionQuestions` / `askQuestion` / `send` / `feedbackFor` / `finish` / 计时器 / `store.session` 读写）。

结构：

```
<div class="fixed inset-0 overflow-hidden text-white">
  ① 场景背景：background2.png，cover，上层 rgba(6,14,28,0.55) 压暗
  ② <AvatarStage :code="persona.code" :emotion="currentEmotion" />
  ③ 顶部行（absolute top-0 inset-x-0，px-6 py-4，flex justify-between，z-20）：
       左：[题目 2 / 5] · 12:34        ← text-sm text-white/85，题目数字 text-[#79d9ff]
       右：[结束面试] 玻璃胶囊按钮
  ④ 底部对话框（absolute bottom-0 inset-x-0，z-20，flex justify-center，pb-5）：
       <div class="w-[70%] max-w-5xl ...">   ← 窄屏 max-[1024px]:w-[92%]
         顶部 40px 渐变压暗带：bg-gradient-to-b from-[#000e27]/30 to-[#000e27]/60
         玻璃主体：rounded-2xl border border-white/15 bg-white/10 backdrop-blur-2xl p-4
           标题栏：人名（text-2xl font-bold）+ 岗位（text-xl text-[#6eb4ff]）
                   右侧：[追问中] 标签（text-[#ff77dd] font-bold）+ 三个 34px 图标钮（⚙ 🎤 ✕）
           正文区：min-h-[72px] max-h-[28vh] overflow-y-auto
                   当前 AI 台词逐字上浮显示（见下）
           输入行：glass-input flex-1 + 右侧 [发送] 主题色胶囊钮
</div>
```

### AvatarStage 组件（新建 `src/components/room/AvatarStage.vue`）

```vue
<template>
  <img
    :src="avatarUrl(code, emotion)"
    class="pointer-events-none absolute left-1/2 top-1/2 max-h-[86%] max-w-[52%] select-none object-contain"
    :style="{ transform: `translate(-50%, -50%) translate(${offsetX}px, ${offsetY}px) scale(${scale})` }"
    draggable="false"
  />
</template>
<script setup lang="ts">
import { avatarUrl } from "../../data/interviewers";
withDefaults(defineProps<{ code: string; emotion?: string; scale?: number; offsetX?: number; offsetY?: number }>(),
  { emotion: "正常", scale: 1, offsetX: 0, offsetY: 0 });
</script>
```

`currentEmotion`：本轮从对话消息里取——`messages` 里若有 `emotion` 字段就用最后一条，否则 `"正常"`。不接 LLM。

### 打字机

保留现有逐字逻辑，把渲染改成：

```html
<span v-for="(ch, i) in chars" :key="i" class="lc-char" :style="{ animationDelay: i * 28 + 'ms' }">{{ ch }}</span>
```

往 `styles/lingchat.css` 追加（全局，scoped 无效）：

```css
.lc-char { display: inline-block; white-space: pre; animation: lc-char-rise 0.28s cubic-bezier(0.16, 1, 0.3, 1) both; }
@keyframes lc-char-rise { from { opacity: 0; transform: translateY(0.35em); } to { opacity: 1; transform: none; } }
```

## 3. 两处修正

**a. RecordsView 卡片等高**：卡片网格容器加 `auto-rows-fr`，每张卡加 `h-full flex flex-col`，按钮行用 `mt-auto` 贴底。现在左右两张卡高度不一致。

**b. ReportView 总分环文字居中**：圆心数字与「总分」二字必须严格居中（`absolute inset-0 flex flex-col items-center justify-center`），当前偏左上。

## 验收

```bash
cd D:\cxdownload\javaee课设\frontend
npm run build
```

目检（dev server 在 127.0.0.1:5173）：
- 设置 → 面试官：卡片显示真实立绘头像 + 新名字，详情弹窗有「面试编排」段
- 首页 → 开始面试 → 自由面试：进入房间后**能看到立绘**（风雪等），底部是玻璃对话框，顶部有「题目 N/5 · mm:ss」
- 立绘不遮挡对话框、不溢出屏幕
- 面试记录页两张卡等高
- 报告页总分数字在圆环正中

禁止：出现 `.room` / `.console-*` / `ConsoleShell` 等旧类。
