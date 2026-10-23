<template>
  <div
    v-if="showPic"
    class="pointer-events-none fixed z-1000 flex h-full w-full items-center justify-center"
    style="transform: translate(0%, -10%)"
  >
    <Transition name="fade">
      <img
        :src="currentPic"
        class="h-[40dvh] max-h-[60dvh] w-auto max-w-[80vw] object-contain"
        :style="{ transform: `scale(${presentPicScale || 1})` }"
        alt=""
      />
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { extraDemo } from "../../../store/extraDemo";
import { roomStore } from "../../../store/roomStore";

/**
 * 剧本图片浮层：真实 script:present-pic 优先（原版读 uiStore.currentPresentPic），
 * 没有时退回演示数据。空串 = 淡出收起（原版 ScriptPicDisplay.vue:29-40 同一套判断）。
 */
const currentPic = computed(() => roomStore.presentPic || extraDemo.presentPic);

/** 立绘缩放：script:present-pic 的 scale，缺省 1（原版 uiStore.currentPresentPicScale） */
const presentPicScale = computed(() =>
  roomStore.presentPic ? roomStore.presentPicScale : 1,
);

const showPic = ref(false);

// 监听图片来源变化
watch(
  currentPic,
  (newVal) => {
    if (newVal && newVal !== "") {
      showPic.value = true;
    } else {
      // 无图片时，淡出
      showPic.value = false;
    }
  },
  { immediate: true },
);
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.5s ease-in-out;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
