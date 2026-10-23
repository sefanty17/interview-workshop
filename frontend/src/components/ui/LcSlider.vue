<template>
  <div class="flex w-full min-w-[160px] items-center gap-3">
    <input
      class="lc-range min-w-0 flex-1"
      type="range"
      :min="min"
      :max="max"
      :step="step"
      :value="modelValue"
      @input="emit('update:modelValue', Number(($event.target as HTMLInputElement).value))"
    />
    <span class="w-16 shrink-0 text-right text-sm tabular-nums text-white/80">
      {{ modelValue }}{{ unit }}
    </span>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{ modelValue: number; min?: number; max?: number; step?: number; unit?: string }>(),
  { min: 0, max: 100, step: 1, unit: "" },
);

const emit = defineEmits<{ "update:modelValue": [value: number] }>();
</script>
