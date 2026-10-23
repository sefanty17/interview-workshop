<template>
  <MenuPage>
    <!-- 场景管理（含背景分类、卡片网格、排序、编辑、右键菜单） -->
    <SceneManageSection />

    <!-- 剧本自带背景（导入时复制到 public/backgrounds/scripts/<code>/，只读展示） -->
    <MenuItem title="剧本背景" size="large">
      <template #header>
        <Images :size="20" />
      </template>
      <p v-if="scriptAssets.length === 0" class="text-sm text-white/50">
        暂无：导入自带 Assets/Backgrounds 的剧本后，这里会出现对应的背景图。
      </p>
      <div v-else class="grid grid-cols-2 gap-3 md:grid-cols-4">
        <figure v-for="asset in scriptAssets" :key="asset.url">
          <img
            :src="asset.url"
            :alt="asset.file"
            class="aspect-video w-full rounded-lg border border-white/10 object-cover"
          />
          <figcaption class="mt-1 truncate text-xs text-white/60">
            {{ asset.scriptName }} · {{ asset.file }}
          </figcaption>
        </figure>
      </div>
    </MenuItem>

    <!-- 粒子选择（原版 settings.background.particle 卡，改动立即生效：InterviewRoomView 的
         GameBackground 读 store.settings.particleType 渲染对应粒子组件） -->
    <MenuItem title="粒子选择" size="large">
      <template #header>
        <Sparkles :size="20" />
      </template>
      <div class="flex gap-4 overflow-x-auto pb-2">
        <Button
          v-for="opt in particleOptions"
          :key="opt.value"
          type="transparent"
          :active="currentParticle === opt.value"
          @click="updateParticle(opt.value)"
        >
          <component :is="opt.icon" :size="16" style="margin-right: 6px" />
          {{ opt.label }}
        </Button>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, type Component } from "vue";
import {
  Ban,
  CloudDrizzle,
  CloudFog,
  CloudRain,
  CloudSnow,
  Flower2,
  Images,
  PartyPopper,
  Snowflake,
  Sparkles,
  Star,
  Sun,
} from "@lucide/vue";
import { MenuPage, MenuItem } from "../../ui";
import { Button } from "../../base";
import { store, updateSettings } from "../../../store/appStore";
import SceneManageSection from "../background/SceneManageSection.vue";

/** 粒子选项：词条取自原版 zh-CN/settings.ts 的 settings.background.particle / weather */
const particleOptions: { value: string; label: string; icon: Component }[] = [
  { value: "none", label: "无", icon: Ban },
  { value: "starfield", label: "星空", icon: Star },
  { value: "sakura", label: "樱花", icon: Flower2 },
  { value: "fireworks", label: "烟花", icon: PartyPopper },
  { value: "baparticles", label: "星辉", icon: Sun },
  { value: "fireflies", label: "萤火虫", icon: Sparkles },
  { value: "meteorshower", label: "流星雨", icon: Sparkles },
  { value: "drizzle", label: "小雨", icon: CloudDrizzle },
  { value: "rain", label: "雨水", icon: CloudRain },
  { value: "snow", label: "雪景", icon: Snowflake },
  { value: "blizzard", label: "雪暴", icon: CloudSnow },
  { value: "fog", label: "雾", icon: CloudFog },
];

const currentParticle = computed(() => store.settings.particleType);

function updateParticle(value: string): void {
  updateSettings({ particleType: value });
}

/** 剧本自带背景清单：后端扫 public/backgrounds/scripts/<code>/，失败就当没有（不编数据）。 */
interface ScriptAsset {
  scriptCode: string;
  scriptName: string;
  file: string;
  url: string;
}

const scriptAssets = ref<ScriptAsset[]>([]);

onMounted(async () => {
  try {
    const res = await fetch("/api/scripts/background-assets");
    if (!res.ok) return;
    scriptAssets.value = (await res.json()) as ScriptAsset[];
  } catch {
    // 后端未启动时保持空列表
  }
});
</script>
