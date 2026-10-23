<template>
  <div
    class="group relative flex min-h-[190px] items-stretch rounded-2xl border border-white/20 bg-white/10 p-4 backdrop-blur-xl transition-all duration-300 hover:-translate-y-0.5 hover:border-white/40 hover:shadow-2xl hover:shadow-indigo-500/20"
    :class="selected ? 'shadow-lg shadow-indigo-500/20 ring-2 ring-indigo-400/40' : ''"
  >
    <div
      class="text-brand absolute -top-2 -left-2 flex h-6 w-6 -rotate-[18deg] transform items-center justify-center rounded-full shadow-md"
    >
      <Cat :size="20" />
    </div>
    <button
      class="absolute top-2 left-2 z-10 rounded-lg bg-black/30 p-1.5 opacity-0 backdrop-blur-sm transition-all group-hover:opacity-100 focus-visible:opacity-100"
      @click.stop="emit('favoredChange', id)"
      :title="favored ? '取消收藏' : '收藏（移到最前）'"
    >
      <Star
        :size="16"
        :class="favored ? 'fill-amber-400 text-amber-400' : 'text-white/60 hover:text-white'"
      />
    </button>
    <div class="absolute top-3 right-3 z-10 flex items-center gap-2">
      <RoleExportMenu :role-id="id" :role-name="name" />
      <button
        class="flex items-center justify-center rounded-full bg-black/5 p-1 text-white/60 transition-all hover:rotate-90 hover:bg-white/10 hover:text-white"
        title="角色设置"
        @click.stop="emit('settings', id)"
      >
        <Settings :size="24" />
      </button>
    </div>

    <div
      class="flex w-28 shrink-0 flex-col items-center space-y-2 border-r border-white/10 pr-4 md:w-32"
    >
      <div class="h-24 w-24 overflow-hidden rounded-full border-2 border-indigo-400/50 shadow-lg">
        <img
          v-if="avatar && !avatarFailed"
          :src="avatar"
          :alt="name"
          class="h-full w-full object-cover transition-transform duration-500 group-hover:scale-110"
          @error="avatarFailed = true"
        />
        <span
          v-else
          class="flex h-full w-full items-center justify-center bg-indigo-500/20 text-3xl text-white/80"
          aria-hidden="true"
          >{{ Array.from(name || title || "")[0] || "?" }}</span
        >
      </div>
      <span class="bg-brand mt-1 h-1 w-6 rounded-full"></span>
      <h4 class="line-clamp-2 w-full text-center text-sm font-bold text-white drop-shadow-md">
        {{ title }}
      </h4>
      <!-- 原版 PluginTag 仅对非 "game" 来源显示（本地角色不显示），照抄避免多占一行高度 -->
      <PluginTag v-if="source && source !== 'game'" :source="source" />
    </div>

    <div class="flex min-h-0 flex-1 flex-col justify-between pl-4">
      <div class="pr-8">
        <div class="flex items-center gap-2">
          <div class="text-brand mb-3 text-xl font-bold tracking-widest uppercase opacity-80">
            {{ name }}
          </div>
          <div class="text-brand mb-3 text-sm font-medium tracking-widest uppercase opacity-80">
            {{ subName }}
          </div>
        </div>
        <p class="line-clamp-3 text-base leading-relaxed text-gray-200/90 opacity-80">{{ displayInfo }}</p>
      </div>

      <div class="mt-4 flex items-center justify-end gap-2">
      <!-- 详情：打开角色详情 / 编辑大页（settings/pages/SettingsCharacterInfo.vue），
           与右上角齿轮同一个入口，由父组件整页替换角色列表 -->
      <button
        class="rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white transition-all hover:bg-white/20"
        @click.stop="emit('detail', id)"
      >
        详情
      </button>

        <!-- 未加入 -->
        <button
          v-if="!inScene"
          class="rounded-full border border-cyan-400 bg-cyan-500/80 px-4 py-1.5 text-xs font-semibold text-white shadow-lg shadow-cyan-500/20 transition-all hover:bg-cyan-500"
          @click="joinScene"
        >
          加入
        </button>

        <!-- 已加入但未选中：红色「离开」 -->
        <button
          v-else-if="!selected"
          class="rounded-full border border-red-400 bg-red-500/80 px-4 py-1.5 text-xs font-semibold text-white shadow-lg shadow-red-500/20 transition-all hover:bg-red-500"
          @click="leaveScene"
        >
          离开
        </button>

        <!-- 已选中：disabled 的「已在场」 -->
        <button
          v-else
          disabled
          class="cursor-not-allowed rounded-full border border-cyan-400/50 bg-cyan-500/50 px-4 py-1.5 text-xs font-semibold text-cyan-200 shadow-lg transition-all"
        >
          已在场
        </button>

        <!-- 选择 / 已选中 -->
        <button
          :class="[
            'rounded-full border px-5 py-1.5 text-xs font-bold shadow-lg transition-all',
            selected
              ? 'border-emerald-400 bg-emerald-500/80 text-white shadow-emerald-500/20'
              : 'border-indigo-500 bg-indigo-600/80 text-white shadow-indigo-500/20 hover:bg-indigo-500',
          ]"
          @click="emit('select', id)"
        >
          {{ selected ? "✓ 已选中" : "选择" }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Cat, Settings, Star } from "@lucide/vue";
import RoleExportMenu from "../RoleExportMenu.vue";
import PluginTag from "../PluginTag.vue";
import { notify } from "../../../store/appStore";

/** 场景内角色列表（localStorage: interview-room-cast） */
const CAST_KEY = "interview-room-cast";

function readCast(): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(CAST_KEY) ?? "[]");
    return Array.isArray(raw) ? raw.filter((id): id is string => typeof id === "string") : [];
  } catch {
    return [];
  }
}

function writeCast(ids: string[]): void {
  localStorage.setItem(CAST_KEY, JSON.stringify(ids));
}

/** 原版的服装项（我们暂时没有服装数据，clothes 恒为 []） */
interface Clothes {
  title: string;
  avatar: string;
}

interface CharacterProps {
  /** 角色 code（原版是数字 id，我们用 InterviewerCode） */
  id: string;
  avatar?: string;
  /** 左侧头像下方的角色名 */
  name?: string;
  /** 左侧 h4 标题（截图里带「~」的角色称呼） */
  title?: string;
  /** 右侧大字副标题（岗位） */
  subName?: string;
  info?: string;
  clothes?: Clothes[];
  resourceFolder?: string;
  /** 来源："game" 或插件 id */
  source?: string | null;
  favored?: boolean;
  selected?: boolean;
}

const props = withDefaults(defineProps<CharacterProps>(), {
  avatar: "",
  name: "Unknown",
  title: "",
  subName: "",
  info: "",
  clothes: () => [],
  resourceFolder: "",
  favored: false,
  selected: false,
});

const emit = defineEmits<{
  (e: "select", id: string): void;
  (e: "settings", id: string): void;
  (e: "favoredChange", id: string): void;
  /** 「详情」按钮：与齿轮同指角色编辑大页，由父组件决定是否整页替换 */
  (e: "detail", id: string): void;
}>();

const avatarFailed = ref(false);
/** 是否已加入场景（localStorage: interview-room-cast） */
const joined = ref(readCast().includes(props.id));

watch(
  () => props.avatar,
  () => {
    avatarFailed.value = false;
  },
);

/** 是否在场：本地 joined 或已选中（原版 isInScene 与 isSelected 独立，选中即视为在场） */
const inScene = computed(() => joined.value || props.selected);

// 数据里的 cardInfo 用「 / 」分隔多句，卡片摘要里换成逗号，避免一行里挂满斜杠
const displayInfo = computed(() =>
  (props.info || "暂无角色描述")
    .replace(/\s*\/\s*/g, "，")
    .replace(/([。！？；，])\s*，/g, "$1"),
);

function joinScene() {
  if (!readCast().includes(props.id)) writeCast([...readCast(), props.id]);
  joined.value = true;
  notify(`已加入场景：${props.name}`);
}

function leaveScene() {
  writeCast(readCast().filter((item) => item !== props.id));
  joined.value = false;
  notify(`已移出场景：${props.name}`);
}
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供），
   保留原版 text-brand / bg-brand 类名，此处补等效颜色，避免猫爪角标与短横线隐形 */
.text-brand {
  color: var(--accent-color);
}
.bg-brand {
  background-color: var(--accent-color);
}
</style>
