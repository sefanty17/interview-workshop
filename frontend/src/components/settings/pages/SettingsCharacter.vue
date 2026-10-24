<template>
  <!-- 单一根节点：SettingsPanel 会往根元素上挂 absolute inset-0 / overflow-y-auto，
       根节点若变成 fragment（v-if 兄弟节点）这些类会丢失，页面被挤到视口外 -->
  <div class="h-full min-h-0">
    <!-- 角色详情 / 编辑大页：齿轮按钮与「详情」按钮都进这里，整页替换掉角色列表（原版同为容器替换） -->
    <SettingsCharacterInfo
      v-if="infoTarget"
      :role-id="infoTarget.code"
      :title="infoTarget.cardTitle"
      @close="infoTarget = null"
      @saved="handleInfoSaved"
    />

    <MenuPage v-else class="lc-reset-radius">
    <MenuItem title="角色列表（切换角色会开始全新对话）">
      <template #header>
        <Rabbit :size="20" />
      </template>

      <div class="grid w-full grid-cols-1 gap-5 p-3.75 md:grid-cols-2">
        <CharacterCard
          v-for="character in pageCharacters"
          :key="character.code"
          :id="character.code"
          :avatar="avatarUrl(character.code, '正常')"
          :name="character.name"
          :title="character.cardTitle"
          :sub-name="character.displayRole"
          :info="character.cardInfo"
          :clothes="[]"
          resource-folder=""
          source="game"
          :favored="favoredIds.includes(character.code)"
          :selected="store.settings.personaId === character.code"
          @select="handleSelect"
          @settings="openSettings"
          @detail="openSettings"
          @favoredChange="handleFavoriteChange"
        />
        <!-- 新建卡与角色卡同一网格流式排列（跟在最后一张角色卡后面），min-h 与卡片一致保证同高 -->
        <button
          v-if="currentPage === totalPages"
          type="button"
          class="group flex min-h-[190px] flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed border-white/20 bg-white/5 p-4 text-white/60 transition-all duration-300 hover:-translate-y-1 hover:border-brand hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-brand"
          @click="newCharacterVisible = true"
        >
          <span
            class="flex h-16 w-16 items-center justify-center rounded-full bg-white/5 transition-colors group-hover:bg-white/10"
            aria-hidden="true"
          >
            <Plus :size="32" />
          </span>
          <span class="text-lg font-semibold">新建角色</span>
        </button>
      </div>

      <div v-if="totalPages > 1" class="flex w-full items-center justify-between px-3 py-2">
        <button
          class="cursor-pointer rounded-lg border-none bg-[#e9ecef] px-4 py-1.5 text-sm font-medium text-[#495057] transition-all duration-200 hover:-translate-y-0.5 hover:bg-brand hover:text-white hover:shadow-[0_4px_10px_rgba(121,217,255,0.4)] disabled:cursor-not-allowed disabled:opacity-40"
          :disabled="currentPage <= 1"
          @click="currentPage -= 1"
        >
          上一页
        </button>
        <span class="text-sm font-medium text-white/80">第 {{ currentPage }} / {{ totalPages }} 页</span>
        <button
          class="cursor-pointer rounded-lg border-none bg-[#e9ecef] px-4 py-1.5 text-sm font-medium text-[#495057] transition-all duration-200 hover:-translate-y-0.5 hover:bg-brand hover:text-white hover:shadow-[0_4px_10px_rgba(121,217,255,0.4)] disabled:cursor-not-allowed disabled:opacity-40"
          :disabled="currentPage >= totalPages"
          @click="currentPage += 1"
        >
          下一页
        </button>
      </div>
    </MenuItem>

    <RoleArchiveProgress />

    <!-- 我的身份：换身份 = 开一段新对话（本局已开始则不能原地换） -->
    <MenuItem title="我的身份">
      <template #header>
        <Icon icon="character" :size="20" />
      </template>

      <div class="space-y-3">
        <p class="text-xs leading-relaxed text-white/50">
          决定「我」是谁：名字、副标题、注入聊天的身份提示词，以及和各角色的关系。可以随时切换身份，对话历史保留（剧本进行中除外）。
        </p>

        <!-- 锁定原因 -->
        <p v-if="identityScriptBlocked" class="text-xs leading-relaxed text-red-300/80">
          剧本进行中无法更换身份，请先结束剧情。
        </p>

        <div v-if="identityLoading" class="text-sm text-white/50">加载中...</div>

        <p v-else-if="identities.length === 0" class="text-sm text-white/50">
          还没有身份卡。点下面的按钮新建一张；不新建也能用默认身份。
        </p>

        <div v-else class="flex flex-col gap-2">
          <div
            v-for="item in identities"
            :key="item.id"
            class="flex items-start justify-between gap-3 rounded-xl border border-white/10 bg-black/20 px-3 py-2"
          >
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="truncate text-sm font-medium text-white">{{ item.name }}</span>
                <span v-if="item.subtitle" class="truncate text-xs text-white/50">{{
                  item.subtitle
                }}</span>
                <span
                  v-if="item.is_current"
                  class="shrink-0 rounded-full bg-[#79d9ff] px-2 py-0.5 text-[10px] text-white"
                  >当前使用</span
                >
              </div>
              <p v-if="item.prompt" class="mt-0.5 line-clamp-2 text-xs text-white/40">
                {{ item.prompt }}
              </p>
              <p v-if="item.relation_count > 0" class="mt-0.5 text-[11px] text-white/35">
                {{ item.relation_count }} 条关系
              </p>
            </div>

            <div class="flex shrink-0 flex-wrap items-center justify-end gap-1.5">
              <button
                v-if="!item.is_current"
                class="btn-sm"
                :disabled="identityScriptBlocked"
                :title="identityScriptBlocked ? '剧本进行中无法更换身份，请先结束剧情。' : ''"
                @click="switchIdentity(item)"
              >
                切换身份
              </button>
              <button
                v-if="!item.is_current"
                class="btn-sm"
                :disabled="identityScriptBlocked"
                :title="identityScriptBlocked ? '剧本进行中无法更换身份，请先结束剧情。' : ''"
                @click="startNewGame(item)"
              >
                用它开新对话
              </button>
              <button class="btn-sm" @click="editIdentity(item.id)">编辑</button>
              <button class="btn-sm" @click="removeIdentity(item)">删除</button>
            </div>
          </div>
        </div>

        <button class="btn-sm" @click="startCreateIdentity">新建身份</button>

        <!-- 内联编辑表单 -->
        <div v-if="identityForm" class="space-y-3 rounded-xl border border-white/10 bg-black/20 p-3">
          <div class="flex flex-col gap-1.5">
            <label class="text-xs font-medium text-white/60">名字</label>
            <input v-model="identityForm.name" class="glass-input" />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-xs font-medium text-white/60">副标题</label>
            <input v-model="identityForm.subtitle" class="glass-input" />
          </div>
          <div class="flex flex-col gap-1.5">
            <label class="text-xs font-medium text-white/60">身份提示词</label>
            <textarea v-model="identityForm.prompt" rows="4" class="glass-input"></textarea>
            <p class="text-[11px] leading-relaxed text-white/40">这份设定对所有角色公开、都看得到</p>
          </div>

          <!-- 关系：我和各个 AI 角色 / 我的其他身份 -->
          <div id="relation-block-identity" class="flex flex-col gap-2">
            <label class="text-xs font-medium text-white/60">关系</label>
            <p class="text-[11px] leading-relaxed text-white/40">
              这里填的是「我」和各方的初始关系（例如「青梅竹马」「宿敌」）。游戏中的变化记录在本局存档里，不会改动身份卡。
            </p>
            <div v-for="(row, idx) in relationRows" :key="idx" class="flex items-center gap-2">
              <select v-model="row.target" class="glass-input w-40 shrink-0 cursor-pointer text-sm">
                <option
                  v-for="option in relationOptionsFor(row)"
                  :key="option.value"
                  :value="option.value"
                  :disabled="option.disabled"
                  class="bg-[#16202c] text-white"
                >
                  {{ option.group ? `${option.group} · ${option.label}` : option.label }}
                </option>
              </select>
              <input v-model="row.text" class="glass-input flex-1" />
              <button class="btn-sm" @click="removeRelationRow(idx)">×</button>
            </div>
            <button class="btn-sm" @click="addRelationRow">+ 添加关系</button>
          </div>

          <div class="flex justify-end gap-2 pt-1">
            <button class="btn-sm" :disabled="savingIdentity" @click="identityForm = null">取消</button>
            <button class="btn-sm" :disabled="savingIdentity" @click="submitIdentity">
              {{ savingIdentity ? "加载中..." : "保存" }}
            </button>
          </div>
        </div>
      </div>
    </MenuItem>

    <NewCharacterModal
      :visible="newCharacterVisible"
      @close="newCharacterVisible = false"
      @created="handleCreated"
    />

    <!-- 齿轮 / 详情 → 角色详情编辑大页（见 SettingsCharacterInfo），取代原先的 LcModal 占位表单 -->
  </MenuPage>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Plus, Rabbit } from "@lucide/vue";
import { Icon } from "../../base";
import { MenuItem, MenuPage } from "../../ui";
import CharacterCard from "../../ui/Menu/CharacterCard.vue";
import RoleArchiveProgress from "../../ui/RoleArchiveProgress.vue";
import NewCharacterModal from "../character/NewCharacterModal.vue";
import SettingsCharacterInfo from "./SettingsCharacterInfo.vue";
import { avatarUrl, findInterviewer, interviewers, type Interviewer } from "../../../data/interviewers";
import { clearSession, notify, setPersona, store } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import {
  aiKey,
  deletePlayerIdentity,
  emptyPlayerIdentity,
  getPlayerIdentity,
  listPlayerIdentities,
  meKey,
  savePlayerIdentity,
  setCurrentPlayerIdentity,
  startNewGameWithIdentity,
  type PlayerIdentity,
  type PlayerIdentitySummary,
} from "../../../api/playerIdentity";
import {
  useRelationEditor,
  type RelationTargetOption,
} from "../../../composables/useRelationEditor";

const FAVORED_KEY = "interview-lab-favored";
/** 2 列 × 2 行；4 个角色正好一页，分页条不出现（与截图一致） */
const PAGE_SIZE = 4;

const currentPage = ref(1);

// ========== 收藏（localStorage: interview-lab-favored） ==========
function readFavored(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(FAVORED_KEY) ?? "[]");
    return Array.isArray(raw) ? raw.filter((id): id is string => typeof id === "string") : [];
  } catch {
    return [];
  }
}

const favoredIds = ref<string[]>(readFavored());

/** 原版 applyCharacterFavoriteOrder：收藏的排最前，其余保持原顺序 */
const orderedCharacters = computed<Interviewer[]>(() => {
  const favored = favoredIds.value
    .map((id) => interviewers.find((item) => item.code === id))
    .filter((item): item is Interviewer => Boolean(item));
  const favoredSet = new Set(favoredIds.value);
  return [...favored, ...interviewers.filter((item) => !favoredSet.has(item.code))];
});

const totalPages = computed(() => Math.max(1, Math.ceil(orderedCharacters.value.length / PAGE_SIZE)));

const pageCharacters = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE;
  return orderedCharacters.value.slice(start, start + PAGE_SIZE);
});

function handleFavoriteChange(id: string) {
  favoredIds.value = favoredIds.value.includes(id)
    ? favoredIds.value.filter((item) => item !== id)
    : [...favoredIds.value, id];
  localStorage.setItem(FAVORED_KEY, JSON.stringify(favoredIds.value));
  // 重排后可能换页，把页码拉回有效范围
  currentPage.value = Math.min(currentPage.value, totalPages.value);
}

// ========== 选中（写 store.settings.personaId，即 localStorage interview-lab-v1） ==========
function handleSelect(id: string) {
  const persona = findInterviewer(id);
  setPersona(persona.code);
  notify(`已选择面试官：${persona.name} · ${persona.displayRole}`);
}

// ========== 角色详情 / 编辑大页 ==========
const infoTarget = ref<Interviewer | null>(null);

function openSettings(id: string) {
  infoTarget.value = findInterviewer(id);
}

function handleInfoSaved() {
  // 角色名等字段由 SettingsCharacterInfo 写入 localStorage 覆盖层；列表读到的是静态数据，
  // 这里只在角色卡需要刷新时留一个钩子（当前列表仍展示静态底板数据）
  infoTarget.value = null;
}

// ========== 新建角色 ==========
const newCharacterVisible = ref(false);

function handleCreated(name: string) {
  notify(`演示版暂未开放新建角色：${name}`);
}

// ========== 我的身份（后端 REST：/api/player-identities） ==========
const identities = ref<PlayerIdentitySummary[]>([]);
const identityLoading = ref(false);
const savingIdentity = ref(false);
const identityForm = ref<PlayerIdentity | null>(null);

/** 关系目标：其他 AI 角色（AI 侧）+ 我的其他身份（我侧） */
const relationTargets = computed<RelationTargetOption[]>(() => [
  ...interviewers.map((c) => ({
    value: aiKey(c.code),
    label: c.name,
    group: "AI 角色",
  })),
  ...identities.value
    .filter((i) => i.id !== identityForm.value?.id)
    .map((i) => ({
      value: meKey(i.id),
      label: i.name,
      group: "我的身份",
    })),
]);

const {
  rows: relationRows,
  syncRows: syncRelationRows,
  addRow,
  removeRow: removeRelationRow,
  fold: foldRelationRows,
  optionsFor: relationOptionsFor,
} = useRelationEditor(relationTargets);

const addRelationRow = () => {
  addRow(relationTargets.value[0]?.value ?? "");
};

/** 剧本进行中不允许换身份；权威判断在后端 guard，这里只是提前禁用按钮。 */
const identityScriptBlocked = computed(
  () => store.session.active && store.session.mode === "script",
);

const loadIdentities = async (): Promise<void> => {
  identityLoading.value = true;
  try {
    identities.value = await listPlayerIdentities();
  } catch (e) {
    console.error("获取身份列表失败:", e);
    identities.value = [];
  } finally {
    identityLoading.value = false;
  }
};

const startCreateIdentity = () => {
  const form = emptyPlayerIdentity();
  identityForm.value = form;
  syncRelationRows(form.relations);
};

const editIdentity = async (id: string) => {
  try {
    const identity = await getPlayerIdentity(id);
    if (!identity) return;
    identityForm.value = identity;
    syncRelationRows(identity.relations);
  } catch (e) {
    console.error("读取身份失败:", e);
  }
};

const submitIdentity = async () => {
  const form = identityForm.value;
  if (!form) return;
  if (!form.name.trim()) {
    notify("请填写名字");
    return;
  }

  const relations = foldRelationRows();

  savingIdentity.value = true;
  try {
    await savePlayerIdentity({ ...form, relations });
    identityForm.value = null;
    await loadIdentities();
    notify("已保存身份");
  } catch (e) {
    void alertDialog("保存身份失败", e instanceof Error ? e.message : String(e));
  } finally {
    savingIdentity.value = false;
  }
};

/** 换身份后的收尾：刷新身份列表 + 清掉当前会话（本项目没有多存档） */
const finishIdentityChange = async (name: string) => {
  clearSession();
  await loadIdentities();
  notify(`已切换到身份：${name}`);
};

const startNewGame = async (item: PlayerIdentitySummary) => {
  if (!(await confirmDialog("开新对话", `用「${item.name}」开始一段全新对话？`))) return;
  try {
    await startNewGameWithIdentity(item.id);
    await finishIdentityChange(item.name);
  } catch (e) {
    void alertDialog("开新对话失败", e instanceof Error ? e.message : String(e));
  }
};

const switchIdentity = async (item: PlayerIdentitySummary) => {
  if (!(await confirmDialog("切换身份", `切换为「${item.name}」？对话历史会保留。`))) return;
  try {
    await setCurrentPlayerIdentity(item.id);
    await finishIdentityChange(item.name);
  } catch (e) {
    void alertDialog("切换身份失败", e instanceof Error ? e.message : String(e));
  }
};

const removeIdentity = async (item: PlayerIdentitySummary) => {
  if (!(await confirmDialog("删除身份", `确定删除身份「${item.name}」吗？`))) return;
  try {
    await deletePlayerIdentity(item.id);
    await loadIdentities();
  } catch (e) {
    // 409 的 message 就是后端中文原因（被引用 / 当前身份），原样展示
    void alertDialog("删除身份失败", e instanceof Error ? e.message : String(e));
  }
};

onMounted(() => {
  void loadIdentities();
});
</script>

<style scoped>
/* 原版 SettingsPanel 会给整个设置面板重置 Tailwind 圆角 token；本项目
   tokens.css 把 --radius-lg 改成 22px、--radius-sm 改成 10px（全局 css 禁改），
   这里只在角色页容器内锁回 Tailwind 默认值，让照抄来的 rounded-lg=8px / rounded-sm=4px 生效 */
.lc-reset-radius {
  --radius-sm: 0.25rem;
  --radius-lg: 0.5rem;
  --radius: 0.25rem;
}
</style>
