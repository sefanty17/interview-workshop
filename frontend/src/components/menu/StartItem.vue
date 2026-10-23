<template>
  <button
    v-bind="$attrs"
    class="start-item mt-[15px] cursor-pointer border-none bg-transparent p-[5px] text-left font-['Maoken_Assorted_Sans',-apple-system,BlinkMacSystemFont,'Segoe_UI',Roboto,'Helvetica_Neue',Arial,sans-serif] text-[clamp(40px,4vw,72px)] whitespace-nowrap text-white transition-all duration-300 ease-[cubic-bezier(0.18,0.89,0.32,1.28)] hover:not-disabled:-translate-y-2 hover:not-disabled:scale-105 hover:not-disabled:text-[#f0f0f0] disabled:cursor-not-allowed disabled:opacity-50"
  >
    <slot />
  </button>
</template>

<script setup lang="ts">
defineOptions({ inheritAttrs: false });
</script>

<style scoped>
.start-item { text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3); }
.start-item:hover:not(:disabled) { text-shadow: 0 0 6px rgba(255, 255, 255, 0.4); }
</style>
