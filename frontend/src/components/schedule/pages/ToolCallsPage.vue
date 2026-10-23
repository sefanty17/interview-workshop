<template>
  <!-- 视图：工具调用 -->
  <div
    v-if="scheduleStore.scheduleView === 'tool_calls'"
    class="grid grid-cols-1 p-1 sm:grid-cols-1 lg:grid-cols-1"
  >
    <!-- 跳转高级设置 -->
    <div class="mb-6">
      <p class="mb-3 text-sm text-gray-300">网页搜索等工具的设置已经搬到「高级设置 → 工具配置」啦</p>
      <div
        class="bg-brand inline-flex cursor-pointer items-center gap-2 rounded-lg border-none px-5 py-2.5 text-sm font-medium text-white transition-colors duration-200 hover:bg-[#0056b3]"
        @click="goToToolSettings"
      >
        <Wrench :size="16" />
        前往工具配置
      </div>
    </div>

    <!-- 调用提示（最近记录，最多 50 条） -->
    <div>
      <div class="mb-2 flex items-center justify-between">
        <h3 class="text-brand font-bold">调用提示</h3>
        <button
          v-if="scheduleStore.toolCalls.length > 0"
          class="flex cursor-pointer items-center gap-1.5 rounded-lg border border-white/20 bg-transparent px-3 py-1.5 text-xs text-white/70 transition-colors duration-200 hover:bg-white/10 hover:text-white"
          @click="clearRecords"
        >
          <Trash2 :size="14" />
          清空记录
        </button>
      </div>
      <p v-if="scheduleStore.toolCalls.length === 0" class="text-sm text-gray-400">
        还没有工具调用记录，和ta聊聊需要查资料的话题试试吧
      </p>
      <ul v-else class="space-y-2">
        <li
          v-for="(record, index) in scheduleStore.toolCalls"
          :key="index"
          class="cursor-pointer rounded-lg bg-white/5 px-3 py-2 text-sm transition-colors duration-200 hover:bg-white/10"
          @click="toggleExpand(index)"
        >
          <div class="flex items-center gap-3">
            <CheckCircle2 v-if="record.ok" :size="16" class="shrink-0 text-green-400" />
            <XCircle v-else :size="16" class="shrink-0 text-red-400" />
            <span class="shrink-0 text-gray-400">{{ record.time }}</span>
            <span class="text-brand shrink-0">{{ toolLabel(record.tool) }}</span>
            <span class="truncate text-white">{{
              record.ok ? displaySummary(record) : record.error || displaySummary(record)
            }}</span>
            <ChevronDown
              :size="14"
              class="ml-auto shrink-0 text-gray-400 transition-transform duration-200"
              :class="{ 'rotate-180': expandedIndex === index }"
            />
          </div>
          <!-- 展开详情：调用参数与返回结果 -->
          <div v-if="expandedIndex === index" class="mt-2 space-y-2 border-t border-white/10 pt-2">
            <div>
              <p class="mb-1 text-xs text-gray-400">调用参数</p>
              <pre
                class="max-h-40 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                >{{ prettyJson(record.arguments) }}</pre
              >
            </div>
            <div>
              <p class="mb-1 text-xs text-gray-400">返回结果</p>
              <pre
                class="max-h-60 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                >{{ prettyJson(record.result) }}</pre
              >
            </div>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具调用页（原版 components/schedule/pages/ToolCallsPage.vue）
 *
 * 原版记录来自 api/services/tool-settings 的 recentToolCalls（后端内存里最近 50 条），
 * 这里读 store/scheduleStore.ts 的 toolCalls；展开看参数与返回、可清空。
 */
import { ref } from "vue";
import { CheckCircle2, ChevronDown, Trash2, Wrench, XCircle } from "@lucide/vue";
import {
  scheduleStore,
  clearToolCallRecords,
  type ToolCallRecord,
} from "../../../store/scheduleStore";
import { confirmDialog } from "../../../store/dialogStore";
import { openSettings } from "../../../store/settingsUi";

/** 原版 toolDisplayName：把工具 id 换成中文名（取自语言包 ui.toolCalls.tools） */
const TOOL_NAMES: Record<string, string> = {
  web_search: "网页搜索",
  tool_search: "检索工具",
  get_current_time: "当前时间",
  schedule_get_all: "查询日程",
  schedule_add_todo: "添加待办",
  schedule_update_todo: "更新待办",
  schedule_delete_todo: "删除待办",
  memory_get_current: "读取记忆",
  memory_get_notes: "读取笔记",
  memory_add_note: "记笔记",
  ReadMediaFile: "识别媒体文件",
  list_files: "列出文件",
  read_file: "读取文件",
  write_file: "写入文件",
  execute_command: "执行命令",
};

const toolLabel = (tool: string) => TOOL_NAMES[tool] ?? tool;

// 展开详情：同一时间只展开一条记录
const expandedIndex = ref<number | null>(null);

const toggleExpand = (index: number) => {
  expandedIndex.value = expandedIndex.value === index ? null : index;
};

// 无参工具的摘要会退化成 "{}"，显示为友好文案
const displaySummary = (record: ToolCallRecord) => {
  const summary = record.summary.trim();
  return summary === "{}" || summary === "" ? "（无参数）" : record.summary;
};

// 尝试把 JSON 字符串格式化展示，失败则原样返回
const prettyJson = (raw: string) => {
  if (!raw) return "—";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

const clearRecords = async () => {
  const ok = await confirmDialog(
    "清空工具调用记录",
    `确定清空最近 ${scheduleStore.toolCalls.length} 条工具调用记录吗？清空后无法恢复。`,
  );
  if (!ok) return;
  clearToolCallRecords();
  expandedIndex.value = null;
};

// 跳转到「高级设置 → 工具配置」（设置面板由 /settings 挂载，日程弹窗打开时会被盖住）
const goToToolSettings = () => {
  openSettings("advance");
};
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供） */
.text-brand {
  color: var(--accent-color);
}
.bg-brand {
  background-color: var(--accent-color);
}
</style>
