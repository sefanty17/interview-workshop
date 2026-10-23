<template>
  <!-- 仅自由对话模式显示：地点 + 日程 + 面试印象（并列、挨在一起） -->
  <div
    v-if="shouldShow"
    class="free-mode-tools fixed top-[calc(15px+var(--safe-area-inset-top))] left-5 z-2000 flex items-start gap-3 transition-all duration-300 ease-in-out"
  >
    <!-- 地点：显示当前场景名，点击打开场景管理提示 -->
    <Button
      type="nav"
      class="flex items-center gap-2 px-4 py-2 text-white transition-colors"
      @click="openSceneSettings"
    >
      <Signpost :size="18" class="shrink-0" />
      <h3 class="m-0 hidden text-lg font-bold xl:block">{{ sceneName }}</h3>
    </Button>

    <!-- 番茄钟暂时从顶栏撤下：组件与后台计时逻辑原样保留在 components/pomodoro/，
         计时状态持久化在 localStorage，重新挂上即按原进度续跑 -->
    <SchedulePanel />
    <AffectionPanel />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Signpost } from "@lucide/vue";
import { store } from "../../store/appStore";
import { alertDialog } from "../../store/dialogStore";
import Button from "../base/widget/Button.vue";
import SchedulePanel from "../schedule/SchedulePanel.vue";
import AffectionPanel from "./AffectionPanel.vue";

// 自由面试房间固定显示（原版判据：跑剧本时隐藏，本项目房间常为自由对话形态）
const shouldShow = computed(() => true);

// 未选场景时显示「无」，按钮仍作为进入场景管理的入口
const sceneName = computed(() => store.scenes[0]?.scene_name || "无");

const openSceneSettings = () => {
  alertDialog("场景管理", "场景管理需要后端服务，当前未接入。可在「设置 → 背景」查看场景列表。");
};
</script>
