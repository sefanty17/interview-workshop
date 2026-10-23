<template>
  <div
    class="mx-auto flex max-h-full min-h-0 flex-1 flex-wrap items-start gap-5 overflow-y-auto px-3 py-6 text-white text-shadow-2xs"
    :style="{ width: containerWidth + '%' }"
  >
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
// 原版 MenuPage.vue 的容器宽度算法依赖 uiStore.aspectRatio + window.innerWidth。
// 本项目没有 uiStore，等价实现已在 settings/pages/useMenuPageWidth.ts（ratio 用 innerWidth/innerHeight 计算），
// 直接复用，避免两套百分比算法漂移。
import { useMenuPageWidth } from "../../settings/pages/useMenuPageWidth";

const { containerWidth } = useMenuPageWidth();
</script>
