<template>
  <section :class="['lc-menu-item', size]">
    <div class="mb-4 flex w-full items-center space-x-2 border-b-2 border-[#79d9ff] pb-2">
      <span class="text-lg">{{ icon }}</span>
      <h4 class="m-0 font-semibold text-white" style="text-shadow: 0 0 2px rgba(0, 0, 0, 0.5)">{{ title }}</h4>
    </div>
    <div class="w-full"><slot /></div>
  </section>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ title: string; icon?: string; size?: "small" | "large" }>(), { icon: "▣", size: "large" });
</script>

<style scoped>
section { background: rgba(255, 255, 255, 0.1); border-radius: 12px; padding: 15px; margin-bottom: 20px; }
section.large { width: 100%; }
section.small { width: calc(50% - 21px); display: inline-block; vertical-align: top; margin-right: 20px; }
@media (max-width: 768px) { section.small { width: 100%; margin-right: 0; } }
</style>
