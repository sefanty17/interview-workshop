<template>
  <button
    class="relative h-6 w-11 shrink-0 cursor-pointer rounded-full border-none transition-colors duration-200"
    :class="modelValue ? 'bg-[#79d9ff]' : 'bg-white/20'"
    role="switch"
    :aria-checked="modelValue"
    @click="emit('update:modelValue', !modelValue)"
  >
    <span
      class="absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all duration-200"
      :class="modelValue ? 'left-[22px]' : 'left-0.5'"
    />
  </button>
</template>

<script setup lang="ts">
defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean] }>();
</script>
