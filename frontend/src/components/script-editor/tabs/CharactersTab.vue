<script setup lang="ts">
/**
 * 角色 Tab（规格 E6b §4，对照原版 tabs/CharactersTab.vue 改写）。
 *
 * 技术栈差异（对齐智豪裁决：这里就是 LingChat 的角色卡管理，folder = 目录名）：
 * - 数据源是后端 `/api/editor/characters`（全局角色卡，settings.yml 全字段），
 *   不是原版的「剧本内角色」——原版那套走剧本 characters/ 目录 + 立绘复制，
 *   本项目的卡在角色卡目录里，摘要接口没有立绘/表情列表，缩略图给占位。
 * - 点一行展开整份 settings.yml（rawYaml）的文本编辑，保存走 PUT。
 * - 「从磁盘导入角色目录」是 Tauri 能力，网页版禁用并说明。
 */

import { computed, onMounted, ref } from "vue";
import { Button, Icon } from "../../base";
import { MenuPage, MenuItem } from "../../ui";
import { notify } from "../../../store/appStore";
import { confirmDialog } from "../../../store/dialogStore";
import {
  createCharacter,
  deleteCharacter,
  fetchCharacter,
  fetchCharacters,
  saveCharacter,
  type CharacterCardSummary,
} from "../../../api/characters";

const characters = ref<CharacterCardSummary[]>([]);
const loading = ref(true);

const load = async () => {
  loading.value = true;
  try {
    characters.value = await fetchCharacters();
  } catch (e) {
    notify(e instanceof Error ? e.message : "读取角色卡列表失败");
  } finally {
    loading.value = false;
  }
};

// ---- 展开编辑：一次只展开一张卡 ----

const expandedFolder = ref<string | null>(null);
const rawYaml = ref("");
const editing = ref(false); // 详情请求 / 保存请求进行中
const dirty = ref(false);

const editTitle = computed(() =>
  characters.value.find((c) => c.folder === expandedFolder.value)?.aiName ?? "",
);

const openEditor = async (folder: string) => {
  if (expandedFolder.value === folder) {
    expandedFolder.value = null;
    return;
  }
  expandedFolder.value = folder;
  rawYaml.value = "";
  dirty.value = false;
  editing.value = true;
  try {
    const card = await fetchCharacter(folder);
    rawYaml.value = card.rawYaml ?? "";
  } catch (e) {
    notify(e instanceof Error ? e.message : "读取角色卡详情失败");
    expandedFolder.value = null;
  } finally {
    editing.value = false;
  }
};

const saveEditing = async () => {
  if (!expandedFolder.value) return;
  editing.value = true;
  try {
    await saveCharacter(expandedFolder.value, { rawYaml: rawYaml.value });
    dirty.value = false;
    notify(`角色「${editTitle.value}」已保存`);
    await load();
  } catch (e) {
    notify(e instanceof Error ? e.message : "保存角色卡失败");
  } finally {
    editing.value = false;
  }
};

// ---- 新建 ----

const creating = ref(false);
const newFolder = ref("");

const submitNew = async () => {
  const folder = newFolder.value.trim();
  if (!folder) return;
  try {
    await createCharacter(folder);
    notify(`角色卡「${folder}」已创建`);
    creating.value = false;
    newFolder.value = "";
    await load();
  } catch (e) {
    notify(e instanceof Error ? e.message : "新建角色卡失败");
  }
};

// ---- 删除 ----

const removeCard = async (c: CharacterCardSummary) => {
  const ok = await confirmDialog("删除角色", `确定删除角色「${c.aiName}」吗？整个目录会被移除。`);
  if (!ok) return;
  try {
    await deleteCharacter(c.folder);
    notify(`角色「${c.aiName}」已删除`);
    if (expandedFolder.value === c.folder) expandedFolder.value = null;
    await load();
  } catch (e) {
    notify(e instanceof Error ? e.message : "删除角色卡失败");
  }
};

onMounted(() => void load());
</script>

<template>
  <MenuPage>
    <MenuItem title="角色卡">
      <template #header>
        <Icon icon="character" :size="20" />
      </template>

      <p
        class="[&_code]:text-brand mb-[0.9rem] rounded-xl border border-white/10 bg-black/16 px-[0.85rem] py-[0.7rem] text-[0.76rem] leading-[1.85] text-white/60 [&_b]:font-semibold [&_b]:text-white/85 [&_code]:font-mono"
        v-html="'这里管理<b class=&quot;font-semibold text-white/85&quot;>全局角色卡</b>：一张卡就是角色目录里的 <code>folder/settings.yml</code>，目录名即 <code>folder</code>。点击一行可查看 / 修改整份 settings.yml。'"
      ></p>

      <p v-if="loading" class="py-8 text-center text-[0.85rem] text-white/45">加载中…</p>
      <p v-else-if="characters.length === 0" class="py-8 text-center text-[0.85rem] text-white/45">
        还没有任何角色卡，点下方新建
      </p>

      <div v-for="c in characters" :key="c.folder">
        <div
          class="group hover:border-brand/50 mb-2 flex w-full cursor-pointer items-center rounded-[10px] border border-white/10 bg-white/6 px-[13px] py-[11px] transition-all duration-200 hover:bg-white/10"
          :class="{ 'border-brand/50 bg-white/10': expandedFolder === c.folder }"
          title="点这一行查看 / 修改人设"
          @click="openEditor(c.folder)"
        >
          <!-- 立绘缩略图：摘要接口没有立绘信息，一律给原版同款占位 -->
          <div class="char-thumb border-brand/35 h-11 w-11 shrink-0 overflow-hidden rounded-full border-[1.5px]">
            <span class="flex h-full w-full items-center justify-center text-[0.56rem] text-white/35">
              无立绘
            </span>
          </div>
          <div class="flex min-w-0 flex-1 flex-col gap-0.5">
            <div class="flex items-baseline gap-2">
              <span class="font-semibold text-white">{{ c.aiName }}</span>
              <code class="text-brand font-mono">character: {{ c.folder }}</code>
              <span class="ml-auto text-xs text-white/40">{{ c.avatarMode }}</span>
            </div>
            <p v-if="c.aiSubtitle" class="mt-1 text-xs text-white/40">{{ c.aiSubtitle }}</p>
          </div>
          <button
            class="shrink-0 rounded px-[5px] text-[11px] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-red-400/15 hover:text-red-300"
            title="删除角色（移到 .trash/）"
            @click.stop="removeCard(c)"
          >
            ✕
          </button>
        </div>

        <!-- 展开的 rawYaml 编辑区 -->
        <div
          v-if="expandedFolder === c.folder"
          class="mb-3 rounded-xl border border-white/10 bg-black/20 p-3"
        >
          <div class="mb-2 flex items-center gap-2">
            <span class="text-[0.78rem] font-semibold text-white">
              编辑 settings.yml —— {{ editTitle }}
            </span>
            <button
              class="ml-auto inline-flex items-center gap-1 rounded-lg border border-brand/45 bg-brand/14 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-brand transition-all duration-200 hover:bg-brand/24 disabled:cursor-not-allowed disabled:opacity-40"
              :disabled="editing || !dirty"
              @click="saveEditing"
            >
              保存
            </button>
          </div>
          <p v-if="editing" class="py-2 text-center text-[0.78rem] text-white/45">加载中…</p>
          <textarea
            v-else
            v-model="rawYaml"
            class="glass-input h-72 w-full resize-y font-mono text-[0.78rem] leading-[1.7]"
            spellcheck="false"
            @input="dirty = true"
          ></textarea>
          <p class="mt-1 text-[0.7rem] text-white/35">
            保存前会整份写回 settings.yml，YAML 语法错误会让这张卡无法加载，改完记得跑一遍校验。
          </p>
        </div>
      </div>

      <!-- 新建：inline 输入目录名（网页版没有 Tauri 的文件夹对话框） -->
      <div v-if="creating" class="mt-4 flex flex-wrap items-center gap-2">
        <input
          v-model="newFolder"
          class="glass-input w-56 px-2.5 py-1.5 text-[0.8rem]"
          placeholder="角色目录名（如 风雪）"
          @keydown.enter="submitNew"
        />
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-brand/45 bg-brand/14 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-brand transition-all duration-200 hover:bg-brand/24 disabled:cursor-not-allowed disabled:opacity-40"
          :disabled="!newFolder.trim()"
          @click="submitNew"
        >
          创建
        </button>
        <button
          class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:bg-white/[0.12] hover:text-white"
          @click="creating = false"
        >
          取消
        </button>
      </div>

      <div class="mt-4 flex flex-wrap gap-2">
        <Button type="big" @click="creating = !creating">＋ 新建角色</Button>
        <Button
          type="big"
          disabled
          title="网页版暂不支持从磁盘导入角色目录"
          @click="() => {}"
        >
          ↓ 从全局角色库导入
        </Button>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<style scoped>
/* 棋盘底纹：透明图片不至于糊成一片黑 */
.char-thumb {
  background: repeating-conic-gradient(rgba(255, 255, 255, 0.08) 0% 25%, transparent 0% 50%) 0 0 /
    10px 10px;
}
</style>
