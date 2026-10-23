# 面试工坊 · AI 模拟面试与能力提升软件

JavaEE 课程设计，选题为《计算机专业课程设计（期末课设）题目汇编》第 16 题「AI 模拟面试与能力提升软件」（锐捷网络，智能计算方向）。

前端视觉与交互 1:1 对齐开源桌面应用 LingChat，后端由 Rust 核心迁移为 Spring Boot，SSE 事件协议与数据结构保持同名同义，不另立标准。

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Tailwind CSS v4 |
| 后端 | Java 17 + Spring Boot 3.3 + Spring Data JPA |
| 数据库 | H2（`MODE=MySQL`，切 MySQL 只换方言与连接串） |
| LLM | DeepSeek `deepseek-chat`（OpenAI 兼容协议，流式） |
| 流式 | SSE，事件名对齐 LingChat 的 `ai:reply` 系列 |

前端无 Pinia、无 vue-i18n、无 markdown 库，store 用模块级 `reactive` 单例 + 独立导出 computed。

## 功能

**自由对话**
真实 DeepSeek 流式输出，分句解析（`【情绪】台词` 格式），情绪驱动立绘动画与气泡图，对话落库，多角色舞台。

**剧本引擎**
流程编排原语与 LingChat 一致：`ai_dialogue` / `input` / `narration` / `free_dialogue` / `modify_character` / `chapter_end`；`chapter_end` 支持 `linear` / `branching` / `ai_judged` 三种跳转。

**设置面板**（10 个 Tab）
面试官 / 剧本冒险 / 通用 / 背景 / 声音 / 成就 / 存档 / 高级设置 / 日志 / 面试规则。

**剧本编辑器**（8 个 Tab）
章节流程 / 剧本设置 / 校验 / 素材 / 角色 / AI 助手 / 助手设置 / 外观。

**Skill Agent（AI 助手）**
17 个 HTTP 端点，10 类事件变体（status / message_delta / item_start / reasoning / tool_call / tool_result / pending_approval / done / conversation_title / error），8 个工具（list_skills / read_skill / validate_script / list_files / read_file / write_file / edit_file / delete_file），按任务分级工具白名单，落盘走沙箱路径校验。

**角色卡体系**
`game_data/characters/<目录名>/settings.yml`，目录名即角色标识。4 张原卡人设原样导入：风雪（strict）、诺一钦灵（friendly）、松子（pressure）、DeepSeek（expert）。

## 快速开始

### 前置

- JDK 17
- Node.js 20+
- DeepSeek API Key（[申请地址](https://platform.deepseek.com/)）

### 1. 配置密钥

密钥不入库。二选一：

**方式 A：环境变量**

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
```

**方式 B：本地配置文件**（推荐，一次配好）

```bash
cd backend/src/main/resources
cp application-local.yml.example application-local.yml
# 编辑 application-local.yml，填入你的 key
```

`application-local.yml` 已在 `.gitignore` 中，不会被提交。

### 2. 启动

```bash
# 后端（默认 127.0.0.1:8080）
bash scripts/mvn.sh spring-boot:run

# 或带本地配置启动
cd backend
java -jar target/interview-lab-backend-0.1.0.jar --spring.profiles.active=local
```

```bash
# 前端（默认 127.0.0.1:5173，已代理 /api 到 8080）
cd frontend
npm install
npm run dev
```

项目根另有 `scripts/dev.sh`，一条命令拉起前后端。

### 3. 健康检查

```bash
curl http://localhost:8080/api/health
# {"status":"ok","tables":{"interviewer":4,"interview_script":10,"interview_chapter":37}}
```

## 目录结构

```
.
├─ backend/                      # Spring Boot 后端
│  └─ src/main/java/cn/edu/zjut/interview/
│     ├─ api/                    # REST 控制器
│     ├─ agent/                  # Skill Agent（事件、工具、路由、LLM 循环）
│     ├─ engine/                 # 面试引擎与剧本引擎
│     ├─ service/                # 业务服务（角色卡、对话、评分）
│     ├─ domain/ repository/ dto/
│     ├─ llm/ stream/ prompt/    # LLM 客户端、SSE、prompt 组装
│     └─ editor/ importer/ config/
├─ frontend/                     # Vue 3 前端
│  └─ src/
│     ├─ views/                  # 7 个页面
│     ├─ components/             # 房间、设置、编辑器、AI 助手面板
│     ├─ store/ api/ composables/ controllers/
│     └─ styles/                 # LingChat 设计令牌与全局类
├─ game_data/characters/         # 角色卡（目录名即角色标识）
├─ data/seed/                    # 角色卡、剧本、情绪映射种子
├─ specs/                        # 实施规格（每个批次一份）
├─ docs/                         # 方针、设计文档、进度台账
└─ scripts/                      # 迁移与生成脚本
```

## 文档

- [`docs/00-项目方针.md`](docs/00-项目方针.md) —— 唯一真相源，三方冲突时以它为准
- [`docs/design/00-总览.md`](docs/design/00-总览.md) —— 设计令牌、组件规格、动效、Tab 映射
- [`docs/03-待办与实施计划.md`](docs/03-待办与实施计划.md) —— 进度与缺口台账
- [`docs/前端构建经验与踩坑记录.md`](docs/前端构建经验与踩坑记录.md) —— Tailwind v4 等踩过的坑

## 关于参考项目

`references/lingchat/` 是 LingChat 的只读参照源码，**不入库**。运行本项目不需要它；需要复查「原版怎么实现的」时自行 clone 到该目录。

本项目的角色立绘、气泡动图、字体等美术素材直接来自 LingChat，后端逻辑为其 Rust 实现向 Java 的迁移，因此沿用 **AGPL-3.0** 协议分发。详见 [LICENSE](LICENSE)。

## 许可

[GNU Affero General Public License v3.0](LICENSE)

## 协作

课程设计项目，欢迎提 issue。提交前请确认：

```bash
cd frontend && npx vue-tsc --noEmit          # 前端类型检查，必须 0 error
cd backend && bash ../scripts/mvn.sh test    # 后端单测
```

**不要提交**：`application-local.yml`、`.env`、`node_modules/`、`backend/target/`、`references/`、`docs/课设材料/`（报告与答辩材料含个人信息，不随仓库分发）。

协作细节见 [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md)。
