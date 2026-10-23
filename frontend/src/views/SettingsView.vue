<template>
  <div class="fixed inset-0 z-[50] overflow-hidden text-white">
    <!-- 底层：天空背景。压暗层不再放在这里：SettingsPanel 自带原版 blur-overlay
         (rgba(0,0,0,0.7) + blur(4px))，这里再加一层 bg-black/70 会叠成 91% 黑，
         与截图不符 -->
    <div class="absolute inset-0 bg-cover bg-center" :style="{ backgroundImage: `url(${bgUrl})` }" />

    <!-- slide-from-right：原版从主菜单打开设置时，面板整体从右侧滑入（0.42s 那套曲线） -->
    <SettingsPanel slide-from-right />
  </div>
</template>

<script setup lang="ts">
import { onUnmounted } from "vue";
import SettingsPanel from "../components/settings/SettingsPanel.vue";
import { settingsUi } from "../store/settingsUi";
import bgUrl from "../assets/images/background2.png";

// 在 setup 里就置真（而非 onMounted）：SettingsPanel 的 panelVisible 取的是 setup 时的
// showSettings，晚一步的话它会先按「关闭」分支跑一遍退场、再被翻回来，入场动画会闪一下
settingsUi.showSettings = true;

onUnmounted(() => {
  settingsUi.showSettings = false;
});
</script>
