<template>
  <div class="settings-text-container">
    <MenuPage>
      <!-- ========== 语料文档 ========== -->
      <MenuItem title="语料文档">
        <template #header>
          <Database :size="20" />
        </template>

        <div class="flex w-full flex-col gap-4">
          <div class="flex items-center justify-between gap-3">
            <p class="text-[11px] text-white/40">
              已导入 {{ docs.length }} 篇 / {{ chunkTotal }} 片段 / 向量模型 {{ modelLabel }}
            </p>
            <button class="btn-sm shrink-0" :disabled="loading" @click="load">
              {{ loading ? "刷新中..." : "刷新" }}
            </button>
          </div>

          <div v-if="loading && docs.length === 0" class="text-xs text-white/40">加载中...</div>

          <div v-else-if="docs.length > 0" class="flex flex-col gap-2">
            <div
              v-for="doc in docs"
              :key="doc.code"
              class="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-3"
            >
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm text-white/85">{{ doc.title }}</p>
                <span class="text-[11px] text-white/35"
                  >{{ doc.code }} · {{ doc.category || "未分类" }} · {{ doc.chunkCount }} 片段 ·
                  向量 {{ doc.vectorCount }}</span
                >
              </div>
              <button
                class="btn-sm shrink-0"
                :disabled="busyCode === doc.code"
                @click="reindex(doc.code)"
              >
                重新索引
              </button>
              <button
                class="btn-sm shrink-0"
                :disabled="busyCode === doc.code"
                @click="removeDoc(doc)"
              >
                删除
              </button>
            </div>
          </div>

          <p v-else class="text-xs text-white/40">
            还没有语料。用下面的上传区导入一份 markdown，或运行 scripts/import_knowledge.py。
          </p>
        </div>
      </MenuItem>

      <!-- ========== 上传 Markdown ========== -->
      <MenuItem title="上传 Markdown">
        <template #header>
          <Upload :size="20" />
        </template>

        <div class="flex w-full flex-col gap-3">
          <input v-model="form.code" class="glass-input" placeholder="如 java-jvm-memory，唯一标识" />
          <input v-model="form.title" class="glass-input" placeholder="文档标题" />
          <input v-model="form.category" class="glass-input" placeholder="岗位编码：java / frontend" />
          <input
            ref="fileInput"
            type="file"
            accept=".md,.markdown,text/markdown"
            class="text-xs text-white/60"
            @change="onFileChange"
          />
          <div>
            <button class="btn-sm" :disabled="!canUpload" @click="submit">
              {{ uploading ? "上传中..." : "上传并切分" }}
            </button>
          </div>
          <p class="text-[11px] leading-relaxed text-white/40">
            切分规则与导入脚本一致（按 # / ## 切，超 900 字二次切）；内容没变的片段不会重算向量。
          </p>
        </div>
      </MenuItem>

      <!-- ========== 试搜 ========== -->
      <MenuItem title="试搜">
        <template #header>
          <Search :size="20" />
        </template>

        <div class="flex w-full flex-col gap-3">
          <div class="flex items-center gap-2">
            <input
              v-model="query"
              class="glass-input flex-1"
              placeholder="输入一个问题，看看会命中哪些片段"
              @keydown.enter="runSearch"
            />
            <select v-model="mode" class="glass-input w-32 cursor-pointer">
              <option value="hybrid">hybrid</option>
              <option value="bm25">bm25</option>
              <option value="vector">vector</option>
            </select>
            <button class="btn-sm shrink-0" :disabled="searching || !query.trim()" @click="runSearch">
              {{ searching ? "检索中..." : "检索" }}
            </button>
          </div>

          <p v-if="result" class="text-[11px] text-white/40">
            检索范围 {{ result.scopeSize }} 个片段
          </p>

          <div v-if="result && result.hits.length > 0" class="flex flex-col gap-2">
            <div
              v-for="hit in result.hits"
              :key="hit.chunkId"
              class="rounded-xl border border-white/10 bg-black/20 p-3"
            >
              <p class="text-[11px] text-white/35">{{ hit.docCode }} · {{ hit.docTitle }}</p>
              <p class="text-sm text-white/85">{{ hit.heading }}</p>
              <p class="line-clamp-3 text-[12px] leading-relaxed text-white/50">{{ hit.text }}</p>
              <span class="text-[11px] text-white/35"
                >RRF {{ hit.score.toFixed(4) }} · 向量 {{ hit.vectorScore.toFixed(3) }} · BM25
                {{ hit.bm25Score.toFixed(2) }}</span
              >
            </div>
          </div>

          <p v-else-if="result" class="text-xs text-white/40">
            没有命中（低于阈值就不注入，这是设计如此）。
          </p>
        </div>
      </MenuItem>
    </MenuPage>
  </div>
</template>

<script setup lang="ts">
/**
 * 知识库设置页（K5）：文档列表 / 上传 markdown / 试搜。
 *
 * 三个区块各占一个 MenuItem，排版沿用 SettingsText.vue 的容器 + 分组写法；
 * 视觉只用项目已有的 btn-sm / glass-input / 玻璃卡片，不引入新色值。
 */
import { computed, onMounted, reactive, ref } from "vue";
import { Database, Search, Upload } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { notify } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import {
  deleteKnowledgeDoc,
  listKnowledgeDocs,
  reindexDoc,
  searchKnowledge,
  uploadMarkdown,
  type KnowledgeDocView,
  type KnowledgeSearchResult,
} from "../../../api/knowledge";

// ---------- 语料文档 ----------

const docs = ref<KnowledgeDocView[]>([]);
const loading = ref(false);
/** 正在重建/删除的文档 code（同一时刻只允许一个文档操作） */
const busyCode = ref("");

const chunkTotal = computed(() => docs.value.reduce((sum, doc) => sum + doc.chunkCount, 0));
const modelLabel = computed(() => docs.value.find((doc) => doc.model)?.model ?? "—");

const load = async () => {
  loading.value = true;
  try {
    docs.value = await listKnowledgeDocs();
  } catch (e) {
    docs.value = [];
    void alertDialog("读取知识库失败", e instanceof Error ? e.message : String(e));
  } finally {
    loading.value = false;
  }
};

const reindex = async (code: string) => {
  if (busyCode.value) return;
  busyCode.value = code;
  try {
    const result = await reindexDoc(code);
    notify(`已重建 ${result.count} 个向量`);
    await load();
  } catch (e) {
    void alertDialog("重建向量失败", e instanceof Error ? e.message : String(e));
  } finally {
    busyCode.value = "";
  }
};

const removeDoc = async (doc: KnowledgeDocView) => {
  if (busyCode.value) return;
  const ok = await confirmDialog(
    "删除语料文档",
    `确定删除「${doc.title}」吗？该文档的片段与向量会一并清理，挂载了它的考官将不再检索到这份语料。`,
  );
  if (!ok) return;
  busyCode.value = doc.code;
  try {
    await deleteKnowledgeDoc(doc.code);
    notify(`已删除 ${doc.title}`);
    await load();
  } catch (e) {
    void alertDialog("删除文档失败", e instanceof Error ? e.message : String(e));
  } finally {
    busyCode.value = "";
  }
};

// ---------- 上传 Markdown ----------

const form = reactive({ code: "", title: "", category: "" });
const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);

const canUpload = computed(
  () => Boolean(file.value) && form.code.trim() !== "" && !uploading.value,
);

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  file.value = input.files?.[0] ?? null;
}

const submit = async () => {
  const picked = file.value;
  if (!picked || !form.code.trim() || uploading.value) return;
  uploading.value = true;
  try {
    const markdown = await picked.text();
    const result = await uploadMarkdown(form.code.trim(), {
      title: form.title.trim() || undefined,
      category: form.category.trim() || undefined,
      sourcePath: picked.name,
      markdown,
    });
    notify(`已导入 ${result.count} 片段（新增 ${result.inserted} / 复用 ${result.reused}）`);
    // 清空表单：文件框的值只能直接改 DOM 才能重置
    form.code = "";
    form.title = "";
    form.category = "";
    file.value = null;
    if (fileInput.value) fileInput.value.value = "";
    await load();
  } catch (e) {
    void alertDialog("导入失败", e instanceof Error ? e.message : String(e));
  } finally {
    uploading.value = false;
  }
};

// ---------- 试搜 ----------

const query = ref("");
const mode = ref<"hybrid" | "bm25" | "vector">("hybrid");
const searching = ref(false);
const result = ref<KnowledgeSearchResult | null>(null);

const runSearch = async () => {
  const q = query.value.trim();
  if (!q || searching.value) return;
  searching.value = true;
  try {
    result.value = await searchKnowledge(q, { mode: mode.value });
  } catch (e) {
    result.value = null;
    void alertDialog("检索失败", e instanceof Error ? e.message : String(e));
  } finally {
    searching.value = false;
  }
};

onMounted(() => {
  void load();
});
</script>

<style scoped>
.settings-text-container {
  position: relative;
  width: 100%;
  height: 100%;
}
</style>
