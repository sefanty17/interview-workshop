<template>
  <PageShell title="面试报告">
    <template v-if="record">
      <MenuItem size="large" title="总分" icon="◎">
        <div class="mb-4 text-xs text-white/50">
          {{ record.jobName }} · {{ record.personaName }} · {{ record.date }} · {{ record.duration
          }}{{ evaluation ? " · AI 评估" : "" }}
        </div>

        <div v-if="evaluation?.summary" class="mb-4 text-xs text-white/50">{{ evaluation.summary }}</div>

        <div class="flex flex-col items-center gap-8 md:flex-row">
          <div class="flex shrink-0 flex-col items-center">
            <div
              class="relative h-[140px] w-[140px] rounded-full"
              :style="{
                background: `conic-gradient(#79d9ff ${score * 3.6}deg, rgba(255,255,255,0.08) 0deg)`,
              }"
            >
              <div class="absolute inset-[10px] rounded-full bg-[#0b1522]/85 backdrop-blur-sm" />
              <div class="absolute inset-0 flex flex-col items-center justify-center">
                <span class="text-4xl font-black leading-none text-[#79d9ff]">{{ score }}</span>
                <span class="mt-1 pl-[0.25em] text-[10px] leading-none tracking-[0.25em] text-white/40">总分</span>
              </div>
            </div>
            <div class="mt-3 text-center text-xs text-white/40">
              {{ questionCount }} 题 · {{ record.duration }}
            </div>
            <div v-if="evalLoading" class="mt-2 text-center text-xs text-white/50">AI 评估生成中…</div>
            <div v-else-if="evalError" class="mt-2 text-center text-xs text-red-400">
              <span>{{ evalError }}</span>
              <button class="ml-2 underline" @click="loadEvaluation()">重试</button>
            </div>
          </div>

          <div class="min-w-0 flex-1">
            <RadarChart v-if="radarLabels.length" :labels="radarLabels" :values="radarValues" :height="260" />
            <p v-else class="py-16 text-center text-sm text-white/40">本场暂无维度评分</p>
          </div>
        </div>
      </MenuItem>

      <MenuItem size="large" title="逐题复盘" icon="≡">
        <div
          v-for="(q, i) in record.questions"
          :key="q.id"
          class="mb-3 rounded-lg border border-white/10 bg-white/[0.04] p-3 last:mb-0"
        >
          <div class="flex items-start gap-2">
            <span class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#79d9ff]/15 text-[11px] font-bold text-[#79d9ff]">
              {{ i + 1 }}
            </span>
            <div class="min-w-0 flex-1 text-sm leading-relaxed text-white">{{ q.question }}</div>
            <span class="shrink-0 text-sm font-bold text-[#79d9ff]">{{ q.score }}</span>
          </div>

          <div class="mt-2 pl-8">
            <p class="line-clamp-2 text-xs text-white/60">{{ q.comment }}</p>
            <p class="mt-1.5 border-l-2 border-[#79d9ff] pl-2 text-xs leading-relaxed text-white/80">
              建议：{{ q.suggest }}
            </p>
          </div>
        </div>

        <p v-if="!record.questions.length" class="py-10 text-center text-sm text-white/40">本场没有逐题记录</p>
      </MenuItem>

      <MenuItem v-if="highlights.length" size="small" title="亮点" icon="✓">
        <ul class="space-y-2">
          <li v-for="(h, i) in highlights" :key="'h' + i" class="flex gap-2 text-xs leading-relaxed text-white/70">
            <span class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-400"></span>
            <span>{{ h }}</span>
          </li>
        </ul>
      </MenuItem>

      <MenuItem v-if="weaknesses.length" size="small" title="不足" icon="!">
        <ul class="space-y-2">
          <li v-for="(w, i) in weaknesses" :key="'w' + i" class="flex gap-2 text-xs leading-relaxed text-white/70">
            <span class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-red-400"></span>
            <span>{{ w }}</span>
          </li>
        </ul>
      </MenuItem>

      <MenuItem v-if="plans.length" size="large" title="改进建议" icon="✎">
        <ol class="space-y-3">
          <li v-for="(p, i) in plans" :key="'p' + i" class="rounded-lg border border-white/10 bg-white/[0.04] p-3">
            <div class="flex items-start gap-2">
              <span class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#79d9ff]/15 text-[11px] font-bold text-[#79d9ff]">
                {{ i + 1 }}
              </span>
              <div class="min-w-0">
                <p class="text-sm leading-relaxed text-white">{{ p.advice }}</p>
                <p class="mt-1 text-xs leading-relaxed text-white/50">练习方向：{{ p.practice }}</p>
              </div>
            </div>
          </li>
        </ol>
      </MenuItem>

      <MenuItem size="large" title="导出报告" icon="⎙">
        <button class="btn-big" @click="printPage">打印 / 导出 PDF</button>
      </MenuItem>
    </template>

    <MenuItem v-else size="large" title="报告不存在" icon="!">
      <div class="flex flex-col items-center py-16">
        <p class="text-sm text-white/40">没有找到这份报告，可能已被清理。</p>
        <button class="btn-big mt-4 max-w-[240px]" @click="router.push('/records')">返回面试记录</button>
      </div>
    </MenuItem>
  </PageShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageShell from "../components/ui/PageShell.vue";
import MenuItem from "../components/settings/MenuItem.vue";
import RadarChart from "../components/ui/RadarChart.vue";
import { jobs } from "../data/mock";
import { findInterviewer } from "../data/interviewers";
import { store } from "../store/appStore";
import { fetchEvaluation, runEvaluation, type EvaluationResult } from "../api/evaluation";
import { fetchSessionDetail } from "../api/chat";
import type { InterviewRecord } from "../data/mock";

const route = useRoute();
const router = useRouter();

/** 后端场次（记录页 id 形如 s-12）在本地 store.records 里没有条目，按 ?sid= 拉详情兜底 */
const remoteRecord = ref<InterviewRecord | null>(null);
/** 后端场次没有逐题明细，题数用详情里的玩家发言条数还原 */
const remoteQuestionCount = ref(0);

const record = computed(
  () => store.records.find((r) => String(r.id) === String(route.params.id)) ?? remoteRecord.value,
);

/** ?noeval=1：作答不足 3 轮的场次，报告页不跑 AI 评估，分数保持 0 */
const skipEval = computed(() => route.query.noeval === "1");

/** ?sid= 是后端场次 id（本地 record.id 与它是两套号，评估按 sid 查） */
const sid = computed(() => {
  const raw = route.query.sid;
  const n = typeof raw === "string" ? Number(raw) : NaN;
  return Number.isInteger(n) && n > 0 ? n : null;
});

/**
 * 本地没有这条记录（历史后端场次）时，用后端详情拼一份只读记录：
 * 逐题明细没有，模板会显示「本场没有逐题记录」，分数/维度仍走评估。
 */
async function loadRemoteRecord() {
  const id = sid.value;
  if (id === null || record.value) return;
  try {
    const detail = await fetchSessionDetail(id);
    // 玩家发言条数 = 实际作答轮次（剧本/自由都按 role=user 记账）
    remoteQuestionCount.value = detail.messages.filter((m) => m.role === "user").length;
    remoteRecord.value = {
      id: `s-${id}`,
      // 报告头部按场次真实字段还原：岗位显示中文名、面试官显示姓名，而不是库里的 code
      jobName: jobs.find((j) => j.id === detail.jobCode)?.name ?? detail.title,
      personaName: findInterviewer(detail.interviewerCode)?.name ?? detail.interviewerCode ?? "-",
      date: "-",
      duration: "-",
      score: detail.score ?? 0,
      dimensions: [],
      questions: [],
      advice: [],
    };
  } catch (e) {
    console.warn("[report] 拉取场次详情失败", e);
  }
}

const evaluation = ref<EvaluationResult | null>(null);
const evalLoading = ref(false);
const evalError = ref("");

/**
 * 拉评估：有就直接显示，404（还没评估过）就自动跑一次。
 *
 * 失败一律落到 evalError 给重试按钮，绝不抛到渲染层——没有评估也必须能看到本地存档那份报告。
 */
async function loadEvaluation() {
  const id = sid.value;
  if (id === null || skipEval.value) return;
  evalLoading.value = true;
  evalError.value = "";
  try {
    const existing = await fetchEvaluation(id);
    evaluation.value = existing ?? (await runEvaluation(id));
  } catch (e) {
    console.warn("[report] 拉取 AI 评估失败，退回本地数据", e);
    evalError.value = e instanceof Error ? e.message : "评估失败";
  } finally {
    evalLoading.value = false;
  }
}

onMounted(async () => {
  await loadRemoteRecord();
  await loadEvaluation();
});

// ── 以下是「评估优先、本地兜底」：有评估用评估，没有完全退回读 store.records 的旧行为 ──

const score = computed(() => evaluation.value?.overallScore ?? record.value?.score ?? 0);
/** 本地存档有逐题明细；后端场次按详情里的玩家发言数还原题数 */
const questionCount = computed(
  () => (record.value?.questions.length ?? 0) || remoteQuestionCount.value,
);

const radarLabels = computed(() => {
  const ev = evaluation.value;
  if (ev && ev.dimensions.length) return ev.dimensions.map((d) => d.label);
  return record.value?.dimensions.map((d) => d.name) ?? [];
});
const radarValues = computed(() => {
  const ev = evaluation.value;
  if (ev && ev.dimensions.length) return ev.dimensions.map((d) => d.score);
  return record.value?.dimensions.map((d) => d.score) ?? [];
});

/** 统一成 {name, score} 再排序，本地维度与评估维度的字段名差异在这里抹平 */
const dimsAsc = computed(() => {
  const ev = evaluation.value;
  const list =
    ev && ev.dimensions.length
      ? ev.dimensions.map((d) => ({ name: d.label, score: d.score }))
      : (record.value?.dimensions ?? []);
  return [...list].sort((a, b) => a.score - b.score);
});
const dimsDesc = computed(() => [...dimsAsc.value].reverse());

const highlights = computed(() => {
  const ev = evaluation.value;
  if (ev && ev.strengths.length) return ev.strengths;
  const r = record.value;
  if (!r) return [];
  const list: string[] = [];
  for (const q of [...r.questions].sort((a, b) => b.score - a.score)) {
    list.push(`「${q.kp}」回答到位（${q.score} 分）：${q.comment}`);
  }
  for (const d of dimsDesc.value.slice(0, 2)) {
    list.push(`${d.name} ${d.score} 分，是本场相对最强的维度。`);
  }
  return list.slice(0, 3);
});

const weaknesses = computed(() => {
  const ev = evaluation.value;
  if (ev && ev.weaknesses.length) return ev.weaknesses;
  const r = record.value;
  if (!r) return [];
  const list: string[] = [];
  for (const q of [...r.questions].sort((a, b) => a.score - b.score)) {
    list.push(`「${q.kp}」还需要补：${q.suggest}`);
  }
  for (const d of dimsAsc.value.slice(0, 2)) {
    list.push(`${d.name} ${d.score} 分，低于本场平均，建议优先补。`);
  }
  return list.slice(0, 3);
});

/** 练习方向没有真数据可依托时的兜底文案 */
const FALLBACK_PRACTICE = "按「结论 → 方案 → 取舍 → 验证」复述一遍并录音。";

const plans = computed(() => {
  const ev = evaluation.value;
  if (ev && ev.suggestions.length) {
    return ev.suggestions.map((advice) => ({ advice, practice: FALLBACK_PRACTICE }));
  }
  const r = record.value;
  if (!r) return [];
  const weak = [...r.questions].sort((a, b) => a.score - b.score);
  return r.advice.map((advice, i) => ({
    advice,
    practice: (weak.length ? weak[i % weak.length]?.suggest : "") || FALLBACK_PRACTICE,
  }));
});

function printPage() {
  window.print();
}
</script>

<style>
@media print {
  html,
  body {
    background: #fff !important;
  }
  .fixed.inset-0 {
    position: static !important;
    overflow: visible !important;
    background: #fff !important;
    color: #111 !important;
  }
  /* 打印时隐藏天空背景层与压暗层 */
  .fixed.inset-0 > .absolute {
    display: none !important;
  }
  .fixed.inset-0 .relative.flex.h-full {
    height: auto !important;
    padding: 0 !important;
  }
  .fixed.inset-0 .overflow-y-auto {
    overflow: visible !important;
    height: auto !important;
  }
  .fixed.inset-0 .backdrop-blur-2xl,
  .fixed.inset-0 section {
    background: #fff !important;
    border-color: #d8d8d8 !important;
    backdrop-filter: none !important;
    box-shadow: none !important;
  }
  .fixed.inset-0 * {
    color: #1a1a1a !important;
    text-shadow: none !important;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }
}
</style>
