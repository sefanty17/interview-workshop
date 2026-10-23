<template>
  <select
    class="glass-input cursor-pointer text-sm"
    :value="modelValue"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
  >
    <option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
  </select>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string | number;
  options: { value: string | number; label: string }[];
}>();

const emit = defineEmits<{ "update:modelValue": [value: string] }>();
</script>
