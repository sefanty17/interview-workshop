<template>
  <div>
    <MenuItem size="large" title="音频设备" icon="🎧">
      <SettingRow label="输出设备">
        <LcSelect
          :model-value="store.settings.outputDevice"
          :options="deviceOptions"
          @update:model-value="(v: string) => updateSettings({ outputDevice: v })"
        />
      </SettingRow>
      <SettingRow label="输入设备">
        <LcSelect
          :model-value="store.settings.inputDevice"
          :options="deviceOptions"
          @update:model-value="(v: string) => updateSettings({ inputDevice: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="large" title="音量" icon="🔊">
      <SettingRow v-for="row in volumeRows" :key="row.key" :label="row.label">
        <div class="flex items-center gap-3">
          <LcSlider
            :model-value="store.settings[row.key]"
            :min="0"
            :max="100"
            @update:model-value="(v: number) => setVolume(row.key, v)"
          />
          <button
            class="flex h-[34px] w-[34px] cursor-pointer items-center justify-center rounded-full border border-white/15 bg-white/10 text-sm text-[#79d9ff] transition-colors hover:border-[#79d9ff] hover:bg-[#79d9ff]/20"
            @click="notify(`试听：${row.label} ${store.settings[row.key]}%`)"
          >
            ♪
          </button>
        </div>
      </SettingRow>
    </MenuItem>

    <MenuItem size="large" title="背景音乐" icon="🎵">
      <SettingRow label="启用背景音乐">
        <LcToggle
          :model-value="store.settings.bgmOn"
          @update:model-value="(v: boolean) => updateSettings({ bgmOn: v })"
        />
      </SettingRow>
      <SettingRow label="曲目">
        <LcSelect
          :model-value="store.settings.bgmTrack"
          :options="trackOptions"
          @update:model-value="(v: string) => updateSettings({ bgmTrack: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="small" title="环境音" icon="🌧️">
      <SettingRow label="启用环境音">
        <LcToggle
          :model-value="store.settings.ambientOn"
          @update:model-value="(v: boolean) => updateSettings({ ambientOn: v })"
        />
      </SettingRow>
      <SettingRow label="音源">
        <LcSelect
          :model-value="store.settings.ambientTrack"
          :options="ambientOptions"
          @update:model-value="(v: string) => updateSettings({ ambientTrack: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="small" title="语音合成" icon="🗣️">
      <SettingRow label="启用语音合成">
        <LcToggle
          :model-value="store.settings.ttsOn"
          @update:model-value="(v: boolean) => updateSettings({ ttsOn: v })"
        />
      </SettingRow>
      <SettingRow label="语速">
        <LcSlider
          :model-value="store.settings.ttsSpeed"
          :min="0"
          :max="100"
          @update:model-value="(v: number) => updateSettings({ ttsSpeed: v })"
        />
      </SettingRow>
      <SettingRow label="音色">
        <LcSelect
          :model-value="store.settings.ttsVoice"
          :options="voiceOptions"
          @update:model-value="(v: string) => updateSettings({ ttsVoice: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="large" title="语音识别" icon="🎙️">
      <SettingRow label="启用语音识别" hint="使用浏览器语音识别，需麦克风权限">
        <LcToggle
          :model-value="store.settings.asrOn"
          @update:model-value="(v: boolean) => updateSettings({ asrOn: v })"
        />
      </SettingRow>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import MenuItem from "../MenuItem.vue";
import SettingRow from "../../ui/SettingRow.vue";
import LcSlider from "../../ui/LcSlider.vue";
import LcToggle from "../../ui/LcToggle.vue";
import LcSelect from "../../ui/LcSelect.vue";
import { notify, store, updateSettings } from "../../../store/appStore";
import type { SiteSettings } from "../../../data/mock";

type VolumeKey = "volumeSpeaker" | "volumeSfx" | "volumeBgm" | "volumeAchievement" | "volumeAmbient";

const volumeRows: { key: VolumeKey; label: string }[] = [
  { key: "volumeSpeaker", label: "面试官语音" },
  { key: "volumeSfx", label: "操作音效" },
  { key: "volumeBgm", label: "背景乐" },
  { key: "volumeAchievement", label: "成就音效" },
  { key: "volumeAmbient", label: "环境音" },
];

function setVolume(key: VolumeKey, value: number) {
  const patch: Partial<SiteSettings> = {};
  patch[key] = value;
  updateSettings(patch);
}

const deviceOptions = [{ value: "default", label: "默认设备" }];
const trackOptions = [
  { value: "calm", label: "静谧" },
  { value: "bright", label: "轻快" },
  { value: "focus", label: "专注" },
];

const ambientOptions = [
  { value: "none", label: "无" },
  { value: "keyboard", label: "键盘声" },
  { value: "rain", label: "雨声" },
];

const voiceOptions = [
  { value: "default", label: "默认音色" },
  { value: "male", label: "沉稳男声" },
  { value: "female", label: "温和女声" },
];
</script>
