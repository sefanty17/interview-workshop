<template>
  <!-- ========== 场景管理 ========== -->
  <MenuItem title="场景管理">
    <template #header>
      <PictureInPicture :size="20" />
    </template>

    <!-- 当前场景信息 + 操作按钮 -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <div class="font-bold text-[#79d9ff]">当前场景：{{ currentSceneDisplay }}</div>
      <div class="ml-auto flex flex-wrap gap-2">
        <button
          class="rounded-full border border-[#79d9ff] bg-[#79d9ff]/80 px-5 py-1.5 text-sm font-bold text-white shadow-lg shadow-indigo-500/20 transition-all hover:bg-[#79d9ff]"
          @click="openCreateScene"
        >
          创建场景
        </button>
        <button
          class="rounded-full border border-white/20 bg-white/10 px-4 py-1.5 text-sm font-bold text-white/80 shadow-lg transition-all hover:bg-white/20 disabled:cursor-not-allowed disabled:opacity-40"
          :disabled="isBackgroundCategoryReadOnly"
          @click="triggerUpload"
        >
          上传背景
        </button>
        <button
          class="rounded-full border border-white/20 bg-white/10 px-4 py-1.5 text-sm font-bold text-white/80 shadow-lg transition-all hover:bg-white/20"
          @click="handleOpenFolder"
        >
          打开文件夹
        </button>
        <button
          class="rounded-full border border-white/20 bg-white/10 px-4 py-1.5 text-sm font-bold text-white/80 shadow-lg transition-all hover:bg-white/20"
          @click="openSortModal"
        >
          场景排序
        </button>
        <button
          class="flex items-center gap-1.5 rounded-full border border-white/20 bg-white/10 px-4 py-1.5 text-sm font-bold text-white/80 shadow-lg transition-all hover:bg-white/20 disabled:cursor-not-allowed"
          title="重新扫描背景文件夹，同步最新场景（含子文件夹）"
          :disabled="refreshing"
          @click="handleRefreshScenes"
        >
          <LoaderCircle v-if="refreshing" :size="14" class="animate-spin" />
          刷新场景
        </button>
        <button
          class="rounded-full border border-red-500/30 bg-red-500/20 px-4 py-1.5 text-sm font-bold text-red-300 shadow-lg transition-all hover:bg-red-500/30 disabled:cursor-not-allowed"
          :disabled="!currentScene"
          @click="deleteCurrentScene"
        >
          删除
        </button>
      </div>
    </div>

    <SceneCategoryBar
      v-model="currentBackgroundCategory"
      v-model:name="newCategoryName"
      :categories="backgroundCategories"
      :busy="categoryBusy"
      @create="createCategory"
      @delete="deleteCurrentCategory"
    />

    <div
      v-if="filteredScenes.length === 0"
      class="mb-5 rounded-xl border border-dashed border-white/15 bg-white/5 px-6 py-10 text-center text-sm text-white/50"
    >
      该分类下暂无场景，可上传背景或将已有场景移动到此分类
    </div>

    <!-- 场景卡片网格 -->
    <div class="grid w-full grid-cols-1 gap-5 pb-5 sm:grid-cols-2 xl:grid-cols-3">
      <SceneCard
        v-for="scene in paginatedScenes"
        :key="scene.id"
        :scene="scene"
        :selected="isSceneSelected(scene.id)"
        :favored="Boolean(scene.favored)"
        @select="handleSceneClick"
        @edit="openEditScene"
        @toggle-favorite="handleToggleFavorite"
        @update-location="handleUpdateLocation"
        @context-menu="openSceneContextMenu"
      />
    </div>

    <SceneContextMenu
      :visible="sceneMenu.visible"
      :x="sceneMenu.x"
      :y="sceneMenu.y"
      :scene="sceneMenu.scene"
      :categories="writableBackgroundCategories"
      @close="closeSceneContextMenu"
      @move="handleMoveScene"
      @set-current="handleSetCurrentFromMenu"
      @toggle-favorite="handleToggleFavoriteFromMenu"
      @rename="handleRenameFromMenu"
      @delete="handleDeleteFromMenu"
    />

    <ScenePagination v-model="currentPage" :total-pages="totalPages" />

    <!-- 隐藏的文件上传 input -->
    <input
      ref="uploadInput"
      type="file"
      accept="image/jpeg,image/png,image/webp,image/gif"
      class="hidden"
      @change="handleFileUpload"
    />
  </MenuItem>

  <SceneSortModal
    :show="showSortModal"
    :items="sortItems"
    :favored-ids="sortFavoredIds"
    @close="showSortModal = false"
    @save="handleSaveSortOrder"
  />

  <SceneEditModal
    :show="showSceneEdit"
    :mode="editMode"
    :scene="editScene"
    @close="showSceneEdit = false"
    @submit="submitScene"
  />
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  LoaderCircle,
  PictureInPicture,
} from "@lucide/vue";
import { MenuItem } from "../../ui";
import SceneCard from "./SceneCard.vue";
import SceneCategoryBar from "./SceneCategoryBar.vue";
import SceneContextMenu from "./SceneContextMenu.vue";
import ScenePagination from "./ScenePagination.vue";
import SceneSortModal from "./SceneSortModal.vue";
import SceneEditModal from "../scene/SceneEditModal.vue";
import {
  ALL_CATEGORY,
  ROOT_CATEGORY,
  VIRTUAL_CATEGORY,
} from "./categories";
import {
  notify,
  persist,
  store,
  updateSettings,
} from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import type { LightingParams, SceneInfo } from "../../../data/mock";

type LocationType = NonNullable<SceneInfo["location_type"]>;

// ========== 场景列表 ==========
// 收藏置顶：收藏区在前，其余保持既有顺序
const orderedScenes = computed(() => [
  ...store.scenes.filter((s) => s.favored),
  ...store.scenes.filter((s) => !s.favored),
]);

const isPluginScene = (scene: SceneInfo) =>
  Boolean(scene.plugin_id) || Boolean(scene.source && scene.source !== "game");

const currentBackgroundCategory = ref<string>(ALL_CATEGORY);

const backgroundCategories = computed(() => {
  // 原版由后端 list_background_categories 返回「根目录 + 子文件夹 + 插件」，
  // 这里用场景数据 + 已建分类拼出同样的顺序
  const list: string[] = [ROOT_CATEGORY, ...store.sceneCategories];
  for (const scene of store.scenes) {
    if (isPluginScene(scene)) continue;
    const name = scene.category;
    if (name && name !== ROOT_CATEGORY && !list.includes(name)) list.push(name);
  }
  if (!list.includes(VIRTUAL_CATEGORY)) list.push(VIRTUAL_CATEGORY);
  return list;
});

/** 可写入的分类（右键/表单里可选，不含「插件」「根目录」） */
const writableBackgroundCategories = computed(() =>
  backgroundCategories.value.filter((c) => c !== VIRTUAL_CATEGORY && c !== ROOT_CATEGORY),
);

const filteredScenes = computed(() => {
  const category = currentBackgroundCategory.value;
  if (!category || category === ALL_CATEGORY) return orderedScenes.value;
  if (category === VIRTUAL_CATEGORY) return orderedScenes.value.filter(isPluginScene);
  return orderedScenes.value.filter((s) => {
    if (isPluginScene(s)) return false;
    return (s.category ?? ROOT_CATEGORY) === category;
  });
});

const isBackgroundCategoryReadOnly = computed(
  () => currentBackgroundCategory.value === VIRTUAL_CATEGORY,
);

// ========== 当前场景 ==========
const currentScene = computed(
  () => store.scenes.find((s) => s.id === store.settings.sceneId) ?? null,
);
const currentSceneDisplay = computed(() => currentScene.value?.scene_name ?? "无");
const isSceneSelected = (id: string) => store.settings.sceneId === id;

function handleSceneClick(scene: SceneInfo): void {
  updateSettings({ sceneId: scene.id });
}

function handleToggleFavorite(scene: SceneInfo): void {
  scene.favored = !scene.favored;
  persist();
}

function handleUpdateLocation(scene: SceneInfo, location: LocationType): void {
  scene.location_type = location;
  persist();
}

async function deleteCurrentScene(): Promise<void> {
  const scene = currentScene.value;
  if (!scene) return;
  const ok = await confirmDialog("删除场景", `确定要删除场景"${scene.scene_name}"吗？`);
  if (!ok) return;
  const index = store.scenes.findIndex((s) => s.id === scene.id);
  if (index >= 0) store.scenes.splice(index, 1);
  if (store.settings.sceneId === scene.id) updateSettings({ sceneId: "" });
  persist();
  notify(`已删除场景「${scene.scene_name}」`);
}

// ========== 分类新建 / 删除 ==========
const newCategoryName = ref("");
const categoryBusy = ref(false);

function createCategory(): void {
  const name = newCategoryName.value.trim();
  if (!name) {
    void alertDialog("新建分类", "请输入分类名称");
    return;
  }
  if (
    name === ALL_CATEGORY ||
    name === ROOT_CATEGORY ||
    name === VIRTUAL_CATEGORY ||
    backgroundCategories.value.includes(name)
  ) {
    void alertDialog("新建分类", `分类「${name}」已存在`);
    return;
  }
  categoryBusy.value = true;
  store.sceneCategories.push(name);
  newCategoryName.value = "";
  categoryBusy.value = false;
  currentBackgroundCategory.value = name;
  persist();
  notify(`分类「${name}」已创建`);
}

async function deleteCurrentCategory(): Promise<void> {
  const category = currentBackgroundCategory.value;
  const count = store.scenes.filter((s) => s.category === category).length;
  const ok = await confirmDialog(
    "删除分类",
    `确定要删除分类「${category}」，并把其下背景全部移到主分类吗？`,
  );
  if (!ok) return;
  for (const scene of store.scenes) {
    if (scene.category === category) scene.category = null;
  }
  const index = store.sceneCategories.indexOf(category);
  if (index >= 0) store.sceneCategories.splice(index, 1);
  currentBackgroundCategory.value = ALL_CATEGORY;
  persist();
  notify(
    count > 0
      ? `已删除分类「${category}」，并把 ${count} 个背景移动到根目录`
      : `分类「${category}」已删除`,
  );
}

// ========== 上传背景 ==========
const uploadInput = ref<HTMLInputElement | null>(null);

function triggerUpload(): void {
  if (isBackgroundCategoryReadOnly.value) return;
  uploadInput.value?.click();
}

function handleFileUpload(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (!file.type.startsWith("image/")) {
    void alertDialog("上传背景", "请上传支持的图片格式: .jpg / .png / .webp / .gif");
    return;
  }
  const sceneName = file.name.replace(/\.[^.]+$/, "");
  const category = currentBackgroundCategory.value;
  store.scenes.unshift({
    id: `sc-${Date.now()}`,
    scene_name: sceneName,
    scene_description: `上传的背景文件：${file.name}`,
    background: URL.createObjectURL(file),
    category: category === ALL_CATEGORY || category === VIRTUAL_CATEGORY ? null : category,
    source: "game",
    location_type: "unknown",
    favored: false,
  });
  persist();
  notify(`已上传背景「${sceneName}」`);
}

// ========== 刷新场景 ==========
const refreshing = ref(false);

async function handleRefreshScenes(): Promise<void> {
  const ok = await confirmDialog(
    "刷新场景",
    "刷新场景将清理背景文件已丢失的空白场景，并重新扫描背景。确定继续吗？",
  );
  if (!ok) return;
  refreshing.value = true;
  await new Promise((resolve) => window.setTimeout(resolve, 600));
  refreshing.value = false;
  notify("刷新完成：场景已同步");
}

function handleOpenFolder(): void {
  void alertDialog("提示", "桌面版功能，Web 端不适用");
}

// ========== 分页 ==========
const ITEMS_PER_PAGE = 6;
const currentPage = ref(1);
const totalPages = computed(() => Math.max(1, Math.ceil(filteredScenes.value.length / ITEMS_PER_PAGE)));
const paginatedScenes = computed(() => {
  const start = (currentPage.value - 1) * ITEMS_PER_PAGE;
  return filteredScenes.value.slice(start, start + ITEMS_PER_PAGE);
});

watch([filteredScenes, totalPages], () => {
  if (currentPage.value > totalPages.value) currentPage.value = totalPages.value;
});

watch(currentBackgroundCategory, () => {
  currentPage.value = 1;
});

// ========== 场景排序 ==========
const showSortModal = ref(false);
const sortItems = ref<SceneInfo[]>([]);
const sortFavoredIds = ref<string[]>([]);

async function openSortModal(): Promise<void> {
  // 全局收藏与排序键是跨分类的，在子分类下保存会用当前子集覆盖全局，
  // 因此只允许在「全部」分类下调整全局排序。
  if (currentBackgroundCategory.value !== ALL_CATEGORY) {
    await alertDialog("场景排序", "请先切换到「全部」分类后再调整场景排序。");
    return;
  }
  sortItems.value = filteredScenes.value.map((s) => ({ ...s }));
  sortFavoredIds.value = store.scenes.filter((s) => s.favored).map((s) => s.id);
  showSortModal.value = true;
}

function handleSaveSortOrder(ordered: SceneInfo[]): void {
  const ids = ordered.map((s) => s.id);
  const byId = new Map(store.scenes.map((s) => [s.id, s]));
  const next: SceneInfo[] = [];
  for (const id of ids) {
    const scene = byId.get(id);
    if (scene) next.push(scene);
  }
  for (const scene of store.scenes) {
    if (!ids.includes(scene.id)) next.push(scene);
  }
  store.scenes.splice(0, store.scenes.length, ...next);
  showSortModal.value = false;
  persist();
  notify("排序已保存");
}

// ========== 右键菜单 ==========
const sceneMenu = ref<{
  visible: boolean;
  x: number;
  y: number;
  scene: SceneInfo | null;
}>({ visible: false, x: 0, y: 0, scene: null });

function openSceneContextMenu(scene: SceneInfo, event: MouseEvent): void {
  if (scene.source && scene.source !== "game") return;
  sceneMenu.value = { visible: true, x: event.clientX, y: event.clientY, scene };
}

function closeSceneContextMenu(): void {
  sceneMenu.value.visible = false;
}

function handleMoveScene(category: string | null): void {
  const scene = sceneMenu.value.scene;
  closeSceneContextMenu();
  if (!scene) return;
  scene.category = category;
  persist();
  notify(
    category
      ? `已将「${scene.scene_name}」移动到「${category}」`
      : `已将「${scene.scene_name}」移动到根目录`,
  );
}

function handleSetCurrentFromMenu(): void {
  const scene = sceneMenu.value.scene;
  closeSceneContextMenu();
  if (!scene) return;
  updateSettings({ sceneId: scene.id });
  notify(`当前场景已设为「${scene.scene_name}」`);
}

function handleToggleFavoriteFromMenu(): void {
  const scene = sceneMenu.value.scene;
  closeSceneContextMenu();
  if (!scene) return;
  scene.favored = !scene.favored;
  persist();
}

function handleRenameFromMenu(name: string): void {
  const scene = sceneMenu.value.scene;
  closeSceneContextMenu();
  if (!scene) return;
  scene.scene_name = name;
  persist();
  notify(`已重命名为「${name}」`);
}

async function handleDeleteFromMenu(): Promise<void> {
  const scene = sceneMenu.value.scene;
  closeSceneContextMenu();
  if (!scene) return;
  const ok = await confirmDialog("删除场景", `确定要删除场景"${scene.scene_name}"吗？`);
  if (!ok) return;
  const index = store.scenes.findIndex((s) => s.id === scene.id);
  if (index >= 0) store.scenes.splice(index, 1);
  if (store.settings.sceneId === scene.id) updateSettings({ sceneId: "" });
  persist();
  notify(`已删除场景「${scene.scene_name}」`);
}

// ========== 创建 / 编辑场景 ==========
const showSceneEdit = ref(false);
const editMode = ref<"create" | "update">("create");
const editScene = ref<SceneInfo | null>(null);

function openCreateScene(): void {
  editMode.value = "create";
  editScene.value = null;
  showSceneEdit.value = true;
}

function openEditScene(scene: SceneInfo): void {
  editMode.value = "update";
  editScene.value = scene;
  showSceneEdit.value = true;
}

function submitScene(data: {
  sceneName: string;
  sceneImage: string | null;
  sceneDescription: string;
  category: string | null;
  location: LocationType;
  lighting: LightingParams | null;
}): void {
  const category = data.category;
  if (editMode.value === "create") {
    store.scenes.unshift({
      id: `sc-${Date.now()}`,
      scene_name: data.sceneName,
      scene_description: data.sceneDescription,
      background: data.sceneImage ?? "",
      category,
      source: "game",
      location_type: data.location,
      favored: false,
      lighting: data.lighting,
    });
    notify(`场景「${data.sceneName}」已创建`);
  } else {
    const target = editScene.value;
    if (target) {
      target.scene_name = data.sceneName;
      target.scene_description = data.sceneDescription;
      target.background = data.sceneImage ?? "";
      target.category = category;
      target.location_type = data.location;
      target.lighting = data.lighting;
    }
    notify(`场景「${data.sceneName}」已更新`);
  }
  showSceneEdit.value = false;
  persist();
}
</script>
