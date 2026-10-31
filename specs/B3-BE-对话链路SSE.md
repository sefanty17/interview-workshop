# 规格 B3-BE：ChatService + SSE 对话链路

## 目标

让「前端发一句话 → 后端拼 prompt → 调 DeepSeek → 分句 → 解析 → 落库 → SSE 推回」这条链路**真跑通**。

不做：设置项影响（音量、语速、TTS）、剧本章节引擎、评分。只做自由对话主干。

---

## 一、接口

### 1.1 创建会话

```
POST /api/sessions
{ "interviewerCode": "strict", "jobId": "java", "mode": "free" }
→ 200
{
  "sessionId": 1,
  "interviewer": { "code":"strict", "name":"风雪", "displayRole":"严格技术面", "emotion":"正常" },
  "opening": { "message": "坐吧。今天不问废话…", "emotion": "平静", "seq": 1 },
  "systemPromptLength": 7650
}
```

做的事（顺序不能变）：
1. 查 `interviewer` + `user_profile`
2. 调 `PromptAssembler.build(...)` 生成完整 system 串（人设 + 编排 + 框架句 + 格式要求 + 示例 + 情绪限制 + 正文要求）
3. 建 `interview_session` 行（status=running startsAt=now）
4. **落 system 消息**：`interview_message`，`seq=0`、`role=system`、`content=完整 system 串`
5. **落开场白**：`seq=1`、`role=assistant`、`content=interviewer.opening`、`predicted_emotion` 从 opening 里解析（没有就「正常」）
6. 返回

**这一步是 1:1 复刻的核心**：system 串开场时拼一次、落库，之后每轮只读不重拼。

### 1.2 发消息（SSE）

```
POST /api/sessions/{id}/messages
{ "text": "我叫候选人，软工专业" }
→ text/event-stream
```

事件（名字一字不改，对齐 LingChat 的 `responses.rs`）：

| event | data |
|---|---|
| `ai:thinking` | `{"isThinking":true,"duration":0}` |
| `ai:reply` | 见下面 ReplyEvent |
| `status:reset` | `{}` |
| `ai:error` | `{"errorCode":"LLM_TIMEOUT","detail":"..."}` |

`ReplyEvent` 字段（camelCase，一个都不许少）：

```json
{
  "type": "reply",
  "isFinal": false,
  "duration": 2.4,
  "message": "这个项目里你负责哪部分？",
  "originalMessage": "【平静】这个项目里你负责哪部分？",
  "emotion": "平静",
  "originalTag": "平静",
  "motionText": "",
  "character": "风雪",
  "displayName": "风雪",
  "displaySubtitle": "严格技术面",
  "userMessageSeq": 2
}
```

`duration` 按字数算（原版是「展示时长」，给前端控制阅读节奏用）：`max(1.2, 字数 * 0.08)`，保留 1 位小数。

### 1.3 查询会话消息（给前端刷新用）

```
GET /api/sessions/{id}/messages?afterSeq=0
→ [{ seq, role, content, emotion, action, originalTag }]
```

---

## 二、`ChatService.processMessage` 的步骤（严格照这个顺序）

```
1. 查 session（不存在 → 404）
2. 若 session 首条 system 缺失 → 现场补一条（防止老数据）
3. 保存 user 消息：seq = max(seq)+1，role=user，content = SymbolParser.appendUserMessage(text).main
   （temp 段另存或丢弃，本项目先丢弃）
4. emit ai:thinking {isThinking:true}
5. 读全部消息 → MemoryBuilder.build(...) → List<LlmMessage>
6. 调 LlmClient.stream(messages)  （stream=true）
7. 逐 chunk 喂 SentenceSplitter
8. SentenceSplitter 每产出一句：
   a. SymbolParser.parse(sentence.text) → EmotionSegment
   b. 落库 assistant 消息（seq 递增，content=followingText，original_tag=originalTag，
      predicted_emotion=predicted，action=motionText）
   c. emit ai:reply（isFinal = 该句是否为收尾句）
9. 流结束：emit status:reset
10. 任何异常：emit ai:error，并 emit status:reset（保证前端能复位）
```

**注意**：`ai:thinking` 的 `isThinking=false` 要在第一句 `ai:reply` 之前发一次（或直接由第一句 reply 隐含结束）。
按原版语义：思考结束由第一条 reply 表达，不必单独发 false。以 Rust 源码为准，读 `chat.rs` / `generator.rs` 的 emit 时序。

---

## 三、`LlmClient`

OpenAI 兼容协议，`POST https://api.deepseek.com/chat/completions`，`stream=true`。

配置放 `application.yml`：

```yaml
app:
  llm:
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY:}
    model: deepseek-chat
    temperature: 1.0
    max-tokens: 800
    timeout-seconds: 60
```

实现用 `java.net.http.HttpClient` + `HttpResponse.BodyHandlers.ofLines()`（不要引入 WebClient/OkHttp，少一个依赖）。

解析 SSE 行：`data: {...}`，`data: [DONE]` 结束；取 `choices[0].delta.content` 拼起来。

**流式 chunk 不要直接落库**，只喂给 SentenceSplitter。

---

## 四、前端接入

### 4.1 新增 `frontend/src/api/chat.ts`

```ts
export interface ReplyEvent { type: string; isFinal: boolean; duration: number; message: string;
  originalMessage: string; emotion: string; originalTag: string; motionText?: string;
  character: string; displayName: string; displaySubtitle: string; userMessageSeq?: number }

export async function createSession(payload): Promise<...>
export async function* sendMessage(sessionId: number, text: string): AsyncGenerator<{ event: string; data: any }>
```

`sendMessage` 用 `fetch` + `ReadableStream` 手工解析 SSE（`EventSource` 不支持 POST）。
格式：`event: ai:reply\ndata: {...}\n\n`，按 `\n\n` 切块。

### 4.2 改 `views/InterviewRoomView.vue`

只改"发送"这一条路径，**页面结构、样式、房间逻辑不要动**：

```
send() 时：
  if 后端可用（/api/health 200）：
      state = 'thinking'
      for await (const ev of sendMessage(sessionId, text)) {
          if (ev.event === 'ai:reply')  → pushLine(ev.data) 到 roomStore，切到 responding
          if (ev.event === 'ai:error')  → 提示 + 复位
          if (ev.event === 'status:reset') → 复位到 input
      }
  else：
      走现有的本地假回复（保留，作为降级）
```

进入房间时：
- 若没有 sessionId → 调 `createSession`（interviewerCode 取 `store.settings.personaId`）
- 拿到 `opening` → 作为第一条 line 显示

**降级必须保留**：后端没启动时前端仍能演示（现在的假数据逻辑别删）。

### 4.3 Vite 代理

`frontend/vite.config.ts` 加：

```ts
server: { proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } } }
```

这样前端调 `/api/...` 不用配 CORS。

---

## 五、验收（必须自己跑通，不许只写代码）

1. `bash scripts/mvn.sh -q -DskipTests package` → BUILD SUCCESS
2. `bash scripts/mvn.sh test` → 全绿
3. 启动后端（`java -jar backend/target/*.jar`），然后 **curl 实测**：

```bash
# 建会话
curl -s -X POST http://127.0.0.1:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"interviewerCode":"strict","jobId":"java","mode":"free"}'
# 记下 sessionId，然后发消息（SSE）
curl -sN -X POST http://127.0.0.1:8080/api/sessions/1/messages \
  -H "Content-Type: application/json" \
  -d '{"text":"我叫候选人，软工专业，做过一个 JavaEE 的面试系统"}'
```

要求看到：
- 至少 1 个 `ai:reply`，最后一个是 `isFinal=true`
- `message` 里**不含** `【】` 和 `（）`（已被解析剥离）
- `emotion` 是 20 个枚举之一（不是原始别名，如 `冷淡` 应被折叠成 `平静`）
- 数据库里 `interview_message` 多了 2 条以上（user + assistant）
- `system` 行只有 1 条，长度 > 6000

4. 前端验证：起 vite，进 `/interview`，输入一句话发送，
   确认台词区出现模型真实回复（不是假的追问话术），情绪标签跟着变。

5. 回报：文件清单+行数、curl 实测的原始 SSE 片段（截前 3 条事件）、
   数据库里 system 行长度、assistant 消息条数与 emotion 值、前端截图路径。

---

## 六、禁区

- 不改 `PromptAssembler` / `MemoryBuilder` / `SentenceSplitter` / `SymbolParser` 的行为（它们是已验证的）
- 不改前端房间的 DOM 结构与样式
- 不引入新的 HTTP 客户端依赖
- 不许把 SSE 改成轮询
- 不许在前端硬编码 API Key（Key 只在后端 yml 里）
