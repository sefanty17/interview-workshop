<template>
  <div
    class="role-container-transition pointer-events-none absolute h-full w-full origin-[center_0%]"
    :style="layerStyle"
  >
    <!-- 情绪切换交给 ImageAcrossFade：两层轮流承载当前图与待显示图，
         新图解码完成后才做 300ms 交叉淡入（轮廓差异过大则瞬切，避免双姿势重叠）。
         旧的 <Transition> + <img v-show> 写法里 v-show 的 display:none 会盖掉 opacity 过渡，
         实际是瞬切，这里按原版改用专用组件。 -->
    <ImageAcrossFade
      v-show="visible && !!src"
      ref="imageFadeRef"
      class="absolute h-[102%] w-full"
      :class="animationClasses"
      :src="src"
      :duration="300"
      position="center bottom"
      :object-fit="objectFit"
      @animationend="emit('animation-end')"
    >
      <!-- 触摸区域叠层：必须落在 ImageAcrossFade 内部才与立绘同一个绘制盒子 -->
      <template #overlay><slot name="overlay"></slot></template>
    </ImageAcrossFade>
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";

import ImageAcrossFade from "../ui/ImageAcrossFade.vue";

withDefaults(
  defineProps<{
    src: string;
    visible?: boolean;
    layerStyle: Record<string, string>;
    animationClasses: Record<string, boolean>;
    objectFit: string;
  }>(),
  { visible: true },
);

const emit = defineEmits<{
  "animation-end": [];
}>();

const imageFadeRef = ref<InstanceType<typeof ImageAcrossFade> | null>(null);

async function waitForLoad() {
  await imageFadeRef.value?.waitForLoad();
}

defineExpose({ waitForLoad });
</script>

<style scoped>
/* 进入动画：立绘整层从下往上 12px + 淡入（原版角色入场观感） */
.role-container-transition {
  animation: role-enter 0.5s cubic-bezier(0.25, 0.8, 0.5, 1) both;
}
@keyframes role-enter {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(12px);
  }
  to {
    opacity: 1;
  }
}
</style>
