# 05 · 面试规则 Tab（新增，第 12 位）

- **原型**：无。LingChat 没有面试规则——这是课设业务，**自行设计**，
  但只用 SettingItem 行式控件 + glass 控件拼装
- **位置**：导航条最后一个 Tab

## 区块排布

```
MenuItem large「岗位与知识点」
├─ 行：目标岗位 → select（glass 下拉，option 深色兜底 #16202c）
└─ 行：知识点多选 → chips（工坊分类 chips 同款：
     默认 bg-white/6 text-white/50，选中 主题色边+15% 底+主题色字）

MenuItem small「题量」     滑块 3–10，右侧数字
MenuItem small「时长」     滑块 10–30 分钟
MenuItem small「追问深度」 滑块 0–4 层
MenuItem small「语音开关」 Toggle + 说明文字
```

## 控件规格

- 行式控件 = SettingItem：左标签（白）+ 右控件，行间 `py-2`
- 字段说明用 `text-xs font-medium text-white/60`
- 滑块：主题色圆点、灰白轨道（参照通用 Tab 截图里的滑块样式）
- 所有修改即改即存（`updateSettings` → localStorage），不需要保存按钮
