<template>
  <div>
    <MenuItem size="large" title="界面字体" icon="🔤">
      <LcSelect
        :model-value="store.settings.fontFamily"
        :options="fontOptions"
        @update:model-value="(v: string) => updateSettings({ fontFamily: v })"
      />
    </MenuItem>

    <MenuItem size="large" title="文字显示速度" icon="⏩">
      <LcSlider
        :model-value="store.settings.textSpeed"
        :min="0"
        :max="100"
        @update:model-value="(v: number) => updateSettings({ textSpeed: v })"
      />
    </MenuItem>

    <MenuItem size="large" title="自动推进延迟" icon="⏱️">
      <LcSlider
        :model-value="store.settings.autoAdvanceDelay"
        :min="200"
        :max="3000"
        :step="100"
        unit="ms"
        @update:model-value="(v: number) => updateSettings({ autoAdvanceDelay: v })"
      />
    </MenuItem>

    <MenuItem size="large" title="显示文字样本" icon="💬">
      <div class="rounded-lg border border-white/10 bg-white/10 p-3 text-sm leading-relaxed text-white/80">
        面试官：请简单介绍一下你自己。
      </div>
    </MenuItem>

    <MenuItem size="small" title="台词合并阈值" icon="🧩">
      <LcSlider
        :model-value="store.settings.mergeThreshold"
        :min="0"
        :max="100"
        unit="字"
        @update:model-value="(v: number) => updateSettings({ mergeThreshold: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="台词合并续打延迟" icon="⌛">
      <LcSlider
        :model-value="store.settings.mergeDelay"
        :min="100"
        :max="1000"
        :step="50"
        unit="ms"
        @update:model-value="(v: number) => updateSettings({ mergeDelay: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="追问提示合并方式" icon="🔀">
      <LcSelect
        :model-value="store.settings.mergeMode"
        :options="mergeModeOptions"
        @update:model-value="(v: string) => updateSettings({ mergeMode: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="面试官单次发言条数" icon="🔢">
      <LcSlider
        :model-value="store.settings.replyCount"
        :min="1"
        :max="3"
        @update:model-value="(v: number) => updateSettings({ replyCount: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="内联动作文本" icon="🎬">
      <LcToggle
        :model-value="store.settings.inlineAction"
        @update:model-value="(v: boolean) => updateSettings({ inlineAction: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="久坐提醒" icon="🪑">
      <LcToggle
        :model-value="store.settings.idleRemind"
        @update:model-value="(v: boolean) => updateSettings({ idleRemind: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="记住我的面试偏好" icon="📌">
      <LcToggle
        :model-value="store.settings.rememberPreference"
        @update:model-value="(v: boolean) => updateSettings({ rememberPreference: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="语音音效" icon="🔊">
      <LcToggle
        :model-value="store.settings.soundEffect"
        @update:model-value="(v: boolean) => updateSettings({ soundEffect: v })"
      />
    </MenuItem>

    <MenuItem size="large" title="关于" icon="ℹ️">
      <div class="space-y-1 text-sm text-white/50">
        <p>面试工坊 v1.0</p>
        <p>基于 LingChat 前端复刻</p>
        <p>浙江工业大学 · 计算机学院 · JavaEE 课设</p>
      </div>
    </MenuItem>

    <MenuItem size="large" title="返回首页" icon="🏠">
      <button class="btn-big" @click="router.push('/')">返回首页</button>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from "vue-router";
import MenuItem from "../MenuItem.vue";
import LcSelect from "../../ui/LcSelect.vue";
import LcSlider from "../../ui/LcSlider.vue";
import LcToggle from "../../ui/LcToggle.vue";
import { store, updateSettings } from "../../../store/appStore";

const router = useRouter();

const fontOptions = [
  { value: "system", label: "系统默认" },
  { value: "source-han", label: "思源黑体" },
  { value: "sarasa", label: "更纱黑体" },
];

const mergeModeOptions = [
  { value: "inline", label: "内联" },
  { value: "wrap", label: "换行" },
  { value: "hide", label: "隐藏" },
];
</script>
