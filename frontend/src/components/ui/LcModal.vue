<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div v-if="show" class="fixed inset-0 z-[9999] flex items-center justify-center p-4">
        <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="onMask" />
        <div
          class="glass-effect relative z-10 w-full rounded-[2.5rem] p-8 shadow-2xl"
          :style="{ maxWidth: width }"
        >
          <div class="mb-6 flex items-center justify-between">
            <h3 class="text-xl font-black tracking-tight text-[#79d9ff]">{{ title }}</h3>
            <button
              class="cursor-pointer border-none bg-transparent p-1 text-2xl leading-none font-bold text-white/60 transition-colors hover:text-white"
              @click="emit('close')"
            >
              ×
            </button>
          </div>
          <div class="space-y-4"><slot /></div>
          <div v-if="$slots.footer" class="mt-8"><slot name="footer" /></div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
const props = withDefaults(
  defineProps<{ show: boolean; title: string; width?: string; closeOnMask?: boolean }>(),
  { width: "28rem", closeOnMask: true },
);

const emit = defineEmits<{ close: [] }>();

function onMask() {
  if (props.closeOnMask) emit("close");
}
</script>
