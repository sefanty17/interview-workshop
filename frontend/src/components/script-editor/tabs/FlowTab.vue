<script setup lang="ts">
/**
 * 章节流程 / 章节编辑两个态（原版 tabs/FlowTab.vue）。
 *
 * `level === 'flow'` → 章节流程图；`level === 'chapter'` → 左时间线 + 右属性面板。
 * 原版的「打开剧本目录」按钮是 Tauri 能力，本项目不渲染。
 */

import { computed, ref, watch } from "vue";
import { Icon } from "../../base";
import { MenuPage, MenuItem } from "../../ui";
import ChapterFlow from "../flow/ChapterFlow.vue";
import ChapterTimeline from "../flow/ChapterTimeline.vue";
import EventPropertyPanel from "../flow/EventPropertyPanel.vue";
import Toggle from "../../base/widget/Toggle.vue";
import {
  runValidation,
  setChapterName,
  store,
} from "../../../store/scriptEditor";

const FOLD_HINT =
  "官方剧本里反复出现两组固定写法：「角色退场 → 背景 → 角色出场」的转场，和「AI 说 → 等玩家输入 → AI 说」的一轮互动。打开后它们各折成一行，长章节能少掉近一半行数；折起来的那行会写明这段切到哪个背景、用的什么提示。";

/** 属性栏拖拽宽度钳制：最小 360px，最大不超过编辑器宽度的 88% */
const GRIP_MIN = 360;
const GRIP_MAX_RATIO = 0.88;

const editorWrap = ref<HTMLElement | null>(null);

let gripStartX = 0;
let gripStartW = 0;
let gripDragging = false;

/** 边缘竖条手柄：单击展开/折叠，按住拖拽调宽度（移动超 4px 视为拖拽） */
const onGripDown = (e: PointerEvent) => {
  gripStartX = e.clientX;
  gripStartW = store.propsExpanded ? store.propsWidth : 340;
  gripDragging = false;
  const onMove = (ev: PointerEvent) => {
    const delta = gripStartX - ev.clientX;
    if (!gripDragging && Math.abs(delta) > 4) gripDragging = true;
    if (!gripDragging) return;
    const wrap = editorWrap.value;
    const maxW = wrap ? Math.max(GRIP_MIN, Math.floor(wrap.clientWidth * GRIP_MAX_RATIO)) : 1200;
    store.propsExpanded = true;
    store.propsWidth = Math.min(maxW, Math.max(GRIP_MIN, gripStartW + delta));
  };
  const onUp = () => {
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
    if (!gripDragging) store.propsExpanded = !store.propsExpanded;
  };
  window.addEventListener("pointermove", onMove);
  window.addEventListener("pointerup", onUp);
};

/** 退出章节编辑时自动收起属性栏 */
watch(
  () => store.level,
  (level) => {
    if (level !== "chapter") store.propsExpanded = false;
  },
);

const onRename = (e: Event) => setChapterName((e.target as HTMLInputElement).value);

/** 新建章节交给 EditorModals 弹窗（原版走 LcModal；本项目的 LcModal 没有表单回调能力） */
const emit = defineEmits<{ "new-chapter": [] }>();
</script>

<template>
  <!-- ============ 章节流程 ============ -->
  <MenuPage v-if="store.level === 'flow'">
    <MenuItem title="章节流程">
      <template #header>
        <Icon icon="adventure" :size="20" />
      </template>
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
          @click="emit('new-chapter')"
        >
          ＋ 新建章节
        </button>
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
          @click="runValidation()"
        >
          重新校验
        </button>
      </div>
      <ChapterFlow />
    </MenuItem>
  </MenuPage>

  <!-- ============ 章节编辑 ============ -->
  <!-- 注意：这里不能加 relative —— 组件根元素会收到 <component> fallthrough 的
       absolute inset-0，两个 position 类并存时 CSS 里 relative 在后会胜出，
       元素从「撑满容器」塌缩为「内容高度」，内部滚动链就断了 -->
  <div v-else ref="editorWrap" class="mx-auto flex min-h-0 w-[94%] flex-1 gap-5 px-3 py-4">
    <div class="flex min-w-0 flex-1 flex-col">
      <MenuItem title="事件时间线" class="fill flex h-full min-h-0 flex-col">
        <template #header>
          <Icon icon="text" :size="20" />
        </template>
        <div class="mb-2 flex items-center gap-2">
          <input
            class="glass-input flex-1"
            placeholder="章节显示名（留空则用文件名）"
            :value="store.chapter?.name ?? ''"
            @change="onRename"
          />
          <label
            class="inline-flex items-center gap-2 text-[0.8rem] whitespace-nowrap text-white/70"
            :title="FOLD_HINT"
          >
            <Toggle
              :checked="store.foldCompounds"
              @change="(v: boolean) => (store.foldCompounds = v)"
            />
            合并转场等固定组合
          </label>
          <span class="shrink-0 text-xs text-white/40">
            {{ `${store.chapter?.events.length ?? 0} 个事件` }}
          </span>
        </div>
        <div class="min-h-0 flex-1 overflow-y-auto pr-1">
          <ChapterTimeline />
        </div>
      </MenuItem>
    </div>

    <!-- 属性栏：展开时不遮挡时间线（并行查看），宽度由边缘手柄拖拽记忆 -->
    <div
      class="relative flex min-h-0 flex-col transition-[flex-basis] duration-300 ease-out"
      :style="store.propsExpanded ? { flexBasis: `${store.propsWidth}px` } : { flexBasis: '340px' }"
    >
      <div
        class="group/grip absolute top-0 bottom-0 left-0 z-30 w-2 -translate-x-1/2 cursor-ew-resize touch-none"
        title="单击展开/折叠，按住拖拽调整宽度"
        @pointerdown="onGripDown"
      >
        <div
          class="group-hover/grip:bg-brand absolute top-1/2 left-1/2 h-20 w-[3px] -translate-x-1/2 -translate-y-1/2 rounded-full bg-white/20 transition-colors"
        ></div>
      </div>
      <MenuItem title="事件属性" class="fill flex h-full min-h-0 flex-col">
        <template #header>
          <Icon icon="setting" :size="20" />
        </template>
        <div class="min-h-0 flex-1 overflow-y-auto pr-1">
          <EventPropertyPanel />
        </div>
      </MenuItem>
    </div>
  </div>
</template>

<style scoped>
/* MenuItem 的 .content 默认只有 width:100%，在 .fill（flex 列）里不会收缩 */
.fill :deep(.content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
