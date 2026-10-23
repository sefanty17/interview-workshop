<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- Toolbar -->
    <div class="mb-3 flex shrink-0 flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-1.5">
        <button
          v-for="lvl in levels"
          :key="lvl.key"
          class="lvl-btn cursor-pointer rounded-md border border-transparent px-2.5 py-[3px] text-[11px] font-semibold tracking-[0.3px] transition-all duration-200"
          :class="{ 'is-on': isLevelVisible(lvl.key) }"
          :style="{ '--lvl-color': lvl.color }"
          :title="`${isLevelVisible(lvl.key) ? '隐藏' : '显示'} ${lvl.key} 级别`"
          @click="toggleLevel(lvl.key)"
        >
          {{ lvl.label }}
        </button>
      </div>

      <div class="flex items-center gap-2">
        <span class="text-sm text-gray-400">{{ visibleCount }} / {{ logs.length }}</span>

        <button :class="ICON_BTN" title="复制当前筛选的日志" @click="copyLogs">
          <Copy :size="14" />
        </button>

        <button :class="ICON_BTN" title="导出当前筛选的日志" @click="exportLogs">
          <Download :size="14" />
        </button>

        <button
          :class="[ICON_BTN, autoScroll && ICON_BTN_ACTIVE]"
          title="自动滚动到底部"
          @click="toggleAutoScroll"
        >
          <ArrowDown :size="14" />
        </button>

        <button
          :class="[ICON_BTN, paused && ICON_BTN_ACTIVE]"
          :title="paused ? '继续' : '暂停'"
          @click="paused = !paused"
        >
          <Pause v-if="!paused" :size="14" />
          <Play v-else :size="14" />
        </button>

        <button :class="ICON_BTN" title="清空" @click="clearLogs">
          <Trash2 :size="14" />
        </button>
      </div>
    </div>

    <!-- Log area -->
    <div
      ref="logContainer"
      class="log-scroll max-h-[70dvh] min-h-0 flex-1 scrollbar-thin overflow-x-hidden overflow-y-auto rounded-xl border border-white/10 bg-black/65 px-3 py-3 font-['Cascadia_Code','Fira_Code','JetBrains_Mono','Consolas',monospace] text-[13px] leading-[1.7]"
      @scroll="handleScroll"
    >
      <div v-if="filteredLogs.length === 0" class="flex flex-1 items-center justify-center py-10">
        <div class="text-center">
          <div class="text-xl font-bold text-gray-100 opacity-60">暂无日志</div>
          <div v-if="logs.length > 0" class="mt-2 text-xs text-white/35">
            当前级别筛选下没有日志，点击上方标签重新显示
          </div>
        </div>
      </div>

      <template v-for="(entry, idx) in filteredLogs" :key="idx">
        <!-- 终端式布局：元信息内联，长文本折行后占满整行宽度 -->
        <div class="block rounded-sm py-px hover:bg-white/[0.04]">
          <span class="mr-2.5 inline-block min-w-[88px] text-xs text-white/30 tabular-nums">
            {{ entry.timestamp }}
          </span>
          <span
            class="mr-2.5 inline-block w-[38px] rounded-[3px] px-1 text-center text-[10px] leading-[18px] font-bold"
            :class="levelTagClass(entry.level)"
          >
            {{ entry.level }}
          </span>
          <span class="mr-2.5 inline text-xs wrap-anywhere text-white/40 after:content-[':']">
            {{ entry.target }}
          </span>
          <span
            class="inline break-words wrap-anywhere whitespace-pre-wrap text-white/85"
            :class="messageTintClass(entry.level)"
          >
            {{ entry.message }}
          </span>
        </div>
      </template>

      <div
        v-if="paused && pendingCount > 0"
        class="mt-3 border-t border-dashed border-yellow-500/30 pt-3 text-center text-sm text-yellow-400"
      >
        已暂停 — {{ pendingCount }} 条新日志
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { ArrowDown, Copy, Download, Pause, Play, Trash2 } from "@lucide/vue";
import { notify } from "../../../store/appStore";
import { confirmDialog } from "../../../store/dialogStore";

interface LogEntry {
  timestamp: string;
  level: "ERROR" | "WARN" | "INFO" | "DEBUG" | "TRACE";
  target: string;
  message: string;
}

type DemoRow = [LogEntry["level"], string, string];

const levels = [
  { key: "ERROR", label: "ERRO", color: "#f44747" },
  { key: "WARN", label: "WARN", color: "#e5c07b" },
  { key: "INFO", label: "INFO", color: "#98c379" },
  { key: "DEBUG", label: "DEBG", color: "#61afef" },
  { key: "TRACE", label: "TRCE", color: "#c678dd" },
];

// 等级徽章 / 消息染色（静态类名，保证 Tailwind 能扫描到）
const LEVEL_TAG_CLASSES: Record<string, string> = {
  error: "text-[#f44747] bg-[rgba(244,71,71,0.14)]",
  warn: "text-[#e5c07b] bg-[rgba(229,192,123,0.12)]",
  info: "text-[#98c379] bg-[rgba(152,195,121,0.1)]",
  debug: "text-[#61afef] bg-[rgba(97,175,239,0.12)]",
  trace: "text-[#c678dd] bg-[rgba(198,120,221,0.1)]",
};
const MESSAGE_TINT_CLASSES: Record<string, string> = {
  error: "text-[#fca5a5]!",
  warn: "text-[#fde68a]!",
  trace: "text-[rgba(255,255,255,0.45)]!",
};
const levelTagClass = (level: string) => LEVEL_TAG_CLASSES[level.toLowerCase()] ?? "";
const messageTintClass = (level: string) => MESSAGE_TINT_CLASSES[level.toLowerCase()] ?? "";

// 工具栏图标按钮
const ICON_BTN =
  "flex items-center justify-center w-7 h-7 rounded-md border-none bg-white/[0.08] text-white/60 cursor-pointer transition-all duration-200 hover:bg-white/15 hover:text-white";
const ICON_BTN_ACTIVE = "bg-[rgba(121,217,255,0.2)]! text-brand!";

const MAX_LOGS = 5000;

/** 演示日志：Java 后端格式，覆盖 5 个级别与多个模块 */
const DEMO_ROWS: DemoRow[] = [
  ["INFO", "cn.edu.zjut.interview.JavaeeInterviewApplication", "Starting JavaeeInterviewApplication v1.4.2 using Java 21.0.5 (Zulu 21.44+17)"],
  ["INFO", "cn.edu.zjut.interview.config.BootStarter", 'The following 1 profile is active: "dev"'],
  ["DEBUG", "cn.edu.zjut.interview.config.BootStarter", "Tomcat initialized with port 8080 (http)"],
  ["INFO", "cn.edu.zjut.interview.config.TomcatWebServer", "Tomcat started on port 8080 (http) with context path '/api'"],
  ["DEBUG", "cn.edu.zjut.interview.db.HikariDataSource", "HikariPool-1 - Starting..."],
  ["INFO", "cn.edu.zjut.interview.db.HikariDataSource", "HikariPool-1 - Start completed. (pool size=10, idle=10)"],
  ["INFO", "cn.edu.zjut.interview.db.MyBatisMapperScanner", "Mapped 26 statements into 8 mapper interfaces"],
  ["WARN", "cn.edu.zjut.interview.config.EnvConfigLoader", ".env not found, falling back to data/config/application.yaml"],
  ["INFO", "cn.edu.zjut.interview.config.EnvConfigLoader", "[CONFIG] loaded 42 keys from application.yaml"],
  ["INFO", "cn.edu.zjut.interview.InterviewService", "[SESSION] creating session id=7f2c9a11 job=java-backend persona=strict"],
  ["DEBUG", "cn.edu.zjut.interview.InterviewService", "[PROMPT] system tokens=812 user tokens=146"],
  ["INFO", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] provider=deepseek model=deepseek-chat base=https://api.deepseek.com/v1"],
  ["DEBUG", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] request id=req-01HQ8X stream=true temperature=0.7 max_tokens=2048"],
  ["TRACE", "cn.edu.zjut.interview.llm.LlmClient", '[LLM] chunk#1 delta="请" latency=286ms'],
  ["TRACE", "cn.edu.zjut.interview.llm.LlmClient", '[LLM] chunk#2 delta="先做" latency=93ms'],
  ["INFO", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] stream finished tokens_in=958 tokens_out=214 elapsed=2.31s"],
  ["DEBUG", "cn.edu.zjut.interview.ws.ChatWebSocketHandler", "[WS] text frame len=486 session=7f2c9a11"],
  ["INFO", "cn.edu.zjut.interview.InterviewService", "[QUESTION] #1 kp=jvm type=concept difficulty=mid"],
  ["INFO", "cn.edu.zjut.interview.asr.AsrProvider", "[ASR] provider=aliyun-isl model=paraformer-realtime-v2"],
  ["WARN", "cn.edu.zjut.interview.asr.AsrProvider", "[ASR] microphone permission granted=false, falling back to manual input"],
  ["INFO", "cn.edu.zjut.interview.tts.TtsService", "[TTS] provider=edge-tts voice=zh-CN-YunxiNeural rate=+8%"],
  ["DEBUG", "cn.edu.zjut.interview.tts.TtsService", "[TTS] cached 6 segments in data/cache/tts (4.2 MB)"],
  ["INFO", "cn.edu.zjut.interview.memory.MemoryCompressor", "[MEMORY] window=30/250 triggers=0 skip"],
  ["DEBUG", "cn.edu.zjut.interview.db.RecordRepository", "[SQL] select * from interview_record where job_code=? order by created_at desc limit 20"],
  ["INFO", "cn.edu.zjut.interview.db.RecordRepository", "[SQL] fetched 7 rows in 12ms"],
  ["INFO", "cn.edu.zjut.interview.script.ScriptEngine", "[SCRIPT] loaded 5 chapters from data/scripts/java-backend.json"],
  ["WARN", "cn.edu.zjut.interview.script.ScriptEngine", '[SCRIPT] chapter "concurrency" has no end event, auto-appending chapter_end'],
  ["ERROR", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] upstream returned 429 rate_limit_exceeded, retry 1/3 after 1200ms"],
  ["INFO", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] retry succeeded status=200"],
  ["ERROR", "cn.edu.zjut.interview.db.RecordRepository", "java.sql.SQLIntegrityConstraintViolationException: Duplicate entry '7' for key 'interview_record.PRIMARY'"],
  ["DEBUG", "cn.edu.zjut.interview.db.RecordRepository", "[SQL] rollback transaction tx=4b91c2"],
  ["WARN", "cn.edu.zjut.interview.service.ScoreCalculator", '[SCORE] missing dimension "communication", using default weight 0.15'],
  ["INFO", "cn.edu.zjut.interview.service.ScoreCalculator", "[SCORE] total=82.5 technical=86 expression=78 logic=81"],
  ["TRACE", "cn.edu.zjut.interview.event.SessionEventBus", "[EVENT] publish SessionFinishedEvent session=7f2c9a11 listeners=3"],
  ["DEBUG", "cn.edu.zjut.interview.cache.RedisCacheClient", "[CACHE] set key=session:7f2c9a11 ttl=1800s"],
  ["INFO", "cn.edu.zjut.interview.web.SettingsController", "[HTTP] PATCH /api/settings/advance 204 in 18ms"],
  ["WARN", "cn.edu.zjut.interview.config.EnvConfigLoader", '[CONFIG] key "tts.voice_lang" is deprecated, migrate to "tts.lang"'],
  ["ERROR", "cn.edu.zjut.interview.tts.TtsService", "[TTS] synthesize failed: ConnectException: Connection refused (localhost:9880)"],
  ["INFO", "cn.edu.zjut.interview.tts.TtsService", "[TTS] fallback to cloud engine, queued 2 segments"],
  ["DEBUG", "cn.edu.zjut.interview.ws.ChatWebSocketHandler", "[WS] heartbeat ping -> pong rtt=6ms"],
  ["TRACE", "cn.edu.zjut.interview.thread.InterviewExecutor", "[POOL] active=2 pool=8 queue=0 completed=143"],
  ["INFO", "cn.edu.zjut.interview.web.ExportController", "[HTTP] GET /api/records/7/export -> interview-report.pdf (412 KB)"],
  ["WARN", "cn.edu.zjut.interview.asr.AsrProvider", "[ASR] stream stalled 1.6s, sending keepalive frame"],
  ["ERROR", "cn.edu.zjut.interview.ws.ChatWebSocketHandler", '[WS] closing session 7f2c9a11 with code=1011 reason="upstream timeout"'],
  ["INFO", "cn.edu.zjut.interview.session.SessionRegistry", "[SESSION] closed 7f2c9a11 duration=612s turns=9"],
  ["DEBUG", "cn.edu.zjut.interview.cache.RedisCacheClient", "[CACHE] evicted 12 keys (maxmemory-policy allkeys-lru)"],
  ["INFO", "cn.edu.zjut.interview.InterviewService", "[LLM] provider=deepseek model=deepseek-chat"],
  ["TRACE", "cn.edu.zjut.interview.config.EnvConfigLoader", "[CONFIG] resolve path data/config/application.yaml (exists=true)"],
];

const DEMO_TIMES = [
  "17:42:05.120", "17:42:05.164", "17:42:05.208", "17:42:05.612",
  "17:42:05.671", "17:42:05.984", "17:42:06.012", "17:42:06.240",
  "17:42:06.318", "17:42:06.402", "17:42:06.487", "17:42:06.590",
  "17:42:06.611", "17:42:06.897", "17:42:06.990", "17:42:07.021",
  "17:42:07.238", "17:42:07.339", "17:42:07.402", "17:42:07.446",
  "17:42:07.512", "17:42:07.688", "17:42:07.902", "17:42:08.010",
  "17:42:08.022", "17:42:08.301", "17:42:08.344", "17:42:09.512",
  "17:42:10.740", "17:42:10.986", "17:42:11.002", "17:42:11.180",
  "17:42:11.204", "17:42:11.338", "17:42:11.402", "17:42:11.514",
  "17:42:11.602", "17:42:12.008", "17:42:12.120", "17:42:12.384",
  "17:42:12.401", "17:42:12.560", "17:42:12.702", "17:42:13.004",
  "17:42:13.117", "17:42:13.240", "17:42:13.318", "17:42:13.402",
];

function buildDemoLogs(): LogEntry[] {
  return DEMO_ROWS.map(([level, target, message], index) => ({
    timestamp: DEMO_TIMES[index] ?? "17:42:13.402",
    level,
    target,
    message,
  }));
}

/** 运行期模拟追加的日志，用来验证暂停 / 自动滚动 */
const LIVE_ROWS: DemoRow[] = [
  ["DEBUG", "cn.edu.zjut.interview.ws.ChatWebSocketHandler", "[WS] heartbeat ping -> pong rtt=5ms"],
  ["TRACE", "cn.edu.zjut.interview.thread.InterviewExecutor", "[POOL] active=1 pool=8 queue=0 completed=144"],
  ["TRACE", "cn.edu.zjut.interview.cache.RedisCacheClient", "[CACHE] touch key=session:7f2c9a11"],
  ["WARN", "cn.edu.zjut.interview.asr.AsrProvider", "[ASR] input level low (-52 dBFS)"],
  ["INFO", "cn.edu.zjut.interview.llm.LlmClient", "[LLM] keep-alive ping 200 OK in 14ms"],
];

function nowStamp(): string {
  const d = new Date();
  const pad = (n: number, width = 2) => String(n).padStart(width, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

const logs = ref<LogEntry[]>(buildDemoLogs());
const visibleLevels = ref(new Set<string>(levels.map((l) => l.key)));
const paused = ref(false);
const pendingCount = ref(0);
const autoScroll = ref(true);
const logContainer = ref<HTMLElement | null>(null);
let liveTimer: number | null = null;
let liveIndex = 0;

const filteredLogs = computed(() =>
  logs.value.filter((e) => visibleLevels.value.has(e.level.toUpperCase())),
);
const visibleCount = computed(() => filteredLogs.value.length);

function isLevelVisible(key: string): boolean {
  return visibleLevels.value.has(key);
}

function toggleLevel(key: string): void {
  const next = new Set(visibleLevels.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  visibleLevels.value = next;
}

async function clearLogs(): Promise<void> {
  if (logs.value.length === 0) {
    notify("当前没有可清空的日志");
    return;
  }
  const ok = await confirmDialog("清空日志", `确定要清空当前全部 ${logs.value.length} 条日志吗？此操作不可撤销。`);
  if (!ok) return;
  logs.value = [];
  pendingCount.value = 0;
  notify("日志已清空");
}

function formatEntry(entry: LogEntry): string {
  return `${entry.timestamp} ${entry.level.padEnd(5)} ${entry.target} : ${entry.message}`;
}

async function copyLogs(): Promise<void> {
  const list = filteredLogs.value;
  if (list.length === 0) {
    notify("当前筛选下没有可复制的日志");
    return;
  }
  const text = list.map(formatEntry).join("\n");
  try {
    await navigator.clipboard.writeText(text);
    notify(`已复制 ${list.length} 条日志到剪贴板`);
  } catch {
    // 浏览器拒绝剪贴板权限时退回 execCommand
    const area = document.createElement("textarea");
    area.value = text;
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(area);
    notify(ok ? `已复制 ${list.length} 条日志到剪贴板` : "复制失败，浏览器拒绝了剪贴板权限");
  }
}

function exportLogs(): void {
  const list = filteredLogs.value;
  if (list.length === 0) {
    notify("当前筛选下没有可导出的日志");
    return;
  }
  const blob = new Blob([list.map(formatEntry).join("\n")], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
  const link = document.createElement("a");
  link.href = url;
  link.download = `interview-log-${stamp}.log`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
  notify(`已导出 ${list.length} 条日志`);
}

function scrollToBottom(force = false): void {
  if (!force && !autoScroll.value) return;
  nextTick(() => {
    if (logContainer.value) logContainer.value.scrollTop = logContainer.value.scrollHeight;
  });
}

function toggleAutoScroll(): void {
  autoScroll.value = !autoScroll.value;
  if (autoScroll.value) scrollToBottom(true);
  notify(autoScroll.value ? "已开启自动滚动" : "已关闭自动滚动");
}

// 用户向上滚动时暂停自动滚动，回到底部时恢复
function handleScroll(): void {
  const el = logContainer.value;
  if (!el) return;
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
}

function appendLiveLog(): void {
  if (paused.value) {
    pendingCount.value++;
    return;
  }
  const row = LIVE_ROWS[liveIndex % LIVE_ROWS.length];
  liveIndex++;
  logs.value.push({ timestamp: nowStamp(), level: row[0], target: row[1], message: row[2] });
  if (logs.value.length > MAX_LOGS) logs.value = logs.value.slice(-MAX_LOGS);
  scrollToBottom();
}

onMounted(() => {
  scrollToBottom(true);
  liveTimer = window.setInterval(appendLiveLog, 6000);
});

onUnmounted(() => {
  if (liveTimer !== null) window.clearInterval(liveTimer);
});

watch(paused, (now) => {
  if (!now && pendingCount.value > 0) {
    pendingCount.value = 0;
    scrollToBottom(true);
  }
});
</script>

<style scoped>
/* 日志滚动条染色：原为行内 :style 绑定 var(--accent-color)，按 V4 规格 A 收进 <style> */
.log-scroll {
  scrollbar-color: var(--accent-color, #79d9ff) transparent;
}

/* 级别筛选标签：与原版一致——浅灰底 + 该级别的描边/文字色，hover 抬升并发光 */
.lvl-btn {
  background: #e9ecef;
  color: #495057;
}
.lvl-btn.is-on {
  border-color: var(--lvl-color);
  color: var(--lvl-color);
  background: color-mix(in srgb, var(--lvl-color) 14%, #e9ecef);
}
.lvl-btn:hover {
  transform: translateY(-1px);
  background: var(--accent-color, #79d9ff);
  color: #fff;
  box-shadow: 0 4px 10px rgba(121, 217, 255, 0.4);
}
.lvl-btn.is-on:hover {
  background: var(--lvl-color);
  color: #fff;
}
</style>
