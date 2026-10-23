<template>
  <div>
    <MenuItem size="large" title="面试场景" icon="🏞️">
      <div class="grid grid-cols-3 gap-3">
        <button
          v-for="s in scenes"
          :key="s.id"
          class="cursor-pointer rounded-xl border p-2 transition-all"
          :class="
            store.settings.sceneId === s.id
              ? 'border-[#79d9ff] bg-[#79d9ff]/10'
              : 'border-white/10 bg-white/5 hover:border-white/30'
          "
          @click="updateSettings({ sceneId: s.id })"
        >
          <div class="flex aspect-video items-center justify-center rounded-lg bg-white/10 text-2xl">{{ s.emoji }}</div>
          <div class="mt-2 text-xs font-medium text-white/80">{{ s.name }}</div>
          <span class="mt-1 block h-1 w-6 rounded-full" :class="store.settings.sceneId === s.id ? 'bg-[#79d9ff]' : 'bg-transparent'"></span>
        </button>
      </div>
    </MenuItem>

    <MenuItem size="large" title="粒子效果" icon="✨">
      <SettingRow label="星星">
        <LcToggle
          :model-value="store.settings.starOn"
          @update:model-value="(v: boolean) => updateSettings({ starOn: v })"
        />
      </SettingRow>
      <SettingRow label="流星">
        <LcToggle
          :model-value="store.settings.meteorOn"
          @update:model-value="(v: boolean) => updateSettings({ meteorOn: v })"
        />
      </SettingRow>
      <SettingRow label="粒子帧率">
        <LcSelect
          :model-value="store.settings.particleFps"
          :options="fpsOptions"
          @update:model-value="(v: string) => updateSettings({ particleFps: Number(v) })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="small" title="天气" icon="🌦️">
      <p class="mb-2 text-xs text-white/50">暂只保存设置</p>
      <LcSelect
        :model-value="store.settings.weather"
        :options="weatherOptions"
        @update:model-value="(v: string) => updateSettings({ weather: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="实时预览" icon="🖼️">
      <div class="flex aspect-video flex-col items-center justify-center rounded-lg border border-white/10 bg-slate-900/70">
        <span class="text-sm font-bold text-[#79d9ff]">{{ currentSceneName }}</span>
        <span class="mt-1 text-xs text-white/40">1920 × 1080</span>
      </div>
    </MenuItem>

    <MenuItem size="large" title="性能检测" icon="📈">
      <p class="mb-3 text-sm text-white/60">检测当前设备的渲染能力，据此给出粒子效果建议。</p>
      <button class="btn-big" @click="notify('检测完成，当前设备建议开启粒子效果')">开始检测</button>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import MenuItem from "../MenuItem.vue";
import SettingRow from "../../ui/SettingRow.vue";
import LcToggle from "../../ui/LcToggle.vue";
import LcSelect from "../../ui/LcSelect.vue";
import { notify, store, updateSettings } from "../../../store/appStore";

const scenes = [
  { id: "classroom", name: "天空教室", emoji: "🏫" },
  { id: "library", name: "图书馆", emoji: "📚" },
  { id: "office", name: "办公室", emoji: "🏢" },
];

const fpsOptions = [
  { value: 30, label: "30 FPS" },
  { value: 60, label: "60 FPS" },
  { value: 120, label: "120 FPS" },
];

const weatherOptions = [
  { value: "none", label: "无" },
  { value: "rain", label: "雨" },
  { value: "snow", label: "雪" },
];

const currentSceneName = computed(
  () => scenes.find((s) => s.id === store.settings.sceneId)?.name ?? "天空教室",
);
</script>
