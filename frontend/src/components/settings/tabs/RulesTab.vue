<template>
  <div>
    <MenuItem size="large" title="岗位与知识点" icon="🎯">
      <SettingRow label="目标岗位">
        <LcSelect
          :model-value="store.settings.jobId"
          :options="jobOptions"
          @update:model-value="(v: string) => updateSettings({ jobId: v })"
        />
      </SettingRow>
      <div class="pt-3">
        <div class="mb-2 text-sm font-medium text-white">知识点范围</div>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="kp in currentJob.knowledgePoints"
            :key="kp.id"
            class="cursor-pointer rounded-full border px-3 py-1 text-xs transition-colors"
            :class="
              store.settings.kps.includes(kp.id)
                ? 'border-[#79d9ff] bg-[#79d9ff]/15 text-[#79d9ff]'
                : 'border-white/10 bg-white/[0.06] text-white/50 hover:text-white/80'
            "
            @click="toggleKp(kp.id)"
          >
            {{ kp.name }}
          </button>
        </div>
        <p class="mt-3 text-xs text-white/40">已选 {{ selectedCount }} 项</p>
      </div>
    </MenuItem>

    <MenuItem size="small" title="题量" icon="🔢">
      <LcSlider
        :model-value="store.settings.questionCount"
        :min="3"
        :max="10"
        unit="题"
        @update:model-value="(v: number) => updateSettings({ questionCount: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="时长" icon="⏳">
      <LcSlider
        :model-value="store.settings.timeLimit"
        :min="10"
        :max="30"
        unit="分"
        @update:model-value="(v: number) => updateSettings({ timeLimit: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="追问深度" icon="🪜">
      <LcSlider
        :model-value="store.settings.followUpDepth"
        :min="0"
        :max="4"
        unit="层"
        @update:model-value="(v: number) => updateSettings({ followUpDepth: v })"
      />
    </MenuItem>

    <MenuItem size="small" title="语音开关" icon="🔈">
      <div class="flex items-center justify-between gap-3">
        <span class="text-xs text-white/60">面试过程中启用语音播报</span>
        <LcToggle
          :model-value="store.settings.voiceOn"
          @update:model-value="(v: boolean) => updateSettings({ voiceOn: v })"
        />
      </div>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import MenuItem from "../MenuItem.vue";
import SettingRow from "../../ui/SettingRow.vue";
import LcSlider from "../../ui/LcSlider.vue";
import LcToggle from "../../ui/LcToggle.vue";
import LcSelect from "../../ui/LcSelect.vue";
import { jobs } from "../../../data/mock";
import { currentJob, store, toggleKp, updateSettings } from "../../../store/appStore";

const jobOptions = jobs.map((j) => ({ value: j.id, label: j.name }));

const selectedCount = computed(
  () => currentJob.value.knowledgePoints.filter((kp) => store.settings.kps.includes(kp.id)).length,
);
</script>
