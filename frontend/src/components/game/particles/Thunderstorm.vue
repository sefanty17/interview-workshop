<template>
  <canvas ref="canvasRef" class="thunderstorm-layer"></canvas>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRain } from "./hooks/useRain";
import type { RainLayerOption, RainProfile } from "./config/rain";

/**
 * 雷阵雨。跑的是和雨同一台引擎，换一套更密的参数并打开闪电。
 *
 * 它属于「天气」层，与设置页的「氛围」特效相互独立，可以同时开着。
 * 全部绘制细节见 hooks/useRain.ts，这里只有参数表。
 *
 * 原版参数表在 config/thunderstorm.ts；本项目该目录按效果平铺一份配置
 * （rain.ts / drizzle.ts ……），雷暴是唯一没拆出去的一份。数值与原版逐字一致，
 * 先内联在这里，不为一次移植新增配置文件。
 */
const props = withDefaults(
  defineProps<{
    enabled?: boolean;
    /** 密度倍率，0 到 2 */
    intensity?: number;
  }>(),
  {
    enabled: true,
    intensity: 1,
  },
);

/**
 * 由远及近三层。比小雨的每一层都更宽更亮，近景占比也更大 ——
 * 暴雨的体感来自「近处的雨丝糊住视野」，而不是全场均匀加密。
 * 落速提到 1250，配合更大的风速，雨丝的倾角从约 7 度张开到 10 度以上。
 */
const STORM_LAYERS: RainLayerOption[] = [
  {
    share: 0.42,
    zMin: 0.3,
    zMax: 0.48,
    speedAtZ1: 1250,
    width: 1,
    alpha: 0.16,
    headRatio: 0.3,
    headBoost: 1.7,
  },
  {
    share: 0.34,
    zMin: 0.48,
    zMax: 0.74,
    speedAtZ1: 1250,
    width: 1.5,
    alpha: 0.26,
    headRatio: 0.34,
    headBoost: 1.75,
  },
  {
    share: 0.24,
    zMin: 0.74,
    zMax: 1.05,
    speedAtZ1: 1250,
    width: 2.2,
    alpha: 0.4,
    headRatio: 0.38,
    headBoost: 1.85,
  },
];

const THUNDERSTORM_PROFILE: RainProfile = {
  layers: STORM_LAYERS,

  /** 密度约为小雨的 1.85 倍。再密下去就会糊住背景画，galgame 里背景也是要看的 */
  dropsPerMegapixel: 240,
  maxDrops: 2600,

  /** 风比小雨大一倍，阵风幅度也更大，雨幕整体被吹得歪向一侧 */
  wind: {
    base: 235,
    gust: [
      { amplitude: 95, period: 13.7 },
      { amplitude: 44, period: 5.3 },
    ],
  },

  splash: {
    groundRatio: 1,
    chance: 0.3,
    maxRadius: 15,
    life: 0.45,
    flatten: 0.34,
    alpha: [0.38, 0.22, 0.11],
  },

  bokeh: {
    perMegapixel: 6,
    maxCount: 26,
    minRadius: 18,
    maxRadius: 60,
    minSpeed: 34,
    maxSpeed: 88,
    minAlpha: 0.08,
    maxAlpha: 0.18,
    color: "198, 224, 255",
  },

  /**
   * 闪电。真实闪电不是「亮一下」，而是一串间隔几十毫秒、逐次变暗的
   * 脉冲，之后拖一条缓慢衰减的尾巴 —— 引擎按这个形状拟合包络，
   * 所以这里给的是间隔与时长，不是某条曲线。
   *
   * 5 到 16 秒的间隔是刻意拉开的：太密会从「天气」变成「闪烁」。
   */
  lightning: {
    intervalMin: 5,
    intervalMax: 16,
    duration: 0.95,
    maxAlpha: 0.42,
    color: "214, 232, 255",
    boltChance: 0.45,
    boltMinSegments: 9,
    boltMaxSegments: 15,
    boltMinReach: 0.5,
    boltMaxReach: 0.95,
    boltJitter: 0.75,
    boltDrift: 1.1,
  },
};

const canvasRef = ref<HTMLCanvasElement | null>(null);

useRain(canvasRef, props, THUNDERSTORM_PROFILE);
</script>

<style scoped>
.thunderstorm-layer {
  position: absolute;
  top: 0;
  left: 0;
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: -1;
}
</style>
