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
      <div class="grid auto-rows-fr grid-cols-1 gap-4 md:grid-cols-2">
        <div
          v-for="(r, i) in records"
          :key="r.id"
          class="flex h-full flex-col rounded-xl border border-white/12 bg-white/[0.05] p-4 transition-all duration-300 hover:-translate-y-[3px] hover:border-[#79d9ff]/50"
        >
          <div class="font-mono text-[10px] tracking-wide text-white/40">
            No.{{ i + 1 }} · {{ formatDate(r.date) }}
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

          <div class="mt-auto flex flex-wrap gap-2 pt-3">
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

    <MenuItem v-if="curveRecords.length >= 2" size="large" title="成长曲线" icon="↗">
      <LineChart :labels="chartLabels" :values="chartValues" :height="240" />
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
import { fetchSessions, isBackendUp, type SessionSummary } from "../api/chat";

const router = useRouter();

const activeJob = computed(() => jobs.find((j) => j.id === store.session.jobId) ?? jobs[0]);
const activePersona = computed(() => findInterviewer(store.session.personaId));

/** 后端已完成场次（只读条目，id 前缀 s- 与本地的 r- 区分；不写回 store.records） */
const remoteRecords = ref<InterviewRecord[]>([]);
/** s- 条目 → 原始场次（弹窗要显示 status） */
const remoteSummaries = new Map<string, SessionSummary>();

const records = computed(() => [...store.records, ...remoteRecords.value]);

/** 只有本地条目带 dimensions，成长曲线跳过后端条目 */
const curveRecords = computed(() => records.value.filter((r) => r.dimensions.length > 0).reverse());
const chartLabels = computed(() => curveRecords.value.map((r) => r.date.slice(5, 10)));
const chartValues = computed(() => curveRecords.value.map((r) => r.score));

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
});

/** 后端在线则把已完成场次并进列表；离线完全保持纯本地列表，不报错 */
async function loadRemoteRecords() {
  if (!(await isBackendUp())) return;
  try {
    const sessions = await fetchSessions("finished");
    remoteRecords.value = sessions.map(toRecord);
    remoteSummaries.clear();
    sessions.forEach((summary) => remoteSummaries.set("s-" + summary.id, summary));
  } catch (error) {
    console.warn("[records] 拉取后端场次失败，只用本地记录", error);
  }
}

/** 后端场次 → 本地 InterviewRecord 形状的只读条目（无维度/题目/建议） */
function toRecord(summary: SessionSummary): InterviewRecord {
  return {
    id: "s-" + summary.id,
    jobName: summary.title,
    personaName: summary.interviewerCode ?? "-",
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

/** 后端条目没有逐题数据，ReportView 渲染不了，改成弹窗显示摘要 */
async function openReport(r: InterviewRecord) {
  if (!isRemote(r)) {
    // 有后端场次 id 就带上，报告页靠它去拉 AI 评估；老存档没有 sid 会退回本地数据
    router.push(r.sessionId ? `/report/${r.id}?sid=${r.sessionId}` : "/report/" + r.id);
    return;
  }
  const summary = remoteSummaries.get(r.id);
  const title = summary?.title ?? r.jobName;
  const status = summary?.status ?? "-";
  const score = summary?.score ?? r.score;
  await confirmDialog(title, `状态：${status}　总分：${score}`);
}

async function remove(r: InterviewRecord) {
  const ok = await confirmDialog("删除这条记录？", "删除后不可恢复。");
  if (!ok) return;
  if (isRemote(r)) {
    // 后端条目只从展示列表移除，不调后端
    remoteRecords.value = remoteRecords.value.filter((x) => x.id !== r.id);
    remoteSummaries.delete(r.id);
    notify("记录已从列表移除");
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
