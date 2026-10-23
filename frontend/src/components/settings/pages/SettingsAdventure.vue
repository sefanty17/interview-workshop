<template>
  <MenuPage>
    <MenuItem title="独立剧本">
      <template #header>
        <FileText :size="20" />
      </template>

      <!-- 顶部操作区：新建 / 导入（右上角） -->
      <div class="mb-4 flex items-center justify-end gap-2">
        <button
          class="flex cursor-pointer items-center rounded-lg border border-white/15 bg-white/10 px-3 py-1.5 text-xs font-bold text-white/85 transition-colors hover:border-[#79d9ff]/50 hover:bg-white/15 hover:text-white"
          @click="openCreateDialog"
        >
          <Plus :size="15" class="mr-1" />
          新建剧本
        </button>
        <button
          class="flex cursor-pointer items-center rounded-lg border border-white/15 bg-white/10 px-3 py-1.5 text-xs font-bold text-white/85 transition-colors hover:border-[#79d9ff]/50 hover:bg-white/15 hover:text-white disabled:cursor-wait disabled:opacity-60"
          :disabled="importing"
          @click="triggerImport"
        >
          <Download :size="15" class="mr-1" />
          {{ importing ? "导入中…" : "导入剧本" }}
        </button>
        <input ref="importInput" type="file" accept=".zip" class="hidden" @change="handleImport" />
      </div>

      <!-- 空态：书本图标 + 两行灰字 -->
      <div
        v-if="scripts.length === 0"
        class="flex flex-col items-center justify-center py-12 text-gray-400"
      >
        <div class="mb-4 flex h-20 w-20 items-center justify-center rounded-full bg-white/5">
          <Book :size="40" class="text-white/40" />
        </div>
        <p class="mb-2 text-lg text-white/80">暂无独立剧本</p>
        <p class="text-sm text-white/40">独立剧本是无需选择角色即可游玩的剧本</p>
      </div>

      <!-- 剧本卡列表 -->
      <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <div
          v-for="script in scripts"
          :key="script.code"
          class="group relative flex cursor-pointer flex-col rounded-xl border border-white/10 bg-white/[0.06] p-4 transition-all duration-300 hover:border-[#79d9ff]/50 hover:bg-white/[0.1]"
          @click="openPanel(script)"
        >
          <div class="mb-3 flex items-center justify-between gap-2">
            <h3 class="truncate text-lg font-bold text-white">{{ script.script_name }}</h3>
            <span
              class="shrink-0 rounded-full border border-[#79d9ff]/30 bg-[#79d9ff]/20 px-3 py-1 text-xs font-medium text-[#79d9ff]"
            >
              独立剧本
            </span>
          </div>

          <p v-if="script.description" class="mb-4 line-clamp-3 flex-1 text-sm text-white/70">
            {{ script.description }}
          </p>
          <p v-else class="mb-4 flex-1 text-sm italic text-white/40">暂无描述</p>

          <div class="mt-auto flex items-center justify-between">
            <span class="text-xs text-white/50">
              {{ chapterCount(script) }} 章 · {{ eventCount(script) }} 事件
            </span>
            <span class="text-xs font-medium text-[#79d9ff] transition-transform group-hover:translate-x-0.5">
              查看 ▸
            </span>
          </div>
        </div>
      </div>
    </MenuItem>

    <!-- 剧本冒险部分：选角色 + 选剧本 两步流程卡 -->
    <MenuItem title="剧本冒险（测试中）">
      <template #header>
        <Book :size="20" />
      </template>

      <!-- 当前进度提示 -->
      <div
        class="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-[#79d9ff]/30 bg-[#79d9ff]/10 px-4 py-2 text-sm"
      >
        <span class="text-white/60">当前进度：</span>
        <span class="font-medium text-[#79d9ff]">
          {{ bondCharacter ? findInterviewer(bondCharacter).name : "未选择角色" }}
        </span>
        <span class="text-white/40">→</span>
        <span class="font-medium text-[#79d9ff]">
          {{ bondScript ? (findScript(bondScript)?.script_name ?? "未选择剧本") : "未选择剧本" }}
        </span>
      </div>

      <!-- 第一步：选择角色 -->
      <h5 class="mb-3 mt-1 text-sm font-semibold text-white/80">第一步 · 选择角色</h5>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <button
          v-for="char in interviewers"
          :key="char.code"
          type="button"
          class="group flex cursor-pointer flex-col items-center rounded-xl border p-3 text-center transition-all duration-200"
          :class="
            bondCharacter === char.code
              ? 'border-[#79d9ff] bg-[#79d9ff]/15'
              : 'border-white/10 bg-white/[0.06] hover:border-[#79d9ff]/50 hover:bg-white/[0.1]'
          "
          @click="bondCharacter = char.code"
        >
          <img
            :src="avatarUrl(char.code, '正常')"
            :alt="char.name"
            class="mb-2 h-14 w-14 rounded-full border-2 object-cover"
            :class="bondCharacter === char.code ? 'border-[#79d9ff]' : 'border-white/20'"
          />
          <span class="text-sm font-bold text-white">{{ char.name }}</span>
          <span class="mt-0.5 text-xs text-white/50">{{ char.subtitle }}</span>
        </button>
      </div>

      <!-- 第二步：选择剧本 -->
      <h5 class="mb-3 mt-5 text-sm font-semibold text-white/80">第二步 · 选择剧本</h5>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <button
          v-for="script in seedScripts"
          :key="script.code"
          type="button"
          class="flex cursor-pointer flex-col rounded-xl border p-4 text-left transition-all duration-200"
          :class="
            bondScript === script.code
              ? 'border-[#79d9ff] bg-[#79d9ff]/15'
              : 'border-white/10 bg-white/[0.06] hover:border-[#79d9ff]/50 hover:bg-white/[0.1]'
          "
          @click="bondScript = script.code"
        >
          <div class="mb-1.5 flex items-center justify-between gap-2">
            <h3 class="truncate text-base font-bold text-white">{{ script.script_name }}</h3>
            <span
              class="shrink-0 rounded-full border border-[#79d9ff]/30 bg-[#79d9ff]/20 px-2 py-0.5 text-xs text-[#79d9ff]"
            >
              {{ script.chapters.length }} 章
            </span>
          </div>
          <p class="line-clamp-2 text-xs text-white/60">{{ script.description }}</p>
        </button>
      </div>

      <!-- 开始按钮 -->
      <div class="mt-5 flex justify-end">
        <button
          type="button"
          class="rounded-xl border-none px-6 py-2 text-sm font-bold text-white shadow-lg shadow-cyan-500/25 transition-all"
          :class="
            canStartBond
              ? 'cursor-pointer bg-cyan-500 hover:bg-cyan-400'
              : 'cursor-not-allowed bg-white/10 text-white/40'
          "
          :disabled="!canStartBond"
          @click="startBond"
        >
          开始剧本
        </button>
      </div>
    </MenuItem>

    <!-- 创意工坊部分 -->
    <MenuItem title="创意工坊" size="small">
      <template #header>
        <Birdhouse :size="20" />
      </template>
      <p class="mb-3 text-sm text-white/60">
        在创意工坊浏览、下载社区分享的剧本与角色，一键加入你的游戏。
      </p>
      <button
        type="button"
        class="flex cursor-pointer items-center rounded-lg border border-white/15 bg-white/10 px-4 py-2 text-sm font-bold text-white/85 transition-colors hover:border-[#79d9ff]/50 hover:bg-white/15 hover:text-white"
        @click="openWorkshop"
      >
        进入创意工坊
      </button>
    </MenuItem>

    <!-- 创建剧本部分 -->
    <MenuItem title="创建自己的剧本" size="small">
      <template #header>
        <UserPlus :size="20" />
      </template>
      <p class="mb-3 text-sm text-white/60">
        用可视化向导从零搭建你的面试剧本：添加章节、编排事件、设置分支跳转。
      </p>
      <button
        type="button"
        class="flex cursor-pointer items-center rounded-lg border border-white/15 bg-white/10 px-4 py-2 text-sm font-bold text-white/85 transition-colors hover:border-[#79d9ff]/50 hover:bg-white/15 hover:text-white"
        @click="openScriptWizard"
      >
        进入创建向导
      </button>
    </MenuItem>

    <!-- 剧本详情面板（点卡片打开） -->
    <AdventurePanel v-if="selectedScript" :script="selectedScript" @close="selectedScript = null" />

    <!-- 导入报告弹窗（规格 B5 §三）：绿可折叠、黄/红默认展开 -->
    <LcModal :show="reportOpen" title="导入报告" width="46rem" @close="closeReport">
      <div v-if="report" class="report">
        <div class="report-head">
          <div class="min-w-0">
            <p class="truncate text-base font-bold text-white">{{ report.report.scriptName }}</p>
            <p class="mt-0.5 text-xs text-white/45">
              {{ report.scriptCode }} · {{ report.created ? "新建" : "覆盖重建" }} ·
              {{ report.report.chapters }} 章 / {{ report.report.events }} 事件
            </p>
          </div>
          <div class="flex shrink-0 items-center gap-3 text-xs font-bold">
            <span class="text-emerald-300">{{ report.report.summary.green }} 可执行</span>
            <span class="text-white/20">/</span>
            <span class="text-amber-300">{{ report.report.summary.yellow }} 需处理</span>
            <span class="text-white/20">/</span>
            <span class="text-rose-300">{{ report.report.summary.red }} 未实现</span>
          </div>
        </div>

        <!-- 绿：可折叠，默认收起（数量多，先看问题） -->
        <section class="report-section">
          <button type="button" class="report-toggle" @click="showGreen = !showGreen">
            <span class="report-dot bg-emerald-400"></span>
            绿 · 可执行（{{ report.report.green.length }}）
            <span class="ml-auto text-sm text-white/40">{{ showGreen ? "收起 ▴" : "展开 ▾" }}</span>
          </button>
          <ul v-if="showGreen" class="report-list">
            <li v-for="item in report.report.green" :key="'g-' + item.chapter + '-' + item.index" class="report-item">
              <code class="report-where">{{ item.chapter || "剧本" }}#{{ item.index }}</code>
              <span class="report-type">{{ item.type }}</span>
              <span class="text-white/55">{{ item.note }}</span>
            </li>
          </ul>
        </section>

        <!-- 黄：默认展开 -->
        <section class="report-section">
          <h4 class="report-title">
            <span class="report-dot bg-amber-400"></span>
            黄 · 需处理（{{ report.report.yellow.length }}）
          </h4>
          <p v-if="report.report.yellow.length === 0" class="report-empty">没有发现问题</p>
          <ul v-else class="report-list">
            <li
              v-for="item in report.report.yellow"
              :key="'y-' + item.chapter + '-' + item.index"
              class="report-item report-item-yellow"
            >
              <code class="report-where">{{ item.chapter || "剧本" }}#{{ item.index }}</code>
              <span class="report-type">{{ item.type }}</span>
              <span class="font-bold text-amber-200">{{ item.issue }}</span>
              <span class="text-white/60">{{ item.detail }}</span>
            </li>
          </ul>
        </section>

        <!-- 红：默认展开 -->
        <section class="report-section">
          <h4 class="report-title">
            <span class="report-dot bg-rose-400"></span>
            红 · 未实现（{{ report.report.red.length }}）
          </h4>
          <p v-if="report.report.red.length === 0" class="report-empty">没有跑不动的事件</p>
          <ul v-else class="report-list">
            <li
              v-for="item in report.report.red"
              :key="'r-' + item.chapter + '-' + item.index"
              class="report-item report-item-red"
            >
              <code class="report-where">{{ item.chapter || "剧本" }}#{{ item.index }}</code>
              <span class="report-type">{{ item.type }}</span>
              <span class="font-bold text-rose-200">{{ item.issue }}</span>
              <span class="text-white/60">{{ item.detail }}</span>
            </li>
          </ul>
        </section>
      </div>

      <template #footer>
        <div class="flex items-center justify-between gap-4">
          <p v-if="report && report.report.red.length > 0" class="text-xs text-rose-300">
            有 {{ report.report.red.length }} 项未实现，导入后可编辑补全
          </p>
          <p v-else class="text-xs text-white/40">报告只做记录，黄/红项不会被自动修掉</p>
          <button type="button" class="report-done" @click="closeReport">完成</button>
        </div>
      </template>
    </LcModal>

    <!-- 新建剧本弹窗 -->
    <Teleport to="body">
      <div
        v-if="createOpen"
        class="fixed inset-0 z-[10003] flex items-center justify-center p-4"
      >
        <div class="absolute inset-0 bg-black/70 backdrop-blur-[4px]" @click="closeCreateDialog"></div>
        <div
          class="relative z-10 w-full max-w-md overflow-hidden rounded-xl border border-white/20 bg-slate-900 p-6"
        >
          <div class="absolute top-0 right-0 left-0 h-1 bg-[#79d9ff]"></div>
          <h3 class="mb-4 text-lg font-bold text-white">新建剧本</h3>
          <label class="mb-1.5 block text-xs text-white/50">剧本名称</label>
          <input
            ref="nameInput"
            v-model="newName"
            class="glass-input"
            placeholder="例如：算法岗 · 压力面"
            @keyup.enter="confirmCreate"
          />
          <p v-if="createError" class="mt-2 text-xs text-red-300">{{ createError }}</p>
          <div class="mt-5 flex justify-end gap-2.5">
            <button
              class="cursor-pointer rounded-xl border border-white/20 bg-transparent px-5 py-1.5 text-sm font-bold text-white/70 transition-all hover:bg-white/10 hover:text-white"
              @click="closeCreateDialog"
            >
              取消
            </button>
            <button
              class="cursor-pointer rounded-xl border-none bg-cyan-500 px-5 py-1.5 text-sm font-bold text-white shadow-lg shadow-cyan-500/25 transition-all hover:bg-cyan-400"
              @click="confirmCreate"
            >
              创建
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { useRouter } from "vue-router";
import { Birdhouse, Book, Download, FileText, Plus, UserPlus } from "@lucide/vue";
import { MenuPage, MenuItem } from "../../ui";
import LcModal from "../../ui/LcModal.vue";
import AdventurePanel from "./Adeventure/AdventurePanel.vue";
import type { InterviewScript } from "../../../data/scripts";
import { seedScripts, findScript } from "../../../data/scripts";
import { interviewers, findInterviewer, avatarUrl } from "../../../data/interviewers";
import { importScriptZip, ScriptImportError, type ImportResult } from "../../../api/scripts";
import {
  createScript,
  notify,
  persist,
  store,
} from "../../../store/appStore";
import { alertDialog } from "../../../store/dialogStore";

const router = useRouter();

// 剧本列表：直接接 store.scripts（data/scripts.ts 的 4 个种子 + 用户新建/导入）
const scripts = computed(() => store.scripts);

const selectedScript = ref<InterviewScript | null>(null);
function openPanel(script: InterviewScript): void {
  selectedScript.value = script;
}

function chapterCount(s: InterviewScript): number {
  return s.chapters.length;
}
function eventCount(s: InterviewScript): number {
  return s.chapters.reduce((sum, ch) => sum + ch.events.length, 0);
}

// ---------- 剧本冒险：选角色 + 选剧本（localStorage 持久化） ----------
const bondCharacter = ref<string>(localStorage.getItem("bondCharacter") || "");
const bondScript = ref<string>(localStorage.getItem("bondScript") || "");

const canStartBond = computed(() => !!bondCharacter.value && !!bondScript.value);

function startBond(): void {
  if (!canStartBond.value) return;
  localStorage.setItem("bondCharacter", bondCharacter.value);
  localStorage.setItem("bondScript", bondScript.value);
  const name = findScript(bondScript.value)?.script_name ?? "未命名剧本";
  notify(`即将开始剧本「${name}」`);
  router.push("/script-editor");
}

// ---------- 创意工坊 / 创建剧本 入口 ----------
function openWorkshop(): void {
  router.push("/workshop");
}

function openScriptWizard(): void {
  router.push("/script-editor");
}

// ---------- 新建剧本弹窗 ----------
const createOpen = ref(false);
const newName = ref("");
const createError = ref("");
const nameInput = ref<HTMLInputElement | null>(null);

function openCreateDialog(): void {
  newName.value = "";
  createError.value = "";
  createOpen.value = true;
  void nextTick(() => nameInput.value?.focus());
}

function closeCreateDialog(): void {
  createOpen.value = false;
}

function confirmCreate(): void {
  const name = newName.value.trim();
  if (!name) {
    createError.value = "请输入剧本名称";
    return;
  }
  const s = createScript();
  s.script_name = name;
  persist();
  notify(`已新建剧本「${name}」`);
  createOpen.value = false;
  // 跳转到剧本编辑器，便于立即完善内容
  router.push("/script-editor");
}

// ---------- 导入剧本（规格 B5 §三：真导入 + 三色报告） ----------
const importInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
const reportOpen = ref(false);
const report = ref<ImportResult | null>(null);
const showGreen = ref(false);

function triggerImport(): void {
  importInput.value?.click();
}

async function handleImport(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;

  importing.value = true;
  try {
    const result = await importScriptZip(file);
    report.value = result;
    showGreen.value = false;
    reportOpen.value = true;
    notify(`剧本「${result.report.scriptName}」已导入（${result.report.chapters} 章）`);
  } catch (e) {
    const message = e instanceof ScriptImportError ? e.message : "无法解析文件，请确认是剧本目录打成的 zip";
    void alertDialog("导入失败", message);
  } finally {
    importing.value = false;
  }
}

function closeReport(): void {
  reportOpen.value = false;
}

</script>

<style scoped>
.glass-input {
  width: 100%;
  padding: 10px 12px;
  color: #fff;
  font-family: inherit;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  outline: none;
  transition: border-color 0.2s;
}
.glass-input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}
.glass-input:focus {
  border-color: rgba(121, 217, 255, 0.5);
}

/* ---------- 导入报告弹窗 ---------- */
.report {
  max-height: 58vh;
  overflow-y: auto;
  padding-right: 4px;
}
.report-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
}
.report-section {
  margin-top: 14px;
}
.report-toggle,
.report-title {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  font-size: 13px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.85);
  background: none;
  border: none;
  padding: 0;
  text-align: left;
}
.report-toggle {
  cursor: pointer;
}
.report-dot {
  width: 8px;
  height: 8px;
  border-radius: 9999px;
  flex-shrink: 0;
}
.report-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.report-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.5;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.report-item-yellow {
  background: rgba(251, 191, 36, 0.08);
  border-color: rgba(251, 191, 36, 0.25);
}
.report-item-red {
  background: rgba(244, 63, 94, 0.1);
  border-color: rgba(244, 63, 94, 0.3);
}
.report-where {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #79d9ff;
  white-space: nowrap;
}
.report-type {
  font-size: 11px;
  padding: 0 6px;
  border-radius: 9999px;
  color: rgba(255, 255, 255, 0.6);
  background: rgba(255, 255, 255, 0.1);
}
.report-empty {
  margin-top: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.35);
}
.report-done {
  flex-shrink: 0;
  padding: 6px 24px;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: #06b6d4;
  border: none;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 10px 15px -3px rgba(6, 182, 212, 0.25);
  transition: background-color 0.2s;
}
.report-done:hover {
  background: #22d3ee;
}
</style>
