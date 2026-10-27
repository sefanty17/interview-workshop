<template>
  <div class="space-y-3 rounded-xl border border-white/10 bg-black/20 p-4">
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0">
        <h3 class="text-sm font-bold text-white/80">岗位知识</h3>
        <p class="mt-1 text-[11px] leading-relaxed text-white/40">
          勾选的文档会在面试时作为该考官的提问依据（RAG 检索后按轮注入）。
        </p>
      </div>
      <button class="btn-sm shrink-0" :disabled="saving || loading" @click="save">
        {{ saving ? "保存中..." : "保存勾选" }}
      </button>
    </div>

    <div v-if="loading" class="text-xs text-white/40">加载中...</div>

    <template v-else-if="groups.length > 0">
      <div v-for="group in groups" :key="group.category" class="space-y-1.5">
        <p class="text-[11px] text-white/40">{{ group.category }}</p>
        <label
          v-for="doc in group.docs"
          :key="doc.code"
          class="flex items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 hover:bg-white/10"
        >
          <input v-model="checked" type="checkbox" :value="doc.code" class="shrink-0 cursor-pointer" />
          <span class="min-w-0 truncate">{{ doc.title }}</span>
          <span class="shrink-0 text-[11px] text-white/35">{{ doc.code }}</span>
          <span class="ml-auto shrink-0 text-[11px] text-white/35">{{ doc.chunkCount }} 片段</span>
        </label>
      </div>
    </template>

    <p v-else class="text-xs text-white/40">
      还没有导入任何文档，先到设置 → 知识库页导入。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  getRoleKnowledge,
  listKnowledgeDocs,
  saveRoleKnowledge,
  type KnowledgeDocView,
} from "../../../../api/knowledge";
import { notify } from "../../../../store/appStore";
import { alertDialog } from "../../../../store/dialogStore";

const props = defineProps<{
  /** 角色 code（如 strict）；后端 interviewers/{code}/knowledge 按它存 */
  interviewerCode: string;
}>();

const docs = ref<KnowledgeDocView[]>([]);
const checked = ref<string[]>([]);
const loading = ref(false);
const saving = ref(false);

/** 按 category 分组；空分类归到「未分类」并排在最后。 */
const groups = computed(() => {
  const map = new Map<string, { category: string; docs: KnowledgeDocView[] }>();
  for (const doc of docs.value) {
    const key = doc.category?.trim() || "未分类";
    const group = map.get(key) ?? { category: key, docs: [] };
    group.docs.push(doc);
    map.set(key, group);
  }
  return [...map.values()].sort((a, b) => {
    if (a.category === "未分类") return 1;
    if (b.category === "未分类") return -1;
    return a.category.localeCompare(b.category);
  });
});

// 换角色时旧请求可能后返回，用序号丢弃过期结果（同 RelationEditor 的写法）
let loadSeq = 0;

const load = async (interviewerCode: string) => {
  const seq = ++loadSeq;
  loading.value = true;
  try {
    const [allDocs, selected] = await Promise.all([
      listKnowledgeDocs(),
      getRoleKnowledge(interviewerCode),
    ]);
    if (seq !== loadSeq) return;
    docs.value = allDocs;
    checked.value = selected;
  } catch (e) {
    if (seq !== loadSeq) return;
    console.error("读取岗位知识失败:", e);
    void alertDialog("读取岗位知识失败", e instanceof Error ? e.message : String(e));
    docs.value = [];
    checked.value = [];
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
};

const save = async () => {
  if (saving.value) return;
  saving.value = true;
  try {
    await saveRoleKnowledge(props.interviewerCode, checked.value);
    notify("已保存岗位知识");
  } catch (e) {
    void alertDialog("保存岗位知识失败", e instanceof Error ? e.message : String(e));
  } finally {
    saving.value = false;
  }
};

watch(
  () => props.interviewerCode,
  (code) => {
    if (code) void load(code);
  },
  { immediate: true },
);
</script>
