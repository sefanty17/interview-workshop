<template>
  <!-- 迷你频谱（与左下角"声效"按钮对称的右下角），展开后是同源的详情面板 -->
  <div
    v-if="visible"
    ref="rootRef"
    class="pointer-events-auto fixed right-6 bottom-[calc(24px+var(--safe-area-inset-bottom))] z-1000 flex flex-col items-end gap-2"
  >
    <Transition
      enter-active-class="transition-all duration-300 ease-out"
      leave-active-class="transition-all duration-200 ease-in"
      enter-from-class="opacity-0 translate-y-3 scale-95"
      leave-to-class="opacity-0 translate-y-3 scale-95"
    >
      <div
        v-if="expanded"
        class="w-59 rounded-2xl border border-white/10 bg-[#12121c]/45 p-3 text-white shadow-[0_10px_30px_rgba(0,0,0,0.35)] backdrop-blur-[18px]"
      >
        <!-- 当前音源 + 采集状态点（未接入音频时为暗灰） -->
        <div class="mb-2 flex items-center gap-2">
          <AudioLines :size="12" class="shrink-0 text-(--accent-color)" />
          <span class="min-w-0 flex-1 truncate text-[11px] font-semibold text-gray-200">
            {{ nowPlayingText }}
          </span>
          <span
            class="h-1.5 w-1.5 shrink-0 rounded-full transition-colors duration-300"
            :style="{ background: spectrumActive ? colors.from : 'rgba(255, 255, 255, 0.22)' }"
          ></span>
        </div>

        <canvas ref="fullCanvasRef" class="mx-auto block"></canvas>

        <!-- 配色快捷切换（完整配置在声效面板） -->
        <div class="mt-2.5" @click.stop>
          <SpectrumPaletteChips
            :model-value="activePalette"
            :custom-from="settings.spectrumColor1"
            :custom-to="settings.spectrumColor2"
            @update:model-value="selectPalette"
          />
        </div>
      </div>
    </Transition>

    <!-- 迷你频谱：没有外壳，只有浮在画面上的柱子 -->
    <button
      class="cursor-pointer border-none bg-transparent p-1 opacity-90 transition-all duration-200 hover:scale-105 hover:opacity-100 active:scale-95"
      :title="expanded ? '收起' : '展开频谱面板'"
      @click.stop="expanded = !expanded"
    >
      <canvas ref="miniCanvasRef" class="block"></canvas>
    </button>
  </div>
</template>

<script setup lang="ts">
import {
  DEFAULT_SPECTRUM_PALETTE,
  resolveSpectrumColors,
  resolveSpectrumStyle,
  type SpectrumStyle,
} from "../../../constants/spectrum";
import SpectrumPaletteChips from "./SpectrumPaletteChips.vue";
import { store, updateSettings } from "../../../store/appStore";
import { extraDemo } from "../../../store/extraDemo";
import { AudioLines } from "@lucide/vue";
import { computed, onMounted, onUnmounted, ref, watch } from "vue";

const settings = store.settings;

/**
 * 每个形态的尺寸。
 * 画布要留出辉光的余量（glow），否则柱子外发光会被画布边缘裁成硬边。
 * - 镜像/柱状是宽扁的，迷你态 88×32（加按钮 4px 内边距 = 40，与左侧"声效"按钮等高）
 * - 圆环要方正，迷你态 48×48，展开态 132×132（居中）
 */
interface SpectrumSpec {
  kind: SpectrumStyle;
  w: number;
  h: number;
  bars: number;
  gap: number;
  glow: number;
  r0: number;
  len: number;
}

function specFor(style: SpectrumStyle, big: boolean): SpectrumSpec {
  if (style === "ring") {
    return big
      ? { kind: style, w: 132, h: 132, bars: 48, gap: 0, glow: 6, r0: 32, len: 26 }
      : { kind: style, w: 48, h: 48, bars: 26, gap: 0, glow: 4, r0: 11, len: 9 };
  }
  return big
    ? { kind: style, w: 212, h: 60, bars: 40, gap: 2, glow: 6, r0: 0, len: 0 }
    : { kind: style, w: 88, h: 32, bars: 20, gap: 1.6, glow: 4, r0: 0, len: 0 };
}

/** 帧率上限：40 帧肉眼已足够顺滑 */
const FRAME_MS = 1000 / 40;

const rootRef = ref<HTMLElement | null>(null);
const miniCanvasRef = ref<HTMLCanvasElement | null>(null);
const fullCanvasRef = ref<HTMLCanvasElement | null>(null);
const expanded = ref(false);

// ===== 设置 =====
const spectrumOn = computed(() => !!settings.spectrumEnabled);
const spectrumStyle = computed(() => resolveSpectrumStyle(settings.spectrumStyle));
const activePalette = computed(() => settings.spectrumPalette || DEFAULT_SPECTRUM_PALETTE);
const colors = computed(() =>
  resolveSpectrumColors(activePalette.value, settings.spectrumColor1, settings.spectrumColor2),
);

const visible = computed(() => spectrumOn.value);

const selectPalette = (id: string) => {
  updateSettings({ spectrumPalette: id });
};

/** 演示态：无真实音频采集，正弦波合成动画（接入后端后换 WebAudio Analyser） */
const spectrumActive = computed(() => true);

// ===== 当前音源文案 =====
const nowPlayingText = computed(() => {
  const bgm = extraDemo.bgm;
  if (bgm.current && !bgm.stoped) {
    return bgm.paused ? "已暂停" : bgm.current;
  }
  const ambient = store.settings.ambientTracks.filter((tr) => !tr.paused).length;
  if (ambient > 0) return `环境音 ×${ambient}`;
  return "静默";
});

// ===== 绘制（演示正弦波） =====
const miniSpec = computed(() => specFor(spectrumStyle.value, false));
const fullSpec = computed(() => specFor(spectrumStyle.value, true));

/** 演示波形：多个正弦叠加出有机起伏，0..1 */
function demoLevel(i: number, bars: number, t: number): number {
  const x = i / bars;
  const a = Math.sin(t * 0.0021 + x * 9.4) * 0.5 + 0.5;
  const b = Math.sin(t * 0.0013 + x * 17.2 + 1.7) * 0.5 + 0.5;
  const c = Math.sin(t * 0.0037 + i * 0.9) * 0.5 + 0.5;
  return Math.min(1, Math.max(0.06, Math.pow(a * 0.55 + b * 0.3 + c * 0.15, 1.4)));
}

function draw(canvas: HTMLCanvasElement | null, spec: SpectrumSpec, t: number) {
  if (!canvas) return;
  if (canvas.width !== spec.w) canvas.width = spec.w;
  if (canvas.height !== spec.h) canvas.height = spec.h;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.clearRect(0, 0, spec.w, spec.h);

  const { from, to } = colors.value;
  const gradient =
    spec.kind === "ring"
      ? ctx.createLinearGradient(0, 0, spec.w, spec.h)
      : ctx.createLinearGradient(0, 0, 0, spec.h);
  gradient.addColorStop(0, from);
  gradient.addColorStop(1, to);
  ctx.fillStyle = gradient;
  ctx.shadowColor = from;
  ctx.shadowBlur = spec.glow * 2;

  const barW = (spec.w - spec.bars * spec.gap) / spec.bars;

  if (spec.kind === "ring") {
    const cx = spec.w / 2;
    const cy = spec.h / 2;
    for (let i = 0; i < spec.bars; i++) {
      const v = demoLevel(i, spec.bars, t);
      const ang = (i / spec.bars) * Math.PI * 2 - Math.PI / 2;
      const r1 = spec.r0;
      const r2 = spec.r0 + v * spec.len;
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(ang) * r1, cy + Math.sin(ang) * r1);
      ctx.lineTo(cx + Math.cos(ang) * r2, cy + Math.sin(ang) * r2);
      ctx.lineWidth = 2;
      ctx.strokeStyle = gradient;
      ctx.stroke();
    }
    return;
  }

  for (let i = 0; i < spec.bars; i++) {
    const v = demoLevel(i, spec.bars, t);
    const x = i * (barW + spec.gap);
    if (spec.kind === "mirror") {
      const h = v * (spec.h / 2 - spec.glow);
      const y = spec.h / 2 - h;
      ctx.beginPath();
      ctx.roundRect(x, y, barW, h * 2, barW / 2);
      ctx.fill();
    } else {
      const h = v * (spec.h - spec.glow * 2);
      const y = spec.h - spec.glow - h;
      ctx.beginPath();
      ctx.roundRect(x, y, barW, h, barW / 2);
      ctx.fill();
    }
  }
}

let rafId = 0;
let lastFrame = 0;

function frame(now: number) {
  rafId = requestAnimationFrame(frame);
  if (!visible.value || document.hidden || now - lastFrame < FRAME_MS) return;
  lastFrame = now;
  if (expanded.value) draw(fullCanvasRef.value, fullSpec.value, now);
  draw(miniCanvasRef.value, miniSpec.value, now);
}

// 设置面板打开时隐藏，与左侧声效按钮一致；顺手收起展开面板
watch(visible, (v) => {
  if (!v) expanded.value = false;
});

// 点击外部收起
const onDocMouseDown = (e: MouseEvent) => {
  if (!expanded.value) return;
  if (rootRef.value?.contains(e.target as Node)) return;
  expanded.value = false;
};

onMounted(() => {
  document.addEventListener("mousedown", onDocMouseDown);
  rafId = requestAnimationFrame(frame);
});

onUnmounted(() => {
  document.removeEventListener("mousedown", onDocMouseDown);
  cancelAnimationFrame(rafId);
});
</script>
