# 规格 B0-BE：Spring Boot 骨架 + 提示词拼装 + 分句解析

## 铁律（先读这条）

**这不是重写，是搬运。** LingChat 的 Rust 代码怎么算，Java 就怎么算，一个字都不许自己发挥。

- 函数职责、判断分支、字符串常量、正则、遍历顺序，全部照抄
- 命名可以 Java 化（snake_case → camelCase），**行为不许变**
- 拿不准时：先读 Rust 源码，照它的分支写；不要"我觉得这样更好"
- 每个类的方法上写注释标明来源行号，例如 `// 对应 prompt.rs:183 sys_prompt_builder`

---

## 一、项目骨架

```
backend/
  pom.xml
  src/main/java/cn/edu/zjut/interview/
    InterviewApplication.java
    config/{JacksonConfig,WebConfig}.java
    domain/            ← 6 张表的实体
    repository/        ← Spring Data JPA
    prompt/            ← F1：PromptAssembler / MemoryBuilder / PromptConstants
    stream/            ← F2：SentenceSplitter / SymbolParser / EmotionDictionary
    llm/               ← F3 才用，本批只放接口
    api/               ← 本批只放健康检查
  src/main/resources/
    application.yml
    schema-h2.sql      ← 从 data/schema/schema-h2.sql 复制
    data-h2.sql        ← 从 data/seed/data-h2.sql 复制
    emotions.json      ← 从 data/seed/emotions.json 复制
  src/test/java/...    ← 单元测试
```

坐标：`cn.edu.zjut:interview-lab-backend:0.1.0`，Java 17，Spring Boot **3.3.5**（不要 3.5.x，稳）。

### pom.xml 依赖

```xml
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-validation
com.h2database:h2        （runtime）
spring-boot-starter-test （test）
```

不用 Lombok（避免注解处理器惹麻烦），实体手写 getter/setter。

### application.yml

```yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:h2:file:./data/interview;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-h2.sql
      data-locations: classpath:data-h2.sql
      continue-on-error: false
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: true
```

注意：`data-h2.sql` 里可能有 `INSERT ... ON DUPLICATE KEY` 之类 H2 不认的语法，跑不通就改成 `MERGE INTO`，
**但字段和值一个不许改**。

---

## 二、实体（6 张表）

DDL 在 `data/schema/schema-h2.sql`，**按它逐字段建实体**，字段名、类型、可空性、长度对齐。
表：`interviewer` / `user_profile` / `interview_script` / `interview_chapter` / `interview_session` / `interview_message`。

要点：
- `interview_chapter.events` 是 CLOB 存 JSON 数组，Java 侧用 `String` 存，需要时再反序列化
- `interview_message.role` 值域 `system/user/assistant`
- 时间字段用 `LocalDateTime`，标注 `@Column(columnDefinition = "TIMESTAMP")` 如果 DDL 是 TIMESTAMP

Repository 只要 `JpaRepository` + 几个查询方法（按 session 查消息、按 code 查角色/剧本）。

---

## 三、F1：提示词拼装（最高优先级，字符串必须一致）

### 3.1 `PromptConstants.java`

从 `references/lingchat/crates/ling-chat-main/src/utils/prompt.rs` **逐字复制**这 6 个常量：

| 常量 | 原文件行号 | Java 字段名 |
|---|---|---|
| `DIALOG_FORMAT_PROMPT_CN` | 33-57 | `DIALOG_FORMAT_PROMPT_CN` |
| `DIALOG_FORMAT_PROMPT_2_EMOTION_LIMIT_HEAD` | 88-92 | `EMOTION_LIMIT_HEAD` |
| `DIALOG_FORMAT_PROMPT_2_BODY` | 93-110 | `DIALOG_FORMAT_PROMPT_2_BODY` |
| `DEFAULT_EXAMPLE_CN` | 111-129 | `DEFAULT_EXAMPLE_CN` |
| `EXAMPLE_CUSTOM` | 159-162 | `EXAMPLE_CUSTOM` |
| （框架句由 `build_framing_prefix_cn` 生成，见 3.2） | 163-182 | — |

**用 Java 15 的 text block（`"""`）**，注意：
- Rust 的 `indoc!{r#"..."#}` 会去掉公共缩进，Java text block 也会，但要确认首尾换行一致
- 复制后**写一个测试比对长度**：把 Rust 常量的字符数（去掉公共缩进后）和 Java 的 `String.length()` 对比，
  差一个字符都算错。Rust 侧的字数你自己用 python 或其他方式量出来，写进测试断言。

### 3.2 `PromptAssembler.java`

对应 `prompt.rs:183 sys_prompt_builder`，**中文分支**（不实现 `_JP`）。

```
入参：aiPrompt（人设）、userName、characterName、exampleDialogue（可空）、interviewPlan（可空，我们扩展）
出参：String（完整 system prompt）
```

拼装顺序（一个字都不许变）：

```
1. aiPrompt
2. 三个硬性判断：
   a. aiPrompt 含 "日语翻译"        → 直接 return aiPrompt（不拼格式）
   b. aiPrompt 含 "以下是我的对话格式提示" → 直接 return aiPrompt
   c. 否则继续
3. 【本项目扩展】interviewPlan（非空时插入，格式见 3.3）
4. buildFramingPrefixCn(userName, characterName)
5. DIALOG_FORMAT_PROMPT_CN
6. DEFAULT_EXAMPLE_CN
7. exampleDialogue 非空 → EXAMPLE_CUSTOM + exampleDialogue
8. EMOTION_LIMIT_HEAD
9. DIALOG_FORMAT_PROMPT_2_BODY
```

段与段之间用 `\n` 连接，具体几个换行照 Rust 的 `format!` 看。

`buildFramingPrefixCn` 对应 `prompt.rs:163`，把 `{user}` `{char}` 占位符替换成实际名字，
**逐字照抄格式**（包括「你现在扮演」这类措辞）。

### 3.3 面试编排段（我们唯一的新增，要标清楚）

在 `aiPrompt` 之后、框架句之前插入，模板固定：

```
【本次面试编排】
岗位：{job}
面试官：{interviewerName}（{displayRole}）
流程：
{stage1}. {stageName} —— {stageGoal}
...
轮次控制：{pacing}
结束条件：{endCondition}
```

内容来自 `interview_script` + `interview_chapter`（章节 title / goal）。**这段是我们扩展的，注释里写清楚。**

### 3.4 `MemoryBuilder.java`

对应 `game_system/memory_builder.rs:88 build`。台词表 → `List<LlmMessage>`。

规则（逐条照抄，边界最容易错）：

1. 遍历 `interview_message`（按 `seq` 升序），分三类累积：
   - `role == system`：**只取第一条**变 `LlmMessage.system(content)`，后面重复的忽略并 `log.warn`
   - `role == assistant`：连续多句**合并成一条** assistant 消息，每句格式 `【{emotion}】{content}\n({action})\n\n`，
     整段末尾再加一个 `\n`
   - `role == user`：连续多句合并成一条 user 消息，每句 `content\n`，段末空行
2. 合并的边界：角色切换就断开
3. 首条 system 必须是本次会话的 system 行（对话开场写入的那条）

返回的 `LlmMessage` 结构：`role`（system/user/assistant）+ `content`。用 record 或简单类，序列化给 OpenAI 兼容接口时字段名就是 `role` / `content`。

---

## 四、F2：流式分句

### 4.1 `SentenceSplitter.java`

对应 `message_system/producer.rs:89`。**不是按句号切，是按 `【` 标签边界切**。

状态：`buffer`（StringBuilder）、`inTag`（boolean）、`sentenceIndex`、`turnEnded`（boolean）。

伪代码（照 Rust 分支写）：

```
feed(chunk):
  for each char c in chunk:
    if turnEnded: 直接丢弃（越界保护）
    if c == '【':
        if buffer 里已经有完整的【...】正文（即之前见过】）:
            切出 buffer → emit(text, index++, final=false)，buffer 清空
        进入 inTag 状态
    buffer.append(c)
    if c == '】': inTag = false

finish():
  剩余 buffer 非空 → emit(text, index, final=true)
```

**越界保护**（`producer.rs:110`）必须实现：见到回合结束标记（空行 + `user` / `\n\n\n` 这类，
具体标记照 Rust 源码里的判断）后，后续内容一律丢弃，并把 `turnEnded` 置 true。
不迁这条会出现"模型替玩家说话 → 写进历史 → 下轮复读"。

### 4.2 `SymbolParser.java`

对应 `message_system/processor.rs:120`，6 条正则 + 一段文本 → `List<EmotionSegment>`：

| 用途 | 正则 |
|---|---|
| 情绪+正文 | `【([^】]*)】([^【】]*)` |
| 日语 | `<([^>]*)>` |
| 动作 | `（([^）]*)）`（全角括号） |
| 花括号块 | `\{([^}]+)\}` |
| 临时段 | `(?s)\[!Temp!\](.*?)\[/!Temp!\]` |
| 剥剩余标签 | `<[^>]*>`、`（[^）]*）`、`【[^】]*】` |

输出 `EmotionSegment`：`index / originalTag / followingText / motionText / predicted`。
`japaneseText` 解析出来但我们不做 TTS，**保留字段但不用**。
`predicted` 用 `EmotionDictionary` 查表（`data/seed/emotions.json`，82 条别名），查不到就用 originalTag。

另外实现 `appendUserMessage`（`processor.rs:264`）：
1. 抽出 `[!Temp!]…[/!Temp!]` 段（返回 `{main, temp}`），从正文删掉
2. 时间感知提示按开关挂上（本项目先固定关闭）

### 4.3 `EmotionDictionary.java`

读 `emotions.json`（20 个枚举 + 82 条别名），提供 `predict(tag)`：
- 精确命中别名 → 返回标准枚举
- 未命中 → 返回原 tag
- 空 → 返回 `"正常"`

---

## 五、单元测试（必须写，这是验收依据）

`src/test/java/.../prompt/` 和 `.../stream/`：

1. **PromptAssemblerTest**
   - 拼装顺序：断言输出里各段的起始位置依次递增
   - 短路分支：喂「日语翻译」→ 输出等于入参；喂「以下是我的对话格式提示」→ 同样
   - 常量长度：6 个常量的 `length()` 与你从 Rust 量出的数字一致（把测量值写进断言）
2. **SentenceSplitterTest**
   - 喂 `【平静】你好啊【冷淡】今天不谈这个` → 2 句
   - 喂分片（每次 3 个字符）→ 结果与一次性喂**完全相同**（这条最重要，证明与 chunk 边界无关）
   - 越界：`【平静】好\n\n\nuser\n我不能说` → 只出 1 句，`user` 之后被丢弃
3. **SymbolParserTest**
   - `【冷淡】走吧（转身）` → emotion=平静（别名折叠）、motionText=`转身`
   - `【未知情绪】x` → predicted 回落到 `未知情绪`
   - `<こんにちは>【平静】hi` → japaneseText=`こんにちは`
4. **MemoryBuilderTest**
   - 造 5 条消息（1 system + 2 assistant + 2 user）→ 断言合并成 3 条 LlmMessage
   - 两条 system → 只取第一条

---

## 六、验收

1. `bash scripts/mvn.sh -q -DskipTests package` → BUILD SUCCESS
   （Maven 用项目根目录的 wrapper：`bash scripts/mvn.sh ...`，不要用系统 `mvn`，它坏了）
2. `bash scripts/mvn.sh -q test` → 全绿
3. `bash scripts/mvn.sh -q spring-boot:run` 能起来，访问 `GET http://127.0.0.1:8080/api/health` 返回
   `{"status":"ok","tables":{"interviewer":4,"interview_script":4,"interview_chapter":21}}`（数字来自种子数据）
4. 汇报：文件清单 + 行数、6 个常量的字符数对照表、测试用例数、health 实测输出

---

## 七、禁区

- 不许改 `data/` 下的任何文件（要复制到 resources，不要改源文件）
- 不许改前端任何文件
- 不许自己发明 prompt 文案
- 不许省略越界保护、短路分支、只取第一条 system 这些"看起来多余"的逻辑
- 不许用 Lombok
