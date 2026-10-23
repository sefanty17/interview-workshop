<template>
  <!-- 游戏附加 UI 层（原版 fixed z-999 全屏、不拦截鼠标） -->
  <div class="pointer-events-none fixed top-0 left-0 z-999 h-full w-full">
    <!-- 剧本演示层：仅「剧本模式 + 对应数据存在」或显式 window.__extraDemo(true) 时渲染 -->
    <template v-if="scriptExtrasOn">
      <!-- 章节名显示（组件内部按 extraDemo.showChapter 显隐） -->
      <ChapterNameDisplay />
      <!-- Galgame 选项：真实 script:choice 优先，演示数据兜底；无选项时自身不渲染 -->
      <GameChoices @choose="(text: string) => emit('choose', text)" />
      <!-- 剧本完成庆祝（组件内部按 extraDemo.showComplete 显隐） -->
      <ScriptCompleteDisplay />
      <!-- 自由对话剩余轮数（组件内部按 isFreeDialogue 显隐） -->
      <ScriptFreeDialogueDisplay />
      <!-- 剧本图片浮层（script:present-pic，空串 = 不显示） -->
      <ScriptPicDisplay />
    </template>
    <!-- 面试印象变化飘出标签（affectionStore.lastChange 驱动，瞬时出现，保留） -->
    <AffectionChangeFloat />
    <!-- 左下角声效面板（自由模式同样需要，保留） -->
    <SoundEffectPanel />
    <!-- 右下角迷你频谱（自由模式同样需要，保留） -->
    <SpectrumVisualizer />
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AffectionChangeFloat from "./extra/AffectionChangeFloat.vue";
import ChapterNameDisplay from "./extra/ChapterNameDisplay.vue";
import GameChoices from "./extra/GameChoices.vue";
import ScriptCompleteDisplay from "./extra/ScriptCompleteDisplay.vue";
import ScriptFreeDialogueDisplay from "./extra/ScriptFreeDialogueDisplay.vue";
import ScriptPicDisplay from "./extra/ScriptPicDisplay.vue";
import SoundEffectPanel from "./extra/SoundEffectPanel.vue";
import SpectrumVisualizer from "./extra/SpectrumVisualizer.vue";
import { store } from "../../store/appStore";
import { extraDemo } from "../../store/extraDemo";

/** 选项层向上转发：真正的提交由房间视图（advance 链路）处理 */
const emit = defineEmits<{
  /** 玩家点选了某个选项，参数为选项文案 */
  choose: [text: string];
}>();

/** 剧本模式或手动演示模式才挂载剧本类附加层 */
const scriptExtrasOn = computed(
  () => store.session.mode === "script" || extraDemo.demoEnabled,
);
</script>
