# 规格 B5-BE：剧本 YAML 导入（解析 + 覆盖度报告）

## 目标

把 LingChat 原版剧本（`story_config.yaml` + `Chapters/**/*.yaml`）导入我们的库，
并且**在导入时就告诉你哪些能跑、哪些会出问题**，而不是"导入成功、播放不对"。

**这一批只做「解析 + 落库 + 报告」，不做「引擎补全」。** 报告里的黄/红项下一批处理。

---

## 一、原版数据结构（必须读源码确认）

```
references/lingchat/data/game_data/scripts/character/诺一钦灵/小狼的爱好/
  story_config.yaml
  Chapters/
    Intro/intro.yaml
    Intro/intro2.yaml
    final.yaml
    main.yaml
    main2.yaml
```

`story_config.yaml` 字段（真实样例，逐字对照）：

```yaml
script_name: 小狼的爱好
intro_chapter: Intro/intro
description: 了解一下钦灵的爱好吧！
recommand_start: 和钦灵聊天的时候
adventure:
  is_adventure: true
  bound_character_folder: "诺一钦灵"
  order: 1
  trigger:
    mode: "manual"
script_settings:
  user_name: ""
```

章节 yaml：

```yaml
name: 5 家
events:
  - type: modify_character
    action: hide_character
    character: MAIN
    duration: 1.5
  - type: narration
    text: |
      很快，钦灵和%player%一起淋着小雨回到了家里...
  - type: chapter_end
    end_type: linear
    next_chapter: 'end'
```

事件类型全集（`script_engine/events/` 下 17 个文件，去掉 `mod.rs` / `known_effects.rs` / `achievement_event.rs`）：

```
ai_dialogue / input / narration / free_dialogue / modify_character / chapter_end
choices / dialogue / set_var / player / present_pic
background / background_effect / music / sound / ambient
```

---

## 二、后端实现

### 2.1 `ScriptYamlImporter.java`（`engine` 或新建 `importer` 包）

```java
public ImportResult importScript(byte[] zipBytes, String scriptCode)
public ImportResult importDirectory(Path dir)      // 开发用，扫本机 references
```

步骤：
1. 解压/读目录 → 找 `story_config.yaml` + `Chapters/**/*.yaml`
2. `story_config.yaml` → `interview_script` 行：
   - `script_code` = 目录名拼音或用户指定（**不能用中文**，用 `imported-1` 这种）
   - `script_name` ← `script_name`
   - `intro_chapter` ← `intro_chapter`（相对路径，去掉 `.yaml`，如 `Intro/intro`）
   - `description` / `recommand_start` ← 同名字段
   - `job_code` = `"java"`（默认，导入后可在编辑器改）
   - `interviewer_code` ← `adventure.bound_character_folder` 映射（见 §2.3）
   - `characters` ← `["MAIN"]`
3. 每个章节 yaml → `interview_chapter` 行：
   - `code` = 相对路径去掉 `.yaml`（`Intro/intro`）
   - `title` ← `name`
   - `events` ← YAML events 数组序列化成 JSON 字符串
   - `order_num` = 按文件名排序序号
4. **幂等**：同一 `script_code` 重复导入时，先删该剧本的 chapters，再重建（不删 session）

### 2.2 字段转换（每个都要有单测）

| 原版 | 转换 | 说明 |
|---|---|---|
| `%player%` | → `{user}` | 正文/旁白/prompt 里都替换 |
| `%char%` | → `{char}` | 同上 |
| `effect: Starfield` | → `starfield` | 粒子枚举转小写，映射表见 §2.4 |
| `effect: 星空` | → `starfield` | 中文名也要映射 |
| `imagePath: 夜晚.webp` | → `/backgrounds/night.webp` | 资源映射见 §2.4 |
| `next_chapter: 'end'` | 原样 `"end"` | 哨兵值，引擎已认 |
| `character: MAIN` | 原样 `MAIN` | 引擎 `roleId()` 已映射 |
| 事件 `type: choices` | 原样 | 注意不是 `choice`（类名≠注册键） |

### 2.3 角色映射

`adventure.bound_character_folder`（如 `诺一钦灵`）要映射到我们的 `interviewer.code`：

```
诺一钦灵 → friendly（钦灵）
其余未识别 → strict（风雪，默认面试官）
```

映射表写成常量，注释标明「本项目映射规则」。

### 2.4 资源映射表

```
夜晚.webp  → /backgrounds/night.webp
白天.webp  → /backgrounds/day.webp
占卜摊2.webp → /backgrounds/booth.webp
（其余 → 标记为「资源缺失」，不替换，进报告）
```

粒子（`known_effects.rs` 里有全集，去读）：
```
Starfield → starfield / Sakura → sakura / Rain → rain / Snow → snow
Fireflies → fireflies / Fireworks → fireworks / Fog → fog
MeteorShower → meteorshower / Blizzard → blizzard / Drizzle → drizzle / Thunderstorm → thunderstorm
```

### 2.5 覆盖度报告

```json
{
  "scriptCode": "imported-1",
  "scriptName": "小狼的爱好",
  "chapters": 5,
  "events": 23,
  "green": [ { "chapter": "Intro/intro", "index": 0, "type": "narration", "note": "可执行" } ],
  "yellow": [ { "chapter": "final", "index": 3, "type": "background",
                "issue": "资源缺失", "detail": "imagePath=房间.webp 不在映射表里" } ],
  "red":    [ { "chapter": "final", "index": 7, "type": "achievement",
                "issue": "事件类型未实现", "detail": "achievement 按约定不迁" } ],
  "summary": { "green": 18, "yellow": 4, "red": 1 }
}
```

**黄的判定**：
- 资源路径不在映射表
- 引用了不存在的角色（`character` 不是 MAIN 且不在 characters 里）
- 必需字段缺失（如 `ai_dialogue` 没有 `prompt` 且有 `character`）

**红的判定**：
- 事件 type 不在我们的 16 种里
- `chapter_end` 的 `next_chapter` 指向不存在的章节（悬空引用）

### 2.6 接口

```
POST /api/scripts/import          multipart/form-data，字段 file（zip）
POST /api/scripts/import/local    {"path":"references/lingchat/data/game_data/scripts/character/诺一钦灵/小狼的爱好"}
→ 200 ImportResult（含报告）
```

导入成功后：
- 剧本出现在 `GET /api/scripts`
- 章节可在 `GET /api/scripts/{code}` 里看到
- **不自动变成"可玩"**，报告里黄/红项还在就是还在

---

## 三、前端

`components/settings/pages/SettingsAdventure.vue` 里已有「导入剧本」按钮（现在是 `<input type=file>` 读了点东西）。
改成：
1. 选 zip → `POST /api/scripts/import`
2. 拿到报告 → 弹一个 `LcModal`，三色分区展示（绿可折叠、黄/红默认展开）
3. 顶部 `summary`：`18 可执行 / 4 需处理 / 1 未实现`
4. 底部按钮「完成」；如果有红项，额外显示「有 N 项未实现，导入后可编辑补全」

---

## 四、验收

1. 单元测试：
   - `%player%` / `%char%` 替换
   - 粒子中文名 → 英文枚举
   - 资源路径映射（命中 / 未命中进 yellow）
   - `next_chapter` 悬空 → red
   - 未知事件 type → red
   - 同一剧本导入两次 → chapters 不重复
2. **真实导入**：把 `references/lingchat/data/game_data/scripts/` 下**全部**剧本（不止一个）跑一遍 `import/local`，
   把每个剧本的报告 summary 打印出来（这是覆盖度全景）
3. `GET /api/scripts` 能看到导入的剧本，`GET /api/scripts/{code}` 能看到章节和事件
4. 前端截图：导入报告弹窗（要有黄/红）
5. 回报：文件清单+行数、**全部剧本的覆盖度汇总表**（每个剧本 green/yellow/red 数）、
   黄/红项的具体分布（哪类问题最多）

---

## 五、禁区

- 这一批**不许改引擎执行器**（`events/*.java`），只做导入与报告
- 不许把黄/红项"静默修掉"（比如资源缺失就随便给个默认图）——必须如实进报告
- 不许导入时创建 session / 改 `interview_message`
- 不许动 `data/` 源文件
- 章节 yaml 可能有多级目录（`Intro/intro.yaml`），`code` 要保留相对路径
