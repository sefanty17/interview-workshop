<template>
  <div
    :class="[
      `group relative flex cursor-pointer flex-col overflow-hidden rounded-xl border border-white/[0.125] bg-white/10 shadow-[0_8px_32px_rgba(0,0,0,0.1),inset_0_1px_1px_rgba(255,255,255,0.1)] transition-all duration-300 hover:-translate-y-1 hover:scale-[1.01] hover:border-white/30 hover:bg-white/15 hover:shadow-[0_12px_40px_rgba(0,0,0,0.15),inset_0_2px_2px_rgba(255,255,255,0.15)]`,
      selected
        ? `border-2! border-sky-400! shadow-[0_0_12px_rgba(56,189,248,0.5),0_0_3px_rgba(56,189,248,0.8),inset_0_0_8px_rgba(56,189,248,0.15)]!`
        : '',
    ]"
    @click="$emit('select', scene)"
    @contextmenu.prevent="$emit('context-menu', scene, $event)"
  >
    <!-- 编辑按钮（右上角扳手）—— 插件场景只读，不提供编辑 -->
    <button
      v-if="!scene.source || scene.source === 'game'"
      class="absolute top-2 right-2 z-10 rounded-lg bg-black/50 p-1.5 text-white/60 opacity-0 transition-all group-hover:opacity-100 hover:bg-black/70 hover:text-white"
      title="编辑场景"
      @click.stop="$emit('edit', scene)"
    >
      <Wrench :size="16" />
    </button>
    <!-- 收藏置顶按钮（左上角星星） -->
    <button
      class="absolute top-2 left-2 z-10 rounded-lg bg-black/50 p-1.5 transition-all hover:bg-black/70"
      :title="favored ? '取消收藏' : '收藏（移到最前）'"
      @click.stop="$emit('toggle-favorite', scene)"
    >
      <Star
        :size="16"
        :class="favored ? 'fill-amber-400 text-amber-400' : 'text-white/60 hover:text-white'"
      />
    </button>
    <!-- 插件来源标签（右上角） -->
    <PluginTag
      v-if="scene.source && scene.source !== 'game'"
      source="plugin"
      label="插件"
      class="absolute top-2 right-2 z-10"
    />

    <!-- 背景预览：真实场景图（public/backgrounds/*.webp） -->
    <div
      class="relative flex-1 overflow-hidden after:pointer-events-none after:absolute after:inset-0 after:bg-linear-to-b after:from-transparent after:to-black/30"
    >
      <img
        v-if="scene.background && !imgBroken"
        :src="scene.background"
        :alt="scene.scene_name"
        class="aspect-video w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
        @error="imgBroken = true"
      />
      <!-- 图片缺失 / 加载失败时的容错占位（不是假数据） -->
      <div
        v-else
        class="flex aspect-video w-full flex-col items-center justify-center gap-1 bg-white/5 text-white/30"
      >
        <ImageOff :size="22" />
        <span class="text-[11px]">背景图缺失</span>
      </div>
    </div>

    <!-- 信息栏 -->
    <div class="relative z-[2] flex flex-col gap-1 border-t border-white/20 bg-white/15 px-4 py-3">
      <span class="truncate text-base font-medium text-white/90 drop-shadow-md">
        {{ scene.scene_name }}
      </span>
      <span v-if="scene.scene_description" class="line-clamp-2 text-xs text-white/50">{{
        scene.scene_description
      }}</span>
      <span v-else class="text-xs text-yellow-400/60 italic">{{
        "暂无描述（选择后不会触发旁白）"
      }}</span>
      <!-- 室内外标注：点击在 室内 → 室外 → 不确定 间循环 -->
      <button
        class="absolute right-2 bottom-2 z-10 rounded-full border border-white/15 bg-black/45 px-2 py-0.5 text-xs text-white/75 backdrop-blur-sm transition-colors hover:bg-black/65 hover:text-white"
        title="点击切换室内外标注"
        @click.stop="cycleLocation"
      >
        <component :is="locationMeta.icon" :size="12" class="mr-1 inline align-[-2px]" />
        {{ LOCATION_LABELS[currentLocation] }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, type Component } from "vue";
import { ImageOff, CircleHelp, Home, Star, TreePine, Wrench } from "@lucide/vue";
import PluginTag from "../../ui/PluginTag.vue";
import type { SceneInfo } from "../../../data/mock";

const props = defineProps<{
  scene: SceneInfo;
  /** 是否为当前激活的场景 */
  selected: boolean;
  /** 是否已收藏（收藏场景排在前面） */
  favored: boolean;
}>();

const emit = defineEmits<{
  (e: "select", scene: SceneInfo): void;
  (e: "edit", scene: SceneInfo): void;
  (e: "toggle-favorite", scene: SceneInfo): void;
  (e: "context-menu", scene: SceneInfo, event: MouseEvent): void;
  /** 室内外标注循环切换（原版在此直接写后端，本项目交给父组件落库） */
  (e: "update-location", scene: SceneInfo, location: NonNullable<SceneInfo["location_type"]>): void;
}>();

type LocationType = NonNullable<SceneInfo["location_type"]>;

const LOCATION_CYCLE: LocationType[] = ["indoor", "outdoor", "unknown"];
const LOCATION_LABELS: Record<LocationType, string> = {
  indoor: "室内",
  outdoor: "室外",
  unknown: "不确定",
};
const LOCATION_ICONS: Record<LocationType, Component> = {
  indoor: Home,
  outdoor: TreePine,
  unknown: CircleHelp,
};

const currentLocation = computed<LocationType>(() => props.scene.location_type ?? "unknown");
const locationMeta = computed(() => ({ icon: LOCATION_ICONS[currentLocation.value] }));

/** 点击标注循环切换（原版写后端，本项目直接改本地数据，父组件负责持久化） */
function cycleLocation(): void {
  const next = LOCATION_CYCLE[(LOCATION_CYCLE.indexOf(currentLocation.value) + 1) % 3]!;
  emit("update-location", props.scene, next);
}

// ===== 缩略图加载失败时切到灰色占位块 =====
const imgBroken = ref(false);
watch(
  () => props.scene.background,
  () => {
    imgBroken.value = false;
  },
);
</script>
