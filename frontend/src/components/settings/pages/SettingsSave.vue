<template>
  <MenuPage>
    <MenuItem title="创建新存档（会记录当前对话）">
      <template #header>
        <PencilLine :size="20" />
      </template>
      <div class="flex gap-2.5">
        <input
          v-model="newSaveTitle"
          class="save-input"
          type="text"
          placeholder="输入存档名称"
          @keyup.enter="handleCreateSave"
        />
        <button class="save-create-btn" :disabled="actionLoading !== null" @click="handleCreateSave">
          {{ actionLoading === -1 ? "创建中..." : "创建" }}
        </button>
      </div>
      <div class="mt-3 space-y-1">
        <p class="text-xs text-white/40">本局身份</p>
        <p class="text-sm font-semibold text-white">{{ identityName }}</p>
        <p class="text-xs leading-relaxed text-white/35">
          存档会记录本局身份与面试进度，读档时一并恢复。
        </p>
      </div>
    </MenuItem>

    <MenuItem title="存档列表">
      <template #header>
        <LayoutList :size="20" />
      </template>
      <div class="flex flex-col">
        <div class="h-[calc(100dvh-24rem)] min-h-75 overflow-y-auto pr-1 pb-4">
          <div v-if="saves.length === 0" class="p-8 text-center text-[#888]">暂无存档记录</div>

          <div v-else class="grid grid-cols-1 gap-5 p-1.25 md:grid-cols-2">
            <div
              v-for="(save, index) in saves"
              :key="save.id"
              class="flex flex-col rounded-2xl border p-4 backdrop-blur shadow-[0_8px_32px_rgba(0,0,0,0.2)] transition-all duration-300 ease-[cubic-bezier(0.25,0.8,0.25,1)] hover:-translate-y-0.75 hover:bg-white/15"
              :class="
                isCurrentSave(save.id)
                  ? 'border-pink-400/45 bg-white/10 hover:border-pink-400/60 hover:shadow-[0_12px_40px_rgba(236,72,153,0.12)]'
                  : 'border-white/20 bg-white/10 hover:border-[rgba(121,217,255,0.35)] hover:shadow-[0_12px_40px_rgba(121,217,255,0.08)]'
              "
            >
              <div class="flex gap-4">
                <!-- 左：缩略图（本项目无真截图，按 id 取色相的渐变占位） -->
                <div
                  class="relative h-48 w-1/2 shrink-0 overflow-hidden rounded-lg border border-white/10 bg-black/40"
                >
                  <span
                    v-if="isCurrentSave(save.id)"
                    class="absolute top-1.5 left-1.5 z-10 rounded-full bg-pink-500/80 px-2 py-0.5 text-[10px] font-bold text-white shadow-[0_2px_8px_rgba(0,0,0,0.4)]"
                  >
                    当前存档
                  </span>
                  <div
                    class="flex h-full w-full flex-col items-center justify-center"
                    :style="{ background: thumbGradient(save.id) }"
                  >
                    <Save :size="24" class="mb-1 text-white/45" />
                    <span class="text-[10px] font-semibold text-white/60">{{ save.jobName }}</span>
                  </div>
                </div>

                <!-- 右：存档信息 -->
                <div class="flex min-w-0 flex-1 flex-col overflow-hidden">
                  <div class="flex items-center justify-between font-mono text-xs text-white/40">
                    <span class="font-bold">No.{{ index + 1 }}</span>
                    <span class="flex items-center gap-1">
                      <Clock :size="10" />
                      {{ formatDate(save.date) }}
                    </span>
                  </div>

                  <div class="mt-1.5 flex min-h-6.5 items-center">
                    <input
                      v-if="editingSaveId === save.id"
                      v-model="editTitleText"
                      v-focus
                      class="w-full rounded border border-[rgba(121,217,255,0.5)] bg-black/50 px-1.5 py-0.5 text-sm font-bold text-white outline-none"
                      @blur="handleSaveTitle(save.id)"
                      @keyup.enter="handleSaveTitle(save.id)"
                    />
                    <div
                      v-else
                      class="max-w-full cursor-pointer truncate text-sm font-bold text-white transition-colors duration-200 select-none hover:text-sky-300"
                      title="双击以修改存档标题"
                      @dblclick="startEditTitle(save)"
                    >
                      {{ saveTitle(save) }}
                    </div>
                  </div>

                  <div class="my-2 w-full border-b border-dashed border-white/15"></div>

                  <div
                    class="line-clamp-2 h-8.25 text-xs leading-[1.4] text-white/65 italic"
                    :title="saveSummary(save)"
                  >
                    {{ saveSummary(save) || "暂无对话台词记录" }}
                  </div>

                  <div class="mt-1 truncate text-[11px] text-white/35">身份：{{ identityName }}</div>
                </div>
              </div>

              <!-- 底部按钮 -->
              <div class="mt-4 flex gap-2 border-t border-white/5 pt-3">
                <button
                  class="flex-1 cursor-pointer rounded-md border border-blue-500/40 bg-blue-500/25 px-3 py-1.5 text-xs font-semibold whitespace-nowrap text-white transition-all duration-200 hover:bg-blue-500/45 hover:shadow-[0_0_10px_rgba(59,130,246,0.2)] disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="actionLoading !== null"
                  @click="handleLoadSave(save.id)"
                >
                  {{ actionLoading === save.id ? "读取中..." : "读取存档" }}
                </button>
                <button
                  class="flex-1 cursor-pointer rounded-md border px-3 py-1.5 text-xs font-semibold whitespace-nowrap text-white transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-50"
                  :class="
                    isCurrentSave(save.id)
                      ? 'border-pink-500/40 bg-pink-500/25 hover:bg-pink-500/45 hover:shadow-[0_0_10px_rgba(236,72,153,0.2)]'
                      : 'border-emerald-500/40 bg-emerald-500/25 hover:bg-emerald-500/45 hover:shadow-[0_0_10px_rgba(16,185,129,0.2)]'
                  "
                  :disabled="actionLoading !== null"
                  @click="handleSaveGame(save.id)"
                >
                  {{
                    actionLoading === save.id
                      ? "保存中..."
                      : isCurrentSave(save.id)
                        ? "更新存档"
                        : "覆盖存档"
                  }}
                </button>
                <button
                  class="flex-1 cursor-pointer rounded-md border border-red-500/40 bg-red-500/25 px-3 py-1.5 text-xs font-semibold whitespace-nowrap text-white transition-all duration-200 hover:bg-red-500/45 hover:shadow-[0_0_10px_rgba(239,68,68,0.2)] disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="actionLoading !== null"
                  @click="handleDeleteSave(save.id)"
                >
                  {{ actionLoading === save.id ? "删除中..." : "删除存档" }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { Clock, LayoutList, PencilLine, Save } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { addRecord, currentJob, currentPersona, notify, persist, store } from "../../../store/appStore";
import { confirmDialog } from "../../../store/dialogStore";
import type { InterviewRecord } from "../../../data/mock";

/** 本项目在面试记录上附加的两个字段：存档名、当前对话快照（不改 data/mock.ts 的类型） */
interface SaveTranscriptLine {
  role: "ai" | "user";
  text: string;
  time: string;
}
type SaveRecord = InterviewRecord & {
  saveTitle?: string;
  transcript?: SaveTranscriptLine[];
};

const router = useRouter();

const saves = computed(() => store.records as SaveRecord[]);

const newSaveTitle = ref("");
/** -1 = 正在创建；其余为正在执行动作的存档 id */
const actionLoading = ref<string | number | null>(null);

/** 本局当前存档：原版取 gameStore.currentSaveId，这里用 localStorage 记住，刷新后仍在 */
const CURRENT_KEY = "interview-lab-current-save";
const currentSaveId = ref<string | null>(readCurrent());

function readCurrent(): string | null {
  try {
    return localStorage.getItem(CURRENT_KEY);
  } catch {
    return null;
  }
}

function setCurrentSave(id: string | null): void {
  currentSaveId.value = id;
  try {
    if (id) localStorage.setItem(CURRENT_KEY, id);
    else localStorage.removeItem(CURRENT_KEY);
  } catch {
    /* 隐私模式下 localStorage 不可写，仅本会话生效 */
  }
}

const isCurrentSave = (id: string) => currentSaveId.value === id;

/** 本局身份：原版从当前角色卡取，这里固定是求职者本人 */
const identityName = "求职者";

const saveTitle = (save: SaveRecord) => save.saveTitle || `${save.jobName} 面试`;
const saveSummary = (save: SaveRecord) => save.advice[0] ?? "";

function thumbGradient(id: string): string {
  let hash = 0;
  for (let i = 0; i < id.length; i += 1) hash = (hash * 31 + id.charCodeAt(i)) % 360;
  // 乘黄金角，避免相邻 id（r-1018 / r-1024）挤在同一片绿里
  const hue = Math.round(hash * 137.508) % 360;
  return `linear-gradient(135deg, hsl(${hue} 62% 38%) 0%, hsl(${(hue + 45) % 360} 58% 18%) 100%)`;
}

const pad2 = (n: number) => String(n).padStart(2, "0");

function formatDate(value: string): string {
  // record.date 形如 "2026-10-18 20:14"，用斜杠换掉横杠保证各浏览器都能解析
  const date = new Date(value.replace(/-/g, "/"));
  if (Number.isNaN(date.getTime())) return value;
  return `${date.getFullYear()}.${pad2(date.getMonth() + 1)}.${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

function nowDate(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m} 分 ${s} 秒`;
}

/** 当前面试的对话快照，创建/覆盖存档时一起写进记录 */
function snapshotTranscript(): SaveTranscriptLine[] {
  return store.session.messages.map((m) => ({ role: m.role, text: m.text, time: m.time }));
}

// ---------- 标题重命名（双击） ----------
const editingSaveId = ref<string | null>(null);
const editTitleText = ref("");

const vFocus = {
  mounted: (el: HTMLInputElement) => el.focus(),
};

function startEditTitle(save: SaveRecord): void {
  editingSaveId.value = save.id;
  editTitleText.value = saveTitle(save);
}

function handleSaveTitle(saveId: string): void {
  const title = editTitleText.value.trim();
  const save = saves.value.find((s) => s.id === saveId);
  if (!title) {
    notify("存档名称不能为空");
    editingSaveId.value = null;
    return;
  }
  if (save && saveTitle(save) !== title) {
    save.saveTitle = title;
    persist();
    notify("存档名称已修改");
  }
  editingSaveId.value = null;
}

// ---------- 创建 ----------
function handleCreateSave(): void {
  const title = newSaveTitle.value.trim();
  if (!title) {
    notify("请输入存档名称");
    return;
  }
  actionLoading.value = -1;
  const transcript = snapshotTranscript();
  const record: SaveRecord = {
    id: `r-${Date.now()}`,
    jobName: currentJob.value.name,
    personaName: `${currentPersona.value.name} · ${currentPersona.value.title}`,
    date: nowDate(),
    duration: formatDuration(store.session.elapsed),
    score: 0,
    dimensions: [],
    questions: [],
    advice: [
      transcript.length
        ? `存档时已记录 ${transcript.length} 条对话，共 ${store.session.messages.length} 轮记录`
        : "本局还没有开始对话，存档仅保留身份与岗位设置",
    ],
    saveTitle: title,
    transcript,
  };
  addRecord(record);
  setCurrentSave(record.id);
  newSaveTitle.value = "";
  notify("存档已创建");
  actionLoading.value = null;
}

// ---------- 读取 ----------
async function handleLoadSave(saveId: string): Promise<void> {
  const ok = await confirmDialog("读取存档", "加载存档会导致丢失当前对话进度，确定要加载吗？");
  if (!ok) return;
  actionLoading.value = saveId;
  setCurrentSave(saveId);
  actionLoading.value = null;
  notify("存档已加载");
  router.push(`/report/${saveId}`);
}

// ---------- 覆盖 / 更新 ----------
async function handleSaveGame(saveId: string): Promise<void> {
  const isCurrent = isCurrentSave(saveId);
  if (!isCurrent) {
    const ok = await confirmDialog("覆盖存档", "覆盖存档会导致丢失之前的存档进度，确定要覆盖吗？");
    if (!ok) return;
  }
  const save = saves.value.find((s) => s.id === saveId);
  if (!save) return;
  actionLoading.value = saveId;
  save.date = nowDate();
  save.duration = formatDuration(store.session.elapsed);
  const transcript = snapshotTranscript();
  if (transcript.length) {
    save.transcript = transcript;
    save.advice = [`存档时已记录 ${transcript.length} 条对话，共 ${store.session.messages.length} 轮记录`];
  }
  persist();
  setCurrentSave(saveId);
  notify(isCurrent ? "存档已更新" : "存档已覆盖");
  actionLoading.value = null;
}

// ---------- 删除 ----------
async function handleDeleteSave(saveId: string): Promise<void> {
  const ok = await confirmDialog("删除存档", "确定要删除这个存档吗？此操作不可撤销。");
  if (!ok) return;
  actionLoading.value = saveId;
  const idx = store.records.findIndex((r) => r.id === saveId);
  if (idx >= 0) store.records.splice(idx, 1);
  if (isCurrentSave(saveId)) setCurrentSave(null);
  persist();
  notify("存档已删除");
  actionLoading.value = null;
}
</script>

<style scoped>
/* 原版 base/widget/Input.vue 的玻璃输入框（本项目 base 里没有 Input 组件） */
.save-input {
  width: 100%;
  padding: 10px 12px;
  color: #fff;
  font-family: inherit;
  font-size: 15px;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.125);
  border-radius: 8px;
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.1),
    inset 0 1px 1px rgba(255, 255, 255, 0.1);
  transition: border-color 0.2s;
  outline: none;
}

.save-input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}

.save-input:focus {
  border-color: rgba(121, 217, 255, 0.5);
}

/* 原版创建按钮：绿色描边 + 绿色半透明底，hover 上浮并加辉光 */
.save-create-btn {
  width: 10%;
  min-width: 4.0625rem;
  flex: none;
  cursor: pointer;
  padding: 8px 16px;
  white-space: nowrap;
  color: #ddd;
  background: rgba(0, 255, 55, 0.2);
  border: 1px solid rgba(0, 255, 55, 0.3);
  border-radius: 6px;
  transition: all 0.2s;
}

.save-create-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  background: rgba(0, 255, 55, 0.35);
  box-shadow: 0 0 10px rgba(0, 255, 55, 0.15);
}

.save-create-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
