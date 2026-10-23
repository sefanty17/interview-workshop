<template>
  <!-- 背景图（当前场景图；script:background 下发后换图并做转场） -->
  <div class="absolute inset-0 overflow-hidden">
    <Transition name="bg-cross-fade">
      <img
        :key="backgroundSrc"
        :src="backgroundSrc"
        class="game-background"
        :style="{ transitionDuration: `${backgroundTransitionMs}ms` }"
        alt=""
        draggable="false"
      />
    </Transition>
  </div>

  <!-- 星空 / 流星背景动画（原版主界面 MainMenu 同款，开关来自设置） -->
  <StarAnimation
    v-if="store.settings.starOn"
    :stars-enabled="true"
    :stars-layer-ref="null"
    :stars-fps="store.settings.particleFps"
  />
  <MeteorAnimation
    v-if="store.settings.meteorOn"
    :meteors-enabled="true"
    :meteor-fps="store.settings.particleFps"
  />

  <!-- 粒子层：按设置页「粒子选择」（settings.particleType）渲染对应特效组件。
       isolation: isolate 让粒子 canvas 的 z-index:-1 只在本层内部生效，不会压到背景图之下。 -->
  <div class="pointer-events-none absolute inset-0" style="isolation: isolate">
    <BAParticles v-if="particleType === 'baparticles'" />
    <Blizzard v-else-if="particleType === 'blizzard'" />
    <Drizzle v-else-if="particleType === 'drizzle'" />
    <Fireflies v-else-if="particleType === 'fireflies'" />
    <Fireworks v-else-if="particleType === 'fireworks'" />
    <Fog v-else-if="particleType === 'fog'" />
    <MeteorShower v-else-if="particleType === 'meteorshower'" />
    <Rain v-else-if="particleType === 'rain'" />
    <Sakura v-else-if="particleType === 'sakura'" />
    <Snow v-else-if="particleType === 'snow'" />
    <StarField v-else-if="particleType === 'starfield'" />
    <Thunderstorm v-else-if="particleType === 'thunderstorm'" />
  </div>

  <!-- 剧本特效层（script:background-effect）：与上面设置页粒子层独立，可叠加。
       有对应粒子组件就渲染，没有对应物只记日志、留空层（原版 BackgroundEffectProcessor
       走 same 注册表 canonicalEffectKey 纠大小写后落到氛围层/天气层）。 -->
  <div
    class="pointer-events-none absolute inset-0"
    style="isolation: isolate"
    data-script-effect-layer
  >
    <component :is="scriptEffectComponent" v-if="scriptEffectComponent" />
  </div>

  <!-- 压暗层（背景上方、立绘下方） -->
  <div class="pointer-events-none absolute inset-0 bg-[rgba(6,14,28,0.35)]"></div>
</template>

<script setup lang="ts">
import { computed, watch, type Component } from "vue";
import { store } from "../../store/appStore";
import { roomStore } from "../../store/roomStore";
import { resolveAssetUrl } from "../../store/roomAudio";

import BAParticles from "./particles/BAParticles.vue";
import Blizzard from "./particles/Blizzard.vue";
import Drizzle from "./particles/Drizzle.vue";
import Fireflies from "./particles/Fireflies.vue";
import Fireworks from "./particles/Fireworks.vue";
import Fog from "./particles/Fog.vue";
import MeteorShower from "./particles/MeteorShower.vue";
import Rain from "./particles/Rain.vue";
import Sakura from "./particles/Sakura.vue";
import Snow from "./particles/Snow.vue";
import StarField from "./particles/StarField.vue";
import Thunderstorm from "./particles/Thunderstorm.vue";

import StarAnimation from "./animations/StarAnimation.vue";
import MeteorAnimation from "./animations/MeteorAnimation.vue";

/** 与原版粒子注册表对应的全部取值（小写），未知取值一律视为 none */
const PARTICLE_KEYS: readonly string[] = [
  "baparticles",
  "blizzard",
  "drizzle",
  "fireflies",
  "fireworks",
  "fog",
  "meteorshower",
  "rain",
  "sakura",
  "snow",
  "starfield",
  "thunderstorm",
];

/** 当前选中的粒子类型；"none" 或未知值都不渲染 */
const particleType = computed(() =>
  PARTICLE_KEYS.includes(store.settings.particleType) ? store.settings.particleType : "none",
);

/** 没有场景时回落的背景图（原版 白天.webp -> public/backgrounds/day.webp） */
const FALLBACK_BACKGROUND = "/backgrounds/day.webp";

/** 当前场景的背景图（设置 → 背景 选中的场景，存在 store.settings.sceneId 里） */
const sceneBackground = computed(() => {
  const scene = store.scenes.find((s) => s.id === store.settings.sceneId);
  return scene?.background || "";
});

/** 剧本背景：script:background 下发过就用它，否则用当前场景图，再没有才回落 day.webp */
const backgroundSrc = computed(() =>
  roomStore.backgroundUrl
    ? resolveAssetUrl(roomStore.backgroundUrl)
    : sceneBackground.value || FALLBACK_BACKGROUND,
);

/** 转场时长：后端 transition 单位是秒（BackgroundPayload），这里折成毫秒 */
const backgroundTransitionMs = computed(() => {
  const seconds = roomStore.backgroundTransition;
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : 0;
});

/**
 * 剧本特效（script:background-effect）：key 大小写不敏感地对到注册表，
 * 与原版 canonicalEffectKey 一致；"none"/空 → 无特效，未知值 → 记日志后留空层。
 */
const SCRIPT_EFFECTS: Record<string, Component> = {
  starfield: StarField,
  sakura: Sakura,
  fireworks: Fireworks,
  ba: BAParticles,
  fireflies: Fireflies,
  meteorshower: MeteorShower,
  drizzle: Drizzle,
  rain: Rain,
  thunderstorm: Thunderstorm,
  snow: Snow,
  blizzard: Blizzard,
  fog: Fog,
};

const scriptEffectComponent = computed<Component | null>(() => {
  const key = roomStore.backgroundEffect.trim().toLowerCase();
  if (!key || key === "none") return null;
  return SCRIPT_EFFECTS[key] ?? null;
});

watch(
  () => roomStore.backgroundEffect,
  (effect) => {
    const key = effect.trim().toLowerCase();
    if (!key || key === "none" || SCRIPT_EFFECTS[key]) return;
    // 没有对应物：留空层，只记一条日志（不打断剧本）
    console.warn("[game] 未知背景特效，留空层", effect);
  },
);
</script>

<style scoped>
.game-background {
  position: absolute;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center center;
  user-select: none;
}

/* 背景切换：新旧两张图叠着交叉淡入淡出（时长取 script:background 的 transition） */
.bg-cross-fade-enter-active,
.bg-cross-fade-leave-active {
  transition-property: opacity;
  transition-timing-function: ease-in-out;
}
.bg-cross-fade-enter-from,
.bg-cross-fade-leave-to {
  opacity: 0;
}
</style>
