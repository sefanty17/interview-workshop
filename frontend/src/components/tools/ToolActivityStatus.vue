<template>
  <Transition
    enter-active-class="transition-all duration-200 ease-out"
    leave-active-class="transition-all duration-200 ease-in"
    enter-from-class="opacity-0 -translate-x-1"
    leave-to-class="opacity-0 -translate-x-1"
    mode="out-in"
  >
    <div
      v-if="activity || preparing"
      :key="viewKey"
      class="pointer-events-none mr-2 hidden h-10 max-w-[min(36vw,28rem)] min-w-0 items-center xl:flex"
      role="status"
      aria-live="polite"
      :title="statusText"
    >
      <div
        class="flex min-w-0 items-center gap-1.5 text-xs font-medium tracking-wide drop-shadow-sm"
        :class="statusClass"
      >
        <LoaderCircle
          v-if="preparing || activity?.status === 'running'"
          :size="14"
          class="shrink-0 animate-spin"
        />
        <CheckCircle2 v-else-if="activity?.status === 'success'" :size="14" class="shrink-0" />
        <CircleAlert v-else :size="14" class="shrink-0" />
        <span class="truncate">{{ statusText }}</span>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { CheckCircle2, CircleAlert, LoaderCircle } from "@lucide/vue";
import {
  currentToolActivity,
  toolCallPreparing,
  type ToolActivity,
} from "../../store/toolActivity";

const activity = currentToolActivity;
// 工具调用参数的流式生成进度；存在时优先于执行状态展示
const preparing = toolCallPreparing;

const viewKey = computed(() => {
  if (preparing.value) return `preparing-${preparing.value.tool}`;
  const current = activity.value;
  return current ? `${current.callId}-${current.status}` : "idle";
});

const readTools = new Set([
  "list_skills",
  "read_skill",
  "list_files",
  "read_file",
  "ReadMediaFile",
  "glob",
  "grep",
  "memory_get_current",
  "memory_get_notes",
  "schedule_get_all",
  "character_list",
  "character_get_clothes",
  "scene_list",
  "status_get_current",
  "status_get_scene",
  "get_current_time",
]);
const updateTools = new Set([
  "schedule_add_todo",
  "schedule_update_todo",
  "schedule_delete_todo",
  "memory_add_note",
  "memory_update_note",
  "memory_delete_note",
  "character_set_clothes",
]);
const switchTools = new Set(["character_switch", "scene_switch"]);

/** 工具中文名（取自原版 zh-CN 语言包 ui.toolCalls.tools） */
const TOOL_LABELS: Record<string, string> = {
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
  memory_update_note: "更新笔记",
  memory_delete_note: "删除笔记",
  character_list: "面试官列表",
  character_switch: "切换面试官",
  character_get_clothes: "查询服装",
  character_set_clothes: "更换服装",
  scene_list: "场景列表",
  scene_switch: "切换场景",
  status_get_current: "当前状态",
  status_get_scene: "场景状态",
  list_skills: "列出技能",
  read_skill: "读取技能",
  ReadMediaFile: "识别媒体文件",
  list_files: "列出文件",
  read_file: "读取文件",
  write_file: "写入文件",
  delete_file: "删除文件",
  edit_file: "编辑文件",
  search_files: "搜索文件",
  grep_files: "搜索内容",
  glob: "Glob 查找文件",
  grep: "Grep 搜索内容",
  execute_command: "执行命令",
};

const toolLabel = computed(() => {
  const tool = activity.value?.tool ?? "";
  return (tool && TOOL_LABELS[tool]) || tool;
});

function compact(value: string): string {
  const normalized = value.replace(/\s+/g, " ").trim();
  if (normalized.length <= 44) return normalized;
  return `${normalized.slice(0, 20)}…${normalized.slice(-20)}`;
}

const target = computed(() => {
  const current = activity.value;
  if (!current) return "";
  try {
    const args = JSON.parse(current.arguments) as Record<string, unknown>;
    const keys = ["search_files", "grep_files", "glob", "grep", "web_search"].includes(current.tool)
      ? ["query", "pattern", "path"]
      : current.tool === "execute_command"
        ? ["description"]
        : ["path", "name", "text", "query"];
    for (const key of keys) {
      const value = args[key];
      if (typeof value === "string" && value.trim()) return compact(value);
    }
  } catch {
    // 参数仅用于展示，解析失败时退回工具名称。
  }
  return toolLabel.value;
});

function isBackgroundCommand(current: ToolActivity): boolean {
  if (current.tool !== "execute_command") return false;
  try {
    const args = JSON.parse(current.arguments) as Record<string, unknown>;
    return args.run_in_background === true;
  } catch {
    return false;
  }
}

function runningKey(current: ToolActivity): string {
  const tool = current.tool;
  if (tool === "write_file") return "writing";
  if (tool === "edit_file") return "editing";
  if (tool === "delete_file") return "deleting";
  if (["web_search", "search_files", "grep_files", "glob", "grep"].includes(tool))
    return "searching";
  if (tool === "execute_command") {
    if (isBackgroundCommand(current)) return "backgroundExecuting";
    return "executing";
  }
  if (updateTools.has(tool)) return "updating";
  if (switchTools.has(tool)) return "switching";
  if (readTools.has(tool)) return "reading";
  return "calling";
}

/** 状态文案模板（取自原版 zh-CN 语言包 ui.toolActivity） */
const STATUS_TPL: Record<string, string> = {
  reading: "正在读取：{target}",
  writing: "正在写入：{target}",
  editing: "正在编辑：{target}",
  deleting: "正在删除：{target}",
  searching: "正在搜索：{target}",
  executing: "正在执行：{tool}",
  backgroundExecuting: "后台执行：{target}",
  updating: "正在更新：{tool}",
  switching: "正在切换：{tool}",
  calling: "正在调用：{tool}",
  completed: "已完成：{tool}",
  failed: "调用失败：{tool}",
};

function fmt(tpl: string, vars: Record<string, string | number>): string {
  return tpl.replace(/\{(\w+)\}/g, (_, k) => String(vars[k] ?? ""));
}

const statusText = computed(() => {
  const pending = preparing.value;
  if (pending) {
    const label = TOOL_LABELS[pending.tool] ?? pending.tool;
    return fmt("正在生成：{tool}（{chars} 字）", { tool: label, chars: pending.chars });
  }
  const current = activity.value;
  if (!current) return "";
  if (current.status === "success") {
    return fmt(STATUS_TPL.completed, { tool: toolLabel.value });
  }
  if (current.status === "failure") {
    return fmt(STATUS_TPL.failed, { tool: toolLabel.value });
  }
  return fmt(STATUS_TPL[runningKey(current)] ?? STATUS_TPL.calling, {
    target: target.value,
    tool: toolLabel.value,
  });
});

const statusClass = computed(() => {
  if (preparing.value) return "text-sky-200/90";
  switch (activity.value?.status) {
    case "success":
      return "text-emerald-300/90";
    case "failure":
      return "text-amber-300/90";
    default:
      return "text-sky-200/90";
  }
});
</script>
