<template>
  <!--
    背景层自己造：本项目路由是独立页面，底下没有桌面/主界面可透，不给背景就直接露白。
    结构照原版：bg-layer（渐变兜底）→ bg-image（背景图，压暗）→ bg-dim-mask（遮罩）。
    默认背景图是主菜单同款的 background2.png（本项目自带资产）。
  -->
  <div class="editor-root relative h-full w-full overflow-hidden">
    <div
      class="bg-layer"
      :style="{
        '--bg-blur': `${store.editorBg.blur}px`,
        '--bg-dim': String(store.editorBg.dim),
      }"
    >
      <div class="bg-image" :style="{ backgroundImage: `url(${bgImageSrc})` }"></div>
      <div class="bg-dim-mask"></div>
    </div>

    <EditorHeader @leave="leave" />

    <div class="relative h-[calc(100%-5.5rem)] min-h-0">
      <Transition name="tab-slide">
        <component
          :is="currentTabComponent"
          :key="tabKey"
          class="absolute inset-0"
          @new-script="modal = 'script'"
          @new-chapter="modal = 'chapter'"
        />
      </Transition>
    </div>

    <EditorModals v-model:modal="modal" />

    <!-- 试玩层（E5b）：Teleport 到 #app 的全屏浮层，挂在这里而不是某个 Tab 里，
         这样切 Tab / 进章节编辑都不会把它卸掉 -->
    <PreviewStage :fromChapter="store.previewFromChapter" />

    <!-- ============ 快捷键表（E6a） ============ -->
    <ShortcutHelpPanel :visible="shortcutHelp" @close="shortcutHelp = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import type { Component } from "vue";
import EditorHeader from "../components/script-editor/panels/EditorHeader.vue";
import ScriptListPanel from "../components/script-editor/panels/ScriptListPanel.vue";
import ShortcutHelpPanel from "../components/script-editor/panels/ShortcutHelpPanel.vue";
import FlowTab from "../components/script-editor/tabs/FlowTab.vue";
import ConfigTab from "../components/script-editor/tabs/ConfigTab.vue";
import ValidateTab from "../components/script-editor/tabs/ValidateTab.vue";
import AppearanceTab from "../components/script-editor/tabs/AppearanceTab.vue";
import AssetsTab from "../components/script-editor/tabs/AssetsTab.vue";
import CharactersTab from "../components/script-editor/tabs/CharactersTab.vue";
import EditorModals from "../components/script-editor/modals/EditorModals.vue";
import AgentChatPanel from "../components/script-editor/agent/AgentChatPanel.vue";
import AgentSettingsPanel from "../components/script-editor/agent/AgentSettingsPanel.vue";
import PreviewStage from "../components/script-editor/preview/PreviewStage.vue";
import { MenuPage } from "../components/ui";
import { editorShortcuts } from "../store/editorShortcuts";
import {
  backToFlow,
  duplicateEvent,
  initEditor,
  moveEvent,
  NO_SCRIPT_TABS,
  redo,
  removeEvent,
  saveNow,
  startPreview,
  stopPreview,
  store,
  undo,
} from "../store/scriptEditor";
import { bindingMatches, type ShortcutAction } from "../utils/shortcuts";
// 默认背景与主菜单同款（对齐原版 ScriptEditor.vue 的 defaultBg import）
import defaultBg from "../assets/images/background2.png";

const router = useRouter();

/** 新建剧本 / 新建章节 的弹窗；null = 关闭 */
const modal = ref<"script" | "chapter" | null>(null);

/**
 * flow Tab 在没有打开剧本时是剧本列表、打开后是章节流程 —— 这是原版行为，别自己改。
 * appearance（外观）无剧本也可用（背景是全局的，对齐原版 EditorHeader 的 disabled 规则）。
 */
const menuTab = (inner: Component) =>
  defineComponent({
    name: "MenuTab",
    render: () => h(MenuPage, null, () => h(inner)),
  });

/**
 * AI 助手对话面板不用 MenuPage 包（它是左右两栏的应用式布局，不是卡片页）。
 * 外层容器照原版 ScriptEditor.vue 的 agentChatTab：96% 宽、居中、撑满剩余高度。
 */
const agentChatTab = defineComponent({
  name: "AgentChatTab",
  render: () =>
    h("div", { class: "mx-auto flex min-h-0 w-[96%] flex-1 gap-5 px-3 py-4" }, h(AgentChatPanel)),
});

const tabComponents: Record<string, Component> = {
  flow: FlowTab,
  config: ConfigTab,
  validate: ValidateTab,
  appearance: menuTab(AppearanceTab),
  assets: menuTab(AssetsTab),
  characters: menuTab(CharactersTab),
  "agent-chat": agentChatTab,
  "agent-settings": menuTab(AgentSettingsPanel),
};

/**
 * 选哪个组件上屏。
 *
 * <p>没打开剧本时回落到剧本列表 —— 但**不是所有 Tab 都回落**：
 * `NO_SCRIPT_TABS`（对齐原版 EditorHeader:125）里的四项照常渲染，
 * 否则 AI 助手 / 助手设置即使按钮没置灰也会被弹回列表。
 */
const currentTabComponent = computed<Component>(() => {
  if (store.detail) return tabComponents[store.tab] ?? FlowTab;
  if (NO_SCRIPT_TABS.includes(store.tab)) return tabComponents[store.tab];
  return ScriptListPanel;
});

/**
 * Transition 的 key：剧本列表 ↔ 各 Tab 都要换 key，否则 Vue 会复用同一个实例。
 *
 * flow 分支额外带上 `store.level`（对齐原版 `ScriptEditor.vue:174-177`）：进/出章节编辑时
 * FlowTab 的根元素会从 MenuPage 换成普通 div，同一个 key 的话 Vue 会走原地 patch、
 * 不重跑 Transition 的 enter，`tab-slide-enter-from`（本项目带 `opacity: 0`）就会留在新根
 * 元素上，整块章节编辑区变成全透明。
 */
const tabKey = computed<string>(() => {
  if (store.tab === "appearance") return "tab-appearance";
  if (!store.detail) return "flow-list";
  return store.tab === "flow" ? `flow-${store.level}` : `tab-${store.tab}`;
});

/** 编辑器背景图源：自定义时用素材 URL（追加 ?v= 做缓存击穿），默认时用内置背景图。
 *  对齐原版 ScriptEditor.vue 的 bgImageSrc。 */
const bgImageSrc = computed(() =>
  store.editorBg.path ? `${store.editorBg.path}?v=${store.bgVersion}` : defaultBg,
);

const leave = () => {
  void router.push("/");
};

// ---- 快捷键表（E6a） ----
const shortcutHelp = ref(false);

// ---- 快捷键（键位可自定义，见 ShortcutHelpPanel / editorShortcuts） ----

const isTyping = (target: EventTarget | null): boolean => {
  const el = target as HTMLElement | null;
  return !!el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable);
};

const onKey = (e: KeyboardEvent) => {
  const typing = isTyping(e.target);

  const hit = (action: ShortcutAction) => bindingMatches(editorShortcuts.shortcuts[action], e);

  // Esc 与快捷键表切换不带修饰键且语义特殊，先行处理
  if (hit("esc")) {
    if (store.previewing) {
      stopPreview();
    } else if (shortcutHelp.value) {
      shortcutHelp.value = false;
    } else if (store.level === "chapter") {
      backToFlow();
    }
    return;
  }
  if (!typing && hit("shortcutHelp")) {
    e.preventDefault();
    shortcutHelp.value = !shortcutHelp.value;
    return;
  }

  // 试玩期间键盘归游戏，编辑器不抢
  if (store.previewing) return;

  // 保存允许在输入框里触发（写作中随手保存）
  if (hit("save")) {
    e.preventDefault();
    void saveNow();
    return;
  }

  // 在输入框里让位给浏览器原生撤销：否则作者想撤销一个词，却把整个事件列表回退一帧
  if (typing) return;

  if (hit("redo")) {
    e.preventDefault();
    redo();
    return;
  }
  if (hit("undo")) {
    e.preventDefault();
    undo();
    return;
  }
  if (hit("copyEvent")) {
    e.preventDefault();
    if (store.chapter) duplicateEvent(store.selectedEvent);
    return;
  }
  if (hit("playtest")) {
    e.preventDefault();
    startPreview(store.level === "chapter" ? (store.chapter?.id ?? "") : "");
    return;
  }
  // 展开/收起事件属性栏（章节编辑页才有意义）
  if (hit("expandProps") && store.level === "chapter") {
    e.preventDefault();
    store.propsExpanded = !store.propsExpanded;
    return;
  }

  // 以下都只在章节编辑页有意义
  if (store.level !== "chapter" || !store.chapter) return;
  const last = store.chapter.events.length - 1;

  if (hit("deleteEvent")) {
    e.preventDefault();
    removeEvent(store.selectedEvent);
  } else if (hit("moveCursor") || hit("moveEvent")) {
    e.preventDefault();
    const step = e.key === "ArrowUp" ? -1 : 1;
    const to = store.selectedEvent + step;
    if (to < 0 || to > last) return;
    if (hit("moveEvent")) moveEvent(store.selectedEvent, to);
    else store.selectedEvent = to;
  }
};

onMounted(() => {
  window.addEventListener("keydown", onKey);
  void initEditor();
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKey);
  if (store.dirty) void saveNow();
});
</script>

<style scoped>
/* 渐变兜底：背景图缺失或加载失败时露出的底色（照原版 ScriptEditor.vue 的 .bg-layer） */
.bg-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background:
    radial-gradient(900px 500px at 78% 12%, rgba(121, 217, 255, 0.1), transparent 62%),
    radial-gradient(700px 600px at 15% 88%, rgba(90, 140, 190, 0.12), transparent 64%),
    linear-gradient(168deg, #101a26 0%, #16202c 45%, #1b2430 100%);
}
.bg-image {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  /* 固定压暗再叠加可调模糊；模糊半径由滑块写入 --bg-blur（照原版） */
  filter: brightness(0.35) blur(var(--bg-blur, 12px));
  /* 放大一点，避免模糊后四周露出透明边缘 */
  transform: scale(1.02);
}
.bg-dim-mask {
  position: absolute;
  inset: 0;
  /* 压暗遮罩不随图模糊；不透明度由滑块写入 --bg-dim */
  background: rgba(0, 0, 0, var(--bg-dim, 0.3));
}
.editor-root > *:not(.bg-layer) {
  position: relative;
  z-index: 1;
}

/* Tab 切换过渡。
 * 原版用推入推出（方向随 Tab 顺序：slide-left / slide-right），本批只有两个 Tab，
 * 直接用淡入淡出 + 12px 位移，E4 加 Flow 时间线后再换成推入推出。
 */
.tab-slide-enter-active,
.tab-slide-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.tab-slide-enter-from {
  opacity: 0;
  transform: translateX(12px);
}
.tab-slide-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}
</style>
