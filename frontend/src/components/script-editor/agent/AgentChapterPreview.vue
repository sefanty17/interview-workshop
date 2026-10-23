<script setup lang="ts">
/** 章节预览浮窗：只读展示某剧本已落盘的章节与事件时间线（章节列表来自 `Chapters/` 扫描）。
 * 刻意不碰 store.chapter —— 那是编辑器正在编辑的章节，带自动保存防抖，误用会写盘。 */
import { computed, ref, watch } from "vue";
import { Icon } from "../../base";
import { MenuItem } from "../../ui";
import LcToggle from "../../ui/LcToggle.vue";
import {
  openChapter as editorOpenChapter,
  openScript as editorOpenScript,
  scriptKey as editorScriptKey,
  startPreview,
} from "../../../store/scriptEditor";
import { readChapter, readScript } from "../../../api/editor";
import type { ChapterSummary, ScriptDetail, ScriptEventData } from "../../../api/editor";
import { listArtifacts } from "../../../api/agent";
import type { AgentArtifact } from "../../../api/agent";
import ChapterTimeline from "../flow/ChapterTimeline.vue";

/**
 * 原版 `agent/AgentChapterPreview.vue`（350 行）逐字改写。
 *
 * 技术栈差异（规格 E7b2 §2.1）：
 * - 无 vue-i18n：文案换成 zh-CN 原文（`agentScriptPreview` / `flowTab` / `chapterFlow` 节点）。
 * - 无 Tauri：`readScript` / `readChapter` 走 `api/editor.ts`；产物走 `api/agent.ts`
 *   的 `listArtifacts` —— 注意后端这个端点的入参是**会话 id**（原版传 scriptKey），
 *   所以多了一个 `conversationId` prop。
 * - 无 Pinia：编辑器状态从 `store/scriptEditor.ts` 取。
 */
const props = defineProps<{
  open: boolean;
  scriptKey: string | null;
  /** 产物端点要的会话 id（null = 没有会话，产物为空）。 */
  conversationId: number | null;
}>();
const emit = defineEmits<{ close: [] }>();

const detail = ref<ScriptDetail | null>(null);
const error = ref("");
const loading = ref(false);
const currentId = ref("");
const events = ref<ScriptEventData[]>([]);
const opening = ref(false);
const artifacts = ref<AgentArtifact[]>([]);
const artifactName = ref("");
const foldCompounds = ref(true);

const chapters = computed<ChapterSummary[]>(() => detail.value?.chapters ?? []);

const title = computed(() => {
  const pkg = detail.value?.package;
  return pkg?.scriptName?.trim() || pkg?.folderName || props.scriptKey || "";
});

const currentName = computed(
  () => chapters.value.find((c) => c.id === currentId.value)?.name || currentId.value,
);

const roleNameMap = computed(
  () => new Map((detail.value?.characters ?? []).map((c) => [c.roleKey, c.aiName])),
);

const artifactContent = computed(
  () => artifacts.value.find((a) => a.name === artifactName.value)?.content ?? "",
);

/** MAIN 展示名：绑定角色优先，其次剧本里的玩家名（与编辑器 getter 同口径）。 */
const mainRoleName = computed(() => {
  const d = detail.value;
  if (!d) return "";
  const bound = d.package.boundCharacterFolder;
  if (bound) {
    const c = d.characters.find((x) => x.folder === bound);
    if (c?.aiName) return c.aiName;
  }
  const settings = d.storyConfig?.script_settings;
  const userName = settings?.user_name;
  if (typeof userName === "string" && userName.trim()) return userName.trim();
  return "MAIN";
});

async function openChapter(id: string) {
  const key = props.scriptKey;
  if (!key || opening.value || id === currentId.value) return;
  opening.value = true;
  try {
    const content = await readChapter(key, id);
    events.value = content.events;
    currentId.value = id;
    artifactName.value = "";
  } catch (e) {
    error.value = String(e);
  } finally {
    opening.value = false;
  }
}

async function load() {
  detail.value = null;
  error.value = "";
  events.value = [];
  currentId.value = "";
  artifacts.value = [];
  artifactName.value = "";
  const key = props.scriptKey;
  if (!key) return;
  loading.value = true;
  try {
    // 产物读取失败不该挡住看章节：它是附加信息
    const convId = props.conversationId;
    const [script, files] = await Promise.all([
      readScript(key),
      convId == null
        ? Promise.resolve([] as AgentArtifact[])
        : listArtifacts(convId).catch(() => [] as AgentArtifact[]),
    ]);
    detail.value = script;
    artifacts.value = files;
    const first = chapters.value[0];
    if (first) await openChapter(first.id);
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) void load();
  },
  { immediate: true },
);

/** openScript 自己会先落盘未保存的改动 */
async function focusScript() {
  const key = props.scriptKey;
  if (!key) return false;
  // 本项目 store 的 scriptKey 是 getter（detail?.package.key）
  if (editorScriptKey.value !== key) await editorOpenScript(key);
  return editorScriptKey.value === key;
}

async function jumpToEditor() {
  const id = currentId.value;
  if (!id) return;
  emit("close");
  if (await focusScript()) await editorOpenChapter(id);
}

async function previewFromChapter() {
  const id = currentId.value;
  if (!id) return;
  emit("close");
  if (await focusScript()) startPreview(id);
}
</script>

<template>
  <!-- Teleport 到 #app：浮层若挂在 body 下会脱离整体缩放作用域（同 ChapterTimeline 注释） -->
  <Teleport to="#app">
    <Transition
      enter-active-class="transition-opacity duration-200 ease"
      leave-active-class="transition-opacity duration-200 ease"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="modal-mask fixed inset-0 z-[9999] flex items-center justify-center bg-black/30 p-4 backdrop-blur-md"
        @click.self="emit('close')"
      >
        <div
          class="flex h-[min(82dvh,820px)] w-[min(1120px,94vw)] flex-col overflow-hidden rounded-xl border border-white/12.5 bg-white/10 shadow-[0_8px_32px_rgba(0,0,0,0.45),inset_0_1px_1px_rgba(255,255,255,0.06)] backdrop-blur-lg backdrop-saturate-[1.4]"
        >
          <div class="border-brand flex shrink-0 items-center gap-2 border-b-2 px-4.5 pt-3.5 pb-2">
            <h4 class="truncate font-semibold text-white">
              {{ scriptKey ? `剧本详情 · ${title}` : "剧本详情" }}
            </h4>
            <span v-if="scriptKey" class="text-brand/70 shrink-0 font-mono text-[0.66rem]"
              >📕 {{ scriptKey }}</span
            >
            <span
              class="ml-auto shrink-0 rounded-full border border-white/10 bg-white/5 px-2 py-px text-[0.64rem] text-white/45"
            >
              只读
            </span>
            <button
              class="hover:text-brand shrink-0 cursor-pointer px-1 text-white/50 transition-all duration-300 hover:rotate-90"
              @click="emit('close')"
            >
              ✕
            </button>
          </div>

          <div v-if="!scriptKey" class="px-4 py-8 text-center text-[0.82rem] text-white/50">
            这个会话还没有产出剧本包，没有可预览的章节。
          </div>
          <div v-else-if="loading" class="px-4 py-6 text-[0.8rem] text-white/45">加载中…</div>
          <div v-else-if="error" class="px-4 py-6 text-[0.8rem] text-red-300">
            读取剧本失败：{{ error }}
          </div>
          <div v-else class="flex min-h-0 flex-1 gap-4 px-4 py-3.5">
            <div class="flex min-w-0 flex-1 flex-col">
              <MenuItem
                :title="artifactName ? '流程产物' : '事件时间线'"
                class="fill flex h-full min-h-0 flex-col"
              >
                <template #header>
                  <Icon icon="text" :size="20" />
                </template>
                <div class="mb-2 flex items-center gap-2">
                  <span class="min-w-0 flex-1 truncate text-sm text-white/85">{{
                    artifactName || currentName
                  }}</span>
                  <template v-if="artifactName">
                    <span class="shrink-0 text-xs text-white/40"> 只读；要改就在对话里说 </span>
                  </template>
                  <template v-else>
                    <label
                      class="inline-flex items-center gap-2 text-[0.8rem] whitespace-nowrap text-white/70"
                    >
                      <LcToggle v-model="foldCompounds" />
                      合并转场等固定组合
                    </label>
                    <span class="shrink-0 text-xs text-white/40">
                      {{ `${events.length} 个事件` }}
                    </span>
                    <button
                      class="inline-flex shrink-0 items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-2.5 py-[0.25rem] text-[0.76rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:bg-white/[0.12] hover:text-white disabled:cursor-not-allowed disabled:opacity-40"
                      title="跳到剧本编辑器里这一章"
                      :disabled="!currentId"
                      @click="jumpToEditor"
                    >
                      跳转
                    </button>
                    <button
                      class="border-brand/45 bg-brand/14 text-brand hover:bg-brand/24 inline-flex shrink-0 items-center gap-1 rounded-lg border px-2.5 py-[0.25rem] text-[0.76rem] whitespace-nowrap transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-40"
                      title="从这一章开始运行剧本"
                      :disabled="!currentId"
                      @click="previewFromChapter"
                    >
                      从本章试玩
                    </button>
                  </template>
                </div>
                <div class="min-h-0 flex-1 overflow-y-auto pr-1">
                  <pre
                    v-if="artifactName"
                    class="font-mono text-[0.74rem] leading-relaxed whitespace-pre-wrap text-white/75"
                    >{{ artifactContent }}</pre
                  >
                  <ChapterTimeline
                    v-else-if="events.length"
                    readonly
                    :events="events"
                    :fold-compounds="foldCompounds"
                    :role-name-map="roleNameMap"
                    :main-role-name="mainRoleName"
                  />
                  <p v-else class="text-[0.8rem] text-white/45">这一章还没有事件</p>
                </div>
              </MenuItem>
            </div>

            <div class="flex min-h-0 w-[236px] shrink-0 flex-col gap-3">
              <MenuItem
                :title="`章节（${chapters.length}）`"
                class="fill flex min-h-0 flex-1 flex-col"
              >
                <template #header>
                  <Icon icon="edit" :size="20" />
                </template>
                <div class="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto pr-1">
                  <button
                    v-for="c in chapters"
                    :key="c.id"
                    class="shrink-0 rounded-lg border px-2.5 py-2 text-left transition-all duration-150"
                    :class="
                      c.id === currentId && !artifactName
                        ? 'border-brand/60 bg-brand/12'
                        : 'hover:border-brand/40 border-white/10 bg-white/5 hover:bg-white/10'
                    "
                    @click="openChapter(c.id)"
                  >
                    <div class="flex items-center gap-1.5">
                      <span
                        class="border-brand/40 text-brand shrink-0 rounded border px-[5px] py-px font-mono text-[0.66rem]"
                        >{{ c.id }}</span
                      >
                      <span class="min-w-0 flex-1 truncate text-[0.76rem] text-white/80">{{
                        c.name || c.id
                      }}</span>
                    </div>
                    <div class="mt-0.5 text-[0.64rem] text-white/35">
                      {{ `${c.eventCount} 事件` }}
                    </div>
                  </button>
                  <p v-if="!chapters.length" class="px-1 text-[0.74rem] text-white/40">
                    还没有已写完的章节
                  </p>
                </div>
              </MenuItem>

              <!-- 流程产物（.agent/）：设计稿、任务队列、用户约束……只读，改就在对话里说 -->
              <!-- 每行 shrink-0：不给 flex 会把几行一起压扁，要的是"一行一个、放不下就滚动" -->
              <MenuItem
                :title="`流程产物（${artifacts.length}）`"
                class="fill flex max-h-[46%] min-h-[132px] shrink-0 flex-col"
              >
                <template #header>
                  <Icon icon="log" :size="20" />
                </template>
                <div class="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto pr-1">
                  <button
                    v-for="a in artifacts"
                    :key="a.name"
                    class="shrink-0 truncate rounded-lg border px-2.5 py-[7px] text-left font-mono text-[0.72rem] leading-normal transition-all duration-150"
                    :class="
                      a.name === artifactName
                        ? 'border-brand/60 bg-brand/12 text-brand'
                        : 'hover:border-brand/40 border-white/10 bg-white/5 text-white/75 hover:bg-white/10'
                    "
                    @click="artifactName = a.name"
                  >
                    {{ a.name }}
                  </button>
                  <p v-if="!artifacts.length" class="px-1 text-[0.72rem] text-white/40">
                    还没有流程产物
                  </p>
                </div>
              </MenuItem>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* MenuItem 的 .content 默认只有 width:100%，在 .fill（flex 列）里不会收缩 */
.fill :deep(.content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* MenuItem 表面默认 rgba(255,255,255,0.1) 铺在弹层底上偏暗，这里提到 0.16 与「章节流程」卡片亮度接近 */
:deep(.menu-item) {
  background: rgba(255, 255, 255, 0.16);
}
</style>
