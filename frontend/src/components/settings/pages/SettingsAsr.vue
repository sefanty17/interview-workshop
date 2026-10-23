<template>
  <MenuPage>
    <!-- ===== 语音识别：总开关（控制所有输入来源） ===== -->
    <MenuItem title="语音识别" size="large">
      <template #header>
        <Mic :size="20" class="text-cyan-300" />
      </template>

      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-white/10 pb-4">
        <p class="min-w-0 text-xs text-white/45">识别引擎、麦克风监听与发送方式都在这里配置</p>
        <span class="text-sm" :class="statusClass">{{ statusText }}</span>
      </div>

      <div class="mt-4">
        <button
          type="button"
          role="switch"
          :aria-checked="store.settings.voice_input_enabled"
          aria-label="语音输入"
          class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
          @click="setBooleanSetting('voice_input_enabled', !store.settings.voice_input_enabled)"
        >
          <span
            class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
            :class="store.settings.voice_input_enabled ? 'bg-cyan-400/70' : 'bg-white/20'"
          >
            <span
              class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
              :class="store.settings.voice_input_enabled ? 'translate-x-4' : 'translate-x-0'"
            ></span>
          </span>
          <span class="min-w-0">
            <span class="block text-sm font-medium text-white">语音输入</span>
            <span class="mt-0.5 block text-sm text-gray-300">
              总开关：关闭后语音输入整体不可用（自动与手动麦克风一并关闭）
            </span>
          </span>
        </button>
      </div>
    </MenuItem>

    <!-- ===== 语音快捷键 ===== -->
    <MenuItem title="语音快捷键" size="large">
      <template #header>
        <Keyboard :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-1.5 flex items-center justify-between">
        <label class="text-sm font-medium">语音快捷键</label>
        <button
          type="button"
          class="text-xs text-gray-300 transition-colors hover:text-cyan-300"
          @click="resetPttKey"
        >
          恢复默认
        </button>
      </div>
      <button
        type="button"
        :class="[
          'w-full rounded-lg border px-3 py-2.5 text-left text-sm transition-all duration-200',
          capturingPtt
            ? 'border-brand bg-cyan-300/15 text-cyan-200'
            : 'border-white/10 bg-white/10 text-white hover:border-cyan-300/40',
        ]"
        @click="startPttCapture"
      >
        {{ capturingPtt ? "按下新快捷键…（Esc 取消）" : formatBinding(pttBinding) }}
      </button>
      <p class="mt-1.5 block text-sm text-gray-300">
        按住说话，单击切换录音；开启自动语音识别时切换自动监听。可绑定单键（聊天输入时会同时触发，建议使用功能键或组合键）
      </p>
      <p v-if="pttCaptureInvalid" class="mt-1 block text-sm text-red-400">
        该键位不可绑定：Enter 用于发送消息
      </p>

      <div class="mt-4 border-t border-white/10 pt-4">
        <button
          type="button"
          role="switch"
          :aria-checked="store.settings.ptt_global"
          aria-label="失去焦点快捷键可用"
          class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
          @click="setBooleanSetting('ptt_global', !store.settings.ptt_global)"
        >
          <span
            class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
            :class="store.settings.ptt_global ? 'bg-cyan-400/70' : 'bg-white/20'"
          >
            <span
              class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
              :class="store.settings.ptt_global ? 'translate-x-4' : 'translate-x-0'"
            ></span>
          </span>
          <span class="min-w-0">
            <span class="block text-sm font-medium text-white">失去焦点快捷键可用</span>
            <span class="mt-0.5 block text-sm text-gray-300">
              窗口不在前台时快捷键仍可用（全局快捷键）。绑定字母/数字等单键会拦截其它应用的该键输入，请自行评估
            </span>
          </span>
        </button>
      </div>
    </MenuItem>

    <!-- ===== 自动语音识别 ===== -->
    <MenuItem title="自动语音识别" size="large">
      <template #header>
        <Ear :size="20" class="text-cyan-300" />
      </template>

      <div class="flex flex-col gap-5">
        <button
          type="button"
          role="switch"
          :aria-checked="store.settings.auto_listen"
          aria-label="启用自动语音识别"
          class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
          @click="setBooleanSetting('auto_listen', !store.settings.auto_listen)"
        >
          <span
            class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
            :class="store.settings.auto_listen ? 'bg-cyan-400/70' : 'bg-white/20'"
          >
            <span
              class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
              :class="store.settings.auto_listen ? 'translate-x-4' : 'translate-x-0'"
            ></span>
          </span>
          <span class="min-w-0">
            <span class="block text-sm font-medium text-white">启用自动语音识别</span>
            <span class="mt-0.5 block text-sm text-gray-300">
              自动监听麦克风，识别用户说话后自动转为文本
            </span>
          </span>
        </button>

        <!-- 连续对话 -->
        <div class="border-t border-white/10 pt-5">
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.asr_continuous"
            aria-label="连续对话"
            class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
            @click="setBooleanSetting('asr_continuous', !store.settings.asr_continuous)"
          >
            <span
              class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="store.settings.asr_continuous ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.asr_continuous ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span class="min-w-0">
              <span class="block text-sm font-medium text-white">连续对话</span>
              <span class="mt-0.5 block text-sm text-gray-300">
                识别完成后继续监听下一轮，直到手动停止自动监听
              </span>
            </span>
          </button>
        </div>

        <!-- 唤醒词 -->
        <div class="border-t border-white/10 pt-5">
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.wake_word_enabled"
            aria-label="唤醒词"
            class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
            @click="setBooleanSetting('wake_word_enabled', !store.settings.wake_word_enabled)"
          >
            <span
              class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="store.settings.wake_word_enabled ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.wake_word_enabled ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span class="min-w-0">
              <span class="block text-sm font-medium text-white">唤醒词</span>
              <span class="mt-0.5 block text-sm text-gray-300">
                自动监听时先听到唤醒词才开始识别，避免环境声误触发
              </span>
            </span>
          </button>
          <input
            :value="store.settings.wake_word"
            type="text"
            maxlength="32"
            placeholder="输入唤醒词"
            aria-label="唤醒词"
            class="mt-3 h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65 disabled:opacity-40"
            :disabled="!store.settings.wake_word_enabled"
            @input="setStringFromEvent('wake_word', $event)"
          />
        </div>

        <!-- VAD 静音计时（停止说话后等多久结束录音） -->
        <div class="grid grid-cols-1 gap-4 border-t border-white/10 pt-5 md:grid-cols-2">
          <label class="flex min-w-0 flex-col gap-1.5">
            <span class="text-sm font-medium text-white">静音计时（毫秒）</span>
            <input
              :value="store.settings.vad_silence_ms"
              type="number"
              min="100"
              max="3000"
              step="100"
              class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              @input="setNumberFromEvent('vad_silence_ms', $event)"
            />
            <span class="text-[11px] leading-4 text-gray-300">
              停止说话后等待该时长才结束录音（默认 800ms，数字越大越不容易被停顿切断）
            </span>
          </label>
          <label class="flex min-w-0 flex-col gap-1.5">
            <span class="text-sm font-medium text-white">能量监测缓冲期（毫秒）</span>
            <input
              :value="store.settings.energy_warmup_ms"
              type="number"
              min="0"
              max="2000"
              step="100"
              class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              @input="setNumberFromEvent('energy_warmup_ms', $event)"
            />
            <span class="text-[11px] leading-4 text-gray-300">
              AI 说完话后等待该时长才恢复语音触发（默认 100ms，设 0 立即触发；若 AI 话音刚落环境声会误触发可调大）
            </span>
          </label>
        </div>

        <!-- 详细 VAD 能量检测日志 -->
        <div class="border-t border-white/10 pt-5">
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.vad_debug_log"
            aria-label="详细VAD能量检测日志开关"
            class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
            @click="setBooleanSetting('vad_debug_log', !store.settings.vad_debug_log)"
          >
            <span
              class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="store.settings.vad_debug_log ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.vad_debug_log ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span class="min-w-0">
              <span class="block text-sm font-medium text-white">详细VAD能量检测日志开关</span>
              <span class="mt-0.5 block text-sm text-gray-300">
                输出逐帧语音概率（frame/prob/len）到日志页，排查语音识别不触发时用；默认关闭，录音期间每秒约 1 条
              </span>
            </span>
          </button>
        </div>
      </div>
    </MenuItem>

    <!-- ===== 识别完成后处理方式 ===== -->
    <MenuItem title="识别完成后" size="large">
      <template #header>
        <Send :size="20" class="text-cyan-300" />
      </template>

      <div class="space-y-2">
        <label
          v-for="opt in SEND_MODE_OPTIONS"
          :key="opt.value"
          class="flex cursor-pointer items-center gap-2 text-sm text-white"
        >
          <input
            type="radio"
            :value="opt.value"
            :checked="store.settings.send_mode === opt.value"
            class="h-4 w-4 cursor-pointer accent-cyan-300"
            @change="updateSettings({ send_mode: opt.value })"
          />
          <span>{{ opt.label }}</span>
        </label>
      </div>
    </MenuItem>

    <!-- ===== 麦克风设置 ===== -->
    <MenuItem title="麦克风设置" size="large">
      <template #header>
        <MicVocal :size="20" class="text-cyan-300" />
      </template>

      <div class="flex flex-col gap-5">
        <div class="flex min-w-0 flex-wrap items-end gap-2">
          <label class="flex min-w-55 flex-1 flex-col gap-1.5">
            <span class="text-sm font-medium text-white">麦克风设备</span>
            <select
              :value="store.settings.asr_mic_device"
              class="h-9 w-full cursor-pointer rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              aria-label="麦克风设备"
              @change="setStringFromEvent('asr_mic_device', $event)"
            >
              <option v-for="device in MIC_DEVICES" :key="device.value" :value="device.value" class="bg-slate-800">
                {{ device.label }}
              </option>
            </select>
          </label>
          <button
            type="button"
            title="刷新设备列表"
            class="inline-flex h-9 items-center justify-center gap-1.75 rounded-md border border-white/15 bg-white/5 px-3 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
            @click="requireBackend('刷新麦克风设备列表')"
          >
            <RefreshCw :size="16" />
            <span>刷新设备列表</span>
          </button>
        </div>

        <div class="border-t border-white/10 pt-5">
          <div class="mb-1.5 flex items-center justify-between">
            <span class="text-sm font-medium text-white">音量阈值</span>
            <span class="text-xs text-cyan-200">{{ store.settings.asr_mic_threshold.toFixed(2) }}</span>
          </div>
          <div class="b2-slider">
            <span>弱</span>
            <input
              :value="store.settings.asr_mic_threshold"
              class="b2-range"
              type="range"
              min="0"
              max="1"
              step="0.05"
              aria-label="音量阈值"
              @input="setNumberFromEvent('asr_mic_threshold', $event)"
            />
            <span>强</span>
          </div>
          <p class="mt-1 text-[11px] leading-4 text-gray-300">
            低于该能量的声音视为环境噪声，不送去做识别
          </p>
        </div>

        <div class="border-t border-white/10 pt-5">
          <button
            type="button"
            role="switch"
            :aria-checked="store.settings.asr_mic_gate"
            aria-label="静音检测"
            class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
            @click="setBooleanSetting('asr_mic_gate', !store.settings.asr_mic_gate)"
          >
            <span
              class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="store.settings.asr_mic_gate ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="store.settings.asr_mic_gate ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span class="min-w-0">
              <span class="block text-sm font-medium text-white">静音检测</span>
              <span class="mt-0.5 block text-sm text-gray-300">
                按静音计时自动结束录音；关闭后需手动停止录音
              </span>
            </span>
          </button>
        </div>

        <div class="border-t border-white/10 pt-5">
          <button
            type="button"
            role="switch"
            :aria-checked="micAutoSend"
            aria-label="自动发送"
            class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left"
            @click="toggleMicAutoSend"
          >
            <span
              class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="micAutoSend ? 'bg-cyan-400/70' : 'bg-white/20'"
            >
              <span
                class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
                :class="micAutoSend ? 'translate-x-4' : 'translate-x-0'"
              ></span>
            </span>
            <span class="min-w-0">
              <span class="block text-sm font-medium text-white">自动发送</span>
              <span class="mt-0.5 block text-sm text-gray-300">
                与「识别完成后」同一项：开启即自动发送，关闭则只填入输入框
              </span>
            </span>
          </button>
        </div>
      </div>
    </MenuItem>

    <!-- ===== 识别服务 ===== -->
    <MenuItem title="识别服务" size="large">
      <template #header>
        <Cloud :size="20" class="text-cyan-300" />
      </template>

      <select
        :value="activeServiceKey"
        class="mb-4 h-12 w-full cursor-pointer rounded-md border border-white/15 bg-black/25 px-3 text-sm text-white transition-colors outline-none hover:border-cyan-300/35 focus:border-cyan-300/65"
        style="color-scheme: dark"
        aria-label="识别服务"
        data-testid="asr-provider-select"
        @change="onServiceChange"
      >
        <optgroup
          v-for="group in serviceGroups"
          :key="group.provider.id"
          :label="group.provider.display_name"
        >
          <option
            v-for="item in group.items"
            :key="item.key"
            :value="item.key"
            class="bg-slate-800"
          >
            {{ item.label }}
          </option>
        </optgroup>
      </select>

      <div
        v-if="activeProviderInfo"
        class="mb-4 flex items-start gap-3 rounded-lg border border-white/10 bg-white/5 p-4"
      >
        <CircleAlert :size="18" class="mt-0.5 shrink-0 text-cyan-300" />
        <div class="min-w-0">
          <p class="text-sm font-medium text-white">{{ activeProviderInfo.display_name }}</p>
          <p class="mt-1 text-xs leading-5 text-white/50">{{ activeProviderInfo.description }}</p>
        </div>
      </div>

      <div v-if="activeProviderInfo" class="space-y-3">
        <div v-for="field in activeProviderInfo.config_fields" :key="field.key">
          <label class="mb-1.5 block text-sm font-medium text-white">
            {{ field.label }}
            <span v-if="field.kind === 'password_map' && activeRegion" class="text-gray-400">
              （{{ activeRegion.label }}）
            </span>
            <span v-if="field.required" class="text-red-500">*</span>
          </label>

          <!-- 模型下拉：有动态模型清单时优先下拉选择，右侧可刷新清单 -->
          <div v-if="field.key === 'model' && activeModels.length > 0" class="flex gap-2">
            <select
              :value="String(cfgValue('model') ?? '')"
              class="h-9 flex-1 cursor-pointer rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
              style="color-scheme: dark"
              :aria-label="field.label"
              @change="setProviderValue('model', ($event.target as HTMLSelectElement).value)"
            >
              <option v-for="m in activeModels" :key="m.id" :value="m.id" class="bg-slate-800">
                {{ m.display_name }}
              </option>
            </select>
            <button
              type="button"
              title="刷新模型列表"
              class="inline-flex h-9 w-11 shrink-0 items-center justify-center rounded-md border border-white/15 bg-white/5 text-white/70 transition-colors hover:bg-white/10 hover:text-white"
              @click="refreshModels"
            >
              ↻
            </button>
          </div>

          <input
            v-else-if="field.kind === 'password'"
            :value="String(cfgValue(field.key) ?? '')"
            type="password"
            :placeholder="field.placeholder"
            :aria-label="field.label"
            class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
            @input="setProviderValue(field.key, ($event.target as HTMLInputElement).value)"
          />

          <!-- 按地域分存的密码框（API Key）：只显示当前地域的那一个 -->
          <input
            v-else-if="field.kind === 'password_map'"
            :value="activeApiKey"
            type="password"
            :placeholder="field.placeholder"
            :aria-label="field.label"
            class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
            @input="setRegionApiKey(($event.target as HTMLInputElement).value)"
          />

          <input
            v-else-if="field.kind === 'number'"
            :value="String(cfgValue(field.key) ?? '')"
            type="number"
            :placeholder="field.placeholder"
            :aria-label="field.label"
            class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
            @input="setProviderValue(field.key, Number(($event.target as HTMLInputElement).value))"
          />

          <label v-else-if="field.kind === 'boolean'" class="flex cursor-pointer items-center gap-2 text-sm text-white">
            <input
              type="checkbox"
              :checked="cfgValue(field.key) === true"
              class="h-4 w-4 cursor-pointer accent-cyan-300"
              @change="setProviderValue(field.key, ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ cfgValue(field.key) === true ? "已启用" : "已关闭" }}</span>
          </label>

          <select
            v-else-if="field.kind === 'select'"
            :value="String(cfgValue(field.key) ?? '')"
            class="h-9 w-full cursor-pointer rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none focus:border-cyan-300/65"
            style="color-scheme: dark"
            :aria-label="field.label"
            @change="setProviderValue(field.key, ($event.target as HTMLSelectElement).value)"
          >
            <option v-for="opt in field.options ?? []" :key="opt.value" :value="opt.value" class="bg-slate-800">
              {{ opt.label }}
            </option>
          </select>

          <input
            v-else
            :value="String(cfgValue(field.key) ?? '')"
            type="text"
            :placeholder="field.placeholder"
            :aria-label="field.label"
            class="h-9 w-full rounded-md border border-white/15 bg-black/25 px-2.5 py-2 text-[13px] text-white transition-colors outline-none placeholder:text-white/35 focus:border-cyan-300/65"
            @input="setProviderValue(field.key, ($event.target as HTMLInputElement).value)"
          />

          <p v-if="field.hint" class="mt-1.5 block text-sm text-gray-300">{{ field.hint }}</p>
        </div>

        <div class="flex flex-wrap items-center gap-3">
          <button
            type="button"
            class="inline-flex min-h-9 items-center justify-center gap-1.75 rounded-md border border-cyan-300/40 bg-cyan-600/35 px-3.5 py-2 text-[13px] font-semibold text-cyan-50 transition-colors duration-200 hover:bg-cyan-600/50"
            data-testid="asr-test-button"
            @click="requireBackend('测试连接')"
          >
            <Play :size="16" />
            <span>测试连接</span>
          </button>
          <p v-if="store.settings.asr_last_result" class="max-w-md text-sm text-green-400">
            识别结果：{{ store.settings.asr_last_result }}
          </p>
        </div>
      </div>

      <!-- 流式识别开关：选中模型支持流式才可用 -->
      <div class="mt-4 border-t border-white/10 pt-4">
        <button
          type="button"
          role="switch"
          :aria-checked="store.settings.stream_enabled"
          :disabled="!providerSupportsStreaming"
          aria-label="流式识别"
          class="flex cursor-pointer items-start gap-3 border-none bg-transparent p-0 text-left disabled:cursor-not-allowed disabled:opacity-45"
          @click="setBooleanSetting('stream_enabled', !store.settings.stream_enabled)"
        >
          <span
            class="relative mt-0.5 inline-block h-5 w-9 shrink-0 rounded-full transition-colors"
            :class="store.settings.stream_enabled ? 'bg-cyan-400/70' : 'bg-white/20'"
          >
            <span
              class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform"
              :class="store.settings.stream_enabled ? 'translate-x-4' : 'translate-x-0'"
            ></span>
          </span>
          <span class="min-w-0">
            <span class="block text-sm font-medium text-white">流式识别</span>
            <span class="mt-0.5 block text-sm text-gray-300">
              {{
                !providerSupportsStreaming
                  ? "当前模型不支持流式识别"
                  : store.settings.active_provider === "llama-asr"
                    ? "录音结束后上传本地识别，结果逐步显示（llama-server 结果流式）"
                    : "说话过程中实时显示识别文字"
              }}
            </span>
          </span>
        </button>
      </div>
    </MenuItem>

    <!-- ===== 模型下载与管理 ===== -->
    <MenuItem title="模型下载与管理" size="large">
      <template #header>
        <FileDown :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <p class="text-xs text-white/45">下载、切换并清理本地识别引擎所需的模型文件</p>
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
              <p class="text-xs text-white/40">
                {{ asset.engine }} · {{ asset.size }} · {{ asset.language }}
              </p>
              <span
                class="mt-1 inline-block rounded border border-cyan-300/25 bg-cyan-600/10 px-1 py-px text-[10px] text-cyan-50/75"
              >
                {{ modelState(asset.id).installed ? "已安装" : "未下载" }}
              </span>
              <span
                v-if="asset.model === activeModelId && asset.provider === store.settings.active_provider"
                class="mt-1 ml-1 inline-block rounded border border-emerald-300/25 bg-emerald-500/10 px-1 py-px text-[10px] text-emerald-200"
              >
                使用中
              </span>
            </div>
            <div class="flex shrink-0 gap-2">
              <button
                v-if="!modelState(asset.id).installed"
                type="button"
                class="inline-flex min-h-9 items-center justify-center gap-1.75 rounded-md border border-white/15 bg-white/5 px-3 py-2 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
                @click="requireBackend('下载模型')"
              >
                <FileDown :size="16" />
                <span>下载</span>
              </button>
              <button
                v-else
                type="button"
                class="inline-flex min-h-9 items-center justify-center rounded-md border border-cyan-300/25 bg-cyan-300/10 px-3 py-2 text-[13px] text-cyan-100 transition-colors duration-200 hover:bg-cyan-300/20"
                @click="switchModel(asset)"
              >
                切换
              </button>
              <button
                type="button"
                class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-red-300/20 bg-red-400/10 text-red-200 transition-colors hover:bg-red-400/20 disabled:opacity-40"
                title="删除模型"
                :disabled="!modelState(asset.id).installed"
                @click="deleteModel(asset)"
              >
                <Trash2 :size="16" />
              </button>
            </div>
          </div>
          <div
            v-if="modelState(asset.id).progress > 0 && modelState(asset.id).progress < 100"
            class="h-1.5 w-full overflow-hidden rounded-full bg-white/10"
          >
            <div
              class="h-full rounded-full bg-cyan-400 transition-all duration-300"
              :style="{ width: modelState(asset.id).progress + '%' }"
            ></div>
          </div>
        </li>
      </ul>
    </MenuItem>

    <!-- ===== 测试区 ===== -->
    <MenuItem title="测试区" size="large">
      <template #header>
        <SlidersHorizontal :size="20" class="text-cyan-300" />
      </template>

      <div class="mb-3 flex items-center justify-between gap-3">
        <p class="text-xs text-white/45">开始录音 4 秒 → 识别 → 结果直接显示在这里</p>
        <Mic :size="18" class="text-white/40" />
      </div>

      <div
        class="min-h-24 w-full rounded-lg border border-white/10 bg-black/25 px-3 py-2.5 text-[13px] leading-6 text-white"
      >
        <span v-if="store.settings.asr_last_result">{{ store.settings.asr_last_result }}</span>
        <span v-else class="text-white/40">未识别到语音，请重试</span>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="inline-flex min-h-9 items-center justify-center gap-1.75 rounded-md border border-cyan-300/40 bg-cyan-600/35 px-3.5 py-2 text-[13px] font-semibold text-cyan-50 transition-colors duration-200 hover:bg-cyan-600/50"
          data-testid="asr-record-button"
          @click="requireBackend('开始录音')"
        >
          <Mic :size="16" />
          开始录音
        </button>
        <button
          type="button"
          class="inline-flex min-h-9 items-center justify-center rounded-md border border-white/15 bg-white/5 px-3.5 py-2 text-[13px] text-white/80 transition-colors duration-200 hover:border-cyan-300/40 hover:bg-cyan-300/10 hover:text-cyan-50"
          @click="clearResult"
        >
          清空结果
        </button>
        <span class="text-xs text-white/40">
          当前引擎：{{ activeProviderInfo?.display_name ?? "—" }} · 当前模型：{{
            activeModel?.display_name ?? "—"
          }}
        </span>
      </div>
    </MenuItem>

    <!-- ===== 状态面板 ===== -->
    <MenuItem title="状态" size="large">
      <template #header>
        <Activity :size="20" class="text-cyan-300" />
      </template>

      <div class="flex flex-col gap-1.5 text-sm text-gray-300">
        <div>
          VAD 模型:
          <span :class="vadLoaded ? 'text-green-400' : 'text-red-400'">
            {{ vadLoaded ? "已加载" : "未加载" }}
          </span>
        </div>
        <div>
          麦克风:
          <span class="text-gray-300">
            {{ store.settings.voice_input_enabled ? "空闲" : "已关闭" }}
          </span>
        </div>
        <div>
          语音输入:
          <span :class="store.settings.voice_input_enabled ? 'text-green-400' : 'text-gray-400'">
            {{ store.settings.voice_input_enabled ? "已启用" : "已关闭" }}
          </span>
        </div>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  Activity,
  CircleAlert,
  Cloud,
  Ear,
  FileDown,
  Keyboard,
  Mic,
  MicVocal,
  Play,
  RefreshCw,
  Send,
  SlidersHorizontal,
  Trash2,
} from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { notify, store, updateSettings } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import type { AsrModelState, AsrProviderConfig, SiteSettings } from "../../../data/mock";

type FieldKind = "text" | "password" | "password_map" | "number" | "boolean" | "select";
type SendMode = "fill_only" | "auto_send";

interface AsrFieldOption {
  value: string;
  label: string;
}

interface AsrConfigField {
  key: string;
  label: string;
  kind: FieldKind;
  placeholder?: string;
  hint?: string;
  required?: boolean;
  default_value?: string;
  options?: AsrFieldOption[];
}

interface AsrRegion {
  id: string;
  label: string;
  http_endpoint: string;
  ws_endpoint: string;
}

interface AsrModel {
  id: string;
  display_name: string;
  supports_streaming: boolean;
  is_default?: boolean;
}

interface AsrProvider {
  id: string;
  display_name: string;
  description: string;
  config_fields: AsrConfigField[];
  regions: AsrRegion[];
  models: AsrModel[];
}

interface ModelAsset {
  id: string;
  name: string;
  engine: string;
  size: string;
  language: string;
  /** 切换模型时写入的 provider / model */
  provider: string;
  model: string;
}

const SEND_MODE_OPTIONS: { value: SendMode; label: string }[] = [
  { value: "fill_only", label: "填入输入框（推荐）" },
  { value: "auto_send", label: "自动发送" },
];

const MIC_DEVICES: AsrFieldOption[] = [
  { value: "default", label: "系统默认设备" },
  { value: "mic-array", label: "麦克风阵列（Realtek）" },
  { value: "headset", label: "耳机麦克风" },
  { value: "builtin", label: "内置麦克风" },
];

const LANGUAGE_OPTIONS: AsrFieldOption[] = [
  { value: "zh", label: "中文" },
  { value: "zh-CN", label: "中文（中国大陆）" },
  { value: "en", label: "英语" },
  { value: "ja", label: "日语" },
  { value: "ko", label: "韩语" },
  { value: "auto", label: "自动检测" },
];

const DEVICE_OPTIONS: AsrFieldOption[] = [
  { value: "cpu", label: "CPU" },
  { value: "cuda", label: "CUDA" },
  { value: "cuda-int8", label: "CUDA（INT8 量化）" },
];

const MODEL_SIZE_OPTIONS: AsrFieldOption[] = [
  { value: "tiny", label: "tiny（75 MB）" },
  { value: "base", label: "base（142 MB）" },
  { value: "small", label: "small（466 MB）" },
  { value: "medium", label: "medium（1.5 GB）" },
  { value: "large", label: "large（2.9 GB）" },
];

/** 识别服务商清单：原版由后端 ProviderInfo 下发，本项目静态内置，字段形态与之一致 */
const ASR_PROVIDERS: AsrProvider[] = [
  {
    id: "llama-asr",
    display_name: "本地 llama-server（whisper.cpp）",
    description: "本机运行的 llama-server / whisper.cpp，模型清单从服务端 /v1/models 拉取，无需联网。",
    config_fields: [
      {
        key: "endpoint",
        label: "端点地址",
        kind: "text",
        placeholder: "http://127.0.0.1:8080",
        hint: "llama-server 的 OpenAI 兼容地址，服务没起时模型列表会拉不到",
      },
      { key: "model", label: "模型", kind: "select", options: [] },
      {
        key: "model_path",
        label: "模型路径",
        kind: "text",
        placeholder: "models/ggml-base.bin",
        hint: "本地 ggml / gguf 模型文件相对路径",
      },
      { key: "model_size", label: "模型大小", kind: "select", options: MODEL_SIZE_OPTIONS },
      { key: "device", label: "设备", kind: "select", options: DEVICE_OPTIONS },
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
      { key: "threads", label: "线程数", kind: "number", placeholder: "4" },
      { key: "translate", label: "翻译为英文", kind: "boolean" },
    ],
    regions: [],
    models: [
      { id: "whisper-base", display_name: "Whisper Base（本地）", supports_streaming: false, is_default: true },
      { id: "whisper-small", display_name: "Whisper Small（本地）", supports_streaming: false },
      { id: "whisper-large-v3", display_name: "Whisper Large V3（流式）", supports_streaming: true },
    ],
  },
  {
    id: "fun-asr",
    display_name: "FunASR（本地服务）",
    description: "阿里 FunASR 本地服务，中文识别准确率高，支持热词与流式 paraformer。",
    config_fields: [
      {
        key: "endpoint",
        label: "端点地址",
        kind: "text",
        placeholder: "http://127.0.0.1:10095",
      },
      { key: "model", label: "模型", kind: "select", options: [] },
      { key: "model_path", label: "模型路径", kind: "text", placeholder: "models/paraformer-zh" },
      { key: "model_size", label: "模型大小", kind: "select", options: MODEL_SIZE_OPTIONS },
      { key: "device", label: "设备", kind: "select", options: DEVICE_OPTIONS },
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
      { key: "threads", label: "线程数", kind: "number", placeholder: "4" },
      { key: "hotword", label: "热词", kind: "text", placeholder: "用空格分隔多个热词" },
    ],
    regions: [],
    models: [
      { id: "paraformer-zh", display_name: "Paraformer 中文（离线）", supports_streaming: false, is_default: true },
      { id: "paraformer-zh-streaming", display_name: "Paraformer 中文（流式）", supports_streaming: true },
      { id: "fun-asr-nano", display_name: "FunASR Nano（轻量）", supports_streaming: false },
    ],
  },
  {
    id: "sense-voice",
    display_name: "SenseVoice（本地服务）",
    description: "SenseVoiceSmall 多语言模型，带内置 VAD 与情感/事件标签。",
    config_fields: [
      { key: "endpoint", label: "端点地址", kind: "text", placeholder: "http://127.0.0.1:50000" },
      { key: "model", label: "模型", kind: "select", options: [] },
      { key: "model_path", label: "模型路径", kind: "text", placeholder: "models/SenseVoiceSmall" },
      { key: "model_size", label: "模型大小", kind: "select", options: MODEL_SIZE_OPTIONS },
      { key: "device", label: "设备", kind: "select", options: DEVICE_OPTIONS },
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
      { key: "threads", label: "线程数", kind: "number", placeholder: "2" },
      { key: "vad", label: "内置 VAD 切分", kind: "boolean" },
    ],
    regions: [],
    models: [
      { id: "sense-voice-small", display_name: "SenseVoiceSmall（离线）", supports_streaming: false, is_default: true },
      { id: "sense-voice-small-streaming", display_name: "SenseVoiceSmall（流式）", supports_streaming: true },
    ],
  },
  {
    id: "qwen",
    display_name: "云端 API（阿里云百炼 DashScope）",
    description: "阿里云百炼语音识别，按地域分存 API Key，支持一句话识别与实时流式识别。",
    config_fields: [
      {
        key: "region",
        label: "地域",
        kind: "select",
        options: [
          { value: "beijing", label: "北京" },
          { value: "singapore", label: "新加坡（国际站）" },
        ],
        hint: "切换地域会同步该地域的默认端点，API Key 按地域分别保存",
      },
      {
        key: "api_key",
        label: "API Key",
        kind: "password_map",
        required: true,
        placeholder: "sk-xxxxxxxxxxxxxxxx",
      },
      { key: "endpoint", label: "HTTP 端点地址", kind: "text" },
      { key: "ws_endpoint", label: "WebSocket 端点地址", kind: "text" },
      { key: "model", label: "模型", kind: "select", options: [] },
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
    ],
    regions: [
      {
        id: "beijing",
        label: "北京",
        http_endpoint: "https://dashscope.aliyuncs.com/api/v1",
        ws_endpoint: "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
      },
      {
        id: "singapore",
        label: "新加坡（国际站）",
        http_endpoint: "https://dashscope-intl.aliyuncs.com/api/v1",
        ws_endpoint: "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime",
      },
    ],
    models: [
      { id: "qwen3-asr-flash", display_name: "qwen3-asr-flash（一句话识别）", supports_streaming: false, is_default: true },
      { id: "qwen3-asr-flash-realtime", display_name: "qwen3-asr-flash-realtime（实时）", supports_streaming: true },
    ],
  },
  {
    id: "web-speech",
    display_name: "浏览器原生识别",
    description: "使用浏览器自带的语音识别（Web Speech API），无需任何本地模型与 Key，识别能力取决于浏览器。",
    config_fields: [
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
      { key: "interim_results", label: "显示中间结果", kind: "boolean" },
      { key: "continuous", label: "连续识别", kind: "boolean" },
    ],
    regions: [],
    models: [
      { id: "web-speech-zh", display_name: "浏览器识别（中文）", supports_streaming: true, is_default: true },
      { id: "web-speech-en", display_name: "浏览器识别（英语）", supports_streaming: true },
    ],
  },
  {
    id: "auto-vad",
    display_name: "自动语音检测（VAD）",
    description: "只做人声端点检测：由 Silero VAD 决定何时开始/结束录音，识别仍交给上方选中的引擎。",
    config_fields: [
      { key: "device", label: "设备", kind: "select", options: DEVICE_OPTIONS },
      { key: "sensitivity", label: "灵敏度（0–1）", kind: "number", placeholder: "0.5" },
      { key: "min_speech_ms", label: "最短语音时长（毫秒）", kind: "number", placeholder: "250" },
      { key: "language", label: "语言", kind: "select", options: LANGUAGE_OPTIONS },
    ],
    regions: [],
    models: [
      { id: "vad-silero-zh", display_name: "Silero VAD（中文）", supports_streaming: true, is_default: true },
      { id: "vad-silero-en", display_name: "Silero VAD（多语言）", supports_streaming: true },
    ],
  },
];

const MODEL_CATALOG: ModelAsset[] = [
  {
    id: "ggml-base",
    name: "Whisper Base（ggml-base.bin）",
    engine: "llama-asr / whisper.cpp",
    size: "142 MB",
    language: "多语言",
    provider: "llama-asr",
    model: "whisper-base",
  },
  {
    id: "ggml-small",
    name: "Whisper Small（ggml-small.bin）",
    engine: "llama-asr / whisper.cpp",
    size: "466 MB",
    language: "多语言",
    provider: "llama-asr",
    model: "whisper-small",
  },
  {
    id: "paraformer",
    name: "Paraformer 中文大模型",
    engine: "FunASR",
    size: "840 MB",
    language: "中文",
    provider: "fun-asr",
    model: "paraformer-zh",
  },
  {
    id: "sense-voice-small",
    name: "SenseVoiceSmall",
    engine: "SenseVoice",
    size: "936 MB",
    language: "中文 / 英语 / 日语",
    provider: "sense-voice",
    model: "sense-voice-small",
  },
  {
    id: "silero-vad",
    name: "Silero VAD 语音检测模型",
    engine: "自动语音检测（VAD）",
    size: "2.1 MB",
    language: "多语言",
    provider: "auto-vad",
    model: "vad-silero-zh",
  },
];

// ── 服务商 / 配置读写 ──────────────────────────────────────────

const activeProviderInfo = computed<AsrProvider | undefined>(
  () => ASR_PROVIDERS.find((p) => p.id === store.settings.active_provider) ?? ASR_PROVIDERS[0],
);

/** provider 的模型是否在它自己的表单里选（config_fields 含 model 字段即为是） */
function picksModelInForm(p: AsrProvider | undefined): boolean {
  return p?.config_fields.some((f) => f.key === "model") ?? false;
}

const activeModels = computed<AsrModel[]>(() => activeProviderInfo.value?.models ?? []);

const providerCfg = computed<AsrProviderConfig>(
  () => store.settings.provider_configs[store.settings.active_provider] ?? {},
);

function cfgValue(key: string): string | number | boolean | Record<string, string> | undefined {
  return providerCfg.value[key];
}

function writeProviderConfig(id: string, key: string, value: string | number | boolean | Record<string, string>): void {
  updateSettings({
    provider_configs: {
      ...store.settings.provider_configs,
      [id]: { ...(store.settings.provider_configs[id] ?? {}), [key]: value },
    },
  });
}

function setProviderValue(key: string, value: string | number | boolean | Record<string, string>): void {
  writeProviderConfig(store.settings.active_provider, key, value);
}

/** 地域：端点默认值的单一数据源在 ProviderInfo.regions，前端不硬编码域名 */
const providerRegions = computed<AsrRegion[]>(() => activeProviderInfo.value?.regions ?? []);

const activeRegion = computed<AsrRegion | undefined>(() => {
  const regions = providerRegions.value;
  if (regions.length === 0) return undefined;
  const id = String(cfgValue("region") ?? "");
  return regions.find((r) => r.id === id) ?? regions[0];
});

const activeApiKey = computed<string>(() => {
  const region = activeRegion.value;
  if (!region) return String(cfgValue("api_key") ?? "");
  const keys = cfgValue("api_keys");
  if (keys && typeof keys === "object") return keys[region.id] ?? "";
  return "";
});

function setRegionApiKey(value: string): void {
  const region = activeRegion.value;
  if (!region) {
    setProviderValue("api_key", value);
    return;
  }
  const keys = cfgValue("api_keys");
  const current = keys && typeof keys === "object" ? keys : {};
  setProviderValue("api_keys", { ...current, [region.id]: value });
}

/** 只改写「空」或「恰好等于某个地域默认端点」的值，用户手填的域名一律不动 */
function applyRegionEndpoints(): void {
  const region = activeRegion.value;
  if (!region) return;
  const pairs: [string, string][] = [
    ["endpoint", region.http_endpoint],
    ["ws_endpoint", region.ws_endpoint],
  ];
  for (const [key, next] of pairs) {
    const cur = String(cfgValue(key) ?? "").trim();
    const known = providerRegions.value.some((r) => r.http_endpoint === cur || r.ws_endpoint === cur);
    if (!cur || known) setProviderValue(key, next);
  }
}

/** 后端 default_value 兜底空字段，已有值不覆盖 */
function ensureProviderConfig(): void {
  const id = store.settings.active_provider;
  const info = activeProviderInfo.value;
  if (!info) return;
  const cfg = store.settings.provider_configs[id] ?? {};
  for (const field of info.config_fields) {
    if (field.default_value && cfg[field.key] === undefined) {
      cfg[field.key] = field.default_value;
    }
  }
  updateSettings({
    provider_configs: { ...store.settings.provider_configs, [id]: cfg },
  });
}

/** 应用模型预设：填 model，并确保两个端点字段已按当前地域填好 */
function applyAsrPreset(modelId: string): void {
  setProviderValue("model", modelId);
  applyRegionEndpoints();
}

/** 当前生效模型：配置非空取配置，否则默认模型 */
const activeModel = computed<AsrModel | undefined>(() => {
  const id = String(cfgValue("model") ?? "");
  return activeModels.value.find((m) => m.id === id) ?? activeModels.value.find((m) => m.is_default);
});
const activeModelId = computed<string>(() => activeModel.value?.id ?? "");

/** 流式开关可用性：当前生效模型的流式能力（模型级权威判定） */
const providerSupportsStreaming = computed(() => activeModel.value?.supports_streaming ?? false);

// 切到不支持流式的模型 → 自动关闭流式开关（避免录音时后端报错）
watch(activeModelId, () => {
  if (!providerSupportsStreaming.value && store.settings.stream_enabled) {
    updateSettings({ stream_enabled: false });
  }
});

// 流式开关 ↔ 模型自动同步：打开流式 → 切流式模型；关闭 → 切非流式模型
watch(
  () => store.settings.stream_enabled,
  (on) => {
    const m = activeModel.value;
    if (!m) return;
    if (on && !m.supports_streaming) {
      const sm = activeModels.value.find((x) => x.supports_streaming);
      if (sm) applyAsrPreset(sm.id);
    } else if (!on && m.supports_streaming) {
      const nm = activeModels.value.find((x) => !x.supports_streaming);
      if (nm) applyAsrPreset(nm.id);
    }
  },
);

// ── 扁平「识别服务」列表（按 provider 分组，模型各占一项） ─────────────

interface ServiceItem {
  /** 编码为 `${provider_id}::${model_id}`；model_id 为空 = 该 provider 的模型在表单里选 */
  key: string;
  label: string;
}

const serviceGroups = computed<{ provider: AsrProvider; items: ServiceItem[] }[]>(() =>
  ASR_PROVIDERS.map((p) => {
    if (picksModelInForm(p)) {
      return { provider: p, items: [{ key: `${p.id}::`, label: p.display_name }] };
    }
    const items: ServiceItem[] =
      p.models.length > 0
        ? p.models.map((m) => ({ key: `${p.id}::${m.id}`, label: m.display_name }))
        : [{ key: `${p.id}::`, label: `${p.display_name}（模型未列出）` }];
    return { provider: p, items };
  }),
);

const activeServiceKey = computed<string>(() => {
  const pid = store.settings.active_provider;
  const info = ASR_PROVIDERS.find((p) => p.id === pid);
  if (!info || picksModelInForm(info)) return `${pid}::`;
  const key = `${pid}::${String(cfgValue("model") ?? "")}`;
  const group = serviceGroups.value.find((g) => g.provider.id === pid);
  return group?.items.some((i) => i.key === key) ? key : (group?.items[0]?.key ?? key);
});

function onServiceChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value;
  const sep = value.indexOf("::");
  const pid = sep < 0 ? value : value.slice(0, sep);
  const mid = sep < 0 ? "" : value.slice(sep + 2);
  updateSettings({ active_provider: pid });
  if (mid) writeProviderConfig(pid, "model", mid);
}

function refreshModels(): void {
  notify(`已刷新 ${activeProviderInfo.value?.display_name ?? "识别服务"} 的模型列表（${activeModels.value.length} 个）`);
}

// ── 模型下载与管理 ──────────────────────────────────────────

function modelState(id: string): AsrModelState {
  return store.settings.asr_model_states[id] ?? { installed: false, progress: 0 };
}

function setModelState(id: string, patch: Partial<AsrModelState>): void {
  updateSettings({
    asr_model_states: {
      ...store.settings.asr_model_states,
      [id]: { ...modelState(id), ...patch },
    },
  });
}

function switchModel(asset: ModelAsset): void {
  if (!modelState(asset.id).installed) {
    void alertDialog("切换模型", "该模型尚未下载，请先下载后再切换。");
    return;
  }
  updateSettings({ active_provider: asset.provider });
  writeProviderConfig(asset.provider, "model", asset.model);
  notify(`已切换到 ${asset.name}`);
}

async function deleteModel(asset: ModelAsset): Promise<void> {
  const ok = await confirmDialog("删除模型", `确定要删除「${asset.name}」吗？删除后需要重新下载才能使用。`);
  if (!ok) return;
  setModelState(asset.id, { installed: false, progress: 0 });
  notify(`已删除 ${asset.name}`);
}

// ── 测试区 ──────────────────────────────────────────────────

function clearResult(): void {
  updateSettings({ asr_last_result: "" });
  notify("已清空识别结果");
}

function requireBackend(action: string): void {
  void alertDialog(action, "需要后端服务，当前未接入");
}

// ── 顶部状态 ────────────────────────────────────────────────

const missingCredential = computed(() => {
  if (!store.settings.voice_input_enabled) return false;
  const info = activeProviderInfo.value;
  if (!info) return false;
  return info.config_fields.some((f) => {
    if (!f.required) return false;
    if (f.kind === "password_map") return !activeApiKey.value.trim();
    return !String(cfgValue(f.key) ?? "").trim();
  });
});

const statusText = computed(() => {
  if (!store.settings.voice_input_enabled) return "已关闭";
  return missingCredential.value ? "未配置（请先填写该服务商的 API Key）" : "已就绪";
});
const statusClass = computed(() =>
  !store.settings.voice_input_enabled ? "text-gray-300" : missingCredential.value ? "text-red-400" : "text-green-400",
);

const vadLoaded = computed(() => modelState("silero-vad").installed);

// ── 麦克风：自动发送与「识别完成后」是同一项 ────────────────────

const micAutoSend = computed(() => store.settings.send_mode === "auto_send");

function toggleMicAutoSend(): void {
  updateSettings({ send_mode: micAutoSend.value ? "fill_only" : "auto_send" });
}

// ── 语音快捷键捕获 ──────────────────────────────────────────

interface ShortcutBinding {
  key: string;
  ctrl?: boolean;
  alt?: boolean;
  shift?: boolean;
  meta?: boolean;
}

function parsePttBinding(raw: string): ShortcutBinding | null {
  try {
    const parsed = JSON.parse(raw) as ShortcutBinding;
    return typeof parsed?.key === "string" ? parsed : null;
  } catch {
    // 非法 JSON 回退裸键（与原版一致）
    return raw ? { key: raw } : null;
  }
}

const KEY_NAMES: Record<string, string> = {
  " ": "Space",
  spacebar: "Space",
  arrowup: "↑",
  arrowdown: "↓",
  arrowleft: "←",
  arrowright: "→",
  escape: "Esc",
};

function formatKeyName(key: string): string {
  const lower = key.toLowerCase();
  if (KEY_NAMES[lower]) return KEY_NAMES[lower] as string;
  if (lower.startsWith("f") && /^f\d{1,2}$/.test(lower)) return lower.toUpperCase();
  return lower.length === 1 ? lower.toUpperCase() : lower.charAt(0).toUpperCase() + lower.slice(1);
}

function formatBinding(binding: ShortcutBinding): string {
  const parts: string[] = [];
  if (binding.ctrl) parts.push("Ctrl");
  if (binding.alt) parts.push("Alt");
  if (binding.shift) parts.push("Shift");
  if (binding.meta) parts.push("Meta");
  parts.push(formatKeyName(binding.key));
  return parts.join(" + ");
}

const pttBinding = computed<ShortcutBinding>(
  () => parsePttBinding(store.settings.ptt_key) ?? { key: "f8" },
);

const capturingPtt = ref(false);
const pttCaptureInvalid = ref(false);
let pttKeyHandler: ((e: KeyboardEvent) => void) | null = null;

function endPttCapture(): void {
  capturingPtt.value = false;
  pttCaptureInvalid.value = false;
  window.removeEventListener("blur", endPttCapture);
  if (pttKeyHandler) {
    window.removeEventListener("keydown", pttKeyHandler, true);
    pttKeyHandler = null;
  }
}

function startPttCapture(): void {
  if (capturingPtt.value) return;
  capturingPtt.value = true;
  pttCaptureInvalid.value = false;
  window.addEventListener("blur", endPttCapture);
  pttKeyHandler = (e: KeyboardEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.key === "Escape") {
      endPttCapture();
      return;
    }
    if (e.key === "Enter") {
      // PTT 专用键位校验：Enter 是聊天发送键，绑定后发消息会误触发录音
      pttCaptureInvalid.value = true;
      return;
    }
    if (["Control", "Alt", "Shift", "Meta"].includes(e.key)) return;
    const binding: ShortcutBinding = {
      key: e.key.toLowerCase(),
      ctrl: e.ctrlKey,
      alt: e.altKey,
      shift: e.shiftKey,
      meta: e.metaKey,
    };
    updateSettings({ ptt_key: JSON.stringify(binding) });
    endPttCapture();
  };
  window.addEventListener("keydown", pttKeyHandler, true);
}

function resetPttKey(): void {
  endPttCapture();
  updateSettings({ ptt_key: '{"key":"f8"}' });
  notify("已恢复默认快捷键 F8");
}

// ── 通用设置写入 ────────────────────────────────────────────

function setBooleanSetting(key: keyof SiteSettings, value: boolean): void {
  updateSettings({ [key]: value } as unknown as Partial<SiteSettings>);
}

function setStringFromEvent(key: keyof SiteSettings, event: Event): void {
  updateSettings({
    [key]: (event.target as HTMLInputElement | HTMLSelectElement).value,
  } as unknown as Partial<SiteSettings>);
}

function setNumberFromEvent(key: keyof SiteSettings, event: Event): void {
  const raw = (event.target as HTMLInputElement).value;
  const parsed = Number(raw);
  if (raw === "" || Number.isNaN(parsed)) return;
  updateSettings({ [key]: parsed } as unknown as Partial<SiteSettings>);
}

onMounted(() => {
  ensureProviderConfig();
});

// 切换服务商：补默认配置 + 同步该地域端点
watch(
  () => store.settings.active_provider,
  () => {
    ensureProviderConfig();
    applyRegionEndpoints();
  },
);

watch(
  () => String(cfgValue("region") ?? ""),
  () => applyRegionEndpoints(),
);

onBeforeUnmount(endPttCapture);
</script>

<style scoped>
/* ===== 滑块：原生 range + 原版自定义轨道/滑块头（与声音页同款） ===== */
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

.b2-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  margin-top: -6px;
  cursor: pointer;
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow:
    0 2px 6px rgba(0, 0, 0, 0.3),
    0 0 8px rgba(121, 217, 255, 0.5);
  transition: all 0.2s ease;
}

.b2-range::-moz-range-track {
  width: 100%;
  height: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.b2-range::-moz-range-thumb {
  width: 18px;
  height: 18px;
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 50%;
  cursor: pointer;
  background: linear-gradient(135deg, var(--accent-color), #64b5f6);
  box-shadow:
    0 2px 6px rgba(0, 0, 0, 0.3),
    0 0 8px rgba(121, 217, 255, 0.5);
}
</style>
