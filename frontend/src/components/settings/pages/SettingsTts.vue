<template>
  <MenuPage>
    <MenuItem title="TTS 设置" size="large">
      <template #header>
        <AudioLines :size="20" class="text-cyan-300" />
      </template>

      <div class="flex min-h-0 flex-col gap-5">
        <div class="flex flex-wrap items-center gap-x-6 gap-y-4 border-b border-white/10 pb-5">
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.ttsOn"
            class="flex cursor-pointer items-center gap-3 border-none bg-transparent p-0 text-left"
            @click="setBooleanSetting('ttsOn', !store.settings.ttsOn)"
          >
            <span
              class="relative h-5 w-9 rounded-full transition-colors"
              :class="store.settings.ttsOn ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.ttsOn ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span>
              <span class="block text-xs text-white/45">全局本地 TTS</span>
              <span class="block text-sm font-medium text-white">
                {{ store.settings.ttsOn ? "已启用" : "已关闭，使用云端 TTS" }}
              </span>
            </span>
          </button>

          <div class="h-8 w-px bg-white/10"></div>

          <div class="flex items-center gap-2">
            <span
              class="h-2.25 w-2.25 shrink-0 rounded-full"
              :class="
                store.settings.ttsOn
                  ? 'bg-emerald-300 shadow-[0_0_8px_rgba(110,231,183,0.5)]'
                  : 'bg-red-400 shadow-[0_0_8px_rgba(248,113,113,0.45)]'
              "
            ></span>
            <div>
              <p class="text-xs text-white/45">本地引擎</p>
              <p class="text-sm font-medium text-white">
                {{ store.settings.ttsOn ? "已就绪" : "未就绪" }}
              </p>
            </div>
          </div>
        </div>

        <label class="flex flex-col gap-2">
          <span class="text-sm font-semibold text-white">语音合成服务</span>
          <select
            :value="store.settings.ttsType"
            class="h-12 w-full cursor-pointer rounded-md border border-white/15 bg-black/25 px-3 text-sm text-white transition-colors outline-none hover:border-cyan-300/35 focus:border-cyan-300/65"
            style="color-scheme: dark"
            aria-label="语音合成服务"
            data-testid="tts-engine-select"
            @change="onEngineChange"
          >
            <option
              v-for="engine in ENGINE_OPTIONS"
              :key="engine.value"
              :value="engine.value"
              class="bg-slate-800"
            >
              {{ engine.label }}
            </option>
          </select>
        </label>

        <div class="flex items-start gap-3 rounded-lg border border-white/10 bg-white/5 p-4">
          <CircleAlert :size="18" class="mt-0.5 shrink-0 text-cyan-300" />
          <div>
            <p class="text-sm font-medium text-white">{{ currentEngine.label }}</p>
            <p class="mt-1 text-xs leading-5 text-white/50">{{ currentEngine.description }}</p>
          </div>
        </div>
      </div>
    </MenuItem>

    <MenuItem title="引擎参数" size="large">
      <template #header>
        <SlidersHorizontal :size="20" class="text-cyan-300" />
      </template>

      <div :key="store.settings.ttsType" data-testid="tts-engine-fields">
        <div class="mb-4 flex items-center justify-between gap-3">
          <div>
            <h3 class="text-sm font-semibold text-white">{{ currentEngine.label }}</h3>
            <p class="mt-0.5 text-xs text-white/45">选中服务后，仅显示该引擎使用的连接与合成参数</p>
          </div>
          <Settings2 :size="18" class="text-white/40" />
        </div>

        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <label
            v-for="field in activeFields"
            :key="field.key"
            class="flex min-w-0 flex-col gap-1.5 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60"
          >
            <span class="flex items-center justify-between gap-3">
              <span class="font-medium text-white/80">{{ field.label }}</span>
              <span v-if="field.type === 'range'" class="text-cyan-200">
                {{ formatFieldValue(field) }}
              </span>
            </span>

            <select
              v-if="field.type === 'select'"
              :value="String(fieldValue(field))"
              class="h-9 w-full cursor-pointer rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              :aria-label="field.label"
              @change="onEngineFieldInput(field, $event)"
            >
              <option
                v-for="option in field.options"
                :key="option.value"
                :value="option.value"
                class="bg-slate-800"
              >
                {{ option.label }}
              </option>
            </select>

            <input
              v-else-if="field.type === 'range'"
              :value="Number(fieldValue(field))"
              type="range"
              :min="field.min"
              :max="field.max"
              :step="field.step"
              class="w-full cursor-pointer accent-cyan-300"
              :aria-label="field.label"
              @input="onEngineFieldInput(field, $event)"
            />

            <div v-else-if="field.type === 'file'" class="flex min-w-0 flex-col gap-2">
              <input
                type="file"
                accept="audio/wav,audio/mpeg,audio/flac,.wav,.mp3,.flac"
                class="block w-full cursor-pointer rounded-md border border-white/15 bg-black/25 text-[13px] text-white/70 file:mr-3 file:cursor-pointer file:border-0 file:bg-white/10 file:px-3 file:py-2 file:text-white/80 hover:file:bg-cyan-300/15"
                :aria-label="field.label"
                @change="onEngineFile(field, $event)"
              />
              <span class="truncate text-[11px] text-white/40">
                {{ fieldValue(field) || "尚未选择参考音频" }}
              </span>
            </div>

            <button
              v-else-if="field.type === 'switch'"
              type="button"
              role="switch"
              :aria-checked="Boolean(fieldValue(field))"
              class="flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white"
              @click="setEngineField(field.key, !Boolean(fieldValue(field)))"
            >
              <span
                class="relative h-5 w-9 rounded-full transition-colors"
                :class="Boolean(fieldValue(field)) ? 'bg-cyan-400/70' : 'bg-white/20'"
              >
                <span
                  class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                  :class="Boolean(fieldValue(field)) ? 'translate-x-4' : 'translate-x-0'"
                ></span>
              </span>
              {{ Boolean(fieldValue(field)) ? "已启用" : "已关闭" }}
            </button>

            <input
              v-else
              :value="fieldValue(field)"
              :type="field.type"
              :min="field.min"
              :max="field.max"
              :step="field.step"
              :placeholder="field.placeholder"
              class="h-9 w-full min-w-0 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
              :aria-label="field.label"
              @input="onEngineFieldInput(field, $event)"
            />

            <span v-if="field.hint" class="text-[11px] leading-4 text-white/40">{{ field.hint }}</span>
          </label>
        </div>
      </div>
    </MenuItem>

    <MenuItem title="模型下载" size="large">
      <template #header>
        <FileDown :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold text-white">模型下载</h3>
          <p class="mt-0.5 text-xs text-white/45">从 ModelScope 一键拉取本地 TTS 所需的全部文件</p>
        </div>
        <FileDown :size="18" class="text-white/40" />
      </div>

      <ul class="flex flex-col gap-3">
        <li
          v-for="asset in MODEL_CATALOG"
          :key="asset.id"
          class="flex flex-col gap-2 rounded-lg border border-white/10 bg-white/5 p-4"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="font-medium text-white">{{ asset.name }}</p>
              <p class="text-xs text-white/40">{{ asset.source }} · {{ asset.size }} · {{ asset.language }}</p>
            </div>
            <button
              type="button"
              class="inline-flex min-h-9 shrink-0 items-center justify-center gap-1.75 rounded-md border border-white/15 bg-white/5 px-3 py-2 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
              @click="requireBackend('下载模型')"
            >
              <FileDown :size="16" />
              <span>下载</span>
            </button>
          </div>
        </li>
      </ul>
    </MenuItem>

    <MenuItem title="本地导入" size="large">
      <template #header>
        <HardDriveDownload :size="20" class="text-cyan-300" />
      </template>

      <div class="flex flex-col gap-6">
        <section>
          <div class="mb-3 flex items-center justify-between gap-3">
            <div>
              <h3 class="text-sm font-semibold text-white">本地导入</h3>
              <p class="mt-0.5 text-xs text-white/45">支持 .sbv2 / .onnx 原始模型文件</p>
            </div>
            <HardDriveDownload :size="18" class="text-white/40" />
          </div>
          <div class="flex min-w-0 flex-wrap gap-2">
            <input
              :value="store.settings.ttsImportVoiceId"
              class="min-w-50 flex-1 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
              maxlength="64"
              placeholder="语音 ID（可选）"
              aria-label="语音 ID"
              @input="setStringFromEvent('ttsImportVoiceId', $event)"
            />
            <button
              type="button"
              class="inline-flex min-h-9 shrink-0 items-center justify-center gap-1.75 rounded-md border border-white/15 bg-white/5 px-3 py-2 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
              @click="requireBackend('导入语音模型')"
            >
              <FileArchive :size="17" />
              <span>导入语音模型</span>
            </button>
          </div>
        </section>

        <section class="border-t border-white/10 pt-6">
          <div class="mb-3 flex items-center justify-between gap-3">
            <div>
              <h3 class="text-sm font-semibold text-white">补齐 style_vectors</h3>
              <p class="mt-0.5 text-xs text-white/45">
                .onnx 语音需要同名的 style_vectors.json 才能在本地 TTS 中启用
              </p>
            </div>
            <Wand2 :size="18" class="text-white/40" />
          </div>
          <div class="flex min-w-0 flex-wrap items-center gap-2">
            <select
              :value="store.settings.ttsStyleVectorsTarget"
              class="h-9 min-w-50 flex-1 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              aria-label="选择需要补齐的语音"
              @change="setStringFromEvent('ttsStyleVectorsTarget', $event)"
            >
              <option value="local-interviewer-female" class="bg-slate-800">温和面试官（ONNX）</option>
              <option value="local-interviewer-male" class="bg-slate-800">沉稳面试官（ONNX）</option>
            </select>
            <button
              type="button"
              class="inline-flex min-h-9 shrink-0 items-center justify-center gap-1.75 rounded-md border border-white/15 bg-white/5 px-3 py-2 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
              @click="requireBackend('导入 style_vectors')"
            >
              <FileJson :size="17" />
              <span>导入 style_vectors</span>
            </button>
          </div>
        </section>
      </div>
    </MenuItem>

    <MenuItem title="已安装语音" size="large">
      <template #header>
        <ListMusic :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold text-white">已安装语音</h3>
          <p class="mt-0.5 text-xs text-white/45">2 个可用面试官模型</p>
        </div>
        <ListMusic :size="18" class="text-white/40" />
      </div>

      <div class="divide-y divide-white/8 border-y border-white/10">
        <div
          v-for="voice in INSTALLED_VOICES"
          :key="voice.id"
          class="grid min-h-16 grid-cols-[minmax(0,1fr)_auto] items-center gap-4 py-3"
        >
          <div class="min-w-0">
            <p class="truncate text-sm font-medium text-white">{{ voice.name }}</p>
            <p class="mt-1 truncate text-xs text-white/45">
              {{ voice.id }} · {{ voice.kind }} · {{ voice.size }}
            </p>
            <span
              class="mt-1 inline-block rounded border border-cyan-300/25 bg-cyan-600/10 px-1 py-px text-[10px] text-cyan-50/75"
            >
              {{ voice.style }}
            </span>
          </div>
          <button
            type="button"
            class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-red-300/20 bg-red-400/10 text-red-200 transition-colors hover:bg-red-400/20"
            title="删除语音"
            @click="requireBackend('删除语音')"
          >
            <Trash2 :size="16" />
          </button>
        </div>
      </div>
    </MenuItem>

    <MenuItem title="试听" size="large">
      <template #header>
        <Volume2 :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold text-white">试听</h3>
          <p class="mt-0.5 text-xs text-white/45">输入内容并选择已安装的面试官语音</p>
        </div>
        <Volume2 :size="18" class="text-white/40" />
      </div>

      <div class="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_17rem]">
        <textarea
          :value="store.settings.ttsPreviewText"
          class="min-h-28 w-full resize-y rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
          maxlength="500"
          placeholder="输入试听文本"
          aria-label="试听文本"
          @input="setStringFromEvent('ttsPreviewText', $event)"
        ></textarea>

        <div class="flex flex-col gap-3">
          <label class="flex flex-col gap-1.5 text-xs text-white/60">
            <span>语音模型</span>
            <select
              :value="store.settings.ttsVoice"
              class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              @change="setStringFromEvent('ttsVoice', $event)"
            >
              <option value="zh-CN-YunxiNeural" class="bg-slate-800">云希（沉稳男声）</option>
              <option value="zh-CN-XiaoxiaoNeural" class="bg-slate-800">晓晓（自然女声）</option>
              <option value="local-interviewer-female" class="bg-slate-800">温和面试官</option>
              <option value="local-interviewer-male" class="bg-slate-800">沉稳面试官</option>
            </select>
          </label>
          <label class="flex flex-col gap-1.5 text-xs text-white/60">
            <span>长度倍率 {{ store.settings.ttsSpeed.toFixed(2) }}（1.0 = 正常）</span>
            <input
              :value="store.settings.ttsSpeed"
              type="range"
              min="0.5"
              max="2"
              step="0.05"
              class="cursor-pointer accent-cyan-300"
              @input="setNumberFromEvent('ttsSpeed', $event)"
            />
          </label>
          <label class="flex flex-col gap-1.5 text-xs text-white/60">
            <span>随机度 {{ store.settings.ttsPreviewSdp.toFixed(2) }}</span>
            <input
              :value="store.settings.ttsPreviewSdp"
              type="range"
              min="0"
              max="1"
              step="0.05"
              class="cursor-pointer accent-cyan-300"
              @input="setNumberFromEvent('ttsPreviewSdp', $event)"
            />
          </label>
        </div>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="inline-flex min-h-9 items-center justify-center gap-1.75 rounded-md border border-cyan-300/40 bg-cyan-600/35 px-3.5 py-2 text-[13px] font-semibold text-cyan-50 transition-colors duration-200 hover:bg-cyan-600/50"
          data-testid="tts-preview-button"
          @click="requireBackend('生成试听')"
        >
          <Play :size="16" />
          生成试听
        </button>
        <audio controls class="h-9 min-w-0 flex-1" />
      </div>
    </MenuItem>

    <MenuItem title="语音克隆 TTS" size="large">
      <template #header>
        <Mic2 :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold text-white">语音克隆 TTS</h3>
          <p class="mt-0.5 text-xs text-white/45">
            基于阿里云百炼 CosyVoice，导入语音样本克隆专属音色
          </p>
        </div>
        <Mic2 :size="18" class="text-white/40" />
      </div>

      <div class="mb-5 flex min-w-0 flex-wrap items-center gap-2">
        <KeyRound :size="16" class="shrink-0 text-white/40" />
        <input
          :value="store.settings.ttsApiKey"
          type="password"
          class="min-w-55 flex-1 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
          placeholder="输入 DASHSCOPE API Key"
          aria-label="DASHSCOPE API Key"
          @input="setStringFromEvent('ttsApiKey', $event)"
        />
        <button
          type="button"
          class="inline-flex min-h-9 shrink-0 items-center justify-center rounded-md border border-cyan-300/40 bg-cyan-600/35 px-3.5 py-2 text-[13px] font-semibold text-cyan-50 transition-colors hover:bg-cyan-600/50"
          @click="requireBackend('保存 API Key')"
        >
          保存
        </button>
      </div>

      <section class="mb-5">
        <p class="mb-2 text-xs text-white/60">我的音色（1）</p>
        <div class="grid min-h-16 grid-cols-[minmax(0,1fr)_auto] items-center gap-4 border-y border-white/10 py-3">
          <div class="min-w-0">
            <p class="truncate text-sm font-medium text-white">面试官示例音色</p>
            <p class="mt-1 truncate text-xs text-white/45">cosyvoice-demo-interviewer · cosyvoice-v3.5-flash</p>
            <span class="mt-1 inline-block text-[11px] text-emerald-300">可用</span>
          </div>
          <div class="flex gap-2">
            <button
              type="button"
              class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-cyan-300/25 bg-cyan-300/10 text-cyan-100 transition-colors hover:bg-cyan-300/20"
              title="试听"
              @click="requireBackend('试听克隆音色')"
            >
              <Play :size="16" />
            </button>
            <button
              type="button"
              class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-red-300/20 bg-red-400/10 text-red-200 transition-colors hover:bg-red-400/20"
              title="删除音色"
              @click="requireBackend('删除克隆音色')"
            >
              <Trash2 :size="16" />
            </button>
          </div>
        </div>
      </section>

      <section class="border-t border-white/10 pt-6">
        <div class="mb-3 flex items-center justify-between gap-3">
          <div>
            <h3 class="text-sm font-semibold text-white">导入音色</h3>
            <p class="mt-0.5 text-xs text-white/45">上传清晰人声样本，创建专属克隆音色</p>
          </div>
          <FileAudio :size="18" class="text-white/40" />
        </div>

        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <label class="flex flex-col gap-1.5 text-xs text-white/60">
            <span>音色名称（必填）</span>
            <input
              :value="store.settings.ttsCloneName"
              class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
              maxlength="32"
              placeholder="音色名称"
              @input="setStringFromEvent('ttsCloneName', $event)"
            />
          </label>
          <label class="flex flex-col gap-1.5 text-xs text-white/60">
            <span>参考音频语言（必选）</span>
            <select
              :value="store.settings.ttsCloneLanguage"
              class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              @change="setStringFromEvent('ttsCloneLanguage', $event)"
            >
              <option v-for="language in CLONE_LANGUAGES" :key="language.value" :value="language.value" class="bg-slate-800">
                {{ language.label }}
              </option>
            </select>
          </label>
          <label class="flex flex-col gap-1.5 text-xs text-white/60 md:col-span-2">
            <span>选择语音样本</span>
            <input
              type="file"
              accept="audio/wav,audio/mpeg,audio/flac,.wav,.mp3,.flac"
              class="block w-full cursor-pointer rounded-md border border-white/15 bg-black/25 text-[13px] text-white/70 file:mr-3 file:cursor-pointer file:border-0 file:bg-white/10 file:px-3 file:py-2 file:text-white/80 hover:file:bg-cyan-300/15"
              @change="onCloneSampleChange"
            />
            <span class="text-[11px] text-white/40">
              {{ store.settings.ttsCloneSample || "尚未选择语音样本" }}
            </span>
          </label>
          <label class="flex flex-col gap-1.5 text-xs text-white/60 md:col-span-2">
            <span>参考文本</span>
            <textarea
              :value="store.settings.ttsCloneText"
              class="min-h-24 resize-y rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
              placeholder="输入参考音频中对应的文本内容"
              @input="setStringFromEvent('ttsCloneText', $event)"
            ></textarea>
          </label>
        </div>

        <div class="mt-4 flex flex-wrap items-center gap-3">
          <button
            type="button"
            class="inline-flex min-h-9 items-center justify-center gap-1.75 rounded-md border border-cyan-300/40 bg-cyan-600/35 px-3.5 py-2 text-[13px] font-semibold text-cyan-50 transition-colors hover:bg-cyan-600/50"
            data-testid="tts-clone-button"
            @click="requireBackend('导入并注册音色')"
          >
            <Mic2 :size="16" />
            导入并注册音色
          </button>
        </div>

        <p class="mt-3 border-l border-white/10 pl-3 text-xs leading-5 text-white/45">
          时长建议 10–20 秒，支持 wav / mp3，采样率不低于 16kHz，所选参考音频语言必须与实际音频语言一致，单人清晰说话，无背景音乐或噪音
        </p>
      </section>
    </MenuItem>

    <MenuItem title="高级参数" size="large">
      <template #header>
        <SlidersHorizontal :size="20" class="text-cyan-300" />
      </template>

      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <label class="flex flex-col gap-1.5 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60">
          <span class="font-medium text-white/80">请求超时（秒）</span>
          <input
            :value="store.settings.ttsTimeout"
            type="number"
            min="1"
            max="300"
            step="1"
            class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white outline-none focus:border-cyan-300/65"
            @input="setNumberFromEvent('ttsTimeout', $event)"
          />
        </label>
        <label class="flex flex-col gap-1.5 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60">
          <span class="font-medium text-white/80">并发数量</span>
          <input
            :value="store.settings.ttsConcurrency"
            type="number"
            min="1"
            max="16"
            step="1"
            class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white outline-none focus:border-cyan-300/65"
            @input="setNumberFromEvent('ttsConcurrency', $event)"
          />
        </label>
        <div class="flex flex-col gap-3 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60">
          <span class="font-medium text-white/80">语音缓存</span>
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.ttsCacheEnabled"
            class="flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white"
            @click="setBooleanSetting('ttsCacheEnabled', !store.settings.ttsCacheEnabled)"
          >
            <span
              class="relative h-5 w-9 rounded-full transition-colors"
              :class="store.settings.ttsCacheEnabled ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.ttsCacheEnabled ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            {{ store.settings.ttsCacheEnabled ? "已启用" : "已关闭" }}
          </button>
        </div>
        <label class="flex flex-col gap-1.5 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60">
          <span class="font-medium text-white/80">缓存上限（MB）</span>
          <input
            :value="store.settings.ttsCacheLimit"
            type="number"
            min="64"
            max="8192"
            step="64"
            class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white outline-none focus:border-cyan-300/65"
            @input="setNumberFromEvent('ttsCacheLimit', $event)"
          />
        </label>
        <label class="flex flex-col gap-1.5 rounded-lg border border-white/10 bg-white/5 p-4 text-xs text-white/60 md:col-span-2">
          <span class="font-medium text-white/80">失败重试次数</span>
          <input
            :value="store.settings.ttsRetryCount"
            type="number"
            min="0"
            max="10"
            step="1"
            class="h-9 rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white outline-none focus:border-cyan-300/65"
            @input="setNumberFromEvent('ttsRetryCount', $event)"
          />
        </label>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  AudioLines,
  CircleAlert,
  FileArchive,
  FileAudio,
  FileDown,
  FileJson,
  HardDriveDownload,
  KeyRound,
  ListMusic,
  Mic2,
  Play,
  Settings2,
  SlidersHorizontal,
  Trash2,
  Volume2,
  Wand2,
} from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { store, updateSettings } from "../../../store/appStore";
import { alertDialog } from "../../../store/dialogStore";
import type { SiteSettings } from "../../../data/mock";

type SettingValue = string | number | boolean;
type EngineFieldType = "text" | "password" | "number" | "range" | "select" | "file" | "switch";

interface EngineOption {
  value: string;
  label: string;
  description: string;
}

interface SelectOption {
  value: string;
  label: string;
}

interface EngineField {
  key: string;
  label: string;
  type: EngineFieldType;
  placeholder?: string;
  min?: number;
  max?: number;
  step?: number;
  suffix?: string;
  hint?: string;
  options?: SelectOption[];
}

const LANGUAGE_OPTIONS: SelectOption[] = [
  { value: "zh", label: "中文" },
  { value: "zh-CN", label: "中文（中国大陆）" },
  { value: "en", label: "英语" },
  { value: "ja", label: "日语" },
  { value: "ko", label: "韩语" },
  { value: "es", label: "西班牙语" },
  { value: "ar", label: "阿拉伯语" },
];

const EMOTION_OPTIONS: SelectOption[] = [
  { value: "中立", label: "中立" },
  { value: "开心", label: "开心" },
  { value: "难过", label: "难过" },
  { value: "生气", label: "生气" },
  { value: "惊讶", label: "惊讶" },
  { value: "严肃", label: "严肃" },
];

const ENGINE_OPTIONS: EngineOption[] = [
  {
    value: "edge-tts",
    label: "Edge TTS",
    description: "微软在线神经语音服务，适合直接选择语言与系统音色。",
  },
  {
    value: "gpt-sovits",
    label: "GPT-SoVITS",
    description: "使用参考音频与参考文本进行少样本语音合成。",
  },
  {
    value: "sbv2",
    label: "Style-Bert-VITS2",
    description: "支持说话人、风格与语言参数的 Style-Bert-VITS2 服务。",
  },
  {
    value: "sbv2api",
    label: "SBV2 API",
    description: "通过独立 SBV2 API 服务调用模型和说话人。",
  },
  {
    value: "cosyvoice",
    label: "CosyVoice",
    description: "阿里云百炼 CosyVoice 云端语音与音色复刻服务。",
  },
  {
    value: "indextts2",
    label: "IndexTTS2",
    description: "支持多语言、情感控制与参考音频的 IndexTTS 服务。",
  },
  {
    value: "fish-speech",
    label: "Fish-Speech / Fish S2",
    description: "Fish Speech 本地或远程接口，支持音色标识和情感参数。",
  },
  {
    value: "opentts",
    label: "OpenTTS",
    description: "OpenAI 兼容语音接口，可配置 API 地址、模型与音色。",
  },
  {
    value: "vits",
    label: "VITS / Simple-VITS",
    description: "Simple-VITS API 服务，支持说话人和推理参数调节。",
  },
  {
    value: "aivis",
    label: "Aivis",
    description: "Aivis 云 API，使用模型 UUID 和说话人参数合成日语语音。",
  },
  {
    value: "bv2",
    label: "Bert-VITS2",
    description: "Bert-VITS2 适配器，支持说话人、风格和 SDP 噪声比。",
  },
  {
    value: "gsv",
    label: "GSV",
    description: "LingChat GSV 适配器，配置参考音频、文本与两组模型权重。",
  },
  {
    value: "local-sbv2",
    label: "本地 SBV2",
    description: "使用已安装的本地 SBV2 / ONNX 音色，并可设置云端备用模型。",
  },
];

const ENGINE_FIELDS: Record<string, EngineField[]> = {
  "edge-tts": [
    { key: "language", label: "语音语言", type: "select", options: LANGUAGE_OPTIONS },
    {
      key: "voice",
      label: "音色",
      type: "select",
      options: [
        { value: "zh-CN-YunxiNeural", label: "云希（沉稳男声）" },
        { value: "zh-CN-XiaoxiaoNeural", label: "晓晓（自然女声）" },
        { value: "zh-CN-YunjianNeural", label: "云健（专业男声）" },
        { value: "zh-CN-XiaoyiNeural", label: "晓伊（活力女声）" },
      ],
    },
    { key: "speed", label: "语速", type: "range", min: 0.5, max: 2, step: 0.05, suffix: "×" },
    { key: "pitch", label: "语调", type: "range", min: -50, max: 50, step: 1, suffix: "%" },
    { key: "volume", label: "音量", type: "range", min: 0, max: 100, step: 1, suffix: "%" },
  ],
  "gpt-sovits": [
    { key: "apiUrl", label: "API 地址", type: "text", placeholder: "http://127.0.0.1" },
    { key: "port", label: "端口", type: "number", min: 1, max: 65535, step: 1 },
    { key: "gptModel", label: "GPT 模型", type: "text" },
    { key: "sovitsModel", label: "SoVITS 模型", type: "text" },
    { key: "referenceAudio", label: "参考音频", type: "file" },
    { key: "referenceText", label: "参考文本", type: "text", placeholder: "参考音频对应的文本内容" },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "emotion", label: "情感", type: "select", options: EMOTION_OPTIONS },
  ],
  sbv2: [
    { key: "apiUrl", label: "Style-Bert-VITS2 服务地址", type: "text" },
    { key: "modelName", label: "模型名称", type: "text" },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "styleId", label: "风格 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "speed", label: "语速", type: "range", min: 0.5, max: 2, step: 0.05, suffix: "×" },
  ],
  sbv2api: [
    { key: "apiUrl", label: "SBV2 API 服务地址", type: "text" },
    { key: "modelName", label: "模型名称", type: "text" },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "styleId", label: "风格 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "speed", label: "语速", type: "range", min: 0.5, max: 2, step: 0.05, suffix: "×" },
  ],
  cosyvoice: [
    { key: "apiUrl", label: "API 地址", type: "text" },
    { key: "apiKey", label: "API Key", type: "password", placeholder: "输入 DASHSCOPE API Key" },
    { key: "model", label: "模型", type: "text" },
    { key: "voiceId", label: "云端音色", type: "text", placeholder: "音色 ID" },
    { key: "language", label: "参考音频语言", type: "select", options: LANGUAGE_OPTIONS },
    {
      key: "dialect",
      label: "中文方言",
      type: "select",
      options: [
        { value: "", label: "普通话" },
        { value: "广东话", label: "广东话" },
        { value: "东北话", label: "东北话" },
        { value: "河南话", label: "河南话" },
        { value: "四川话", label: "四川话" },
        { value: "上海话", label: "上海话" },
      ],
    },
  ],
  indextts2: [
    { key: "apiUrl", label: "IndexTTS2 API 地址", type: "text" },
    { key: "port", label: "端口", type: "number", min: 1, max: 65535, step: 1 },
    { key: "model", label: "模型", type: "text" },
    { key: "voice", label: "音色", type: "text" },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "emotion", label: "情感", type: "select", options: EMOTION_OPTIONS },
    { key: "referenceAudio", label: "参考音频", type: "file" },
  ],
  "fish-speech": [
    { key: "apiUrl", label: "Fish S2 API 地址", type: "text" },
    { key: "apiKey", label: "API Key", type: "password" },
    { key: "model", label: "模型", type: "text" },
    { key: "voice", label: "Fish S2 默认音色标识", type: "text" },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "emotion", label: "情感", type: "select", options: EMOTION_OPTIONS },
    { key: "referenceAudio", label: "参考音频", type: "file" },
  ],
  opentts: [
    { key: "apiUrl", label: "OpenTTS API 地址", type: "text" },
    { key: "apiKey", label: "OpenTTS API 密钥", type: "password" },
    { key: "model", label: "OpenTTS 模型名称", type: "text" },
    { key: "voice", label: "OpenTTS voice / 音色标识", type: "text" },
    { key: "language", label: "语音语言", type: "select", options: LANGUAGE_OPTIONS },
    {
      key: "audioFormat",
      label: "音频文件格式",
      type: "select",
      options: [
        { value: "wav", label: "wav" },
        { value: "mp3", label: "mp3" },
        { value: "flac", label: "flac" },
        { value: "ogg", label: "ogg" },
      ],
    },
  ],
  vits: [
    { key: "apiUrl", label: "Simple-Vits-API 地址", type: "text" },
    { key: "port", label: "端口", type: "number", min: 1, max: 65535, step: 1 },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "noiseScale", label: "noise scale", type: "range", min: 0, max: 1, step: 0.01 },
    { key: "lengthScale", label: "length scale", type: "range", min: 0.5, max: 2, step: 0.05 },
  ],
  aivis: [
    { key: "apiUrl", label: "AIVIS 云 API 地址", type: "text" },
    { key: "apiKey", label: "AIVIS API 密钥", type: "password" },
    { key: "modelUuid", label: "模型 UUID", type: "text" },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "speed", label: "语速", type: "range", min: 0.5, max: 2, step: 0.05, suffix: "×" },
    { key: "intonation", label: "语调", type: "range", min: 0, max: 2, step: 0.05 },
  ],
  bv2: [
    { key: "apiUrl", label: "Bert-VITS2 服务地址", type: "text" },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "styleId", label: "风格 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "sdpRatio", label: "SDP 噪声比", type: "range", min: 0, max: 1, step: 0.05 },
  ],
  gsv: [
    { key: "apiUrl", label: "GPT-SoVITS API 地址", type: "text" },
    { key: "referenceText", label: "参考文本", type: "text" },
    { key: "referenceAudio", label: "参考语音文件", type: "file" },
    { key: "gptModel", label: "GPT 模型名称", type: "text" },
    { key: "sovitsModel", label: "SoVITS 模型名称", type: "text" },
    { key: "language", label: "语种", type: "select", options: LANGUAGE_OPTIONS },
    { key: "emotion", label: "情感", type: "select", options: EMOTION_OPTIONS },
  ],
  "local-sbv2": [
    {
      key: "voiceId",
      label: "本地语音 ID",
      type: "select",
      options: [
        { value: "local-interviewer-female", label: "温和面试官" },
        { value: "local-interviewer-male", label: "沉稳面试官" },
      ],
    },
    { key: "speakerId", label: "说话人 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "styleId", label: "风格 ID", type: "number", min: 0, max: 999, step: 1 },
    { key: "lengthScale", label: "长度缩放 (length_scale)", type: "range", min: 0.5, max: 2, step: 0.05 },
    { key: "sdpRatio", label: "SDP 噪声比", type: "range", min: 0, max: 1, step: 0.05 },
    { key: "fallbackModel", label: "本地 TTS 云端备用模型", type: "text", placeholder: "本地 TTS 关闭时使用，可留空" },
    { key: "fallbackSpeakerId", label: "本地 TTS 云端备用说话人 ID", type: "text", placeholder: "本地 TTS 关闭时使用，可留空" },
  ],
};

const MODEL_CATALOG = [
  { id: "deberta", name: "DeBERTa 情感模型与分词器", source: "ModelScope", size: "394 MB", language: "多语言" },
  { id: "sbv2-demo", name: "SBV2 面试官示例音色", source: "ModelScope", size: "168 MB", language: "中文" },
  { id: "onnx-demo", name: "ONNX 面试官轻量音色", source: "ModelScope", size: "92 MB", language: "中文 / 日语" },
];

const INSTALLED_VOICES = [
  { id: "local-interviewer-female", name: "温和面试官", kind: "ONNX", size: "92 MB", style: "style_vectors 已配" },
  { id: "local-interviewer-male", name: "沉稳面试官", kind: "SBV2", size: "168 MB", style: "style_vectors 已内置" },
];

const CLONE_LANGUAGES: SelectOption[] = [
  { value: "zh", label: "中文" },
  { value: "en", label: "英语" },
  { value: "ja", label: "日语" },
  { value: "ko", label: "韩语" },
  { value: "fr", label: "法语" },
  { value: "de", label: "德语" },
  { value: "ru", label: "俄语" },
  { value: "pt", label: "葡萄牙语" },
  { value: "th", label: "泰语" },
  { value: "id", label: "印尼语" },
  { value: "vi", label: "越南语" },
];

const currentEngine = computed<EngineOption>(
  () => ENGINE_OPTIONS.find((engine) => engine.value === store.settings.ttsType) ?? ENGINE_OPTIONS[0]!,
);

const activeFields = computed<EngineField[]>(() => ENGINE_FIELDS[store.settings.ttsType] ?? []);

const commonSettingByField: Record<string, keyof SiteSettings> = {
  apiUrl: "ttsApiUrl",
  apiKey: "ttsApiKey",
  port: "ttsPort",
  model: "ttsModel",
  modelName: "ttsModel",
  voice: "ttsVoice",
  voiceId: "ttsVoice",
  speakerId: "ttsSpeakerId",
  styleId: "ttsStyleId",
  language: "ttsLanguage",
  emotion: "ttsEmotion",
  speed: "ttsSpeed",
  pitch: "ttsPitch",
  volume: "ttsVolume",
  referenceAudio: "ttsReferenceAudio",
  referenceText: "ttsReferenceText",
  audioFormat: "ttsAudioFormat",
  dialect: "ttsDialect",
  lengthScale: "ttsLengthScale",
  sdpRatio: "ttsSdpRatio",
  noiseScale: "ttsNoiseScale",
};

function fieldValue(field: EngineField): SettingValue {
  return store.settings.ttsEngineConfigs[store.settings.ttsType]?.[field.key] ?? "";
}

function setEngineField(key: string, value: SettingValue): void {
  const engine = store.settings.ttsType;
  const nextConfigs = {
    ...store.settings.ttsEngineConfigs,
    [engine]: {
      ...(store.settings.ttsEngineConfigs[engine] ?? {}),
      [key]: value,
    },
  };
  const patch: Partial<SiteSettings> = { ttsEngineConfigs: nextConfigs };
  const commonKey = commonSettingByField[key];
  if (commonKey) {
    (patch as unknown as Record<string, unknown>)[commonKey] = value;
  }
  updateSettings(patch);
}

function onEngineChange(event: Event): void {
  updateSettings({ ttsType: (event.target as HTMLSelectElement).value });
}

function onEngineFieldInput(field: EngineField, event: Event): void {
  const raw = (event.target as HTMLInputElement | HTMLSelectElement).value;
  const value = field.type === "number" || field.type === "range" ? Number(raw) : raw;
  setEngineField(field.key, value);
}

function onEngineFile(field: EngineField, event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) setEngineField(field.key, file.name);
}

function formatFieldValue(field: EngineField): string {
  const value = fieldValue(field);
  return `${typeof value === "number" ? Number(value).toFixed(field.step && field.step < 1 ? 2 : 0) : value}${field.suffix ?? ""}`;
}

function setStringFromEvent(key: keyof SiteSettings, event: Event): void {
  updateSettings({ [key]: (event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement).value } as unknown as Partial<SiteSettings>);
}

function setNumberFromEvent(key: keyof SiteSettings, event: Event): void {
  updateSettings({ [key]: Number((event.target as HTMLInputElement).value) } as unknown as Partial<SiteSettings>);
}

function setBooleanSetting(key: keyof SiteSettings, value: boolean): void {
  updateSettings({ [key]: value } as unknown as Partial<SiteSettings>);
}

function onCloneSampleChange(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (file) updateSettings({ ttsCloneSample: file.name });
}

function requireBackend(action: string): void {
  void alertDialog(action, "需要后端服务，当前未接入");
}
</script>
