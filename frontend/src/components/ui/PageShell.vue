<template>
  <div class="fixed inset-0 overflow-hidden text-white">
    <div class="absolute inset-0 bg-cover bg-center" :style="{ backgroundImage: `url(${bgUrl})` }" />
    <div class="absolute inset-0 bg-black/70 backdrop-blur-[4px]" />

    <div class="relative flex h-full flex-col px-6 py-5 md:px-10">
      <header class="flex shrink-0 items-center justify-between">
        <button
          class="flex cursor-pointer items-center gap-2 rounded-full border border-white/15 bg-white/10 px-4 py-1.5 text-sm text-white/85 backdrop-blur-md transition-all hover:bg-white/20 hover:text-white"
          @click="router.push(backTo)"
        >
          <span class="text-base leading-none">←</span> 返回
        </button>
        <h1 class="font-['Maoken_Assorted_Sans',sans-serif] text-xl tracking-wide text-white md:text-2xl">
          {{ title }}
        </h1>
        <div class="flex items-center gap-2">
          <slot name="actions" />
        </div>
      </header>

      <div class="mt-4 flex-1 overflow-y-auto pb-4">
        <div
          class="mx-auto w-[92%] max-w-6xl rounded-2xl border border-white/10 bg-white/[0.06] p-6 backdrop-blur-2xl md:w-[86%]"
        >
          <slot />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from "vue-router";
/** 页面背景：改用原版场景图库的白天背景（原 background2.png 已废弃） */
const bgUrl = "/backgrounds/day.webp";

withDefaults(defineProps<{ title: string; backTo?: string }>(), { backTo: "/" });

const router = useRouter();
</script>
