export type PersonaId = "strict" | "friendly" | "pressure" | "expert";

export interface Persona {
  id: PersonaId;
  name: string;
  title: string;
  style: string;
  tone: string;
  quote: string;
  followUp: number;
  speed: number;
  avatar: string;
  voice: string;
  opening: string;
}

export interface Job {
  id: string;
  name: string;
  en: string;
  desc: string;
  knowledgePoints: KnowledgePoint[];
}

export interface KnowledgePoint {
  id: string;
  name: string;
  group: string;
}

export interface Question {
  id: string;
  jobId: string;
  kp: string;
  type: string;
  difficulty: string;
  text: string;
  reference: string;
  followUps: string[];
}

export interface DimensionScore {
  name: string;
  score: number;
}

export interface QuestionReview {
  id: string;
  question: string;
  kp: string;
  score: number;
  comment: string;
  suggest: string;
}

export interface InterviewRecord {
  id: string;
  /**
   * 本场后端场次 id；有它报告页才能去后端拉 AI 评估。
   * 老存档没有这个字段，报告页会退回本地数据。
   */
  sessionId?: number;
  jobName: string;
  personaName: string;
  date: string;
  duration: string;
  score: number;
  dimensions: DimensionScore[];
  questions: QuestionReview[];
  advice: string[];
}

/** 环境音播放轨道（原版 uiStore.ambientTracks） */
export interface AmbientTrack {
  id: string;
  name: string;
  /** 单轨音量 0~100 */
  volume: number;
  /** 循环模式：single=单曲循环 / list=列表循环 / once=不循环 */
  loop: "single" | "list" | "once";
  paused: boolean;
}

/**
 * 单个大模型服务商配置，字段名沿用原版 api/services/llm-providers.ts 的 LlmProviderConfig。
 * provider 取值为 deepseek / openai / lmstudio / gemini / kimicode / codex。
 */
export interface LlmProviderConfig {
  id: string;
  label: string;
  provider: string;
  model: string;
  api_key: string;
  base_url: string;
  temperature: number | null;
  top_p: number | null;
  enable_thinking: boolean;
  reasoning_effort: string | null;
  fast_mode: boolean;
  /** 该模型是否支持原生多模态识图（勾选后发图直接走对话模型，不再旁白转述） */
  support_vision: boolean;
  /** 原生识图时是否压缩图片（默认 false：原图直发） */
  vision_compress: boolean;
  /** 压缩时图片最大边长（像素），超宽图等比缩放 */
  vision_max_edge: number;
  /** 压缩时 JPEG 编码质量（0-100） */
  vision_jpeg_quality: number;
}

/** 单个服务商预设（对应原版 constants/llm-presets.ts 的 LlmPreset） */
export interface LlmPreset {
  key: string;
  label: string;
  provider: string;
  model: string;
  base_url: string;
}

/** 单组滤镜参数（角色 / 背景共用，对应原版 FilterParams） */
export interface FilterParams {
  brightness: number;
  contrast: number;
  saturation: number;
  sepia: number;
  glow_radius: number;
  glow_color: string;
}

/** 场景光影参数（对应原版 LightingParams，存进 SceneInfo.lighting） */
export interface LightingParams {
  character: FilterParams;
  background: FilterParams;
  overlay_enabled: boolean;
  blend_mode: string;
  light_x: number;
  light_y: number;
  overlay_color1: string;
  overlay_color2: string;
  overlay_radius: number;
  overlay_opacity: number;
  overlay_target: string;
}

/** 面试场景（原版 SceneInfo，字段名沿用原版，供 settings/background/SceneCard.vue 使用） */
export interface SceneInfo {
  id: string;
  scene_name: string;
  scene_description: string;
  /** 背景图地址（真实图片，如 /backgrounds/day.webp）；空串表示没有背景图 */
  background: string;
  /** 所属子分类；null = 根目录 */
  category: string | null;
  /** 来源：game = 内置；plugin = 插件 */
  source?: string;
  plugin_id?: string | null;
  /** 室内外标注（原版由 LLM 标注一次，点击可在三者间循环） */
  location_type?: "indoor" | "outdoor" | "unknown";
  /** 收藏（收藏的场景排在前面） */
  favored?: boolean;
  /** 光影参数（原版 SceneEditModal 编辑，可为空） */
  lighting?: LightingParams | null;
}

/** 单个识别服务商的配置：键由后端 config_fields 动态给出，故用索引签名承载任意键 */
export interface AsrProviderConfig {
  [key: string]: string | number | boolean | Record<string, string>;
}

/** 识别模型的本地下载状态 */
export interface AsrModelState {
  installed: boolean;
  /** 下载进度（0–100） */
  progress: number;
}

/** 工具访问审批模式（命名沿用原版 ToolAccessMode）。 */
export type ToolAccessMode = "manual" | "auto_approve" | "full_access";

/** 每轮发言前的工具推荐方式。 */
export type ToolRecommendMode = "off" | "agent" | "rag";

/** 网页搜索工具配置（字段名沿用原版 WebSearchSettings）。 */
export interface WebSearchSettings {
  enabled: boolean;
  provider: string;
  api_key: string;
  base_url: string;
  proxy_enabled: boolean;
  proxy_addr: string;
  max_results: number;
  hide_search_results: boolean;
}

/** 图片/视频识别工具配置（字段名沿用原版 MediaFileSettings）。 */
export interface MediaFileSettings {
  image_enabled: boolean;
  video_enabled: boolean;
  max_file_mb: number;
  image_max_edge: number;
  jpeg_quality: number;
  max_output_tokens: number;
  default_prompt: string;
}

export interface SiteSettings {
  personaId: PersonaId;
  jobId: string;
  kps: string[];
  questionCount: number;
  timeLimit: number;
  followUpDepth: number;
  voiceOn: boolean;
  customName: string;
  tone: string;
  opening: string;
  speed: number;
  model: string;
  asr: string;

  // 通用
  fontFamily: string;
  textSpeed: number;
  autoAdvanceDelay: number;
  mergeThreshold: number;
  mergeDelay: number;
  mergeMode: string;
  /** 台词合并时动作文本的处理方式：append=接在后面显示 / replace=独立显示本次动作 */
  mergeMotionMode: "append" | "replace";
  inlineAction: boolean;
  replyCount: number;
  idleRemind: boolean;
  rememberPreference: boolean;
  soundEffect: boolean;
  /** 启用永久记忆：开启后对话记忆自动压缩存档 */
  permanentMemory: boolean;
  /** 语音缓存占用（字节） */
  ttsCacheSize: number;
  /** 语音缓存文件数 */
  ttsCacheFiles: number;
  /** 局域网数据同步开关 */
  lanSyncEnabled: boolean;

  // 背景
  sceneId: string;
  starOn: boolean;
  meteorOn: boolean;
  particleFps: number;
  weather: string;
  /**
   * 房间粒子特效（原版 GameBackground 的背景特效槽）。
   * 取值：none / baparticles / blizzard / drizzle / fireflies / fireworks /
   * fog / meteorshower / rain / sakura / snow / starfield / thunderstorm
   */
  particleType: string;

  // 声音
  outputDevice: string;
  inputDevice: string;
  volumeSpeaker: number;
  volumeSfx: number;
  volumeBgm: number;
  volumeAchievement: number;
  volumeAmbient: number;
  bgmOn: boolean;
  /** 音频频谱开关（右下角迷你频谱，字段沿用原版 audio.spectrum*） */
  spectrumEnabled: boolean;
  /** 频谱形态：mirror=镜像 / bars=柱状 / ring=圆环 */
  spectrumStyle: string;
  /** 频谱配色 id（constants/spectrum.ts） */
  spectrumPalette: string;
  /** 自定义配色主色 */
  spectrumColor1: string;
  /** 自定义配色辅色 */
  spectrumColor2: string;
  bgmTrack: string;
  /** 背景音乐播放模式：loop-list=列表循环 / loop-single=单曲循环 / random=随机播放 */
  bgmMode: "loop-list" | "loop-single" | "random";
  ambientOn: boolean;
  ambientTrack: string;
  /** 环境音播放轨道列表 */
  ambientTracks: AmbientTrack[];
  ttsOn: boolean;
  ttsType: string;
  ttsSpeed: number;
  ttsVoice: string;
  ttsPitch: number;
  ttsVolume: number;
  ttsLanguage: string;
  ttsEmotion: string;
  ttsApiUrl: string;
  ttsApiKey: string;
  ttsPort: number;
  ttsModel: string;
  ttsSpeakerId: number;
  ttsStyleId: number;
  ttsReferenceAudio: string;
  ttsReferenceText: string;
  ttsAudioFormat: string;
  ttsDialect: string;
  ttsLengthScale: number;
  ttsSdpRatio: number;
  ttsNoiseScale: number;
  ttsTimeout: number;
  ttsConcurrency: number;
  ttsCacheEnabled: boolean;
  ttsCacheLimit: number;
  ttsRetryCount: number;
  ttsCloneName: string;
  ttsCloneSample: string;
  ttsCloneText: string;
  ttsCloneLanguage: string;
  ttsImportVoiceId: string;
  ttsStyleVectorsTarget: string;
  ttsPreviewText: string;
  ttsPreviewSdp: number;
  ttsEngineConfigs: Record<string, Record<string, string | number | boolean>>;
  asrOn: boolean;

  // 语音识别（SettingsAsr）：命名沿用原版 AsrSettings
  /** 语音输入总开关：关闭后自动监听与手动麦克风一并停用 */
  voice_input_enabled: boolean;
  /** 语音快捷键绑定（JSON 字符串，如 {"key":"f8"}） */
  ptt_key: string;
  /** 失去焦点时快捷键仍可用（全局快捷键） */
  ptt_global: boolean;
  /** 自动监听麦克风，识别到说话后自动转为文本 */
  auto_listen: boolean;
  /** 连续对话：识别完成后继续监听下一轮 */
  asr_continuous: boolean;
  /** 唤醒词开关 */
  wake_word_enabled: boolean;
  /** 唤醒词文本 */
  wake_word: string;
  /** 停止说话后等待该时长才结束录音（毫秒） */
  vad_silence_ms: number;
  /** AI 说完话后等待该时长才恢复语音触发（毫秒） */
  energy_warmup_ms: number;
  /** 详细 VAD 能量检测日志开关 */
  vad_debug_log: boolean;
  /** 识别完成后：fill_only=填入输入框 / auto_send=自动发送 */
  send_mode: "fill_only" | "auto_send";
  /** 流式识别 */
  stream_enabled: boolean;
  /** 当前识别服务商（引擎） */
  active_provider: string;
  /** 各识别服务商配置，键为 provider id */
  provider_configs: Record<string, AsrProviderConfig>;
  /** 麦克风设备 */
  asr_mic_device: string;
  /** 麦克风音量阈值（0–1） */
  asr_mic_threshold: number;
  /** 静音检测（低于阈值不送识别） */
  asr_mic_gate: boolean;
  /** 模型下载状态，键为模型 id */
  asr_model_states: Record<string, AsrModelState>;
  /** 最近一次测试识别的结果文本 */
  asr_last_result: string;

  // 工具配置（SettingsTools）：字段名沿用原版 ToolSettings
  web_search: WebSearchSettings;
  media_file: MediaFileSettings;
  groups: Record<string, boolean>;
  access_mode: ToolAccessMode;
  max_tool_rounds: number;
  tool_rag: boolean;
  tool_recommend_mode: ToolRecommendMode;

  // 投屏设置（SettingsCast）：字段名沿用原版页面状态
  enabled: boolean;
  port: number;
  fps: number;
  quality: number;
  castWidth: number;
  castHeight: number;
  vivid: boolean;
  charScale: number;
  charOffsetX: number;
  charOffsetY: number;
  dialogWidth: number;
  dialogHeight: number;
  dialogFontSize: number;
  dialogBgOpacity: number;
  dialogHidden: boolean;

  // 高级设置
  llmBaseUrl: string;
  llmKey: string;
  llmModel: string;
  temperature: number;
  maxTokens: number;
  promptPreset: string;

  // 大模型接入（SettingsLlmProviders）
  /** 已配置的服务商列表 */
  llmProviders: LlmProviderConfig[];
  /** 角色 → 服务商，null 表示对话模型未选 / 其余角色跟随对话模型 */
  llmChatProviderId: string | null;
  llmTranslateProviderId: string | null;
  llmGodAgentProviderId: string | null;
  llmVisionProviderId: string | null;
}

export const personas: Persona[] = [
  {
    id: "strict",
    name: "林工",
    title: "严格技术面",
    style: "大厂 P7 · Java 后端",
    tone: "直接、追问细节、重视原理与取舍，回答含糊会当场打断。",
    quote: "先别讲结论，讲讲你为什么这么设计，以及它的代价。",
    followUp: 3,
    speed: 1,
    avatar: "林",
    voice: "沉稳男声",
    opening: "你好，我是今天的技术面试官林工。我们直接开始，先做个一分钟自我介绍。",
  },
  {
    id: "friendly",
    name: "周老师",
    title: "温和 HR 面",
    style: "校招 HR · 综合面",
    tone: "循序渐进、鼓励表达，关注动机、协作与成长，不会刻意施压。",
    quote: "别紧张，我们就当聊聊你最近做过的项目。",
    followUp: 1,
    speed: 0.9,
    avatar: "周",
    voice: "温和女声",
    opening: "你好呀，我是周老师。今天主要想了解一下你的经历和想法，放轻松就好。",
  },
  {
    id: "pressure",
    name: "陈总",
    title: "压力面",
    style: "业务负责人 · 压力测试",
    tone: "节奏快、质疑多、连续追问，观察抗压与临场反应。",
    quote: "你说的这些我在简历上没看到，凭什么认为你做过？",
    followUp: 4,
    speed: 1.15,
    avatar: "陈",
    voice: "低沉男声",
    opening: "时间有限，直接一点。先说说你最能拿得出手的一个项目。",
  },
  {
    id: "expert",
    name: "顾博",
    title: "技术专家面",
    style: "架构师 · 深度面",
    tone: "偏架构与系统性，喜欢问边界、失败场景和长期演进。",
    quote: "如果流量涨十倍，你这个方案最先崩在哪里？",
    followUp: 2,
    speed: 1,
    avatar: "顾",
    voice: "平稳男声",
    opening: "我们聊点深的。选一个你熟悉的系统，讲讲它的整体架构。",
  },
];

export const jobs: Job[] = [
  {
    id: "java",
    name: "Java 后端",
    en: "JAVA BACKEND",
    desc: "JVM、并发、Spring、MySQL、Redis",
    knowledgePoints: [
      { id: "jvm", name: "JVM 与内存模型", group: "Java 基础" },
      { id: "collection", name: "集合与源码", group: "Java 基础" },
      { id: "concurrent", name: "并发编程", group: "Java 基础" },
      { id: "spring", name: "Spring / Spring Boot", group: "框架" },
      { id: "mysql", name: "MySQL 与索引", group: "存储" },
      { id: "redis", name: "Redis 与缓存", group: "存储" },
      { id: "mq", name: "消息队列", group: "中间件" },
      { id: "micro", name: "微服务与分布式", group: "架构" },
    ],
  },
  {
    id: "frontend",
    name: "Web 前端",
    en: "WEB FRONTEND",
    desc: "JavaScript、Vue、浏览器、工程化",
    knowledgePoints: [
      { id: "js", name: "JavaScript 基础", group: "语言" },
      { id: "ts", name: "TypeScript", group: "语言" },
      { id: "vue", name: "Vue 原理", group: "框架" },
      { id: "browser", name: "浏览器与网络", group: "基础" },
      { id: "css", name: "CSS 与布局", group: "基础" },
      { id: "build", name: "工程化与构建", group: "工程" },
      { id: "perf", name: "性能优化", group: "进阶" },
      { id: "security", name: "前端安全", group: "进阶" },
    ],
  },
];

export const questions: Question[] = [
  {
    id: "j1",
    jobId: "java",
    kp: "jvm",
    type: "技术知识",
    difficulty: "中等",
    text: "说说 JVM 的内存区域划分，哪些区域会 OOM，哪些不会。",
    reference: "程序计数器、虚拟机栈、本地方法栈、堆、方法区/元空间；PC 不会 OOM；栈会 StackOverflowError；堆和元空间会 OOM。",
    followUps: ["栈溢出和堆溢出分别怎么排查？", "元空间为什么会取代永久代？"],
  },
  {
    id: "j2",
    jobId: "java",
    kp: "concurrent",
    type: "技术知识",
    difficulty: "较难",
    text: "ConcurrentHashMap 在 JDK 1.8 里为什么放弃分段锁？它怎么保证线程安全？",
    reference: "CAS + synchronized 锁单个桶；减少锁粒度、避免 Segment 的内存与并发瓶颈；扩容协助 transfer。",
    followUps: ["size() 为什么是弱一致的？", "扩容时并发读写怎么处理？"],
  },
  {
    id: "j3",
    jobId: "java",
    kp: "spring",
    type: "项目深挖",
    difficulty: "中等",
    text: "你项目里 Spring Bean 的循环依赖是怎么解决的？什么情况下解决不了？",
    reference: "三级缓存；构造器注入、prototype、@Async 代理等场景解决不了。",
    followUps: ["三级缓存分别存什么？", "为什么要 early reference 而不是直接放成品？"],
  },
  {
    id: "j4",
    jobId: "java",
    kp: "mysql",
    type: "场景题",
    difficulty: "较难",
    text: "一张订单表 5000 万行，查询最近 7 天某个用户的订单很慢，你怎么优化？",
    reference: "联合索引 (user_id, created_at)、覆盖索引、避免函数运算、分页优化、冷热分离/归档。",
    followUps: ["为什么不建议直接加 created_at 单列索引？", "深分页怎么处理？"],
  },
  {
    id: "j5",
    jobId: "java",
    kp: "redis",
    type: "场景题",
    difficulty: "中等",
    text: "缓存穿透、击穿、雪崩分别是什么？你项目里怎么处理的？",
    reference: "穿透：空值缓存/布隆过滤器；击穿：互斥锁/逻辑过期；雪崩：过期时间打散、多级缓存、降级。",
    followUps: ["布隆过滤器有误判怎么办？", "逻辑过期和互斥锁怎么选？"],
  },
  {
    id: "j6",
    jobId: "java",
    kp: "micro",
    type: "行为题",
    difficulty: "中等",
    text: "讲一个你和其他同学/同事意见不一致的例子，最后怎么解决的？",
    reference: "STAR 结构；突出倾听、数据说服、复盘。",
    followUps: ["如果对方是你的上级，你还会坚持吗？"],
  },
  {
    id: "f1",
    jobId: "frontend",
    kp: "js",
    type: "技术知识",
    difficulty: "中等",
    text: "说说事件循环里宏任务和微任务的执行顺序，并举例说明。",
    reference: "同步 → 微任务清空 → 宏任务；Promise.then、queueMicrotask 是微任务；setTimeout 是宏任务。",
    followUps: ["async/await 的 await 后面算微任务吗？", "Node 和浏览器的事件循环差异？"],
  },
  {
    id: "f2",
    jobId: "frontend",
    kp: "vue",
    type: "技术知识",
    difficulty: "较难",
    text: "Vue 3 的响应式为什么用 Proxy 替代 Object.defineProperty？",
    reference: "可监听新增/删除属性、数组索引、Map/Set；惰性代理；缺点是不支持 IE。",
    followUps: ["ref 和 reactive 的区别？", "依赖收集是怎么触发的？"],
  },
  {
    id: "f3",
    jobId: "frontend",
    kp: "browser",
    type: "场景题",
    difficulty: "中等",
    text: "从输入 URL 到页面展示，中间发生了什么？尽量完整。",
    reference: "DNS、TCP/TLS、HTTP、解析 HTML/CSS、构建 DOM/CSSOM、布局、绘制、合成。",
    followUps: ["哪些阶段会阻塞渲染？", "如何优化首屏？"],
  },
  {
    id: "f4",
    jobId: "frontend",
    kp: "perf",
    type: "场景题",
    difficulty: "较难",
    text: "一个长列表页面滚动卡顿，你会怎么定位和优化？",
    reference: "Performance 面板、长任务、虚拟列表、避免 layout thrashing、will-change、分片渲染。",
    followUps: ["虚拟列表的坑有哪些？", "怎么衡量优化效果？"],
  },
  {
    id: "f5",
    jobId: "frontend",
    kp: "security",
    type: "技术知识",
    difficulty: "中等",
    text: "XSS 和 CSRF 的区别是什么？分别怎么防？",
    reference: "XSS 注入脚本；CSRF 借用 cookie。转义/CSP/HttpOnly；CSRF Token/SameSite/校验 Origin。",
    followUps: ["CSP 能完全防住 XSS 吗？", "SameSite=Lax 有什么副作用？"],
  },
  {
    id: "f6",
    jobId: "frontend",
    kp: "build",
    type: "行为题",
    difficulty: "中等",
    text: "你做过哪些工程化的事情？带来了什么可量化的收益？",
    reference: "构建提速、规范、CI、监控；要有数字。",
    followUps: ["如果团队不愿意配合，你怎么推动？"],
  },
];

export const defaultSettings: SiteSettings = {
  personaId: "strict",
  jobId: "java",
  kps: ["jvm", "concurrent", "spring", "mysql", "redis"],
  questionCount: 6,
  timeLimit: 25,
  followUpDepth: 2,
  voiceOn: false,
  customName: "",
  tone: "",
  opening: "",
  speed: 1,
  model: "deepseek-chat",
  asr: "阿里云 · 一句话识别",

  // 通用
  fontFamily: "",
  textSpeed: 80,
  autoAdvanceDelay: 1000,
  mergeThreshold: 55,
  mergeDelay: 200,
  mergeMode: "inline",
  mergeMotionMode: "append",
  inlineAction: false,
  replyCount: 1,
  idleRemind: false,
  rememberPreference: true,
  soundEffect: true,
  permanentMemory: false,
  ttsCacheSize: 48 * 1024 * 1024,
  ttsCacheFiles: 126,
  lanSyncEnabled: false,

  // 背景
  sceneId: "classroom",
  starOn: true,
  meteorOn: true,
  particleFps: 60,
  weather: "none",
  particleType: "none",

  // 声音
  outputDevice: "",
  inputDevice: "default",
  volumeSpeaker: 80,
  volumeSfx: 80,
  volumeBgm: 80,
  volumeAchievement: 80,
  volumeAmbient: 70,
  bgmOn: true,
  bgmTrack: "",
  bgmMode: "loop-single",
  spectrumEnabled: true,
  spectrumStyle: "mirror",
  spectrumPalette: "aurora",
  spectrumColor1: "#79d9ff",
  spectrumColor2: "#a78bfa",
  ambientOn: false,
  ambientTrack: "none",
  ambientTracks: [
    { id: "amb-1", name: "咖啡馆背景人声", volume: 70, loop: "list", paused: false },
    { id: "amb-2", name: "键盘敲击声", volume: 45, loop: "single", paused: false },
    { id: "amb-3", name: "窗外雨声", volume: 60, loop: "list", paused: false },
  ],
  ttsOn: false,
  ttsType: "edge-tts",
  ttsSpeed: 1,
  ttsVoice: "zh-CN-YunxiNeural",
  ttsPitch: 0,
  ttsVolume: 100,
  ttsLanguage: "zh-CN",
  ttsEmotion: "中立",
  ttsApiUrl: "http://127.0.0.1",
  ttsApiKey: "",
  ttsPort: 9880,
  ttsModel: "",
  ttsSpeakerId: 0,
  ttsStyleId: 0,
  ttsReferenceAudio: "",
  ttsReferenceText: "",
  ttsAudioFormat: "wav",
  ttsDialect: "",
  ttsLengthScale: 1,
  ttsSdpRatio: 0.2,
  ttsNoiseScale: 0.667,
  ttsTimeout: 30,
  ttsConcurrency: 2,
  ttsCacheEnabled: true,
  ttsCacheLimit: 512,
  ttsRetryCount: 2,
  ttsCloneName: "",
  ttsCloneSample: "",
  ttsCloneText: "",
  ttsCloneLanguage: "zh",
  ttsImportVoiceId: "",
  ttsStyleVectorsTarget: "local-interviewer-female",
  ttsPreviewText: "你好，欢迎参加今天的模拟面试。",
  ttsPreviewSdp: 0.2,
  ttsEngineConfigs: {
    "edge-tts": {
      language: "zh-CN",
      voice: "zh-CN-YunxiNeural",
      speed: 1,
      pitch: 0,
      volume: 100,
    },
    "gpt-sovits": {
      apiUrl: "http://127.0.0.1",
      port: 9880,
      gptModel: "GPT_weights_v2/interviewer.ckpt",
      sovitsModel: "SoVITS_weights_v2/interviewer.pth",
      referenceAudio: "",
      referenceText: "",
      language: "zh",
      emotion: "中立",
    },
    sbv2: {
      apiUrl: "http://127.0.0.1:5000",
      modelName: "interviewer",
      speakerId: 0,
      styleId: 0,
      language: "zh",
      speed: 1,
    },
    sbv2api: {
      apiUrl: "http://127.0.0.1:5000",
      modelName: "interviewer",
      speakerId: 0,
      styleId: 0,
      speed: 1,
    },
    cosyvoice: {
      apiUrl: "https://dashscope.aliyuncs.com/api/v1",
      apiKey: "",
      model: "cosyvoice-v3.5-flash",
      voiceId: "",
      language: "zh",
      dialect: "",
    },
    indextts2: {
      apiUrl: "http://127.0.0.1",
      port: 7860,
      model: "IndexTTS-2",
      voice: "interviewer",
      language: "zh",
      emotion: "中立",
      referenceAudio: "",
    },
    "fish-speech": {
      apiUrl: "http://127.0.0.1:8080",
      apiKey: "",
      model: "fish-speech-1.5",
      voice: "paimeng",
      language: "zh",
      emotion: "中立",
      referenceAudio: "",
    },
    opentts: {
      apiUrl: "https://api.siliconflow.cn/v1",
      apiKey: "",
      model: "FunAudioLLM/CosyVoice2-0.5B",
      voice: "",
      language: "zh",
      audioFormat: "wav",
    },
    vits: {
      apiUrl: "http://127.0.0.1",
      port: 23456,
      speakerId: 0,
      language: "zh",
      noiseScale: 0.667,
      lengthScale: 1,
    },
    aivis: {
      apiUrl: "https://api.aivis-project.com/v1",
      apiKey: "",
      modelUuid: "",
      speakerId: 0,
      speed: 1,
      intonation: 1,
    },
    bv2: {
      apiUrl: "http://127.0.0.1:5000",
      speakerId: 0,
      styleId: 0,
      language: "zh",
      sdpRatio: 0.2,
    },
    gsv: {
      apiUrl: "http://127.0.0.1:9880",
      referenceText: "",
      referenceAudio: "",
      gptModel: "",
      sovitsModel: "",
      language: "zh",
      emotion: "中立",
    },
    "local-sbv2": {
      voiceId: "local-interviewer-female",
      speakerId: 0,
      styleId: 0,
      lengthScale: 1,
      sdpRatio: 0.2,
      fallbackModel: "",
      fallbackSpeakerId: "",
    },
  },
  asrOn: false,

  // 语音识别（SettingsAsr）
  voice_input_enabled: true,
  ptt_key: '{"key":"f8"}',
  ptt_global: false,
  auto_listen: false,
  asr_continuous: true,
  wake_word_enabled: false,
  wake_word: "你好面试官",
  vad_silence_ms: 800,
  energy_warmup_ms: 100,
  vad_debug_log: false,
  send_mode: "fill_only",
  stream_enabled: false,
  active_provider: "llama-asr",
  provider_configs: {
    "llama-asr": {
      endpoint: "http://127.0.0.1:8080",
      model: "whisper-base",
      model_path: "models/ggml-base.bin",
      model_size: "base",
      device: "cpu",
      language: "zh",
      threads: 4,
      translate: false,
    },
    "fun-asr": {
      endpoint: "http://127.0.0.1:10095",
      model: "paraformer-zh",
      model_path: "models/paraformer-zh",
      model_size: "large",
      device: "cpu",
      language: "zh",
      threads: 4,
      hotword: "",
    },
    "sense-voice": {
      endpoint: "http://127.0.0.1:50000",
      model: "sense-voice-small",
      model_path: "models/SenseVoiceSmall",
      model_size: "small",
      device: "cpu",
      language: "zh",
      threads: 2,
      vad: true,
    },
    qwen: {
      region: "beijing",
      api_keys: {},
      endpoint: "https://dashscope.aliyuncs.com/api/v1",
      ws_endpoint: "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
      model: "qwen3-asr-flash",
      language: "zh",
    },
    "web-speech": {
      language: "zh-CN",
      interim_results: true,
      continuous: false,
      model: "web-speech-zh",
    },
    "auto-vad": {
      device: "cpu",
      sensitivity: 0.5,
      min_speech_ms: 250,
      language: "zh",
      model: "vad-silero-zh",
    },
  },
  asr_mic_device: "default",
  asr_mic_threshold: 0.5,
  asr_mic_gate: true,
  asr_model_states: {
    "ggml-base": { installed: true, progress: 100 },
    "ggml-small": { installed: false, progress: 0 },
    paraformer: { installed: false, progress: 0 },
    "sense-voice-small": { installed: false, progress: 0 },
    "silero-vad": { installed: true, progress: 100 },
  },
  asr_last_result: "",

  // 工具配置（SettingsTools）
  web_search: {
    enabled: false,
    provider: "kimi",
    api_key: "",
    base_url: "",
    proxy_enabled: false,
    proxy_addr: "http://127.0.0.1:10808",
    max_results: 8,
    hide_search_results: false,
  },
  media_file: {
    image_enabled: true,
    video_enabled: true,
    max_file_mb: 100,
    image_max_edge: 2000,
    jpeg_quality: 85,
    max_output_tokens: 1024,
    default_prompt:
      "请详细识别并描述这个媒体文件的内容；如果其中包含文字、界面、人物、物体、动作或时间顺序，请准确说明。",
  },
  groups: {
    schedule: false,
    memory: false,
    character: false,
    scene: false,
    status: false,
    clock: false,
    skills: false,
    media: false,
    file_ops: false,
    command: false,
  },
  access_mode: "manual",
  max_tool_rounds: 8,
  tool_rag: true,
  tool_recommend_mode: "off",

  // 投屏设置（SettingsCast）
  enabled: false,
  port: 1470,
  fps: 15,
  quality: 80,
  castWidth: 0,
  castHeight: 0,
  vivid: false,
  charScale: 1,
  charOffsetX: 0,
  charOffsetY: 0,
  dialogWidth: 70,
  dialogHeight: 40,
  dialogFontSize: 20,
  dialogBgOpacity: 70,
  dialogHidden: false,

  // 高级设置
  llmBaseUrl: "https://api.deepseek.com",
  llmKey: "",
  llmModel: "deepseek-chat",
  temperature: 80,
  maxTokens: 2048,
  promptPreset: "default",

  // 大模型接入：默认启用 DeepSeek（本项目实际使用的对话模型）。
  // 密钥不入库：这里的 api_key 只是前端设置页的占位，真实密钥由后端 application-local.yml / 环境变量提供。
  llmProviders: [
    {
      id: "llm-deepseek-chat",
      label: "DeepSeek Chat",
      provider: "deepseek",
      model: "deepseek-chat",
      api_key: "",
      base_url: "https://api.deepseek.com",
      temperature: null,
      top_p: null,
      enable_thinking: false,
      reasoning_effort: null,
      fast_mode: false,
      support_vision: false,
      vision_compress: false,
      vision_max_edge: 2048,
      vision_jpeg_quality: 85,
    },
  ],
  llmChatProviderId: "llm-deepseek-chat",
  llmTranslateProviderId: null,
  llmGodAgentProviderId: null,
  llmVisionProviderId: null,
};

/**
 * LLM 提供商预设（快速配置），照搬原版 constants/llm-presets.ts。
 * 前端 SettingsLlmProviders.vue 遍历本数组渲染预设按钮，点击即填充表单字段。
 */
export const llmPresets: LlmPreset[] = [
  {
    key: "deepseek-flash",
    label: "DeepSeek V4.1 Flash",
    provider: "deepseek",
    model: "deepseek-flash",
    base_url: "https://api.deepseek.com",
  },
  {
    key: "deepseek-v4-pro",
    label: "DeepSeek V4 Pro",
    provider: "deepseek",
    model: "deepseek-v4-pro",
    base_url: "https://api.deepseek.com",
  },
  {
    key: "qwen-max",
    label: "通义千问 Max",
    provider: "openai",
    model: "qwen3.8-max",
    base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  },
  {
    key: "qwen-plus",
    label: "通义千问 Plus",
    provider: "openai",
    model: "qwen3.7-plus",
    base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  },
  {
    key: "qwen-flash",
    label: "通义千问 Flash",
    provider: "openai",
    model: "qwen3.8-flash",
    base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1",
  },
  {
    key: "kimi",
    label: "Kimi K2.6",
    provider: "openai",
    model: "kimi-k2.6",
    base_url: "https://api.moonshot.cn/v1",
  },
  {
    key: "zhipu-glm",
    label: "智谱 GLM",
    provider: "openai",
    model: "glm-4.7",
    base_url: "https://open.bigmodel.cn/api/paas/v4",
  },
  {
    key: "ollama",
    label: "Ollama",
    provider: "openai",
    model: "",
    base_url: "http://localhost:11434/v1",
  },
  {
    key: "lmstudio",
    label: "LM Studio",
    provider: "lmstudio",
    model: "",
    base_url: "http://localhost:1234/v1",
  },
  {
    key: "codex",
    label: "OpenAI Codex",
    provider: "codex",
    model: "gpt-5.6-sol",
    base_url: "",
  },
];

export const dimensionNames = ["技术正确性", "知识深度", "逻辑严谨", "岗位匹配", "表达呈现"];

// ============ 面试印象（原好感度系统，B7 房间附加层数据源） ============

/** 单条面试印象变化记录 */
export interface AffectionHistoryEntry {
  time: string;
  /** 本次变化量（正=印象变好，负=变差） */
  delta: number;
  /** 变化后的六维平均值 */
  value: number;
  reason: string;
}

/** 面试印象当前值（0-100，六维平均值；允许临时溢出/为负用于演示满溢与疏离态） */
export const affection = 62;

/** 面试印象变化历史（按时间升序） */
export const affectionHistory: AffectionHistoryEntry[] = [
  { time: "13:47", delta: 8, value: 58, reason: "订单与库存一致性方案有真实实践与数据" },
  { time: "13:55", delta: -4, value: 54, reason: "联合索引最左前缀答错后经追问改口" },
  { time: "13:58", delta: 2, value: 56, reason: "自动装配答出关键注解，条件顺序欠佳" },
  { time: "14:02", delta: 6, value: 62, reason: "Full GC 排查链路完整，表达清晰" },
];

export const mockRecords: InterviewRecord[] = [
  {
    id: "r-1024",
    jobName: "Java 后端",
    personaName: "林工 · 严格技术面",
    date: "2026-10-18 20:14",
    duration: "23 分 40 秒",
    score: 82,
    dimensions: [
      { name: "技术正确性", score: 84 },
      { name: "知识深度", score: 76 },
      { name: "逻辑严谨", score: 86 },
      { name: "岗位匹配", score: 88 },
      { name: "表达呈现", score: 79 },
    ],
    questions: [
      {
        id: "q1",
        question: "ConcurrentHashMap 在 1.8 里为什么放弃分段锁？",
        kp: "并发编程",
        score: 86,
        comment: "能讲到 CAS + synchronized 锁桶，方向正确；扩容协助只提了一句。",
        suggest: "补一下 size() 弱一致性和 transfer 的并发处理。",
      },
      {
        id: "q2",
        question: "订单表 5000 万行，最近 7 天查询很慢怎么优化？",
        kp: "MySQL 与索引",
        score: 78,
        comment: "联合索引方向对，但漏了深分页和冷热分离。",
        suggest: "按「索引 → 分页 → 归档」三层回答，并给出执行计划验证。",
      },
      {
        id: "q3",
        question: "缓存穿透、击穿、雪崩分别怎么处理？",
        kp: "Redis 与缓存",
        score: 84,
        comment: "三类都覆盖，布隆过滤器与互斥锁说得清楚。",
        suggest: "补一个你项目里的真实参数与效果。",
      },
    ],
    advice: [
      "技术深度是当前最大短板：原理类问题要能讲到「为什么」和「代价」。",
      "回答结构建议固定为「结论 → 方案 → 取舍 → 验证」。",
      "项目题准备 2 个可量化的案例，避免只说「做了」。",
    ],
  },
  {
    id: "r-1018",
    jobName: "Web 前端",
    personaName: "顾博 · 技术专家面",
    date: "2026-10-16 19:02",
    duration: "19 分 12 秒",
    score: 76,
    dimensions: [
      { name: "技术正确性", score: 80 },
      { name: "知识深度", score: 72 },
      { name: "逻辑严谨", score: 78 },
      { name: "岗位匹配", score: 74 },
      { name: "表达呈现", score: 76 },
    ],
    questions: [
      {
        id: "q1",
        question: "Vue 3 为什么用 Proxy 替代 defineProperty？",
        kp: "Vue 原理",
        score: 82,
        comment: "新增/删除、数组索引都提到了；惰性代理没展开。",
        suggest: "补 ref/reactive 的依赖收集路径。",
      },
      {
        id: "q2",
        question: "长列表滚动卡顿怎么定位和优化？",
        kp: "性能优化",
        score: 70,
        comment: "知道虚拟列表，但缺少定位过程与指标。",
        suggest: "按「Performance 面板 → 长任务 → 虚拟列表 → 验证」回答。",
      },
    ],
    advice: [
      "性能题要带指标：FPS、长任务时长、首屏时间。",
      "准备一个工程化案例，讲清楚收益数字。",
    ],
  },
  {
    id: "r-1012",
    jobName: "Java 后端",
    personaName: "周老师 · 温和 HR 面",
    date: "2026-10-12 15:30",
    duration: "16 分 05 秒",
    score: 88,
    dimensions: [
      { name: "技术正确性", score: 82 },
      { name: "知识深度", score: 80 },
      { name: "逻辑严谨", score: 86 },
      { name: "岗位匹配", score: 90 },
      { name: "表达呈现", score: 92 },
    ],
    questions: [
      {
        id: "q1",
        question: "讲一个你和同学意见不一致的例子。",
        kp: "行为题",
        score: 90,
        comment: "STAR 结构完整，有倾听和复盘。",
        suggest: "补一下最终的数据结果。",
      },
      {
        id: "q2",
        question: "为什么选择 Java 后端方向？",
        kp: "动机",
        score: 88,
        comment: "动机清晰，有项目支撑。",
        suggest: "可以再具体到某个技术点带来的成就感。",
      },
    ],
    advice: ["表达自然、结构清楚，保持即可。", "技术深度再补一层，综合面会更稳。"],
  },
];

/**
 * 场景（面试间背景）。图片取自原版 lingchat 的 data/game_data/backgrounds/：
 *   占卜摊2.webp -> booth.webp、夜晚.webp -> night.webp、白天.webp -> day.webp
 * （改成 ASCII 文件名避免 URL 编码问题，文件放在 frontend/public/backgrounds/）
 * 分类表里的「室内 / 室外」落在 location_type；category 为子文件夹，均为根目录。
 */
export const scenes: SceneInfo[] = [
  {
    id: "sc-01",
    scene_name: "占卜摊2",
    scene_description: "原版默认场景",
    background: "/backgrounds/booth.webp",
    category: null,
    source: "game",
    location_type: "indoor",
    favored: false,
  },
  {
    id: "sc-02",
    scene_name: "夜晚",
    scene_description: "夜色下的场景",
    background: "/backgrounds/night.webp",
    category: null,
    source: "game",
    location_type: "outdoor",
    favored: false,
  },
  {
    id: "sc-03",
    scene_name: "白天",
    scene_description: "白天场景",
    background: "/backgrounds/day.webp",
    category: null,
    source: "game",
    location_type: "outdoor",
    favored: false,
  },
];

export const workshopItems = [
  {
    id: "w1",
    title: "大厂 Java 后端 · 压力面",
    author: "林工",
    tags: ["Java", "压力面", "高频"],
    desc: "JVM、并发、MySQL、Redis 全链路追问，适合冲刺。",
    uses: 1284,
    score: 4.8,
  },
  {
    id: "w2",
    title: "前端校招 · 基础面",
    author: "顾博",
    tags: ["前端", "校招", "基础"],
    desc: "JS、浏览器、Vue、性能，覆盖前端校招核心考点。",
    uses: 962,
    score: 4.7,
  },
  {
    id: "w3",
    title: "HR 综合面 · 行为题",
    author: "周老师",
    tags: ["HR", "行为题", "综合"],
    desc: "动机、协作、抗压、职业规划，适合综合面热身。",
    uses: 743,
    score: 4.6,
  },
  {
    id: "w4",
    title: "算法岗 · 项目深挖",
    author: "匿名",
    tags: ["算法", "项目", "深挖"],
    desc: "从简历项目出发，连续追问技术选型与指标。",
    uses: 521,
    score: 4.5,
  },
  {
    id: "w5",
    title: "测试开发 · 场景面",
    author: "匿名",
    tags: ["测试", "场景", "自动化"],
    desc: "接口测试、自动化框架、质量保障体系。",
    uses: 388,
    score: 4.4,
  },
  {
    id: "w6",
    title: "英文技术面 · 轻量版",
    author: "Ling",
    tags: ["English", "技术面"],
    desc: "英文自我介绍与项目介绍，适合外企热身。",
    uses: 276,
    score: 4.3,
  },
];

// ============ 面试场景成就（B2 设置-成就页） ============

export interface InterviewAchievement {
  id: string;
  title: string;
  description: string;
  /** normal=普通 / rare=稀有 */
  type: "normal" | "rare";
  /** 隐藏成就：未解锁时只显示 ??? */
  hidden: boolean;
  unlocked: boolean;
  current_progress: number;
  target_progress: number;
}

export const interviewAchievements: InterviewAchievement[] = [
  {
    id: "first_interview",
    title: "初次面试",
    description: "完成第一场模拟面试，迈出求职第一步",
    type: "normal",
    hidden: false,
    unlocked: true,
    current_progress: 1,
    target_progress: 1,
  },
  {
    id: "three_in_row",
    title: "连面三场",
    description: "一天之内连续完成 3 场不同岗位的模拟面试",
    type: "normal",
    hidden: false,
    unlocked: true,
    current_progress: 3,
    target_progress: 3,
  },
  {
    id: "perfect_answer",
    title: "满分回答",
    description: "任意一道面试题获得满分评价",
    type: "rare",
    hidden: false,
    unlocked: true,
    current_progress: 1,
    target_progress: 1,
  },
  {
    id: "comeback",
    title: "逆风翻盘",
    description: "上半场评分不及格，最终总评仍达到合格线",
    type: "rare",
    hidden: false,
    unlocked: true,
    current_progress: 1,
    target_progress: 1,
  },
  {
    id: "early_bird",
    title: "早起的鸟儿",
    description: "在早上 8 点前开始一场模拟面试",
    type: "normal",
    hidden: false,
    unlocked: true,
    current_progress: 1,
    target_progress: 1,
  },
  {
    id: "steady_hand",
    title: "稳如泰山",
    description: "连续 5 道题的单题评分都不低于 8 分",
    type: "normal",
    hidden: false,
    unlocked: false,
    current_progress: 3,
    target_progress: 5,
  },
  {
    id: "resume_master",
    title: "简历优化师",
    description: "根据面试报告的建议修改简历 5 次",
    type: "normal",
    hidden: false,
    unlocked: false,
    current_progress: 2,
    target_progress: 5,
  },
  {
    id: "all_jobs",
    title: "全岗位制霸",
    description: "完成全部 4 个岗位方向的模拟面试",
    type: "normal",
    hidden: false,
    unlocked: false,
    current_progress: 3,
    target_progress: 4,
  },
  {
    id: "hundred_questions",
    title: "百题斩",
    description: "累计回答 100 道面试题",
    type: "normal",
    hidden: false,
    unlocked: false,
    current_progress: 64,
    target_progress: 100,
  },
  {
    id: "offer_collector",
    title: "offer 收割机",
    description: "累计拿到 3 个模拟 offer",
    type: "rare",
    hidden: false,
    unlocked: false,
    current_progress: 1,
    target_progress: 3,
  },
  {
    id: "night_owl",
    title: "深夜刷题人",
    description: "在深夜 23 点后完成一场模拟面试",
    type: "normal",
    hidden: true,
    unlocked: false,
    current_progress: 0,
    target_progress: 1,
  },
  {
    id: "final_boss",
    title: "终极面试官",
    description: "打穿「严格三轮」剧本的全部章节并通过 AI 终审",
    type: "rare",
    hidden: true,
    unlocked: false,
    current_progress: 0,
    target_progress: 1,
  },
];

/* ==========================================================================
 * 永久记忆调试（SettingsMemoryDebug）
 * 字段命名沿用原版 references/lingchat/src/api/services/memory-debug.ts：
 * MemoryDebugRole / MemorySectionStat / MemorySystemSnapshot / RoleMemorySnapshot
 * ========================================================================== */

/** 记忆库分区 key，与原版 GameMemoryBankData 的四个字段一致 */
export type MemorySectionKey = "short_term" | "long_term" | "user_info" | "promises";

/** 条目重要度：原版按重要度过滤记忆条目 */
export type MemoryImportance = "high" | "medium" | "low";

export interface MemoryDebugRole {
  roleId: number;
  displayName: string;
  /** 运行时是否已创建 */
  runtimePresent: boolean;
  enabled: boolean;
}

export interface MemorySectionStat {
  key: MemorySectionKey;
  /** 注入上限（字），0 = 不截断 */
  limit: number;
  /** 注入时会被截断 */
  truncated: boolean;
}

export interface MemoryEntry {
  id: string;
  /** 所属分区 */
  section: MemorySectionKey;
  /** 摘要：列表与注入视图里显示的那段 */
  summary: string;
  /** 原文：展开后看到的压缩前原文 */
  content: string;
  importance: MemoryImportance;
  /** 权重 0–1：注入时的排序权重 */
  weight: number;
  /** 命中次数：被检索命中的次数 */
  hitCount: number;
  /** 创建时间 */
  createdAt: string;
  /** 该条记忆占用的 token */
  tokens: number;
}

export interface MemoryDebugSnapshot {
  displayName: string;
  /** 已累积的可见台词数（压缩触发进度） */
  accumulatedVisibleCount: number;
  updateInterval: number;
  enabled: boolean;
  aiName: string;
  isUpdating: boolean;
  hasPending: boolean;
  failCount: number;
  cooldownRemainingMs: number;
  recentWindow: number;
  pointerRaw: number;
  pointerEffective: number;
  lineCount: number;
  historyRevision: number;
  pointerOutOfRange: boolean;
  sections: MemorySectionStat[];
  bank: {
    schema_version: number;
    meta: { last_processed_global_idx: number; updated_at: string };
    data: Record<MemorySectionKey, string>;
  };
  injectedSystemText: string;
  injectedShortTermText: string;
  /** 压缩次数 */
  compressCount: number;
  /** 记忆占用 token */
  tokenUsed: number;
  /** 上次压缩时间 */
  lastCompressedAt: string;
  /** 真实上下文（下一次请求的基础） */
  context: { role: string; content: string; tool_call_id?: string; tool_calls?: unknown[] }[];
  entries: MemoryEntry[];
}

export interface MemoryDebugOverview {
  currentRoleId: number | null;
  activeSavePersistent: boolean;
  activeSaveId: string;
  roles: MemoryDebugRole[];
}

export const memoryDebugOverview: MemoryDebugOverview = {
  currentRoleId: 1,
  activeSavePersistent: true,
  activeSaveId: "A217",
  roles: [
    { roleId: 1, displayName: "风雪 · 严格技术面", runtimePresent: true, enabled: true },
    { roleId: 2, displayName: "松子 · 压力面", runtimePresent: true, enabled: true },
    { roleId: 3, displayName: "钦灵 · 温和 HR 面", runtimePresent: true, enabled: false },
    { roleId: 4, displayName: "DeepSeek · 技术专家面", runtimePresent: false, enabled: false },
  ],
};

export const memoryDebugSnapshots: Record<number, MemoryDebugSnapshot> = {
  1: {
    displayName: "风雪 · 严格技术面",
    accumulatedVisibleCount: 186,
    updateInterval: 250,
    enabled: true,
    aiName: "风雪",
    isUpdating: false,
    hasPending: true,
    failCount: 0,
    cooldownRemainingMs: 0,
    recentWindow: 30,
    pointerRaw: 152,
    pointerEffective: 152,
    lineCount: 168,
    historyRevision: 12,
    pointerOutOfRange: false,
    sections: [
      { key: "short_term", limit: 1200, truncated: false },
      { key: "long_term", limit: 2400, truncated: true },
      { key: "user_info", limit: 800, truncated: false },
      { key: "promises", limit: 0, truncated: false },
    ],
    bank: {
      schema_version: 3,
      meta: { last_processed_global_idx: 152, updated_at: "2026-10-21 14:08:36" },
      data: {
        short_term:
          "【第 12 轮】问：MySQL 联合索引 (a,b,c) 在 where b=1 时能否命中？候选人答「不能，最左前缀」，追问覆盖索引后改口，回答一般。\n【第 13 轮】问：Spring Boot 自动装配原理。候选人答出 @EnableAutoConfiguration + spring.factories，但说不清条件注解的生效顺序。\n【第 14 轮】问：如何排查线上 Full GC 频繁。候选人给出 jstat / jmap 流程，能说到 MAT 分析支配树，表达清晰。",
        long_term:
          "候选人应聘 Java 后端岗，简历标注 3 年经验，熟悉 Spring Boot / MyBatis / Redis。\n自我介绍提到在电商团队负责订单与库存模块，QPS 峰值约 3000。\n技术底色偏工程实现，理论细节（JVM 内存模型、索引底层结构）答得浅，需要追问才能展开。\n沟通风格偏稳，不会抢话，被追问时先确认题意再作答。",
        user_info:
          "求职者：后端工程师，目标岗位 Java 后端（深圳）。\n技术栈：Java 11 / Spring Boot 2.7 / MySQL 8 / Redis 6 / RabbitMQ。\n期望薪资区间未主动提及，只说「看平台」。",
        promises: "本场为模拟面试，结束后会给出逐题点评与能力雷达图。",
      },
    },
    injectedSystemText:
      "[永久记忆 · 长期经历]\n候选人应聘 Java 后端岗，简历标注 3 年经验，熟悉 Spring Boot / MyBatis / Redis……（已按 2400 字上限截断）\n\n[永久记忆 · ta 的信息]\n求职者：后端工程师，目标岗位 Java 后端（深圳）。技术栈：Java 11 / Spring Boot 2.7 / MySQL 8 / Redis 6 / RabbitMQ。",
    injectedShortTermText:
      "[近期回顾] 第 12 轮：联合索引最左前缀，回答一般；第 13 轮：自动装配原理，能答出关键点；第 14 轮：Full GC 排查，回答清晰。",
    compressCount: 12,
    tokenUsed: 2684,
    lastCompressedAt: "2026-10-21 14:08:36",
    context: [
      { role: "system", content: "你是严格技术面试官「风雪」，只问 Java 后端问题，回答不达标就追问。" },
      { role: "user", content: "我熟悉 Spring Boot，做过订单和库存模块。" },
      { role: "assistant", content: "订单与库存的一致性怎么保证？先说你用过哪种方案。" },
      { role: "user", content: "用本地事务加消息队列，最终一致。" },
      { role: "assistant", content: "消息丢了怎么办？" },
      {
        role: "tool",
        content: "",
        tool_call_id: "call_9f2c",
        tool_calls: [{ name: "web_search", arguments: { query: "RocketMQ 事务消息 回查" } }],
      },
    ],
    entries: [
      {
        id: "m-1-01",
        section: "user_info",
        summary: "候选人在 Java 后端岗，熟悉 Spring Boot",
        content:
          "候选人开场自述：应聘 Java 后端开发岗，工作 3 年，主力框架 Spring Boot 2.7，ORM 用 MyBatis-Plus，缓存用 Redis。简历里的项目集中在订单与库存。",
        importance: "high",
        weight: 0.92,
        hitCount: 14,
        createdAt: "2026-10-21 13:42:10",
        tokens: 186,
      },
      {
        id: "m-1-02",
        section: "short_term",
        summary: "上轮追问过索引优化，回答一般",
        content:
          "第 12 轮追问 MySQL 联合索引 (a,b,c) 在 where b=1 时能否命中。候选人先答「不能，违反最左前缀」，追问覆盖索引与索引下推后改口，最终表述为「看优化器是否选择索引扫描」，回答一般。",
        importance: "medium",
        weight: 0.61,
        hitCount: 5,
        createdAt: "2026-10-21 13:55:28",
        tokens: 152,
      },
      {
        id: "m-1-03",
        section: "short_term",
        summary: "Spring Boot 自动装配：答出关键注解，说不清条件顺序",
        content:
          "第 13 轮问自动装配原理。候选人答出 @EnableAutoConfiguration、spring.factories / AutoConfiguration.imports，但说不清 @ConditionalOnMissingBean 与 @ConditionalOnClass 的判定顺序。",
        importance: "medium",
        weight: 0.66,
        hitCount: 7,
        createdAt: "2026-10-21 13:58:44",
        tokens: 164,
      },
      {
        id: "m-1-04",
        section: "long_term",
        summary: "Full GC 排查流程完整，表达清晰（加分项）",
        content:
          "第 14 轮问线上 Full GC 频繁如何排查。候选人给出 jstat -gcutil 观察 → jmap -dump 导出 → MAT 看支配树定位大对象的完整链路，并补充了先保留现场再重启的注意点，表达清晰。",
        importance: "high",
        weight: 0.88,
        hitCount: 9,
        createdAt: "2026-10-21 14:02:03",
        tokens: 198,
      },
      {
        id: "m-1-05",
        section: "long_term",
        summary: "订单与库存一致性方案：本地事务 + MQ 最终一致",
        content:
          "候选人描述的下单链路：扣库存走本地事务，订单落库后发 MQ 通知库存服务，消费端做幂等；提到用唯一索引 + 消费表去重。未说明事务消息与本地消息表的取舍。",
        importance: "high",
        weight: 0.83,
        hitCount: 11,
        createdAt: "2026-10-21 13:47:52",
        tokens: 176,
      },
      {
        id: "m-1-06",
        section: "long_term",
        summary: "QPS 峰值约 3000，库存热点用 Redis 预扣减",
        content:
          "自述负责系统的 QPS 峰值约 3000，大促时库存热点商品改 Redis 预扣减 + 异步落库，但没有给出超卖兜底与对账机制。",
        importance: "medium",
        weight: 0.58,
        hitCount: 4,
        createdAt: "2026-10-21 13:49:16",
        tokens: 144,
      },
      {
        id: "m-1-07",
        section: "user_info",
        summary: "技术栈：Java 11 / MySQL 8 / Redis 6 / RabbitMQ",
        content:
          "简历与自述综合：Java 11、Spring Boot 2.7、MySQL 8、Redis 6、RabbitMQ；接触过 Kubernetes 但不熟，未做过 JVM 调优的正式项目。",
        importance: "medium",
        weight: 0.7,
        hitCount: 6,
        createdAt: "2026-10-21 13:43:05",
        tokens: 132,
      },
      {
        id: "m-1-08",
        section: "promises",
        summary: "本场结束后给出逐题点评与能力雷达图",
        content:
          "开场与候选人约定：本场为模拟面试，全部题目结束后按「技术正确性 / 知识深度 / 逻辑严谨 / 岗位匹配 / 表达呈现」五个维度出能力雷达图，并逐题给出点评。",
        importance: "high",
        weight: 0.9,
        hitCount: 3,
        createdAt: "2026-10-21 13:40:22",
        tokens: 118,
      },
      {
        id: "m-1-09",
        section: "short_term",
        summary: "被追问时习惯先复述题意再作答",
        content:
          "多轮观察：候选人在不确定时会先复述一遍问题确认边界，再开始作答；缺点是耗时，优点是少答偏。已计入表达呈现维度。",
        importance: "low",
        weight: 0.42,
        hitCount: 2,
        createdAt: "2026-10-21 14:00:37",
        tokens: 96,
      },
      {
        id: "m-1-10",
        section: "long_term",
        summary: "JVM 内存模型答得浅，只说到堆分代",
        content:
          "问 JVM 运行时数据区。候选人只答出堆的新生代 / 老年代划分，未提及元空间、直接内存与栈帧结构，追问后仍停留在「对象先到伊甸园」层面。",
        importance: "medium",
        weight: 0.55,
        hitCount: 4,
        createdAt: "2026-10-21 13:52:19",
        tokens: 138,
      },
      {
        id: "m-1-11",
        section: "short_term",
        summary: "线程池参数能背，拒绝策略说不全",
        content:
          "问 ThreadPoolExecutor 构造参数。核心线程数、最大线程数、队列、拒绝策略都能说出，但只举出 AbortPolicy，说不出 CallerRunsPolicy 的适用场景。",
        importance: "low",
        weight: 0.38,
        hitCount: 1,
        createdAt: "2026-10-21 14:04:51",
        tokens: 104,
      },
      {
        id: "m-1-12",
        section: "user_info",
        summary: "期望薪资未主动提及，只表示看平台",
        content:
          "HR 视角补记：候选人未主动给出期望薪资区间，只说「看平台和发展空间」，可在后续 HR 面中追问。",
        importance: "low",
        weight: 0.31,
        hitCount: 0,
        createdAt: "2026-10-21 13:44:58",
        tokens: 88,
      },
    ],
  },
  2: {
    displayName: "松子 · 压力面",
    accumulatedVisibleCount: 96,
    updateInterval: 250,
    enabled: true,
    aiName: "松子",
    isUpdating: true,
    hasPending: false,
    failCount: 1,
    cooldownRemainingMs: 4200,
    recentWindow: 30,
    pointerRaw: 88,
    pointerEffective: 88,
    lineCount: 92,
    historyRevision: 5,
    pointerOutOfRange: false,
    sections: [
      { key: "short_term", limit: 1200, truncated: false },
      { key: "long_term", limit: 2400, truncated: false },
      { key: "user_info", limit: 800, truncated: false },
      { key: "promises", limit: 0, truncated: false },
    ],
    bank: {
      schema_version: 3,
      meta: { last_processed_global_idx: 88, updated_at: "2026-10-21 15:21:09" },
      data: {
        short_term:
          "【压力面 · 第 4 轮】质疑候选人项目经历注水，候选人没有慌，用具体 QPS 数字回应。\n【第 5 轮】问「你说的最终一致，是不是就是会丢数据」，候选人承认边界，补充了对账任务。",
        long_term:
          "候选人在压力下表达稳定，被质疑时不急着反驳，会先要数据再作答。\n短板：系统设计题只给方案不给量化依据，容易被追问到卡壳。",
        user_info: "求职者：后端工程师，抗压自评「还行」，上一份工作 2 年 3 个月。",
        promises: "压力面结束后会单独给一份抗压能力评估。",
      },
    },
    injectedSystemText:
      "[永久记忆 · 长期经历]\n候选人在压力下表达稳定，被质疑时不急着反驳，会先要数据再作答。\n\n[永久记忆 · ta 的信息]\n求职者：后端工程师，抗压自评「还行」。",
    injectedShortTermText:
      "[近期回顾] 第 4 轮：被质疑项目注水，用 QPS 数字回应；第 5 轮：承认最终一致的丢数据边界。",
    compressCount: 5,
    tokenUsed: 1146,
    lastCompressedAt: "2026-10-21 15:21:09",
    context: [
      { role: "system", content: "你是压力面试官「松子」，持续质疑候选人的答案，逼出真实边界。" },
      { role: "user", content: "我们用的是最终一致，理论上不会有问题。" },
      { role: "assistant", content: "「理论上」？那就是说线上真的出过问题吧，说说怎么兜的。" },
    ],
    entries: [
      {
        id: "m-2-01",
        section: "long_term",
        summary: "压力下表达稳定，被质疑不急着反驳",
        content:
          "连续四轮施压观察：候选人语速略有上升但不抢话，被质疑时先要数据再作答，没有出现过情绪化回应。抗压维度给中上。",
        importance: "high",
        weight: 0.86,
        hitCount: 8,
        createdAt: "2026-10-21 15:12:40",
        tokens: 158,
      },
      {
        id: "m-2-02",
        section: "short_term",
        summary: "承认「最终一致」存在丢数据边界，补了对账任务",
        content:
          "被追问「最终一致是不是就是会丢数据」时，候选人承认存在窗口期，并补充了每日对账任务与人工补偿入口，回答比上一轮更诚实。",
        importance: "high",
        weight: 0.79,
        hitCount: 6,
        createdAt: "2026-10-21 15:18:22",
        tokens: 146,
      },
      {
        id: "m-2-03",
        section: "long_term",
        summary: "系统设计题只给方案、不给量化依据",
        content:
          "设计「秒杀」时直接给出 Redis 预扣减 + 限流，但被问「限流阈值多少、依据是什么」时答不上来，只在追问后才补了「按压测结果调」。",
        importance: "medium",
        weight: 0.52,
        hitCount: 3,
        createdAt: "2026-10-21 15:15:07",
        tokens: 132,
      },
      {
        id: "m-2-04",
        section: "user_info",
        summary: "上一份工作 2 年 3 个月，抗压自评「还行」",
        content:
          "候选人自述上一份工作在职 2 年 3 个月，离职原因是「项目停摆」；抗压能力自评「还行」，举例是连续两周的线上值守。",
        importance: "low",
        weight: 0.35,
        hitCount: 1,
        createdAt: "2026-10-21 15:10:33",
        tokens: 92,
      },
    ],
  },
  3: {
    displayName: "钦灵 · 温和 HR 面",
    accumulatedVisibleCount: 42,
    updateInterval: 250,
    enabled: false,
    aiName: "钦灵",
    isUpdating: false,
    hasPending: false,
    failCount: 0,
    cooldownRemainingMs: 0,
    recentWindow: 30,
    pointerRaw: 40,
    pointerEffective: 40,
    lineCount: 44,
    historyRevision: 2,
    pointerOutOfRange: false,
    sections: [
      { key: "short_term", limit: 1200, truncated: false },
      { key: "long_term", limit: 2400, truncated: false },
      { key: "user_info", limit: 800, truncated: false },
      { key: "promises", limit: 0, truncated: false },
    ],
    bank: {
      schema_version: 3,
      meta: { last_processed_global_idx: 40, updated_at: "2026-10-20 17:36:54" },
      data: {
        short_term:
          "【HR 面 · 第 2 轮】问离职原因，候选人答「项目停摆，想做更有挑战的事」，表述中性。\n【第 3 轮】问团队协作中的冲突，候选人举了一次排期冲突，处理方式是拉对齐会。",
        long_term:
          "候选人沟通顺畅，讲项目时能照顾到非技术听众。\n对薪资没有明确预期，需要在终面阶段确认。",
        user_info: "求职者：后端工程师，现居深圳，可接受出差但希望低频。",
        promises: "HR 面结束后会同步一份面试印象摘要给技术面。",
      },
    },
    injectedSystemText:
      "[永久记忆 · 长期经历]\n候选人沟通顺畅，讲项目时能照顾到非技术听众。\n\n[永久记忆 · ta 的信息]\n求职者：后端工程师，现居深圳。",
    injectedShortTermText: "",
    compressCount: 2,
    tokenUsed: 604,
    lastCompressedAt: "2026-10-20 17:36:54",
    context: [
      { role: "system", content: "你是温和的 HR 面试官「钦灵」，聊经历、动机与协作方式。" },
      { role: "user", content: "上一份工作项目停摆了，所以想换个环境。" },
      { role: "assistant", content: "理解～那停摆之后你在团队里做了些什么？" },
    ],
    entries: [
      {
        id: "m-3-01",
        section: "long_term",
        summary: "沟通顺畅，能照顾非技术听众",
        content:
          "讲订单系统时主动把「分布式事务」换成「下单和扣库存要么一起成、要么一起撤」，HR 视角判断其跨团队沟通成本较低。",
        importance: "medium",
        weight: 0.64,
        hitCount: 4,
        createdAt: "2026-10-20 17:30:11",
        tokens: 124,
      },
      {
        id: "m-3-02",
        section: "short_term",
        summary: "离职原因表述中性：项目停摆，想做更有挑战的事",
        content:
          "问离职原因，候选人答「项目停摆，团队被拆，想做更有挑战的事」，全程没有负面评价前东家，表述中性。",
        importance: "medium",
        weight: 0.57,
        hitCount: 2,
        createdAt: "2026-10-20 17:33:40",
        tokens: 116,
      },
      {
        id: "m-3-03",
        section: "short_term",
        summary: "团队冲突处理方式：拉对齐会，先定优先级",
        content:
          "问协作冲突，候选人举了一次排期冲突的例子：先和产品、测试拉对齐会定优先级，再砍掉非核心需求，没有升级到主管。",
        importance: "low",
        weight: 0.4,
        hitCount: 1,
        createdAt: "2026-10-20 17:35:02",
        tokens: 108,
      },
      {
        id: "m-3-04",
        section: "user_info",
        summary: "现居深圳，可接受低频出差",
        content:
          "候选人现居深圳南山，可接受出差但希望「一个月不超过一次」，无异地 relocate 意愿。",
        importance: "low",
        weight: 0.3,
        hitCount: 0,
        createdAt: "2026-10-20 17:28:19",
        tokens: 84,
      },
    ],
  },
};
