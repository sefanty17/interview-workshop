# RAG 岗位知识库 · 实施成果（K1–K7 已交付）

> 2026-10-27 归档。提交链 `35d4c12`(K1) → `8d0b1ae`(K2) → `0322f58`(K3) → `11319cd`(K4) → `7355a7f`(K5) → `5a07d91`(K6) → `ecae092`(K7)。
> 对应课设六条硬指标的第 ① 条「至少两种岗位的题库 + 知识库作 RAG 基础」。

## 一、结论先行

- 两条岗位线：`java`（6 篇）、`frontend`（3 篇），共 **9 篇 / 40 段**语料。
- 检索命中率（自建 26 正 6 负评测集）：**hybrid Hit@1 96.2% → 加 HyPE 后 100%**，负样本误注入 0/6。
- 留出集（10 条未参与调参的口语问法）：Hit@1 **70% → 80%**，MRR 0.808 → 0.850。
- 后端 `knowledge` 包 **1423 行** + 服务/控制器 798 行；前端 471 行；运维脚本 733 行。
- 测试基线 398 → 433 → 440（K6 加 3 条，K7 加 4 条）。

## 二、七层结构

| 层 | 提交 | 内容 | 主要文件 |
|---|---|---|---|
| K1 基座 | `35d4c12` | 4 张表、embedding 抽象、知识库 CRUD、角色绑定 | `knowledge/EmbeddingProvider.java`、`HashingEmbeddingProvider.java`、`OpenAiCompatibleEmbeddingProvider.java`、`service/KnowledgeService.java`、`api/KnowledgeController.java` |
| K2 导入 | `8d0b1ae` | Markdown 标题切分、FNV-1a 指纹增量同步、导入脚本 | `scripts/import_knowledge.py` |
| K3 检索 | `0322f58` | 中文分词、BM25、向量、加权 RRF、阈值 | `knowledge/ChineseTokenizer.java`、`RetrievalIndex.java`、`RetrievalService.java`、`KnowledgeIndexVersion.java` |
| K4 注入 | `11319cd` | 每轮按玩家原话检索 top-3 注入当轮请求副本 | `knowledge/KnowledgeInjector.java`、`ChatService` 第 5.7 步、SSE 事件 `ai:knowledge` |
| K5 前端 | `7355a7f` | 设置页第 11 个 Tab + 角色信息页多选 + 上传 md | `api/knowledge.ts`、`SettingsKnowledge.vue`、`character-info/RoleKnowledgeEditor.vue`、后端 `MarkdownChunker.java` |
| K6 HyPE | `5a07d91` | 片段挂「假设问题」，进索引不进结果正文 | `scripts/generate_hype.py`、`data/knowledge/hype.json` |
| K7 工具 | `ecae092` | 剧本助手第 9 个工具 `search_knowledge` | `agent/SkillAgentTools.java`、`agent/Role.java`、`agent/SkillAgentRouter.java` |

## 三、数据模型（4 张表，`backend/src/main/resources/schema-h2.sql`）

```
knowledge_doc            文档：code / title / category(java|frontend) / source / updated_at
  └ knowledge_chunk      片段：doc_id / idx / heading / text / hype_questions / fingerprint
       └ knowledge_vector 向量：chunk_id / dim / embedding(BLOB)
interviewer_knowledge    角色 ↔ 文档 多对多（角色卡「岗位知识」勾选结果）
```

设计口径：
- **不用复合主键**，一律 `BIGINT IDENTITY` + 业务唯一约束（与 `interviewer_relation` 一致）。
- 正文 `text` 是真相源；`hype_questions` 是模型生成的辅助索引，可整列重算而不动正文。
- `knowledge_vector` 独立表而不是片段的一个 BLOB 列：换 embedding 模型要整批重算，独立表能只删向量不删语料。

## 四、检索链路

```
查询 → ChineseTokenizer（中文 bigram、拉丁段整体）
     ├─ BM25（k1=1.2 b=0.75，阈值 3.0）
     └─ 余弦相似度（阈值 0.30）
        → 加权 RRF：score += w / (60 + rank)，w_bm25=1.0 / w_vector=0.2
        → 按 docCode / category 过滤 → topK
```

- **embedding 默认用 `HashingEmbeddingProvider`**：DeepSeek 没有 `/embeddings` 接口，这套是汉字 bigram + 拉丁词哈希到 512 维再 L2 归一化，纯 JDK 零依赖。`OpenAiCompatibleEmbeddingProvider` 保留，配 `app.knowledge.embedding.provider=openai` 即可切真模型。
- **索引缓存按版本号失效**：`KnowledgeIndexVersion` 在写入/删除时 bump，检索侧比对版本重建不可变快照，不做逐次查库。
- **阈值为什么是 0.30 / 3.0**：向量分在正负样本上有重叠（正样本最低 0.132 / 负样本最高 0.184），不能当硬门槛；BM25 能干净分开。所以向量当排序信号、BM25 当准入门槛。
- **权重为什么是 0.2 : 1.0**：等权 RRF 时 hybrid 92.3% 反而低于单 BM25 96.2%，扫描后定为 0.2:1.0。

## 五、注入铁律（K4）

1. 每轮用**玩家本轮真实输入**检索，不是对话历史摘要。
2. 只改本轮请求的副本，不落库 —— 下一轮重新检索，历史消息里的知识块不累积。
3. `KnowledgeInjector` 三条短路：开关关 / 查询为 blank / 检索抛异常 → 一律不注入，不影响对话。
4. 命中片段以 `[岗位知识 N]` 前缀拼进 system 附加段，同时通过 SSE `ai:knowledge` 推给前端展示来源。

## 六、HyPE（K6）与评测方法

- 每个片段挂 3~5 个「这段话能回答的问题」，与正文一起进词表与向量，但**检索结果的 `text` 只给正文** —— 否则模型生成的问题会被拼进提示词，面试官会照着念。
- 指纹包含假设问题 → 改了问题必然重算向量，不会出现「旧向量配新问题」。
- 种子 `data/knowledge/hype.json` 键为 `文档code|小节名`，与 md 切分结果 40/40 完全对齐（有脚本校验）。
- **评测必须用留出集**：主评测集那 26 条写种子时参考过，直接比是既出题又给答案。`data/eval/holdout-questions.json` 是 10 条没见过的口语问法，且逐条核对过与种子无 3 字以上重合。

| 评测集 | 指标 | 无 HyPE | 有 HyPE |
|---|---|---|---|
| 主集 26 正 / 6 负 | Hit@1 / MRR | 96.2% / 0.974 | 100% / 1.000 |
| 留出集 10 正 | Hit@1 / Hit@3 / MRR | 70% / 90% / 0.808 | 80% / 80% / 0.850 |

留出集有 1 条退步（「根节点是怎么判定的」第 3 → 第 4 名），其余 9 条不降，Hit@1 与 MRR 净涨，故保留。

## 七、K7 工具的分工

`search_knowledge` 只给**剧本助手**（唯一真有 tool-call 循环的链路），写题目/章节前按需查语料。

**面试对话侧刻意不加工具循环**：那一路是流式出字，塞一轮 tool hop 会让首字延迟翻倍，而 K4 已经每轮注入 top-3，收益覆盖不了成本。两边用的是同一条检索链路与同一套阈值，口径一致。

## 八、HTTP 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/knowledge/docs` | 文档列表 |
| POST | `/api/knowledge/docs` | 新建文档 |
| GET | `/api/knowledge/docs/{code}` | 单个文档 + 片段 |
| DELETE | `/api/knowledge/docs/{code}` | 删除文档（级联片段与向量） |
| PUT | `/api/knowledge/docs/{code}/chunks` | 整体替换片段（指纹增量） |
| POST | `/api/knowledge/docs/{code}/markdown` | 上传 Markdown（服务端切分） |
| POST | `/api/knowledge/docs/{code}/reindex` | 强制重算向量 |
| GET | `/api/interviewers/{code}/knowledge` | 角色的岗位知识绑定 |
| PUT | `/api/interviewers/{code}/knowledge` | 保存绑定 |
| GET | `/api/knowledge/search?q=&mode=hybrid\|bm25\|vector&topK=` | 检索（调试/评测用） |
| GET | `/api/knowledge/health` | 索引规模与 provider 状态 |

## 九、运维脚本

```bash
python scripts/import_knowledge.py            # 批量导入 data/knowledge/**/*.md（幂等）
python scripts/eval_knowledge.py              # 主评测集
python scripts/eval_knowledge.py --questions data/eval/holdout-questions.json
python scripts/eval_knowledge.py --calibrate  # 阈值标定复核
python scripts/eval_knowledge.py --sweep      # 权重扫描
python scripts/generate_hype.py --from-seed   # 写假设问题（手写种子）
python scripts/generate_hype.py --llm         # 用 LLM 现场生成
```

全部为纯标准库，不引入 Python 依赖（本机 pip 直连与清华源均 502）。

## 十、已知限制与后续

1. **embedding 是哈希近似，不是语义模型**。跨表述的同义问法能力弱，这是留出集只有 80% 的主因。配真 embedding 服务能显著改善，但当前默认零依赖优先。
2. **语料是自建的 9 篇示例**，不是完整题库。要求「两种岗位的题库」还需要扩充到每题一条（后续可让 #4/#5 的分析结果反哺语料）。
3. **主评测集 26 条已被 K6 调参污染**，后续调参一律以留出集或新建评测集为准。
4. K7 只有单元测试，未做真模型调用验证（模型是否会主动调 `search_knowledge` 未观测）。
