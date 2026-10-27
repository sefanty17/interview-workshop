<template>
  <div class="blur-overlay" v-if="shouldShowOverlay" :style="{ opacity: overlayOpacity }"></div>
  <div
    class="settings-panel lc-reset-radius flex h-full flex-col"
    :class="{ 'slide-in-right': slideFromRight && !panelLeaving, 'is-leaving': panelLeaving }"
    v-show="panelVisible"
  >
    <div class="w-full shrink-0">
      <SettingsNav ref="settingsNavRef" @remove-more-menu-from-a="onAddFromA" />
    </div>

    <div
      class="relative w-full flex-1 overflow-hidden"
      ref="contentRef"
      @touchstart="onTouchStart"
      @touchend="onTouchEnd"
    >
      <Transition :name="transitionName">
        <!-- KeepAlive 缓存设置子页面实例：切换时只激活/停用，不销毁重建，保留状态 -->
        <KeepAlive>
          <component
            :is="currentTabComponent"
            :key="settingsUi.currentSettingsTab"
            class="absolute inset-0 overflow-y-auto"
            ref="settingsAdvanceRef"
            @remove-more-menu-from-b="onAddFromB"
          />
        </KeepAlive>
      </Transition>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch, type Component } from "vue";
import { settingsUi } from "../../store/settingsUi";
import {
  SettingsAchievement,
  SettingsAdvance,
  SettingsAdventure,
  SettingsBackground,
  SettingsCharacter,
  SettingsHistory,
  SettingsKnowledge,
  SettingsLog,
  SettingsSave,
  SettingsSchedule,
  SettingsSound,
  SettingsText,
} from "./pages";
import SettingsNav from "./SettingsNav.vue";

// 从主菜单打开时让面板从右侧滑入（转盘式）。做在面板本体上而不是外层包一层：
// 本组件根节点是 fragment 且两个根都是 position fixed，外层带 transform 会成为
// fixed 的包含块，令 blur-overlay 的 backdrop-filter 失去采样背景并压低层级
const props = defineProps<{ slideFromRight?: boolean }>();
/** slideFromRight 场景下退场动画播完后通知父级，父级据此才真正卸载本组件 */
const emit = defineEmits<{ (e: "closed"): void }>();

/** 退场动画时长，须与下方 settings-panel-slide-out 的时长一致 */
const LEAVE_MS = 420;

// 获取 A 组件和 B 组件的 Ref 实例
const settingsNavRef = ref<InstanceType<typeof SettingsNav> | null>(null);
const settingsAdvanceRef = ref<InstanceType<typeof SettingsAdvance> | null>(null);

// 添加延迟状态
const shouldShowOverlay = ref(false);
const overlayOpacity = ref(0);
// 原版面板常驻（v-show），初始 immediate:false 分支的 300ms 隐藏定时器早就跑完了；
// 我们是路由级挂载（挂载后立刻 showSettings=true），必须清掉这个过期定时器，
// 否则它会盖掉刚打开的遮罩
let overlayHideTimer: number | null = null;

// 面板是否可见：关闭时先让退场动画播完再隐藏，否则面板会瞬间消失
const panelVisible = ref(settingsUi.showSettings);
const panelLeaving = ref(false);
let leaveTimer: number | null = null;

function cancelLeave() {
  if (leaveTimer !== null) {
    clearTimeout(leaveTimer);
    leaveTimer = null;
  }
  panelLeaving.value = false;
}

function startLeave() {
  cancelLeave();
  panelLeaving.value = true;
  leaveTimer = window.setTimeout(() => {
    leaveTimer = null;
    panelLeaving.value = false;
    panelVisible.value = false;
    emit("closed");
  }, LEAVE_MS);
}

onUnmounted(cancelLeave);

watch(
  () => settingsUi.showSettings,
  (newVal) => {
    if (newVal) {
      // 显示时：立即显示元素，然后延迟改变透明度
      cancelLeave();
      if (overlayHideTimer !== null) {
        clearTimeout(overlayHideTimer);
        overlayHideTimer = null;
      }
      panelVisible.value = true;
      shouldShowOverlay.value = true;
      setTimeout(() => {
        overlayOpacity.value = 1;
      }, 10); // 使用很小的延迟确保浏览器有机会渲染
    } else {
      // 隐藏时：先改变透明度，淡出播完再卸载元素。
      // 时长须与 .blur-overlay 的 transition 一致，早了会在淡出中途被硬切
      overlayOpacity.value = 0;
      overlayHideTimer = window.setTimeout(() => {
        overlayHideTimer = null;
        shouldShowOverlay.value = false;
      }, 300);
      // 主菜单场景反向滑出，播完再隐藏并通知父级卸载；其余场景维持瞬时隐藏
      if (props.slideFromRight) startLeave();
      else panelVisible.value = false;
    }
  },
  { immediate: true },
);

// ========== 手机端左右滑动切换标签 ==========
// 导航栏在顶部，手机端通过水平滑动内容区切换设置页
// 标签顺序与 SettingsNav 导航一致
const TABS = [
  "character",
  "adventure",
  "text",
  "knowledge",
  "background",
  "sound",
  "history",
  "achievement",
  "save",
  "advance",
  "log",
] as const;

// 标签 → 组件映射（推入推出转场用 v-if 动态组件）
// 注意：这里只做映射，上面 TABS（滑动顺序）与 SettingsNav 的按钮列表都没加 schedule——
// 原版顶部 nav 就没有「日程」，它由房间里的 SchedulePanel 打开；但 currentSettingsTab
// 若被置为 "schedule" 时本面板要能渲染出来
const tabComponents: Record<string, Component> = {
  save: SettingsSave,
  text: SettingsText,
  knowledge: SettingsKnowledge,
  sound: SettingsSound,
  advance: SettingsAdvance,
  adventure: SettingsAdventure,
  history: SettingsHistory,
  achievement: SettingsAchievement,
  character: SettingsCharacter,
  background: SettingsBackground,
  log: SettingsLog,
  schedule: SettingsSchedule,
};
const currentTabComponent = computed(() => tabComponents[settingsUi.currentSettingsTab]);
// 转场方向：左滑下一项 → slide-left（新页从右进）；右滑上一项 → slide-right
const transitionName = ref<"slide-left" | "slide-right">("slide-left");

// 依据 TABS 顺序决定滑动方向：目标索引更大 → 下一项（slide-left）；更小 → 上一项（slide-right）。
// watch 默认 flush: 'pre'，在重渲染前更新 transitionName，Transition 开始动画时能取到正确方向。
watch(
  () => settingsUi.currentSettingsTab,
  (newTab, oldTab) => {
    if (!oldTab || oldTab === newTab) return;
    const newIdx = (TABS as readonly string[]).indexOf(newTab);
    const oldIdx = (TABS as readonly string[]).indexOf(oldTab);
    // 目标/来源不在滑动顺序里（理论不应发生）→ 保持原方向
    if (newIdx === -1 || oldIdx === -1) return;
    transitionName.value = newIdx > oldIdx ? "slide-left" : "slide-right";
  },
);

// 原版 uiStore.isSmallScreen：视口短边 < 500
const isSmallScreen = () => Math.min(window.innerWidth, window.innerHeight) < 500;

const contentRef = ref<HTMLElement | null>(null);
let touchStartX = 0;
let touchStartY = 0;
let touchOnHorizontalScrollable = false;
let isSwipeAnimating = false;

// 判断触摸起点是否在"可滚动"容器内（纵向列表如存档/日志、横向表格等）。
// 是则不触发页面切换——用户可能在拖动内容或滚动条，不该切页。
// 只排除"确实有溢出可滚"的容器：内容不满的页面（无可滚动区域）仍可滑动切换。
function isInsideScrollable(el: Element | null): boolean {
  while (el && el !== contentRef.value) {
    // 数值调节滑块（原生 range / 自定义 Slider）→ 拖动它不该切页
    if (el.tagName === "INPUT" && (el as HTMLInputElement).type === "range") return true;
    // 横向可滚动容器（如日志页横向表格）→ 拖动横向内容不该切页。
    // 竖向滚动容器不在此列：竖向滚动由 onTouchEnd 的 |dx| <= |dy| 判断兜底，
    // 竖向列表里做"明显横向"滑动仍可切页。
    const s = getComputedStyle(el);
    if (
      (s.overflowX === "auto" || s.overflowX === "scroll") &&
      el.scrollWidth > el.clientWidth + 4
    ) {
      return true;
    }
    el = el.parentElement;
  }
  return false;
}

const onTouchStart = (e: TouchEvent) => {
  touchStartX = e.touches[0].clientX;
  touchStartY = e.touches[0].clientY;
  touchOnHorizontalScrollable = isInsideScrollable(e.target as Element);
};

const onTouchEnd = (e: TouchEvent) => {
  // 仅小屏（手机）生效
  if (!isSmallScreen()) return;
  // 起点在可横向滚动区域（日志页等）→ 让原生滚动处理
  if (touchOnHorizontalScrollable) return;
  if (isSwipeAnimating) return;

  const dx = e.changedTouches[0].clientX - touchStartX;
  const dy = e.changedTouches[0].clientY - touchStartY;

  // 只响应明显的水平滑动（避免和垂直滚动/滑块冲突）
  if (Math.abs(dx) < 50 || Math.abs(dx) <= Math.abs(dy)) return;

  const currentIdx = TABS.indexOf(settingsUi.currentSettingsTab as (typeof TABS)[number]);
  let nextIdx = dx < 0 ? currentIdx + 1 : currentIdx - 1; // 左滑 → 下一个，右滑 → 上一个
  if (nextIdx < 0) nextIdx = TABS.length - 1;
  if (nextIdx >= TABS.length) nextIdx = 0;

  isSwipeAnimating = true;
  settingsUi.currentSettingsTab = TABS[nextIdx];
  setTimeout(() => {
    isSwipeAnimating = false;
  }, 300);
};

// 提供给 SettingsAdvance 的对外暴露接口（示例）
const onAddFromA = () => {
  // A 组件转发的事件处理
};

const onAddFromB = () => {
  // B 组件转发的事件处理
};

// 在父组件中暴露引用（如需要）
defineExpose({
  settingsNavRef,
  settingsAdvanceRef,
});
</script>

<style scoped>
/* tokens.css 把 --radius-lg 覆盖成 22px、--radius-sm 覆盖成 10px（全局 css 禁改）。
   设置面板内锁回 Tailwind 默认圆角，让照抄原版的 rounded-lg=8px / rounded-sm=4px 生效 */
.lc-reset-radius {
  --radius-sm: 0.25rem;
  --radius-lg: 0.5rem;
  --radius: 0.25rem;
}

.blur-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 999;
  transition: opacity 0.3s ease;
  opacity: 0;
}

.settings-panel {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1000;
  background: transparent;
  color: var(--text-primary, #fff);
}

/* 主菜单打开设置时面板整体从右侧滑入，与设置内 tab 切换同一套动画参数。
   用 animation 而不是 transition：transition 需要起始状态先被绘制一帧，
   靠定时器翻转 class 存在竞态；keyframes 在首次渲染即生效，播完自动回默认位置 */
.settings-panel.slide-in-right {
  animation: settings-panel-slide-in 0.42s cubic-bezier(0.32, 0.72, 0, 1);
}

@keyframes settings-panel-slide-in {
  from {
    transform: translateX(100%);
  }
  to {
    transform: translateX(0);
  }
}

/* 关闭时反向滑出。播完由脚本隐藏面板并回调父级卸载，时长见 LEAVE_MS */
.settings-panel.is-leaving {
  animation: settings-panel-slide-out 0.42s cubic-bezier(0.32, 0.72, 0, 1);
}

@keyframes settings-panel-slide-out {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(100%);
  }
}

.slide-left-enter-active,
.slide-left-leave-active,
.slide-right-enter-active,
.slide-right-leave-active {
  /* 与剧本编辑器同一套动画参数：起步即快的缓动（而非默认的慢起步）——
     切 Tab 时新页挂载/旧页缓存 DOM 重插会有 1~2 帧主线程开销，慢起步缓动
     会让旧页看起来「卡在原地」形成残留；快速起步则把这点开销掩盖掉。 */
  transition: transform 0.32s cubic-bezier(0.32, 0.72, 0, 1);
}

.slide-left-enter-from {
  transform: translateX(100%);
}
.slide-left-leave-to {
  /* 旧页滑出多一点（±150%），保证整页宽度的 Tab 也彻底离开视口 */
  transform: translateX(-150%);
}

.slide-right-enter-from {
  transform: translateX(-100%);
}
.slide-right-leave-to {
  transform: translateX(150%);
}

.slide-left-enter-to,
.slide-left-leave-from,
.slide-right-enter-to,
.slide-right-leave-from {
  transform: translateX(0);
}
</style>
