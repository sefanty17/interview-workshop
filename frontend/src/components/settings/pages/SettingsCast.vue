<template>
  <div
    class="flex h-full min-h-0 w-full flex-wrap items-start gap-5 overflow-y-auto px-3 py-6 text-white text-shadow-2xs"
  >
    <MenuItem title="投屏" size="small">
      <template #header>
        <Cast :size="20" class="text-cyan-400" />
      </template>
      <button
        type="button"
        role="switch"
        :aria-checked="store.settings.enabled"
        aria-label="启动应用时自动打开投屏窗口并开启串流"
        class="toggle-control"
        @click="onToggleEnabled"
      >
        <span
          class="toggle-track"
          :class="store.settings.enabled ? 'toggle-track-on' : 'toggle-track-off'"
        >
          <span
            class="toggle-knob"
            :class="store.settings.enabled ? 'toggle-knob-on' : 'toggle-knob-off'"
          ></span>
        </span>
        <span class="min-w-0 flex-1 text-left">启动应用时自动打开投屏窗口并开启串流</span>
      </button>
    </MenuItem>

    <MenuItem title="串流端口" size="small">
      <template #header>
        <PlugZap :size="20" class="text-orange-400" />
      </template>
      <div class="flex w-full flex-col gap-2">
        <div class="flex w-full items-center gap-2">
          <input
            v-model.number="store.settings.port"
            type="number"
            min="1"
            max="65535"
            class="cast-num-input"
            style="color-scheme: dark"
            @change="onPortChange"
          />
        </div>
        <p class="text-xs text-white/50">局域网设备访问的端口（改端口后需重启串流生效）</p>
      </div>
    </MenuItem>

    <MenuItem title="性能参数" size="small">
      <template #header>
        <Gauge :size="20" class="text-pink-400" />
      </template>
      <div class="flex w-full flex-col gap-3">
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">帧率（{{ store.settings.fps }}）</span>
          <div class="b2-slider">
            <span>1</span>
            <input
              v-model.number="store.settings.fps"
              class="b2-range"
              type="range"
              min="1"
              max="30"
              step="1"
              aria-label="帧率"
              @change="persist"
            />
            <span>30</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">画质（{{ store.settings.quality }}）</span>
          <div class="b2-slider">
            <span>1</span>
            <input
              v-model.number="store.settings.quality"
              class="b2-range"
              type="range"
              min="1"
              max="100"
              step="1"
              aria-label="画质"
              @change="persist"
            />
            <span>100</span>
          </div>
        </div>
        <p class="text-xs text-white/50">
          帧率越高画面越流畅、画质越高体积越大，两者都更耗 CPU；小屏设备建议调低。
        </p>
      </div>
    </MenuItem>

    <MenuItem title="输出分辨率" size="small">
      <template #header>
        <PictureInPicture2 :size="20" class="text-purple-400" />
      </template>
      <div class="flex w-full flex-col gap-2">
        <div class="flex w-full items-center gap-2">
          <input
            v-model.number="store.settings.castWidth"
            type="number"
            min="0"
            max="1920"
            step="1"
            class="cast-num-input"
            style="color-scheme: dark"
            aria-label="输出宽度"
            @change="onResolutionChange"
          />
          <span class="text-white/50">×</span>
          <input
            v-model.number="store.settings.castHeight"
            type="number"
            min="0"
            max="1920"
            step="1"
            class="cast-num-input"
            style="color-scheme: dark"
            aria-label="输出高度"
            @change="onResolutionChange"
          />
        </div>
        <p class="text-xs text-white/50">
          0 × 0 = 跟随投屏窗口当前尺寸；填宽高则按设定尺寸串流（如 320×240），下一路连接生效。
        </p>
      </div>
    </MenuItem>

    <MenuItem title="色彩增强（vivid）" size="small">
      <template #header>
        <Palette :size="20" class="text-amber-400" />
      </template>
      <button
        type="button"
        role="switch"
        :aria-checked="store.settings.vivid"
        aria-label="色彩增强"
        class="toggle-control"
        @click="onVividChange"
      >
        <span
          class="toggle-track"
          :class="store.settings.vivid ? 'toggle-track-on' : 'toggle-track-off'"
        >
          <span
            class="toggle-knob"
            :class="store.settings.vivid ? 'toggle-knob-on' : 'toggle-knob-off'"
          ></span>
        </span>
        <span class="min-w-0 flex-1 text-left">
          串流时提升饱和度与对比度，画面更鲜艳（复刻 cast_sender.py 的 --vivid，下一路连接生效）
        </span>
      </button>
    </MenuItem>

    <MenuItem title="隐藏对话框" size="small">
      <template #header>
        <MessageSquareOff :size="20" class="text-rose-400" />
      </template>
      <button
        type="button"
        role="switch"
        :aria-checked="store.settings.dialogHidden"
        aria-label="隐藏对话框"
        class="toggle-control"
        @click="onDialogHiddenChange"
      >
        <span
          class="toggle-track"
          :class="store.settings.dialogHidden ? 'toggle-track-on' : 'toggle-track-off'"
        >
          <span
            class="toggle-knob"
            :class="store.settings.dialogHidden ? 'toggle-knob-on' : 'toggle-knob-off'"
          ></span>
        </span>
        <span class="min-w-0 flex-1 text-left">
          开启后投屏不显示对话框，只保留背景与面试官舞台
        </span>
      </button>
    </MenuItem>

    <MenuItem title="面试官调整" size="small">
      <template #header>
        <Scan :size="20" class="text-orange-400" />
      </template>
      <div class="flex w-full flex-col gap-3">
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            面试官缩放（{{ store.settings.charScale.toFixed(2) }}）
          </span>
          <div class="b2-slider">
            <span>0.5</span>
            <input
              v-model.number="store.settings.charScale"
              class="b2-range"
              type="range"
              min="0.5"
              max="2"
              step="0.05"
              aria-label="面试官缩放"
              @change="onCharTuneChange"
            />
            <span>2</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            水平偏移（{{ Math.round(store.settings.charOffsetX) }}px）
          </span>
          <div class="b2-slider">
            <span>-400</span>
            <input
              v-model.number="store.settings.charOffsetX"
              class="b2-range"
              type="range"
              min="-400"
              max="400"
              step="10"
              aria-label="水平偏移"
              @change="onCharTuneChange"
            />
            <span>400</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            垂直偏移（{{ Math.round(store.settings.charOffsetY) }}px）
          </span>
          <div class="b2-slider">
            <span>-400</span>
            <input
              v-model.number="store.settings.charOffsetY"
              class="b2-range"
              type="range"
              min="-400"
              max="400"
              step="10"
              aria-label="垂直偏移"
              @change="onCharTuneChange"
            />
            <span>400</span>
          </div>
        </div>
        <p class="text-xs text-white/50">
          面试官舞台层的缩放与偏移，默认 1.0 / 0 / 0 与主界面一致，改动即时广播到已打开的投屏窗口。
        </p>
      </div>
    </MenuItem>

    <MenuItem title="对话框调整" size="small">
      <template #header>
        <AlignLeft :size="20" class="text-sky-400" />
      </template>
      <div class="flex w-full flex-col gap-3">
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            对话框宽度（{{ Math.round(store.settings.dialogWidth) }}%）
          </span>
          <div class="b2-slider">
            <span>30</span>
            <input
              v-model.number="store.settings.dialogWidth"
              class="b2-range"
              type="range"
              min="30"
              max="100"
              step="5"
              aria-label="对话框宽度"
              @change="onDialogTuneChange"
            />
            <span>100</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            对话框高度（{{ Math.round(store.settings.dialogHeight) }}vh）
          </span>
          <div class="b2-slider">
            <span>10</span>
            <input
              v-model.number="store.settings.dialogHeight"
              class="b2-range"
              type="range"
              min="10"
              max="100"
              step="5"
              aria-label="对话框高度"
              @change="onDialogTuneChange"
            />
            <span>100</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            字体大小（{{ Math.round(store.settings.dialogFontSize) }}px）
          </span>
          <div class="b2-slider">
            <span>12</span>
            <input
              v-model.number="store.settings.dialogFontSize"
              class="b2-range"
              type="range"
              min="12"
              max="40"
              step="1"
              aria-label="字体大小"
              @change="onDialogTuneChange"
            />
            <span>40</span>
          </div>
        </div>
        <div class="w-full">
          <span class="mb-1 block text-xs text-white/70">
            背景透明度（{{ Math.round(store.settings.dialogBgOpacity) }}%）
          </span>
          <div class="b2-slider">
            <span>0</span>
            <input
              v-model.number="store.settings.dialogBgOpacity"
              class="b2-range"
              type="range"
              min="0"
              max="100"
              step="5"
              aria-label="背景透明度"
              @change="onDialogTuneChange"
            />
            <span>100</span>
          </div>
        </div>
        <p class="text-xs text-white/50">
          宽度控制左右的留白（100% 为无留白）、高度控制整体元素高度，字体大小与背景透明度也在此设置，改动即时广播到已打开的投屏窗口。
        </p>
      </div>
    </MenuItem>

    <MenuItem title="控制" size="small">
      <template #header>
        <MonitorPlay :size="20" class="text-green-400" />
      </template>
      <div class="flex w-full flex-wrap gap-2">
        <Button type="big" :disabled="castWindowOpen" @click="openWindow">打开投屏窗口</Button>
        <Button type="big" :disabled="!castWindowOpen" @click="closeWindow">关闭投屏窗口</Button>
        <Button type="big" :disabled="running" @click="startCast">开启串流</Button>
        <Button type="big" :disabled="!running" @click="stopCast">停止串流</Button>
      </div>
      <p class="mt-2 text-xs text-white/50">
        未设置输出分辨率时，串流画面比例跟随投屏窗口大小。
      </p>
      <p v-if="statusMsg" class="mt-2 text-sm" :class="statusMsgColor">{{ statusMsg }}</p>
    </MenuItem>

    <MenuItem title="连接信息" size="small">
      <template #header>
        <Wifi :size="20" class="text-blue-400" />
      </template>
      <template v-if="running">
        <div class="flex w-full flex-col gap-2">
          <div class="rounded-xl border border-white/10 bg-black/20 p-3">
            <div class="mb-1 text-xs text-white/50">
              MJPEG 流地址（ESP32 / HoloCubic / VLC / ffmpeg 拉流）
            </div>
            <div class="cast-url">{{ streamUrl }}</div>
            <div class="mt-2 mb-1 text-xs text-white/50">
              预览页地址（电脑 / 树莓派浏览器直接看）
            </div>
            <div class="cast-url">{{ pageUrl }}</div>
          </div>
          <p class="text-xs text-white/50">
            小屏设备可在流地址后加 ?w=320&h=240&fps=15&quality=80 指定输出分辨率。
          </p>
        </div>
      </template>
      <p v-else class="text-sm text-white/60">
        串流未开启，点击上方「开启串流」后这里会显示局域网地址。
      </p>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import {
  AlignLeft,
  Cast,
  Gauge,
  MessageSquareOff,
  MonitorPlay,
  Palette,
  PictureInPicture2,
  PlugZap,
  Scan,
  Wifi,
} from "@lucide/vue";
import { ref } from "vue";
import { Button } from "../../base";
import { MenuItem } from "../../ui";
import { persist, store } from "../../../store/appStore";
import { alertDialog } from "../../../store/dialogStore";

const running = ref(false);
const castWindowOpen = ref(false);
const streamUrl = ref("");
const pageUrl = ref("");
const statusMsg = ref("");
const statusMsgColor = ref("text-white/60");
let msgTimer: number | undefined;

function setMsg(message: string, color = "text-white/60"): void {
  statusMsg.value = message;
  statusMsgColor.value = color;
  window.clearTimeout(msgTimer);
  msgTimer = window.setTimeout(() => {
    statusMsg.value = "";
  }, 6000);
}

function serviceAlert(title: string): void {
  void alertDialog(title, "需要后端服务，当前未接入");
}

function onToggleEnabled(): void {
  store.settings.enabled = !store.settings.enabled;
  persist();
  if (store.settings.enabled) serviceAlert("投屏");
}

function onPortChange(): void {
  const value = Math.round(Number(store.settings.port));
  store.settings.port = Number.isFinite(value) ? Math.min(65535, Math.max(1, value)) : 1470;
  persist();
  setMsg("端口已保存，重新「开启串流」后生效。");
}

function onResolutionChange(): void {
  const width = Math.round(Number(store.settings.castWidth));
  const height = Math.round(Number(store.settings.castHeight));
  store.settings.castWidth = Number.isFinite(width) ? Math.min(1920, Math.max(0, width)) : 0;
  store.settings.castHeight = Number.isFinite(height) ? Math.min(1920, Math.max(0, height)) : 0;
  persist();
  setMsg("分辨率已保存，下一路连接生效。");
}

function onVividChange(): void {
  store.settings.vivid = !store.settings.vivid;
  persist();
}

function onDialogHiddenChange(): void {
  store.settings.dialogHidden = !store.settings.dialogHidden;
  persist();
  setMsg("投屏调参已保存。");
}

function onCharTuneChange(): void {
  persist();
  setMsg("投屏调参已保存。");
}

function onDialogTuneChange(): void {
  persist();
  setMsg("投屏调参已保存。");
}

function openWindow(): void {
  serviceAlert("打开投屏窗口");
}

function closeWindow(): void {
  serviceAlert("关闭投屏窗口");
}

function startCast(): void {
  serviceAlert("开启串流");
}

function stopCast(): void {
  serviceAlert("停止串流");
}
</script>

<style scoped>
.cast-num-input {
  width: 120px;
  padding: 8px 10px;
  color: #fff;
  font-size: 14px;
  font-family: ui-monospace, Consolas, monospace;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 8px;
  outline: none;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.cast-num-input:focus {
  border-color: var(--accent-color);
  box-shadow: 0 0 0 3px rgba(0, 123, 255, 0.2);
}

.cast-url {
  font:
    13px/1.6 ui-monospace,
    Consolas,
    monospace;
  color: #71f59b;
  word-break: break-all;
  user-select: text;
}

.toggle-control {
  display: inline-flex;
  width: 100%;
  cursor: pointer;
  align-items: center;
  border: 0;
  background: transparent;
  padding: 0;
  color: #fff;
  font-size: 0.875rem;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}
.toggle-track {
  position: relative;
  display: inline-block;
  width: 3.125rem;
  height: 1.625rem;
  margin-right: 0.5rem;
  flex-shrink: 0;
  border-radius: 13px;
  transition: all 0.3s ease-in-out;
}
.toggle-track-on {
  border-color: var(--accent-color);
  background: rgba(121, 217, 255, 0.3);
  box-shadow: 0 0 10px rgba(121, 217, 255, 0.3);
}
.toggle-track-off {
  border-color: rgba(255, 255, 255, 0.3);
  background: rgba(255, 255, 255, 0.2);
}
.toggle-knob {
  position: absolute;
  top: 50%;
  width: 1.25rem;
  height: 1.25rem;
  transform: translateY(-50%);
  border-radius: 9999px;
  transition: all 0.3s ease-in-out;
}
.toggle-knob-on {
  left: 1.625rem;
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
  box-shadow:
    0 3px 8px rgba(121, 217, 255, 0.4),
    0 1px 3px rgba(0, 0, 0, 0.2);
}
.toggle-knob-off {
  left: 0.25rem;
  background: linear-gradient(135deg, #fff, #f0f0f0);
  box-shadow:
    0 2px 6px rgba(0, 0, 0, 0.3),
    0 1px 2px rgba(0, 0, 0, 0.1);
}

.b2-slider {
  display: flex;
  align-items: center;
  gap: 15px;
  color: rgba(255, 255, 255, 0.9);
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}
.b2-slider > span {
  flex: none;
  white-space: nowrap;
}
.b2-range {
  flex-grow: 1;
  min-width: 0;
  outline: none;
  margin: 10px 0;
  appearance: none;
  position: relative;
  -webkit-appearance: none;
  background-color: transparent;
}
.b2-range::-webkit-slider-runnable-track {
  width: 100%;
  height: 8px;
  border-radius: 4px;
  transition: all 0.3s ease;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.2);
}
.b2-range:hover::-webkit-slider-runnable-track {
  height: 10px;
  background: rgba(255, 255, 255, 0.25);
}
.b2-range::-webkit-slider-thumb {
  z-index: 2;
  width: 22px;
  height: 22px;
  cursor: grab;
  appearance: none;
  margin-top: -7px;
  position: relative;
  border-radius: 50%;
  -webkit-appearance: none;
  transform-origin: center;
  border: 2px solid rgba(255, 255, 255, 0.8);
  transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
  box-shadow:
    0 4px 12px rgba(121, 217, 255, 0.4),
    0 2px 4px rgba(0, 0, 0, 0.2);
}
.b2-range::-webkit-slider-thumb:hover,
.b2-range::-webkit-slider-thumb:active {
  transform: scale(1.15);
  background: linear-gradient(135deg, #64b5f6, var(--accent-color));
  box-shadow:
    0 6px 20px rgba(121, 217, 255, 0.6),
    0 4px 8px rgba(0, 0, 0, 0.3);
}
.b2-range:active::-webkit-slider-thumb {
  cursor: grabbing;
}
.b2-range::-moz-range-track {
  width: 100%;
  height: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.b2-range::-moz-range-thumb {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.8);
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
}
</style>
