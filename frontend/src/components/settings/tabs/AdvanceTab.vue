<template>
  <div>
    <MenuItem size="large" title="LLM 接口" icon="🔌">
      <SettingRow label="Base URL">
        <input
          class="glass-input w-64 text-sm"
          :value="store.settings.llmBaseUrl"
          placeholder="https://api.deepseek.com"
          @change="(e) => updateSettings({ llmBaseUrl: (e.target as HTMLInputElement).value })"
        />
      </SettingRow>
      <SettingRow label="API Key">
        <input
          class="glass-input w-64 text-sm"
          type="password"
          :value="store.settings.llmKey"
          placeholder="sk-..."
          @change="(e) => updateSettings({ llmKey: (e.target as HTMLInputElement).value })"
        />
      </SettingRow>
      <SettingRow label="模型">
        <LcSelect
          :model-value="store.settings.llmModel"
          :options="modelOptions"
          @update:model-value="(v: string) => updateSettings({ llmModel: v })"
        />
      </SettingRow>
      <SettingRow label="提示词预设">
        <LcSelect
          :model-value="store.settings.promptPreset"
          :options="presetOptions"
          @update:model-value="(v: string) => updateSettings({ promptPreset: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="large" title="生成参数" icon="🎛️">
      <SettingRow label="温度" hint="越高回答越发散">
        <div class="flex items-center gap-3">
          <LcSlider
            :model-value="store.settings.temperature"
            :min="0"
            :max="100"
            @update:model-value="(v: number) => updateSettings({ temperature: v })"
          />
          <span class="w-12 text-right text-sm tabular-nums text-[#79d9ff]">
            {{ (store.settings.temperature / 100).toFixed(2) }}
          </span>
        </div>
      </SettingRow>
      <SettingRow label="最大 token">
        <LcSlider
          :model-value="store.settings.maxTokens"
          :min="256"
          :max="8192"
          :step="256"
          @update:model-value="(v: number) => updateSettings({ maxTokens: v })"
        />
      </SettingRow>
    </MenuItem>

    <MenuItem size="small" title="导出配置" icon="📤">
      <button class="btn-big" @click="exportConfig">导出为 JSON</button>
    </MenuItem>

    <MenuItem size="small" title="导入配置" icon="📥">
      <button class="btn-big" @click="fileRef?.click()">选择配置文件…</button>
      <input ref="fileRef" type="file" accept="application/json,.json" class="hidden" @change="onFile" />
    </MenuItem>

    <MenuItem size="large" title="危险操作" icon="⚠️">
      <div class="rounded-xl border border-red-500/30 bg-red-500/5 p-4">
        <h5 class="text-sm font-bold text-red-400">重置全部设置</h5>
        <p class="mt-1 mb-3 text-xs text-white/50">会把所有偏好恢复为默认值，该操作不可撤销。</p>
        <button
          class="w-full cursor-pointer rounded-lg border-none bg-red-500 px-4 py-3 text-base font-bold text-white transition-colors hover:bg-red-400"
          @click="reset"
        >
          重置全部设置
        </button>
      </div>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import MenuItem from "../MenuItem.vue";
import SettingRow from "../../ui/SettingRow.vue";
import LcSlider from "../../ui/LcSlider.vue";
import LcSelect from "../../ui/LcSelect.vue";
import { confirmDialog } from "../../../store/dialogStore";
import { notify, resetSettings, store, updateSettings } from "../../../store/appStore";
import type { SiteSettings } from "../../../data/mock";

const fileRef = ref<HTMLInputElement | null>(null);

const modelOptions = [
  { value: "deepseek-chat", label: "deepseek-chat" },
  { value: "deepseek-reasoner", label: "deepseek-reasoner" },
  { value: "gpt-4o-mini", label: "gpt-4o-mini" },
];

const presetOptions = [
  { value: "default", label: "默认" },
  { value: "strict", label: "严格技术面" },
  { value: "friendly", label: "温和 HR 面" },
];

function exportConfig() {
  const blob = new Blob([JSON.stringify(store.settings, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "interview-settings.json";
  a.click();
  URL.revokeObjectURL(url);
  notify("配置已导出");
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  try {
    const data = JSON.parse(await file.text()) as Record<string, unknown>;
    const patch: Record<string, unknown> = {};
    for (const key of Object.keys(store.settings)) {
      if (key in data) patch[key] = data[key];
    }
    updateSettings(patch as unknown as Partial<SiteSettings>);
    notify("配置已导入");
  } catch {
    notify("导入失败：文件格式不正确");
  }
  input.value = "";
}

async function reset() {
  const ok = await confirmDialog("重置全部设置", "所有偏好都会恢复默认值，确定继续吗？");
  if (!ok) return;
  resetSettings();
  notify("已重置全部设置");
}
</script>
