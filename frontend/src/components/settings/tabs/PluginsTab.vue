<template>
  <div>
    <MenuItem size="large" title="已装插件" icon="🧩">
      <div class="space-y-2">
        <div
          v-for="(p, i) in plugins"
          :key="p.name"
          class="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-3"
        >
          <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-[#79d9ff]/15 text-lg">
            {{ p.icon }}
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="truncate text-sm font-bold text-white">{{ p.name }}</span>
              <span class="shrink-0 text-xs text-white/40">{{ p.version }}</span>
            </div>
            <p class="mt-0.5 text-xs text-white/50">{{ p.source }}</p>
          </div>
          <LcToggle v-model="enabled[i]" />
        </div>
      </div>
    </MenuItem>

    <MenuItem size="small" title="从压缩包导入" icon="📦">
      <button class="btn-big cursor-not-allowed opacity-40" disabled>选择压缩包…</button>
      <p class="mt-2 text-xs text-white/40">P2 接入</p>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import MenuItem from "../MenuItem.vue";
import LcToggle from "../../ui/LcToggle.vue";

const plugins = [
  { icon: "📘", name: "知识点包 · Java 后端题库", version: "v1.2", source: "内置" },
  { icon: "🔥", name: "压力面题库包", version: "v1.0", source: "创意工坊" },
  { icon: "📄", name: "面试报告模板", version: "v0.9", source: "官方" },
];

const enabled = ref([true, true, false]);
</script>
