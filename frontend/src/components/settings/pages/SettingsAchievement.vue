<template>
  <MenuPage>
    <MenuItem title="成就列表">
      <template #header>
        <Award :size="20" />
      </template>
      <div class="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
        <div
          v-for="achievement in sortedAchievements"
          :key="achievement.id"
          class="group relative flex items-center overflow-hidden rounded-xl border p-4 transition-all duration-300"
          :class="cardClass(achievement)"
          @click="onAchievementClick(achievement)"
        >
          <!-- 图标 -->
          <div class="relative z-10 mr-4 shrink-0">
            <div
              class="flex h-16 w-16 items-center justify-center rounded-full border-2 shadow-md transition-all duration-300"
              :class="iconClass(achievement)"
            >
              <Award :size="32" :class="iconSvgClass(achievement)" />
            </div>
          </div>

          <!-- 信息 -->
          <div class="z-10 min-w-0 flex-1">
            <div class="mb-1.5 flex items-center justify-between gap-2">
              <h3
                class="truncate text-base font-bold tracking-wide"
                :class="achievement.unlocked ? 'text-white text-shadow-sm' : 'text-white/90'"
              >
                {{ achievementTitle(achievement) }}
              </h3>
              <span
                v-if="achievement.unlocked"
                class="shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-medium shadow-sm"
                :class="badgeClass(achievement)"
              >
                {{ achievement.type === "rare" ? "稀有" : "普通" }}
              </span>
            </div>

            <p
              class="mb-2 text-xs leading-4 whitespace-pre-line transition-colors duration-300"
              :class="achievement.unlocked ? 'text-gray-200' : 'text-white/70'"
            >
              {{ achievementDescription(achievement) }}
            </p>

            <!-- 进度条 -->
            <div
              class="relative h-1.5 w-full overflow-hidden rounded-full border border-white/5 bg-white/30"
            >
              <div
                class="absolute top-0 left-0 h-full shadow-[0_0_8px_currentColor] transition-all duration-1000 ease-out"
                :class="progressClass(achievement)"
                :style="{ width: progressPercent(achievement) + '%' }"
              ></div>
            </div>

            <!-- 进度文字 -->
            <div v-if="!achievement.unlocked" class="mt-1 flex justify-end">
              <span
                class="font-mono text-[10px]"
                :class="achievement.unlocked ? 'text-gray-200' : 'text-white/60'"
              >
                {{
                  achievement.hidden
                    ? "???"
                    : `${achievement.current_progress} / ${achievement.target_progress}`
                }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { Award } from "@lucide/vue";
import { MenuPage, MenuItem } from "../../ui";
import { interviewAchievements, type InterviewAchievement } from "../../../data/mock";
import { notify } from "../../../store/appStore";

const sortedAchievements = computed<InterviewAchievement[]>(() =>
  [...interviewAchievements].sort((a, b) => {
    if (a.unlocked && !b.unlocked) return -1;
    if (!a.unlocked && b.unlocked) return 1;
    if (!a.unlocked && !b.unlocked) {
      if (a.hidden && !b.hidden) return 1;
      if (!a.hidden && b.hidden) return -1;
    }
    if (a.unlocked && b.unlocked) {
      if (a.type === "rare" && b.type !== "rare") return -1;
      if (a.type !== "rare" && b.type === "rare") return 1;
    }
    return 0;
  }),
);

// ---------- 文案 ----------
function achievementTitle(ach: InterviewAchievement): string {
  return ach.hidden && !ach.unlocked ? "???" : ach.title;
}
function achievementDescription(ach: InterviewAchievement): string {
  return ach.hidden && !ach.unlocked ? "隐藏成就，尚未解锁" : ach.description;
}

// ---------- 样式类（已解锁 / 未解锁 / 稀有 视觉区分） ----------
function cardClass(ach: InterviewAchievement): string {
  if (!ach.unlocked) {
    if (ach.hidden) {
      return "bg-purple-900/30 border-purple-400/40 opacity-80 hover:bg-white/5";
    }
    return "bg-white/[0.04] border-white/10 opacity-90 hover:bg-white/10 transition-all";
  }
  if (ach.type === "rare") {
    return "bg-white/[0.06] border-yellow-400/50 shadow-[0_0_24px_rgba(234,179,8,0.18)] hover:shadow-[0_0_32px_rgba(234,179,8,0.3)] hover:-translate-y-0.5";
  }
  return "bg-white/[0.06] border-emerald-400/40 shadow-[0_0_18px_rgba(16,185,129,0.12)] hover:-translate-y-0.5";
}

function iconClass(ach: InterviewAchievement): string {
  if (!ach.unlocked) {
    if (ach.hidden) return "border-purple-400/40 bg-purple-400/10 text-purple-300";
    return "border-white/20 bg-white/5 text-white/30";
  }
  if (ach.type === "rare") {
    return "border-yellow-400 bg-yellow-400/20 text-yellow-400 shadow-[0_0_15px_rgba(250,204,21,0.4)]";
  }
  return "border-emerald-400 bg-emerald-400/20 text-emerald-400 shadow-[0_0_10px_rgba(52,211,153,0.3)]";
}

function iconSvgClass(ach: InterviewAchievement): string {
  if (!ach.unlocked) {
    if (ach.hidden) return "text-purple-300/80";
    return "text-white/40";
  }
  if (ach.type === "rare") return "text-yellow-400 drop-shadow-[0_0_4px_rgba(250,204,21,0.8)]";
  return "text-emerald-400 drop-shadow-[0_0_4px_rgba(52,211,153,0.6)]";
}

function badgeClass(ach: InterviewAchievement): string {
  if (ach.type === "rare") return "bg-yellow-500/30 text-yellow-200 border-yellow-400/50";
  return "bg-emerald-500/30 text-emerald-200 border-emerald-400/50";
}

function progressClass(ach: InterviewAchievement): string {
  if (ach.unlocked) {
    if (ach.type === "rare") return "bg-linear-to-r from-yellow-600 to-yellow-300";
    return "bg-linear-to-r from-emerald-600 to-emerald-300";
  }
  if (ach.hidden) return "bg-linear-to-r from-purple-600 to-purple-300";
  return "bg-white/40";
}

function progressPercent(ach: InterviewAchievement): number {
  if (ach.unlocked) return 100;
  const current = ach.current_progress || 0;
  const target = ach.target_progress || 1;
  return Math.min(100, Math.max(0, (current / target) * 100));
}

// ---------- 点击反馈（真实交互） ----------
function onAchievementClick(ach: InterviewAchievement): void {
  if (ach.hidden && !ach.unlocked) {
    notify("隐藏成就：尚未解锁，继续完成面试挑战即可揭晓");
    return;
  }
  notify(ach.unlocked ? `已解锁成就：${ach.title}` : `未解锁成就：${ach.title}（${ach.current_progress}/${ach.target_progress}）`);
}
</script>

<style scoped>
.text-shadow-sm {
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
}
</style>
