<template>
  <span class="plugin-tag" :title="tooltip">
    <Puzzle :size="11" />
    <span>{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Puzzle } from "@lucide/vue";

const props = defineProps<{
  /** 原版按 source 区分「本地」/「工坊」；本项目没有插件体系，固定「本地」 */
  source?: string | null;
  /** 覆盖条目标签（默认「本地」） */
  label?: string;
}>();

const label = computed(() => props.label ?? "本地");
const tooltip = computed(() => (props.source === "game" ? "来自本地角色列表" : "来自插件（本项目固定为本地）"));
</script>

<style scoped>
.plugin-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 6px;
  border-radius: 9999px;
  font-size: 10px;
  line-height: 1.4;
  color: #c4b5fd;
  background: rgba(139, 92, 246, 0.16);
  border: 1px solid rgba(139, 92, 246, 0.35);
  white-space: nowrap;
}
</style>
