<template>
  <!--
    顶部状态栏：时间 / 天气。与左上角 FreeModeTools、右上角菜单按钮同高同风格
    （icon + 文字），纯展示不接收点击。
  -->
  <div v-if="visible" class="pointer-events-none hidden items-center lg:flex">
    <!-- 两侧装饰：与文字同色的渐隐横线 -->
    <div class="h-px w-10 bg-linear-to-r from-transparent to-white/80 xl:w-16"></div>

    <div
      v-for="item in items"
      :key="item.key"
      class="mx-[5px] flex items-center gap-2 px-[15px] py-[10px] text-lg font-bold text-white [text-shadow:0_2px_4px_rgba(0,0,0,0.2)]"
    >
      <component :is="item.icon" :size="20" :stroke-width="2.5" class="shrink-0" />
      <span>{{ item.text }}</span>
    </div>

    <div class="h-px w-10 bg-linear-to-l from-transparent to-white/80 xl:w-16"></div>
  </div>
</template>

<script setup lang="ts">
import { store } from "../../store/appStore";
import {
  Ban,
  Clock,
  CloudDrizzle,
  CloudFog,
  CloudLightning,
  CloudRain,
  CloudSnow,
  Snowflake,
} from "@lucide/vue";
import { computed, onMounted, onUnmounted, ref, type Component } from "vue";

// 房间内常显（原版判据：设置面板打开或跑剧本时隐藏）
const visible = computed(() => true);

// ===== 时间：每秒走一次，显示到时:分:秒 =====
const now = ref(new Date());
let timer: number | undefined;

onMounted(() => {
  timer = window.setInterval(() => {
    now.value = new Date();
  }, 1000);
});

onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer);
});

const timeText = computed(() => {
  const date = now.value;
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  const seconds = String(date.getSeconds()).padStart(2, "0");
  return `${hours}:${minutes}:${seconds}`;
});

// ===== 天气：特效 key → 图标（与设置页背景的映射一致），文案取自设置页词条 =====
const WEATHER_ICONS: Record<string, Component> = {
  drizzle: CloudDrizzle,
  rain: CloudRain,
  thunderstorm: CloudLightning,
  snow: Snowflake,
  blizzard: CloudSnow,
  fog: CloudFog,
};
const WEATHER_TEXT: Record<string, string> = {
  drizzle: "小雨",
  rain: "雨水",
  thunderstorm: "雷阵雨",
  snow: "雪景",
  blizzard: "雪暴",
  fog: "雾",
};

const weatherKey = computed(() => store.settings.weather);

// 没开任何天气特效时落回「无」（设置页里「无」用的也是 Ban 图标）
const weatherIcon = computed(() =>
  weatherKey.value && WEATHER_ICONS[weatherKey.value] ? WEATHER_ICONS[weatherKey.value] : Ban,
);
const weatherText = computed(() =>
  weatherKey.value && WEATHER_TEXT[weatherKey.value] ? WEATHER_TEXT[weatherKey.value] : "无",
);

const items = computed(() => [
  { key: "time", icon: Clock, text: timeText.value },
  { key: "weather", icon: weatherIcon.value, text: weatherText.value },
]);
</script>
