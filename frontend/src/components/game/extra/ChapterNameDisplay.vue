<template>
  <div class="fixed w-full pt-8">
    <transition
      @before-enter="beforeEnter"
      @enter="enter"
      @after-enter="afterEnter"
      @before-leave="beforeLeave"
      @leave="leave"
      @after-leave="afterLeave"
    >
      <div
        v-if="showChapterName"
        class="relative w-[20%] drop-shadow-[0_8px_12px_rgba(0,0,0,0.25)]"
      >
        <div
          class="relative flex items-center overflow-hidden bg-slate-900/40 py-3 pr-14 pl-8 backdrop-blur-md"
          style="
            clip-path: polygon(0 0, calc(100% - 24px) 0, 100% 50%, calc(100% - 24px) 100%, 0 100%);
          "
        >
          <div class="bg-brand absolute top-0 bottom-0 left-0 w-1.5 rounded-2xl"></div>
          <Columns2 class="mr-4 text-white" />
          <div
            class="text-xl font-bold tracking-widest text-gray-300 drop-shadow-[0_2px_4px_rgba(0,0,0,0.8)]"
          >
            {{ displayChapterName }}
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { Columns2 } from "@lucide/vue";
import { extraDemo } from "../../../store/extraDemo";

const displayChapterName = ref("");
const showChapterName = ref(false);

// 监听章节名显示状态变化（章节名来自 data/scripts.ts 第一个剧本的第一章）
watch(
  () => extraDemo.showChapter,
  (show) => {
    if (show) {
      displayChapterName.value = extraDemo.chapterName;
      showChapterName.value = true;
    } else {
      showChapterName.value = false;
    }
  },
);

// 动画函数
function beforeEnter(el: Element) {
  const element = el as HTMLElement;
  element.style.opacity = "0";
  element.style.transform = "translateX(-20px)";
  element.style.transition = "all 0.5s cubic-bezier(0.4, 0, 0.2, 1)";
}

function enter(el: Element, done: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      const element = el as HTMLElement;
      element.style.opacity = "1";
      element.style.transform = "translateX(0)";
      setTimeout(done, 500);
    });
  });
}

function afterEnter(el: Element) {
  const element = el as HTMLElement;
  element.style.opacity = "1";
  element.style.transform = "translateX(0)";
}

function beforeLeave(el: Element) {
  const element = el as HTMLElement;
  element.style.opacity = "1";
  element.style.transform = "translateX(0)";
  element.style.transition = "all 0.5s cubic-bezier(0.4, 0, 0.2, 1)";
}

function leave(el: Element, done: () => void) {
  const element = el as HTMLElement;
  element.style.opacity = "0";
  element.style.transform = "translateX(20px)";
  setTimeout(done, 500);
}

function afterLeave(el: Element) {
  const element = el as HTMLElement;
  element.style.opacity = "0";
}
</script>
