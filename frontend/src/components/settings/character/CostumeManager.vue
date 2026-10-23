<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-white/50">
      按服装查看立绘资源和 Live2D 映射。新增、重命名、移除及映射修改均保留在草稿，点击底部保存更改后生效。
    </p>
    <div class="flex flex-wrap gap-2">
      <input
        v-model="newName"
        :disabled="busy"
        class="min-w-0 flex-1 rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm text-white"
        placeholder="新服装名称"
        aria-label="新服装名称"
        @keydown.enter="create"
      />
      <button
        type="button"
        :disabled="busy || !newName.trim()"
        class="rounded-lg bg-[#5e72e4] px-4 py-2 text-sm text-white hover:bg-[#4a5acf] disabled:opacity-40"
        @click="create"
      >
        新增服装
      </button>
    </div>
    <p v-if="error" role="alert" class="text-sm text-red-300">{{ error }}</p>
    <article
      v-for="costume in costumes"
      :key="costume.name"
      class="flex flex-col gap-4 rounded-xl border border-white/10 bg-white/5 p-4 sm:flex-row"
    >
      <div
        class="flex aspect-[3/4] w-28 shrink-0 items-center justify-center self-start overflow-hidden rounded-lg bg-black/20"
      >
        <img
          v-if="costume.preview"
          :src="costume.preview"
          :alt="costume.name === 'default' ? '默认服装' : costume.name"
          class="h-full w-full object-contain"
        />
        <span v-else class="px-2 text-center text-xs text-white/35">缺少正常立绘</span>
      </div>
      <div class="min-w-0 flex-1 space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <strong class="text-sm text-white/85">{{
            costume.name === "default" ? "默认服装" : costume.name
          }}</strong>
          <span
            class="text-xs"
            :class="costume.present === costume.total ? 'text-cyan-200' : 'text-amber-200/80'"
            >静态差分 {{ costume.present }}/{{ costume.total }}</span
          >
        </div>
        <div v-if="costume.name !== 'default'" class="flex gap-2">
          <input
            v-model="names[costume.name]"
            :disabled="busy"
            class="min-w-0 flex-1 rounded-lg border border-white/10 bg-black/20 px-3 py-2 text-sm text-white"
            :aria-label="`${costume.name}的新名称`"
          />
          <button
            type="button"
            :disabled="
              busy || !names[costume.name]?.trim() || names[costume.name].trim() === costume.name
            "
            class="rounded-lg bg-white/10 px-3 text-xs text-white/70 hover:bg-white/15 disabled:opacity-30"
            @click="rename(costume.name)"
          >
            重命名
          </button>
          <button
            type="button"
            :disabled="busy"
            class="rounded-lg bg-red-500/15 px-3 text-xs text-red-200 hover:bg-red-500/25 disabled:opacity-30"
            @click="remove(costume.name)"
          >
            移除服装
          </button>
        </div>
        <label class="flex flex-col gap-2 text-xs text-white/60">
          服装提示词
          <textarea
            :value="promptFor(costume.name)"
            :disabled="busy"
            rows="2"
            class="rounded-lg border border-white/10 bg-black/20 px-3 py-2 text-sm text-white"
            @input="setPrompt(costume.name, ($event.target as HTMLTextAreaElement).value)"
          ></textarea>
        </label>
        <label v-if="modelValue.live2d" class="flex flex-col gap-2 text-xs text-white/60">
          Live2D 模型
          <select
            :value="modelValue.live2d.clothes_variants?.[costume.name] ?? ''"
            :disabled="busy"
            class="rounded-lg border border-white/10 bg-[#252535] px-3 py-2 text-sm text-white"
            @change="setVariant(costume.name, ($event.target as HTMLSelectElement).value)"
          >
            <option value="">使用默认模型</option>
            <option
              v-for="variant in Object.keys(modelValue.live2d.variants ?? {})"
              :key="variant"
              :value="variant"
            >
              {{ variant }}
            </option>
          </select>
        </label>
        <details v-if="costume.missing.length" class="text-xs text-white/45">
          <summary class="cursor-pointer hover:text-white/70">查看缺失情绪</summary>
          <p class="mt-2 leading-relaxed">{{ costume.missing.join("、") }}</p>
        </details>
        <button
          type="button"
          class="text-xs text-cyan-200/80 hover:text-cyan-100"
          @click="emit('edit-avatars', costume.name)"
        >
          管理此服装差分 →
        </button>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, toRaw } from "vue";
import { EMOTIONS, avatarUrl } from "../../../data/interviewers";
import { confirmDialog } from "../../../store/dialogStore";

const props = defineProps<{ roleId: string; modelValue: Record<string, any> }>();
const emit = defineEmits<{
  "update:modelValue": [value: Record<string, any>];
  changed: [value: { oldName: string; newName: string }];
  "edit-avatars": [name: string];
}>();
interface CostumeSummary {
  name: string;
  preview: string;
  present: number;
  total: number;
  missing: string[];
}
const names = ref<Record<string, string>>({});
const newName = ref("");
const busy = ref(false);
const error = ref("");
const clothes = computed(() => (props.modelValue.clothes ?? []) as Array<{ name: string; prompt: string }>);
/**
 * 默认服装用静态差分；新增的服装只有名字与提示词，资源要等后端角色目录，
 * 所以 present=0 / missing=全部情绪，与原版「未上传的服装」表现一致。
 */
const costumes = computed<CostumeSummary[]>(() => [
  {
    name: "default",
    preview: avatarUrl(props.roleId, "正常"),
    present: EMOTIONS.length,
    total: EMOTIONS.length,
    missing: [],
  },
  ...clothes.value.map((item) => ({
    name: item.name,
    preview: "",
    present: 0,
    total: EMOTIONS.length,
    missing: [...EMOTIONS],
  })),
]);
watch(
  costumes,
  (list) => {
    names.value = Object.fromEntries(list.map((item) => [item.name, item.name]));
  },
  { immediate: true },
);
function promptFor(name: string) {
  return clothes.value.find((item) => item.name === name)?.prompt ?? "";
}
function setPrompt(name: string, prompt: string) {
  const list = [...clothes.value];
  const index = list.findIndex((item) => item.name === name);
  if (index < 0) list.push({ name, prompt });
  else list[index] = { ...list[index], prompt };
  emit("update:modelValue", { ...props.modelValue, clothes: list });
}
function setVariant(name: string, variant: string) {
  const live2d = structuredClone(toRaw(props.modelValue.live2d));
  live2d.clothes_variants ??= {};
  if (variant) live2d.clothes_variants[name] = variant;
  else delete live2d.clothes_variants[name];
  emit("update:modelValue", { ...props.modelValue, live2d });
}
function create() {
  const name = newName.value.trim();
  if (!name || clothes.value.some((item) => item.name === name)) {
    error.value = name ? `已存在同名服装「${name}」` : "请输入服装名称";
    return;
  }
  error.value = "";
  newName.value = "";
  emit("update:modelValue", {
    ...props.modelValue,
    clothes: [...clothes.value, { name, prompt: "" }],
  });
}
function rename(oldName: string) {
  const name = (names.value[oldName] ?? "").trim();
  if (!name || name === oldName) return;
  const list = clothes.value.map((item) =>
    item.name === oldName ? { ...item, name } : item,
  );
  emit("update:modelValue", { ...props.modelValue, clothes: list });
  emit("changed", { oldName, newName: name });
}
async function remove(name: string) {
  if (
    !(await confirmDialog(
      "移除服装",
      `确定从草稿移除「${name}」及其触摸区域、Live2D 映射吗？点击底部保存更改后生效，取消编辑可放弃此次移除。`,
    ))
  )
    return;
  const list = clothes.value.filter((item) => item.name !== name);
  emit("update:modelValue", { ...props.modelValue, clothes: list });
  emit("changed", { oldName: name, newName: "default" });
}
</script>
