<script setup lang="ts">
/**
 * 编辑器顶栏（对照原版 `panels/EditorHeader.vue`，295 行）。
 *
 * 技术栈差异（规格 E2 §1）：
 * - 无 vue-i18n，文案直接写中文。
 * - 无 `useZoom` / `currentZoom`：指示条只用 getBoundingClientRect 相对 nav 求差，
 *   不再除以缩放值。
 * - 无 `settings.shortcuts`：键位硬编码 Ctrl+Z / Ctrl+Shift+Z / Ctrl+S。
 * - 原版的 `text-brand` / `bg-brand` 依赖 Tailwind @theme，本项目 lingchat.css 已用
 *   `--color-brand` 指向同一个强调色变量，类名与原版写法保持一致。
 */

import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { Button, Icon } from "../../base";
import type { IconType } from "../../base";
import {
  backToFlow,
  canRedo,
  canUndo,
  closeScript,
  redo,
  runValidation,
  startPreview,
  store,
  undo,
} from "../../../store/scriptEditor";
import { NO_SCRIPT_TABS, type EditorTabKey } from "../../../store/scriptEditor";
import { editorShortcuts } from "../../../store/editorShortcuts";
import { formatBinding } from "../../../utils/shortcuts";

const emit = defineEmits<{
  "toggle-shortcut-help": [];
  leave: [];
}>();

const tabs: { key: EditorTabKey; label: string; icon: IconType }[] = [
  { key: "flow", label: "章节流程", icon: "adventure" },
  { key: "config", label: "剧本设置", icon: "setting" },
  { key: "validate", label: "校验", icon: "achievement" },
  { key: "assets", label: "素材", icon: "background" },
  { key: "characters", label: "角色", icon: "character" },
  { key: "agent-chat", label: "AI 助手", icon: "bot" },
  { key: "agent-settings", label: "助手设置", icon: "sliders" },
  { key: "appearance", label: "外观", icon: "palette" },
];
// 顺序按规格 V5-E6b §6.2：章节流程 / 剧本设置 / 校验 / 素材 / 角色 / 外观。
// E7b2 按原版 8 Tab 补回 AI 助手 / 助手设置两项，插在 appearance 之前，
// 既有 6 个 Tab 的相对顺序保持不变（V5 已验收）。

// ---- nav 指示条（与 SettingsNav 同一套做法）----
const navEl = ref<HTMLElement | null>(null);
const indicatorEl = ref<HTMLElement | null>(null);
const tabRefs: Record<string, HTMLElement | null> = {};

const setTabRef = (key: string, el: unknown) => {
  tabRefs[key] = (el as { $el?: HTMLElement } | null)?.$el ?? null;
};

/**
 * 指示条定位。
 *
 * 位置不能只在「切 Tab / 换剧本」时算一次：按钮宽度会在别的时刻变（字体加载完成、
 * 角标出现、窗口跨断点时文字显隐）。nav 是 `justify-content: center`，任何一个
 * 按钮变宽都会把**所有**按钮推走，上一次算出来的 left 就落到两个按钮中间的空当里。
 *
 * 所以让尺寸变化自己触发重算：ResizeObserver 同时盯 nav 和每个按钮；坐标用
 * getBoundingClientRect 相对 nav 求差（不用 offsetLeft —— 它依赖 offsetParent
 * 恰好是 nav，中间层加了 position 就会静默偏移）。
 */
const moveIndicator = async (animate = true) => {
  await nextTick();
  const bar = indicatorEl.value;
  const nav = navEl.value;
  if (!bar || !nav) return;
  const target = tabRefs[store.tab];
  bar.style.transition = animate
    ? "left 0.3s cubic-bezier(0.18, 0.89, 0.32, 1), width 0.3s cubic-bezier(0.18, 0.89, 0.32, 1)"
    : "none";
  if (!target) {
    // 目标不在了就收起来，否则指示条会停在上一次的位置 —— 那正是「出现在空白处」
    bar.style.width = "0px";
    return;
  }
  const navBox = nav.getBoundingClientRect();
  const box = target.getBoundingClientRect();
  bar.style.left = `${box.left - navBox.left + nav.scrollLeft}px`;
  bar.style.width = `${box.width}px`;
};

watch(
  () => store.tab,
  () => void moveIndicator(),
);
watch(
  () => store.detail?.package.key,
  () => void moveIndicator(),
);

let navObserver: ResizeObserver | null = null;

const observeNav = () => {
  if (typeof ResizeObserver === "undefined" || !navEl.value) return;
  // 不加动画：这类重算是「布局变了跟着修正」，滑一下反而像在乱动
  navObserver = new ResizeObserver(() => void moveIndicator(false));
  navObserver.observe(navEl.value);
  for (const el of Object.values(tabRefs)) if (el) navObserver.observe(el);
};

const switchTab = (key: EditorTabKey) => {
  // 原版这里对 validate / assets / characters 三个 Tab 做预加载；config 不需要额外动作
  // （原版 EditorHeader:125：无剧本时 flow / agent-chat / agent-settings / appearance 仍可进）
  if (!store.detail && !NO_SCRIPT_TABS.includes(key)) return;
  store.tab = key;
  // 原版对校验页做预加载：进页面就先跑一遍，作者不用先点「重新校验」
  if (key === "validate") void runValidation();
};

// ---- 面包屑 / 保存态 ----
const saveLabel = computed(() => {
  if (store.saving) return "保存中…";
  if (store.dirty) return "未保存";
  if (store.lastSavedAt) {
    const d = new Date(store.lastSavedAt);
    const time = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    return `已保存 ${time}`;
  }
  return "已保存";
});

onMounted(async () => {
  await moveIndicator(false);
  observeNav();
});

onUnmounted(() => {
  navObserver?.disconnect();
  navObserver = null;
});
</script>

<template>
  <div>
    <!-- 顶栏：与 SettingsNav 同构 -->
    <div class="flex w-full items-center justify-between px-5 py-2">
      <span class="ml-5 text-[0.95rem] font-bold tracking-[0.5px] whitespace-nowrap text-brand">
        剧本编辑器
      </span>
      <nav
        ref="navEl"
        class="relative flex h-full w-full flex-nowrap items-center justify-center gap-1 overflow-x-auto overflow-y-hidden px-2"
      >
        <div
          ref="indicatorEl"
          class="absolute bottom-0 left-0 z-10 h-1 w-0 rounded-sm bg-brand shadow-[0_0_10px_rgba(121,217,255,0.4)]"
        ></div>
        <Button
          v-for="tab in tabs"
          :key="tab.key"
          :ref="(el: unknown) => setTabRef(tab.key, el)"
          type="nav"
          :icon="tab.icon"
          :active="store.tab === tab.key"
          :disabled="!store.detail && !NO_SCRIPT_TABS.includes(tab.key)"
          @click="switchTab(tab.key)"
        >
          <p class="hidden xl:block">{{ tab.label }}</p>
          <span
            v-if="tab.key === 'validate' && store.report && store.report.errorCount > 0"
            class="ml-1 rounded-full bg-red-500 px-1.25 text-[0.6rem] text-white"
            >{{ store.report.errorCount }}</span
          >
        </Button>
      </nav>
      <Icon
        icon="close"
        :size="40"
        class="flex cursor-pointer items-center justify-center rounded-full p-1.5 text-white transition-all duration-300 ease-in-out hover:rotate-90 hover:bg-white/10 hover:text-brand"
        @click="emit('leave')"
      />
    </div>

    <!-- 面包屑 -->
    <div class="flex items-center gap-2 px-8 pb-1 text-[0.8rem] text-white/55">
      <button
        v-if="store.detail"
        class="text-brand hover:underline"
        @click="closeScript()"
      >
        ← 剧本列表
      </button>
      <span v-else>首页</span>

      <template v-if="store.detail">
        <span class="opacity-40">›</span>
        <button
          v-if="store.level === 'chapter'"
          class="text-brand hover:underline"
          @click="backToFlow()"
        >
          {{ store.detail.package.scriptName }}
        </button>
        <b v-else class="font-semibold text-white">{{ store.detail.package.scriptName }}</b>

        <template v-if="store.level === 'chapter' && store.chapter">
          <span class="opacity-40">›</span>
          <b class="font-semibold text-white">{{ store.chapter.name || store.chapter.id }}</b>
          <span class="text-[0.72rem] opacity-35">{{ store.chapter.id }}.yaml</span>
        </template>
      </template>

      <span class="ml-auto flex items-center gap-3">
        <span v-if="store.detail" class="inline-flex items-center gap-1.25 text-[0.75rem] text-white/50">
          <i
            class="inline-block h-1.5 w-1.5 rounded-full"
            :class="store.dirty ? 'bg-amber-300' : 'bg-green-400'"
          ></i>
          {{ saveLabel }}
        </span>
        <!-- E5b：「试玩」：直接改 store.previewing，由 PreviewStage 建试玩场次并开演。
             配色对齐原版 playtest 按钮（brand 系，与右侧白描边按钮区分主次） -->
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-brand/45 bg-brand/14 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-brand transition-all duration-200 hover:bg-brand/24 disabled:cursor-not-allowed disabled:opacity-40"
          :disabled="!store.detail"
          title="从剧本开头试玩（Esc 停止）"
          @click="startPreview('')"
        >
          ▶ 试玩
        </button>
        <template v-if="store.level === 'chapter'">
          <button
            class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="!canUndo"
            :title="`撤销 (${formatBinding(editorShortcuts.shortcuts.undo)})`"
            @click="undo()"
          >
            ↩ 撤销
          </button>
          <button
            class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="!canRedo"
            :title="`重做 (${formatBinding(editorShortcuts.shortcuts.redo)})`"
            @click="redo()"
          >
            ↪ 重做
          </button>
        </template>
        <!-- E6a：「快捷键」按钮，样式照原版 EditorHeader（brand → accent 等价替换） -->
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
          :title="`查看全部快捷键 (${formatBinding(editorShortcuts.shortcuts.shortcutHelp)})`"
          @click="emit('toggle-shortcut-help')"
        >
          查看全部快捷键
        </button>
      </span>
    </div>

    <!-- 原版底部的「试玩前置条件不满足」横幅依赖 store.readiness（E5 才有），此处留空 -->
  </div>
</template>
