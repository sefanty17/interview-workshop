# E1b · 剧本校验器（validate 1:1 迁移）

> 上游：`E1-编辑器基座.md`（已完成，schema + 章节读写已上线）。
> 原版真相源：`references/lingchat/crates/ling-chat-main/src/ai_service/game_system/script_engine/validate.rs`（1732 行）
> 依赖原版函数：`utils/script_function.rs::parse_variable_action`（67-89 行）+ `parse_value`（93-160 行）

## 0. 目标

把原版 68 个诊断码的校验器搬到 Java，端点：

```
GET /api/editor/scripts/{key}/validate
```

响应结构与原版 `ValidationReport` 一致（camelCase）：

```json
{
  "diagnostics": [
    {"severity":"error","code":"chapter.no_end","message":"...","chapter":"main","eventIndex":3,"field":"text"}
  ],
  "errorCount": 1, "warnCount": 0, "infoCount": 0,
  "variables": ["flag"],
  "edges": [{"from":"main","to":"end","isEnd":true,"label":"","endType":"linear"}]
}
```

`severity` 小写三值：`error` / `warn` / `info`。`chapter` / `eventIndex` / `field` 为 null 时**不输出**（`@JsonInclude(NON_EMPTY)`）。

`edges` 前端要拿去画真实流程图连线，`variables` 要给变量面板 —— 两个都不能漏。

## 1. 照抄原版，不许自己发明

**你必须打开 `validate.rs` 逐函数读，逐函数翻译。** 本规格只给：
- 映射决策（哪些原版概念在本项目没有对应物）
- 函数清单与行号
- 验收

判定逻辑、文案、severity、诊断码**一个字都不许改**。原版那些长注释解释的是「为什么这条不是 Error」，翻译时把注释一并搬过来（中文注释原样保留）。

## 2. 与 E1 的接口

已存在，直接用：

| 需要的东西 | 从哪来 |
|---|---|
| 剧本 | `InterviewScriptRepository.findByCode(key)`，找不到 → 404 |
| 章节列表 | `InterviewChapterRepository.findByScriptIdOrderByOrderNumAsc(scriptId)` |
| 章节 events | `InterviewChapter.getEvents()`，是 JSON 数组字符串 |
| schema | `ScriptSchemaCatalog.build()`（返回 `ScriptSchemaView`） |
| 素材目录 | `@Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")` |
| 同名剧本查重 | 遍历 `InterviewScriptRepository.findAllByOrderByOrderNumAsc()` 收集 `scriptName → List<code>` |

## 3. 新增文件

```
backend/src/main/java/cn/edu/zjut/interview/
  editor/
    ScriptValidator.java              主逻辑，对应 validate() + 全部 check_* 函数
    Severity.java                     enum ERROR/WARN/INFO，序列化小写
    Diagnostic.java                   诊断（code 用 String，不是 &'static str）
    ChapterEdge.java                  章节跳转边
  dto/
    ValidationReportView.java         diagnostics/errorCount/warnCount/infoCount/variables/edges
```
`ScriptEditorController` 加 `GET /scripts/{key}/validate`，`ScriptEditorService` 加 `validate(key)` 委托给 `ScriptValidator`。

## 4. 映射决策（技术栈差异，不做等于 bug，做了也等于 bug —— 严格按这张表）

### 4.1 六个码本项目**不产生**

原因都是原版依赖的实体在本项目不存在。这六个码仍要在代码里出现（定义常量 / 分支入口），但走不到：

| 码 | 原版依赖 | 本项目为何不产生 |
|---|---|---|
| `chapter.unreadable` | `Chapters/<id>.yaml` 文件读不出 | 章节在 DB 里，查到就是可读 |
| `chapter.bad_shape` | `ChapterDoc::from_json` 形状不对 | 我们的 `events` 列本身就是 JSON 数组 |
| `character.no_role_key` | 剧本内 `characters/<目录>/settings.yml` 的 `script_role_key` | 没有「剧本内角色设定文件」这个实体（角色统一在 `interviewer` 表） |
| `character.no_persona` | 同上，`system_prompt` 为空 | 同上 |
| `achievement.id_conflicts_builtin` | `builtin_achievement_ids()` | 本项目没有内置成就系统（成就 Tab 未迁移） |
| `config.unreadable` | `story_config.yaml` 读不出 | 剧本在 DB 里，404 在校验前就返回了 |

在 `ScriptValidator` 顶部用注释写明这张表，别让后来人以为是漏了。

### 4.2 其余 62 个码全部实做

### 4.3 概念映射

| 原版 | 本项目 |
|---|---|
| `chapter_ids = paths::enumerate_chapter_ids` | `chapterRepository.findByScriptIdOrderByOrderNumAsc(id)` 的 `code` 列表，**顺序即 order_num** |
| `chapter_set` | 同上转 `Set<String>` |
| `intro`（缺省 `"main"`） | `script.getIntroChapter()`，**空白时用 `"main"`**（保留原版缺省） |
| `folder_name` | `script.getCode()` |
| `script_key` | `script.getCode()` |
| `collect_script_characters` | `ScriptCharacters.parse(script.getCharacters())` 的字符串集合；**空集合时**：所有非 `MAIN` 的 `character` 引用都会报 `character.unknown`（Warn），这是正确的 —— 本项目剧本确实没声明角色 |
| `ScriptModes`（`.agent/modes`）| **本项目没有这套机制，固定按"未声明"处理**，即 `modes.asset != OnlyExisting`、`modes.cast != OnlyExisting` → 所有相关诊断一律 Warn，**且不追加后缀**：`asset.missing` 不带「（素材模式允许缺口：登记进 .agent/assets.md 即可）」，`character.unknown` 不带「（角色卡模式允许缺失：之后建好这张卡即可）」 |
| `builtin_achievement_ids` | `Set.of()` 空集 |
| `resolve_script_media(data_dir, Some(script_dir), path, media)` | 见 4.4 |
| `NONE_SENTINEL` | `"none"`（大小写不敏感） |
| `KNOWN_EFFECTS` | `ScriptSchemaCatalog.KNOWN_EFFECTS` |
| `parse_variable_action` | 见 4.5 |
| `check_character_personas(...)` | 整函数不实现（4.1） |
| `check_chapter_assets(...)`（供 Skill Agent 用） | 本批不做，E6 需要时再加 |

### 4.4 素材解析 `resolveMedia(String path, String kind)`

本项目素材是**扁平落盘**：`<assetTarget>/<code>/<文件名>`（见 `ScriptYamlImporter.publishAssets`）。

```
resolveMedia(path, kind):
  目录 = assetTarget/<code>/
  若目录不存在 → null
  # 先精确匹配文件名（忽略大小写，Windows 语义）
  命中 → 返回磁盘上的实际文件名
  # 再按「同主干的其它扩展名」兜底（复现原版 resolve_script_media 的行为，
  # 否则 asset.extension_mismatch 这个码永远不会触发）
  取 path 的文件名主干，在目录里找同类别的同主干文件
  命中 → 返回实际文件名
  否则 → null
```

`kind` 到扩展名的映射（原版 `MediaType::allowed_extensions`，本规格直接给定，不要去翻 Rust）：

| kind | 扩展名 |
|---|---|
| background | png jpg jpeg webp gif bmp |
| pic | png jpg jpeg webp gif bmp |
| music | mp3 ogg wav flac m4a |
| sound | mp3 ogg wav flac m4a |
| ambient | mp3 ogg wav flac m4a |

> 本项目导入器目前只搬背景图，所以 music/sound/ambient 的引用基本都会报 `asset.missing`(Warn)。**这是真实缺口，不要为了"干净"跳过检查** —— 作者该知道素材没进来。

### 4.5 `parseVariableAction(String expr)`

原版正则：`^\s*(\w+)\s*(=|\+=|-=)\s*(.+?)\s*$`

Java 版必须开 Unicode 词类（Rust regex 的 `\w` 是 Unicode 语义）：

```java
private static final java.util.regex.Pattern VAR_ACTION =
        java.util.regex.Pattern.compile("^(?U)\\s*(\\w+)\\s*(=|\\+=|-=)\\s*(.+?)\\s*$");
```

返回 `Optional<String> variableName`（解析失败即 `empty`），校验器只需要「能不能解析」和变量名，**值部分不需要求值**（`random()` 之类的求值不关校验器的事）。

对值部分只做一个检查：`random(a, b)` 形式时，两个参数必须能解析成整数且 `max >= min`，否则算解析失败（原版 `parse_value` 会 Err）。其余值一律视为合法。

### 4.6 `field.inert`

原版由 `schema.common_fields` 里 `enabled == false` 的字段驱动。本项目 schema 里 `common_fields` 两个都不是 disabled，**但 `chapter_end.fields` 里的 `next` 是 disabled**。

原版这里只查 `common_fields`（所以 `next` 的 inert 提示其实不会出现在原版里）。**照原版语义**：只扫 `commonFields`，本项目因此永不产生 `field.inert`。写注释说明，不要自作聪明去扫 events 的字段。

## 5. 函数清单（对照原版行号）

| 原版函数 | 行号 | Java 方法 | 备注 |
|---|---|---|---|
| `Severity` | 33-50 | `Severity` enum | `rank()`：error=0 warn=1 info=2 |
| `Diagnostic` + 4 个构造器 | 52-110 | `Diagnostic` + `script()/chapter()/event()/withField()` | |
| `ValidationReport` | 112-123 | `ValidationReportView` | record |
| `chapter_id_of` | 129-132 | `chapterIdOf(String)` | trim + 剥一次 `.yaml` |
| `branch_label` | 135-154 | `branchLabel(JsonNode opt, int index)` | condition → name → default「默认」→ `分支 N` |
| `ChapterEdge` | 160-173 | `ChapterEdge` | `label` 空串时不输出 |
| `validate` | 181-665 | `validate(InterviewScript, List<InterviewChapter>)` | 主流程，逐段翻译 |
| `finish` | 667-701 | `finish(...)` | 排序 + 三个计数 + variables + edges |
| `has_engine_default` | 707-718 | `hasEngineDefault(String ty, String field)` | 5 条硬编码 |
| `collect_script_characters` | 720-742 | 见 4.3 | |
| `check_character_personas` | 752-817 | **不实现** | 4.1 |
| `media_field_of` | 819-827 | `mediaFieldOf(String ty)` | 返回 `[key, kind]` |
| `check_modify_character_action` | 834-857 | 同名 | |
| `check_asset` | 860-943 | 同名 | 内部调 4.4 |
| `file_name_of` | 946-950 | `fileNameOf(String)` | 取最后一段 |
| `ASSET_MISSING_PREFIX/TAIL` | 953-954 | 常量 | 文案逐字 |
| `missing_asset_name` | 957-965 | 本批不做（E6 用） | |
| `check_chapter_assets` | 969-995 | **不实现** | |
| `check_condition` | 998-1078 | 同名 | |
| `check_actions` | 1080-1173 | 同名 | |
| `check_choices` | 1175-1303 | 同名 | |
| `check_set_variable` | 1305-1341 | 同名 | |
| `push_target` | 1348-1416 | 同名 | 参数顺序照抄 |
| `check_chapter_end` | 1418-1612 | 同名 | 最长，别漏分支 |
| `check_graph` | 1615-1732 | 同名 | 可达性 + DFS 三色环检测 |

### 5.1 几个易错点（原版注释里解释了原因，翻译时一定要保留分支）

1. **`required` 跳过规则**：`has_engine_default` 的 5 个 `(事件, 字段)` 组合不报 `field.required_missing`，因为引擎有默认值。别自己加规则。
2. **`present` 判定**：字段存在且不是 `null` 且 `as_str() != Some("")`。注意 `as_str()` 对非字符串返回 None —— 所以数字 `0`、布尔 `false` 都算"present"。用 `JsonNode` 时写：`!node.isNull() && !(node.isTextual() && node.asText().isEmpty())`。
3. **`check_condition` 只在运算符左侧扫非法运算符**，右值里的 `/` `*` `(` 是合法内容。
4. **`BAD_OPS` 顺序**：`["&&","||",">=","<=",">","<","!","(",")"]`，长运算符在前，命中即停。
5. **`chapter_end` 的 linear 分支**：`next` 优先于 `next_chapter`；两个都有时先报 `both_next_fields`(Warn) 再按 `next` 走。
6. **`end_suffix`**：`raw.trim() != "end"` 但归一后是 `end` 时报错并 `return`（不 push edge）。
7. **`branching` / `ai_judged` 的 `has_default`**：遍历所有分支累计，最后统一报 `no_default_branch`。
8. **`ai_judged` 的 default 分支不需要 name**，别误报 `ai_option_no_name`。
9. **`choices` 空文案选项**：`oi != last` 才检查；带 condition 报 Info，不带报 Warn。
10. **`check_actions` 里 `add_line` 在 `set_variable` 事件中**先报 `not_supported_here`(Warn)，再判空内容报 `empty_content`(Warn) —— 两个都可能同时出现。
11. **`graph.cycle`**：DFS 三色，找到第一个环就停，路径用 ` → ` 连接且首尾重复（`a → b → a`）。
12. **`variable.never_set` / `never_read`**：`varsRead - varsWritten` / `varsWritten - varsRead`，用 `TreeSet` 保证输出有序。
13. **`finish` 排序**：`severity.rank()` → `chapter`（null 排最前，Rust 的 `Option<String>` 排序 None < Some）→ `eventIndex`（同样 None < Some）。

## 6. 单测（必须写，这是验收的主要依据）

文件：`backend/src/test/java/cn/edu/zjut/interview/editor/ScriptValidatorTest.java`

用 `@SpringBootTest @ActiveProfiles("test")`，通过 `ScriptEditorService` 造剧本数据（或直接 new 实体存库），**不要依赖种子数据**。

### 6.1 必测用例（每个用例写清"造什么数据 → 断言哪些码"）

| # | 造的数据 | 断言 |
|---|---|---|
| 1 | 空章节列表 | 有 `chapters.empty`(error) |
| 2 | `intro_chapter` 指向不存在的章节 | 有 `config.intro_missing`(error) |
| 3 | `script_name` 为空 | 有 `config.no_script_name`(warn) |
| 4 | 两个剧本 `script_name` 相同 | 有 `config.duplicate_name`(error) |
| 5 | 章节 events 为空数组 | 有 `chapter.no_events`(warn) + `chapter.no_end`(error) |
| 6 | events 含 `"abc"` | 有 `event.not_a_map`(error) |
| 7 | events 含 `{"text":"x"}` | 有 `event.missing_type`(error) |
| 8 | events 含 `{"type":"nope"}` | 有 `event.unknown_type`(error) |
| 9 | events 含 `{"type":"narration"}` | 有 `field.required_missing`(error)，`field=text` |
| 10 | events 含 `{"type":"ai_dialogue","prompt":"x","typo":1}` | 有 `field.unknown`(warn)，`field=typo`；**无** `field.required_missing`（character 有默认值） |
| 11 | `condition: "hp >= 5"` | 有 `condition.unsupported_operator`(error) |
| 12 | `condition: "my var == 1"` | 有 `condition.bad_variable`(error) |
| 13 | `condition: "flag == %player%"` | 有 `condition.placeholder_not_replaced`(warn) |
| 14 | `{"type":"choices","options":[]}` | 有 `choices.empty`(error) |
| 15 | 两个选项文案都是 `"A"` | 有 `choices.duplicate_text`(warn) |
| 16 | 中间选项 `{}` 且无 condition | 有 `choices.catch_all_not_last`(warn) |
| 17 | 中间选项 `{"condition":"f"}` 无文案 | 有 `choices.catch_all_conditional`(info) |
| 18 | 选项带 `next` | 有 `choices.option_next_ignored`(error) |
| 19 | 选项带 `lock_hint` 无条件 | 有 `choices.lock_hint_without_condition`(info) |
| 20 | `{"type":"set_variable"}` | 有 `set_variable.no_options`(error) |
| 21 | `{"type":"set_variable","options":[]}` | 有 `set_variable.empty`(warn) |
| 22 | action `{"type":"set_var","content":"bad expr!"}` | 有 `action.bad_expression`(error) |
| 23 | action `{"type":"set_var","name":"a","value":"1"}`（无 content） | 有 `action.legacy_shape`(error) |
| 24 | `set_variable` 里的 `{"type":"add_line","content":"x"}` | 有 `action.not_supported_here`(warn)，**无** `action.empty_content` |
| 25 | action `{"type":"weird"}` | 有 `action.unknown_type`(warn) |
| 26 | `{"type":"free_dialogue","max_rounds":0,"end_line":""}` | 有 `free_dialogue.no_exit`(error) |
| 27 | `{"type":"background_effect","effect":"starfield"}` | 有 `effect.case`(info)，**无** `effect.unknown` |
| 28 | `{"type":"background_effect","effect":"Nope"}` | 有 `effect.unknown`(warn) |
| 29 | `{"type":"music","musicPath":"a.mp3"}`（素材不存在） | 有 `asset.missing`(warn) |
| 30 | `{"type":"ambient"}`（无 path 无 stop） | 有 `ambient.no_path`(warn) |
| 31 | `{"type":"ambient","stop":true}` | **无** `ambient.no_path`、**无** `asset.missing` |
| 32 | `{"type":"music","musicPath":"a.mp3","playbackSpeed":5}` | 有 `music.bad_speed`(warn) |
| 33 | `{"type":"modify_character","action":"dance"}` | 有 `character.action_unknown`(warn) |
| 34 | `{"type":"chapter_end","end_type":"weird"}` | 有 `chapter_end.unknown_end_type`(error) |
| 35 | `{"type":"chapter_end","end_type":"linear","next_chapter":"ghost"}` | 有 `chapter_end.dangling`(error)，**无 edge** |
| 36 | `{"type":"chapter_end","end_type":"linear","next":"end.yaml"}` | 有 `chapter_end.end_suffix`(error) |
| 37 | `{"type":"chapter_end","end_type":"linear"}` | 有 `chapter_end.no_next`(warn) |
| 38 | `next` 与 `next_chapter` 同时存在 | 有 `chapter_end.both_next_fields`(warn) |
| 39 | `chapter_end` 不在最后 | 有 `chapter_end.not_last`(warn) |
| 40 | `{"type":"chapter_end","end_type":"branching","options":[{"next":"a"}]}` | 有 `chapter_end.branch_no_condition`(warn)，**无** `no_options` |
| 41 | `end_type:"branching"` 无 options | 有 `chapter_end.no_options`(error) |
| 42 | `end_type:"branching"` 分支无 default | 有 `chapter_end.no_default_branch`(warn) |
| 43 | `end_type:"ai_judged"` 分支无 name | 有 `chapter_end.ai_option_no_name`(warn) |
| 44 | `end_type:"ai_judged"` default 分支无 name | **无** `ai_option_no_name` |
| 45 | `end_type:"ai_judged"` 分支带 condition | 有 `chapter_end.ai_condition_ignored`(info) |
| 46 | 分支同时有 `text` | 有 `chapter_end.choice_shaped_option`(error) |
| 47 | 3 章：a→b→a 循环 | 有 `graph.cycle`(warn)，消息含 `a → b → a` |
| 48 | 孤立章节（无人指向且开场走不到） | 有 `graph.unreachable`(warn) |
| 49 | 变量只读（condition 用 `flag`，从不赋值） | 有 `variable.never_set`(warn)，`variables` 含 `flag` |
| 50 | 变量只写 | 有 `variable.never_read`(info) |

### 6.2 结构断言
- 诊断排序：`error` 全在 `warn` 前，`warn` 全在 `info` 前。
- `edges`：linear 到 `end` 时 `isEnd=true`；到真实章节时 `isEnd=false`。
- `branching` 的 edge `label` 取 condition 优先，其次 name，最后「默认」。

## 7. 禁区

同 E1 第 10 节，另外加三条：

- 不许改 `ScriptSchemaCatalog` 的任何字段定义（校验器依赖它，改了就是两处不同步）。
- 不许把诊断文案缩短、改写、翻译成"更通顺的中文"。文案是前端直接展示给作者的，逐字照抄。
- 不许为了让测试变绿而放宽判定（比如把 Error 降成 Warn、或跳过某条码）。测试是验收，不是目标。

## 8. 验收

```bash
cd backend
../scripts/mvn.sh -q test -Dtest=ScriptValidatorTest
../scripts/mvn.sh -q -DskipTests package
```

真机（8080 已有一个后端在跑，先停掉再启动新 jar）：

```bash
# 种子剧本的校验结果（当前种子应该比较干净）
curl -s localhost:8080/api/editor/scripts/java-strict/validate | python -m json.tool

# 断言结构完整
curl -s localhost:8080/api/editor/scripts/java-strict/validate | python -c "
import sys,json
d=json.load(sys.stdin)
print('键:',sorted(d.keys()))
print('计数:',d['errorCount'],d['warnCount'],d['infoCount'],'诊断数:',len(d['diagnostics']))
print('edges:',d['edges'])
print('variables:',d['variables'])
"
```

**必须做到的一步**：用 `PUT /api/editor/scripts/{key}/chapter` 往一个**临时新建的剧本**里写一个刻意写坏的章节（照 6.1 组合若干个错误），然后 `GET .../validate` 确认诊断码真实出现。验证完 `DELETE` 掉这个临时剧本的章节。

不允许只跑单测就交差 —— 单测用的是内存库，必须证明真机也能跑通。

## 9. 交付报告

1. 新增/修改文件清单
2. `mvn test` 输出（ScriptValidatorTest 的 Tests run 行 + 全量回归行）
3. 6.1 五十个用例里，**哪些是通过真实断言的、哪些你没写**（没写的直接列出来，不要谎报）
4. 真机 curl 的输出
5. 临时坏剧本的诊断码清单（证明真机能产生诊断）
6. 偏差与"不做"清单
