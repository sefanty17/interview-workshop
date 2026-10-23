<template>
  <div class="flex w-full flex-1 flex-col overflow-hidden md:flex-row" :class="containerClass">
    <!-- 导航菜单 (左侧)：宽屏始终可见；窄屏仅在浏览菜单层级时可见 -->
    <aside
      v-show="!roomStore.isNarrowScreen || narrowViewLevel === 'menu'"
      class="flex w-full flex-col border-r border-cyan-300 p-6 md:w-64"
      :class="{ 'min-h-0 flex-1': roomStore.isNarrowScreen }"
    >
      <div
        class="text-brand inset_0_1px_1px_rgba(255,255,255,0.1)] mb-8 flex items-center space-x-3 rounded-lg px-3.75 py-2.5 text-base font-bold"
      >
        <div class="relative">
          <div
            class="flex h-10 w-10 items-center justify-center rounded-xl bg-cyan-500 text-white shadow-lg"
          >
            <Sparkles :size="20" />
          </div>
        </div>
        <h1 class="text-xl font-bold tracking-tight text-white">面试工坊</h1>
      </div>

      <nav class="min-h-0 w-full flex-1 space-y-2 overflow-y-auto">
        <button
          class="adv-nav-link relative z-10 flex w-full cursor-pointer items-center space-x-6 rounded-lg border-none bg-transparent px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
          :class="{ 'bg-white/10 font-bold': isView('schedule') }"
          @click="changeView('schedule_groups')"
        >
          <Layers :size="18" />
          <span>日程主题</span>
        </button>
        <button
          class="adv-nav-link relative z-10 flex w-full cursor-pointer items-center space-x-6 rounded-lg border-none bg-transparent px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
          :class="{ 'bg-white/10 font-bold': isView('todo') }"
          @click="changeView('todo_groups')"
        >
          <CheckCircle2 :size="18" />
          <span>待办事项</span>
        </button>
        <button
          class="adv-nav-link relative z-10 flex w-full cursor-pointer items-center space-x-6 rounded-lg border-none bg-transparent px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
          :class="{ 'bg-white/10 font-bold': isView('calendar') }"
          @click="changeView('calendar')"
        >
          <CalendarDays :size="18" />
          <span>重要日子</span>
        </button>
        <button
          class="adv-nav-link relative z-10 flex w-full cursor-pointer items-center space-x-6 rounded-lg border-none bg-transparent px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
          :class="{ 'bg-white/10 font-bold': isView('proactive') }"
          @click="changeView('proactive_settings')"
        >
          <Cat :size="18" />
          <span>主动对话</span>
        </button>
        <button
          class="adv-nav-link relative z-10 flex w-full cursor-pointer items-center space-x-6 rounded-lg border-none bg-transparent px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
          :class="{ 'bg-white/10 font-bold': isView('tool_calls') }"
          @click="changeView('tool_calls')"
        >
          <Wrench :size="18" />
          <span>工具调用</span>
        </button>
      </nav>

      <div class="mt-auto mb-6 rounded-2xl border border-cyan-500/20 bg-cyan-50/10 p-4">
        <Toggle :checked="widgetOpen" @change="toggleWidget">
          <span class="text-brand text-xs font-bold">TODO 小组件</span>
        </Toggle>
        <p class="mt-2 text-xs leading-relaxed text-white/70">
          在屏幕角落挂一块小窗，随时看最近的日程和待办
        </p>
      </div>
    </aside>

    <main
      v-show="!roomStore.isNarrowScreen || narrowViewLevel === 'content'"
      class="flex w-full flex-1 flex-col overflow-hidden"
    >
      <header
        class="flex shrink-0 items-center justify-between border-b border-cyan-300"
        :class="roomStore.isNarrowScreen ? 'px-3 py-3' : 'mt-2 p-6'"
      >
        <div
          class="flex min-w-0 items-center"
          :class="roomStore.isNarrowScreen ? 'space-x-2' : 'space-x-4 pl-4'"
        >
          <!-- 窄屏：返回菜单按钮 -->
          <button
            v-if="roomStore.isNarrowScreen"
            class="flex shrink-0 cursor-pointer items-center gap-1 rounded-lg border-none bg-transparent px-1.5 py-1 text-sm text-white/70 transition-colors hover:bg-white/10 hover:text-white"
            @click="narrowViewLevel = 'menu'"
          >
            <ChevronLeft :size="18" />
          </button>
          <!-- 宽屏：返回上级视图（详情 → 分组） -->
          <button
            v-show="
              !roomStore.isNarrowScreen &&
              (scheduleStore.scheduleView === 'schedule_detail' ||
                scheduleStore.scheduleView === 'todo_detail')
            "
            class="cursor-pointer rounded-full border-none bg-transparent p-2 text-cyan-600 transition-all hover:bg-cyan-50"
            @click="goBackToParentView"
          >
            <ChevronLeft />
          </button>
          <div class="min-w-0">
            <h2
              class="text-brand truncate font-bold"
              :class="roomStore.isNarrowScreen ? 'text-base' : 'mb-2 text-2xl'"
            >
              {{ titleInfo.title }}
            </h2>
            <p v-show="!roomStore.isNarrowScreen" class="mt-0.5 text-xs tracking-wide text-white">
              {{ titleInfo.subtitle }}
            </p>
          </div>
        </div>

        <button
          v-show="
            !scheduleStore.scheduleView.startsWith('proactive') &&
            !scheduleStore.scheduleView.startsWith('tool_calls')
          "
          class="flex shrink-0 cursor-pointer items-center rounded-xl border-none bg-cyan-500 text-white shadow-lg transition-all hover:bg-cyan-600"
          :class="roomStore.isNarrowScreen ? 'space-x-1 px-3 py-2 text-sm' : 'space-x-2 px-5 py-2.5'"
          @click="triggerCreate"
        >
          <Plus :size="roomStore.isNarrowScreen ? 16 : undefined" />
          <span class="font-medium" :class="{ hidden: roomStore.isNarrowScreen }">新建</span>
        </button>
      </header>

      <!-- 内容滚动容器 -->
      <div
        class="custom-scrollbar flex-1 overflow-y-auto"
        :class="roomStore.isNarrowScreen ? 'p-3' : 'p-6'"
      >
        <!--日程界面-->
        <SchedulePage ref="scheduleRef" />

        <!--待办事项界面-->
        <TodoPage ref="todoRef" />

        <!--日历页面-->
        <CalendarPage ref="calendarRef" />

        <ProactivePage />

        <!--工具调用设置界面-->
        <ToolCallsPage />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 日程内容区（原版 components/schedule/ScheduleContent.vue）。
 *
 * 五个子页（日程 / 待办 / 日历 / 主动对话 / 工具调用）挂在同一棵树上，
 * 靠 scheduleStore.scheduleView 切换——与原版 uiStore.scheduleView 同构。
 *
 * 与原版差异：原版数据全走 Tauri invoke；本项目换成 store/scheduleStore.ts
 * （reactive + localStorage）。TODO 小组件是独立窗口，无桌面运行时 → 弹提示。
 */
import { computed, ref } from "vue";
import {
  CalendarDays,
  Cat,
  CheckCircle2,
  ChevronLeft,
  Layers,
  Plus,
  Sparkles,
  Wrench,
} from "@lucide/vue";
import Toggle from "../base/widget/Toggle.vue";
import SchedulePage from "./pages/SchedulePage.vue";
import TodoPage from "./pages/TodoPage.vue";
import CalendarPage from "./pages/CalendarPage.vue";
import ProactivePage from "./pages/ProactivePage.vue";
import ToolCallsPage from "./pages/ToolCallsPage.vue";
import { scheduleStore, type ScheduleView } from "../../store/scheduleStore";
import { roomStore } from "../../store/roomStore";
import { alertDialog } from "../../store/dialogStore";

type Variant = "settings" | "popup";

const props = withDefaults(
  defineProps<{
    variant?: Variant;
  }>(),
  { variant: "settings" },
);

const narrowViewLevel = ref<"menu" | "content">("menu");

/** 子页对外暴露 handleCreate（顶部「新建」按钮按当前视图分发） */
type CreatablePage = { handleCreate: () => void };
const scheduleRef = ref<CreatablePage | null>(null);
const todoRef = ref<CreatablePage | null>(null);
const calendarRef = ref<CreatablePage | null>(null);

const isView = (prefix: string) => scheduleStore.scheduleView.startsWith(prefix);

const titleInfo = computed(() => {
  const currentView = scheduleStore.scheduleView;

  if (currentView.startsWith("schedule")) {
    return { title: "铃铃提醒闹钟", subtitle: "到点的时候ta会提醒你的哦" };
  } else if (currentView.startsWith("todo")) {
    return { title: "TODO 待办笔记", subtitle: "在这里记录重要的事情吧，ta会随机提醒你哒" };
  } else if (currentView.startsWith("proactive")) {
    return {
      title: "主动对话设置",
      subtitle: "需要专心和隐私的时候可以关闭哦（需要点击底部的保存才生效）",
    };
  } else if (currentView.startsWith("tool_calls")) {
    return {
      title: "工具调用",
      subtitle: "让ta学会使用工具，比如联网搜索哦（需要点击保存才生效）",
    };
  } else if (currentView.startsWith("calendar")) {
    return { title: "君の重要な日", subtitle: "可以记下你朋友的生日自动提醒哦" };
  }
  return { title: "小灵闹钟", subtitle: "留下需要她提醒你的事情吧" };
});

const triggerCreate = () => {
  const currentView = scheduleStore.scheduleView;

  // 判断当前在哪个视图，就调用哪个组件内部的 handleCreate 方法
  if (currentView.startsWith("schedule")) {
    scheduleRef.value?.handleCreate();
  } else if (currentView.startsWith("todo")) {
    todoRef.value?.handleCreate();
  } else if (currentView === "calendar") {
    calendarRef.value?.handleCreate();
  }
};

const changeView = (view: ScheduleView) => {
  scheduleStore.scheduleView = view;
  // 窄屏下自动切换到内容视图
  if (roomStore.isNarrowScreen) {
    narrowViewLevel.value = "content";
  }
};

const goBackToParentView = () => {
  if (scheduleStore.scheduleView === "schedule_detail") {
    scheduleStore.scheduleView = "schedule_groups";
  } else if (scheduleStore.scheduleView === "todo_detail") {
    scheduleStore.scheduleView = "todo_groups";
  }
};

const containerClass = computed(() => {
  // settings：沿用原来的全屏设置页布局
  if (props.variant === "settings") {
    return "h-[85dvh] max-w-6xl md:w-[calc(100vw-4rem)] glass-panel bg-white/10 rounded-2xl";
  }
  // popup：由父级 modal 控制尺寸和样式，此处填满容器
  return "w-full h-full";
});

// ─── TODO 小组件开关 ────────────────────────────────────────
// 原版会真的开一个独立小窗（Tauri window）；本项目没有桌面运行时，开关只提示
const widgetOpen = ref(false);

const toggleWidget = async (next: boolean) => {
  widgetOpen.value = next;
  await alertDialog("TODO 小组件", "需要后端服务，当前未接入");
  // 没有真的开窗，开关回弹
  widgetOpen.value = false;
};
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand 与 --color-glass-panel，
   原版的 text-brand / glass-panel 类名保留，这里补等效样式 */
.text-brand {
  color: var(--accent-color);
}
.glass-panel {
  background: rgba(18, 18, 28, 0.72);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.125);
}
.custom-scrollbar::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: var(--accent-color);
  border-radius: 4px;
}
</style>
