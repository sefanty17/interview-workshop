<template>
  <div class="rounded-lg border" :class="[tone.border, tone.bg]">
    <!-- 单行紧凑头部：图标 + 工具名 + 审批按钮（仅待审批）+ 详情切换 + 状态徽标。
         原先是整卡片大留白的多行布局，压缩后 N 个工具 = N 行紧凑条目。 -->
    <div class="flex items-center gap-2 px-2.5 py-[7px]">
      <span class="text-[0.95rem] leading-none">{{ tone.emoji }}</span>
      <span class="min-w-0 truncate font-mono text-[0.8rem] text-white/90">{{ run.tool }}</span>

      <!-- 等待审批：行内小号允许/拒绝 -->
      <template v-if="run.status === 'pending'">
        <button
          class="inline-flex shrink-0 items-center rounded-md border border-emerald-400/40 bg-emerald-400/15 px-2 py-[2px] text-[0.7rem] text-emerald-300 transition-all duration-200 hover:bg-emerald-400/25"
          :title="APPROVAL_HINT"
          @click="$emit('allow')"
        >
          {{ "允许执行" }}
        </button>
        <button
          class="inline-flex shrink-0 items-center rounded-md border border-red-400/35 bg-red-400/12 px-2 py-[2px] text-[0.7rem] text-red-300 transition-all duration-200 hover:bg-red-400/25"
          @click="$emit('deny')"
        >
          {{ "拒绝" }}
        </button>
      </template>

      <!-- 详情切换（有参数或结果才出现） -->
      <button
        v-if="hasArgs || run.output"
        class="ml-auto inline-flex shrink-0 items-center gap-1 text-[0.68rem] text-white/40 transition-colors hover:text-white/80"
        @click="showDetails = !showDetails"
      >
        <span class="text-[0.6rem] leading-none">{{ showDetails ? "▾" : "▸" }}</span>
        {{ showDetails ? "收起详情" : "查看详情" }}
      </button>

      <!-- 状态徽标（running 时带转圈指示） -->
      <span
        class="inline-flex shrink-0 items-center gap-1 rounded-full border px-[7px] py-[1px] text-[0.68rem]"
        :class="statusMap[run.status].cls"
      >
        <span
          v-if="run.status === 'running'"
          class="h-2 w-2 shrink-0 animate-spin rounded-full border-[1.5px] border-current border-t-transparent"
        ></span>
        {{ statusMap[run.status].text }}
      </span>
    </div>

    <!-- 参数 / 结果展开区（保留原有内容，间距收紧） -->
    <div v-if="showDetails" class="space-y-1.5 overflow-hidden border-t border-white/8 px-2.5 py-2">
      <div v-if="hasArgs" class="rounded-lg border border-white/10 bg-black/25 px-2.5 py-1.5">
        <div class="mb-1 text-[0.68rem] text-white/40">{{ "参数" }}</div>
        <pre
          class="max-h-40 overflow-y-auto font-mono text-[0.72rem] whitespace-pre-wrap text-white/75"
          >{{ argsText }}</pre
        >
      </div>
      <div v-if="run.output" class="rounded-lg border border-white/10 bg-black/25 px-2.5 py-1.5">
        <div class="mb-1 text-[0.68rem] text-white/40">{{ "结果" }}</div>
        <pre
          class="max-h-52 overflow-y-auto font-mono text-[0.72rem] whitespace-pre-wrap text-white/75"
          >{{ run.output }}</pre
        >
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 单个工具调用的紧凑卡片（原版 `AgentToolCard.vue` 逐字搬）。
 *
 * 文案原版走 vue-i18n 的 `scriptEditor.agentTool.*`，本项目没有 vue-i18n，
 * 直接写 zh-CN 原文。唯一改动：原版 i18n 的 showDetails/hideDetails 自带尾箭头
 * （"查看详情 ▸"），而模板里另有一个箭头 span，照搬会出现双箭头，
 * 这里去掉文案里的箭头，只留 span。
 */
import { computed, ref } from "vue";
import type { ToolRun, ToolStatus } from "../../../store/agent";

const props = defineProps<{ run: ToolRun }>();
defineEmits<{ (e: "allow"): void; (e: "deny"): void }>();

/** 审批提示（原版 `scriptEditor.agentTool.approvalHint`）。 */
const APPROVAL_HINT = "命令执行前需要你的确认（120 秒内有效）";

const showDetails = ref(false);

interface Tone {
  border: string;
  bg: string;
  emoji: string;
}

const TONES: Record<string, Tone> = {
  indigo: { border: "border-indigo-400/35", bg: "bg-indigo-400/8", emoji: "📖" },
  amber: { border: "border-amber-400/35", bg: "bg-amber-400/8", emoji: "💻" },
  emerald: { border: "border-emerald-400/35", bg: "bg-emerald-400/8", emoji: "📄" },
  red: { border: "border-red-400/35", bg: "bg-red-400/8", emoji: "🗑️" },
};

const toneOf = (tool: string): string => {
  if (tool === "execute_command") return "amber";
  if (tool === "read_skill" || tool === "list_skills") return "indigo";
  if (tool === "delete_file") return "red";
  return "emerald";
};

const tone = computed<Tone>(() => TONES[toneOf(props.run.tool)] ?? TONES.emerald);

const statusMap: Record<ToolStatus, { text: string; cls: string }> = {
  running: {
    text: "执行中",
    cls: "text-amber-300 border-amber-300/30 bg-amber-300/10",
  },
  pending: {
    text: "等待审批",
    cls: "text-blue-300 border-blue-300/30 bg-blue-300/10",
  },
  done: {
    text: "完成",
    cls: "text-emerald-300 border-emerald-300/30 bg-emerald-300/10",
  },
  error: {
    text: "失败",
    cls: "text-red-300 border-red-300/30 bg-red-300/10",
  },
  denied: {
    text: "已拒绝",
    cls: "text-red-300 border-red-300/30 bg-red-300/10",
  },
};

const hasArgs = computed(() => props.run.args && Object.keys(props.run.args).length > 0);

const argsText = computed(() => {
  try {
    return JSON.stringify(props.run.args, null, 2);
  } catch {
    return String(props.run.args);
  }
});
</script>
