<template>
  <!-- 透明遮罩：点击任意处 / 再次右键 关闭菜单 -->
  <div
    v-if="visible"
    class="fixed inset-0 z-[9998]"
    @click="$emit('close')"
    @contextmenu.prevent="$emit('close')"
  ></div>

  <!-- 场景右键菜单 -->
  <div
    v-if="visible && scene"
    class="fixed z-[9999] min-w-48 rounded-xl border border-white/15 bg-slate-900/95 p-1.5 shadow-2xl backdrop-blur-xl"
    :style="menuStyle"
    @click.stop
  >
    <div class="truncate px-2.5 py-1.5 text-xs font-semibold text-white/50">
      {{ scene.scene_name }}
    </div>

    <!-- 重命名：行内输入框 -->
    <div v-if="editing" class="px-1.5 py-1.5">
      <input
        ref="renameInput"
        v-model="renameValue"
        class="w-full rounded-lg border border-white/15 bg-black/40 px-2 py-1.5 text-sm text-white outline-none focus:border-[#79d9ff]"
        @keydown.enter.prevent="confirmRename"
        @keydown.esc.prevent="cancelRename"
      />
      <div class="mt-1.5 flex justify-end gap-1.5">
        <button
          class="rounded-md px-2.5 py-1 text-xs text-white/60 transition-colors hover:bg-white/10"
          @click="cancelRename"
        >
          取消
        </button>
        <button
          class="rounded-md bg-[#79d9ff]/80 px-2.5 py-1 text-xs font-semibold text-white transition-colors hover:bg-[#79d9ff]"
          @click="confirmRename"
        >
          确定
        </button>
      </div>
    </div>

    <template v-else>
      <!-- 移动到子分类（仅内置场景） -->
      <template v-if="isGameScene">
        <div class="px-2.5 py-1 text-xs font-semibold tracking-widest text-white/30 uppercase">
          移动到
        </div>
        <button
          class="block w-full rounded-lg px-2.5 py-1.5 text-left text-sm text-white/80 transition-colors hover:bg-white/10"
          @click="$emit('move', null)"
        >
          根目录
        </button>
        <button
          v-for="cat in categories"
          :key="'move-' + cat"
          class="block w-full rounded-lg px-2.5 py-1.5 text-left text-sm text-white/80 transition-colors hover:bg-white/10"
          @click="$emit('move', cat)"
        >
          {{ cat }}
        </button>
        <div v-if="categories.length === 0" class="px-2.5 py-1.5 text-xs text-white/30">
          暂无子分类，请先在分类栏新建
        </div>
        <div class="my-1 h-px bg-white/10"></div>
      </template>

      <button
        class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm text-white/80 transition-colors hover:bg-white/10"
        @click="$emit('set-current')"
      >
        <CircleDot :size="14" />
        设为当前场景
      </button>

      <button
        class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm text-white/80 transition-colors hover:bg-white/10"
        @click="$emit('toggle-favorite')"
      >
        <Star :size="14" :class="scene.favored ? 'fill-amber-400 text-amber-400' : ''" />
        {{ scene.favored ? "取消收藏" : "收藏" }}
      </button>

      <button
        v-if="isGameScene"
        class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm text-white/80 transition-colors hover:bg-white/10"
        @click="startRename"
      >
        <Pencil :size="14" />
        重命名
      </button>

      <button
        v-if="isGameScene"
        class="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm text-red-300 transition-colors hover:bg-red-500/15"
        @click="$emit('delete')"
      >
        <Trash2 :size="14" />
        删除
      </button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { CircleDot, Pencil, Star, Trash2 } from "@lucide/vue";
import type { SceneInfo } from "../../../data/mock";

const props = defineProps<{
  visible: boolean;
  /** 触发右键时的鼠标位置 */
  x: number;
  y: number;
  /** 菜单对应的场景 */
  scene: SceneInfo | null;
  /** 允许移动到的目标分类（不含「全部」与虚拟分类与根目录） */
  categories: string[];
}>();

const emit = defineEmits<{
  (e: "close"): void;
  (e: "move", category: string | null): void;
  (e: "set-current"): void;
  (e: "toggle-favorite"): void;
  (e: "rename", name: string): void;
  (e: "delete"): void;
}>();

const isGameScene = computed(() => !props.scene?.source || props.scene.source === "game");

// 菜单定位：尽量不超出视口右/下边界
const menuStyle = computed(() => ({
  left: Math.max(0, Math.min(props.x, window.innerWidth - 210)) + "px",
  top: Math.max(0, Math.min(props.y, window.innerHeight - 320)) + "px",
}));

// ---- 行内重命名 ----
const editing = ref(false);
const renameValue = ref("");
const renameInput = ref<HTMLInputElement | null>(null);

watch(
  () => props.visible,
  (v) => {
    if (!v) editing.value = false;
  },
);

async function startRename(): Promise<void> {
  renameValue.value = props.scene?.scene_name ?? "";
  editing.value = true;
  await nextTick();
  renameInput.value?.focus();
  renameInput.value?.select();
}

function confirmRename(): void {
  const name = renameValue.value.trim();
  if (!name) {
    cancelRename();
    return;
  }
  editing.value = false;
  emit("rename", name);
}

function cancelRename(): void {
  editing.value = false;
}
</script>
