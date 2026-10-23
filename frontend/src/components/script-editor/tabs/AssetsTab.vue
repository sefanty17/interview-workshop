<script setup lang="ts">
/**
 * 素材 Tab（规格 E6b §3，对照原版 tabs/AssetsTab.vue 改写）。
 *
 * 技术栈差异：
 * - 无 vue-i18n，文案照原版 zh-CN 直写。
 * - 无 Tauri：convertFileSrc 换成后端直接给的 HTTP url；openDialog 换成隐藏
 *   file input + dataURL 上传（与外观页的裁剪上传同一通道，素材不做裁剪）。
 * - 原版音效没有全局目录（issue #6）所以只给「本剧本」一列；本项目全局有
 *   audio_effects/ 目录，音效也展示「全局」列。
 * - 本项目剧本级素材只有背景图（backgrounds/scripts/<key>/），其余类别的
 *   「导入本剧本」按钮禁用并说明原因。
 */

import { onMounted, onUnmounted, reactive, ref } from "vue";
import { Icon } from "../../base";
import { MenuPage, MenuItem } from "../../ui";
import { notify } from "../../../store/appStore";
import { confirmDialog } from "../../../store/dialogStore";
import { scriptKey, store } from "../../../store/scriptEditor";
import {
  deleteAsset,
  fetchAssetFiles,
  uploadAsset,
  type AssetFile,
  type AssetFileIndex,
  type AssetKind,
  type AssetScope,
} from "../../../api/assets";

const assetKinds: { key: AssetKind; label: string }[] = [
  { key: "background", label: "背景图" },
  { key: "pic", label: "插图" },
  { key: "music", label: "背景音乐" },
  { key: "sound", label: "音效" },
  { key: "ambient", label: "环境音" },
];

const isImageKind = (k: AssetKind) => k === "background" || k === "pic";

const IMAGE_EXT = ["png", "jpg", "jpeg", "webp", "bmp", "gif"];
const AUDIO_EXT = ["mp3", "wav", "ogg", "flac"];

const emptyIndex = (): AssetFileIndex => ({
  background: [],
  pic: [],
  music: [],
  sound: [],
  ambient: [],
});

const files = reactive<{ script: AssetFileIndex; global: AssetFileIndex }>({
  script: emptyIndex(),
  global: emptyIndex(),
});

const filesOf = (scope: AssetScope, kind: AssetKind): AssetFile[] => files[scope][kind];

/** 音效全局也有目录，因此五类都展示「本剧本 + 全局」两列（与原版的差异见文件头注释） */
const scopesFor = (_kind: AssetKind): AssetScope[] => ["script", "global"];

const humanSize = (n: number) => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
};

const refresh = async () => {
  const jobs: Promise<void>[] = [
    fetchAssetFiles("global").then((idx) => {
      files.global = idx;
    }),
  ];
  if (store.detail) {
    jobs.push(
      fetchAssetFiles("script", scriptKey.value).then((idx) => {
        files.script = idx;
      }),
    );
  }
  await Promise.allSettled(jobs);
};

// ---- 导入（网页版：隐藏 file input 替代 Tauri openDialog）----

const fileEl = ref<HTMLInputElement | null>(null);
let pendingPick: { kind: AssetKind; scope: AssetScope } | null = null;

const importAsset = (kind: AssetKind, scope: AssetScope) => {
  pendingPick = { kind, scope };
  // 每次点都重建 accept，同一个 input 在图片/音频之间切换
  fileEl.value?.setAttribute("accept", (isImageKind(kind) ? IMAGE_EXT : AUDIO_EXT)
    .map((e) => `.${e}`)
    .join(","));
  fileEl.value?.click();
};

const onFileChange = async (e: Event) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  const pick = pendingPick;
  input.value = "";
  pendingPick = null;
  if (!file || !pick) return;
  try {
    await uploadAsset(pick.kind, pick.scope, scriptKey.value, file);
    notify(`素材「${file.name}」已导入`);
  } catch (err) {
    notify(err instanceof Error ? err.message : "素材导入失败");
  }
  await refresh();
};

// ---- 删除 ----

const removeAsset = async (kind: AssetKind, scope: AssetScope, f: AssetFile) => {
  const ok = await confirmDialog("删除素材", `确定删除素材「${f.name}」吗？剧本里的引用会失效。`);
  if (!ok) return;
  try {
    await deleteAsset(kind, scope, scriptKey.value, f.name);
    notify(`素材「${f.name}」已删除`);
  } catch (err) {
    notify(err instanceof Error ? err.message : "删除素材失败");
  }
  await refresh();
};

// ---- 素材音频播放速度 ----
const audioEls: Record<string, HTMLAudioElement | null> = {};
const audioRates = reactive<Record<string, number>>({});
const speedMenu = ref<string | null>(null);
const setAudioRef = (path: string) => (el: unknown) => {
  audioEls[path] = el as HTMLAudioElement | null;
  if (!(path in audioRates)) audioRates[path] = 1;
};
const onDocClick = () => {
  speedMenu.value = null;
};
const setRate = (path: string, rate: number) => {
  const a = audioEls[path];
  if (a) {
    a.playbackRate = rate;
    audioRates[path] = rate;
  }
};

// 点击素材卡片外的区域关闭速度菜单
onMounted(() => {
  document.addEventListener("click", onDocClick);
  void refresh();
});
onUnmounted(() => document.removeEventListener("click", onDocClick));
</script>

<template>
  <MenuPage>
    <MenuItem title="素材">
      <template #header>
        <Icon icon="background" :size="20" />
      </template>

      <p
        class="mb-[0.9rem] rounded-xl border border-white/10 bg-black/16 px-[0.85rem] py-[0.7rem] text-[0.76rem] leading-[1.85] text-white/60"
        v-html="'引擎查找素材的顺序是<b class=&quot;font-semibold text-white/85&quot;>先本剧本，再全局</b>，所以两处都能被找到，区别在于：<b class=&quot;font-semibold text-white/85&quot;>剧本素材</b>随剧本一起分发，别的剧本看不到；<b class=&quot;font-semibold text-white/85&quot;>全局素材</b>所有剧本共享，但导出剧本时不会带走。'"
      ></p>

      <div
        v-for="k in assetKinds"
        :key="k.key"
        class="mb-[1.1rem] border-b border-white/[0.07] pb-[0.9rem]"
      >
        <div class="mb-[0.6rem] flex items-center gap-2">
          <span class="text-[0.85rem] font-semibold text-white">{{ k.label }}</span>
          <button
            class="ml-auto inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="k.key !== 'background'"
            :title="k.key !== 'background' ? '本项目剧本级素材只支持背景图，其余类别请导入全局' : ''"
            @click="importAsset(k.key, 'script')"
          >
            导入本剧本
          </button>
          <button
            class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
            @click="importAsset(k.key, 'global')"
          >
            导入全局
          </button>
        </div>
        <div class="grid grid-cols-2 gap-4 has-[>:only-child]:grid-cols-1">
          <div v-for="col in scopesFor(k.key)" :key="col">
            <p class="mb-[0.35rem] text-[0.7rem] text-white/40">
              {{ col === "script" ? "本剧本" : "全局" }}
              ·
              {{ filesOf(col, k.key).length }}
            </p>
            <p v-if="filesOf(col, k.key).length === 0" class="text-[0.72rem] text-white/25">无</p>
            <div
              v-for="f in filesOf(col, k.key)"
              :key="f.url"
              class="group relative mb-1.5 flex items-center gap-[9px] rounded-lg border border-white/10 bg-white/4 px-[9px] py-[7px] transition-all duration-150 hover:border-white/[0.22] hover:bg-white/7"
              :class="{
                'border-purple-400/[0.22] bg-purple-400/7': col === 'global',
              }"
            >
              <!-- 图片直接出缩略图；音频给一个原生播放器，够用且零依赖 -->
              <img
                v-if="isImageKind(k.key)"
                class="asset-thumb h-10 w-14 shrink-0 rounded-[5px] object-cover"
                :src="f.url"
                :alt="f.name"
                loading="lazy"
              />
              <div class="flex min-w-0 flex-1 flex-col gap-[3px]">
                <span
                  class="overflow-hidden text-[0.74rem] text-ellipsis whitespace-nowrap text-white/80"
                  >{{ f.name }}</span
                >
                <span class="text-[0.64rem] text-white/35">{{ humanSize(f.size) }}</span>
                <div v-if="!isImageKind(k.key)" class="flex items-center gap-2">
                  <audio
                    :ref="setAudioRef(f.url)"
                    class="asset-audio h-[26px] min-w-0 flex-1"
                    controls
                    preload="none"
                    controlslist="nodownload noremoteplayback"
                    :src="f.url"
                  ></audio>
                  <div class="relative shrink-0">
                    <button
                      class="rounded border border-white/10 px-1.5 py-0.5 text-[0.6rem] text-white/40 transition-colors hover:border-white/20 hover:text-white/70"
                      title="播放速度"
                      @click.stop.prevent="speedMenu = speedMenu === f.url ? null : f.url"
                    >
                      {{ audioRates[f.url] ?? 1 }}× ▾
                    </button>
                    <div
                      v-if="speedMenu === f.url"
                      class="absolute right-0 bottom-full z-20 mb-1 flex flex-col rounded border border-white/[0.14] bg-[#16202c] py-0.5 shadow-lg"
                      @click.stop
                    >
                      <button
                        v-for="rate in [0.5, 0.75, 1, 1.25, 1.5, 2]"
                        :key="rate"
                        class="px-2 py-0.5 text-left text-[0.6rem] whitespace-nowrap transition-colors hover:bg-white/10"
                        :class="(audioRates[f.url] ?? 1) === rate ? 'text-brand' : 'text-white/60'"
                        @click="
                          () => {
                            setRate(f.url, rate);
                            speedMenu = null;
                          }
                        "
                      >
                        {{ rate }}×
                      </button>
                    </div>
                  </div>
                </div>
              </div>
              <button
                class="shrink-0 rounded px-[5px] text-[11px] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-red-400/15 hover:text-red-300"
                title="删除（移到 .trash/）"
                @click="removeAsset(k.key, col, f)"
              >
                ✕
              </button>
            </div>
          </div>
        </div>
      </div>
    </MenuItem>
  </MenuPage>

  <!-- 网页版选素材入口：隐藏 file input 替代 Tauri openDialog -->
  <input ref="fileEl" class="hidden" type="file" @change="onFileChange" />
</template>

<style scoped>
/* 棋盘底纹：透明图片不至于糊成一片黑 */
.asset-thumb {
  background: repeating-conic-gradient(rgba(255, 255, 255, 0.08) 0% 25%, transparent 0% 50%) 0 0 /
    10px 10px;
}
/* 隐藏 Chromium 原生音频控件的「更多选项」溢出菜单（内含播放速度/循环播放）。
   速度调节已由右侧自制的 {rate}× ▾ 按钮提供，原生菜单是残留入口；
   仅在 Chromium（WebView2/Chrome）存在该伪元素，其他平台无此菜单，无副作用 */
.asset-audio::-webkit-media-controls-overflow-button {
  display: none;
}
</style>
