<template>
  <div>
    <MenuItem size="large" title="成就列表（实验）" icon="🏆">
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div
          v-for="a in achievements"
          :key="a.name"
          class="relative rounded-xl border p-4 transition-all"
          :class="a.unlocked ? 'border-[#79d9ff]/40 bg-[#79d9ff]/10' : 'border-white/10 bg-white/5 opacity-50'"
        >
          <span v-if="!a.unlocked" class="absolute right-3 top-3 text-sm">🔒</span>
          <div class="flex items-center gap-2">
            <span class="text-lg">{{ a.icon }}</span>
            <h5 class="text-sm font-bold text-white">{{ a.name }}</h5>
          </div>
          <p class="mt-1 text-xs text-white/60">{{ a.desc }}</p>
          <span
            class="mt-3 block h-1 w-6 rounded-full"
            :class="a.unlocked ? 'bg-[#79d9ff]' : 'bg-transparent'"
          ></span>
        </div>
      </div>
      <p class="mt-3 text-xs text-white/40">已解锁 {{ unlockedCount }} / {{ achievements.length }}</p>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import MenuItem from "../MenuItem.vue";

const achievements = [
  { name: "初次面试", desc: "完成第一场模拟面试", icon: "🎬", unlocked: true },
  { name: "连答三题", desc: "连续三题不卡壳", icon: "🔥", unlocked: true },
  { name: "压力面幸存者", desc: "完成一次压力面", icon: "😤", unlocked: false },
  { name: "高分通过", desc: "单场得分 ≥ 85", icon: "🎯", unlocked: false },
  { name: "七日连练", desc: "连续七天练习", icon: "📅", unlocked: false },
  { name: "题海漫游", desc: "累计完成 50 题", icon: "🌊", unlocked: false },
];

const unlockedCount = computed(() => achievements.filter((a) => a.unlocked).length);
</script>
