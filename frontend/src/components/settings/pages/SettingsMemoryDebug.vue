<template>
  <!-- 视图：永久记忆调试（高级设置 → 菜单 → 记忆调试）。检视演示记忆库，条目可搜索 / 过滤 / 展开 / 删除。 -->
  <MenuPage>
    <MenuItem title="永久记忆调试" size="large">
      <template #header>
        <Database :size="20" class="text-cyan-300" />
      </template>

      <div
        class="flex h-[70dvh] min-h-[460px] w-full flex-col overflow-hidden md:grid md:grid-cols-[min(28%,240px)_1fr]"
      >
        <!-- 左栏：面试官列表 -->
        <nav
          class="flex shrink-0 flex-col gap-1 overflow-y-auto border-b border-brand pb-2 md:border-r md:border-b-0 md:pb-0"
        >
          <div class="px-4 py-2 text-xs text-white/50">面试官</div>
          <p v-if="roles.length === 0" class="px-4 text-sm text-white/60">
            当前没有可检视的面试官（先在对话里让面试官上场）
          </p>
          <button
            v-for="role in roles"
            :key="role.roleId"
            class="mx-2 cursor-pointer rounded-lg px-3 py-2 text-left text-sm transition-colors duration-200"
            :class="
              role.roleId === selectedRoleId
                ? 'bg-brand text-white'
                : 'text-white/70 hover:bg-white/10 hover:text-white'
            "
            @click="selectRole(role.roleId)"
          >
            <span class="block truncate">{{ role.displayName || `面试官 ${role.roleId}` }}</span>
            <span class="block text-xs opacity-70">
              #{{ role.roleId }} · {{ runtimeText(role) }}
            </span>
          </button>
        </nav>

        <!-- 右栏：详情 -->
        <section class="min-h-0 flex-1 overflow-y-auto p-4">
          <!-- 工具条 -->
          <div class="mb-4 flex flex-wrap items-center gap-3">
            <h3 class="text-base font-bold text-brand">
              {{ snapshot?.displayName || "永久记忆调试" }}
            </h3>
            <span
              class="rounded-full border px-2 py-0.5 text-xs"
              :class="
                overview?.activeSavePersistent
                  ? 'border-white/20 text-white/70'
                  : 'border-amber-400/60 text-amber-300'
              "
            >
              {{
                overview?.activeSavePersistent
                  ? `面试存档 #${overview?.activeSaveId}`
                  : "自由对话：记忆只存在内存里，重启即失"
              }}
            </span>
            <span class="w-32 shrink-0">
              <Button type="big" :disabled="loading" @click="refreshAll">
                <RefreshCw :size="16" :class="{ 'animate-spin': loading }" />
                {{ loading ? "刷新中…" : "刷新" }}
              </Button>
            </span>
          </div>

          <p v-if="error" class="mb-4 rounded-lg border border-red-400/40 bg-red-500/10 p-3 text-sm">
            {{ error }}
          </p>
          <p v-else-if="!snapshot" class="text-sm text-white/60">
            选择左侧面试官，查看其永久记忆与真实上下文
          </p>

          <!-- 守卫直接判 runtime（而非 snapshot）：模板里 v-else 无法为 computed 收窄类型 -->
          <template v-else-if="runtime">
            <!-- 统计概览 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <h4 class="mb-1 text-sm font-semibold text-white">记忆总览</h4>
              <p class="mb-3 text-xs leading-5 text-white/60">
                条目数与 token 占用随筛选无关，始终统计该面试官记忆库里的全部条目。
              </p>
              <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
                <div>
                  <dt class="text-white/50">记忆条目数</dt>
                  <dd class="text-white/85">{{ runtime.entries.length }} 条</dd>
                </div>
                <div>
                  <dt class="text-white/50">压缩次数</dt>
                  <dd class="text-white/85">{{ runtime.compressCount }} 次</dd>
                </div>
                <div>
                  <dt class="text-white/50">token 占用</dt>
                  <dd class="text-white/85">{{ runtime.tokenUsed }} tokens</dd>
                </div>
                <div>
                  <dt class="text-white/50">上次压缩时间</dt>
                  <dd class="text-white/85">{{ runtime.lastCompressedAt || "—" }}</dd>
                </div>
              </dl>
            </div>

            <!-- 压缩状态 -->
            <div class="mb-4 rounded-xl border border-white/10 bg-black/15 p-4">
              <h4 class="mb-1 text-sm font-semibold text-white">压缩状态（运行时生效值）</h4>
              <p class="mb-3 text-xs leading-5 text-white/60">
                阈值单位是「该面试官可见的非 system 台词」：别的面试官的台词、System
                行、该面试官感知不到的台词都不计数。
              </p>

              <div class="mb-1 flex items-baseline justify-between text-sm">
                <span class="text-white/80">
                  已累积 {{ runtime.accumulatedVisibleCount }} / 阈值
                  {{ runtime.updateInterval }} 条
                </span>
                <span class="text-white/60">
                  {{ reached ? "已达阈值" : `距触发还差 ${remaining} 条` }}
                </span>
              </div>
              <div class="h-2 w-full overflow-hidden rounded-full bg-white/10">
                <div
                  class="h-full bg-brand"
                  :style="{ width: `${percent}%` }"
                ></div>
              </div>

              <p
                v-if="runtime.pointerOutOfRange"
                class="mt-3 rounded border border-amber-400/50 px-2 py-1 text-xs text-amber-300"
              >
                压缩指针越界：下次触发时会被重置为 0（撤回 / 读档 / 清空都会让历史变短）
              </p>

              <!-- 状态格子 -->
              <dl class="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-xs sm:grid-cols-3">
                <div v-for="item in stateItems" :key="item.label">
                  <dt class="text-white/50">{{ item.label }}</dt>
                  <dd class="text-white/85">{{ item.value }}</dd>
                </div>
              </dl>

              <p class="mt-3 text-xs text-white/45">
                以上 interval / window 是运行时生效值，改设置需重启才会更新。
              </p>
            </div>

            <!-- 过滤与搜索 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <h4 class="mb-3 text-sm font-semibold text-white">过滤与搜索</h4>
              <div class="flex flex-wrap items-center gap-2">
                <div class="relative min-w-[200px] flex-1">
                  <Search :size="14" class="absolute top-2.5 left-3 text-white/40" />
                  <input
                    v-model="keyword"
                    type="text"
                    placeholder="搜索记忆摘要或原文关键词"
                    aria-label="搜索记忆条目"
                    class="w-full rounded-lg bg-white/10 py-2 pr-3 pl-9 text-sm text-white placeholder:text-white/50 focus:outline-none"
                  />
                </div>
                <div class="flex items-center gap-1">
                  <button
                    v-for="opt in IMPORTANCE_FILTERS"
                    :key="opt.value"
                    class="cursor-pointer rounded-lg border px-3 py-1.5 text-xs transition-colors duration-200"
                    :class="
                      importanceFilter === opt.value
                        ? 'border-brand bg-[rgba(121,217,255,0.18)] text-white'
                        : 'border-white/15 text-white/60 hover:border-white/30 hover:text-white'
                    "
                    @click="importanceFilter = opt.value"
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </div>
              <p class="mt-2 text-xs text-white/45">
                命中 {{ filteredEntries.length }} / {{ runtime.entries.length }} 条
              </p>
            </div>

            <!-- 记忆条目列表 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <div class="mb-3 flex items-center justify-between">
                <h4 class="text-sm font-semibold text-white">记忆条目</h4>
                <span class="text-xs text-white/50">
                  展开可看压缩前原文，删除只影响本页演示数据
                </span>
              </div>

              <p v-if="filteredEntries.length === 0" class="py-4 text-center text-sm text-white/60">
                {{
                  runtime.entries.length === 0
                    ? "该面试官还没有记忆条目，先在模拟面试里聊几轮"
                    : "没有符合当前筛选条件的记忆条目"
                }}
              </p>
              <ul v-else class="space-y-2">
                <li
                  v-for="entry in filteredEntries"
                  :key="entry.id"
                  class="rounded-lg bg-white/5 px-3 py-2 text-xs"
                >
                  <div class="flex items-start gap-2">
                    <button
                      class="flex min-w-0 flex-1 cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-left"
                      @click="toggleExpand(entry.id)"
                    >
                      <ChevronDown
                        :size="14"
                        class="shrink-0 text-gray-400 transition-transform duration-200"
                        :class="{ 'rotate-180': expanded[entry.id] }"
                      />
                      <span class="truncate text-white/85">{{ entry.summary }}</span>
                    </button>
                    <span
                      class="shrink-0 rounded border px-1.5 py-0.5"
                      :class="importanceClass(entry.importance)"
                    >
                      {{ importanceLabel(entry.importance) }}
                    </span>
                    <button
                      class="shrink-0 cursor-pointer rounded border border-red-400/30 p-1 text-red-300 transition-colors duration-200 hover:border-red-400/60 hover:bg-red-500/15"
                      :aria-label="`删除记忆条目：${entry.summary}`"
                      @click="removeEntry(entry)"
                    >
                      <Trash2 :size="13" />
                    </button>
                  </div>

                  <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-white/45">
                    <span>{{ sectionLabel(entry.section) }}</span>
                    <span>重要度权重 {{ entry.weight.toFixed(2) }}</span>
                    <span>创建 {{ entry.createdAt }}</span>
                    <span>命中 {{ entry.hitCount }} 次</span>
                    <span>{{ entry.tokens }} tokens</span>
                  </div>

                  <pre
                    v-if="expanded[entry.id]"
                    class="mt-2 max-h-60 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                    >{{ entry.content }}</pre
                  >
                </li>
              </ul>
            </div>

            <!-- 压缩与重建操作 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <h4 class="mb-1 text-sm font-semibold text-white">压缩与重建</h4>
              <p class="mb-3 text-xs leading-5 text-white/60">
                压缩与重建索引需要调用压缩模型；开启「模拟触发」后改为本地演练，直接改写上方演示数据。
              </p>
              <div class="flex flex-wrap gap-2">
                <button
                  class="flex cursor-pointer items-center gap-1.5 rounded-lg bg-brand px-3 py-1.5 text-xs font-medium text-white transition-colors duration-200 hover:bg-[rgba(121,217,255,0.8)]"
                  @click="manualCompress"
                >
                  <Zap :size="14" />
                  手动触发压缩
                </button>
                <button
                  class="flex cursor-pointer items-center gap-1.5 rounded-lg border border-red-400/40 px-3 py-1.5 text-xs text-red-300 transition-colors duration-200 hover:bg-red-500/15"
                  @click="clearMemory"
                >
                  <Eraser :size="14" />
                  清空记忆
                </button>
                <button
                  class="flex cursor-pointer items-center gap-1.5 rounded-lg border border-white/20 px-3 py-1.5 text-xs text-white/80 transition-colors duration-200 hover:bg-white/10 hover:text-white"
                  @click="rebuildIndex"
                >
                  <RefreshCw :size="14" />
                  重建记忆索引
                </button>
              </div>
            </div>

            <!-- 调试开关 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <h4 class="mb-3 text-sm font-semibold text-white">调试开关</h4>
              <div class="flex flex-col gap-3">
                <button
                  type="button"
                  role="switch"
                  :aria-checked="showCompressLog"
                  aria-label="显示压缩过程日志"
                  class="inline-flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white select-none"
                  style="text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3)"
                  @click="showCompressLog = !showCompressLog"
                >
                  <span
                    class="relative inline-block h-6.5 w-12.5 shrink-0 rounded-[13px] transition-all duration-300 ease-in-out"
                    :class="
                      showCompressLog
                        ? 'border-brand bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                        : 'border-white/30 bg-white/20'
                    "
                  >
                    <span
                      class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
                      :class="
                        showCompressLog
                          ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6] shadow-[0_3px_8px_rgba(121,217,255,0.4),0_1px_3px_rgba(0,0,0,0.2)]'
                          : 'left-1 bg-linear-to-br from-white to-[#f0f0f0] shadow-[0_2px_6px_rgba(0,0,0,0.3),0_1px_2px_rgba(0,0,0,0.1)]'
                      "
                    ></span>
                  </span>
                  <span class="min-w-0 flex-1 text-left">显示压缩过程日志</span>
                </button>

                <button
                  type="button"
                  role="switch"
                  :aria-checked="simulateMode"
                  aria-label="模拟触发"
                  class="inline-flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white select-none"
                  style="text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3)"
                  @click="simulateMode = !simulateMode"
                >
                  <span
                    class="relative inline-block h-6.5 w-12.5 shrink-0 rounded-[13px] transition-all duration-300 ease-in-out"
                    :class="
                      simulateMode
                        ? 'border-brand bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                        : 'border-white/30 bg-white/20'
                    "
                  >
                    <span
                      class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
                      :class="
                        simulateMode
                          ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6] shadow-[0_3px_8px_rgba(121,217,255,0.4),0_1px_3px_rgba(0,0,0,0.2)]'
                          : 'left-1 bg-linear-to-br from-white to-[#f0f0f0] shadow-[0_2px_6px_rgba(0,0,0,0.3),0_1px_2px_rgba(0,0,0,0.1)]'
                      "
                    ></span>
                  </span>
                  <span class="min-w-0 flex-1 text-left">模拟触发（本地演练，不调用模型）</span>
                </button>
              </div>

              <div v-if="showCompressLog" class="mt-3">
                <p class="mb-1 text-xs text-white/50">压缩过程日志（最近 {{ logs.length }} 条）</p>
                <p v-if="logs.length === 0" class="text-xs text-white/45">
                  暂无日志，点一次「手动触发压缩」或「重建记忆索引」即可看到过程。
                </p>
                <ul v-else class="space-y-1">
                  <li
                    v-for="(line, index) in logs"
                    :key="`${index}-${line}`"
                    class="rounded bg-black/30 px-2 py-1 text-xs break-all whitespace-pre-wrap text-white/70"
                  >
                    {{ line }}
                  </li>
                </ul>
              </div>
            </div>

            <!-- ① 存储真源 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <button
                class="flex w-full cursor-pointer items-center justify-between text-left"
                @click="showStore = !showStore"
              >
                <span>
                  <span class="text-sm font-semibold text-white">① 记忆库存储真源</span>
                  <span class="ml-2 text-xs text-white/50">完整、不截断</span>
                </span>
                <ChevronDown
                  :size="15"
                  class="shrink-0 text-gray-400 transition-transform duration-200"
                  :class="{ 'rotate-180': showStore }"
                />
              </button>

              <div v-if="showStore" class="mt-3 space-y-3">
                <div v-for="sec in runtime.sections" :key="sec.key">
                  <div class="mb-1 flex flex-wrap items-center gap-2 text-xs">
                    <span class="text-brand">{{ sectionLabel(sec.key) }}</span>
                    <span class="text-white/50">
                      {{
                        sec.limit === 0
                          ? `${sectionChars(sec.key)} 字 / 不截断`
                          : `${sectionChars(sec.key)} 字 / 上限 ${sec.limit} 字`
                      }}
                    </span>
                    <span
                      v-if="sec.truncated"
                      class="rounded border border-amber-400/60 px-1.5 text-amber-300"
                    >
                      注入时会被截断
                    </span>
                  </div>
                  <pre
                    class="max-h-40 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                    >{{ sectionText(sec.key) }}</pre
                  >
                </div>
                <p class="text-xs text-white/45">
                  压缩指针 {{ runtime.bank.meta.last_processed_global_idx }} · 更新于
                  {{ runtime.bank.meta.updated_at || "—" }} · schema v{{ runtime.bank.schema_version }}
                </p>
              </div>
            </div>

            <!-- ② 注入视图 -->
            <div class="mb-3 rounded-xl border border-white/10 bg-black/15 p-4">
              <button
                class="flex w-full cursor-pointer items-center justify-between text-left"
                @click="showInjected = !showInjected"
              >
                <span>
                  <span class="text-sm font-semibold text-white">② 注入视图</span>
                  <span class="ml-2 text-xs text-white/50">LLM 实际收到的内容，已按上限截断</span>
                </span>
                <ChevronDown
                  :size="15"
                  class="shrink-0 text-gray-400 transition-transform duration-200"
                  :class="{ 'rotate-180': showInjected }"
                />
              </button>

              <div v-if="showInjected" class="mt-3 space-y-3">
                <div>
                  <p class="mb-1 text-xs text-white/50">system 记忆块（不含短期摘要）</p>
                  <pre
                    class="max-h-60 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                    >{{ runtime.injectedSystemText }}</pre
                  >
                </div>
                <div>
                  <p class="mb-1 text-xs text-white/50">短期回顾前缀（拼在第一条 user 前）</p>
                  <pre
                    class="max-h-40 overflow-y-auto rounded bg-black/30 p-2 text-xs break-all whitespace-pre-wrap text-white/80"
                    >{{ runtime.injectedShortTermText || "未注入" }}</pre
                  >
                </div>
              </div>
            </div>

            <!-- ③ 真实上下文 -->
            <div class="rounded-xl border border-white/10 bg-black/15 p-4">
              <button
                class="flex w-full cursor-pointer items-center justify-between text-left"
                @click="showContext = !showContext"
              >
                <span>
                  <span class="text-sm font-semibold text-white">③ 真实上下文</span>
                  <span class="ml-2 text-xs text-white/50">
                    共 {{ context.length }} 条，下一次请求将以此为基础
                  </span>
                </span>
                <ChevronDown
                  :size="15"
                  class="shrink-0 text-gray-400 transition-transform duration-200"
                  :class="{ 'rotate-180': showContext }"
                />
              </button>

              <div v-if="showContext" class="mt-3">
                <p v-if="context.length === 0" class="text-sm text-white/60">
                  上下文为空（该面试官还没参与过台词）
                </p>
                <ul v-else class="space-y-2">
                  <li
                    v-for="(msg, index) in context"
                    :key="index"
                    class="rounded-lg bg-white/5 px-3 py-2 text-xs"
                  >
                    <div class="mb-1 flex items-center gap-2">
                      <span class="text-white/40">{{ index }}</span>
                      <span class="font-semibold" :class="roleClass(msg.role)">{{ msg.role }}</span>
                      <span v-if="msg.tool_call_id" class="truncate text-white/40">
                        tool_call_id: {{ msg.tool_call_id }}
                      </span>
                    </div>
                    <pre
                      v-if="msg.content"
                      class="max-h-60 overflow-y-auto break-all whitespace-pre-wrap text-white/80"
                      >{{ msg.content }}</pre
                    >
                    <div v-if="msg.tool_calls?.length" class="mt-1">
                      <p class="mb-1 text-white/50">工具调用</p>
                      <pre
                        class="max-h-40 overflow-y-auto rounded bg-black/30 p-2 break-all whitespace-pre-wrap text-white/70"
                        >{{ prettyJson(JSON.stringify(msg.tool_calls)) }}</pre
                      >
                    </div>
                  </li>
                </ul>
              </div>
            </div>
          </template>
        </section>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ChevronDown, Database, Eraser, RefreshCw, Search, Trash2, Zap } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { Button } from "../../base";
import { notify } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import {
  memoryDebugOverview,
  memoryDebugSnapshots,
  type MemoryDebugRole,
  type MemoryDebugSnapshot,
  type MemoryEntry,
  type MemoryImportance,
  type MemorySectionKey,
} from "../../../data/mock";

/** 运行时生效值 = 底稿快照 + 本页可改的演示状态 */
type MemoryRuntime = MemoryDebugSnapshot & { roleId: number };

/** 每个面试官在本页被改动过的部分（条目、压缩计数、日志…），持久化到 localStorage */
interface LiveRoleState {
  entries: MemoryEntry[];
  compressCount: number;
  tokenUsed: number;
  lastCompressedAt: string;
  accumulatedVisibleCount: number;
  isUpdating: boolean;
  hasPending: boolean;
  pointerRaw: number;
  historyRevision: number;
  logs: string[];
}

const LIVE_KEY = "interview-memory-debug-live";

const SECTION_LABELS: Record<MemorySectionKey, string> = {
  short_term: "近期回顾（短期摘要）",
  long_term: "长期经历",
  user_info: "ta 的信息",
  promises: "重要约定",
};

const IMPORTANCE_LABELS: Record<MemoryImportance, string> = {
  high: "高",
  medium: "中",
  low: "低",
};

const IMPORTANCE_FILTERS: { value: MemoryImportance | "all"; label: string }[] = [
  { value: "all", label: "全部" },
  { value: "high", label: "高" },
  { value: "medium", label: "中" },
  { value: "low", label: "低" },
];

const overview = ref<typeof memoryDebugOverview | null>(null);
const snapshot = ref<MemoryDebugSnapshot | null>(null);
const selectedRoleId = ref<number | null>(null);
const loading = ref(false);
const error = ref("");

const showStore = ref(true);
const showInjected = ref(false);
const showContext = ref(false);
/** 调试开关：显示压缩过程日志 / 模拟触发 */
const showCompressLog = ref(false);
const simulateMode = ref(false);

const keyword = ref("");
const importanceFilter = ref<MemoryImportance | "all">("all");
const expanded = ref<Record<string, boolean>>({});

function loadLive(): Record<number, LiveRoleState> {
  try {
    const parsed = JSON.parse(localStorage.getItem(LIVE_KEY) ?? "{}") as Record<
      number,
      LiveRoleState
    >;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

const live = reactive<Record<number, LiveRoleState>>(loadLive());

function persistLive(): void {
  localStorage.setItem(LIVE_KEY, JSON.stringify(live));
}

/** 首次访问某个面试官时，用底稿快照初始化一份可改的演示状态 */
function ensureLive(roleId: number, base: MemoryDebugSnapshot): LiveRoleState {
  if (!live[roleId]) {
    live[roleId] = {
      entries: JSON.parse(JSON.stringify(base.entries)) as MemoryEntry[],
      compressCount: base.compressCount,
      tokenUsed: base.tokenUsed,
      lastCompressedAt: base.lastCompressedAt,
      accumulatedVisibleCount: base.accumulatedVisibleCount,
      isUpdating: base.isUpdating,
      hasPending: base.hasPending,
      pointerRaw: base.pointerRaw,
      historyRevision: base.historyRevision,
      logs: [`[${base.lastCompressedAt}] 载入记忆库：schema v${base.bank.schema_version}`],
    };
    persistLive();
  }
  return live[roleId];
}

const roles = computed<MemoryDebugRole[]>(() => overview.value?.roles ?? []);
const context = computed(() => snapshot.value?.context ?? []);

const runtime = computed<MemoryRuntime | null>(() => {
  const base = snapshot.value;
  if (!base || selectedRoleId.value === null) return null;
  const s = ensureLive(selectedRoleId.value, base);
  return {
    ...base,
    roleId: selectedRoleId.value,
    entries: s.entries,
    compressCount: s.compressCount,
    tokenUsed: s.tokenUsed,
    lastCompressedAt: s.lastCompressedAt,
    accumulatedVisibleCount: s.accumulatedVisibleCount,
    isUpdating: s.isUpdating,
    hasPending: s.hasPending,
    pointerRaw: s.pointerRaw,
    historyRevision: s.historyRevision,
  };
});

const logs = computed<string[]>(() =>
  selectedRoleId.value === null ? [] : (live[selectedRoleId.value]?.logs ?? []),
);

const remaining = computed(() =>
  runtime.value ? Math.max(0, runtime.value.updateInterval - runtime.value.accumulatedVisibleCount) : 0,
);
const reached = computed(() => !!runtime.value && remaining.value === 0);
const percent = computed(() => {
  if (!runtime.value || runtime.value.updateInterval === 0) return 0;
  return Math.min(
    100,
    Math.round((runtime.value.accumulatedVisibleCount / runtime.value.updateInterval) * 100),
  );
});

// 状态格子：全部取运行时生效值
const stateItems = computed(() => {
  const r = runtime.value;
  if (!r) return [];
  return [
    { label: "开关", value: r.enabled ? "已启用" : "已关闭" },
    { label: "压缩用面试官名", value: r.aiName || "—" },
    { label: "正在压缩", value: r.isUpdating ? "是" : "否" },
    { label: "有待同步结果", value: r.hasPending ? "是" : "否" },
    { label: "连续失败", value: String(r.failCount) },
    {
      label: "失败冷却",
      value: r.cooldownRemainingMs > 0 ? `${Math.ceil(r.cooldownRemainingMs / 1000)} 秒` : "—",
    },
    { label: "保留窗口", value: String(r.recentWindow) },
    { label: "指针 / 历史", value: `${r.pointerRaw} → ${r.pointerEffective} / ${r.lineCount}` },
    { label: "历史版本", value: String(r.historyRevision) },
  ];
});

const sectionLabel = (key: MemorySectionKey) => SECTION_LABELS[key] ?? key;
const importanceLabel = (level: MemoryImportance) => IMPORTANCE_LABELS[level] ?? level;

const importanceClass = (level: MemoryImportance) => {
  switch (level) {
    case "high":
      return "border-red-400/50 text-red-300";
    case "medium":
      return "border-amber-400/50 text-amber-300";
    default:
      return "border-white/25 text-white/60";
  }
};

const runtimeText = (role: MemoryDebugRole) => {
  if (!role.runtimePresent) return "运行时未创建";
  return role.enabled ? "记忆已启用" : "记忆已关闭";
};

const sectionText = (key: MemorySectionKey) => runtime.value?.bank.data[key] ?? "";
const sectionChars = (key: MemorySectionKey) => (runtime.value?.bank.data[key] ?? "").length;

const roleClass = (role: string) => {
  switch (role) {
    case "system":
      return "text-amber-300";
    case "user":
      return "text-sky-300";
    case "assistant":
      return "text-emerald-300";
    case "tool":
      return "text-fuchsia-300";
    default:
      return "text-white/70";
  }
};

const prettyJson = (raw: string) => {
  if (!raw) return "—";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
};

function nowStamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function pushLog(line: string): void {
  if (selectedRoleId.value === null) return;
  const s = live[selectedRoleId.value];
  if (!s) return;
  s.logs.unshift(`[${nowStamp()}] ${line}`);
  if (s.logs.length > 40) s.logs.length = 40;
  persistLive();
}

const filteredEntries = computed<MemoryEntry[]>(() => {
  const list = runtime.value?.entries ?? [];
  const kw = keyword.value.trim().toLowerCase();
  return list.filter((entry) => {
    if (importanceFilter.value !== "all" && entry.importance !== importanceFilter.value) {
      return false;
    }
    if (!kw) return true;
    return (
      entry.summary.toLowerCase().includes(kw) ||
      entry.content.toLowerCase().includes(kw) ||
      sectionLabel(entry.section).toLowerCase().includes(kw)
    );
  });
});

function toggleExpand(id: string): void {
  expanded.value[id] = !expanded.value[id];
}

async function removeEntry(entry: MemoryEntry): Promise<void> {
  const s = selectedRoleId.value === null ? undefined : live[selectedRoleId.value];
  if (!s) return;
  const ok = await confirmDialog(
    "删除记忆条目",
    `确定删除这条记忆吗？\n「${entry.summary}」\n删除后该条目不再参与注入，token 占用同步下降。`,
  );
  if (!ok) return;
  const idx = s.entries.findIndex((e) => e.id === entry.id);
  if (idx >= 0) {
    s.entries.splice(idx, 1);
    s.tokenUsed = Math.max(0, s.tokenUsed - entry.tokens);
    delete expanded.value[entry.id];
    persistLive();
    pushLog(`手动删除条目 ${entry.id}（-${entry.tokens} tokens）`);
    notify("记忆条目已删除");
  }
}

async function clearMemory(): Promise<void> {
  const s = selectedRoleId.value === null ? undefined : live[selectedRoleId.value];
  if (!s) return;
  if (s.entries.length === 0) {
    await alertDialog("清空记忆", "该面试官当前没有记忆条目，无需清空。");
    return;
  }
  const ok = await confirmDialog(
    "清空记忆",
    `确定清空「${runtime.value?.displayName ?? "该面试官"}」的全部 ${s.entries.length} 条记忆吗？清空后压缩指针会一并重置为 0。`,
  );
  if (!ok) return;
  const removed = s.entries.length;
  s.entries = [];
  s.tokenUsed = 0;
  s.pointerRaw = 0;
  s.accumulatedVisibleCount = 0;
  s.lastCompressedAt = nowStamp();
  s.compressCount += 1;
  expanded.value = {};
  persistLive();
  pushLog(`清空记忆：移除 ${removed} 条，指针重置为 0`);
  notify(`已清空 ${removed} 条记忆`);
}

/** 压满一轮可见台词 → 触发一次压缩（不调用模型，只改本页演示数据） */
function runSimulatedCompress(reason: string): void {
  const s = selectedRoleId.value === null ? undefined : live[selectedRoleId.value];
  const r = runtime.value;
  if (!s || !r) return;
  s.isUpdating = true;
  pushLog(`${reason}：可见台词 ${s.accumulatedVisibleCount} / 阈值 ${r.updateInterval}`);
  window.setTimeout(() => {
    s.compressCount += 1;
    s.lastCompressedAt = nowStamp();
    s.accumulatedVisibleCount = 0;
    s.pointerRaw = r.lineCount;
    s.historyRevision += 1;
    s.isUpdating = false;
    s.hasPending = false;
    persistLive();
    pushLog(
      `压缩完成：第 ${s.compressCount} 次，指针 ${s.pointerRaw} / ${r.lineCount}，历史版本 v${s.historyRevision}`,
    );
    notify(`已模拟触发一次压缩（第 ${s.compressCount} 次）`);
  }, 600);
}

async function manualCompress(): Promise<void> {
  if (simulateMode.value) {
    runSimulatedCompress("手动触发压缩");
    showCompressLog.value = true;
    return;
  }
  await alertDialog("手动触发压缩", "需要后端服务，当前未接入");
}

async function rebuildIndex(): Promise<void> {
  if (simulateMode.value) {
    runSimulatedCompress("重建记忆索引");
    showCompressLog.value = true;
    return;
  }
  await alertDialog("重建记忆索引", "需要后端服务，当前未接入");
}

const loadSnapshot = (roleId: number) => {
  const base = memoryDebugSnapshots[roleId];
  if (!base) {
    snapshot.value = null;
    error.value = "";
    return;
  }
  snapshot.value = base;
  error.value = "";
};

const selectRole = (roleId: number) => {
  selectedRoleId.value = roleId;
  loadSnapshot(roleId);
};

const refreshAll = () => {
  loading.value = true;
  error.value = "";
  try {
    const data = memoryDebugOverview;
    overview.value = data;
    // currentRoleId 可能为 null，或已不在列表里（面试官离场）——
    // 命中才选中，否则退回第一项，避免详情留下空白卡片。
    const ids = data.roles.map((r) => r.roleId);
    const preferred =
      data.currentRoleId !== null && ids.includes(data.currentRoleId)
        ? data.currentRoleId
        : (ids[0] ?? null);
    selectedRoleId.value = preferred;
    if (preferred === null) {
      snapshot.value = null;
    } else {
      loadSnapshot(preferred);
    }
  } catch (e) {
    overview.value = null;
    snapshot.value = null;
    error.value = String(e);
  } finally {
    loading.value = false;
  }
};

onMounted(refreshAll);
</script>
