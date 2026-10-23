# S-1a · 配置树改两层 + type 契约改小写

> 基线 `7a8849b`。目标：把配置树结构改成原版 `Category → Subcategory → settings` 两层，`type` 改成原版三个小写值。
> **纯契约改动，不动业务取值、不删不增 key。** 前端只需跟着改类型与模板。

---

## 一、原版事实（照这个对齐）

```rust
// config/types.rs:8-30
pub struct ConfigSetting { key: String, value: String, description: String,
                           setting_type: String }          // 只有 4 字段
pub struct Subcategory { description: String, settings: Vec<ConfigSetting> }
pub struct Category    { subcategories: BTreeMap<String, Subcategory> }
pub type ConfigTree    = BTreeMap<String, Category>;       // 两层
```

`setting_type` 只有 3 个值，**全小写**：`bool`(26) / `number`(3) / `text`(33)。
无 `ENUM`、无 `options`、无 `min/max` 字段（范围写在 `description` 文案 + 后端硬编码校验）。

---

## 二、后端改动

### B1 · `config/ConfigTree.java` 改成两层

```java
public record ConfigTree(List<Category> categories) {
    public record Category(String id, String label, List<Subcategory> subcategories) {}
    public record Subcategory(String id, String label, List<ConfigSetting> settings) {}
}
```

- 删掉现有的 `ConfigGroup` record
- `labels` 用原版中文名（见 B3）
- **不允许**保留 `groups` 字段做兼容；前后端一次改完

### B2 · `config/ConfigSetting.java` 改 type 常量

- 删 `TYPE_STRING` / `TYPE_INT` / `TYPE_DOUBLE` / `TYPE_BOOL` / `TYPE_ENUM` 五个常量
- 改为三个：`TYPE_TEXT = "text"`、`TYPE_NUMBER = "number"`、`TYPE_BOOL = "bool"`
- `type` 字段原样保留（String）
- `min` / `max` / `options` / `defaultValue` / `extension` **字段保留**（本项目扩展，不影响原版结构对齐）
- `ConfigService` 里 18 处 `TYPE_XXX` 按语义替换：
  - 原 `TYPE_STRING` / `TYPE_ENUM` → `TYPE_TEXT`
  - 原 `TYPE_INT` / `TYPE_DOUBLE` → `TYPE_NUMBER`
  - 原 `TYPE_BOOL` 不变名
- `checkRange` 的分支跟着改（原来按 INT/DOUBLE 区分整数/小数，现在按 `number` 一种处理，用 `min/max` 的字段值是否为整数来判断是否允许小数——沿用现有 `requireLong`/`requireDouble` 的实现即可，不要重写校验逻辑）

### B3 · `config/ConfigService.java` 分类映射

树的分类/子类**只建真实有内容的**，不建空壳。映射表（照原版分类名）：

| Category id | label | Subcategory id | label | 放入的 key |
|---|---|---|---|---|
| `llm` | LLM 配置 | `advanced` | 高级选项 | `llm.model` `llm.temperature` `llm.top_p` `llm.timeout_secs` `llm.max_tokens` `llm.chat_provider_id` `llm.enable_thinking` |
| `features` | 功能设置 | `chat-enhance` | 对话增强 | `features.enable_time_sense` `features.enable_emotion_classifier` `chat.inline_action` |
| `features` | 功能设置 | `interface` | 界面与显示 | `display.font_family` `display.font_size` |
| `features` | 功能设置 | `chat-rhythm` | 对话节奏（本项目扩展） | `chat.reply_count` `chat.merge_threshold` `chat.merge_delay_ms` `chat.text_speed` `chat.auto_advance_ms` |
| `features` | 功能设置 | `audio` | 音频 | `audio.volume_character` |

**说明**：原版 8 个顶层分类里，翻译配置 / TTS 配置 / 创意工坊 / 日志配置 / 主动对话配置 / 好感度 **本轮不建**——对应模块未迁，建空壳等于造假。

### B4 · `api/SettingsController.java`

`GET /tree` 返回结构随之变化，无需改代码（序列化跟着 record 走）。`PUT ""` 不动。

---

## 三、前端改动

### F1 · `frontend/src/api/settings.ts`

类型改成对应两层：

```ts
export interface ConfigSetting { key: string; group: string; label: string; type: "text" | "number" | "bool";
  value: string; defaultValue: string; min?: number; max?: number;
  options?: string[]; description: string; extension: boolean }
export interface Subcategory { id: string; label: string; settings: ConfigSetting[] }
export interface Category { id: string; label: string; subcategories: Subcategory[] }
export interface ConfigTree { categories: Category[] }
```

（`type` 用字面量联合，让 vue-tsc 帮你抓漏改的分支）

### F2 · `components/settings/pages/SettingsText.vue`

只改**数据结构与模板层级**，不动样式类、不动保存逻辑：

- `groups` → `categories`
- `applyTree(tree)` 里改成遍历 `tree.categories` → `category.subcategories` → `subcategory.settings`
- 模板外层 `v-for="category in categories"`，内层 `v-for="sub in category.subcategories"`，最内 `v-for="setting in sub.settings"`
- `commit` / 缓存（`interview-lab:config`）逻辑保持不变
- **CSS / class 一个都不许动**

### F3 · `components/settings/pages/SettingsAdvanceOther.vue`

**本批不动**（它的静态 `configData` 是 S-1b 才处理）。如果因为 type 改动编译报错，只做最小修补让它编过，不改结构。

---

## 四、禁区

- 不删任何 key、不增任何 key（`audio.volume_character` 等留到 S-2）
- 不改任何 `value` / `defaultValue`
- 不改校验规则的行为（只改类型分支的判定方式）
- 不改任何 .vue 的 class 与 scoped CSS
- 不跑 git 写命令；`mvn` 只用 `scripts/mvn.sh`，且必须在 `backend/` 目录下执行

---

## 五、验收

```bash
cd backend && ../scripts/mvn.sh test          # 134 个 @Test 全绿
cd frontend && npx vue-tsc --noEmit           # 0 error
```

起服务后：

1. `curl -s --noproxy 127.0.0.1 http://127.0.0.1:8080/api/settings/tree`
   - 顶层是 `{"categories":[...]}`，不是 `{"groups":[...]}`
   - 每个 category 有 `subcategories`，每个 subcategory 有 `settings`
   - 所有 `type` 值 ∈ {`text`,`number`,`bool`}，**没有大写、没有 ENUM**
   - `categories` 数量 = 6（llm 1 个 + features 5 个）
2. `PUT /api/settings` 提交一个非法值（如 `llm.timeout_secs` = `1`）→ 仍返回 400（校验未被改坏）
3. 前端 `/settings` 打开「文本」页 → 能看到两层分组的设置项，改一个数字能保存成功（刷新后值还在）

把 curl 输出（截前 80 行）与前端截图/关键 DOM 断言贴进交付说明。
