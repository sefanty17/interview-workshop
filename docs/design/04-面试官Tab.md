# 04 · 面试官 Tab（≈ LingChat 角色）

- **原型**：`settings/pages/SettingsCharacter.vue` + `ui/Menu/CharacterCard.vue`
- **复用度**：100%，只换名词与数据
- **数值规格**：规范 §5

## 区块排布（自上而下，与 LingChat 角色 Tab 一致）

```
MenuItem large「面试官列表（切换面试官会开始全新面试）」
┌─────────────┬─────────────┐
│ 人设卡 林工  │ 人设卡 周老师 │
│ 人设卡 陈总  │ 人设卡 顾博  │
│ 人设卡 …    │ ╶╶ 新建人设 ╴╴│ ← 虚线占位卡
└─────────────┴─────────────┘
        ← 1 / N →（超过 6 张才出现分页条）

MenuItem large「我的身份」（截图版本有，照做）
└─ 候选人卡：姓名 · 目标岗位 · 经验标签 + 简历摘要 + [编辑][删除] 白胶囊钮

MenuItem small「从压缩包导入人设」→ big 按钮
MenuItem small「刷新人设列表」  → big 按钮
（LingChat 的「打开人物文件夹」是桌面端能力，Web 无对应，砍）
```

## 人设卡（逐像素复刻 CharacterCard）

```
┌──────────────────────────────────────────┐
│ [头像96px]  陈总  PRESSURE INTERVIEW      │  ← 主名主题色大写 + 副名
│  圆形描边    压力面考官。追问不留情面……     │  ← 描述 3 行截断
│  ▬(色条)                     [详情][选择] │  ← 胶囊钮
└──────────────────────────────────────────┘
```

- 头像：96px 圆形、`border-2 border-indigo-400/50`、hover 图片 scale-110；
  无头像时用首字符占位（`bg-indigo-500/20` + 3xl 白字）
- 卡片：`rounded-2xl border-white/20 bg-white/10 p-4 backdrop-blur-xl`，
  hover 上浮 4px + 边框增亮 + indigo 光晕
- 按钮：详情=白玻璃；选择=indigo 实色，已选=emerald 实色「✓ 已选中」
- 左上角收藏星（hover 才显现）、右上角设置齿轮（hover 旋转 90°）——P1 做

## 数据映射（mock.ts personas）

| 卡字段 | 数据字段 |
| --- | --- |
| 头像/占位字 | `avatar`（"林"/"周"/"陈"/"顾"） |
| 主名 | `name`（林工/周老师/陈总/顾博） |
| 副名 | `title` 大写英文感（如 STRICT TECH · P7） |
| 描述 | `tone` |
| 选择按钮 | 点击 → `setPersona(id)`，已选态绑 `store.settings.personaId` |

## 详情弹窗（本轮做简化版）

遮罩 `bg-black/40 backdrop-blur-md`；弹体 `max-w-4xl rounded-3xl bg-slate-900/40 backdrop-blur-2xl`；
头部：头像 + 名 + 副标题；内容分节（基本信息/提问风格/开场白），节标题带左侧 4px 彩色竖条；
进场 `scale(0.95)+translateY(10px) → 1`，0.4s。
