<template>
  <section class="space-y-4 rounded-xl border border-white/10 bg-white/5 p-4">
    <h4 class="text-sm font-semibold text-white/80">角色语音试听</h4>
    <p class="text-xs leading-relaxed text-white/50">
      使用上方当前配置合成试听音频；沿用全局语音服务地址与密钥。远程服务将收到试听文本，并按其规则计费。
    </p>
    <div v-if="modelValue.tts_type === 'gsv'" class="space-y-3 rounded-lg bg-black/15 p-3">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h5 class="text-sm text-white/75">参考音频</h5>
        <button type="button" class="voice-button" :disabled="referenceBusy" @click="chooseReference">
          选择本机音频
        </button>
      </div>
      <p class="text-xs break-all text-white/60">
        {{ referencePath || "尚未填写参考音频路径" }}
      </p>
      <p class="text-xs leading-relaxed text-white/45">
        GPT-SoVITS 按服务器文件系统读取此路径。本机选择适用于同机服务；远程服务请在上方填写服务器路径，本机预听不可用时仍可合成试听。选择后保留在草稿，点击底部保存更改后生效。
      </p>
      <button
        type="button"
        class="voice-button"
        :disabled="!referencePath || referenceBusy"
        @click="loadReference"
      >
        {{ referenceBusy ? "正在读取…" : "读取并预听本机文件" }}
      </button>
      <audio
        v-if="referenceUrl"
        ref="referenceAudio"
        :key="referenceUrl"
        class="h-10 w-full"
        controls
        preload="metadata"
        :src="referenceUrl"
        @loadedmetadata="referenceDuration = audioDuration($event)"
        @error="referenceError = '当前设备无法播放此音频，请检查文件内容或音频格式'"
      />
      <p v-if="referenceUrl" class="text-xs text-white/60">
        音频大小 {{ formatBytes(referenceBytes) }} · 时长 {{ formatDuration(referenceDuration) }}
      </p>
      <p v-if="referenceError" role="alert" class="text-xs leading-relaxed text-red-200">
        {{ referenceError }}
      </p>
    </div>
    <label class="block space-y-2 text-sm text-white/65">
      <span>试听文本</span>
      <textarea
        v-model="previewText"
        rows="3"
        maxlength="500"
        class="voice-control w-full resize-y"
        placeholder="输入一段与所选语音语言一致的文本…"
      />
    </label>
    <div class="flex flex-wrap items-end gap-3">
      <label class="flex min-w-32 flex-col gap-2 text-xs text-white/65">
        <span>试听情绪</span>
        <select v-model="emotion" class="voice-control">
          <option v-for="name in emotions" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>
      <button
        type="button"
        class="voice-button bg-cyan-400/15! text-cyan-100!"
        :disabled="busy || !modelValue.tts_type || !previewText.trim()"
        @click="synthesize"
      >
        {{ busy ? "正在合成…" : "生成试听" }}
      </button>
      <span class="ml-auto text-xs text-white/40">{{ Array.from(previewText).length }} / 500</span>
    </div>
    <p class="text-xs text-white/45">试听直接朗读输入文本，不进行翻译；情绪仅对支持它的语音服务生效。</p>
    <p v-if="stale" class="text-xs text-amber-100/75">配置或文本已修改，下方为上一次的试听结果，请重新生成。</p>
    <p v-if="errorMessage" role="alert" class="text-xs break-words text-red-200">
      {{ errorMessage }}
    </p>
    <div v-if="previewUrl" class="space-y-2 rounded-lg bg-black/15 p-3">
      <audio
        :key="previewUrl"
        ref="previewAudio"
        class="h-10 w-full"
        controls
        preload="metadata"
        :src="previewUrl"
        @loadedmetadata="previewDuration = audioDuration($event)"
        @error="errorMessage = '当前设备无法播放此音频，请检查文件内容或音频格式'"
      />
      <p class="text-xs text-white/60">
        音频大小 {{ formatBytes(previewBytes) }} · 时长 {{ formatDuration(previewDuration) }}
      </p>
    </div>
    <p v-if="!modelValue.tts_type" class="text-xs text-white/50">请先在上方选择语音服务。</p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from "vue";
import { requireBackend } from "../../../utils/backendStub";

const props = defineProps<{ roleId: string; modelValue: Record<string, any> }>();
const previewText = ref("面试官你好，我先简单介绍一下我的项目经历。");
const emotion = ref("正常");
const emotions = ["正常", "高兴", "伤心", "生气", "害怕", "厌恶", "惊讶", "平静", "心动"];
const busy = ref(false);
const referenceBusy = ref(false);
const errorMessage = ref("");
const referenceError = ref("");
const previewAudio = ref<HTMLAudioElement | null>(null);
const referenceAudio = ref<HTMLAudioElement | null>(null);
const previewUrl = ref("");
const referenceUrl = ref("");
const previewBytes = ref(0);
const referenceBytes = ref(0);
const previewDuration = ref<number | null>(null);
const referenceDuration = ref<number | null>(null);
const generatedFor = ref("");
let sequence = 0;
let referenceSequence = 0;
let disposed = false;
const referencePath = computed(() =>
  String(props.modelValue.voice_models?.gsv_voice_filename ?? ""),
);
const signature = computed(() =>
  JSON.stringify([
    props.roleId,
    props.modelValue.tts_type,
    props.modelValue.voice_lang,
    props.modelValue.voice_dialect,
    props.modelValue.voice_models,
    previewText.value,
    emotion.value,
  ]),
);
const stale = computed(() => Boolean(previewUrl.value && generatedFor.value !== signature.value));

function replaceUrl(target: typeof previewUrl, bytes?: ArrayBuffer | number[]) {
  (target === previewUrl ? previewAudio : referenceAudio).value?.pause();
  if (target.value) URL.revokeObjectURL(target.value);
  target.value = "";
  if (bytes) target.value = URL.createObjectURL(new Blob([new Uint8Array(bytes)]));
}
function formatBytes(size: number) {
  return `${(size / 1024).toFixed(1)} KB`;
}
function formatDuration(duration: number | null) {
  return duration === null ? "—" : `${duration.toFixed(2)} s`;
}
function audioDuration(event: Event) {
  const value = (event.target as HTMLAudioElement).duration;
  return Number.isFinite(value) ? value : null;
}

// 本机选文件、读参考音频、调用 TTS 合成都要走后端（Tauri / 音频服务），这里统一给提示
async function chooseReference() {
  referenceBusy.value = true;
  referenceError.value = "";
  try {
    await requireBackend(
      "「选择本机音频」需要后端的文件对话框与角色资源目录读写，当前为纯前端演示。可在上方 «gsv_voice_filename» 字段直接填写服务端可访问的路径。",
    );
  } finally {
    referenceBusy.value = false;
  }
}
async function loadReference() {
  const token = ++referenceSequence;
  referenceBusy.value = true;
  referenceError.value = "";
  replaceUrl(referenceUrl);
  referenceDuration.value = null;
  try {
    await requireBackend("读取并预听参考音频需要后端读取服务器上的音频文件，当前为纯前端演示。");
  } finally {
    if (!disposed && token === referenceSequence) referenceBusy.value = false;
  }
}
async function synthesize() {
  const token = ++sequence;
  busy.value = true;
  errorMessage.value = "";
  replaceUrl(previewUrl);
  previewDuration.value = null;
  try {
    await requireBackend(
      `合成「${emotion.value}」情绪的试听音频需要后端 TTS 服务（当前配音服务：${String(props.modelValue.tts_type || "未选择")}），暂不可用。`,
    );
  } finally {
    if (!disposed && token === sequence) busy.value = false;
  }
}
watch(referencePath, () => {
  ++referenceSequence;
  referenceBusy.value = false;
  referenceError.value = "";
  replaceUrl(referenceUrl);
});
watch(
  () => props.roleId,
  () => {
    ++sequence;
    ++referenceSequence;
    busy.value = false;
    referenceBusy.value = false;
    errorMessage.value = "";
    referenceError.value = "";
    replaceUrl(previewUrl);
    replaceUrl(referenceUrl);
  },
);
onBeforeUnmount(() => {
  disposed = true;
  ++sequence;
  ++referenceSequence;
  replaceUrl(previewUrl);
  replaceUrl(referenceUrl);
});
</script>

<style scoped>
.voice-button {
  border-radius: 0.5rem;
  background: rgb(255 255 255 / 0.1);
  padding: 0.5rem 0.75rem;
  color: rgb(255 255 255 / 0.8);
  font-size: 0.75rem;
}
.voice-button:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.2);
}
.voice-button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.voice-control {
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 0.5rem;
  background: rgb(0 0 0 / 0.2);
  padding: 0.5rem 0.65rem;
  color: white;
  font-size: 0.8rem;
}
.voice-control option {
  background: #292929;
}
</style>
