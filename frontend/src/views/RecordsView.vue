<template>
  <PageShell title="面试记录">
    <MenuItem v-if="store.session.active" size="large" title="进行中的面试" icon="▶">
      <div class="relative rounded-xl border border-[#79d9ff] bg-[#79d9ff]/[0.08] p-4 shadow-[0_0_24px_rgba(121,217,255,0.15)]">
        <span class="absolute top-3 left-3 rounded-full bg-[#79d9ff]/20 px-2 py-0.5 text-[10px] font-bold tracking-wide text-[#79d9ff]">
          进行中
        </span>
        <div class="flex flex-wrap items-end justify-between gap-4 pt-5">
          <div class="min-w-0">
            <div class="text-lg font-bold text-white">
              {{ activeJob.name }} · {{ activePersona.name }} · {{ activePersona.displayRole }}
            </div>
            <div class="mt-1 font-mono text-xs text-white/50">
              第 {{ store.session.questionIndex + 1 }} 题 · 已进行 {{ elapsedText }}
            </div>
          </div>
          <button
            class="shrink-0 cursor-pointer rounded-full border border-emerald-400/40 bg-emerald-500/15 px-5 py-2 text-xs font-bold text-emerald-300 transition-colors hover:bg-emerald-500/25"
            @click="router.push('/interview')"
          >
            继续面试
          </button>
        </div>
      </div>
    </MenuItem>

    <MenuItem v-if="records.length" size="large" title="历史面试" icon="▤">
      <div class="mb-4 flex flex-wrap items-center justify-end gap-2">
        <button
          v-if="!batchMode"
          class="cursor-pointer rounded-full border border-red-500/40 bg-red-500/15 px-4 py-1.5 text-xs font-bold text-red-300 transition-colors hover:bg-red-500/25"
          @click="enterBatch"
        >
          批量删除
        </button>
        <template v-else>
          <span class="mr-auto text-xs text-white/50">已选 {{ selected.size }} / {{ records.length }}</span>
          <button
            class="cursor-pointer rounded-full border border-white/20 bg-white/5 px-4 py-1.5 text-xs font-bold text-white/70 transition-colors hover:bg-white/10"
            @click="toggleSelectAll"
          >
            {{ allSelected ? "取消全选" : "全选" }}
          </button>
          <button
            class="cursor-pointer rounded-full border border-red-500/40 bg-red-500/15 px-4 py-1.5 text-xs font-bold text-red-300 transition-colors hover:bg-red-500/25 disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="!selected.size"
            @click="removeSelected"
          >
            删除选中（{{ selected.size }}）
          </button>
          <button
            class="cursor-pointer rounded-full border border-white/15 bg-white/5 px-4 py-1.5 text-xs font-bold text-white/60 transition-colors hover:bg-white/10"
            @click="exitBatch"
          >
            取消
          </button>
        </template>
      </div>
      <div class="grid auto-rows-fr grid-cols-1 gap-4 md:grid-cols-2">
        <div
          v-for="(r, i) in records"
          :key="r.id"
          class="flex h-full flex-col rounded-xl border bg-white/[0.05] p-4 transition-all duration-300"
          :class="batchMode && selected.has(r.id)
            ? 'border-red-400/60 bg-red-500/[0.08]'
            : 'border-white/12 hover:-translate-y-[3px] hover:border-[#79d9ff]/50'"
        >
          <div class="flex items-center gap-2">
            <input
              v-if="batchMode"
              type="checkbox"
              :checked="selected.has(r.id)"
              class="size-4 shrink-0 cursor-pointer accent-red-400"
              @change="toggle(r.id)"
            />
            <div class="font-mono text-[10px] tracking-wide text-white/40">
              No.{{ i + 1 }} · {{ formatDate(r.date) }}
            </div>
          </div>

          <div class="mt-1 truncate text-base font-bold text-white" :title="r.jobName">
            {{ r.jobName }} · {{ personaShort(r.personaName) }}
          </div>

          <div class="my-2 border-b border-dashed border-white/10"></div>

          <div class="flex items-baseline gap-2">
            <span class="text-2xl font-bold text-[#79d9ff]">{{ r.score }} 分</span>
            <span class="text-xs text-white/50">{{ r.questions.length }} 题 · {{ minutes(r.duration) }} 分钟</span>
          </div>

          <p class="mt-2 line-clamp-2 text-xs italic text-white/40">{{ excerpt(r) }}</p>

          <div v-if="!batchMode" class="mt-auto flex flex-wrap gap-2 pt-3">
            <button
              class="cursor-pointer rounded-full border border-[#79d9ff]/40 bg-[#79d9ff]/15 px-3 py-1 text-xs font-bold text-[#79d9ff] transition-colors hover:bg-[#79d9ff]/25"
              @click="openReport(r)"
            >
              查看报告
            </button>
            <button
              class="cursor-pointer rounded-full border border-red-500/40 bg-red-500/15 px-3 py-1 text-xs font-bold text-red-300 transition-colors hover:bg-red-500/25"
              @click="remove(r)"
            >
              删除
            </button>
          </div>
        </div>
      </div>
    </MenuItem>

    <MenuItem v-else size="large" title="历史面试" icon="▤">
      <div class="flex flex-col items-center py-16">
        <p class="text-sm text-white/40">还没有面试记录</p>
        <button class="btn-big mt-4 max-w-[260px]" @click="startNew">去开始一场面试</button>
      </div>
    </MenuItem>

    <MenuItem v-if="trendPoints.length" size="large" title="成长曲线" icon="↗">
      <p class="mb-3 text-xs text-white/45">只统计剧本场次的 AI 评估，自由对话不计入。</p>
      <LineChart
        v-if="trendPoints.length >= requiredPoints"
        :labels="trendLabels"
        :series="trendSeries"
        :height="260"
      />
      <div v-else class="py-10 text-center">
        <p class="text-sm text-white/50">
          数据不足，再练 {{ requiredPoints - trendPoints.length }} 场就能看到变化。
        </p>
        <p class="mt-1 text-xs text-white/35">
          当前已有 {{ trendPoints.length }} 场带评估的剧本面试（至少 {{ requiredPoints }} 场才画线）。
        </p>
      </div>
    </MenuItem>

    <MenuItem v-if="recommendations.length" size="large" title="提升路径" icon="→">
      <p class="mb-3 text-xs text-white/45">
        按最近几场的短板维度推荐知识库条目，点「开始练习」直接进入同岗位面试。
      </p>
      <div
        v-for="rec in recommendations"
        :key="rec.dimCode"
        class="mb-4 rounded-xl border border-white/10 bg-white/[0.04] p-4 last:mb-0"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <span class="text-sm font-bold text-white">{{ rec.label }}</span>
            <span class="ml-2 text-xs text-[#ffd166]">最近平均 {{ rec.averageScore }} 分 · 短板</span>
          </div>
          <button
            class="shrink-0 cursor-pointer rounded-full border border-[#79d9ff]/40 bg-[#79d9ff]/15 px-4 py-1.5 text-xs font-bold text-[#79d9ff] transition-colors hover:bg-[#79d9ff]/25"
            @click="practice(rec)"
          >
            开始练习
          </button>
        </div>
        <p class="mt-1 text-[11px] text-white/35">推荐依据：{{ rec.query }}</p>
        <ul class="mt-3 space-y-2">
          <li
            v-for="hit in rec.hits"
            :key="hit.chunkId"
            class="rounded-lg border border-white/10 bg-[#0b1522]/60 p-2.5"
          >
            <div class="text-xs font-bold text-white/80">
              {{ hit.docTitle }}
              <span v-if="hit.heading" class="text-white/45"> · {{ hit.heading }}</span>
            </div>
            <p class="mt-1 line-clamp-2 text-[11px] leading-relaxed text-white/55">{{ hit.text }}</p>
          </li>
        </ul>
        <p v-if="!rec.hits.length" class="mt-3 text-xs text-white/40">
          知识库里暂无对应条目，可先在「设置 → 知识库」导入语料。
        </p>
      </div>
    </MenuItem>
  </PageShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import PageShell from "../components/ui/PageShell.vue";
import MenuItem from "../components/settings/MenuItem.vue";
import LineChart from "../components/ui/LineChart.vue";
import { jobs, type InterviewRecord } from "../data/mock";
import { findInterviewer } from "../data/interviewers";
import { confirmDialog } from "../store/dialogStore";
import { notify, persist, startSession, store } from "../store/appStore";
import { deleteSession, fetchSessions, isBackendUp, type SessionSummary } from "../api/chat";
import { fetchTrend, type TrendPoint, type TrendRecommendation } from "../api/reports";

const router = useRouter();

const activeJob = computed(() => jobs.find((j) => j.id === store.session.jobId) ?? jobs[0]);
const activePersona = computed(() => findInterviewer(store.session.personaId));

/** 后端已完成场次（只读条目，id 前缀 s- 与本地的 r- 区分；不写回 store.records） */
const remoteRecords = ref<InterviewRecord[]>([]);
/** s- 条目 → 原始场次（弹窗要显示 status） */
const remoteSummaries = new Map<string, SessionSummary>();

const records = computed(() => [...store.records, ...remoteRecords.value]);

// ── 批量删除：进入选择态后每张卡片前出现勾选框，按 id 记录选中项 ──
const batchMode = ref(false);
const selected = ref<Set<string>>(new Set());
const allSelected = computed(() => records.value.length > 0 && selected.value.size === records.value.length);

function enterBatch() {
  batchMode.value = true;
  selected.value = new Set();
}

function exitBatch() {
  batchMode.value = false;
  selected.value = new Set();
}

function toggle(id: string) {
  const next = new Set(selected.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selected.value = next;
}

function toggleSelectAll() {
  selected.value = allSelected.value ? new Set() : new Set(records.value.map((r) => r.id));
}

/** 本地记录删存档；后端场次调 DELETE 真删（连同台词与评估一起清） */
async function removeSelected() {
  const ids = [...selected.value];
  if (!ids.length) return;
  const ok = await confirmDialog("删除选中的记录？", `共 ${ids.length} 条，删除后不可恢复。`);
  if (!ok) return;

  const remoteIds = ids.filter((id) => id.startsWith("s-"));
  const localIds = ids.filter((id) => !id.startsWith("s-"));
  for (const id of localIds) {
    const idx = store.records.findIndex((r) => r.id === id);
    if (idx >= 0) store.records.splice(idx, 1);
  }
  if (localIds.length) persist();

  let failed = 0;
  if (remoteIds.length) {
    const results = await Promise.allSettled(
      remoteIds.map((id) => deleteSession(Number(id.replace(/^s-/, "")))),
    );
    const deletedIds = remoteIds.filter((_, i) => results[i].status === "fulfilled");
    failed = remoteIds.length - deletedIds.length;
    remoteRecords.value = remoteRecords.value.filter((r) => !deletedIds.includes(r.id));
    deletedIds.forEach((id) => remoteSummaries.delete(id));
  }

  exitBatch();
  notify(failed ? `已删除 ${ids.length - failed} 条，${failed} 条后端删除失败` : `已删除 ${ids.length} 条记录`);
}

/** 成长曲线：只来自后端评估（本地记录的分是 mock，不参与趋势） */
const trendPoints = ref<TrendPoint[]>([]);
const requiredPoints = ref(3);
const recommendations = ref<TrendRecommendation[]>([]);

const trendLabels = computed(() => trendPoints.value.map((p) => p.date.slice(5, 10)));

/** 维度固定顺序 + 各自配色；缺该维度的场次不参与这条线 */
const DIM_ORDER = ["technical", "depth", "logic", "fit"] as const;
const DIM_COLORS = ["#ffd166", "#7ee787", "#a78bfa", "#ff8fa3"];

const trendSeries = computed(() => {
  const points = trendPoints.value;
  if (!points.length) return [];
  const total = {
    name: "总分",
    values: points.map((p) => p.overallScore),
    color: "#79d9ff",
  };
  const dims = DIM_ORDER.map((code, i) => ({
    name: points[0].dimensions.find((d) => d.code === code)?.label ?? code,
    values: points.map((p) => p.dimensions.find((d) => d.code === code)?.score ?? 0),
    color: DIM_COLORS[i],
    present: points.some((p) => p.dimensions.some((d) => d.code === code)),
  }))
    .filter((s) => s.present)
    .map((s) => ({ name: s.name, values: s.values, color: s.color }));
  return [total, ...dims];
});

/** 短板推荐点进去＝同岗位新开一场练习 */
function practice(rec: TrendRecommendation) {
  const jobCode = trendPoints.value[trendPoints.value.length - 1]?.jobCode;
  const job = jobs.find((j) => j.id === jobCode) ?? jobs[0];
  startSession(job.id, store.settings.personaId, "free");
  notify(`已开始「${rec.label}」专项练习`);
  router.push("/interview");
}

const elapsed = ref(0);
let timer: number | undefined;

const elapsedText = computed(() => {
  const m = Math.floor(elapsed.value / 60);
  const s = elapsed.value % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
});

function tick() {
  if (!store.session.startedAt) {
    elapsed.value = 0;
    return;
  }
  elapsed.value = Math.max(0, Math.floor((Date.now() - store.session.startedAt) / 1000));
}

onMounted(() => {
  tick();
  timer = window.setInterval(tick, 1000);
  void loadRemoteRecords();
  void loadTrend();
});

/** 后端在线则把已完成场次并进列表；离线完全保持纯本地列表，不报错 */
async function loadRemoteRecords() {
  if (!(await isBackendUp())) return;
  try {
    const sessions = await fetchSessions("finished");
    remoteRecords.value = sessions.map(toRecord);
    remoteSummaries.clear();
    sessions.forEach((summary) => remoteSummaries.set("s-" + summary.id, summary));
    pruneDuplicateLocalRecords(new Set(sessions.map((s) => s.id)));
  } catch (error) {
    console.warn("[records] 拉取后端场次失败，只用本地记录", error);
  }
}

/**
 * 清掉旧版本留下的重复本地存档：本地记录只要带着后端场次 id，就说明这一场后端已有记录，
 * 本地那份是当初 finish() 额外写的 mock 分副本（典型表现是每场面试多出一条 86 分记录）。
 */
function pruneDuplicateLocalRecords(remoteIds: Set<number>) {
  const kept = store.records.filter((r) => !(r.sessionId && remoteIds.has(r.sessionId)));
  if (kept.length === store.records.length) return;
  store.records.splice(0, store.records.length, ...kept);
  persist();
}

/** 成长曲线/提升路径：后端没起来就整块不显示，避免拿本地 mock 分数糊一条假的趋势 */
async function loadTrend() {
  try {
    const view = await fetchTrend();
    trendPoints.value = view.points;
    requiredPoints.value = view.requiredPoints;
    recommendations.value = view.recommendations;
  } catch (error) {
    console.warn("[records] 拉取成长曲线失败，暂不展示趋势", error);
  }
}

/** 后端场次 → 本地 InterviewRecord 形状的只读条目（无维度/题目/建议） */
function toRecord(summary: SessionSummary): InterviewRecord {
  return {
    id: "s-" + summary.id,
    // 岗位/面试官按场次字段映射成中文名，别直接拿后端 title（那是「姓名 · 岗位 code」拼的）
    jobName: jobs.find((j) => j.id === summary.jobCode)?.name ?? summary.title,
    personaName: findInterviewer(summary.interviewerCode)?.name ?? summary.interviewerCode ?? "-",
    date: summary.endedAt ?? summary.startedAt ?? "-",
    duration: "-",
    score: summary.score ?? 0,
    dimensions: [],
    questions: [],
    advice: [],
  };
}

function isRemote(r: InterviewRecord): boolean {
  return r.id.startsWith("s-");
}

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});

function formatDate(raw: string): string {
  return raw.slice(0, 10).replace(/-/g, ".");
}

function personaShort(name: string): string {
  return name.split(" · ")[0];
}

function minutes(duration: string): string {
  const m = duration.match(/(\d+)\s*分/);
  return m ? m[1] : "0";
}

function excerpt(r: InterviewRecord): string {
  const last = r.questions.length ? r.questions[r.questions.length - 1] : null;
  return last ? `“${last.question}”` : "暂无对话摘录";
}

/** 后端条目没有逐题数据，报告页按 ?sid= 拉详情与评估，逐题区块显示「本场没有逐题记录」 */
async function openReport(r: InterviewRecord) {
  if (!isRemote(r)) {
    // 有后端场次 id 就带上，报告页靠它去拉 AI 评估；老存档没有 sid 会退回本地数据
    router.push(r.sessionId ? `/report/${r.id}?sid=${r.sessionId}` : "/report/" + r.id);
    return;
  }
  // 后端场次：本地没有对应的 store.records，报告页会用 ?sid= 拉场次详情 + 评估自行渲染
  router.push(`/report/${r.id}?sid=${String(r.id).replace(/^s-/, "")}`);
}

async function remove(r: InterviewRecord) {
  const ok = await confirmDialog("删除这条记录？", "删除后不可恢复。");
  if (!ok) return;
  if (isRemote(r)) {
    try {
      await deleteSession(Number(r.id.replace(/^s-/, "")));
      remoteRecords.value = remoteRecords.value.filter((x) => x.id !== r.id);
      remoteSummaries.delete(r.id);
      notify("记录已删除");
    } catch (error) {
      console.warn("[records] 后端删除失败", error);
      notify("后端删除失败，请稍后重试");
    }
    return;
  }
  const idx = store.records.findIndex((x) => x.id === r.id);
  if (idx < 0) return;
  store.records.splice(idx, 1);
  persist();
  notify("记录已删除");
}

function startNew() {
  startSession(store.settings.jobId, store.settings.personaId);
  router.push("/interview");
}
</script>
