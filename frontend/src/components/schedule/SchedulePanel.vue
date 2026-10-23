<template>
  <div class="flex flex-col gap-3">
    <Button
      v-show="!settingsUi.showSettings"
      type="nav"
      icon="schedule"
      :class="[
        'flex items-center gap-2 px-4 py-2 transition-colors',
        enabled ? 'text-[#4facfe]' : 'text-white',
      ]"
      @click="toggleEnabled"
    >
      <h3 class="m-0 hidden text-lg font-bold xl:block">日程</h3>
    </Button>

    <!-- Modal overlay -->
    <Teleport to="body">
      <Transition
        enter-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
        leave-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
        enter-from-class="opacity-0"
        leave-to-class="opacity-0"
      >
        <div
          v-if="enabled"
          class="fixed inset-0 z-[1100] flex items-center justify-center bg-black/50 backdrop-blur-sm"
        >
          <Transition
            enter-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
            leave-active-class="transition-all duration-200 cubic-bezier(0.6, -0.28, 0.74, 0.05)"
            enter-from-class="opacity-0 scale-95 translate-y-2"
            leave-to-class="opacity-0 scale-95 translate-y-2"
          >
            <div
              v-if="enabled"
              class="relative flex flex-col overflow-hidden rounded-3xl border border-white/10 shadow-[0_8px_32px_rgba(0,0,0,0.4)]"
              :class="
                roomStore.isNarrowScreen ? 'h-[85dvh] w-[95vw]' : 'h-[80dvh] w-[80vw] max-w-[1200px]'
              "
            >
              <!-- Header bar -->
              <div
                class="flex shrink-0 items-center justify-between border-b border-white/10 bg-[#12121c]/90 px-5 py-3 backdrop-blur-xl"
              >
                <div class="flex items-center gap-2">
                  <PawPrint :size="24" class="text-brand -rotate-18" />
                  <h3 class="text-base font-semibold text-white">日程</h3>
                </div>
                <button
                  class="rounded-full p-2 text-white/50 transition-colors hover:bg-white/10 hover:text-white"
                  @click="enabled = false"
                >
                  <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>

              <!-- Content area with glass styling -->
              <div
                class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-b-3xl bg-[#12121c]/75 backdrop-blur-[20px]"
              >
                <ScheduleContent variant="popup" />
              </div>
            </div>
          </Transition>
        </div>
      </Transition>
    </Teleport>

    <!-- 设置页形态：与 popup 的差别只是 chrome 由外层 MenuPage / MenuItem 提供，内容区一致 -->
    <div
      v-if="variant === 'settings'"
      class="flex min-h-0 w-full flex-1 flex-col overflow-hidden rounded-2xl border border-white/10 bg-[#12121c]/40"
    >
      <div class="flex shrink-0 items-center justify-between border-b border-white/10 px-5 py-3">
        <div class="flex items-center gap-2">
          <PawPrint :size="22" class="text-brand -rotate-18" />
          <h3 class="text-base font-semibold text-white">日程</h3>
        </div>
      </div>
      <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
        <ScheduleContent />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 日程面板（原版 components/schedule/SchedulePanel.vue）。
 *
 * 自由对话房间左上角的工具条按钮 + 全屏弹窗，弹窗里是日程内容区；
 * 设置页的 SettingsSchedule 也直接渲染 ScheduleContent。
 *
 * 内容区 = ScheduleContent.vue（日程 / 待办 / 日历 / 主动对话 / 工具调用五个子页）。
 * 原版这些页面全部走 Tauri invoke 读写本地数据库，本项目换成
 * store/scheduleStore.ts（reactive + localStorage），面板结构与开合逻辑
 * （按钮高亮、打开设置时收起、窄屏尺寸）与原版一致。
 */
import { ref, watch } from "vue";
import { PawPrint } from "@lucide/vue";
import Button from "../base/widget/Button.vue";
import ScheduleContent from "./ScheduleContent.vue";
import { settingsUi } from "../../store/settingsUi";
import { roomStore } from "../../store/roomStore";

withDefaults(defineProps<{ variant?: "popup" | "settings" }>(), { variant: "popup" });

const enabled = ref(false);

function toggleEnabled() {
  enabled.value = !enabled.value;
}

watch(
  () => settingsUi.showSettings,
  (show) => {
    if (show) enabled.value = false;
  },
);
</script>
