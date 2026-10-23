<template>
  <div class="flex w-full flex-col gap-4">
    <div class="flex items-center justify-between">
      <p class="text-[0.78rem] text-white/50">
        编辑器背景与遮挡效果，改动即时生效并自动保存。
      </p>
    </div>

    <!-- 背景图 -->
    <MenuItem title="背景图">
      <template #header>
        <Icon icon="background" :size="16" class="text-brand" />
      </template>
      <div class="flex flex-col gap-3">
        <div class="rounded-lg border border-white/10 bg-black/20 px-3 py-2.5">
          <div class="mb-1 text-[0.72rem] text-white/45">当前背景</div>
          <div class="flex items-center gap-2 font-mono text-[0.78rem] text-white/85">
            <Icon icon="background" :size="13" class="shrink-0 text-white/40" />
            <span class="truncate">{{ bgFileName }}</span>
          </div>
        </div>

        <div class="flex gap-2">
          <button
            class="inline-flex flex-1 items-center justify-center gap-1.5 rounded-lg border border-brand/45 bg-brand/14 px-3 py-2 text-[0.8rem] text-brand transition-colors hover:bg-brand/24"
            @click="pickImage"
          >
            <Icon icon="background" :size="14" />
            选择图片…
          </button>
          <button
            class="inline-flex items-center justify-center gap-1.5 rounded-lg border border-white/10 bg-white/6 px-3 py-2 text-[0.8rem] text-white/70 transition-colors hover:bg-white/[0.12] hover:text-white"
            title="恢复为内置默认背景"
            :disabled="!store.editorBg.path"
            @click="resetEditorBg()"
          >
            <Icon icon="history" :size="14" />
            恢复默认
          </button>
        </div>
        <p class="text-[0.72rem] leading-relaxed text-white/40">
          默认背景与主菜单同款；自定义图片会复制到应用数据目录，本地文件可随时移动。选择图片后可在裁剪弹窗中调整范围
        </p>

        <!-- 从已有背景选择 -->
        <div>
          <div class="mb-1.5 text-[0.72rem] text-white/45">从已有背景选择</div>
          <div class="flex flex-col gap-1.5">
            <button
              v-for="f in store.globalBgFiles"
              :key="f.url"
              class="flex items-center gap-2 rounded-[10px] border px-3 py-2 text-left transition-all duration-200"
              :class="
                store.editorBg.path === f.url
                  ? 'border-brand/40 bg-brand/10'
                  : 'border-white/10 bg-white/6 hover:border-brand/40'
              "
              @click="setEditorBgPath(f.url)"
            >
              <Icon icon="background" :size="14" class="shrink-0 text-white/40" />
              <span class="min-w-0 flex-1 truncate text-[0.8rem] text-white/85">{{ f.name }}</span>
              <span class="shrink-0 text-[0.68rem] text-white/35">{{ humanSize(f.size) }}</span>
            </button>
            <p v-if="!store.globalBgFiles.length" class="text-[0.72rem] text-white/35">
              （暂无全局背景，可在素材页导入）
            </p>
          </div>
        </div>
      </div>
    </MenuItem>

    <!-- 视觉效果 -->
    <MenuItem title="视觉效果">
      <template #header>
        <Icon icon="sliders" :size="16" class="text-brand" />
      </template>
      <div class="flex flex-col gap-4">
        <div>
          <label
            class="mb-1 inline-flex items-center gap-1.5 text-[0.84rem] font-medium text-white/80"
          >
            模糊
          </label>
          <!-- 本项目的 Slider 在 ui 里叫 LcSlider：v-model 风格，无 change 事件与插槽 -->
          <LcSlider
            :model-value="store.editorBg.blur"
            :min="0"
            :max="24"
            :step="1"
            @update:model-value="onBlurChange"
          />
          <p class="mt-1 text-[0.72rem] leading-relaxed text-white/40">清晰 / 柔和</p>
        </div>

        <div>
          <label
            class="mb-1 inline-flex items-center gap-1.5 text-[0.84rem] font-medium text-white/80"
          >
            压暗遮罩
          </label>
          <LcSlider
            :model-value="dimPercent"
            :min="0"
            :max="90"
            :step="1"
            @update:model-value="onDimChange"
          />
          <p class="mt-1 text-[0.72rem] leading-relaxed text-white/40">
            明亮 / 深暗。调节背景上方的黑色遮罩，保证文字与面板可读。
          </p>
        </div>
      </div>
    </MenuItem>

    <!-- 网页版选图入口：隐藏 file input 替代 Tauri openDialog -->
    <input
      ref="fileEl"
      class="hidden"
      type="file"
      accept="image/png,image/jpeg,image/webp,image/bmp,image/gif"
      @change="onFileChange"
    />

    <!-- 裁剪弹窗：选图后打开 -->
    <ImageCropModal
      v-if="cropSrc"
      :src-url="cropSrc"
      :src-name="cropName"
      @confirm="onCropConfirm"
      @cancel="onCropCancel"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Icon } from "../../base";
import { MenuItem } from "../../ui";
import LcSlider from "../../ui/LcSlider.vue";
import ImageCropModal from "../modals/ImageCropModal.vue";
import {
  refreshGlobalBgFiles,
  resetEditorBg,
  setEditorBgPath,
  store,
  uploadEditorBgData,
} from "../../../store/scriptEditor";

/** 当前正在裁剪的源文件 object URL；非空时显示裁剪弹窗 */
const cropSrc = ref<string | null>(null);
/** 网页版新增：原始文件名（confirm 输出名取自它，规格 E6a §4） */
const cropName = ref("");

const fileEl = ref<HTMLInputElement | null>(null);

/** 当前背景显示名：自定义时取落盘文件名，默认时说明内置背景 */
const bgFileName = computed(() => {
  const path = store.editorBg.path;
  if (!path) return "内置默认背景（主菜单同款）";
  return path.split(/[\\/]/).pop() || path;
});

/** 压暗遮罩：UI 用 0~90 整数显示，store 存 0~1 小数 */
const dimPercent = computed(() => Math.round(store.editorBg.dim * 100));

// 本组件只 emit change，不 emit update:modelValue，这里显式回写 store
const onBlurChange = (v: number) => {
  store.editorBg = { ...store.editorBg, blur: v };
};
const onDimChange = (v: number) => {
  store.editorBg = { ...store.editorBg, dim: v / 100 };
};

const humanSize = (n: number) => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

/** 选图后进入裁剪弹窗（默认选区铺满整图，不拖框即等于不裁剪） */
const pickImage = () => {
  fileEl.value?.click();
};

const onFileChange = (e: Event) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (cropSrc.value) URL.revokeObjectURL(cropSrc.value);
  cropSrc.value = URL.createObjectURL(file);
  cropName.value = file.name;
  // 允许连续选同一个文件
  input.value = "";
};

const onCropConfirm = async (dataUrl: string, name: string) => {
  if (cropSrc.value) URL.revokeObjectURL(cropSrc.value);
  cropSrc.value = null;
  await uploadEditorBgData(dataUrl, name);
};

const onCropCancel = () => {
  if (cropSrc.value) URL.revokeObjectURL(cropSrc.value);
  cropSrc.value = null;
};

onMounted(() => {
  void refreshGlobalBgFiles();
});
</script>
