<template>
  <!-- 场景排序面板（浮层）：收藏区固定在上方，未收藏区在下方 -->
  <div
    v-if="show"
    class="fixed inset-0 z-[9998] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
  >
    <div
      class="relative flex max-h-[85vh] w-full max-w-2xl flex-col overflow-hidden rounded-3xl border border-white/20 bg-slate-900/80 shadow-2xl"
      @click.stop
    >
      <div
        class="flex items-center justify-between border-b border-white/10 bg-white/10 p-4"
      >
        <h3 class="text-lg font-bold text-white">场景排序</h3>
        <button
          class="rounded-full p-2 text-white/50 transition-colors hover:bg-red-500/20 hover:text-white"
          @click="$emit('close')"
        >
          <X :size="18" />
        </button>
      </div>

      <div class="flex-1 space-y-2 overflow-y-auto p-4">
        <div
          v-for="(item, index) in sortItems"
          :key="item.id"
          class="flex flex-wrap items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-2"
        >
          <!-- 收藏区 / 未收藏区 分隔标题 -->
          <template
            v-if="
              index === 0 ||
              isFavored(item) !== isFavored(sortItems[index - 1]!)
            "
          >
            <div
              class="row-span-1 mb-1 flex w-full items-center gap-1 text-xs font-bold tracking-widest text-white/40 uppercase"
            >
              <Star v-if="isFavored(item)" :size="12" class="text-amber-400" />
              {{ isFavored(item) ? "收藏区" : "未收藏区" }}
            </div>
          </template>

          <div class="sort-thumb" :style="thumbStyleOf(item.id)"></div>
          <Star
            v-if="isFavored(item)"
            :size="14"
            class="shrink-0 fill-amber-400 text-amber-400"
          />
          <span class="flex-1 truncate text-sm text-white/85">{{ item.scene_name }}</span>
          <span class="shrink-0 text-xs text-white/30">{{ index + 1 }}</span>
          <button
            class="rounded-lg p-1.5 text-white/50 transition-colors hover:bg-white/10 hover:text-white disabled:opacity-25"
            :disabled="index === 0"
            title="上移"
            @click="move(index, -1)"
          >
            <ArrowUp :size="15" />
          </button>
          <button
            class="rounded-lg p-1.5 text-white/50 transition-colors hover:bg-white/10 hover:text-white disabled:opacity-25"
            :disabled="index === sortItems.length - 1"
            title="下移"
            @click="move(index, 1)"
          >
            <ArrowDown :size="15" />
          </button>
        </div>
        <div v-if="sortItems.length === 0" class="py-8 text-center text-sm text-white/40">
          暂无场景可排序
        </div>
      </div>

      <div class="flex justify-end gap-2 border-t border-white/10 bg-white/5 p-3">
        <button
          class="rounded-full bg-white/10 px-4 py-1.5 text-sm font-bold text-white/70 hover:bg-white/20"
          @click="$emit('close')"
        >
          取消
        </button>
        <button
          class="rounded-full bg-indigo-500/80 px-4 py-1.5 text-sm font-bold text-white hover:bg-indigo-500"
          @click="$emit('save', sortItems)"
        >
          保存排序
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { ArrowDown, ArrowUp, Star, X } from "@lucide/vue";
import type { SceneInfo } from "../../../data/mock";

const props = defineProps<{
  show: boolean;
  /** 待排序的场景列表（打开时复制一份，取消则不落库） */
  items: SceneInfo[];
  /** 已收藏的场景 id，用于划分收藏区 / 未收藏区 */
  favoredIds: string[];
}>();

const emit = defineEmits<{
  (e: "close"): void;
  (e: "save", ordered: SceneInfo[]): void;
}>();

const sortItems = ref<SceneInfo[]>([]);

// 每次打开都基于传入列表重新拷贝，避免上次拖拽/移动的残留顺序被写回
watch(
  () => props.show,
  (show) => {
    if (show) sortItems.value = props.items.map((s) => ({ ...s }));
  },
  { immediate: true },
);

function isFavored(item: SceneInfo): boolean {
  return props.favoredIds.includes(item.id);
}

/** 上移 / 下移：限制在各自分区内（收藏区不能拖到未收藏区） */
function move(index: number, delta: number): void {
  const target = index + delta;
  if (target < 0 || target >= sortItems.value.length) return;
  // 防止跨分区移动：移动后落点必须与原位置同属一个分区
  if (isFavored(sortItems.value[index]!) !== isFavored(sortItems.value[target]!)) return;
  const list = [...sortItems.value];
  const [moved] = list.splice(index, 1);
  list.splice(target, 0, moved!);
  sortItems.value = list;
}

// 排序弹窗里的 16:10 小缩略图（与卡片同色系）
function thumbStyleOf(id: string): Record<string, string> {
  let h = 7;
  for (let i = 0; i < id.length; i++) h = (h * 131 + id.charCodeAt(i)) % 997;
  const hue = (h * 137) % 360;
  return {
    backgroundImage: `linear-gradient(135deg, hsl(${hue} 38% 34%), hsl(${(hue + 26) % 360} 44% 16%))`,
  };
}
</script>

<style scoped>
.sort-thumb {
  width: 54px;
  height: 34px;
  flex: none;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
}
</style>
