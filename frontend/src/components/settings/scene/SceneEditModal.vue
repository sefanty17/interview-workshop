<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="fixed inset-0 z-[9998] flex items-center justify-center bg-black/40 p-4 backdrop-blur-md"
        @click="$emit('close')"
      >
        <div
          class="relative flex max-h-[92dvh] w-full max-w-7xl overflow-hidden rounded-3xl border border-white/20 bg-slate-900/40 shadow-2xl"
          @click.stop
        >
          <!-- ====== 左栏：表单 ====== -->
          <div class="flex w-105 shrink-0 flex-col border-r border-white/10">
            <!-- Header -->
            <div
              class="flex shrink-0 items-center justify-between border-b border-white/10 bg-white/10 p-4"
            >
              <h3 class="text-lg leading-none font-bold text-white">
                {{ mode === "create" ? "添加场景" : "更新场景" }}
              </h3>
              <button
                @click="$emit('close')"
                class="flex items-center justify-center rounded-full p-2 text-white/50 transition-colors hover:bg-red-500/20 hover:text-white"
              >
                <X :size="18" />
              </button>
            </div>

            <!-- Scrollable body -->
            <div class="flex-1 space-y-4 overflow-y-auto p-4">
              <!-- 场景名称 -->
              <section>
                <label
                  class="mb-1.5 flex items-center gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80"
                >
                  <span class="h-3 w-1 rounded-full bg-orange-500"></span>
                  场景名称
                </label>
                <input
                  v-model="formData.sceneName"
                  placeholder="例如：海边日落"
                  class="w-full rounded-xl border border-white/10 bg-black/40 px-3 py-2 text-sm text-white placeholder-white/30 transition-all focus:border-indigo-400 focus:ring-1 focus:ring-indigo-400/50 focus:outline-none"
                />
              </section>

              <!-- 场景图片 -->
              <section>
                <label
                  class="mb-1.5 flex items-center gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80"
                >
                  <span class="h-3 w-1 rounded-full bg-indigo-500"></span>
                  场景图片
                </label>
                <div class="flex gap-2">
                  <select
                    v-model="formData.sceneImage"
                    class="flex-1 appearance-none rounded-xl border border-white/10 bg-black/40 px-3 py-2 text-sm text-white transition-all focus:border-indigo-400 focus:ring-1 focus:ring-indigo-400/50 focus:outline-none"
                    style="color-scheme: dark"
                  >
                    <option value="">选择背景图片</option>
                    <option
                      v-for="bg in backgroundOptions"
                      :key="bg.url"
                      :value="bg.url"
                      class="bg-slate-800 text-white"
                    >
                      {{ bg.title }}
                    </option>
                  </select>
                  <button
                    @click="triggerUpload"
                    class="rounded-xl border border-white/20 bg-white/10 px-4 text-sm whitespace-nowrap text-white hover:bg-white/20"
                  >
                    上传
                  </button>
                </div>
                <input
                  ref="uploadInput"
                  type="file"
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  class="hidden"
                  @change="handleFileUpload"
                />
              </section>

              <!-- 场景描述 -->
              <section>
                <label
                  class="mb-1.5 flex items-center gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80"
                >
                  <span class="h-3 w-1 rounded-full bg-emerald-500"></span>
                  场景描述
                </label>
                <textarea
                  v-model="formData.sceneDescription"
                  placeholder="描述场景的环境、氛围、光线等"
                  rows="2"
                  class="w-full resize-none rounded-xl border border-white/10 bg-black/40 px-3 py-2 text-sm text-white placeholder-white/30 transition-all focus:border-indigo-400 focus:ring-1 focus:ring-indigo-400/50 focus:outline-none"
                ></textarea>
              </section>

              <!-- 所属分类 -->
              <section>
                <label
                  class="mb-1.5 flex items-center gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80"
                >
                  <span class="h-3 w-1 rounded-full bg-sky-500"></span>
                  所属分类
                </label>
                <select
                  v-model="formData.category"
                  class="w-full appearance-none rounded-xl border border-white/10 bg-black/40 px-3 py-2 text-sm text-white transition-all focus:border-indigo-400 focus:ring-1 focus:ring-indigo-400/50 focus:outline-none"
                  style="color-scheme: dark"
                >
                  <option :value="ROOT_CATEGORY">根目录</option>
                  <option v-for="c in writableCategories" :key="c" :value="c">{{ c }}</option>
                </select>
              </section>

              <!-- 室内外标注 -->
              <section>
                <label
                  class="mb-1.5 flex items-center gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80"
                >
                  <span class="h-3 w-1 rounded-full bg-purple-500"></span>
                  室内外标注
                </label>
                <select
                  v-model="formData.location"
                  class="w-full appearance-none rounded-xl border border-white/10 bg-black/40 px-3 py-2 text-sm text-white transition-all focus:border-indigo-400 focus:ring-1 focus:ring-indigo-400/50 focus:outline-none"
                  style="color-scheme: dark"
                >
                  <option value="indoor">室内</option>
                  <option value="outdoor">室外</option>
                  <option value="unknown">不确定</option>
                </select>
              </section>

              <!-- 光影参数（可折叠） -->
              <section>
                <button
                  @click="showLighting = !showLighting"
                  class="mb-1.5 flex w-full items-center justify-between gap-2 text-xs font-bold tracking-widest text-gray-200/90 uppercase opacity-80 transition-opacity hover:opacity-100"
                >
                  <span class="flex items-center gap-2">
                    <span class="h-3 w-1 rounded-full bg-pink-500"></span>
                    光影参数
                  </span>
                  <svg
                    :class="['h-3.5 w-3.5 transition-transform', showLighting ? 'rotate-90' : '']"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M9 5l7 7-7 7"
                    />
                  </svg>
                </button>

                <div v-show="showLighting" class="space-y-2.5 pl-3">
                  <label class="flex cursor-pointer items-center gap-2">
                    <input
                      v-model="formData.lightingEnabled"
                      type="checkbox"
                      class="h-3.5 w-3.5 rounded accent-purple-500"
                    />
                    <span class="text-xs text-white/70">为此场景启用光影参数</span>
                  </label>

                  <template v-if="formData.lightingEnabled">
                    <label class="flex cursor-pointer items-center gap-2 pl-4">
                      <input
                        v-model="formData.overlayEnabled"
                        type="checkbox"
                        class="h-3.5 w-3.5 rounded accent-amber-500"
                      />
                      <span class="text-xs text-white/70">启用光照叠加层</span>
                    </label>

                    <!-- ======== 角色滤镜 ======== -->
                    <div class="pt-1 text-[11px] font-bold tracking-wider text-white/40 uppercase">
                      角色滤镜
                    </div>

                    <div
                      v-for="s in charFilterSliders"
                      :key="'char-' + s.key"
                      class="flex items-center gap-2"
                    >
                      <span class="w-16 shrink-0 text-[11px] text-white/50">{{ s.label }}</span>
                      <input
                        type="range"
                        :min="s.min"
                        :max="s.max"
                        :step="s.step"
                        v-model.number="formData[s.key]"
                        class="lighting-range flex-1"
                        :style="{ '--accent-color': s.color }"
                      />
                      <span class="w-12 text-right text-[11px] text-white/50 tabular-nums"
                        >{{ formData[s.key] }}{{ s.unit }}</span
                      >
                    </div>

                    <div class="flex items-center gap-3">
                      <span class="shrink-0 text-[11px] text-white/50">发光色</span>
                      <input
                        v-model="formData.charGlowColor"
                        type="color"
                        class="h-6 w-6 shrink-0 cursor-pointer rounded border-0 bg-transparent"
                      />
                      <span class="truncate text-[11px] text-white/30">{{
                        formData.charGlowColor
                      }}</span>
                    </div>

                    <!-- ======== 背景滤镜 ======== -->
                    <div class="pt-1 text-[11px] font-bold tracking-wider text-white/40 uppercase">
                      背景滤镜
                    </div>

                    <div
                      v-for="s in bgFilterSliders"
                      :key="'bg-' + s.key"
                      class="flex items-center gap-2"
                    >
                      <span class="w-16 shrink-0 text-[11px] text-white/50">{{ s.label }}</span>
                      <input
                        type="range"
                        :min="s.min"
                        :max="s.max"
                        :step="s.step"
                        v-model.number="formData[s.key]"
                        class="lighting-range flex-1"
                        :style="{ '--accent-color': s.color }"
                      />
                      <span class="w-12 text-right text-[11px] text-white/50 tabular-nums"
                        >{{ formData[s.key] }}{{ s.unit }}</span
                      >
                    </div>

                    <div class="flex items-center gap-3">
                      <span class="shrink-0 text-[11px] text-white/50">发光色</span>
                      <input
                        v-model="formData.bgGlowColor"
                        type="color"
                        class="h-6 w-6 shrink-0 cursor-pointer rounded border-0 bg-transparent"
                      />
                      <span class="truncate text-[11px] text-white/30">{{
                        formData.bgGlowColor
                      }}</span>
                    </div>

                    <!-- ======== 光照叠加（仅 overlayEnabled 时显示） ======== -->
                    <template v-if="formData.overlayEnabled">
                      <div
                        class="pt-1 text-[11px] font-bold tracking-wider text-white/40 uppercase"
                      >
                        光照叠加
                      </div>

                      <div class="flex items-center gap-3">
                        <span class="shrink-0 text-[11px] text-white/50">混合</span>
                        <select
                          v-model="formData.blendMode"
                          class="flex-1 rounded-lg border border-white/10 bg-black/40 px-2 py-1 text-[11px] text-white focus:ring-1 focus:ring-purple-400/50 focus:outline-none"
                          style="color-scheme: dark"
                        >
                          <option value="normal">Normal</option>
                          <option value="multiply">Multiply</option>
                          <option value="screen">Screen</option>
                          <option value="overlay">Overlay</option>
                          <option value="soft-light">Soft Light</option>
                          <option value="hard-light">Hard Light</option>
                          <option value="color-dodge">Color Dodge</option>
                          <option value="color-burn">Color Burn</option>
                          <option value="difference">Difference</option>
                        </select>
                      </div>

                      <div
                        v-for="s in overlaySliders"
                        :key="'ov-' + s.key"
                        class="flex items-center gap-2"
                      >
                        <span class="w-16 shrink-0 text-[11px] text-white/50">{{ s.label }}</span>
                        <input
                          type="range"
                          :min="s.min"
                          :max="s.max"
                          :step="s.step"
                          v-model.number="formData[s.key]"
                          class="lighting-range flex-1"
                          :style="{ '--accent-color': s.color }"
                        />
                        <span class="w-12 text-right text-[11px] text-white/50 tabular-nums"
                          >{{ formData[s.key] }}{{ s.unit }}</span
                        >
                      </div>

                      <div class="flex items-center gap-3">
                        <span class="shrink-0 text-[11px] text-white/50">中心色</span>
                        <input
                          v-model="formData.overlayColor1"
                          type="color"
                          class="h-6 w-6 shrink-0 cursor-pointer rounded border-0 bg-transparent"
                        />
                        <span class="truncate text-[11px] text-white/30">{{
                          formData.overlayColor1
                        }}</span>
                      </div>
                      <div class="flex items-center gap-3">
                        <span class="shrink-0 text-[11px] text-white/50">边缘色</span>
                        <input
                          v-model="formData.overlayColor2"
                          type="color"
                          class="h-6 w-6 shrink-0 cursor-pointer rounded border-0 bg-transparent"
                        />
                        <span class="truncate text-[11px] text-white/30">{{
                          formData.overlayColor2
                        }}</span>
                      </div>

                      <div
                        v-for="s in overlayExtraSliders"
                        :key="'ove-' + s.key"
                        class="flex items-center gap-2"
                      >
                        <span class="w-16 shrink-0 text-[11px] text-white/50">{{ s.label }}</span>
                        <input
                          type="range"
                          :min="s.min"
                          :max="s.max"
                          :step="s.step"
                          v-model.number="formData[s.key]"
                          class="lighting-range flex-1"
                          :style="{ '--accent-color': s.color }"
                        />
                        <span class="w-12 text-right text-[11px] text-white/50 tabular-nums"
                          >{{ formData[s.key] }}{{ s.unit }}</span
                        >
                      </div>

                      <div class="flex items-center gap-3">
                        <span class="shrink-0 text-[11px] text-white/50">作用目标</span>
                        <select
                          v-model="formData.overlayTarget"
                          class="flex-1 rounded-lg border border-white/10 bg-black/40 px-2 py-1 text-[11px] text-white focus:ring-1 focus:ring-purple-400/50 focus:outline-none"
                          style="color-scheme: dark"
                        >
                          <option value="both">角色 + 背景</option>
                          <option value="character">仅角色</option>
                          <option value="background">仅背景</option>
                        </select>
                      </div>
                    </template>

                    <button
                      @click="resetLighting"
                      class="text-[11px] text-white/40 underline transition-colors hover:text-white/80"
                    >
                      重置为默认值
                    </button>
                  </template>
                </div>
              </section>
            </div>

            <!-- Footer -->
            <div
              class="flex shrink-0 items-center justify-end gap-2 border-t border-white/10 bg-white/5 p-3"
            >
              <button
                @click="$emit('close')"
                class="rounded-full border border-white/20 bg-transparent px-4 py-1.5 text-sm text-white/70 hover:bg-white/10 hover:text-white"
              >
                取消
              </button>
              <button
                @click="handleSubmit"
                :disabled="!formData.sceneName.trim()"
                class="min-w-17.5 rounded-full border-none bg-indigo-500 px-4 py-1.5 text-sm font-semibold text-white shadow-[0_0_10px_rgba(99,102,241,0.5)] hover:bg-indigo-400 disabled:opacity-50 disabled:shadow-none"
              >
                {{ mode === "create" ? "创建" : "更新" }}
              </button>
            </div>
          </div>

          <!-- ====== 右栏：实时预览 ====== -->
          <div class="flex min-w-0 flex-1 flex-col bg-black/20 p-5">
            <span class="mb-3 shrink-0 text-xs font-bold tracking-widest text-white/40 uppercase"
              >实时预览</span
            >
            <div
              class="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden rounded-xl border border-white/10 bg-slate-800/50"
            >
              <!-- 背景图（应用背景滤镜） -->
              <img
                v-if="selectedBackgroundPreview"
                :src="selectedBackgroundPreview"
                class="absolute inset-0 h-full w-full object-cover"
                :style="previewBgFilterStyle"
              />
              <!-- 光照叠加层 -->
              <div
                v-if="formData.lightingEnabled && formData.overlayEnabled"
                class="pointer-events-none absolute inset-0 z-10"
                :style="previewOverlayStyle"
              ></div>
              <!-- 占位 -->
              <div v-if="!selectedBackgroundPreview" class="text-sm text-white/20">
                选择背景图片后显示预览
              </div>
            </div>
            <div class="mt-2 flex shrink-0 gap-3 text-xs text-white/40">
              <span
                v-if="
                  formData.lightingEnabled &&
                  formData.overlayEnabled &&
                  formData.blendMode !== 'normal'
                "
                >叠加混合: {{ formData.blendMode }}</span
              >
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { X } from "@lucide/vue";
import { store } from "../../../store/appStore";
import {
  ALL_CATEGORY,
  ROOT_CATEGORY,
  VIRTUAL_CATEGORY,
} from "../background/categories";
import type { LightingParams, SceneInfo } from "../../../data/mock";

const props = defineProps<{
  show: boolean;
  mode: "create" | "update";
  /** 正在编辑的场景（创建时为 null） */
  scene?: SceneInfo | null;
}>();

const emit = defineEmits<{
  close: [];
  submit: [
    data: {
      sceneName: string;
      sceneImage: string | null;
      sceneDescription: string;
      category: string | null;
      location: NonNullable<SceneInfo["location_type"]>;
      lighting: LightingParams | null;
    },
  ];
}>();

// ---- 可写入的分类（不含「全部 / 插件 / 根目录」） ----
const writableCategories = computed(() =>
  store.sceneCategories.filter((c) => c !== ALL_CATEGORY && c !== VIRTUAL_CATEGORY),
);

// ---- 背景图选项：来自已有场景的背景 + 本次上传 ----
const uploadedBackgrounds = ref<{ url: string; title: string }[]>([]);

const backgroundOptions = computed<{ url: string; title: string }[]>(() => {
  const map = new Map<string, string>();
  for (const s of store.scenes) {
    if (s.background && !map.has(s.background)) map.set(s.background, s.scene_name);
  }
  for (const u of uploadedBackgrounds.value) {
    if (!map.has(u.url)) map.set(u.url, u.title);
  }
  return Array.from(map.entries()).map(([url, title]) => ({ url, title }));
});

// ---- 文件上传（本地文件转 objectURL 预览） ----
const uploadInput = ref<HTMLInputElement | null>(null);

function triggerUpload(): void {
  uploadInput.value?.click();
}

function handleFileUpload(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (!file.type.startsWith("image/")) return;
  const url = URL.createObjectURL(file);
  uploadedBackgrounds.value.push({ url, title: file.name });
  formData.sceneImage = url;
}

// ---- defaults ----

const DEF = {
  brightness: 1.0,
  contrast: 1.0,
  saturation: 1.0,
  sepia: 0.0,
  glowRadius: 0,
  glowColor: "#ffaa33",
  blendMode: "normal",
  lightX: 50,
  lightY: 50,
  overlayColor1: "#ffb44b",
  overlayColor2: "#18202e",
  overlayRadius: 80,
  overlayOpacity: 50, // slider 0–100, saved as 0.0–1.0
  overlayTarget: "both",
};

// ---- reactive form ----

interface FormData {
  sceneName: string;
  sceneImage: string;
  sceneDescription: string;
  category: string;
  location: NonNullable<SceneInfo["location_type"]>;
  lightingEnabled: boolean;
  overlayEnabled: boolean;
  // 角色滤镜
  charBrightness: number;
  charContrast: number;
  charSaturation: number;
  charSepia: number;
  charGlowRadius: number;
  charGlowColor: string;
  // 背景滤镜
  bgBrightness: number;
  bgContrast: number;
  bgSaturation: number;
  bgSepia: number;
  bgGlowRadius: number;
  bgGlowColor: string;
  // 叠加
  blendMode: string;
  lightX: number;
  lightY: number;
  overlayColor1: string;
  overlayColor2: string;
  overlayRadius: number;
  overlayOpacity: number;
  overlayTarget: string;
  [key: string]: string | number | boolean; // 索引签名以支持 v-model="formData[key]"
}

const formData = reactive<FormData>({
  sceneName: "",
  sceneImage: "",
  sceneDescription: "",
  category: ROOT_CATEGORY,
  location: "indoor",
  lightingEnabled: false,
  overlayEnabled: false,
  charBrightness: DEF.brightness,
  charContrast: DEF.contrast,
  charSaturation: DEF.saturation,
  charSepia: DEF.sepia,
  charGlowRadius: DEF.glowRadius,
  charGlowColor: DEF.glowColor,
  bgBrightness: DEF.brightness,
  bgContrast: DEF.contrast,
  bgSaturation: DEF.saturation,
  bgSepia: DEF.sepia,
  bgGlowRadius: DEF.glowRadius,
  bgGlowColor: DEF.glowColor,
  blendMode: DEF.blendMode,
  lightX: DEF.lightX,
  lightY: DEF.lightY,
  overlayColor1: DEF.overlayColor1,
  overlayColor2: DEF.overlayColor2,
  overlayRadius: DEF.overlayRadius,
  overlayOpacity: DEF.overlayOpacity,
  overlayTarget: DEF.overlayTarget,
});

// ---- slider configs ----

interface SliderDef {
  key: keyof FormData;
  label: string;
  min: number;
  max: number;
  step: number;
  color: string;
  unit: string;
}

const charFilterSliders = computed<SliderDef[]>(() => [
  { key: "charBrightness", label: "亮度", min: 0.3, max: 2.2, step: 0.01, color: "#8b5cf6", unit: "" },
  { key: "charContrast", label: "对比度", min: 0.5, max: 2.0, step: 0.01, color: "#8b5cf6", unit: "" },
  { key: "charSaturation", label: "饱和度", min: 0.0, max: 2.5, step: 0.01, color: "#8b5cf6", unit: "" },
  { key: "charSepia", label: "暖色调", min: 0.0, max: 1.0, step: 0.01, color: "#8b5cf6", unit: "" },
  { key: "charGlowRadius", label: "发光半径", min: 0, max: 50, step: 1, color: "#f59e0b", unit: "px" },
]);

const bgFilterSliders = computed<SliderDef[]>(() => [
  { key: "bgBrightness", label: "亮度", min: 0.3, max: 2.2, step: 0.01, color: "#06b6d4", unit: "" },
  { key: "bgContrast", label: "对比度", min: 0.5, max: 2.0, step: 0.01, color: "#06b6d4", unit: "" },
  { key: "bgSaturation", label: "饱和度", min: 0.0, max: 2.5, step: 0.01, color: "#06b6d4", unit: "" },
  { key: "bgSepia", label: "暖色调", min: 0.0, max: 1.0, step: 0.01, color: "#06b6d4", unit: "" },
  { key: "bgGlowRadius", label: "发光半径", min: 0, max: 50, step: 1, color: "#f59e0b", unit: "px" },
]);

const overlaySliders = computed<SliderDef[]>(() => [
  { key: "lightX", label: "光源 X", min: 0, max: 100, step: 1, color: "#fbbf24", unit: "%" },
  { key: "lightY", label: "光源 Y", min: 0, max: 100, step: 1, color: "#fbbf24", unit: "%" },
]);

const overlayExtraSliders = computed<SliderDef[]>(() => [
  { key: "overlayRadius", label: "光照半径", min: 10, max: 100, step: 1, color: "#fbbf24", unit: "%" },
  { key: "overlayOpacity", label: "光照强度", min: 0, max: 100, step: 1, color: "#fbbf24", unit: "%" },
]);

// ---- sub-state ----

const showLighting = ref(false);

// ---- reset ----

function resetLighting() {
  formData.lightingEnabled = true;
  formData.overlayEnabled = false;
  formData.charBrightness = DEF.brightness;
  formData.charContrast = DEF.contrast;
  formData.charSaturation = DEF.saturation;
  formData.charSepia = DEF.sepia;
  formData.charGlowRadius = DEF.glowRadius;
  formData.charGlowColor = DEF.glowColor;
  formData.bgBrightness = DEF.brightness;
  formData.bgContrast = DEF.contrast;
  formData.bgSaturation = DEF.saturation;
  formData.bgSepia = DEF.sepia;
  formData.bgGlowRadius = DEF.glowRadius;
  formData.bgGlowColor = DEF.glowColor;
  formData.blendMode = DEF.blendMode;
  formData.lightX = DEF.lightX;
  formData.lightY = DEF.lightY;
  formData.overlayColor1 = DEF.overlayColor1;
  formData.overlayColor2 = DEF.overlayColor2;
  formData.overlayRadius = DEF.overlayRadius;
  formData.overlayOpacity = DEF.overlayOpacity;
  formData.overlayTarget = DEF.overlayTarget;
}

// ---- init from scene ----

watch(
  () => props.show,
  (val) => {
    uploadedBackgrounds.value = [];
    if (val && props.scene) {
      const s = props.scene;
      formData.sceneName = s.scene_name;
      formData.sceneImage = s.background || "";
      formData.sceneDescription = s.scene_description;
      formData.category = s.category ?? ROOT_CATEGORY;
      formData.location = s.location_type ?? "unknown";
      const l = s.lighting;
      if (l) {
        formData.lightingEnabled = true;
        formData.overlayEnabled = l.overlay_enabled;
        formData.charBrightness = l.character.brightness;
        formData.charContrast = l.character.contrast;
        formData.charSaturation = l.character.saturation;
        formData.charSepia = l.character.sepia;
        formData.charGlowRadius = l.character.glow_radius;
        formData.charGlowColor = l.character.glow_color;
        formData.bgBrightness = l.background.brightness;
        formData.bgContrast = l.background.contrast;
        formData.bgSaturation = l.background.saturation;
        formData.bgSepia = l.background.sepia;
        formData.bgGlowRadius = l.background.glow_radius;
        formData.bgGlowColor = l.background.glow_color;
        formData.blendMode = l.blend_mode;
        formData.lightX = l.light_x;
        formData.lightY = l.light_y;
        formData.overlayColor1 = l.overlay_color1;
        formData.overlayColor2 = l.overlay_color2;
        formData.overlayRadius = l.overlay_radius ?? 80;
        formData.overlayOpacity = (l.overlay_opacity ?? 0.5) * 100;
        formData.overlayTarget = l.overlay_target || "both";
        showLighting.value = true;
      } else {
        formData.lightingEnabled = false;
        resetLighting();
        showLighting.value = false;
      }
    } else if (val) {
      formData.sceneName = "";
      formData.sceneImage = "";
      formData.sceneDescription = "";
      formData.category = ROOT_CATEGORY;
      formData.location = "indoor";
      formData.lightingEnabled = false;
      resetLighting();
      showLighting.value = false;
    }
  },
);

// ---- preview computed ----

const selectedBackgroundPreview = computed(() => {
  if (!formData.sceneImage) return null;
  return formData.sceneImage;
});

function buildFilterString(
  brightness: number,
  contrast: number,
  saturation: number,
  sepia: number,
  glowRadius: number,
  glowColor: string,
) {
  const parts: string[] = [];
  if (brightness !== 1.0) parts.push(`brightness(${brightness})`);
  if (contrast !== 1.0) parts.push(`contrast(${contrast})`);
  if (saturation !== 1.0) parts.push(`saturate(${saturation})`);
  if (sepia > 0) parts.push(`sepia(${sepia})`);
  if (glowRadius > 0) parts.push(`drop-shadow(0 0 ${glowRadius}px ${glowColor})`);
  return parts.join(" ");
}

const previewBgFilterStyle = computed(() => {
  if (!formData.lightingEnabled) return undefined;
  const filter = buildFilterString(
    formData.bgBrightness,
    formData.bgContrast,
    formData.bgSaturation,
    formData.bgSepia,
    formData.bgGlowRadius,
    formData.bgGlowColor,
  );
  return filter ? { filter } : undefined;
});

const previewOverlayStyle = computed(() => {
  if (!formData.lightingEnabled || !formData.overlayEnabled) return undefined;
  const blend = formData.blendMode !== "normal" ? formData.blendMode : "overlay";
  return `background: radial-gradient(circle at ${formData.lightX}% ${formData.lightY}%, ${formData.overlayColor1} 0%, ${formData.overlayColor2} ${formData.overlayRadius}%); mix-blend-mode: ${blend}; opacity: ${formData.overlayOpacity / 100}`;
});

// ---- submit ----

const handleSubmit = () => {
  let lighting: LightingParams | null = null;
  if (formData.lightingEnabled) {
    const charFilter = {
      brightness: formData.charBrightness,
      contrast: formData.charContrast,
      saturation: formData.charSaturation,
      sepia: formData.charSepia,
      glow_radius: formData.charGlowRadius,
      glow_color: formData.charGlowColor,
    };
    const bgFilter = {
      brightness: formData.bgBrightness,
      contrast: formData.bgContrast,
      saturation: formData.bgSaturation,
      sepia: formData.bgSepia,
      glow_radius: formData.bgGlowRadius,
      glow_color: formData.bgGlowColor,
    };
    lighting = {
      character: charFilter,
      background: bgFilter,
      overlay_enabled: formData.overlayEnabled,
      blend_mode: formData.blendMode,
      light_x: formData.lightX,
      light_y: formData.lightY,
      overlay_color1: formData.overlayColor1,
      overlay_color2: formData.overlayColor2,
      overlay_radius: formData.overlayRadius,
      overlay_opacity: formData.overlayOpacity / 100,
      overlay_target: formData.overlayTarget,
    };
  }
  emit("submit", {
    sceneName: formData.sceneName.trim(),
    sceneImage: formData.sceneImage || null,
    sceneDescription: formData.sceneDescription.trim(),
    category: formData.category === ROOT_CATEGORY ? null : formData.category,
    location: formData.location,
    lighting,
  });
};
</script>

<style scoped>
/* 实时响应的 range input 样式 */
.lighting-range {
  -webkit-appearance: none;
  appearance: none;
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.15);
  outline: none;
  cursor: pointer;
}

.lighting-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent-color, #8b5cf6);
  border: 2px solid rgba(255, 255, 255, 0.8);
  cursor: grab;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.lighting-range::-webkit-slider-thumb:active {
  cursor: grabbing;
  transform: scale(1.15);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
