<template>
  <div class="mb-4">
    <label
      class="inline-flex items-center gap-1.5 font-medium text-brand"
      :title="`YAML 键：${field.key}`"
    >
      {{ field.label }}
      <span v-if="field.required" class="text-xs text-red-400">*</span>
      <span v-else class="ml-auto text-xs font-normal text-white/35">选填</span>
    </label>

    <!-- 遗留字段：只展示，不给编辑 -->
    <template v-if="field.kind === 'deprecated' || !field.enabled">
      <input class="glass-input opacity-45" :value="asText" disabled />
    </template>

    <!-- 多行文本 -->
    <textarea
      v-else-if="field.kind === 'textarea'"
      class="glass-input min-h-20 resize-y leading-relaxed"
      :value="asText"
      :placeholder="field.placeholder"
      @change="onText"
    ></textarea>

    <!-- 数字 -->
    <input
      v-else-if="field.kind === 'number'"
      class="glass-input"
      type="number"
      :value="asText"
      :placeholder="field.placeholder"
      @change="onNumber"
    />

    <!-- 开关。必填字段只有开/关两态；可选字段必须能表达「不设置」——
         引擎对这类字段的默认值往往不是 false（比如环境音的 loop / fade 默认 true），
         用两态开关会让「没写过这个字段」和「显式写了 false」长得一模一样，
         作者点一下再点回来就悄悄改变了行为。 -->
    <div v-else-if="field.kind === 'bool' && field.required" class="flex items-center gap-2">
      <Toggle :checked="value === true" @change="(v: boolean) => emit('update', v)" />
      <span class="text-sm text-white/70">{{ value === true ? "开" : "关" }}</span>
    </div>
    <select
      v-else-if="field.kind === 'bool'"
      class="glass-input"
      :value="value === true ? 'true' : value === false ? 'false' : ''"
      @change="onTriState"
    >
      <option value="">
        {{
          field.defaultDesc ? `不设置（默认 ${field.defaultDesc}）` : "不设置（用引擎默认值）"
        }}
      </option>
      <option value="true">开</option>
      <option value="false">关</option>
    </select>

    <!-- 固定候选 / 角色 / 情绪 / 章节 -->
    <select v-else-if="isSelectLike" class="glass-input" :value="asText" @change="onSelect">
      <!-- 非必填字段：不设置。character 例外：独立剧本无 MAIN 选项时，
           留空即引擎的 MAIN（当前主角），故也提供空选项并明确提示 -->
      <option v-if="!field.required || field.kind === 'character'" value="">
        {{ field.kind === "character" ? "不设置（= MAIN，当前主角）" : "不设置" }}
      </option>
      <option v-for="opt in selectOptions" :key="opt.value" :value="opt.value">
        {{ opt.label }}
      </option>
    </select>

    <!--
      素材：原版是「下拉 + 导入到剧本 + 导入到全局」三个控件，靠 Tauri 文件对话框
      挑选本地文件再交给 Rust 复制。本项目后端没有 uploadAsset 端点，也没有全局素材库，
      所以只保留下拉；导入入口统一放在 E5 的「素材」页。
    -->
    <div v-else-if="field.kind === 'asset'">
      <select class="glass-input" :value="asText" @change="onSelect">
        <option v-if="!field.required" value="">不设置</option>
        <option v-for="name in assetOptions" :key="name" :value="name">
          {{ name }}
        </option>
      </select>
      <p v-if="assetOptions.length === 0" class="mt-1 text-xs text-yellow-200">
        这个剧本还没有素材。素材可以在「素材」页导入。
      </p>
      <p class="mt-1 text-xs text-white/35">素材需要在「素材」页导入（E5 开放）</p>
    </div>

    <!-- 触发条件：结构化「变量 + 关系 + 值」表单，无需手写语法。
         说明由下方 schema 的 hint 承担，隐藏编辑器自带的顶部行，避免重复 -->
    <ConditionEditor
      v-else-if="field.kind === 'condition'"
      :model-value="asText"
      :variables="variables"
      :hint="''"
      @update:model-value="(v: string) => emit('update', v)"
    />

    <!-- 复合编辑器：选项 / 分支 / 赋值组 -->
    <CompositeField
      v-else-if="isComposite"
      :field="field"
      :value="value"
      :branch-mode="branchMode"
      @update="(v: unknown) => emit('update', v)"
    />

    <!-- 服装：按事件所选角色动态生成候选（剧本 NPC 服装）。
         本项目后端 characters 恒空（E1 取舍），所以候选只有 default，
         等 E5 补上角色接口后这段自动生效，无需再改。 -->
    <div v-else-if="field.key === 'clothes'">
      <select class="glass-input" :value="asText" @change="onSelect">
        <option v-if="!field.required" value="">不设置</option>
        <option v-for="opt in clothesOptions" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
      <p v-if="clothesOptions.length === 1" class="mt-1 text-xs text-white/35">
        暂无服装目录，先在剧本里建角色
      </p>
    </div>

    <!-- 单行文本兜底 -->
    <input
      v-else
      class="glass-input"
      :value="asText"
      :placeholder="field.placeholder"
      @change="onText"
    />

    <p v-if="field.hint" class="mt-1 text-xs leading-relaxed" :class="hintClass">
      {{ field.hint }}
    </p>
    <p
      v-for="(d, i) in diagnostics"
      :key="i"
      class="mt-1 text-xs leading-relaxed"
      :class="d.severity === 'error' ? 'text-red-300' : 'text-yellow-200'"
    >
      {{ d.message }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import Toggle from "../../base/widget/Toggle.vue";
import { EMOTIONS } from "../../../data/interviewers";
import {
  assets,
  chapterOptions,
  characterOptions,
  store,
  variables,
} from "../../../store/scriptEditor";
import type {
  AssetIndex,
  Diagnostic,
  FieldSpec,
  ScriptEventData,
} from "../../../api/editor";
import CompositeField from "./CompositeField.vue";
import ConditionEditor from "./ConditionEditor.vue";

const props = defineProps<{
  field: FieldSpec;
  value: unknown;
  /** 整个事件对象。分支编辑器需要看兄弟字段 end_type 才知道要不要显示 AI 分支名 */
  event?: ScriptEventData;
  diagnostics: Diagnostic[];
}>();

const emit = defineEmits<{ (e: "update", value: unknown): void }>();

const asText = computed(() => {
  const v = props.value;
  if (v === undefined || v === null) return "";
  if (typeof v === "object") return "";
  return String(v);
});

/**
 * 服装下拉候选：
 * - 先固定一个「默认（不进子目录）」—— 引擎把 default / 空都映射为不进子目录；
 * - 剧本 NPC：当前事件 character 字段对应角色的 avatar/ 子目录。
 *
 * 原版这里还有「MAIN 绑定羁绊人物的全局服装」一支，本项目没有全局角色库
 * （后端 editor_list_global_characters 无对应端点），故整段删除。
 */
const clothesOptions = computed<{ value: string; label: string }[]>(() => {
  const base: { value: string; label: string }[] = [
    { value: "default", label: "默认（不进子目录）" },
  ];
  const seen = new Set<string>(["default"]);
  const add = (name: string) => {
    if (!name || seen.has(name)) return;
    seen.add(name);
    base.push({ value: name, label: name });
  };

  const charKey = typeof props.event?.character === "string" ? props.event.character : "";
  const npc = store.detail?.characters.find((c) => c.roleKey === charKey || c.folder === charKey);
  npc?.clothes.forEach(add);

  return base;
});

const isSelectLike = computed(() =>
  ["select", "character", "emotion", "chapter"].includes(props.field.kind),
);

const isComposite = computed(() =>
  ["choice_options", "branch_options", "var_options"].includes(props.field.kind),
);

/**
 * 候选项的归属：
 * - select   → Rust schema 给的固定表（如背景特效）
 * - character→ MAIN + 剧本内 NPC
 * - emotion  → **前端**的情绪表（它决定情绪到立绘文件名的映射，归前端所有）
 * - chapter  → 当前剧本的章节列表 + 「剧本结束」
 */
const selectOptions = computed<{ value: string; label: string }[]>(() => {
  switch (props.field.kind) {
    case "select":
      // 有 option_labels 用显示名（值仍是引擎认的原文），否则直接显示原文
      return (props.field.options ?? []).map((o, idx) => ({
        value: o,
        label: props.field.optionLabels?.[idx] ?? o,
      }));
    case "character":
      // characterOptions 已含 label（MAIN → 当前主角，NPC → aiName）
      return characterOptions.value;
    case "emotion":
      return EMOTIONS.map((e) => ({ value: e, label: e }));
    case "chapter":
      return chapterOptions.value;
    default:
      return [];
  }
});

const scriptAssets = computed<string[]>(() => {
  const kind = props.field.assetKind;
  if (!kind) return [];
  return assets.value[kind as keyof AssetIndex] ?? [];
});

/** 没有全局素材库，候选就是本剧本的素材 */
const assetOptions = computed<string[]>(() => scriptAssets.value);

/**
 * 分支列表显示模式：按 end_type 决定分支编辑器给作者看「条件」还是「AI 识别名」。
 * - branching → 条件（引擎按 condition 选分支）
 * - ai_judged → AI 识别名（引擎按 name 选分支），条件不读
 * linear 或未设置时无分支，给 undefined 由 CompositeField 兜底为 branching。
 */
const branchMode = computed<"branching" | "ai_judged" | undefined>(() => {
  const et = props.event?.end_type;
  return et === "ai_judged" ? "ai_judged" : et === "branching" ? "branching" : undefined;
});

const hintClass = computed(() =>
  /⚠/.test(props.field.hint ?? "") ? "text-yellow-200" : "text-white/40",
);

const onText = (e: Event) => emit("update", (e.target as HTMLInputElement).value);

const onSelect = (e: Event) => emit("update", (e.target as HTMLSelectElement).value);

/** 空串会被 store 的 setEventField 当成「删键」，正好就是「不设置」的语义 */
const onTriState = (e: Event) => {
  const v = (e.target as HTMLSelectElement).value;
  emit("update", v === "" ? "" : v === "true");
};

const onNumber = (e: Event) => {
  const raw = (e.target as HTMLInputElement).value.trim();
  if (raw === "") {
    emit("update", "");
    return;
  }
  const n = Number(raw);
  // 不是数字就别往 YAML 里写 —— 引擎读到字符串会静默回落默认值
  emit("update", Number.isFinite(n) ? n : "");
};
</script>
