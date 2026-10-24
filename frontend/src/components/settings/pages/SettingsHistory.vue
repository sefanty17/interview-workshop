<template>
  <div class="settings-history-page">
    <MenuPage>
      <MenuItem title="历史对话">
        <template #header>
          <History :size="20" />
        </template>
        <div class="flex h-full max-h-[75dvh] min-h-0 flex-col">
          <div v-if="loading" class="flex flex-1 items-center justify-center">
            <div class="py-8 text-center text-base text-white/50">加载中...</div>
          </div>

          <div v-else-if="loadError" class="flex flex-1 items-center justify-center">
            <div class="py-8 text-center text-base text-white/50">加载失败，请稍后重试</div>
          </div>

          <div v-else-if="dialogHistory.length === 0" class="flex flex-1 items-center justify-center">
            <div
              class="py-8 text-center text-2xl font-bold text-gray-100 [text-shadow:0_0_5px_rgba(255,255,255,0.5)]"
            >
              暂无历史记录，去和面试官聊聊天叭(*^▽^*)
            </div>
          </div>

          <div v-else class="flex min-h-0 flex-1 flex-col">
            <div
              ref="contentRef"
              class="scroll-brand scrollbar-thin min-h-0 flex-1 overflow-y-auto scroll-smooth px-1.5 py-3.5"
              style="line-height: 1.9; font-size: 18px"
            >
              <template v-for="(item, i) in groupedHistory" :key="i">
                <div
                  class="py-1"
                  :class="{ 'mt-0 border-t border-white/10 pt-3': !item.isNarration && i > 0 }"
                >
                  <div v-if="!item.isNarration" class="mb-1 flex items-center justify-between">
                    <span class="text-[17px] font-semibold text-[#79d9ff]">
                      {{ item.displayName }}
                    </span>
                    <!-- TODO(批次8)：回溯按钮，对齐上游 handleBacktrack / rollback_conversation。
                         数据位已就绪（userMessageSeq），等后端截断接口与会话级生成锁做完再挂上。 -->
                  </div>
                  <div v-if="item.thinking" class="mb-1">
                    <button
                      class="inline-flex cursor-pointer items-center gap-1 rounded-full border border-[rgba(121,217,255,0.25)] bg-[rgba(121,217,255,0.08)] px-2.5 py-0.5 text-xs text-[#a8d8f0]/70 transition-all duration-200 hover:border-[rgba(121,217,255,0.5)] hover:text-[#c9e7ff]"
                      @click.stop="toggleThinking(i)"
                    >
                      <span>{{ isThinkingExpanded(i) ? "▼" : "▶" }}</span>
                      <span>思考过程 ({{ item.thinking.length }})</span>
                    </button>
                    <div
                      v-if="isThinkingExpanded(i)"
                      class="scrollbar-thin mt-1.5 max-h-64 overflow-y-auto rounded-2xl border border-[rgba(121,217,255,0.15)] bg-[rgba(121,217,255,0.05)] px-4 py-3 text-[15px] leading-normal whitespace-pre-wrap text-white/55"
                    >
                      {{ item.thinking }}
                    </div>
                  </div>
                  <template v-for="(entry, j) in item.lines" :key="j">
                    <div
                      v-for="(seg, k) in entry.segments"
                      :key="k"
                      class="flex items-start gap-1.5 py-0.5 wrap-break-word whitespace-pre-wrap"
                      :class="{
                        'text-[#c8d0dc] italic': seg.type === 'action',
                        'text-[#b8c0cc] italic': item.isNarration && seg.type !== 'action',
                        'text-[#e8e8e8]': seg.type !== 'action' && !item.isNarration,
                      }"
                      style="font-size: 18px; line-height: 1.9"
                    >
                      <span v-if="seg.type === 'action'" class="text-[#c8d0dc]">{{ seg.text }}</span>
                      <span v-else-if="item.isNarration">{{ seg.text }}</span>
                      <span v-else>{{ "「" + seg.text + "」" }}</span>
                    </div>
                  </template>
                </div>
              </template>
            </div>

            <div
              v-if="totalPages > 1"
              class="mt-auto flex w-full shrink-0 items-center justify-between px-3 py-2"
            >
              <button
                class="cursor-pointer rounded-lg border-0 bg-[#e9ecef] px-4 py-1.5 text-sm font-medium text-[#495057] transition-all duration-200 hover:not-disabled:-translate-y-0.5 hover:not-disabled:bg-[rgba(121,217,255,1)] hover:not-disabled:text-white hover:not-disabled:shadow-[0_4px_10px_rgba(121,217,255,0.4)] disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="currentPage === 1"
                @click="currentPage--"
              >
                上一页
              </button>
              <span class="text-base font-medium text-gray-100">
                第 {{ currentPage }} / {{ totalPages }} 页
              </span>
              <button
                class="cursor-pointer rounded-lg border-0 bg-[#e9ecef] px-4 py-1.5 text-sm font-medium text-[#495057] transition-all duration-200 hover:not-disabled:-translate-y-0.5 hover:not-disabled:bg-[rgba(121,217,255,1)] hover:not-disabled:text-white hover:not-disabled:shadow-[0_4px_10px_rgba(121,217,255,0.4)] disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="currentPage >= totalPages"
                @click="currentPage++"
              >
                下一页
              </button>
            </div>
          </div>
        </div>
      </MenuItem>
    </MenuPage>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { History } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { fetchMessages, fetchSessions, type SessionMessage } from "../../../api/chat";

/**
 * 对话历史页（对齐上游 `SettingsHistory.vue`，基准 5a778fab）。
 *
 * <p>形态是「一条连续对话流」而不是记录卡片：同一人连续说话合并成块，
 * 每句拆正文段 / （动作）段，旁白不冠名且整段斜体，有思考链的块带折叠胶囊，
 * 每 100 条一页，打开跳最后一页并滚到底。
 *
 * <p>上游还有两块本批没做：① 语音播放 / 补生成（依赖 TTS，属课设指标 #10）；
 * ② 回溯（依赖后端截断接口 + 会话级生成锁，属批次 8）。数据位 `userMessageSeq`
 * 已就绪，接口挂上即可。
 */

interface Segment {
  type: "dialogue" | "action";
  text: string;
}

interface LineEntry {
  segments: Segment[];
  /** TODO(批次8)：回溯定位用 */
  userMessageSeq?: number;
  thinking?: string;
}

interface HistoryBlock {
  displayName: string;
  isNarration: boolean;
  lines: LineEntry[];
  userMessageSeq?: number;
  /** 该块（一轮生成）的思考链，取块内最后一条非空值 */
  thinking?: string;
}

const contentRef = ref<HTMLDivElement | null>(null);

/** 补生成语音写回时抑制自动滚动（上游 generateVoice 用）。本项目暂无语音，保留语义位。 */
let suppressAutoScroll = false;

const loading = ref(true);
const loadError = ref(false);
const dialogHistory = ref<SessionMessage[]>([]);

/** 旁白 / 系统：不冠名、不加书名号、整段斜体 */
const narrationNames = new Set(["", "旁白", "系统", "Narrator", "System"]);
/** 全角括号动作标记，prompt 里定死的格式，不许改成半角 */
const ACTION_RE = /（[^）]*）/;

/** 每页显示的台词数量 */
const PAGE_SIZE = 100;

/** 当前页码 */
const currentPage = ref(1);

/** 思考过程展开状态（key: 对话块索引），默认全部折叠 */
const expandedThinking = ref<Set<number>>(new Set());

function isThinkingExpanded(blockIdx: number): boolean {
  return expandedThinking.value.has(blockIdx);
}

function toggleThinking(blockIdx: number): void {
  const next = new Set(expandedThinking.value);
  if (next.has(blockIdx)) {
    next.delete(blockIdx);
  } else {
    next.add(blockIdx);
  }
  expandedThinking.value = next;
}

const totalPages = computed(() => Math.ceil(dialogHistory.value.length / PAGE_SIZE));

const currentPageHistory = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE;
  const end = start + PAGE_SIZE;
  return dialogHistory.value.slice(start, end);
});

const groupedHistory = computed<HistoryBlock[]>(() => {
  const blocks: HistoryBlock[] = [];

  for (const msg of currentPageHistory.value) {
    if (!msg.content || msg.content.trim() === "") continue;

    const isNarration = narrationNames.has(msg.displayName || "");

    const name = isNarration
      ? ""
      : msg.displayName || (msg.role === "user" ? "我" : "未知的声音");

    // 用户输入不做（…）动作切分，原样显示
    const segments =
      msg.role === "user"
        ? [{ type: "dialogue" as const, text: msg.content }]
        : parseSegments(msg.content, msg.action ?? undefined, isNarration);

    const entry: LineEntry = {
      segments,
      userMessageSeq: msg.userMessageSeq ?? undefined,
      thinking: msg.thinking ?? undefined,
    };

    const last = blocks.length > 0 ? blocks[blocks.length - 1] : null;
    if (last && last.displayName === name && last.isNarration === isNarration) {
      if (typeof entry.userMessageSeq === "number" && last.userMessageSeq === undefined) {
        last.userMessageSeq = entry.userMessageSeq;
      }
      if (entry.thinking) {
        last.thinking = entry.thinking;
      }
      last.lines.push(entry);
    } else {
      blocks.push({
        displayName: name,
        isNarration,
        lines: [entry],
        userMessageSeq: entry.userMessageSeq,
        thinking: entry.thinking,
      });
    }
  }

  return blocks;
});

function stripTrailPeriod(text: string): string {
  return text.replace(/[。]+$/, "");
}

function parseSegments(
  raw: string,
  actionPart: string | undefined,
  isNarration: boolean,
): Segment[] {
  const segments: Segment[] = [];
  let remaining = raw;
  const actions: string[] = [];
  let match: RegExpExecArray | null;

  while ((match = ACTION_RE.exec(remaining)) !== null) {
    if (match.index > 0) {
      let text = remaining.substring(0, match.index);
      if (!isNarration) text = stripTrailPeriod(text);
      if (text.trim()) segments.push({ type: "dialogue", text });
    }
    actions.push(match[0]);
    remaining = remaining.substring(match.index + match[0].length);
  }

  remaining = remaining.trim();
  if (remaining) {
    if (!isNarration) remaining = stripTrailPeriod(remaining);
    segments.push({ type: "dialogue", text: remaining });
  }

  if (actionPart) {
    segments.push({ type: "action", text: actionPart });
  }

  return segments;
}

/** 滚动到内容底部（最新记录） */
async function scrollToBottom(): Promise<void> {
  await nextTick();
  if (contentRef.value) {
    contentRef.value.scrollTop = contentRef.value.scrollHeight;
  }
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = false;
  try {
    const sessions = await fetchSessions();
    if (sessions.length === 0) {
      dialogHistory.value = [];
      return;
    }
    // 会话 id 只活在 InterviewRoomView 的局部 ref 里，历史页拿不到 → 取 id 最大的那场
    const latest = sessions.reduce((max, s) => (s.id > max.id ? s : max), sessions[0]);
    dialogHistory.value = await fetchMessages(latest.id);
  } catch (e) {
    console.error("加载对话历史失败:", e);
    loadError.value = true;
    dialogHistory.value = [];
  } finally {
    loading.value = false;
  }
}

// 打开面板时自动跳转到最后一页，并滚动到底部
onMounted(async () => {
  await load();
  if (dialogHistory.value.length > 0) {
    currentPage.value = totalPages.value;
    await scrollToBottom();
  }
});

// 当切换到最后一页时，自动滚动到底部（补语音期间跳过）
watch([currentPage, groupedHistory], async () => {
  if (suppressAutoScroll) return;
  if (currentPage.value === totalPages.value) {
    await scrollToBottom();
  }
});

// 新消息到达时跳转到最后一页（只监听长度变化；audioFile 回填不动长度）
watch(
  () => dialogHistory.value.length,
  () => {
    currentPage.value = totalPages.value;
  },
);
</script>

<style scoped>
.settings-history-page {
  width: 100%;
  height: 100%;
}

/* 滚动条染色（Tailwind v4 里 CSS 属性用 var() 合法，挪进 <style> 更稳） */
.scroll-brand {
  scrollbar-color: var(--accent-color, #79d9ff) transparent;
}
</style>
