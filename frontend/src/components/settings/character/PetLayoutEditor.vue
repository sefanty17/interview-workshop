<template>
  <section class="space-y-3 rounded-xl border border-white/10 bg-white/5 p-4">
    <h3 class="text-sm font-semibold text-white/80">桌宠布局预览</h3>
    <p class="text-xs leading-relaxed text-white/50">
      拖动调整桌宠立绘，滚轮缩放；方向键微调，Shift 加速。修改后点击底部保存生效，预览不会移动桌宠窗口。
    </p>
    <div class="flex flex-wrap gap-2">
      <select
        v-model="costume"
        class="rounded-lg border border-white/10 bg-[#252535] px-2 py-1.5 text-xs text-white"
        aria-label="服装"
      >
        <option value="default">默认服装</option>
        <option v-for="name in costumeNames" :key="name" :value="name">{{ name }}</option>
      </select>
      <select
        v-model="emotion"
        class="rounded-lg border border-white/10 bg-[#252535] px-2 py-1.5 text-xs text-white"
        aria-label="预览情绪"
      >
        <option v-for="name in Object.keys(EMOTION_CONFIG_EMO)" :key="name" :value="name">
          {{ name }}
        </option>
      </select>
      <label class="flex items-center gap-2 text-xs text-white/70"
        ><input
          type="checkbox"
          :checked="modelValue.pet_frameless"
          @change="patch({ pet_frameless: ($event.target as HTMLInputElement).checked })"
        />无框桌宠</label
      >
      <label class="flex items-center gap-2 text-xs text-white/70"
        ><input v-model="showBounds" type="checkbox" />显示裁切边界</label
      >
      <button
        type="button"
        class="rounded-lg bg-white/10 px-3 py-1.5 text-xs text-white/75 hover:bg-white/15"
        @click="patch({ scale_p: 1, offset_x_p: 0, offset_y_p: 0 })"
      >
        重置桌宠布局
      </button>
    </div>
    <div
      ref="frame"
      class="relative mx-auto aspect-square w-full max-w-[360px] overflow-hidden rounded-xl border border-white/10 bg-black/20"
    >
      <div
        tabindex="0"
        role="group"
        aria-label="桌宠布局画布，可拖动或使用方向键调整"
        class="absolute top-0 left-0 origin-top-left touch-none outline-none select-none focus-visible:ring-2 focus-visible:ring-cyan-300"
        :style="{
          width: `${logicalSize}px`,
          height: `${logicalSize}px`,
          transform: `scale(${previewScale})`,
        }"
        @pointerdown="startDrag"
        @pointermove="moveDrag"
        @pointerup="finishDrag"
        @pointercancel="finishDrag"
        @lostpointercapture="finishDrag"
        @keydown="nudge"
        @wheel.prevent="zoom"
      >
        <div
          class="pointer-events-none absolute top-6 left-6"
          :style="{ width: `${petSize}px`, height: `${petSize}px` }"
        >
          <div v-if="showBounds && imageUrl" class="absolute inset-0 opacity-25" :style="imageTransform">
            <ImageAcrossFade
              class="h-full w-full"
              :style="{ top: '-10px' }"
              :src="imageUrl"
              :duration="0"
              position="center 0%"
              object-fit="cover"
            />
          </div>
          <div
            class="absolute inset-0 overflow-hidden"
            :class="
              modelValue.pet_frameless
                ? ''
                : 'rounded-full border-2 border-white/60 bg-white/10 shadow-[0_8px_32px_rgba(0,176,255,0.15)]'
            "
          >
            <div class="h-full w-full origin-top" :style="imageTransform">
              <ImageAcrossFade
                v-if="imageUrl"
                class="h-full w-full"
                :style="{ top: '-10px' }"
                :src="imageUrl"
                :duration="0"
                position="center 0%"
                object-fit="cover"
              />
            </div>
          </div>
          <div v-if="showBounds" class="absolute inset-0 border border-dashed border-cyan-200/50"></div>
        </div>
      </div>
    </div>
    <p v-if="!imageUrl" role="status" class="text-xs text-amber-200/80">
      当前服装缺少此情绪的立绘，可在差分管理中补充。
    </p>
    <label class="flex items-center gap-3 text-xs text-white/60"
      >桌宠缩放<input
        type="range"
        min="0.1"
        max="3"
        step="0.01"
        :value="modelValue.scale_p ?? 1"
        class="min-w-0 flex-1 accent-[#79d9ff]"
        @input="patch({ scale_p: Number(($event.target as HTMLInputElement).value) })"
      /><span class="w-12 text-right tabular-nums">{{
        Number(modelValue.scale_p ?? 1).toFixed(2)
      }}</span></label
    >
    <label class="flex items-center gap-3 text-xs text-white/60"
      >水平偏移<input
        type="range"
        min="-300"
        max="300"
        step="1"
        :value="modelValue.offset_x_p ?? 0"
        class="min-w-0 flex-1 accent-[#79d9ff]"
        @input="patch({ offset_x_p: Number(($event.target as HTMLInputElement).value) })"
      /><span class="w-12 text-right tabular-nums">{{
        Math.round(Number(modelValue.offset_x_p ?? 0))
      }}</span></label
    >
    <label class="flex items-center gap-3 text-xs text-white/60"
      >垂直偏移<input
        type="range"
        min="-300"
        max="300"
        step="1"
        :value="modelValue.offset_y_p ?? 0"
        class="min-w-0 flex-1 accent-[#79d9ff]"
        @input="patch({ offset_y_p: Number(($event.target as HTMLInputElement).value) })"
      /><span class="w-12 text-right tabular-nums">{{
        Math.round(Number(modelValue.offset_y_p ?? 0))
      }}</span></label
    >
    <p class="text-xs leading-relaxed text-white/40">
      当前舞台 {{ petSize }} × {{ petSize }} 像素，取自全局桌宠大小设置。虚线为舞台边界，淡色轮廓表示静态立绘被圆框或舞台裁切的部分；无框模式仍受方形舞台限制。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from "vue";
import { avatarUrl } from "../../../data/interviewers";
import { EMOTION_CONFIG_EMO } from "../../../utils/character-emotion";
import ImageAcrossFade from "./ImageAcrossFade.vue";

const props = defineProps<{
  roleId: string;
  modelValue: Record<string, any>;
  clothes?: Array<{ title: string }>;
}>();
const emit = defineEmits<{ "update:modelValue": [value: Record<string, any>] }>();
// 原版取 settings.pet?.scale；本项目没有全局桌宠设置，用默认舞台尺寸
const petSize = computed(() => 210);
const logicalSize = computed(() => petSize.value + 48);
const frame = ref<HTMLElement>();
const frameWidth = ref(1);
const previewScale = computed(() => frameWidth.value / logicalSize.value);
let observer: ResizeObserver | undefined;
onMounted(() => {
  observer = new ResizeObserver(() => {
    frameWidth.value = frame.value?.clientWidth || 1;
  });
  if (frame.value) {
    frameWidth.value = frame.value.clientWidth;
    observer.observe(frame.value);
  }
});
onUnmounted(() => observer?.disconnect());
const costume = ref("default");
const emotion = ref("正常");
const showBounds = ref(true);
const costumeNames = computed(
  () =>
    [
      ...new Set(
        [
          ...(props.clothes ?? []).map((c) => c.title),
          ...(props.modelValue.clothes ?? []).map((c: { name: string }) => c.name),
        ].filter((n) => n && n !== "default" && n !== "默认"),
      ),
    ] as string[],
);
/** 服装差分同样共用 /avatars/{code}/{情绪}.webp */
const imageUrl = computed(() => avatarUrl(props.roleId, EMOTION_CONFIG_EMO[emotion.value] ?? "正常"));
const previewScaleP = computed(() => Number(props.modelValue.scale_p ?? 1));
const offsetXP = computed(() => Number(props.modelValue.offset_x_p ?? 0));
const offsetYP = computed(() => Number(props.modelValue.offset_y_p ?? 0));
const imageTransform = computed(() => ({
  transform: `scale(${previewScaleP.value}) translate(${offsetXP.value}px, ${offsetYP.value}px)`,
  transformOrigin: "center top",
}));
function patch(values: Record<string, number | boolean>) {
  emit("update:modelValue", { ...props.modelValue, ...values });
}
let drag:
  | { pointer: number; x: number; y: number; offsetX: number; offsetY: number; ratio: number }
  | undefined;
function startDrag(event: PointerEvent) {
  if (event.button !== 0) return;
  const target = event.currentTarget as HTMLElement;
  target.setPointerCapture(event.pointerId);
  target.focus({ preventScroll: true });
  drag = {
    pointer: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    offsetX: offsetXP.value,
    offsetY: offsetYP.value,
    ratio: previewScale.value * (previewScaleP.value || 1),
  };
}
function moveDrag(event: PointerEvent) {
  if (drag && drag.pointer === event.pointerId)
    patch({
      offset_x_p: Math.round(drag.offsetX + (event.clientX - drag.x) / drag.ratio),
      offset_y_p: Math.round(drag.offsetY + (event.clientY - drag.y) / drag.ratio),
    });
}
function finishDrag() {
  drag = undefined;
}
function zoom(event: WheelEvent) {
  patch({
    scale_p: Math.max(
      0.1,
      Math.min(3, Math.round((previewScaleP.value + (event.deltaY < 0 ? 0.05 : -0.05)) * 100) / 100),
    ),
  });
}
function nudge(event: KeyboardEvent) {
  const delta = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[
    event.key
  ];
  if (!delta) return;
  event.preventDefault();
  const step = event.shiftKey ? 10 : 1;
  patch({
    offset_x_p: offsetXP.value + delta[0] * step,
    offset_y_p: offsetYP.value + delta[1] * step,
  });
}
watch(
  () => props.roleId,
  () => {
    costume.value = "default";
    emotion.value = "正常";
  },
);
</script>
