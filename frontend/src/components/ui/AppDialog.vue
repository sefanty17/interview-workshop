<template>
  <Teleport to="body">
    <Transition name="lc-dialog">
      <div v-if="dialog.open" class="fixed inset-0 z-[10001] flex items-center justify-center p-4">
        <div class="absolute inset-0 bg-black/70 backdrop-blur-[4px]" />
        <div
          class="relative z-10 w-full max-w-md overflow-hidden rounded-xl border border-white/20 bg-slate-900 p-8"
          @click.stop
        >
          <div class="absolute top-0 right-0 left-0 h-1 bg-[#79d9ff]" />
          <div class="mb-4 flex items-center gap-3">
            <span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-[#79d9ff]/50 text-[11px] text-[#79d9ff]">
              {{ dialog.type === "alert" ? "!" : "?" }}
            </span>
            <h3 class="text-xl font-bold text-white">{{ dialog.title }}</h3>
          </div>
          <p class="text-base leading-relaxed whitespace-pre-wrap text-white/80">{{ dialog.message }}</p>
          <div class="mt-6 flex justify-end gap-2.5 border-t border-slate-800/80 pt-4">
            <button
              v-if="dialog.type === 'confirm'"
              class="cursor-pointer rounded-xl border border-white/20 bg-transparent px-6 py-1.5 text-sm font-bold text-white/70 transition-all hover:bg-white/10 hover:text-white"
              @click="settle(false)"
            >
              取消
            </button>
            <button
              class="cursor-pointer rounded-xl border-none bg-cyan-500 px-6 py-1.5 text-sm font-bold text-white shadow-lg shadow-cyan-500/25 transition-all hover:bg-cyan-400"
              @click="settle(true)"
            >
              确定
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { dialog, settle } from "../../store/dialogStore";
</script>

<style scoped>
.lc-dialog-enter-active { transition: opacity 0.2s ease-out; }
.lc-dialog-leave-active { transition: opacity 0.15s ease-in; }
.lc-dialog-enter-from,
.lc-dialog-leave-to { opacity: 0; }
</style>
