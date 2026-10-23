# R1a · 后端角色卡目录体系（原版 characters 逻辑 1:1）

> 主线写规格，子 agent 执行。本批只做**后端**。前端接入在 R1b。

## 0. 背景与裁决

原版 LingChat 是桌面应用，角色卡的真相源是磁盘目录：

```
game_data/characters/<folder>/
  settings.yml        ← 角色全字段
  avatar/*.webp       ← 情绪立绘（文件名即情绪枚举）
```

本项目是 Java 后端 + 浏览器，但**逻辑照搬原版**：目录即单位、目录名即角色标识（folder）、
settings.yml 存全字段。

**智豪已明确裁决**：不存在「自由对话 / 面试」两条分开的角色链路，一切 1:1 仿照 LingChat。
本批不引入任何面试适配字段，也不发明新的角色表结构。

## 1. 蓝本文件

| 蓝本 | 用途 |
|---|---|
| `references/lingchat/data/game_data/characters/{风雪,诺一钦灵,松子,DeepSeek}/settings.yml` | 4 张原卡全字段（复制，不改动源文件） |
| `references/lingchat/src-tauri/src/...characters*`（若能定位） | 加载/保存逻辑参考 |

先 `ls` 确认原版 4 张卡的目录内容再动手。

## 2. 目录资产就位

在项目根（`D:\cxdownload\javaee课设`）建：

```
game_data/characters/风雪/settings.yml
game_data/characters/诺一钦灵/settings.yml
game_data/characters/松子/settings.yml
game_data/characters/DeepSeek/settings.yml
```

- 从原版**复制**（不是移动、不是软链）对应 `settings.yml`。
- 目录名保持原版中文名，不许改成 `strict` 等 code。
- `avatar/` 立绘**不搬**：本项目立绘已在 `frontend/public/avatars/{strict,friendly,pressure,expert}/`
  （21 张/角色，已验收），搬运会破坏现有渲染链路。settings.yml 里不含立绘路径，无冲突。
- folder ↔ code 映射（写死在常量里，与既有 `source_folder` 保持一致）：
  风雪→strict、诺一钦灵→friendly、松子→pressure、DeepSeek→expert。

## 3. 依赖与配置

### 3.1 pom.xml
加（用 Boot dependency management 的版本，不写死 version）：

```xml
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
</dependency>
```

若 Boot 的 dependencyManagement 不带该版本则补上 `<version>2.2</version>`，
以 `mvn.sh` 实际解析结果为准。

### 3.2 application.yml
新增一行（与现有 `app.*` 同级）：

```yaml
app:
  characters-dir: game_data/characters
```

（按现有 application.yml 的实际缩进与结构插入，别破坏既有键。）

## 4. 服务层

新建 `backend/src/main/java/cn/edu/zjut/interview/service/CharacterCardService.java`。

用 `@Service`，`@Value("${app.characters-dir}") private String charactersDir;`
（或 `@ConfigurationProperties`，与项目既有风格一致——先读 `ScriptEditorService` / `PreviewService` 再定。）

方法：

| 方法 | 行为 |
|---|---|
| `List<CharacterCardSummary> listCharacters()` | 遍历目录下每个子目录，读 `settings.yml`，抽摘要字段；缺文件/解析失败的目录**跳过**并 warn 日志，不抛异常 |
| `CharacterCard getCharacter(String folder)` | 读单个；不存在抛自定义 `CharacterNotFoundException`（对齐 `editor/EditorNotFoundException` 的风格） |
| `void saveCharacter(String folder, Map<String, Object> fields)` | 先把 fields 序列化成 YAML 文本写入 `<dir>/settings.yml.tmp`，`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 覆盖原文件；folder 不存在或含路径穿越字符（`.`/`/`/`\`）直接 400 |
| `void createCharacter(String folder, Map<String, Object> fields)` | 建目录 + 写 settings.yml；已存在 → 409/400 |
| `void deleteCharacter(String folder)` | 整目录删除（递归）；不存在 → 404；不许删到根目录（folder 为空/含穿越字符 → 400） |

摘要/详情 DTO 放到 `dto/character/` 下：
`CharacterCardSummary.java`（folder / aiName / aiSubtitle / userName / userSubtitle / title / avatarMode）
`CharacterCard.java`（上面 + info / scale / offsetX / offsetY / bubbleTop / bubbleLeft / voiceModels / bodyPart / clothes / rawYaml）
`SaveCharacterRequest.java`（folder 可空 + rawYaml 或 fields 二选一）

字段名用 JSON camelCase（`ai_name` → `aiName`），与原版 yml 的 snake_case 键区分清楚，
DTO 上写 `@JsonProperty` 显式声明（项目已有 `@JsonProperty("package")` 的先例）。

`rawYaml`：直接把解析出的 Map 重新 dump 成 YAML 文本返回给前端，供角色 Tab 原样编辑/保存。
用 `new Yaml().dump(map)`，注意保留中文不转义（`DumperOptions` 设 `allowUnicode(true)`）。

### 4.1 启动同步（可选但要求做）
加一个 `@Component` 实现 `ApplicationRunner`（或 `CommandLineRunner`），启动时：
1. 扫目录得到 4 张卡；
2. 对每张卡按 folder→code 找 `interviewer` 表记录；
   - 有记录：用 yml 覆盖展示类字段（name/subtitle/card_title/card_info/scale/offset_x/offset_y/avatar_mode/bubble_top/bubble_left），
     **保留** persona_prompt / opening / follow_up / example_dialogue / display_role 等本项目扩展字段不动；
   - 没记录：不自动建（避免脏数据），只 warn；
3. 全程 try/catch，任何异常只 warn 不影响启动。

### 4.2 path 安全
`folder` 校验：`folder.isBlank()` / 含 `.` `..` `/` `\` / 长度 > 64 → 抛 400。
所有路径操作基于 `Paths.get(charactersDir).toAbsolutePath().normalize()`，
解析后必须 `startsWith(root)` 才继续。

## 5. 控制器

新建 `api/CharacterController.java`，`@RequestMapping("/api/editor/characters")`：

| 方法 | 路径 | 成功 |
|---|---|---|
| GET | `/api/editor/characters` | 200 + 数组 |
| GET | `/api/editor/characters/{folder}` | 200 |
| POST | `/api/editor/characters/{folder}` | 201（body 可选初始字段） |
| PUT | `/api/editor/characters/{folder}` | 204（body: 字段 map 或 rawYaml） |
| DELETE | `/api/editor/characters/{folder}` | 204 |

错误处理对齐 `ScriptEditorController` 的既有风格（项目已有全局异常处理则直接复用，先确认）。

## 6. 单测

新建 `backend/src/test/java/cn/edu/zjut/interview/service/CharacterCardServiceTest.java`
（`@SpringBootTest` + `@ActiveProfiles("test")`，与 `ScriptEditorServiceBackupTest` 同一风格）：

1. `listCharacters` 返回 4 张，folder 集合 = {风雪, 诺一钦灵, 松子, DeepSeek}
2. 风雪卡：`aiName=风雪`、`aiSubtitle=LingChat Studio`、`userName=钦灵`、`scale=1.25`、`offsetY=65.0`
3. `getCharacter("风雪").rawYaml` 含 `ai_name` 且含中文（无转义）
4. `getCharacter("不存在的目录")` 抛 `CharacterNotFoundException`
5. `createCharacter("tmp-char", {...})` 后目录与文件存在；重复创建 → 异常
6. `saveCharacter("tmp-char", {...})` 后重新 `getCharacter` 能读到新值
7. 路径穿越：`getCharacter("../..")` → 400（非法参数异常）
8. `deleteCharacter("tmp-char")` 后目录不存在；再删 → 404
9. 收尾：测试内务必清掉 `tmp-char`（`@AfterEach` 兜底删除），不许留脏数据

## 7. 验收

### 7.1 构建
`bash scripts/mvn.sh test` → 全绿（现有基线约 219 个测试，只许增加不许减少）。
后端只能用 `scripts/mvn.sh`，**禁止裸 mvn**。

### 7.2 curl（重启后端后）
后端打包重启：`bash scripts/mvn.sh package -DskipTests` → `java -jar`（端口 8080）。

```bash
curl -s localhost:8080/api/editor/characters | python -c "import json,sys; d=json.load(sys.stdin); print(len(d), [c['folder'] for c in d])"
# 期望：4 ['风雪','诺一钦灵','松子','DeepSeek']（顺序以实际为准）

curl -s localhost:8080/api/editor/characters/风雪 | python -m json.tool | head -20
# 期望 aiName=风雪 aiSubtitle=LingChat Studio userName=钦灵 scale=1.25 offsetY=65

curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/editor/characters/__nope__
# 期望 404

curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/api/editor/characters/..%2F.."
# 期望 400
```

### 7.3 数据还原
跑完后 `game_data/characters/` 只剩 4 张原卡（临时目录已清）。

## 8. 禁区

1. 不许跑任何 git 命令（含只读）。
2. 不许裸 `mvn`，只能用 `bash scripts/mvn.sh ...`。
3. 不许改 `frontend/`、`docs/`、`.workbuddy/`、`specs/`（含本规格）。
4. 不许改动 `references/` 下任何文件（只准读、复制）。
5. 不许删 `game_data/` 之外的任何目录；`deleteCharacter` 必须校验 prefix。
6. 不许加 `any` / `@ts-ignore`（本批无 TS）。
7. 单测不许留脏数据。
8. 不许改 `schema-h2.sql` 加列（本批新增字段走 yml 文本列，不改表结构）。

## 9. 报告

按第 7 节写：改动文件清单（含行数）、`bash scripts/mvn.sh test` 结尾原始输出、
curl 四条结果、数据还原情况、全部偏离规格之处（含原因）。诚实报告。
