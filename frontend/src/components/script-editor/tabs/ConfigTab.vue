<script setup lang="ts">
/**
 * 剧本设置 Tab（原版 `tabs/ConfigTab.vue`，373 行，**裁剪**后见上）。
 *
 * 技术栈差异（规格 E3 §1 / §6）：
 * - 无 vue-i18n，文案直接写中文；schema 的中文 label / hint 由后端下发，不再走
 *   `storyFieldLabelOf` 之类的本地词条表。
 * - 原版「羁绊冒险」整段删除：本项目剧本绑定的是 interviewer_id，没有角色目录 / 解锁条件。
 * - 新增「面试参数」段：课程题要求岗位 / 面试官 / 难度 / 公司风格，`interview_script` 已有列。
 */
import { computed, reactive, watch } from "vue";
import { Button, Icon } from "../../base";
import { MenuPage, MenuItem } from "../../ui";
import FieldRow from "../fields/FieldRow.vue";
import { saveStoryConfig, store } from "../../../store/scriptEditor";
import type { FieldSpec, StoryConfig } from "../../../api/editor";
import { jobs } from "../../../data/mock";
import { interviewers } from "../../../data/interviewers";

/** 本页可编辑的四个 story_config 键（后端 writeConfig 认的键之一部分） */
const CONFIG_KEYS = ["script_name", "description", "recommand_start", "intro_chapter"] as const;
/** 本页新增的四个面试参数键 */
const SETTING_KEYS = ["job_code", "interviewer_code", "level", "company_style"] as const;

const configDraft = reactive<Record<string, unknown>>({});
const settingsDraft = reactive<Record<string, string>>({
  job_code: "",
  interviewer_code: "",
  level: "",
  company_style: "",
});

/** 上次落库（或上次从 detail 灌入）的快照：值没变就不再发 PUT，避免「进页面就存一次」 */
let lastSaved = "";

const snapshot = () => JSON.stringify(buildConfig());

/** 从 detail 重新灌草稿。只取本页编辑的键，其余（script_settings 等）原样留在提交里带回。 */
function hydrate() {
  const cfg = (store.detail?.storyConfig ?? {}) as Record<string, unknown>;
  for (const k of CONFIG_KEYS) configDraft[k] = cfg[k] ?? "";
  for (const k of SETTING_KEYS) {
    const v = cfg[k];
    settingsDraft[k] = v === undefined || v === null ? "" : String(v);
  }
  lastSaved = snapshot();
}

hydrate();

// 切换剧本 / 保存回来后 detail.storyConfig 会换引用，草稿跟着重灌
watch(
  () => store.detail?.storyConfig,
  () => {
    hydrate();
  },
);

/** 声明成 function 是为了提升：hydrate() 在模块顶层就跑，早于下面这些 const */
function str(v: unknown) {
  return v === undefined || v === null ? "" : String(v);
}

/**
 * 提交体：A 段四个键 + D 段四个键 + script_settings（原样带回）。
 *
 * 后端 `writeConfig` 只按白名单取键，多送无副作用；但 `script_settings` 必须带上，
 * 否则保存一次剧本名会把轮次配置（total_questions / time_limit / follow_up_depth）清空。
 */
function buildConfig(): StoryConfig {
  const out: StoryConfig = {};
  for (const k of CONFIG_KEYS) out[k] = str(configDraft[k]);
  for (const k of SETTING_KEYS) out[k] = settingsDraft[k];
  const raw = store.detail?.storyConfig?.script_settings;
  if (raw !== undefined) out.script_settings = raw as Record<string, unknown>;
  return out;
}

/**
 * 防抖自动保存（原版 watch(configDraft) 的行为）。
 *
 * 用「值没变就不发」代替原版的立即保存：hydrate() 之后 watcher 仍会因为
 * detail 合并而触发一次，靠快照比对挡掉，否则进页面 / 每次保存完都会再打一个 PUT。
 */
let saveTimer: number | undefined;
watch(
  [configDraft, settingsDraft],
  () => {
    // 值没变就不打扰顶栏（hydrate 与保存回调都会让 watcher 空跑一次）
    if (JSON.stringify(buildConfig()) === lastSaved) return;
    // 本页没有章节可存，所以只拨脏标记、不走 markDirty()（那会顺带排一次章节写盘）
    store.dirty = true;
    if (saveTimer) window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
      saveTimer = undefined;
      const body = buildConfig();
      if (JSON.stringify(body) === lastSaved) return;
      lastSaved = JSON.stringify(body);
      void saveStoryConfig(body);
    }, 800);
  },
  { deep: true },
);

/** 手动保存按钮：跳过防抖直接写 */
const saveConfig = () => {
  if (saveTimer) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
  const body = buildConfig();
  lastSaved = JSON.stringify(body);
  void saveStoryConfig(body);
};

/** 表单字段（跳过 chapter：开场章节不能选「剧本结束」，由下面内联的下拉单独渲染） */
const textFields = computed<FieldSpec[]>(() =>
  (store.schema?.storyConfigFields ?? []).filter((f) => f.kind !== "chapter"),
);

const introField = computed<FieldSpec | undefined>(() =>
  (store.schema?.storyConfigFields ?? []).find((f) => f.key === "intro_chapter"),
);

/** 取字段元信息的兜底：schema 一定带 intro_chapter，但类型上是可选，这里给常量兜底 */
const INTRO_KEY = "intro_chapter";
const introLabel = computed(() => introField.value?.label ?? "开场章节");
const introRequired = computed(() => introField.value?.required ?? false);
const introHint = computed(() => introField.value?.hint ?? "");

/** 开场章节候选：全部章节，**去掉「剧本结束」哨兵** */
const introOptions = computed(() =>
  (store.detail?.chapters ?? []).map((c) => ({
    value: c.id,
    label: c.name ? `${c.name}（${c.id}）` : c.id,
  })),
);

const jobMissing = computed(
  () => !!settingsDraft.job_code && !jobs.some((j) => j.id === settingsDraft.job_code),
);

const interviewerMissing = computed(
  () =>
    !!settingsDraft.interviewer_code &&
    !interviewers.some((p) => p.code === settingsDraft.interviewer_code),
);
</script>

<template>
  <MenuPage>
    <MenuItem title="剧本设置">
      <template #header>
        <Icon icon="setting" :size="20" />
      </template>

      <!-- A 段：story_config 的四个基础字段（v-for 渲染 schema.storyConfigFields） -->
      <FieldRow
        v-for="f in textFields"
        :key="f.key"
        :field="f"
        :value="configDraft[f.key]"
        :diagnostics="[]"
        @update="(v: unknown) => (configDraft[f.key] = v)"
      />

      <!-- 开场章节：不能是「剧本结束」，所以不复用 FieldRow 的 chapter 候选（它带 end 哨兵） -->
      <div v-if="introField" class="mb-4">
        <label
          class="inline-flex items-center gap-1.5 font-medium text-brand"
          :title="`YAML 键：${INTRO_KEY}`"
        >
          {{ introLabel }}
          <span v-if="introRequired" class="text-xs text-red-400">*</span>
          <span v-else class="ml-auto text-xs font-normal text-white/35">选填</span>
        </label>
        <select
          class="glass-input"
          :value="String(configDraft[INTRO_KEY] ?? '')"
          @change="(e) => (configDraft[INTRO_KEY] = (e.target as HTMLSelectElement).value)"
        >
          <option v-for="c in introOptions" :key="c.value" :value="c.value">{{ c.label }}</option>
        </select>
        <p v-if="introHint" class="mt-1 text-xs leading-relaxed text-white/40">
          {{ introHint }}
        </p>
      </div>

      <!--
        原版这里是「羁绊冒险」设置（是否冒险 / 绑定角色目录 / 顺序 / 解锁条件）。
        本项目没有羁绊冒险体系（剧本绑定的是 interviewer_id，不是角色目录），
        这四个键在 interview_script 表里也没有对应列 —— 所以整段不渲染。
        技术栈差异，不是漏做。
      -->

      <!-- D 段：本项目扩展 —— 面试参数（原版没有，课程题 16 需要） -->
      <MenuItem title="面试参数">
        <template #header><Icon icon="setting" :size="20" /></template>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <label class="block">
            <span class="mb-1 block text-xs text-white/45">岗位</span>
            <select v-model="settingsDraft.job_code" class="glass-input cursor-pointer">
              <option value="">不设置</option>
              <option v-for="j in jobs" :key="j.id" :value="j.id">{{ j.name }}</option>
              <option v-if="jobMissing" :value="settingsDraft.job_code">
                {{ settingsDraft.job_code }}（未收录）
              </option>
            </select>
          </label>
          <label class="block">
            <span class="mb-1 block text-xs text-white/45">面试官</span>
            <select v-model="settingsDraft.interviewer_code" class="glass-input cursor-pointer">
              <option value="">不设置</option>
              <option v-for="p in interviewers" :key="p.code" :value="p.code">{{ p.name }}</option>
              <option v-if="interviewerMissing" :value="settingsDraft.interviewer_code">
                {{ settingsDraft.interviewer_code }}（未收录）
              </option>
            </select>
          </label>
          <label class="block">
            <span class="mb-1 block text-xs text-white/45">难度层级</span>
            <input v-model="settingsDraft.level" class="glass-input" placeholder="校招 / 社招 / 实习" />
          </label>
          <label class="block">
            <span class="mb-1 block text-xs text-white/45">公司风格</span>
            <input v-model="settingsDraft.company_style" class="glass-input" placeholder="一线互联网大厂" />
          </label>
        </div>
      </MenuItem>

      <Button type="big" class="mt-4" @click="saveConfig">保存剧本设置</Button>
    </MenuItem>
  </MenuPage>
</template>
