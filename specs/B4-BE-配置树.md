# 规格 B4-BE：设置后端化（配置树 1:1）

## 为什么做这件事

原版设置不是前端自己存，是**后端持有配置树**：

```
crates/ling-chat-main/src/config/     1947 行
  keys.rs       110 个 key 常量（"llm.temperature" 这种点号命名）
  tree.rs       821 行，把配置组装成带分组/类型/范围/默认值的树
  app_config.rs MIN_/MAX_ 范围常量
api/settings.rs 407 行
  get_settings_tree()  → ConfigTree
  save_settings(values: BTreeMap<String,String>) → 逐项校验
存储：tauri_plugin_store → settings.json（Rust 侧读写）
```

前端注释原话（`stores/modules/settings/asr.ts:64`）：**后端 settings.json 是权威**。
`:131`：api_key 唯一真相在后端，不从 localStorage 持久化，避免明文 key 双副本。

我们现在的做法（前端 localStorage 一把梭、后端零感知）是反的，必须改回来。

---

## 一、后端：`config` 包

### 1.1 `ConfigKey.java`

常量表，命名照原版（点号）。**第一批只迁和对话/显示相关的 18 个**，不要贪多：

| key | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `llm.model` | string | `deepseek-chat` | — | 模型名 |
| `llm.temperature` | double | 1.0 | 0.0~2.0 | 温度 |
| `llm.top_p` | double | 1.0 | 0.0~1.0 | top_p |
| `llm.timeout_secs` | int | 60 | 5~300 | 请求超时（原版 `MIN_LLM_TIMEOUT_SECS`/`MAX_LLM_TIMEOUT_SECS`） |
| `llm.max_tokens` | int | 800 | 64~8192 | 本项目补充（原版无此 key，标「本项目扩展」） |
| `llm.chat_provider_id` | string | `deepseek` | — | 对话用哪个 provider |
| `llm.enable_thinking` | bool | false | — | 思考链（后端只存值，暂不使用） |
| `features.enable_time_sense` | bool | false | — | 时间感知提示 |
| `features.enable_emotion_classifier` | bool | false | — | 情绪分类器（我们用别名表） |
| `chat.reply_count` | int | 3 | 1~6 | 每次回复几句（本项目扩展） |
| `chat.merge_threshold` | int | 55 | 0~200 | 台词合并阈值（0=关闭） |
| `chat.merge_delay_ms` | int | 200 | 0~2000 | 合并续打延迟 |
| `chat.text_speed` | int | 80 | 20~200 | 打字速度（原版 `textSpeed` 语义） |
| `chat.auto_advance_ms` | int | 1000 | 0~5000 | 自动推进延迟 |
| `chat.inline_action` | bool | false | — | 内联动作文本 |
| `display.font_family` | string | `""` | — | 界面字体 |
| `display.font_size` | int | 16 | 12~24 | 字号 |
| `audio.volume_character` | int | 80 | 0~100 | 角色音量（先存值，TTS 未接） |

**范围值必须去 `app_config.rs` 里核对**，能对上原版常量的就用原版，对不上的（本项目扩展）在注释里标清楚。

### 1.2 `ConfigSetting.java`

```
key / group / label / type(STRING|INT|DOUBLE|BOOL|ENUM) / defaultValue
min / max / options（ENUM 用）/ description / extension(boolean，是否本项目扩展)
```

`group` 取值：`llm` / `features` / `chat` / `display` / `audio`。

### 1.3 `ConfigTree.java`

```json
{
  "groups": [
    { "id": "chat", "label": "对话",
      "settings": [
        { "key": "chat.reply_count", "label": "回复台词数量", "type": "INT",
          "value": "3", "defaultValue": "3", "min": 1, "max": 6, "extension": true }
      ] }
  ]
}
```

`value` 一律是 **String**（原版 `BTreeMap<String,String>` 就是这么干的），前端按 `type` 转。

### 1.4 `ConfigService.java`

- `ConfigTree buildTree()`：遍历注册表，读 DB 覆盖值，没覆盖就用默认值
- `void saveAll(Map<String,String> values)`：
  - 逐个 key 校验：key 必须在注册表里、值能按 `type` 解析、在 `min..max` 内、ENUM 在 `options` 里
  - 校验失败抛 `IllegalArgumentException`，message 照原版风格：「LLM 请求空闲超时必须在 5–300 秒之间」
  - 全通过后写 `user_settings`（存在则更新，不存在则插入）
- `String get(String key)` / `int getInt(key)` / `double getDouble(key)` / `boolean getBool(key)`

### 1.5 `user_settings` 表

```sql
CREATE TABLE IF NOT EXISTS user_settings (
  config_key   VARCHAR(64) PRIMARY KEY,
  config_value VARCHAR(2048) NOT NULL,
  updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

加到 `schema-h2.sql`（用 `IF NOT EXISTS`，别加 DROP）。

---

## 二、接口

```
GET  /api/settings/tree      → ConfigTree（含当前值）
PUT  /api/settings           ← {"llm.temperature":"1.2","chat.reply_count":"4"}
                               校验失败返回 400 + {"error":"温度必须在 0.0–2.0 之间"}
```

---

## 三、对话链路接配置

`ChatService` 调 LLM 时，参数从 `ConfigService` 读，不再写死 `LlmProperties` 的 yml 值：

```
temperature  ← llm.temperature
max_tokens   ← llm.max_tokens
model        ← llm.model
timeout      ← llm.timeout_secs
```

`PromptAssembler` 在正文要求段之后，如果 `chat.reply_count` ≠ 默认值，追加一句：
`本次回复请输出 {n} 段台词。`（**这是本项目扩展，注释标清楚**；默认值 3 时不追加，避免影响已验证的 prompt）

`SentenceSplitter` 的合并阈值读 `chat.merge_threshold`（0 表示不合并）。

**注意**：这些读取都走 `ConfigService`，**不要在 ChatService 里直接读 yml**。

---

## 四、前端：先打通一页，不要一次改 10 页

### 4.1 `frontend/src/api/settings.ts`

```ts
export interface ConfigSetting { key: string; label: string; type: string;
  value: string; defaultValue: string; min?: number; max?: number;
  options?: string[]; description?: string; extension?: boolean }
export interface ConfigGroup { id: string; label: string; settings: ConfigSetting[] }

export async function fetchConfigTree(): Promise<{ groups: ConfigGroup[] }>
export async function saveConfig(values: Record<string, string>): Promise<void>
```

### 4.2 只改「通用」页（`SettingsText.vue`）

把现在硬编码的表单，改成**按树渲染**：
- 分组 → `MenuItem`
- `type === "INT" | "DOUBLE"` → 滑块（带数值显示，范围取 `min/max`）
- `BOOL` → 开关
- `STRING` → 输入框
- `ENUM` → 下拉

改动值 → 调 `saveConfig` → 成功后显示 toast。**失败要显示后端返回的 error。**

其他 9 个页面这一批**不动**（下一批逐页迁），但要保证它们仍能正常工作（继续读 localStorage）。

### 4.3 双写过渡

通用页保存时，除了调后端，仍写一份 localStorage 作为离线缓存；进入页面时：
- 后端能连上 → 用树里的值
- 连不上 → 用 localStorage 的值，并显示「离线，改动不会同步」提示

---

## 五、验收

1. `bash scripts/mvn.sh test` → 全绿（新增：校验范围、未知 key、ENUM 非法值、默认值回落）
2. 重启后端，`GET /api/settings/tree` 返回 5 个分组、18 项，`value` 都是默认值
3. `PUT /api/settings` 改 `llm.temperature=1.5` → 200；再 GET 树，该项 value = `1.5`
4. `PUT` 一个越界值（`llm.temperature=9`）→ 400，错误文案含范围
5. `PUT` 一个不存在的 key（`foo.bar=1`）→ 400
6. 重启后端，`llm.temperature` 仍是 `1.5`（落库生效）
7. **真实对话验证**：把 `chat.reply_count` 设成 1，发一条消息，SSE 里 `ai:reply` 只有 1 条；
   设回 3，再发，条数恢复
8. 前端：通用页改成树渲染，拖温度滑块 → 刷新页面值还在（从后端读回来的）
9. 回报：文件清单+行数、18 个 key 的实际范围核对表（原版 vs 我们）、上面 7 条的实测输出

---

## 六、禁区

- 不许一次改 10 个设置页（只改通用页）
- 不许把 `user_settings` 做成「一个大 JSON 字段」（原版是扁平 key-value）
- 不许在 `ChatService` 里绕过 `ConfigService` 直接读 yml
- 不许删 `user_profile` / 其它表
- 不许动 `PromptAssembler` 的 7 段拼装顺序（追加 reply_count 那句只能在正文要求段之后）
