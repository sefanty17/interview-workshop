<template>
  <div>
    <MenuItem size="large" title="日志" icon="🧾">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex flex-wrap gap-2">
          <button
            v-for="f in filters"
            :key="f.value"
            class="cursor-pointer rounded-full border px-3 py-1 text-xs font-medium transition-colors"
            :class="
              level === f.value
                ? 'border-[#79d9ff] bg-[#79d9ff]/15 text-[#79d9ff]'
                : 'border-white/10 bg-white/[0.06] text-white/50 hover:text-white/80'
            "
            @click="level = f.value"
          >
            {{ f.label }}
          </button>
        </div>
        <div class="flex gap-2">
          <button
            class="cursor-pointer rounded-full border border-white/10 bg-white/[0.06] px-3 py-1 text-xs text-white/60 transition-colors hover:text-white"
            @click="clear"
          >
            清空
          </button>
          <button
            class="cursor-pointer rounded-full border border-white/10 bg-white/[0.06] px-3 py-1 text-xs text-white/60 transition-colors hover:text-white"
            @click="exportLog"
          >
            导出
          </button>
        </div>
      </div>

      <div class="mt-3 h-72 overflow-auto rounded-lg bg-black/35 p-3 font-mono text-xs leading-6">
        <p v-for="(l, i) in visible" :key="i" :class="levelClass(l.level)">
          [{{ l.time }}] [{{ l.level }}] {{ l.text }}
        </p>
        <p v-if="!visible.length" class="text-white/30">暂无日志</p>
      </div>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import MenuItem from "../MenuItem.vue";
import { notify } from "../../../store/appStore";

type LogLevel = "INFO" | "WARN" | "ERROR";

interface LogLine {
  time: string;
  level: LogLevel;
  text: string;
}

const filters: { value: "all" | LogLevel; label: string }[] = [
  { value: "all", label: "全部" },
  { value: "INFO", label: "信息" },
  { value: "WARN", label: "警告" },
  { value: "ERROR", label: "错误" },
];

const seed: LogLine[] = [
  { time: "12:01:03", level: "INFO", text: "面试会话已创建" },
  { time: "12:01:05", level: "INFO", text: "已载入面试官：林工 · 严格技术面" },
  { time: "12:01:08", level: "WARN", text: "麦克风权限未授予，语音识别已降级" },
  { time: "12:01:12", level: "INFO", text: "第 1 题已下发：JVM 内存区域划分" },
  { time: "12:02:41", level: "INFO", text: "用户回答已记录（128 字）" },
  { time: "12:03:02", level: "WARN", text: "回答中未检测到关键知识点「元空间」" },
  { time: "12:03:30", level: "ERROR", text: "TTS 合成失败：设备不可用" },
  { time: "12:04:15", level: "INFO", text: "面试报告生成完成，耗时 1.2s" },
];

const level = ref<"all" | LogLevel>("all");
const lines = ref<LogLine[]>([...seed]);

const visible = computed(() => (level.value === "all" ? lines.value : lines.value.filter((l) => l.level === level.value)));

function levelClass(l: LogLevel): string {
  if (l === "WARN") return "text-amber-300";
  if (l === "ERROR") return "text-red-400";
  return "text-white/70";
}

function clear() {
  lines.value = [];
  notify("日志已清空");
}

function exportLog() {
  const text = lines.value.map((l) => `[${l.time}] [${l.level}] ${l.text}`).join("\n");
  const blob = new Blob([text], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "interview-log.txt";
  a.click();
  URL.revokeObjectURL(url);
  notify("日志已导出");
}
</script>
