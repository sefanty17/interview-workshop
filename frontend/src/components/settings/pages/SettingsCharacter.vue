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

    <!-- 我们独有的：我的身份（LingChat 该版本没有，业务需要） -->
    <MenuItem title="我的身份">
      <template #header>
        <Icon icon="character" :size="20" />
      </template>

      <div class="rounded-2xl border border-white/20 bg-white/10 p-4 backdrop-blur-xl">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-base font-bold text-white">候选人 · {{ profile.name }}</span>
            <span class="rounded-full bg-white/10 px-2.5 py-0.5 text-xs text-white/70">
              {{ profile.subtitle }}
            </span>
            <span class="rounded-full bg-[rgba(121,217,255,0.15)] px-2.5 py-0.5 text-xs text-[#79d9ff]">
              当前身份
            </span>
          </div>
          <div class="flex gap-2">
            <button
              class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-white/20"
              @click="openProfileEditor"
            >
              编辑
            </button>
            <button
              class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-white/20"
              @click="handleDeleteProfile"
            >
              删除
            </button>
          </div>
        </div>
        <p class="mt-2 text-sm leading-relaxed text-white/60">{{ profile.prompt }}</p>
      </div>
    </MenuItem>

    <NewCharacterModal
      :visible="newCharacterVisible"
      @close="newCharacterVisible = false"
      @created="handleCreated"
    />

    <!-- 齿轮 / 详情 → 角色详情编辑大页（见 SettingsCharacterInfo），取代原先的 LcModal 占位表单 -->

    <!-- 我的身份：编辑表单 -->
    <LcModal
      :show="profileEditorVisible"
      title="编辑我的身份"
      width="32rem"
      @close="profileEditorVisible = false"
    >
      <label class="block space-y-2">
        <span class="text-sm text-white/70">名字</span>
        <input v-model="profileDraft.name" class="glass-input" maxlength="40" />
      </label>
      <label class="block space-y-2">
        <span class="text-sm text-white/70">副标题</span>
        <input v-model="profileDraft.subtitle" class="glass-input" maxlength="60" />
      </label>
      <label class="block space-y-2">
        <span class="text-sm text-white/70">身份提示词</span>
        <textarea v-model="profileDraft.prompt" class="glass-input resize-none" rows="5"></textarea>
      </label>
      <template #footer>
        <div class="flex justify-end gap-3">
          <button
            class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-white/20"
            @click="profileEditorVisible = false"
          >
            取消
          </button>
          <button
            class="cursor-pointer rounded-full border border-[#79d9ff] bg-[rgba(121,217,255,0.18)] px-5 py-1.5 text-xs font-bold text-[#79d9ff] transition-colors hover:bg-[rgba(121,217,255,0.28)]"
            @click="saveProfile"
          >
            保存
          </button>
        </div>
      </template>
    </LcModal>
  </MenuPage>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { Plus, Rabbit } from "@lucide/vue";
import { Icon } from "../../base";
import { MenuItem, MenuPage } from "../../ui";
import CharacterCard from "../../ui/Menu/CharacterCard.vue";
import RoleArchiveProgress from "../../ui/RoleArchiveProgress.vue";
import LcModal from "../../ui/LcModal.vue";
import NewCharacterModal from "../character/NewCharacterModal.vue";
import SettingsCharacterInfo from "./SettingsCharacterInfo.vue";
import { avatarUrl, findInterviewer, interviewers, type Interviewer } from "../../../data/interviewers";
import { notify, setPersona, store } from "../../../store/appStore";
import { confirmDialog } from "../../../store/dialogStore";

const FAVORED_KEY = "interview-lab-favored";
const PROFILE_KEY = "interview-lab-profile";
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

// ========== 我的身份（localStorage: interview-lab-profile） ==========
interface UserProfile {
  name: string;
  subtitle: string;
  prompt: string;
}

const DEFAULT_PROFILE: UserProfile = {
  name: "蔡智豪",
  subtitle: "应届毕业生 · 在读",
  prompt:
    "计算机专业在读，熟悉 Java / Vue / MySQL，做过多个课程项目。简历摘要会注入面试官的提问上下文（P1 接入）。",
};

function readProfile(): UserProfile {
  try {
    const raw = JSON.parse(localStorage.getItem(PROFILE_KEY) ?? "null");
    if (raw && typeof raw === "object") {
      return {
        name: typeof raw.name === "string" ? raw.name : DEFAULT_PROFILE.name,
        subtitle: typeof raw.subtitle === "string" ? raw.subtitle : DEFAULT_PROFILE.subtitle,
        prompt: typeof raw.prompt === "string" ? raw.prompt : DEFAULT_PROFILE.prompt,
      };
    }
  } catch {
    /* 数据损坏时回落默认值 */
  }
  return { ...DEFAULT_PROFILE };
}

const profile = ref<UserProfile>(readProfile());
const profileEditorVisible = ref(false);
const profileDraft = reactive<UserProfile>({ ...DEFAULT_PROFILE });

function openProfileEditor() {
  Object.assign(profileDraft, profile.value);
  profileEditorVisible.value = true;
}

function saveProfile() {
  profile.value = { ...profileDraft };
  localStorage.setItem(PROFILE_KEY, JSON.stringify(profile.value));
  profileEditorVisible.value = false;
  notify("已保存「我的身份」");
}

async function handleDeleteProfile() {
  const ok = await confirmDialog("删除身份", "删除后「我的身份」会回到默认值，确定删除吗？");
  if (!ok) return;
  profile.value = { ...DEFAULT_PROFILE };
  localStorage.removeItem(PROFILE_KEY);
  notify("已恢复默认身份");
}
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
