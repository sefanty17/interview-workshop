<template>
  <div class="flex flex-col gap-3">
    <Button
      type="nav"
      :class="[
        'flex items-center gap-2 px-4 py-2 transition-colors',
        enabled ? 'text-[#ff8fc0]' : 'text-white',
      ]"
      @click="toggleEnabled"
    >
      <HeartLiquid
        :value="average"
        :negative="negativePeak"
        :size="18"
        :wave="waveEnabled"
        :class="{ 'affection-heartbeat': heartbeatEnabled }"
        :style="heartbeatStyle"
      />
      <!-- 顶栏按钮显示当前档位（初识/平淡/熟络…），无数据时回落标题 -->
      <h3 class="m-0 hidden text-lg font-bold xl:block">
        {{ tier ? TIER_LABELS[tier] : "面试印象" }}
      </h3>
    </Button>

    <!-- 日程式弹窗：全屏遮罩 + 居中窗口（双雷达，宽屏 880px / 窄屏近全宽） -->
    <Teleport to="body">
      <Transition
        enter-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
        leave-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
        enter-from-class="opacity-0"
        leave-to-class="opacity-0"
      >
        <div
          v-if="enabled"
          class="fixed inset-0 z-[1100] flex items-center justify-center bg-black/50 backdrop-blur-sm"
          @click.self="close"
        >
          <Transition
            enter-active-class="transition-all duration-300 cubic-bezier(0.2, 0.8, 0.2, 1)"
            leave-active-class="transition-all duration-200 cubic-bezier(0.6, -0.28, 0.74, 0.05)"
            enter-from-class="opacity-0 scale-95 translate-y-2"
            leave-to-class="opacity-0 scale-95 translate-y-2"
          >
            <div
              v-if="enabled"
              class="relative flex max-h-[85dvh] flex-col overflow-hidden rounded-2xl border border-white/10 shadow-[0_8px_32px_rgba(0,0,0,0.4)]"
              :class="narrow ? 'w-[95vw]' : 'w-168'"
            >
              <!-- Header bar -->
              <div
                class="flex shrink-0 items-center justify-between border-b border-white/10 bg-[#12121c]/90 px-4 py-2.5 backdrop-blur-xl"
              >
                <div class="flex items-center gap-2">
                  <!-- 面板内爱心常跳；心跳开关只控制顶栏按钮 -->
                  <HeartLiquid
                    :value="average"
                    :negative="negativePeak"
                    :size="17"
                    :wave="waveEnabled"
                    class="affection-heartbeat shrink-0"
                    :style="heartbeatStyle"
                  />
                  <h3 class="text-sm font-semibold tracking-wide text-white">面试印象</h3>
                </div>
                <button
                  class="rounded-full p-1.5 text-white/50 transition-colors hover:bg-white/10 hover:text-white"
                  @click="close"
                >
                  <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>

              <!-- Content area with glass styling -->
              <div
                class="min-h-0 flex-1 scrollbar-thin [scrollbar-color:var(--accent-color)_transparent] overflow-y-auto rounded-b-2xl bg-[#12121c]/75 p-4 text-white backdrop-blur-[20px]"
              >
                <!-- 只显示当前面试官；切换时旧内容淡出、新内容淡入（雷达子组件随 key 重挂载） -->
                <Transition name="affection-role" mode="out-in">
                  <div :key="persona?.id ?? 'none'">
                    <template v-if="affection">
                      <!-- 面试官头部：头像位 + 名字 + 平均值大数字 + 档位徽章 -->
                      <div class="flex items-center gap-2.5">
                        <div
                          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-white/15 bg-white/5 text-base font-bold"
                        >
                          {{ persona?.avatar ?? "" }}
                        </div>
                        <div class="min-w-0 flex-1">
                          <div class="truncate text-base font-semibold">
                            {{ persona?.name ?? "—" }}
                          </div>
                          <div class="text-[11px] text-white/50">六维平均</div>
                        </div>
                        <div class="flex shrink-0 flex-col items-end gap-1">
                          <span
                            class="text-2xl leading-none font-bold tabular-nums"
                            :style="{ color: tierColor }"
                          >
                            {{ average }}
                          </span>
                          <span
                            class="affection-tier-badge"
                            :class="{
                              'affection-tier-pulse': tier === 'overflow',
                              'affection-tier-alert': negativePeak > 60,
                            }"
                            :style="tierBadgeStyle"
                          >
                            {{ tier ? TIER_LABELS[tier] : "" }}
                          </span>
                        </div>
                      </div>

                      <!-- 当前负面情绪：由负面六维派生（强度 > 30 的维度），暗红系芯片 -->
                      <div
                        v-if="negativeChips.length > 0"
                        class="mt-2.5 flex flex-wrap items-center gap-1.5"
                      >
                        <span class="flex items-center gap-1 text-[11px] text-white/50">
                          <CloudRain :size="11" />
                          当前负面情绪
                        </span>
                        <span
                          v-for="chip in negativeChips"
                          :key="chip.key"
                          class="affection-neg-chip"
                        >
                          {{ NEG_LABELS[chip.key] }} {{ chip.value }}
                        </span>
                      </div>

                      <!-- 距下一档进度 -->
                      <div
                        v-if="nextTierInfo"
                        class="mt-1.5 flex items-center justify-end gap-2 text-[11px] text-white/50"
                      >
                        <div class="h-1 w-20 overflow-hidden rounded-full bg-white/10">
                          <div
                            class="h-full rounded-full transition-[width] duration-700"
                            :style="{ width: `${nextTierInfo.progress}%`, background: tierColor }"
                          ></div>
                        </div>
                        <span>
                          距「{{ TIER_LABELS[nextTierInfo.key] }}」还差 {{ nextTierInfo.points }} 点
                        </span>
                      </div>
                      <div
                        v-else-if="tier === 'overflow'"
                        class="mt-2 text-right text-xs"
                        :style="{ color: tierColor }"
                      >
                        已抵达满溢
                      </div>

                      <!-- 双雷达：左好感 / 右负面；窄屏上下堆叠（窗口内容区可滚动） -->
                      <div class="mt-1 flex flex-col gap-1 md:flex-row md:gap-3">
                        <AffectionRadar
                          title="好感维度"
                          :values="affectionValues"
                          :labels="affectionLabels"
                          :descs="affectionDescs"
                          :palette="affectionPalette"
                        />
                        <AffectionRadar
                          v-if="negative"
                          title="负面情绪"
                          :values="negativeValues"
                          :labels="negativeLabels"
                          :descs="negativeDescs"
                          :palette="negativePalette"
                        />
                      </div>

                      <!-- 最近一次评估变化（好感增量 + 负面增量并列；负面向增暗红、向减青绿=消解） -->
                      <div
                        v-if="recentChange"
                        class="mt-2.5 rounded-xl border border-white/10 bg-white/5 p-2.5"
                      >
                        <div class="mb-1 text-[11px] font-semibold text-white/60">最近变化</div>
                        <div class="flex flex-wrap gap-1.5">
                          <span
                            v-for="entry in recentChange.entries"
                            :key="`pos-${entry.key}`"
                            class="affection-delta-chip"
                            :class="entry.delta > 0 ? 'affection-delta-up' : 'affection-delta-down'"
                          >
                            {{ DIM_LABELS[entry.key] }}
                            {{ entry.delta > 0 ? "+" : "" }}{{ entry.delta }}
                          </span>
                          <span
                            v-for="entry in recentChange.negEntries"
                            :key="`neg-${entry.key}`"
                            class="affection-delta-chip"
                            :class="entry.delta > 0 ? 'affection-neg-up' : 'affection-neg-down'"
                          >
                            {{ NEG_LABELS[entry.key] }}
                            {{ entry.delta > 0 ? "+" : "" }}{{ entry.delta }}
                          </span>
                        </div>
                        <div
                          v-if="recentChange.reason"
                          class="mt-1 text-[11px] leading-relaxed text-white/50"
                        >
                          {{ recentChange.reason }}
                        </div>
                      </div>
                    </template>

                    <div v-else class="py-8 text-center text-sm text-white/40">暂无数据</div>

                    <!-- 面试印象介绍（可折叠） -->
                    <div class="mt-3 border-t border-white/10 pt-2.5">
                      <button
                        class="flex w-full cursor-pointer items-center justify-between border-none bg-transparent p-0 text-xs text-white/60 transition-colors hover:text-white"
                        @click="introOpen = !introOpen"
                      >
                        <span>关于面试印象</span>
                        <ChevronDown
                          :size="13"
                          class="transition-transform duration-200"
                          :class="{ 'rotate-180': introOpen }"
                        />
                      </button>
                      <Transition
                        enter-active-class="transition-all duration-200 ease-out"
                        leave-active-class="transition-all duration-150 ease-in"
                        enter-from-class="opacity-0 -translate-y-1"
                        leave-to-class="opacity-0 -translate-y-1"
                      >
                        <div
                          v-if="introOpen"
                          class="mt-2 flex flex-col gap-1 text-[11px] leading-relaxed text-white/50"
                        >
                          <div v-for="dim in dimensions" :key="`desc-${dim.key}`">
                            · <span class="text-white/70">{{ DIM_LABELS[dim.key] }}</span
                            >：{{ DIM_DESCS[dim.key] }}
                          </div>
                          <div v-for="dim in negDimensions" :key="`neg-desc-${dim.key}`">
                            ·
                            <span class="text-white/70">{{ NEG_LABELS[dim.key] }}</span
                            >：{{ NEG_DESCS[dim.key] }}
                          </div>
                          <div class="mt-1">
                            面试印象由 AI 定期自动评估对话后调整，无需手动操作。
                          </div>
                          <div>数值跟随面试官保存，跨存档共享，不随存档回滚。</div>
                          <div>
                            数值允许溢出：超过 100 为「满溢」，雷达图顶点发光；低于 0
                            为「疏离」，以冷色显示。
                          </div>
                          <div>
                            负面情绪分六个维度，被冒犯或伤害时累积、安抚后消解；任一维度过强都会影响面试官的态度与语气。
                          </div>
                        </div>
                      </Transition>
                    </div>
                  </div>
                </Transition>
              </div>
            </div>
          </Transition>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ChevronDown, CloudRain } from "@lucide/vue";
import Button from "../base/widget/Button.vue";
import AffectionRadar from "./AffectionRadar.vue";
import type { RadarPalette } from "./AffectionRadar.vue";
import HeartLiquid from "./HeartLiquid.vue";
import {
  affectionAverage,
  affectionStore,
  negativePeak,
  type AffectionVector,
  type NegativeVector,
} from "../../store/affectionStore";
import { personas } from "../../data/mock";
import { store } from "../../store/appStore";

/** 心跳/波浪动画（当前常开；接入高级设置后由设置项控制） */
const heartbeatEnabled = ref(true);
const waveEnabled = ref(true);

const enabled = ref(false);
const introOpen = ref(false);

function toggleEnabled() {
  enabled.value = !enabled.value;
}
function close() {
  enabled.value = false;
}

/** 窄屏判定（原 uiStore.isNarrowScreen）：窗口宽 < 768 时面板近全宽 */
const narrow = ref(typeof window !== "undefined" && window.innerWidth < 768);
const onResize = () => {
  narrow.value = window.innerWidth < 768;
};
onMounted(() => window.addEventListener("resize", onResize));
onUnmounted(() => window.removeEventListener("resize", onResize));

const persona = computed(() => personas.find((p) => p.id === store.session.personaId));
const affection = computed(() => affectionStore.dims);
const negative = computed(() => affectionStore.negDims);

type DimKey = keyof AffectionVector;
const dimensions: { key: DimKey }[] = [
  { key: "fondness" },
  { key: "trust" },
  { key: "intimacy" },
  { key: "rapport" },
  { key: "interest" },
  { key: "longing" },
];
type NegDimKey = keyof NegativeVector;
const negDimensions: { key: NegDimKey }[] = [
  { key: "anger" },
  { key: "hurt" },
  { key: "disappointment" },
  { key: "indifference" },
  { key: "jealousy" },
  { key: "estrangement" },
];

// ── 文案（取自原版 zh-CN 语言包 ui.affection，好感度→面试印象 / 角色→面试官） ──
const DIM_LABELS: Record<DimKey, string> = {
  fondness: "好感",
  trust: "信赖",
  intimacy: "亲密",
  rapport: "默契",
  interest: "兴趣",
  longing: "思念",
};
const DIM_DESCS: Record<DimKey, string> = {
  fondness: "整体喜欢程度，影响语气甜度",
  trust: "倾诉深度、说真心话的程度",
  intimacy: "肢体接触与近距离描写的接受度",
  rapport: "接梗、理解言外之意的程度",
  interest: "对求职者话题的好奇心、主动提问的倾向",
  longing: "久别重逢的反应强度",
};
const NEG_LABELS: Record<NegDimKey, string> = {
  anger: "愤怒",
  hurt: "受伤",
  disappointment: "失望",
  indifference: "冷漠",
  jealousy: "嫉妒",
  estrangement: "疏远",
};
const NEG_DESCS: Record<NegDimKey, string> = {
  anger: "被冒犯时的怒气强度",
  hurt: "被伤害后的在意程度",
  disappointment: "期待落空累积的失望",
  indifference: "心灰意冷时的冷淡程度",
  jealousy: "吃醋与占有欲被触发的程度",
  estrangement: "想要疏远求职者的倾向",
};
const TIER_LABELS = {
  estranged: "疏离",
  acquainted: "初识",
  plain: "平淡",
  familiar: "熟络",
  deep: "深厚",
  blazing: "炽烈",
  overflow: "满溢",
} as const;

// ── 雷达图输入（labels/descs 随界面语言重算；values 数组重建驱动子组件补间） ──
const affectionValues = computed<number[]>(() => {
  const a = affection.value;
  if (!a) return [0, 0, 0, 0, 0, 0];
  return [a.fondness, a.trust, a.intimacy, a.rapport, a.interest, a.longing];
});
const negativeValues = computed<number[]>(() => {
  const n = negative.value;
  if (!n) return [0, 0, 0, 0, 0, 0];
  return [n.anger, n.hurt, n.disappointment, n.indifference, n.jealousy, n.estrangement];
});
const affectionLabels = computed(() => dimensions.map((d) => DIM_LABELS[d.key]));
const affectionDescs = computed(() => dimensions.map((d) => DIM_DESCS[d.key]));
const negativeLabels = computed(() => negDimensions.map((d) => NEG_LABELS[d.key]));
const negativeDescs = computed(() => negDimensions.map((d) => NEG_DESCS[d.key]));

/** 好感雷达：粉色系 */
const affectionPalette: RadarPalette = {
  gradientFrom: "#ff5c8a",
  gradientTo: "#ff9ec7",
  stroke: "#ff5c8a",
  vertex: "#ff9ec7",
  vertexOverflow: "#ffd7e8",
  halo: "rgba(255, 92, 138, 0.35)",
  glow: "rgba(255, 92, 138, 0.9)",
  cold: "#7fc4ff",
};
/** 负面雷达：暗红/玫红/暗紫系 */
const negativePalette: RadarPalette = {
  gradientFrom: "#e0416e",
  gradientTo: "#7c5cbf",
  stroke: "#e0416e",
  vertex: "#e88aa8",
  vertexOverflow: "#ffc4d6",
  halo: "rgba(224, 65, 110, 0.4)",
  glow: "rgba(224, 65, 110, 0.9)",
  cold: "#7fc4ff",
};

// ── 平均值与档位 ─────────────────────────────────────
const average = computed(() => affectionAverage.value);

const TIER_ORDER = [
  "estranged",
  "acquainted",
  "plain",
  "familiar",
  "deep",
  "blazing",
  "overflow",
] as const;
type TierKey = (typeof TIER_ORDER)[number];
/** 各档位下限：疏离 <0 / 初识 0-20 / 平淡 21-40 / 熟络 41-60 / 深厚 61-80 / 炽烈 81-100 / 满溢 >100 */
const TIER_FLOOR: Record<TierKey, number> = {
  estranged: Number.NEGATIVE_INFINITY,
  acquainted: 0,
  plain: 21,
  familiar: 41,
  deep: 61,
  blazing: 81,
  overflow: 101,
};
const TIER_COLORS: Record<TierKey, string> = {
  estranged: "#7fc4ff",
  acquainted: "#8ec5ff",
  plain: "#b8c4d6",
  familiar: "#ffd479",
  deep: "#ff9ec7",
  blazing: "#ff5c8a",
  overflow: "#ff3d71",
};

const tier = computed<TierKey | null>(() => {
  const v = average.value;
  if (v === null) return null;
  if (v < 0) return "estranged";
  if (v <= 20) return "acquainted";
  if (v <= 40) return "plain";
  if (v <= 60) return "familiar";
  if (v <= 80) return "deep";
  if (v <= 100) return "blazing";
  return "overflow";
});
const tierColor = computed(() => (tier.value ? TIER_COLORS[tier.value] : "#ff9ec7"));
const tierBadgeStyle = computed(() => {
  const c = tierColor.value;
  return { color: c, borderColor: `${c}80`, background: `${c}1f` };
});

// ── 爱心心跳：心动周期随好感平均值加快（无数据 1.8s → 满溢 0.7s） ──
const heartbeatStyle = computed(() => {
  const v = average.value;
  const duration = v === null ? 1.8 : Math.min(2.2, Math.max(0.7, 1.9 - v / 125));
  return { "--heartbeat-duration": `${duration.toFixed(2)}s` };
});

/** 距下一档的点数与本档内进度（满溢/无数据时为 null） */
const nextTierInfo = computed((): { key: TierKey; points: number; progress: number } | null => {
  const v = average.value;
  const t = tier.value;
  if (v === null || t === null || t === "overflow") return null;
  const nextKey = TIER_ORDER[TIER_ORDER.indexOf(t) + 1];
  const need = TIER_FLOOR[nextKey];
  // 疏离档以当前负值为进度起点，其余档以本档下限为起点
  const start = t === "estranged" ? v : TIER_FLOOR[t];
  const progress = Math.min(100, Math.max(0, ((v - start) / (need - start)) * 100));
  return { key: nextKey, points: Math.max(0, need - v), progress };
});

// ── 当前负面情绪 chips：强度 > 30 的负面维度 ──
const negativeChips = computed(() => {
  const n = negative.value;
  if (!n) return [];
  return negDimensions
    .filter((d) => n[d.key] > 30)
    .map((d) => ({ key: d.key, value: Math.round(n[d.key]) }));
});

// ── 最近一次评估变化（好感增量 + 负面增量） ──
const recentChange = computed(() => {
  const c = affectionStore.lastChange;
  if (!c) return null;
  const entries = dimensions
    .filter((d) => typeof c.deltas[d.key] === "number" && c.deltas[d.key] !== 0)
    .map((d) => ({ key: d.key, delta: c.deltas[d.key] as number }));
  const negEntries = negDimensions
    .filter((d) => typeof c.negativeDeltas[d.key] === "number" && c.negativeDeltas[d.key] !== 0)
    .map((d) => ({ key: d.key, delta: c.negativeDeltas[d.key] as number }));
  if (entries.length === 0 && negEntries.length === 0 && !c.reason) return null;
  return { entries, negEntries, reason: c.reason };
});
</script>

<style scoped>
/* 爱心心跳：经典「怦-怦」双跳节奏，周期由 --heartbeat-duration 内联控制（随好感加快） */
.affection-heartbeat {
  transform-box: fill-box;
  transform-origin: center;
  animation: affection-heartbeat var(--heartbeat-duration, 1.8s) ease-in-out infinite;
}
@keyframes affection-heartbeat {
  0%,
  100% {
    transform: scale(1);
  }
  14% {
    transform: scale(1.25);
  }
  28% {
    transform: scale(1);
  }
  42% {
    transform: scale(1.16);
  }
  60% {
    transform: scale(1);
  }
}

/* 切换面试官：旧内容轻微上浮淡出，新内容自下淡入 */
.affection-role-enter-active,
.affection-role-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.affection-role-enter-from {
  opacity: 0;
  transform: translateY(4px);
}
.affection-role-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 档位徽章：pill，颜色由档位内联样式决定；满溢档粉色呼吸光晕，负面峰值高时红色警示环 */
.affection-tier-badge {
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 11px;
  line-height: 1.5;
  border: 1px solid;
  white-space: nowrap;
}
.affection-tier-pulse {
  animation: affection-tier-glow 1.6s ease-in-out infinite;
}
@keyframes affection-tier-glow {
  0%,
  100% {
    box-shadow: 0 0 2px rgba(255, 61, 113, 0.3);
  }
  50% {
    box-shadow: 0 0 10px rgba(255, 61, 113, 0.7);
  }
}
.affection-tier-alert {
  animation: affection-tier-alert 1.2s ease-in-out infinite;
}
@keyframes affection-tier-alert {
  0%,
  100% {
    box-shadow: 0 0 2px rgba(255, 77, 109, 0.35);
  }
  50% {
    box-shadow: 0 0 12px rgba(255, 77, 109, 0.85);
  }
}

/* 最近变化的增量小芯片：好感正粉负蓝；负面向增暗红、向减青绿（消解） */
.affection-delta-chip {
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 11px;
  line-height: 1.5;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  white-space: nowrap;
}
.affection-delta-up {
  color: #ff9ec7;
}
.affection-delta-down {
  color: #7fc4ff;
}
.affection-neg-up {
  color: #f06292;
}
.affection-neg-down {
  color: #8fd6a8;
}

/* 「当前负面情绪」芯片：暗红/玫红系，与好感正增量粉色芯片区分 */
.affection-neg-chip {
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 11px;
  line-height: 1.5;
  color: #f5a8c4;
  background: rgba(224, 80, 126, 0.14);
  border: 1px solid rgba(224, 80, 126, 0.35);
  white-space: nowrap;
}
</style>
