<template>
  <div v-if="totalPages > 1" class="flex items-center justify-center gap-2 pb-2">
    <button
      :disabled="modelValue <= 1"
      class="px-2 py-1 text-xs text-white/50 transition-colors hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
      @click="$emit('update:modelValue', 1)"
    >
      ← 首页
    </button>
    <button
      :disabled="modelValue <= 1"
      class="px-3 py-1 text-sm text-white/50 transition-colors hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
      @click="$emit('update:modelValue', modelValue - 1)"
    >
      上一页
    </button>
    <span class="px-3 text-xs text-white/60">第 {{ modelValue }} / {{ totalPages }} 页</span>
    <button
      :disabled="modelValue >= totalPages"
      class="px-3 py-1 text-sm text-white/50 transition-colors hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
      @click="$emit('update:modelValue', modelValue + 1)"
    >
      下一页
    </button>
    <button
      :disabled="modelValue >= totalPages"
      class="px-2 py-1 text-xs text-white/50 transition-colors hover:text-white disabled:cursor-not-allowed disabled:opacity-30"
      @click="$emit('update:modelValue', totalPages)"
    >
      末页 →
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  /** 当前页码，从 1 开始 */
  modelValue: number;
  totalPages: number;
}>();

defineEmits<{
  (e: "update:modelValue", value: number): void;
}>();
</script>
