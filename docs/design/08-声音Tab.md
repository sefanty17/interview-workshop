# 08 · 声音 Tab（≈ LingChat 声音，1:1）

- **原型**：`settings/pages/SettingsSound.vue`

## 区块（自上而下）

| LingChat 区块 | 面试工坊处理 |
| --- | --- |
| 音频设备 | 保留：输出/输入设备 select（Web 用 enumerateDevices，无权限时灰显） |
| 角色语音音量 | 「面试官语音音量」滑块 |
| 气泡音效音量 | 「操作音效音量」滑块 |
| 背景乐音量 | 保留 |
| 成就音效音量 | 保留 |
| 环境音音量 | 保留 |
| 试听 | 保留：每行右侧 btn-icon-primary 试听钮 |
| BGM | 保留：开关 + 曲目 select |
| 环境音 | 保留：开关 + 类型 select |
| 语音合成（TTS） | 保留：开关 + 语速滑块 + 音色 select（MVP 用浏览器 speechSynthesis） |
| 语音识别（ASR） | 保留：开关 + 引擎说明（云 ASR，P1 接入） |
