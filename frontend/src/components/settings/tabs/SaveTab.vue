<template>
  <div>
    <MenuItem size="large" title="创建新存档" icon="➕">
      <div class="flex items-center gap-3">
        <input v-model="nameDraft" class="glass-input flex-1" placeholder="给这次面试起个名字" @keyup.enter="createSave" />
        <button
          class="shrink-0 cursor-pointer rounded-lg bg-emerald-500 px-4 py-2 text-sm font-bold text-white transition-colors hover:bg-emerald-400"
          @click="createSave"
        >
          创建
        </button>
      </div>
    </MenuItem>

    <MenuItem size="large" title="存档列表" icon="🗂️">
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div
          v-for="(r, i) in store.records"
          :key="r.id"
          class="relative flex gap-4 rounded-xl border bg-white/5 p-4"
          :class="store.session.active && i === 0 ? 'border-[#79d9ff]' : 'border-white/10'"
        >
          <span
            v-if="store.session.active && i === 0"
            class="absolute left-3 top-3 rounded-full bg-[#79d9ff]/20 px-2 py-0.5 text-[10px] font-bold text-[#79d9ff]"
          >
            当前
          </span>

          <div class="flex h-28 w-24 shrink-0 items-center justify-center rounded-lg bg-slate-900/70 text-3xl font-black text-white/40">
            {{ r.jobName.slice(0, 1) }}
          </div>

          <div class="flex min-w-0 flex-1 flex-col">
            <div class="font-mono text-[10px] tracking-wide text-white/40">
              No.{{ i + 1 }} · {{ formatDate(r.date) }}
            </div>

            <input
              v-if="editingId === r.id"
              v-model="draft"
              autofocus
              class="glass-input mt-1 text-lg font-bold"
              @blur="commitRename(r)"
              @keyup.enter="commitRename(r)"
            />
            <h5
              v-else
              class="mt-1 truncate text-lg font-bold text-white"
              :title="r.jobName"
              @dblclick="startRename(r)"
            >
              {{ r.jobName }}
            </h5>

            <div class="my-2 border-b border-dashed border-white/20"></div>

            <p class="line-clamp-2 text-xs italic text-white/50">{{ excerpt(r) }}</p>

            <div class="mt-auto flex flex-wrap gap-2 pt-3">
              <button
                class="cursor-pointer rounded-full border border-[#79d9ff]/40 bg-[#79d9ff]/15 px-3 py-1 text-xs font-bold text-[#79d9ff] transition-colors hover:bg-[#79d9ff]/25"
                @click="router.push('/interview')"
              >
                读取
              </button>
              <button
                class="cursor-pointer rounded-full border border-emerald-400/40 bg-emerald-500/15 px-3 py-1 text-xs font-bold text-emerald-300 transition-colors hover:bg-emerald-500/25"
                @click="overwrite(r)"
              >
                覆盖
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
      </div>

      <p v-if="!store.records.length" class="py-6 text-center text-sm text-white/40">还没有存档，先创建一份吧。</p>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import MenuItem from "../MenuItem.vue";
import { confirmDialog } from "../../../store/dialogStore";
import { currentJob, currentPersona, notify, persist, store } from "../../../store/appStore";
import type { InterviewRecord } from "../../../data/mock";

const router = useRouter();

const nameDraft = ref("");
const editingId = ref("");
const draft = ref("");

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

function formatDate(raw: string): string {
  return raw.replace(/-/g, ".");
}

function excerpt(r: InterviewRecord): string {
  const last = r.questions.length ? r.questions[r.questions.length - 1] : null;
  return last ? last.question : "暂无对话记录";
}

function createSave() {
  const name = nameDraft.value.trim();
  if (!name) {
    notify("请先给存档起个名字");
    return;
  }
  const d = new Date();
  store.records.unshift({
    id: `r-${Date.now()}`,
    jobName: name,
    personaName: `${currentPersona.value.name} · ${currentPersona.value.title}`,
    date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`,
    duration: "00 分 00 秒",
    score: 0,
    dimensions: [],
    questions: [],
    advice: ["空存档：完成面试后会自动写入评分与分析。"],
  });
  persist();
  nameDraft.value = "";
  notify(`存档「${name}」已创建`);
}

function startRename(r: InterviewRecord) {
  editingId.value = r.id;
  draft.value = r.jobName;
}

function commitRename(r: InterviewRecord) {
  if (editingId.value !== r.id) return;
  const name = draft.value.trim();
  editingId.value = "";
  if (!name || name === r.jobName) return;
  r.jobName = name;
  persist();
  notify("存档已重命名");
}

async function overwrite(r: InterviewRecord) {
  const ok = await confirmDialog("覆盖存档", `确定用当前面试进度覆盖「${r.jobName}」吗？`);
  if (ok) notify(`已覆盖存档「${r.jobName}」`);
}

async function remove(r: InterviewRecord) {
  const ok = await confirmDialog("删除存档", `确定删除「${r.jobName}」吗？删除后不可恢复。`);
  if (!ok) return;
  const idx = store.records.findIndex((x) => x.id === r.id);
  if (idx >= 0) {
    store.records.splice(idx, 1);
    persist();
    notify("存档已删除");
  }
}
</script>
