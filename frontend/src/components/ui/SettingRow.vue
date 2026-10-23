<template>
  <div class="flex items-center justify-between gap-4 border-b border-white/[0.06] py-2 last:border-b-0">
    <div class="min-w-0">
      <div class="text-sm font-medium text-white">{{ label }}</div>
      <div v-if="hint" class="mt-0.5 text-xs font-medium text-white/60">{{ hint }}</div>
    </div>
    <div class="shrink-0"><slot /></div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ label: string; hint?: string }>();
</script>
