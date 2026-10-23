# 14 · 日志 Tab（≈ LingChat 日志，1:1）

- **原型**：`settings/pages/SettingsLog.vue` + `LogConsole.vue`

## 区块

```
MenuItem large「日志」
└─ 深色终端式日志区（等宽字体、黑 35% 底、圆角 8px）
   级别筛选 chips（全部/信息/警告/错误）+ 清空 + 导出按钮
```

- P2 才接真实日志；本轮放占位（mock 几行）
- 横向可滚动表格时注意：手机端滑动切 Tab 手势会避开横向滚动区（外壳已处理）
