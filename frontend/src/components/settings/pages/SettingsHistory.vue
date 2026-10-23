<template>
  <div class="settings-history-page">
    <MenuPage>
      <MenuItem title="历史对话">
        <template #header>
          <History :size="20" />
        </template>
        <div class="flex h-full max-h-[75dvh] min-h-0 flex-col">
          <div v-if="records.length === 0" class="flex flex-1 items-center justify-center">
            <div
              class="py-8 text-center text-2xl font-bold text-gray-100 [text-shadow:0_0_5px_rgba(255,255,255,0.5)]"
            >
              暂无历史记录，去和面试官聊聊天叭(*^▽^*)
            </div>
          </div>

          <div
            v-else
            class="scroll-brand scrollbar-thin min-h-0 flex-1 overflow-y-auto scroll-smooth px-1.5 py-3.5"
          >
            <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
              <button
                v-for="(record, index) in records"
                :key="record.id"
                class="group flex flex-col rounded-2xl border border-white/20 bg-white/10 p-4 text-left backdrop-blur shadow-[0_8px_32px_rgba(0,0,0,0.2)] transition-all duration-300 ease-[cubic-bezier(0.25,0.8,0.25,1)] hover:-translate-y-0.75 hover:border-[rgba(121,217,255,0.35)] hover:bg-white/15 hover:shadow-[0_12px_40px_rgba(121,217,255,0.08)]"
                @click="openDetail(record)"
              >
                <div class="flex items-center justify-between font-mono text-xs text-white/40">
                  <span class="font-bold">No.{{ index + 1 }}</span>
                  <span class="flex items-center gap-1">
                    <Clock :size="10" />
                    {{ formatDate(record.date) }}
                  </span>
                </div>

                <div class="mt-1.5 flex items-center gap-2 overflow-hidden">
                  <span class="truncate text-sm font-bold text-white">{{ recordTitle(record) }}</span>
                  <span
                    class="shrink-0 rounded-full border border-white/15 bg-white/5 px-1.5 py-0.5 text-[10px] text-white/55"
                  >
                    {{ record.questions.length }} 题
                  </span>
                </div>

                <div class="mt-2 line-clamp-2 text-xs leading-[1.5] text-white/65 italic">
                  {{ recordSummary(record) }}
                </div>

                <div
                  class="mt-3 flex items-center justify-between border-t border-white/10 pt-2 text-[11px] text-white/40"
                >
                  <span class="truncate">{{ record.personaName }}</span>
                  <span class="flex shrink-0 items-center gap-1">
                    身份：求职者
                    <ChevronRight
                      :size="12"
                      class="text-white/30 transition-transform duration-200 group-hover:translate-x-0.5 group-hover:text-brand"
                    />
                  </span>
                </div>
              </button>
            </div>
          </div>
        </div>
      </MenuItem>
    </MenuPage>

    <!-- 三级详情：会话详情 → 角色详情 / 用户详情 -->
    <Teleport to="body">
      <div
        v-if="detailOpen"
        class="fixed inset-0 z-[9000] flex items-center justify-center bg-black/60 p-6 backdrop-blur-sm"
        @click.self="closeDetail"
      >
        <div
          class="flex max-h-[82vh] w-[min(920px,92vw)] flex-col overflow-hidden rounded-2xl border border-white/20 bg-white/10 backdrop-blur-xl shadow-[0_24px_70px_rgba(0,0,0,0.55)]"
        >
          <div class="flex items-center justify-between border-b border-white/10 px-5 py-3">
            <div class="flex items-center gap-2 text-sm">
              <button
                class="cursor-pointer rounded-md px-2 py-1 transition-colors duration-200"
                :class="
                  level === 'session'
                    ? 'text-white'
                    : 'text-white/45 hover:bg-white/10 hover:text-white'
                "
                @click="level = 'session'"
              >
                会话详情
              </button>
              <template v-if="level !== 'session'">
                <span class="text-white/25">/</span>
                <span class="rounded-md bg-white/10 px-2 py-1 font-semibold text-white">
                  {{ level === "character" ? "角色详情" : "用户详情" }}
                </span>
              </template>
            </div>
            <button
              class="cursor-pointer rounded-md p-1 text-white/50 transition-colors duration-200 hover:bg-white/10 hover:text-white"
              title="关闭"
              @click="closeDetail"
            >
              <X :size="18" />
            </button>
          </div>

          <div
            v-if="activeRecord"
            class="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-white/10 px-5 py-2 font-mono text-xs text-white/45"
          >
            <span class="font-bold text-white/70">No.{{ activeIndex + 1 }}</span>
            <span>{{ activeRecord.jobName }}</span>
            <span class="text-white/20">·</span>
            <span>{{ activeRecord.personaName }}</span>
            <span class="text-white/20">·</span>
            <span>{{ formatDate(activeRecord.date) }}</span>
            <span class="text-white/20">·</span>
            <span>{{ activeRecord.duration }}</span>
            <span class="text-white/20">·</span>
            <span>{{ activeRecord.score }} 分</span>
          </div>

          <div
            class="history-modal-body scroll-brand scrollbar-thin min-h-0 flex-1 overflow-y-auto px-5 py-4 text-white"
            style="line-height: 1.9; font-size: 16px"
          >
            <DialogSession
              v-if="level === 'session'"
              :dialog="sessionMessages"
              @open-user="openUser"
              @open-character="openCharacter"
            />

            <div v-else-if="activeMessage" class="space-y-4">
              <div class="rounded-xl border border-white/15 bg-black/20 p-4">
                <DialogCharacter
                  v-if="level === 'character'"
                  :name="activeMessage.displayName"
                  :content="activeMessage.content"
                  :action_content="activeMessage.motionText"
                  :emotionTag="activeMessage.originalTag ?? ''"
                  :emotionText="activeMessage.comment ?? ''"
                />
                <DialogUser
                  v-else
                  :name="activeMessage.displayName"
                  :content="activeMessage.content"
                />
              </div>

              <div class="space-y-2">
                <div
                  v-for="row in detailRows"
                  :key="row.label"
                  class="flex gap-3 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm"
                >
                  <span class="w-20 shrink-0 text-white/45">{{ row.label }}</span>
                  <span class="min-w-0 flex-1 leading-normal text-white/85">{{ row.value }}</span>
                </div>
              </div>

              <button
                class="cursor-pointer rounded-md border border-white/15 bg-white/5 px-3 py-1.5 text-xs text-white/60 transition-colors duration-200 hover:border-[rgba(121,217,255,0.4)] hover:bg-white/10 hover:text-white"
                @click="level = 'session'"
              >
                返回会话详情
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ChevronRight, Clock, History, X } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import DialogSession, { type SessionMessage } from "../history/DialogSession.vue";
import DialogCharacter from "../history/DialogCharacter.vue";
import DialogUser from "../history/DialogUser.vue";
import { store } from "../../../store/appStore";
import type { InterviewRecord } from "../../../data/mock";

type SaveRecord = InterviewRecord & {
  saveTitle?: string;
  transcript?: { role: "ai" | "user"; text: string; time: string }[];
};

type DetailLevel = "session" | "character" | "user";

const records = computed(() => store.records as SaveRecord[]);

const recordTitle = (record: SaveRecord) => record.saveTitle || `${record.jobName} 面试`;
const recordSummary = (record: SaveRecord) =>
  record.advice[0] || `${record.duration} · 共 ${record.questions.length} 题`;

const pad2 = (n: number) => String(n).padStart(2, "0");

function formatDate(value: string): string {
  const date = new Date(value.replace(/-/g, "/"));
  if (Number.isNaN(date.getTime())) return value;
  return `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

// ---------- 弹窗状态 ----------
const detailOpen = ref(false);
const level = ref<DetailLevel>("session");
const activeRecord = ref<SaveRecord | null>(null);
const activeMessage = ref<SessionMessage | null>(null);

const activeIndex = computed(() => {
  const rec = activeRecord.value;
  return rec ? records.value.findIndex((r) => r.id === rec.id) : -1;
});

/** 会话内容：优先用存档时的对话快照，没有快照就按逐题复盘还原一问一答 */
const sessionMessages = computed<SessionMessage[]>(() => {
  const rec = activeRecord.value;
  if (!rec) return [];

  if (rec.transcript?.length) {
    return rec.transcript.map((line, i) => ({
      id: `${rec.id}-t${i}`,
      type: line.role === "ai" ? ("reply" as const) : ("message" as const),
      displayName: line.role === "ai" ? rec.personaName : "求职者",
      content: line.text,
      originalTag: line.role === "ai" ? "面试官发言" : "求职者发言",
      isFinal: i === rec.transcript!.length - 1,
    }));
  }

  const list: SessionMessage[] = [];
  rec.questions.forEach((q, i) => {
    list.push({
      id: `${rec.id}-q${i}`,
      type: "reply",
      displayName: rec.personaName,
      content: q.question,
      motionText: `考察：${q.kp}`,
      originalTag: `${q.kp} · ${q.score} 分`,
      score: q.score,
      comment: q.comment,
    });
    list.push({
      id: `${rec.id}-a${i}`,
      type: "message",
      displayName: "求职者",
      content: `复盘要点：${q.suggest}`,
      originalTag: `本题 ${q.score} 分`,
      score: q.score,
      comment: q.suggest,
    });
  });
  return list;
});

const detailRows = computed(() => {
  const msg = activeMessage.value;
  if (!msg) return [];
  const rows = [
    {
      label: msg.type === "reply" ? "面试官" : "我的身份",
      value: msg.type === "reply" ? (activeRecord.value?.personaName ?? "") : "求职者",
    },
    { label: "本题得分", value: typeof msg.score === "number" ? `${msg.score} 分` : "" },
    { label: msg.type === "reply" ? "面试官点评" : "复盘建议", value: msg.comment ?? "" },
  ];
  return rows.filter((r) => r.value);
});

function openDetail(record: SaveRecord): void {
  activeRecord.value = record;
  activeMessage.value = null;
  level.value = "session";
  detailOpen.value = true;
}

function openCharacter(message: SessionMessage): void {
  activeMessage.value = message;
  level.value = "character";
}

function openUser(message: SessionMessage): void {
  activeMessage.value = message;
  level.value = "user";
}

function closeDetail(): void {
  detailOpen.value = false;
  activeMessage.value = null;
  level.value = "session";
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === "Escape" && detailOpen.value) closeDetail();
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onUnmounted(() => window.removeEventListener("keydown", onKeydown));
</script>

<style scoped>
.settings-history-page {
  width: 100%;
  height: 100%;
}

/* 会话详情里一条台词与下一条之间留一点呼吸（原版 DialogSession 自身行高 1.2） */
.history-modal-body :deep(.message-item) {
  margin-bottom: 8px;
  line-height: 1.7;
}

/* 滚动条染色：原来是 Tailwind 任意属性写法 [scrollbar-color:var(...)]，
   按 V4 规格 A 挪进 <style>（CSS 属性里用 var(--accent-color) 是合法用法） */
.scroll-brand {
  scrollbar-color: var(--accent-color, #79d9ff) transparent;
}
</style>
