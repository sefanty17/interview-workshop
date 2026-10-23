<template>
  <MenuPage>
    <!-- 输出音频设备切换（顶部） -->
    <MenuItem title="输出音频设备">
      <template #header>
        <Speaker :size="20" class="text-amber-400" />
      </template>
      <div v-if="audioOutputSupported" class="flex w-full flex-col gap-2">
        <div class="flex w-full items-stretch gap-2">
          <div class="relative min-w-0 flex-1">
            <select
              v-model="outputDevice"
              class="output-device-select w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-brand"
              style="color-scheme: dark"
              title="选择音频输出设备"
              @change="onOutputDeviceChange"
            >
              <option value="">系统默认</option>
              <option v-for="d in outputDevices" :key="d.deviceId" :value="d.deviceId">
                {{ d.label }}{{ d.isDefault ? "（当前默认）" : "" }}
              </option>
            </select>
            <ChevronDown
              :size="14"
              class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/60"
            />
          </div>
          <button
            v-if="!labelsAvailable"
            type="button"
            class="inline-flex flex-none cursor-pointer items-center justify-center gap-1 rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors hover:border-brand hover:bg-white/20 active:bg-white/30"
            :title="permissionDenied ? '重新获取设备名称' : '获取设备名称'"
            @click="requestDeviceLabelsAndRefresh"
          >
            <ScanLine :size="15" />
            {{ permissionDenied ? "重新获取设备名称" : "获取设备名称" }}
          </button>
        </div>

        <div class="w-full">
          <span class="block truncate text-xs text-gray-300">{{ currentDeviceLabel }}</span>
          <p
            v-if="!labelsAvailable && permissionDenied"
            class="mt-1 text-xs leading-snug text-amber-400/80"
          >
            未获得授权，无法显示设备名，可在系统设置中允许后重试
          </p>
        </div>
      </div>
      <p v-else class="text-xs text-gray-400">当前环境不支持切换输出音频设备</p>
    </MenuItem>

    <!-- 音量控制 -->
    <MenuItem title="角色音量" size="small">
      <template #header>
        <MicVocal :size="20" class="text-indigo-400" />
      </template>
      <div class="b2-slider">
        <span>弱</span>
        <input
          v-model.number="characterVolume"
          class="b2-range"
          type="range"
          min="0"
          max="100"
          step="1"
          aria-label="角色音量"
        />
        <span>强</span>
      </div>
    </MenuItem>

    <MenuItem title="气泡音量" size="small">
      <template #header>
        <MessageCircle :size="20" class="text-blue-400" />
      </template>
      <div class="b2-slider">
        <span>弱</span>
        <input
          v-model.number="bubbleVolume"
          class="b2-range"
          type="range"
          min="0"
          max="100"
          step="1"
          aria-label="气泡音量"
        />
        <span>强</span>
      </div>
    </MenuItem>

    <MenuItem title="背景音量" size="small">
      <template #header>
        <AudioLines :size="20" class="text-green-400" />
      </template>
      <div class="b2-slider">
        <span>弱</span>
        <input
          v-model.number="backgroundVolume"
          class="b2-range"
          type="range"
          min="0"
          max="100"
          step="1"
          aria-label="背景音量"
        />
        <span>强</span>
      </div>
    </MenuItem>

    <MenuItem title="成就音量" size="small">
      <template #header>
        <Trophy :size="20" class="text-yellow-400" />
      </template>
      <div class="b2-slider">
        <span>弱</span>
        <input
          v-model.number="achievementVolume"
          class="b2-range"
          type="range"
          min="0"
          max="100"
          step="1"
          aria-label="成就音量"
        />
        <span>强</span>
      </div>
    </MenuItem>

    <MenuItem title="环境音音量" size="small">
      <template #header>
        <CloudRain :size="20" class="text-cyan-400" />
      </template>
      <div class="b2-slider">
        <span>弱</span>
        <input
          v-model.number="ambientVolume"
          class="b2-range"
          type="range"
          min="0"
          max="100"
          step="1"
          aria-label="环境音音量"
        />
        <span>强</span>
      </div>
    </MenuItem>

    <!-- 声音测试 -->
    <MenuItem title="声音测试" size="small">
      <template #header>
        <FlaskConical :size="20" class="text-pink-400" />
      </template>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <Button type="big" class="flex-1" @click="playCharacterTestSound">测试角色</Button>
        <Button type="big" class="flex-1" @click="playBubbleTestSound">测试气泡</Button>
        <Button type="big" class="flex-1" @click="playAchievementTestSound">测试成就</Button>
      </div>
    </MenuItem>

    <!-- 背景音乐设置 -->
    <MenuItem title="背景音乐设置">
      <template #header>
        <Headphones :size="20" class="text-purple-400" />
      </template>

      <div
        class="flex gap-3 rounded-xl border border-white/10 bg-white/5 p-4 max-[640px]:flex-col max-[640px]:gap-2"
      >
        <div
          class="flex w-[60%] items-center justify-between rounded-lg bg-black/20 px-3 py-2 text-sm font-medium text-gray-200 max-[640px]:w-full"
        >
          <span class="flex min-w-0 items-center gap-2">
            <Music :size="16" class="shrink-0 text-purple-400" />
            <select
              v-model="bgmTrack"
              class="bgm-select min-w-0 flex-1 cursor-pointer appearance-none truncate bg-transparent text-sm font-medium text-gray-200 outline-none"
              title="选择背景音乐文件"
            >
              <option value="">未选择音乐</option>
              <option v-for="t in BGM_TRACKS" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </span>
          <span class="ml-2 shrink-0 text-xs text-gray-400">{{ modeLabel }}</span>
        </div>

        <div class="flex w-[40%] items-center gap-2 max-[640px]:w-full max-[640px]:flex-wrap">
          <Button type="big" class="flex items-center justify-center gap-1" @click="handlePlayPause">
            <Pause v-if="playing" :size="16" />
            <Play v-else :size="16" />
            {{ playing ? "暂停" : "播放" }}
          </Button>
          <Button type="big" class="flex items-center justify-center gap-1" @click="handleStop">
            <Square :size="14" /> 停止
          </Button>
          <Button
            type="big"
            class="flex items-center justify-center"
            :title="modeLabel"
            @click="togglePlaybackMode"
          >
            <Repeat1 v-if="bgmMode === 'loop-single'" :size="18" />
            <Repeat v-else-if="bgmMode === 'loop-list'" :size="18" />
            <Shuffle v-else :size="18" />
          </Button>
        </div>
      </div>
    </MenuItem>
    <!-- 环境音管理 -->
    <MenuItem title="环境音管理">
      <template #header>
        <Wind :size="20" class="text-teal-400" />
      </template>

      <div class="mb-2 flex items-center justify-between">
        <span class="text-xs text-gray-400">已导入文件</span>
        <span class="text-xs text-gray-500">{{ ambientTracks.length }} 个</span>
      </div>

      <div class="flex flex-col overflow-hidden rounded-xl border border-white/10 bg-black/20">
        <div v-if="ambientTracks.length === 0" class="py-4 text-center text-sm text-gray-400">
          暂无环境音文件，请在下方添加
        </div>
        <div
          v-else
          ref="ambientListRef"
          class="custom-scrollbar max-h-[21rem] space-y-1 overflow-y-auto p-1.5"
        >
          <div
            v-for="track in ambientTracks"
            :key="track.id"
            class="group flex flex-col gap-1.5 rounded-lg bg-cyan-500/10 px-3 py-2 transition-all duration-200"
          >
            <div class="flex items-center gap-2">
              <CloudRain :size="14" class="shrink-0 text-cyan-400" />
              <span class="flex-1 truncate text-sm text-gray-200">{{ track.name }}</span>
              <button
                type="button"
                class="rounded p-1 text-gray-400 transition-colors hover:bg-white/10 hover:text-white"
                :title="track.paused ? '恢复播放' : '暂停'"
                @click="toggleTrackPause(track.id)"
              >
                <Play v-if="track.paused" :size="12" />
                <Pause v-else :size="12" />
              </button>
              <button
                type="button"
                class="rounded bg-red-500/10 p-1 text-red-400 transition-colors hover:bg-red-500/40 hover:text-white"
                title="删除"
                @click="removeTrack(track.id)"
              >
                <Trash2 :size="12" />
              </button>
            </div>
            <div class="flex items-center gap-2 pl-6">
              <span class="w-8 shrink-0 text-xs text-gray-400">音量</span>
              <input
                class="b2-range min-w-0 flex-1"
                type="range"
                min="0"
                max="100"
                step="1"
                :value="track.volume"
                :aria-label="`${track.name} 音量`"
                @input="setTrackVolume(track.id, Number(($event.target as HTMLInputElement).value))"
              />
              <span class="w-8 shrink-0 text-right text-xs text-gray-400">{{ track.volume }}</span>
            </div>
            <div class="flex items-center gap-2 pl-6">
              <span class="w-8 shrink-0 text-xs text-gray-400">循环</span>
              <select
                class="ambient-select min-w-0 flex-1 cursor-pointer rounded-lg border border-white/15 bg-white/10 px-2 py-1 text-xs text-gray-200 outline-none focus:border-brand"
                title="选择循环模式"
                :value="track.loop"
                @change="
                  setTrackLoop(
                    track.id,
                    ($event.target as HTMLSelectElement).value as AmbientTrack['loop'],
                  )
                "
              >
                <option value="single">单曲循环</option>
                <option value="list">列表循环</option>
                <option value="once">不循环</option>
              </select>
            </div>
          </div>
        </div>
      </div>

      <div class="mt-2 flex items-center gap-3">
        <Button
          type="big"
          class="flex flex-1 items-center justify-center gap-2"
          @click="addTrack"
        >
          <Plus :size="16" /> 添加环境音
        </Button>
        <Button
          type="big"
          class="flex flex-1 items-center justify-center gap-1"
          :disabled="ambientTracks.length === 0"
          @click="stopAllTracks"
        >
          <Square :size="14" /> 全部停止
        </Button>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  AudioLines,
  ChevronDown,
  CloudRain,
  FlaskConical,
  Headphones,
  MessageCircle,
  MicVocal,
  Music,
  Pause,
  Play,
  Plus,
  Repeat,
  Repeat1,
  ScanLine,
  Shuffle,
  Speaker,
  Square,
  Trash2,
  Trophy,
  Wind,
} from "@lucide/vue";
import { Button } from "../../base";
import { MenuItem, MenuPage } from "../../ui";
import { notify, persist, store, updateSettings } from "../../../store/appStore";
import type { AmbientTrack } from "../../../data/mock";

// ========== 音量（原版 audio.characterVolume 等） ==========
const characterVolume = computed({
  get: () => store.settings.volumeSpeaker,
  set: (v: number) => updateSettings({ volumeSpeaker: v }),
});
const bubbleVolume = computed({
  get: () => store.settings.volumeSfx,
  set: (v: number) => updateSettings({ volumeSfx: v }),
});
const backgroundVolume = computed({
  get: () => store.settings.volumeBgm,
  set: (v: number) => updateSettings({ volumeBgm: v }),
});
const achievementVolume = computed({
  get: () => store.settings.volumeAchievement,
  set: (v: number) => updateSettings({ volumeAchievement: v }),
});
const ambientVolume = computed({
  get: () => store.settings.volumeAmbient,
  set: (v: number) => updateSettings({ volumeAmbient: v }),
});

// ========== 输出音频设备 ==========
interface OutputDevice {
  deviceId: string;
  label: string;
  isDefault: boolean;
}

const audioOutputSupported = ref(
  typeof navigator !== "undefined" && Boolean(navigator.mediaDevices?.enumerateDevices),
);
const outputDevices = ref<OutputDevice[]>([]);
const labelsAvailable = ref(false);
const permissionDenied = ref(false);

const outputDevice = computed({
  // 旧数据里 "default" 与空串同义（跟随系统默认）
  get: () => (store.settings.outputDevice === "default" ? "" : store.settings.outputDevice),
  set: (v: string) => updateSettings({ outputDevice: v }),
});

const currentDeviceLabel = computed(() => {
  if (!outputDevice.value) return "当前使用系统默认输出设备";
  const found = outputDevices.value.find((d) => d.deviceId === outputDevice.value);
  return found ? `当前输出：${found.label}` : "当前输出设备不可用（已回退系统默认）";
});

async function refreshDevices() {
  if (!audioOutputSupported.value) return;
  try {
    const list = await navigator.mediaDevices.enumerateDevices();
    const outs = list.filter((d) => d.kind === "audiooutput" && d.deviceId);
    labelsAvailable.value = outs.some((d) => Boolean(d.label));
    outputDevices.value = outs.map((d, index) => ({
      deviceId: d.deviceId,
      label: d.label || `输出设备 ${index + 1}`,
      isDefault: d.deviceId === "default",
    }));
  } catch {
    outputDevices.value = [];
    labelsAvailable.value = false;
  }
}

async function requestDeviceLabelsAndRefresh() {
  await refreshDevices();
  if (!labelsAvailable.value) {
    permissionDenied.value = true;
    notify("未获得设备授权，仍使用系统默认输出设备");
    return;
  }
  permissionDenied.value = false;
  notify(`已获取 ${outputDevices.value.length} 个输出设备名称`);
}

function onOutputDeviceChange() {
  notify(currentDeviceLabel.value);
}

// ========== 音频合成（Web Audio，用于声音测试与背景音乐） ==========
let audioContext: AudioContext | null = null;

function getAudioContext(): AudioContext {
  if (!audioContext) audioContext = new AudioContext();
  if (audioContext.state === "suspended") void audioContext.resume();
  return audioContext;
}

/** 音量 0~100 → 增益；duration 秒 */
function playTone(freq: number, duration: number, volume: number, delay = 0, type: OscillatorType = "sine") {
  const ctx = getAudioContext();
  const start = ctx.currentTime + delay;
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  const peak = Math.max(0.0001, (volume / 100) * 0.25);
  osc.type = type;
  osc.frequency.setValueAtTime(freq, start);
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(peak, start + 0.02);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
  osc.connect(gain);
  gain.connect(ctx.destination);
  osc.start(start);
  osc.stop(start + duration + 0.05);
}

function playCharacterTestSound() {
  playTone(392, 0.18, characterVolume.value);
  playTone(523, 0.24, characterVolume.value, 0.16);
  notify(`已播放角色音量测试 · 音量 ${characterVolume.value}`);
}

function playBubbleTestSound() {
  playTone(880, 0.08, bubbleVolume.value, 0, "triangle");
  playTone(1174, 0.07, bubbleVolume.value, 0.09, "triangle");
  notify(`已播放气泡音量测试 · 音量 ${bubbleVolume.value}`);
}

function playAchievementTestSound() {
  playTone(523, 0.16, achievementVolume.value);
  playTone(659, 0.16, achievementVolume.value, 0.14);
  playTone(784, 0.3, achievementVolume.value, 0.28);
  notify(`已播放成就音量测试 · 音量 ${achievementVolume.value}`);
}

// ========== 背景音乐 ==========
const BGM_TRACKS = [
  { value: "waiting", label: "面试等候 · 轻钢琴" },
  { value: "focus", label: "专注 · 环境铺底" },
  { value: "rain", label: "窗外小雨 · 白噪音" },
] as const;

const MODES = ["loop-list", "loop-single", "random"] as const;
type PlaybackMode = (typeof MODES)[number];

const bgmTrack = computed({
  // 旧数据里的曲目名已不在列表内（如 "calm"），统一按未选择处理
  get: () =>
    BGM_TRACKS.some((t) => t.value === store.settings.bgmTrack)
      ? store.settings.bgmTrack
      : "",
  set: (v: string) => updateSettings({ bgmTrack: v }),
});
const bgmMode = computed({
  get: () => store.settings.bgmMode,
  set: (v: PlaybackMode) => updateSettings({ bgmMode: v }),
});

const modeLabel = computed(
  () =>
    ({ "loop-list": "列表循环", "loop-single": "单曲循环", random: "随机播放" })[bgmMode.value],
);

const playing = ref(false);
let bgmTimer: number | null = null;
let bgmStep = 0;
const PENTATONIC = [392, 440, 523, 587, 659, 587, 523, 440];

function bgmTick() {
  const note = PENTATONIC[bgmStep % PENTATONIC.length];
  playTone(note, 1.6, backgroundVolume.value * 0.45);
  bgmStep = bgmMode.value === "random" ? Math.floor(Math.random() * PENTATONIC.length) : bgmStep + 1;
}

function startBgm() {
  stopBgmTimer();
  bgmTick();
  bgmTimer = window.setInterval(bgmTick, 900);
}

function stopBgmTimer() {
  if (bgmTimer !== null) {
    window.clearInterval(bgmTimer);
    bgmTimer = null;
  }
}

function handlePlayPause() {
  if (!bgmTrack.value) {
    notify("请先在左侧选择音乐文件");
    return;
  }
  playing.value = !playing.value;
  if (playing.value) {
    startBgm();
    notify(`正在播放：${BGM_TRACKS.find((t) => t.value === bgmTrack.value)?.label ?? "背景音乐"}`);
  } else {
    stopBgmTimer();
    notify("背景音乐已暂停");
  }
}

function handleStop() {
  playing.value = false;
  stopBgmTimer();
  bgmStep = 0;
  bgmTrack.value = "";
  notify("背景音乐已停止");
}

function togglePlaybackMode() {
  const next = MODES[(MODES.indexOf(bgmMode.value) + 1) % MODES.length];
  bgmMode.value = next;
  notify(`播放模式：${modeLabel.value}`);
}

watch(playing, (value) => {
  if (!value) stopBgmTimer();
});

// ========== 环境音轨道（原版 uiStore.ambientTracks） ==========
const AMBIENT_PRESETS = ["海浪声", "风声", "壁炉柴火", "图书馆底噪", "夏夜虫鸣"];

const ambientTracks = computed(() => store.settings.ambientTracks);
const ambientListRef = ref<HTMLElement | null>(null);

function setTrackVolume(id: string, volume: number) {
  const track = store.settings.ambientTracks.find((t) => t.id === id);
  if (!track) return;
  track.volume = volume;
  persist();
}

function setTrackLoop(id: string, loop: AmbientTrack["loop"]) {
  const track = store.settings.ambientTracks.find((t) => t.id === id);
  if (!track) return;
  track.loop = loop;
  persist();
  notify(`「${track.name}」循环模式：${LOOP_LABELS[loop]}`);
}

const LOOP_LABELS: Record<AmbientTrack["loop"], string> = {
  single: "单曲循环",
  list: "列表循环",
  once: "不循环",
};

function toggleTrackPause(id: string) {
  const track = store.settings.ambientTracks.find((t) => t.id === id);
  if (!track) return;
  const next = store.settings.ambientTracks.map((t) =>
    t.id === id ? { ...t, paused: !t.paused } : t,
  );
  updateSettings({ ambientTracks: next });
  notify(`「${track.name}」已${track.paused ? "暂停" : "恢复播放"}`);
}

function addTrack() {
  const name =
    AMBIENT_PRESETS.find((n) => !store.settings.ambientTracks.some((t) => t.name === n)) ??
    `环境音 ${store.settings.ambientTracks.length + 1}`;
  const track: AmbientTrack = {
    id: `amb-${Date.now()}`,
    name,
    volume: 60,
    loop: "list",
    paused: false,
  };
  updateSettings({ ambientTracks: [...store.settings.ambientTracks, track] });
  notify(`已添加环境音「${name}」`);
  // 新增的轨道在列表末尾，滚到底让用户看到刚加的那条
  void nextTick(() => {
    const list = ambientListRef.value;
    if (list) list.scrollTop = list.scrollHeight;
  });
}

function removeTrack(id: string) {
  const track = store.settings.ambientTracks.find((t) => t.id === id);
  updateSettings({ ambientTracks: store.settings.ambientTracks.filter((t) => t.id !== id) });
  notify(track ? `已删除环境音「${track.name}」` : "已删除环境音");
}

function stopAllTracks() {
  updateSettings({ ambientTracks: store.settings.ambientTracks.map((t) => ({ ...t, paused: true })) });
  notify("已停止全部环境音");
}

onMounted(() => {
  void refreshDevices();
});

onUnmounted(() => {
  stopBgmTimer();
  if (audioContext) {
    void audioContext.close();
    audioContext = null;
  }
});
</script>

<style scoped>
/* 原生 select 下拉弹层跟随深色主题，避免白底白字 */
.output-device-select,
.bgm-select {
  color-scheme: dark;
}
.output-device-select option,
.bgm-select option {
  background-color: #1e1e2e;
  color: #e2e8f0;
}
.output-device-select option:checked {
  background-color: #3b82f6;
  color: #ffffff;
}

/* 环境音循环下拉：与深色主题保持一致 */
.ambient-select {
  color-scheme: dark;
}
.ambient-select option {
  background-color: #1e1e2e;
  color: #e2e8f0;
}

/* ===== 滑块：原生 range + 原版自定义轨道/滑块头 ===== */
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
  -webkit-appearance: none;
  position: relative;
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
  -webkit-appearance: none;
  margin-top: -7px;
  position: relative;
  border-radius: 50%;
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
