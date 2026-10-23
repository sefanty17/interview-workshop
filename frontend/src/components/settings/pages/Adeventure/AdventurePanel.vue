<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[10000] flex items-center justify-center p-4">
      <!-- 遮罩 -->
      <div class="absolute inset-0 bg-black/70 backdrop-blur-[4px]" @click="$emit('close')"></div>

      <!-- 面板本体 -->
      <div
        class="relative z-10 flex max-h-[85vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-white/20 bg-slate-900 shadow-2xl"
      >
        <div class="absolute top-0 right-0 left-0 h-1 bg-[#79d9ff]"></div>

        <!-- 头部：剧本名 + 概览 + 关闭 -->
        <div class="flex items-start justify-between gap-3 border-b border-white/10 px-5 py-4">
          <div class="min-w-0">
            <h3 class="truncate text-lg font-bold text-white">{{ script.script_name }}</h3>
            <p class="mt-0.5 truncate text-xs text-white/50">
              {{ script.chapters.length }} 章 · {{ totalEvents }} 事件 · {{ script.level || "通用" }}
              <span v-if="script.company_style" class="ml-1 text-white/35">· {{ script.company_style }}</span>
            </p>
          </div>
          <button
            class="flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg border border-white/10 bg-white/5 text-white/60 transition-colors hover:border-white/30 hover:bg-white/10 hover:text-white"
            title="关闭"
            @click="$emit('close')"
          >
            <X :size="18" />
          </button>
        </div>

        <!-- 章节列表 -->
        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <div class="mb-3 text-xs font-bold tracking-wide text-white/50">章节列表</div>
          <div v-if="script.chapters.length" class="space-y-2">
            <div
              v-for="ch in script.chapters"
              :key="ch.code"
              class="flex items-center justify-between rounded-xl border border-white/10 bg-white/[0.04] px-3 py-2.5 transition-colors hover:bg-white/[0.08]"
            >
              <div class="min-w-0">
                <div class="truncate text-sm font-bold text-white">{{ ch.name }}</div>
                <div class="font-mono text-[10px] text-white/40">{{ ch.code }}</div>
              </div>
              <span class="shrink-0 rounded-full bg-white/10 px-2 py-0.5 text-[11px] text-white/60">
                {{ ch.events.length }} 事件
              </span>
            </div>
          </div>
          <p v-else class="py-6 text-center text-xs text-white/40">这个剧本还没有章节</p>
        </div>

        <!-- 底部操作：开始 / 编辑 / 删除 -->
        <div class="flex gap-2 border-t border-white/10 px-5 py-4">
          <button
            class="flex-1 cursor-pointer rounded-xl border border-[#79d9ff]/40 bg-[#79d9ff]/15 px-4 py-2 text-sm font-bold text-[#79d9ff] transition-all hover:bg-[#79d9ff]/25"
            @click="startScript"
          >
            开始
          </button>
          <button
            class="flex-1 cursor-pointer rounded-xl border border-white/15 bg-white/10 px-4 py-2 text-sm font-bold text-white/85 transition-all hover:bg-white/20 hover:text-white"
            @click="editScript"
          >
            编辑
          </button>
          <button
            class="flex-1 cursor-pointer rounded-xl border border-red-500/40 bg-red-500/15 px-4 py-2 text-sm font-bold text-red-300 transition-all hover:bg-red-500/30"
            @click="removeScript"
          >
            删除
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { X } from "@lucide/vue";
import type { InterviewScript } from "../../../../data/scripts";
import { deleteScript, notify, persist } from "../../../../store/appStore";
import { confirmDialog } from "../../../../store/dialogStore";

const props = defineProps<{ script: InterviewScript }>();
const emit = defineEmits<{ (e: "close"): void }>();

const router = useRouter();

const totalEvents = computed(() =>
  props.script.chapters.reduce((sum, ch) => sum + ch.events.length, 0),
);

// 开始 / 编辑 都跳转到剧本编辑器（按任务要求）
function startScript(): void {
  emit("close");
  router.push("/script-editor");
}

function editScript(): void {
  emit("close");
  router.push("/script-editor");
}

async function removeScript(): Promise<void> {
  const ok = await confirmDialog("删除剧本", `确定删除「${props.script.script_name}」吗？删除后不可恢复。`);
  if (!ok) return;
  deleteScript(props.script.code);
  persist();
  notify("剧本已删除");
  emit("close");
}
</script>
