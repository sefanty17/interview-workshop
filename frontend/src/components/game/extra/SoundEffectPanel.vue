<template>
  <div class="pointer-events-auto relative">
    <!-- 底部左侧触发按钮（用定位容器包裹） -->
    <div class="fixed bottom-[calc(24px+var(--safe-area-inset-bottom))] left-6 z-[1000]">
      <Button
        type="nav"
        :icon="'sound'"
        ref="triggerRef"
        :class="[
          'flex items-center gap-2 px-4 py-2 transition-colors',
          hasActiveAudio ? 'pulse-icon text-[#4facfe]' : 'text-white',
        ]"
        @click.stop="panelVisible = !panelVisible"
      >
        <h3 class="m-0 hidden text-lg font-bold xl:block">声效</h3>
      </Button>
    </div>

    <!-- 弹出面板（复用日程面板样式） -->
    <Transition
      enter-active-class="transition-all duration-300"
      leave-active-class="transition-all duration-200"
      enter-from-class="opacity-0 -translate-y-2 scale-95"
      leave-to-class="opacity-0 -translate-y-2 scale-95"
    >
      <div
        v-if="panelVisible"
        ref="panelRef"
        class="custom-scrollbar fixed bottom-[calc(64px+var(--safe-area-inset-bottom))] left-4 z-[1000] box-border max-h-[80dvh] w-[520px] overflow-y-auto rounded-3xl border border-white/10 bg-[#12121c]/75 p-4 text-white shadow-[0_8px_32px_rgba(0,0,0,0.4)] backdrop-blur-[20px]"
      >
        <!-- ===== 音频频谱可视化 =====
             置顶（不是塞在末尾）：面板 max-h-[80dvh] 会滚动，放末尾等于藏起来。
             关闭时只占一行，开启后原地展开配色配置。 -->
        <div class="mb-3">
          <div class="flex items-center justify-between gap-3">
            <span class="flex items-center gap-2 text-sm font-semibold text-gray-300">
              <AudioLines :size="14" class="text-[#79d9ff]" />
              音频频谱
            </span>
            <div class="shrink-0">
              <Toggle :checked="spectrumEnabled" @change="onSpectrumToggle" />
            </div>
          </div>

          <template v-if="spectrumEnabled">
            <p class="mt-1.5 px-1 text-[11px] leading-snug text-gray-500">
              实时采集正在播放的背景音乐与环境音，显示在主界面右下角；当前为演示正弦波动画（需要后端服务接入真实音频采集）
            </p>

            <div class="mt-2 rounded-xl border border-white/5 bg-white/[0.03] px-3 py-2.5">
              <!-- 形态：镜像 / 柱状 / 圆环 -->
              <div class="mb-2.5 flex items-center gap-1 rounded-lg bg-black/20 p-0.5">
                <button
                  v-for="opt in styleOptions"
                  :key="opt.id"
                  type="button"
                  class="flex flex-1 cursor-pointer items-center justify-center gap-1 rounded-md px-1.5 py-1 text-[11px] transition-colors duration-150"
                  :class="
                    spectrumStyle === opt.id
                      ? 'bg-white/12 text-white'
                      : 'text-gray-400 hover:text-white'
                  "
                  :title="opt.label"
                  @click.stop="setSpectrumStyle(opt.id)"
                >
                  <component :is="opt.icon" :size="11" />
                  <span class="truncate">{{ opt.label }}</span>
                </button>
              </div>

              <div class="mb-2 flex items-center justify-between">
                <span class="text-xs text-gray-400">配色</span>
                <span class="text-[10px] text-gray-500">{{ paletteLabel }}</span>
              </div>

              <SpectrumPaletteChips
                v-model="spectrumPalette"
                :custom-from="spectrumColor1"
                :custom-to="spectrumColor2"
              />

              <!-- 自定义配色：双取色器 -->
              <div
                v-if="spectrumPalette === CUSTOM_SPECTRUM_PALETTE"
                class="mt-2.5 flex items-center gap-4"
              >
                <label class="flex cursor-pointer items-center gap-1.5 text-[11px] text-gray-400">
                  <input
                    type="color"
                    class="spectrum-color-input"
                    :value="spectrumColor1"
                    @input="onColorInput('spectrumColor1', $event)"
                  />
                  主色
                </label>
                <label class="flex cursor-pointer items-center gap-1.5 text-[11px] text-gray-400">
                  <input
                    type="color"
                    class="spectrum-color-input"
                    :value="spectrumColor2"
                    @input="onColorInput('spectrumColor2', $event)"
                  />
                  辅色
                </label>
              </div>
            </div>
          </template>
        </div>

        <div class="mb-4 h-px bg-white/5"></div>

        <!-- ===== BGM 区域 ===== -->
        <div class="mb-4">
          <div class="mb-3 flex items-center justify-between">
            <span class="flex items-center gap-2 text-sm font-semibold text-gray-300">
              <Music2 :size="14" class="text-[#79d9ff]" />
              背景音乐
            </span>
            <div class="flex items-center gap-1">
              <button
                @click.stop="handlePlayPause"
                class="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/10 hover:text-white"
                :title="bgm.paused ? '播放' : '暂停'"
              >
                <Play v-if="bgm.paused" :size="14" />
                <Pause v-else :size="14" />
              </button>
              <button
                @click.stop="handleStop"
                class="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/10 hover:text-white"
                title="停止"
              >
                <Square :size="13" />
              </button>
              <button
                @click.stop="togglePlaybackMode"
                class="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/10 hover:text-white"
                :title="modeText[bgm.mode]"
              >
                <Repeat v-if="bgm.mode === 'loop-list'" :size="13" />
                <Repeat1 v-else-if="bgm.mode === 'loop-single'" :size="13" />
                <Shuffle v-else :size="13" />
              </button>
            </div>
          </div>

          <!-- 未播放时显示指引 -->
          <div v-if="!hasBgm" class="py-4 text-center text-xs text-gray-500">
            前往
            <button class="text-link" @click.stop="openSettings">设置 → 声音</button>
            选择背景音乐
          </div>

          <!-- 有播放时显示曲目 + 控制 -->
          <template v-else>
            <div class="mb-2 truncate px-1 text-xs text-gray-400">
              {{ bgm.current }}
              <span v-if="bgm.mode" class="ml-2 text-gray-500">
                {{ modeText[bgm.mode] }}
              </span>
            </div>

            <!-- BGM 音量滑块 -->
            <div class="mb-2 flex items-center gap-2 px-1">
              <Volume2 :size="12" class="shrink-0 text-gray-500" />
              <input
                type="range"
                min="0"
                max="100"
                :value="settings.volumeBgm"
                @input.stop="
                  (e) => (settings.volumeBgm = Number((e.target as HTMLInputElement).value))
                "
                class="h-1 flex-1 cursor-pointer appearance-none rounded-full bg-white/10 accent-[#79d9ff]"
              />
              <span class="w-6 shrink-0 text-right text-[10px] text-gray-500 tabular-nums">{{
                settings.volumeBgm
              }}</span>
            </div>
          </template>

          <!-- BGM 曲目列表 -->
          <div class="overflow-hidden rounded-xl border border-white/5 bg-white/[0.03]">
            <div class="custom-scrollbar max-h-32 space-y-0.5 overflow-y-auto p-1">
              <div
                v-for="music in bgmList"
                :key="music"
                @click.stop="playMusic(music)"
                class="group flex cursor-pointer items-center gap-2 rounded-lg px-3 py-1.5 transition-all duration-150"
                :class="[
                  bgm.current === music && !bgm.stoped
                    ? 'bg-[#79d9ff]/20 text-[#79d9ff]'
                    : 'text-gray-400 hover:bg-white/10 hover:text-white',
                ]"
              >
                <span class="flex-1 truncate text-xs">{{ music }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 分隔线 -->
        <div class="mb-4 h-px bg-white/5"></div>

        <!-- ===== 环境音区域 ===== -->
        <div>
          <div class="mb-3 flex items-center justify-between">
            <span class="flex items-center gap-2 text-sm font-semibold text-gray-300">
              <Wind :size="14" class="text-[#79d9ff]" />
              环境音
              <span class="text-xs font-normal text-gray-500"
                >({{ settings.ambientTracks.length }})</span
              >
            </span>
            <button
              v-if="settings.ambientTracks.length > 0"
              @click.stop="stopAllAmbient"
              class="flex items-center gap-1 rounded px-2 py-1 text-xs text-red-400 transition-colors hover:bg-red-500/10 hover:text-red-300"
            >
              <Square :size="10" /> 全部停止
            </button>
          </div>

          <!-- 活跃轨道列表 -->
          <div
            v-if="settings.ambientTracks.length === 0"
            class="rounded-xl bg-white/[0.03] py-4 text-center text-xs text-gray-500"
          >
            没有正在播放的环境音
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="track in settings.ambientTracks"
              :key="track.id"
              class="rounded-xl border border-white/5 bg-white/[0.03] px-3 py-2"
            >
              <!-- 轨道名 + 控制按钮 -->
              <div class="mb-2 flex items-center gap-2">
                <Wind :size="12" class="shrink-0 text-[#79d9ff]" />
                <span class="flex-1 truncate text-xs text-gray-200">{{ track.name }}</span>
                <button
                  @click.stop="track.paused = !track.paused"
                  class="rounded p-1 text-gray-400 transition-colors hover:bg-white/10 hover:text-white"
                  :title="track.paused ? '恢复' : '暂停'"
                >
                  <Play v-if="track.paused" :size="11" />
                  <Pause v-else :size="11" />
                </button>
                <button
                  @click.stop="removeAmbientTrack(track.id)"
                  class="rounded p-1 text-gray-500 transition-colors hover:bg-red-500/10 hover:text-red-400"
                  title="移除"
                >
                  <X :size="11" />
                </button>
              </div>
              <!-- 单轨音量滑块 -->
              <div class="flex items-center gap-2 pl-5">
                <Volume2 :size="10" class="shrink-0 text-gray-500" />
                <input
                  type="range"
                  min="0"
                  max="100"
                  :value="track.volume"
                  @input.stop="
                    (e) => (track.volume = Number((e.target as HTMLInputElement).value))
                  "
                  class="h-1 flex-1 cursor-pointer appearance-none rounded-full bg-white/10 accent-[#79d9ff]"
                />
                <span class="w-6 shrink-0 text-right text-[10px] text-gray-500 tabular-nums">{{
                  track.volume
                }}</span>
              </div>
            </div>
          </div>
          <!-- 环境音文件库（可选择播放） -->
          <div v-if="ambientFileList.length > 0" class="mt-3">
            <span class="mb-1.5 block text-xs text-gray-400">可用环境音</span>
            <div class="overflow-hidden rounded-xl border border-white/5 bg-white/[0.03]">
              <div class="custom-scrollbar max-h-28 space-y-0.5 overflow-y-auto p-1">
                <div
                  v-for="ambient in ambientFileList"
                  :key="ambient"
                  @click.stop="playAmbientFromList(ambient)"
                  class="group flex cursor-pointer items-center gap-2 rounded-lg px-3 py-1.5 text-gray-400 transition-all duration-150 hover:bg-white/10 hover:text-white"
                >
                  <Wind :size="12" class="shrink-0 text-[#79d9ff]" />
                  <span class="flex-1 truncate text-xs">{{ ambient }}</span>
                  <Play :size="10" class="opacity-0 transition-opacity group-hover:opacity-100" />
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, type Component } from "vue";
import {
  AudioLines,
  BarChart3,
  CircleDot,
  FlipVertical2,
  Music2,
  Pause,
  Play,
  Repeat,
  Repeat1,
  Shuffle,
  Square,
  Volume2,
  Wind,
  X,
} from "@lucide/vue";
import {
  CUSTOM_SPECTRUM_PALETTE,
  DEFAULT_SPECTRUM_COLOR_FROM,
  DEFAULT_SPECTRUM_COLOR_TO,
  DEFAULT_SPECTRUM_PALETTE,
  SPECTRUM_PALETTES,
  resolveSpectrumStyle,
  type SpectrumStyle,
} from "../../../constants/spectrum";
import { alertDialog } from "../../../store/dialogStore";
import { extraDemo } from "../../../store/extraDemo";
import { store, updateSettings } from "../../../store/appStore";
import Button from "../../base/widget/Button.vue";
import Toggle from "../../base/widget/Toggle.vue";
import SpectrumPaletteChips from "./SpectrumPaletteChips.vue";

const settings = store.settings;
const bgm = extraDemo.bgm;

// ===== 面板状态 =====
const panelVisible = ref(false);
const panelRef = ref<HTMLElement | null>(null);

// ===== 音频频谱可视化（右下角迷你频谱的配置，见 SpectrumVisualizer.vue）=====
const spectrumEnabled = computed(() => !!settings.spectrumEnabled);
const spectrumStyle = computed(() => resolveSpectrumStyle(settings.spectrumStyle));
/** 形态选项：id 取自 constants/spectrum.ts（单一真相源），文案与图标在此组装 */
const styleOptions: Array<{ id: SpectrumStyle; label: string; icon: Component }> = [
  { id: "mirror", label: "镜像", icon: FlipVertical2 },
  { id: "bars", label: "柱状", icon: BarChart3 },
  { id: "ring", label: "圆环", icon: CircleDot },
];
const spectrumPalette = computed({
  get: () => settings.spectrumPalette || DEFAULT_SPECTRUM_PALETTE,
  set: (id: string) => updateSettings({ spectrumPalette: id }),
});
const spectrumColor1 = computed(
  () => settings.spectrumColor1 || DEFAULT_SPECTRUM_COLOR_FROM,
);
const spectrumColor2 = computed(
  () => settings.spectrumColor2 || DEFAULT_SPECTRUM_COLOR_TO,
);
const paletteLabel = computed(
  () =>
    SPECTRUM_PALETTES.find((p) => p.id === spectrumPalette.value)?.label || "自定义",
);

const onSpectrumToggle = (enabled: boolean) => {
  updateSettings({ spectrumEnabled: enabled });
};

const setSpectrumStyle = (style: SpectrumStyle) => {
  updateSettings({ spectrumStyle: style });
};

const onColorInput = (field: "spectrumColor1" | "spectrumColor2", e: Event) => {
  updateSettings({ [field]: (e.target as HTMLInputElement).value });
};

// ===== 是否有活跃音频在播放（控制图标闪烁，暂停/停止时不闪） =====
const hasActiveAudio = computed(() => {
  const bgmPlaying = bgm.current !== "" && !bgm.paused && !bgm.stoped;
  const ambientPlaying =
    settings.ambientTracks.length > 0 && settings.ambientTracks.some((t) => !t.paused);
  return bgmPlaying || ambientPlaying;
});

// ===== BGM 相关（演示数据：无真实音频播放，点击仅作选中高亮） =====
const hasBgm = computed(() => bgm.current !== "" && !bgm.stoped);

const bgmList = [
  "深夜办公室 · 钢琴",
  "轻松咖啡馆 · 爵士",
  "雨天窗边 · Lo-Fi",
  "紧张倒计时 · 电子",
  "清晨校园 · 八音盒",
  "山顶风声 · Ambient",
];

const modeText = computed<Record<string, string>>(() => ({
  "loop-list": "列表循环",
  "loop-single": "单曲循环",
  random: "随机播放",
}));

/** 切换播放模式 */
const togglePlaybackMode = () => {
  const modes: Array<"loop-list" | "loop-single" | "random"> = [
    "loop-list",
    "loop-single",
    "random",
  ];
  const currentIndex = modes.indexOf(bgm.mode);
  bgm.mode = modes[(currentIndex + 1) % modes.length];
};

/** 播放/暂停 */
const handlePlayPause = () => {
  if (bgm.current === "") return; // 未选曲目时不自动选中
  bgm.paused = !bgm.paused;
};

/** 停止：清除选中状态，回退到"未选择音乐" */
const handleStop = () => {
  bgm.stoped = true;
  bgm.paused = true;
  bgm.current = "";
};

/** 播放指定曲目（视觉选中；真实播放需要后端服务） */
const playMusic = (music: string) => {
  alertDialog("背景音乐", `已选中「${music}」。真实播放需要后端服务，当前未接入。`);
  bgm.current = music;
  bgm.paused = false;
  bgm.stoped = false;
};

/** 前往设置 */
const openSettings = () => {
  alertDialog("设置 → 声音", "声音设置需要后端服务，当前未接入。");
  panelVisible.value = false;
};

// ===== 环境音相关 =====
/** 停止全部环境音 */
const stopAllAmbient = () => {
  settings.ambientTracks.splice(0, settings.ambientTracks.length);
};

/** 移除单条环境音轨道 */
const removeAmbientTrack = (id: string) => {
  const idx = settings.ambientTracks.findIndex((t) => t.id === id);
  if (idx >= 0) settings.ambientTracks.splice(idx, 1);
};

// ===== 环境音文件库（演示 6 项，与 BGM 6 项共 12 个音效格） =====
const ambientFileList = [
  "咖啡馆背景人声",
  "键盘敲击声",
  "窗外雨声",
  "林间鸟鸣",
  "翻页声",
  "时钟滴答",
];

/** 从文件库播放环境音（视觉加入轨道；真实播放需要后端服务） */
const playAmbientFromList = (ambient: string) => {
  settings.ambientTracks.push({
    id: `amb-${Date.now()}`,
    name: ambient,
    volume: 80,
    loop: "list",
    paused: false,
  });
};

const handleClickOutside = (e: MouseEvent) => {
  if (!panelVisible.value) return;
  const target = e.target as Node;
  // 点击在面板内部不关闭
  if (panelRef.value?.contains(target)) return;
  panelVisible.value = false;
};

// ===== 生命周期 =====
onMounted(() => {
  document.addEventListener("mousedown", handleClickOutside);
});

onUnmounted(() => {
  document.removeEventListener("mousedown", handleClickOutside);
});
</script>

<style scoped>
/* 自定义滚动条 */
.custom-scrollbar::-webkit-scrollbar {
  width: 4px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(255, 255, 255, 0.15);
  border-radius: 20px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background-color: rgba(255, 255, 255, 0.3);
}

/* 音量滑块样式 */
input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #79d9ff;
  cursor: pointer;
  border: 2px solid rgba(0, 0, 0, 0.3);
}

/* 频谱自定义取色器（去掉原生 swatch 的内边距，方形化） */
.spectrum-color-input {
  height: 20px;
  width: 30px;
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 5px;
}
.spectrum-color-input::-webkit-color-swatch-wrapper {
  padding: 0;
}
.spectrum-color-input::-webkit-color-swatch {
  border: none;
  border-radius: 4px;
}

/* 链接按钮样式 */
.text-link {
  background: none;
  border: none;
  padding: 0;
  color: #79d9ff;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.text-link:hover {
  color: #93c5fd;
}

/* 图标脉冲动画（.pulse-icon 仅让第一个子元素即 Icon 闪烁，文字不闪） */
@keyframes pulse-glow {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}
.pulse-icon > :deep(span:first-child) {
  animation: pulse-glow 2s ease-in-out infinite;
}
</style>
