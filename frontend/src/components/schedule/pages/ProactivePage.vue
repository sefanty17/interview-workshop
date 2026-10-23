<template>
  <!-- 视图：主动对话规则 -->
  <div
    v-if="scheduleStore.scheduleView === 'proactive_settings'"
    class="grid grid-cols-1 p-1 sm:grid-cols-1 lg:grid-cols-1"
  >
    <!-- 三条主动消息规则：开关 / 间隔 / 触发条件 -->
    <div
      v-for="(rule, idx) in draft.rules"
      :key="rule.key"
      class="glass-effect mb-4 rounded-2xl border border-white/10 p-5"
    >
      <div class="mb-3 flex items-start justify-between gap-4">
        <div class="min-w-0">
          <label class="text-brand inline-flex items-center font-medium">{{ rule.title }}</label>
          <p class="mt-1 text-sm text-gray-300">{{ rule.key }}</p>
          <p class="mt-1 text-xs leading-relaxed text-white/50">{{ rule.desc }}</p>
        </div>
        <Toggle :checked="rule.enabled" @change="(v: boolean) => (draft.rules[idx].enabled = v)">
          <span class="text-xs font-bold text-white/70">{{ rule.enabled ? "已开启" : "已关闭" }}</span>
        </Toggle>
      </div>

      <div class="grid gap-4 sm:grid-cols-2">
        <div>
          <label class="mb-1 block text-xs font-bold tracking-wider text-white/50 uppercase"
            >间隔 (分钟)</label
          >
          <div class="flex items-center gap-2 rounded-2xl border border-white/10 bg-white/10 p-2">
            <Clock :size="15" class="ml-2 shrink-0 text-white/50" />
            <input
              v-model.number="draft.rules[idx].intervalMinutes"
              type="number"
              min="5"
              step="5"
              class="min-w-0 flex-1 rounded-xl border-none bg-transparent px-2 py-1.5 text-sm text-white outline-none"
            />
          </div>
          <p class="mt-1 text-xs text-white/40">{{ intervalHint(rule.intervalMinutes) }}</p>
        </div>

        <div>
          <label class="mb-1 block text-xs font-bold tracking-wider text-white/50 uppercase"
            >触发条件</label
          >
          <select
            v-model="draft.rules[idx].condition"
            class="select-dark w-full rounded-2xl border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white outline-none"
          >
            <option v-for="opt in conditionOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
          <p class="mt-1 text-xs text-white/40">权重 {{ rule.weight.toFixed(1) }}</p>
        </div>
      </div>
    </div>

    <!-- 全局项：今日上限 / 待办提醒方式（原版 MAX_PROACTIVE_TIMES、TODO_REMINDER_STYLE） -->
    <div class="glass-effect mb-6 rounded-2xl border border-white/10 p-5">
      <label class="text-brand inline-flex items-center font-medium">一天最多主动几次</label>
      <p class="mt-1 mb-2 text-sm text-gray-300">MAX_PROACTIVE_TIMES</p>
      <div class="flex items-center gap-3">
        <input
          v-model.number="draft.maxProactiveTimes"
          type="number"
          min="1"
          max="50"
          class="w-32 rounded-xl border border-white/10 bg-white/10 px-4 py-2 text-sm text-white outline-none"
        />
        <span class="text-xs text-white/50">次 / 天</span>
      </div>
    </div>

    <div class="mb-6">
      <!-- 提醒方式是二选一，原版只支持 bool/text/number，所以单独渲染 -->
      <label class="text-brand inline-flex items-center font-medium">待办提醒方式</label>
      <p class="mt-1 mb-2 text-sm text-gray-300">TODO_REMINDER_STYLE</p>
      <div class="flex gap-2">
        <div
          v-for="option in reminderStyleOptions"
          :key="option.value"
          class="cursor-pointer rounded-lg px-4 py-2.5 text-sm transition-colors duration-200"
          :class="
            draft.todoReminderStyle === option.value
              ? 'bg-brand text-white'
              : 'border border-white/10 bg-white/10 text-white hover:bg-white/20'
          "
          @click="draft.todoReminderStyle = option.value"
        >
          {{ option.label }}
        </div>
      </div>
    </div>

    <div class="flex h-auto w-auto items-center gap-2 align-text-bottom">
      <div
        class="bg-brand w-18 cursor-pointer rounded-lg border-none px-5 py-2.5 text-center text-sm font-medium text-white transition-colors duration-200 hover:bg-[#0056b3]"
        @click="saveSettings"
      >
        保存
      </div>
      <p :style="{ color: saveStatus.color }">
        {{ saveStatus.message }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 主动对话页（原版 components/schedule/pages/ProactivePage.vue）
 *
 * 原版列的是 14 个 ENV 配置键（ENABLE_PROACTIVE_SYSTEM / SCREEN_WEIGHT /
 * PROACTIVE_EXPECTED_INTERVAL_MINUTES …），保存后还要 reloadProactiveSystem
 * 让后端重新载入。本项目没有后端，把它收成 3 条「主动消息规则」：
 * 每条一个开关 + 一个间隔 + 一个触发条件，另加今日上限与待办提醒方式。
 * 保存写进 scheduleStore（localStorage），刷新后仍在。
 */
import { computed, reactive } from "vue";
import { Clock } from "@lucide/vue";
import Toggle from "../../base/widget/Toggle.vue";
import {
  scheduleStore,
  saveProactiveSettings,
  type ProactiveCondition,
  type ProactiveSettings,
} from "../../../store/scheduleStore";

/** 编辑副本：与原版一致，只有点「保存」才写回 */
const draft = reactive<ProactiveSettings>({
  rules: scheduleStore.proactive.rules.map((r) => ({ ...r })),
  maxProactiveTimes: scheduleStore.proactive.maxProactiveTimes,
  todoReminderStyle: scheduleStore.proactive.todoReminderStyle,
});

const saveStatus = reactive({
  message: "",
  color: "#4ade80",
});

const conditionOptions: { value: ProactiveCondition; label: string }[] = [
  { value: "before_schedule", label: "日程开始前" },
  { value: "todo_deadline", label: "待办临近截止" },
  { value: "long_silence", label: "长时间无对话" },
  { value: "after_interview", label: "面试结束后" },
];

const reminderStyleOptions = computed(() => [
  { value: "role" as const, label: "角色提醒" },
  { value: "toast" as const, label: "弹窗消息提醒" },
]);

/** 原版由后端把间隔反推成兴趣增长区间，这里只做本地换算当提示 */
const intervalHint = (minutes: number) => {
  const m = Number(minutes);
  if (!Number.isFinite(m) || m <= 0) return "间隔需大于 0 分钟";
  if (m < 60) return `≈ 每 ${Math.round(m)} 分钟最多主动一次`;
  const hours = (m / 60).toFixed(m % 60 === 0 ? 0 : 1);
  return `≈ 每 ${hours} 小时最多主动一次`;
};

let clearTimer: number | null = null;

const saveSettings = () => {
  saveProactiveSettings({
    rules: draft.rules.map((r) => ({
      ...r,
      intervalMinutes: Math.max(5, Number(r.intervalMinutes) || 5),
    })),
    maxProactiveTimes: Math.max(1, Number(draft.maxProactiveTimes) || 1),
    todoReminderStyle: draft.todoReminderStyle,
  });
  saveStatus.message = "已保存成功";
  saveStatus.color = "#4ade80";

  if (clearTimer !== null) clearTimeout(clearTimer);
  clearTimer = window.setTimeout(() => {
    saveStatus.message = "";
    clearTimer = null;
  }, 5000);
};
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供） */
.text-brand {
  color: var(--accent-color);
}
.bg-brand {
  background-color: var(--accent-color);
}
.select-dark option {
  background: #16202c;
  color: #fff;
}
</style>
