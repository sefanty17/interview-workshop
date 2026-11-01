<template>
  <PageShell title="面试报告">
    <template v-if="record">
      <MenuItem v-if="freeDialogue" size="large" title="自由对话场次" icon="◎">
        <p class="text-sm leading-relaxed text-white/70">
          本场是自由对话：没有固定题单，所以不产生评分、逐题复盘与对话总结，也不计入成长曲线。
        </p>
        <p class="mt-2 text-xs text-white/45">本场共 {{ questionCount }} 轮作答。需要评分请从剧本面试入口开始。</p>
      </MenuItem>

      <MenuItem v-if="!freeDialogue" size="large" title="总分" icon="◎">
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

      <MenuItem v-if="!freeDialogue" size="large" title="逐题复盘" icon="≡">
        <div
          v-for="(q, i) in questionReviews"
          :key="q.key"
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
            <p
              v-if="q.quote"
              class="mb-1.5 border-l-2 border-emerald-400/70 pl-2 text-xs leading-relaxed text-white/60"
            >
              你的原话：{{ q.quote }}
            </p>
            <p class="line-clamp-2 text-xs text-white/60">{{ q.comment }}</p>
            <p v-if="q.suggestion" class="mt-1.5 border-l-2 border-[#79d9ff] pl-2 text-xs leading-relaxed text-white/80">
              建议：{{ q.suggestion }}
            </p>
          </div>
        </div>

        <p v-if="!questionReviews.length" class="py-6 text-center text-sm text-white/40">本场没有逐题记录</p>

        <div v-if="sid !== null" class="mt-3 flex justify-center">
          <button class="btn btn-sm" :disabled="regenLoading" @click="regenerate">
            {{ regenLoading ? "重新评估中…" : "重新生成逐题复盘" }}
          </button>
        </div>
        <p v-if="regenError" class="mt-2 text-center text-xs text-red-400">{{ regenError }}</p>
      </MenuItem>

      <MenuItem v-if="expression" size="large" title="表达分析" icon="◍">
        <div class="mb-3 flex items-baseline gap-2">
          <span class="text-2xl font-black leading-none text-[#79d9ff]">{{ expression.overallScore }}</span>
          <span class="text-xs text-white/50">表达综合分 · 基于 {{ expression.sampleCount }} 次作答</span>
        </div>

        <div v-for="m in expression.metrics" :key="m.code" class="mb-3 last:mb-0">
          <div class="flex items-center justify-between text-xs">
            <span class="text-white/80">{{ m.label }}</span>
            <span class="text-white/50">
              {{ m.score === null ? "—" : m.score + " 分" }} · {{ m.value }}
            </span>
          </div>
          <div class="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-white/10">
            <div class="h-full rounded-full bg-[#79d9ff]" :style="{ width: `${m.score ?? 0}%` }" />
          </div>
          <p class="mt-1 text-[11px] leading-relaxed text-white/45">{{ m.comment }}</p>
        </div>

        <div v-if="expression.topFillers.length" class="mt-3 flex flex-wrap items-center gap-1.5">
          <span class="text-[11px] text-white/40">高频口头禅</span>
          <span
            v-for="f in expression.topFillers"
            :key="f"
            class="rounded-full bg-white/10 px-2 py-0.5 text-[11px] text-white/70"
          >
            {{ f }}
          </span>
        </div>

        <ul v-if="expression.suggestions.length" class="mt-3 space-y-1.5">
          <li
            v-for="(s, i) in expression.suggestions"
            :key="'e' + i"
            class="flex gap-2 text-xs leading-relaxed text-white/70"
          >
            <span class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-[#79d9ff]"></span>
            <span>{{ s }}</span>
          </li>
        </ul>

        <p class="mt-3 text-[11px] leading-relaxed text-white/35">{{ expression.note }}</p>
      </MenuItem>

      <MenuItem v-if="summary || summaryLoading || summaryError" size="large" title="对话总结" icon="✎">
        <div v-if="summaryLoading" class="py-6 text-center text-sm text-white/50">对话总结生成中…</div>
        <div v-else-if="summaryError" class="py-4 text-center text-xs text-red-400">
          {{ summaryError }}
          <button v-if="sid !== null" class="ml-2 underline" @click="loadSummary()">重试</button>
        </div>
        <div v-else-if="summary?.status === 'failed'" class="py-4 text-center text-xs text-red-400">
          对话总结生成失败：{{ summary.error || "未知原因" }}
          <button class="ml-2 underline" :disabled="summaryRegen" @click="regenerateSummary">
            {{ summaryRegen ? "重新生成中…" : "重新生成" }}
          </button>
        </div>
        <template v-else-if="summary">
          <p v-if="summary.overview" class="mb-4 text-xs leading-relaxed text-white/60">{{ summary.overview }}</p>

          <div
            v-for="q in summary.questions"
            :key="q.index"
            class="mb-3 rounded-lg border border-white/10 bg-white/[0.04] p-3 last:mb-0"
          >
            <div class="flex items-start gap-2">
              <span class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#79d9ff]/15 text-[11px] font-bold text-[#79d9ff]">
                {{ q.index }}
              </span>
              <div class="min-w-0 flex-1 text-sm leading-relaxed text-white">{{ q.question }}</div>
              <span class="shrink-0 rounded-full bg-white/10 px-2 py-0.5 text-[11px] text-white/60">
                深度 {{ q.depth }}
              </span>
            </div>
            <div class="mt-2 space-y-1 pl-8">
              <p v-for="(p, i) in q.points" :key="'p' + i" class="text-xs leading-relaxed text-emerald-300/80">
                · {{ p }}
              </p>
              <p v-for="(b, i) in q.blockers" :key="'b' + i" class="text-xs leading-relaxed text-red-300/80">
                ! {{ b }}
              </p>
            </div>
          </div>

          <div v-if="summary.coverage.covered.length || summary.coverage.missing.length" class="mt-4 space-y-2">
            <div v-if="summary.coverage.covered.length" class="flex flex-wrap items-center gap-1.5">
              <span class="text-[11px] text-white/40">已覆盖</span>
              <span
                v-for="item in summary.coverage.covered"
                :key="'c-' + item"
                class="rounded-full bg-emerald-400/15 px-2 py-0.5 text-[11px] text-emerald-300"
              >
                {{ item }}
              </span>
            </div>
            <div v-if="summary.coverage.missing.length" class="flex flex-wrap items-center gap-1.5">
              <span class="text-[11px] text-white/40">未覆盖</span>
              <span
                v-for="item in summary.coverage.missing"
                :key="'m-' + item"
                class="rounded-full bg-red-400/15 px-2 py-0.5 text-[11px] text-red-300"
              >
                {{ item }}
              </span>
            </div>
          </div>

          <div v-if="summary.vocabulary.good.length || summary.vocabulary.improve.length" class="mt-3 space-y-1">
            <p v-if="summary.vocabulary.good.length" class="text-xs leading-relaxed text-white/60">
              用词准确：{{ summary.vocabulary.good.join("、") }}
            </p>
            <p v-if="summary.vocabulary.improve.length" class="text-xs leading-relaxed text-white/60">
              可以更好：{{ summary.vocabulary.improve.join("；") }}
            </p>
          </div>

          <div class="mt-3 flex justify-center">
            <button class="btn btn-sm" :disabled="summaryRegen" @click="regenerateSummary">
              {{ summaryRegen ? "重新生成中…" : "重新生成总结" }}
            </button>
          </div>
        </template>
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
        <div class="flex flex-col gap-3">
          <button class="btn-big" @click="printPage">打印 / 导出 PDF</button>
          <button class="btn-big" @click="exportMarkdown">导出 Markdown</button>
        </div>
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
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageShell from "../components/ui/PageShell.vue";
import MenuItem from "../components/settings/MenuItem.vue";
import RadarChart from "../components/ui/RadarChart.vue";
import { jobs } from "../data/mock";
import { findInterviewer } from "../data/interviewers";
import { store } from "../store/appStore";
import { fetchEvaluation, runEvaluation, type EvaluationResult } from "../api/evaluation";
import { fetchSummary, runSummary, type DialogueSummary } from "../api/summary";
import { fetchExpression, type ExpressionReport } from "../api/expression";
import { fetchSessionDetail } from "../api/chat";
import type { InterviewRecord } from "../data/mock";

const route = useRoute();
const router = useRouter();

/** 后端场次（记录页 id 形如 s-12）在本地 store.records 里没有条目，按 ?sid= 拉详情兜底 */
const remoteRecord = ref<InterviewRecord | null>(null);
/** 后端场次没有逐题明细，题数用详情里的玩家发言条数还原 */
const remoteQuestionCount = ref(0);
/** 自由对话场次：后端 scriptCode 为空。显式提示，避免 0 分/0 题看起来像考砸了 */
const freeDialogue = ref(false);

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
  if (id === null) return;
  try {
    const detail = await fetchSessionDetail(id);
    // 自由对话没有 scriptCode：报告页改成提示，不跑评估也不再显示 0 分/0 题
    freeDialogue.value = !detail.scriptCode;
    // 玩家发言条数 = 实际作答轮次（剧本/自由都按 role=user 记账）
    remoteQuestionCount.value = detail.messages.filter((m) => m.role === "user").length;
    // 本地存档已经能渲染时，这次只借用详情判断场次类型
    if (record.value) return;
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
const regenLoading = ref(false);
const regenError = ref("");

/**
 * 拉评估：有就直接显示，404（还没评估过）就自动跑一次。
 *
 * 失败一律落到 evalError 给重试按钮，绝不抛到渲染层——没有评估也必须能看到本地存档那份报告。
 */
async function loadEvaluation() {
  const id = sid.value;
  if (id === null || skipEval.value || freeDialogue.value) return;
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

// ── 表达分析（issue #9·文本版）：由后端按台词现算，自由对话/作答不足时接口返回 400 ──

const expression = ref<ExpressionReport | null>(null);

/**
 * 拉表达分析。
 *
 * 后端对「自由对话」「作答不足 2 次」返回 400（不适用，不是故障），
 * `fetchExpression` 已把这两种折成 null，这里只管静默隐藏板块。
 */
async function loadExpression() {
  const id = sid.value;
  if (id === null || freeDialogue.value) return;
  try {
    expression.value = await fetchExpression(id);
  } catch (e) {
    console.warn("[report] 拉取表达分析失败", e);
  }
}

onMounted(async () => {
  await loadRemoteRecord();
  // 表达分析是纯计算，跟着场次详情一起拉，快且无副作用
  await loadExpression();
  // 总结在结束面试时后台生成，报告页打开时可能还没好：先并行轮询，评估照常走
  void loadSummary();
  await loadEvaluation();
});

// ── 对话总结（issue #4）：结束面试后由后端后台生成，这里轮询取结果 ──

const SUMMARY_POLL_INTERVAL = 3000;
/** 约 24 秒：正常一场 5~10 轮的总结在 30 秒内能出来；超过就当作没生成，允许手动触发 */
const SUMMARY_MAX_POLLS = 8;

const summary = ref<DialogueSummary | null>(null);
const summaryLoading = ref(false);
const summaryError = ref("");
const summaryRegen = ref(false);
let summaryPollTimer: number | undefined;

/**
 * 拉取（或轮询）总结。
 *
 * 404 表示后台还在生成 —— 每 3 秒重试一次，最多 8 次；
 * 仍没有就结束轮询、不显示该板块（用户可在其它入口重试）。
 * 生成失败（status=failed）是 200 响应，直接展示失败原因。
 */
async function loadSummary(attempt = 0) {
  const id = sid.value;
  if (id === null || skipEval.value || freeDialogue.value) return;
  if (summaryPollTimer) {
    window.clearTimeout(summaryPollTimer);
    summaryPollTimer = undefined;
  }
  summaryLoading.value = true;
  summaryError.value = "";
  try {
    const existing = await fetchSummary(id);
    summary.value = existing;
    if (existing) return;
    if (attempt < SUMMARY_MAX_POLLS) {
      summaryPollTimer = window.setTimeout(() => void loadSummary(attempt + 1), SUMMARY_POLL_INTERVAL);
      return;
    }
  } catch (e) {
    console.warn("[report] 拉取对话总结失败", e);
    summaryError.value = e instanceof Error ? e.message : "对话总结加载失败";
  } finally {
    // 调度了下一轮时保持「生成中」不变；拿到结果 / 出错 / 轮询结束才收尾
    if (summary.value || summaryError.value || attempt >= SUMMARY_MAX_POLLS) {
      summaryLoading.value = false;
    }
  }
}

/** 手动重新生成（失败重试 / 轮询没等到时补一刀）。非剧本场次后端会返回 400。 */
async function regenerateSummary() {
  const id = sid.value;
  if (id === null || summaryRegen.value) return;
  summaryRegen.value = true;
  summaryError.value = "";
  summaryLoading.value = true;
  try {
    summary.value = await runSummary(id);
  } catch (e) {
    summaryError.value = e instanceof Error ? e.message : "对话总结生成失败";
  } finally {
    summaryRegen.value = false;
    summaryLoading.value = false;
  }
}

onBeforeUnmount(() => {
  if (summaryPollTimer) window.clearTimeout(summaryPollTimer);
});

// ── 以下是「评估优先、本地兜底」：有评估用评估，没有完全退回读 store.records 的旧行为 ──

const score = computed(() => evaluation.value?.overallScore ?? record.value?.score ?? 0);
/** 逐题复盘优先用 AI 评估结果（含原话摘录），其次退回本地存档 */
interface QuestionRow {
  key: string;
  question: string;
  quote: string;
  score: number;
  comment: string;
  suggestion: string;
}
const questionReviews = computed<QuestionRow[]>(() => {
  const fromEval = evaluation.value?.questions ?? [];
  if (fromEval.length) {
    return fromEval.map((q) => ({
      key: `q-${q.index}`,
      question: q.question,
      quote: q.quote ?? "",
      score: q.score,
      comment: q.comment ?? "",
      suggestion: q.suggestion ?? "",
    }));
  }
  return (record.value?.questions ?? []).map((q) => ({
    key: String(q.id),
    question: q.question,
    quote: "",
    score: q.score,
    comment: q.comment,
    suggestion: q.suggest,
  }));
});

/** 题数：AI 逐题复盘 > 本地明细 > 详情里的玩家发言数 */
const questionCount = computed(
  () => questionReviews.value.length || record.value?.questions.length || remoteQuestionCount.value,
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

/** 统一成 {name, score, comment} 再排序，本地维度与评估维度的字段名差异在这里抹平 */
const dimsAsc = computed(() => {
  const ev = evaluation.value;
  const list =
    ev && ev.dimensions.length
      ? ev.dimensions.map((d) => ({ name: d.label, score: d.score, comment: d.comment ?? "" }))
      : (record.value?.dimensions ?? []).map((d) => ({ name: d.name, score: d.score, comment: "" }));
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

/**
 * 重新跑一次评估以补齐逐题复盘。
 * 旧版评估没有 questions 字段，只能整份重算——评估接口是覆盖式的，不会产生第二份。
 */
async function regenerate() {
  const id = sid.value;
  if (id === null || regenLoading.value) return;
  regenLoading.value = true;
  regenError.value = "";
  try {
    evaluation.value = await runEvaluation(id);
    evalError.value = "";
  } catch (e) {
    regenError.value = e instanceof Error ? e.message : "重新评估失败";
  } finally {
    regenLoading.value = false;
  }
}

/** 导出 Markdown：答辩时能当场给出一份可读的报告文件，不依赖打印对话框。 */
function exportMarkdown() {
  const lines: string[] = [];
  lines.push(`# 面试报告`, "");
  lines.push(`- 岗位：${record.value?.jobName ?? "-"}`);
  lines.push(`- 面试官：${record.value?.personaName ?? "-"}`);
  lines.push(`- 总分：${score.value}`);
  lines.push(`- 题数：${questionCount.value}`);
  if (evaluation.value?.summary) lines.push(`- 总评：${evaluation.value.summary}`);
  lines.push("");

  if (dimsDesc.value.length) {
    lines.push("## 维度评分", "");
    lines.push("| 维度 | 分数 | 评语 |", "| --- | --- | --- |");
    for (const d of dimsDesc.value) {
      lines.push(`| ${d.name} | ${d.score} | ${d.comment ?? ""} |`);
    }
    lines.push("");
  }

  if (questionReviews.value.length) {
    lines.push("## 逐题复盘", "");
    questionReviews.value.forEach((q, i) => {
      lines.push(`### ${i + 1}. ${q.question}（${q.score} 分）`);
      if (q.quote) lines.push(`> 你的原话：${q.quote}`);
      if (q.comment) lines.push(`- 点评：${q.comment}`);
      if (q.suggestion) lines.push(`- 建议：${q.suggestion}`);
      lines.push("");
    });
  }

  if (expression.value) {
    const e = expression.value;
    lines.push("## 表达分析", "", `综合分：${e.overallScore}（基于 ${e.sampleCount} 次作答）`, "");
    for (const m of e.metrics) {
      lines.push(`- ${m.label}：${m.score === null ? "—" : m.score} 分（${m.value}）`);
    }
    if (e.topFillers.length) lines.push(`- 高频口头禅：${e.topFillers.join("、")}`);
    for (const s of e.suggestions) lines.push(`- 建议：${s}`);
    lines.push("");
  }

  if (summary.value?.status === "ok") {
    const s = summary.value;
    lines.push("## 对话总结", "");
    if (s.overview) lines.push(`> ${s.overview}`, "");
    s.questions.forEach((q) => {
      lines.push(`### ${q.index}. ${q.question}（深度 ${q.depth}）`);
      q.points.forEach((p) => lines.push(`- 论点：${p}`));
      q.blockers.forEach((b) => lines.push(`- 卡壳：${b}`));
      lines.push("");
    });
    if (s.coverage.covered.length) lines.push(`- 已覆盖：${s.coverage.covered.join("、")}`);
    if (s.coverage.missing.length) lines.push(`- 未覆盖：${s.coverage.missing.join("、")}`);
    if (s.vocabulary.good.length) lines.push(`- 用词准确：${s.vocabulary.good.join("、")}`);
    if (s.vocabulary.improve.length) lines.push(`- 可以更好：${s.vocabulary.improve.join("；")}`);
    lines.push("");
  }

  if (highlights.value.length) {
    lines.push("## 亮点", "", ...highlights.value.map((h) => `- ${h}`), "");
  }
  if (weaknesses.value.length) {
    lines.push("## 不足", "", ...weaknesses.value.map((w) => `- ${w}`), "");
  }
  if (plans.value.length) {
    lines.push("## 改进建议", "", ...plans.value.map((p) => `- ${p.advice}`), "");
  }

  const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `面试报告-${route.params.id}.md`;
  link.click();
  URL.revokeObjectURL(url);
}

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
