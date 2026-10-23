<template>
  <div class="flex h-full min-h-0 flex-col md:grid md:grid-cols-[min(28%,240px)_1fr]">
    <nav class="border-brand/40 flex flex-col gap-1 overflow-y-auto py-2 pr-0 md:border-r md:pr-4">
      <a
        v-for="item in navItems"
        :key="item"
        href="#"
        class="adv-nav-link relative z-10 block rounded-lg px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black active:font-bold active:text-white"
        :class="{ 'bg-brand/30 font-bold': selected === item }"
        @click.prevent="selected = item"
      >
        {{ navLabel(item) }}
      </a>
    </nav>

    <main class="custom-scrollbar h-full overflow-y-auto px-2 py-2 md:px-6">
      <div
        v-if="android"
        class="mb-5 rounded-xl border border-sky-300/30 bg-sky-400/10 px-4 py-3 text-sm"
      >
        <p class="font-semibold text-sky-200">Android 工具说明</p>
        <p class="mt-1 text-gray-300">
          工具设置按设备单独保存，不会从 Windows 同步。请在这台手机上开启需要的工具并点击保存；命令执行和桌面插件在 Android 上不可用。
        </p>
        <p v-if="!modelConfigured" class="mt-2 text-amber-300">
          当前没有可用的聊天模型，请先配置模型、API Key 和模型名称。
        </p>
        <p v-else-if="!nativeToolCallsSupported" class="mt-2 text-amber-300">
          当前聊天模型不支持原生工具调用，请换用支持 function calling 的模型。
        </p>
        <p v-else-if="allowedToolCount === 0" class="mt-2 text-amber-300">
          当前面试官还没有启用任何可用工具。可逐项开启，或使用下方的推荐配置。
        </p>
        <p v-else class="mt-2 text-emerald-300">
          当前面试官有 {{ allowedToolCount }} 个工具可用。保存后新对话会使用最新配置。
        </p>
        <button
          type="button"
          class="mt-3 rounded-lg border border-sky-200/30 bg-sky-300/15 px-3 py-2 text-sky-100 transition-colors hover:bg-sky-300/25"
          @click="enableAndroidRecommended"
        >
          启用 Android 可用工具（仍需保存）
        </button>
      </div>

      <div v-if="selected === 'access'">
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
          工具访问权限
        </h2>
        <p class="mb-4 px-1 text-sm text-gray-300">
          只读工具（Read / Glob / Grep / ReadMediaFile）始终直接运行；这里控制文件修改和命令执行是否需要确认。
        </p>
        <div class="grid gap-3">
          <button
            v-for="mode in accessModes"
            :key="mode"
            type="button"
            class="w-full rounded-xl border px-4 py-3 text-left transition-all duration-200"
            :class="[
              form.access_mode === mode
                ? mode === 'full_access'
                  ? 'border-amber-400/80 bg-amber-400/10 ring-1 ring-amber-400/25'
                  : 'border-brand bg-brand/15 ring-brand/25 ring-1'
                : 'border-white/15 bg-white/5 hover:bg-white/10',
            ]"
            @click="selectAccessMode(mode)"
          >
            <div class="flex items-center justify-between gap-3">
              <span class="flex items-center gap-2.5">
                <span
                  class="flex h-4 w-4 shrink-0 items-center justify-center rounded-full border transition-colors duration-200"
                  :class="
                    form.access_mode === mode
                      ? mode === 'full_access'
                        ? 'border-amber-400'
                        : 'border-brand'
                      : 'border-white/30'
                  "
                >
                  <span
                    v-if="form.access_mode === mode"
                    class="h-2 w-2 rounded-full"
                    :class="mode === 'full_access' ? 'bg-amber-400' : 'bg-brand'"
                  ></span>
                </span>
                <span
                  class="font-semibold"
                  :class="mode === 'full_access' ? 'text-amber-300' : 'text-white'"
                >
                  {{ accessModeText[mode].label }}
                </span>
              </span>
              <span
                v-if="form.access_mode === mode"
                class="rounded-full border px-2 py-0.5 text-xs"
                :class="
                  mode === 'full_access'
                    ? 'border-amber-400/60 text-amber-300'
                    : 'border-brand/60 text-brand'
                "
              >
                当前
              </span>
            </div>
            <p class="mt-1 text-sm text-gray-300">
              {{ accessModeText[mode].description }}
            </p>
          </button>
        </div>

        <div class="mt-3 rounded-xl border border-white/15 bg-white/5 px-4 py-3">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <h3 class="font-semibold text-white">工具调用轮数上限</h3>
              <p class="mt-1 text-sm leading-relaxed text-gray-300">
                限制主聊中单次回复可连续执行工具的轮数；每轮可能包含多个工具调用。
              </p>
            </div>
            <div class="flex shrink-0 flex-col items-start gap-1 sm:items-end">
              <label
                class="focus-within:border-brand flex items-center gap-2 rounded-lg border border-white/20 bg-black/20 px-3 py-2"
              >
                <input
                  v-model.number="form.max_tool_rounds"
                  type="number"
                  :min="MIN_TOOL_ROUND_LIMIT"
                  :max="MAX_TOOL_ROUND_LIMIT"
                  step="1"
                  class="w-16 [appearance:textfield] bg-transparent text-center font-semibold text-white outline-none [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                  aria-label="工具调用轮数上限"
                  @blur="normalizeToolRoundLimit"
                />
                <span class="text-sm text-gray-300">轮</span>
              </label>
              <p class="px-1 text-xs text-gray-400">范围 1–64 · 默认 8</p>
            </div>
          </div>
        </div>

        <div class="mt-3 rounded-xl border border-white/15 bg-white/5 px-4 py-3">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <h3 class="font-semibold text-white">工具按需检索</h3>
              <p class="mt-1 text-sm leading-relaxed text-gray-300">
                每次请求只下发常用工具，模型需要时先用一句话检索出合适的工具再调用，省下全部工具说明占用的上下文。关闭后每次请求都下发全部已授权工具。
              </p>
            </div>
            <div class="flex shrink-0 items-center">
              <button
                type="button"
                role="switch"
                :aria-checked="form.tool_rag"
                aria-label="工具按需检索"
                class="toggle-control"
                @click="form.tool_rag = !form.tool_rag"
              >
                <span class="toggle-track" :class="form.tool_rag ? 'toggle-track-on' : 'toggle-track-off'">
                  <span class="toggle-knob" :class="form.tool_rag ? 'toggle-knob-on' : 'toggle-knob-off'"></span>
                </span>
              </button>
            </div>
          </div>
        </div>

        <div class="mt-3 rounded-xl border border-white/15 bg-white/5 px-4 py-3">
          <h3 class="font-semibold text-white">工具推荐</h3>
          <p class="mt-1 text-sm leading-relaxed text-gray-300">
            模型常常想不起来调用工具。这里选择每轮发言前怎么给它提个醒：推荐只是临时提示，不写进台词表；推荐的工具会随这一轮请求一起下发。
          </p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button
              v-for="mode in toolRecommendModes"
              :key="mode"
              type="button"
              class="rounded-lg border px-3 py-1.5 text-sm transition-colors duration-200"
              :class="
                form.tool_recommend_mode === mode
                  ? 'border-brand bg-brand/15 text-white'
                  : 'border-white/15 bg-black/20 text-gray-300 hover:bg-white/10'
              "
              @click="form.tool_recommend_mode = mode"
            >
              {{ recommendModeText[mode].label }}
            </button>
          </div>
          <p class="mt-2 text-xs leading-relaxed text-gray-400">
            {{ recommendModeText[form.tool_recommend_mode].description }}
          </p>
        </div>

        <div
          v-if="form.access_mode === 'full_access'"
          class="mt-3 rounded-lg border-l-4 border-amber-400/70 bg-amber-400/10 px-4 py-3 text-sm text-amber-200/90"
        >
          ⚠ 完全访问会取消文件与命令审批，并解除 data/ 文件沙箱限制。保存后立即生效。
        </div>

        <div
          v-if="form.access_mode === 'full_access' && windows"
          class="mt-3 rounded-xl border border-white/15 bg-black/20 px-4 py-3"
        >
          <div class="flex items-center gap-2 font-semibold text-white">
            <span class="h-2.5 w-2.5 rounded-full bg-amber-400"></span>
            管理员运行状态
          </div>
          <p class="mt-2 text-sm text-gray-300">
            当前是标准权限；需要管理员权限的命令仍会显示 Windows UAC。
          </p>
          <button
            type="button"
            class="mt-3 rounded-lg border border-amber-400/60 bg-amber-400/15 px-4 py-2 text-sm font-semibold text-amber-200 transition-colors hover:bg-amber-400/25"
            @click="restartAsAdmin"
          >
            以管理员身份重启
          </button>
          <p class="mt-2 text-xs text-gray-400">
            Windows 会在重启时正常确认一次；重启后执行的命令都会继承管理员权限。
          </p>
        </div>
      </div>

      <div v-else-if="selected === 'web_search'">
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">网页搜索</h2>

        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.web_search.enabled"
            aria-label="启用网页搜索工具"
            class="toggle-control"
            @click="form.web_search.enabled = !form.web_search.enabled"
          >
            <span
              class="toggle-track"
              :class="form.web_search.enabled ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.web_search.enabled ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">启用网页搜索工具（让ta可以联网查资料）</p>
        </div>

        <label class="text-brand mt-2 inline-flex items-center font-medium">搜索提供商</label>
        <select
          v-model="form.web_search.provider"
          class="shadow-glass focus:border-brand focus:ring-brand/20 mt-2 w-full cursor-pointer rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:ring-2 focus:outline-none"
          style="color-scheme: dark"
        >
          <option value="kimi" class="bg-slate-800 text-white">Kimi /search</option>
          <option value="bocha" class="bg-slate-800 text-white">BoCha 博查</option>
          <option value="deepseek" class="bg-slate-800 text-white">DeepSeek 网页搜索</option>
          <option value="tavily" class="bg-slate-800 text-white">Tavily</option>
          <option value="codex" class="bg-slate-800 text-white">OpenAI Codex（订阅免 Key）</option>
          <option value="custom" class="bg-slate-800 text-white">自定义端点</option>
        </select>

        <template v-if="form.web_search.provider === 'codex'">
          <label class="text-brand mt-4 inline-flex items-center font-medium">API Key</label>
          <p class="mt-2 mb-2 text-sm text-gray-300">
            复用「大模型管理」中已登录的 Codex 订阅凭据联网搜索，无需填写 API Key
          </p>
        </template>

        <template v-else>
          <label class="text-brand mt-4 inline-flex items-center font-medium">API Key</label>
          <input
            v-model="form.web_search.api_key"
            type="password"
            :placeholder="apiKeyPlaceholder"
            class="shadow-glass focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 placeholder:text-white/50 focus:ring-2 focus:outline-none"
          />
          <p v-if="form.web_search.provider === 'kimi'" class="mt-2 mb-2 text-sm text-gray-300">
            API Key 可留空：当前对话模型为官方 Kimi Code 时，会安全复用它的凭据；其他情况请单独填写
          </p>
        </template>

        <p v-if="form.web_search.provider === 'deepseek'" class="mt-2 mb-2 text-sm text-gray-300">
          通过 DeepSeek /v1/tools/search 端点联网搜索；需要单独填写 DeepSeek API Key
        </p>

        <template v-if="form.web_search.provider === 'custom'">
          <label class="text-brand mt-4 inline-flex items-center font-medium">搜索服务地址</label>
          <p class="mt-1 mb-2 text-sm text-gray-300">
            自定义端点需兼容 Kimi /search 协议（POST JSON，字段名为 text_query）
          </p>
          <input
            v-model="form.web_search.base_url"
            type="text"
            placeholder="https://api.kimi.com/coding/v1/search"
            class="shadow-glass focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 placeholder:text-white/50 focus:ring-2 focus:outline-none"
          />
        </template>

        <label class="text-brand mt-4 inline-flex items-center font-medium">返回结果条数</label>
        <input
          v-model.number="form.web_search.max_results"
          type="number"
          min="1"
          max="20"
          step="1"
          class="shadow-glass focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:ring-2 focus:outline-none"
        />

        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.web_search.hide_search_results"
            aria-label="隐藏搜索结果"
            class="toggle-control"
            @click="form.web_search.hide_search_results = !form.web_search.hide_search_results"
          >
            <span
              class="toggle-track"
              :class="form.web_search.hide_search_results ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.web_search.hide_search_results ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">隐藏搜索结果（ta 回答时不显示来源和网址）</p>
        </div>

        <div class="mt-2 flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.web_search.proxy_enabled"
            aria-label="通过代理访问搜索服务"
            class="toggle-control"
            @click="form.web_search.proxy_enabled = !form.web_search.proxy_enabled"
          >
            <span
              class="toggle-track"
              :class="form.web_search.proxy_enabled ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.web_search.proxy_enabled ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">通过代理访问搜索服务（如 v2rayN）</p>
        </div>
        <p v-if="android" class="mb-2 px-1 text-sm text-amber-300">
          Android 上的 127.0.0.1 指手机本身，不是 Windows 电脑；使用电脑代理时请填写电脑的局域网地址。
        </p>
        <input
          v-if="form.web_search.proxy_enabled"
          v-model="form.web_search.proxy_addr"
          type="text"
          placeholder="http://127.0.0.1:10808"
          class="shadow-glass focus:border-brand focus:ring-brand/20 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 placeholder:text-white/50 focus:ring-2 focus:outline-none"
        />
      </div>

      <div v-else-if="selected === 'media'">
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
          图片与视频识别
        </h2>
        <p class="mb-4 px-1 text-sm text-gray-300">
          ReadMediaFile 会读取本地媒体，并把内容交给“模型管理”中指定的视觉模型分析。路径权限与文件操作工具一致。
        </p>

        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.groups.media ?? false"
            aria-label="启用 ReadMediaFile 工具"
            class="toggle-control"
            @click="setGroup('media', !(form.groups.media ?? false))"
          >
            <span
              class="toggle-track"
              :class="form.groups.media ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.groups.media ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">启用 ReadMediaFile 工具</p>
        </div>

        <div class="border-brand/30 bg-brand/10 my-3 rounded-xl border px-4 py-3">
          <p class="text-sm text-gray-200">
            识别使用“高级设置 → 大模型管理 → 视觉模型”；未单独指定时跟随聊天模型。
          </p>
        </div>

        <div class="grid gap-2 sm:grid-cols-2">
          <div class="flex items-center gap-3 rounded-lg bg-white/5 px-3 py-2.5">
            <button
              type="button"
              role="switch"
              :aria-checked="form.media_file.image_enabled"
              aria-label="允许识别图片"
              class="toggle-control"
              @click="form.media_file.image_enabled = !form.media_file.image_enabled"
            >
              <span
                class="toggle-track"
                :class="form.media_file.image_enabled ? 'toggle-track-on' : 'toggle-track-off'"
              >
                <span
                  class="toggle-knob"
                  :class="form.media_file.image_enabled ? 'toggle-knob-on' : 'toggle-knob-off'"
                ></span>
              </span>
            </button>
            <p class="text-sm text-gray-300">允许识别图片（JPEG / PNG / WebP / GIF）</p>
          </div>
          <div class="flex items-center gap-3 rounded-lg bg-white/5 px-3 py-2.5">
            <button
              type="button"
              role="switch"
              :aria-checked="form.media_file.video_enabled"
              aria-label="允许识别视频"
              class="toggle-control"
              @click="form.media_file.video_enabled = !form.media_file.video_enabled"
            >
              <span
                class="toggle-track"
                :class="form.media_file.video_enabled ? 'toggle-track-on' : 'toggle-track-off'"
              >
                <span
                  class="toggle-knob"
                  :class="form.media_file.video_enabled ? 'toggle-knob-on' : 'toggle-knob-off'"
                ></span>
              </span>
            </button>
            <p class="text-sm text-gray-300">允许识别视频（MP4 / MOV / WebM / MKV / AVI / MPEG）</p>
          </div>
        </div>

        <div class="mt-4 grid gap-4 sm:grid-cols-2">
          <label class="block">
            <span class="text-brand text-sm font-medium">单个媒体文件上限（MB）</span>
            <input
              v-model.number="form.media_file.max_file_mb"
              type="number"
              min="1"
              max="100"
              step="1"
              class="focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white focus:ring-2 focus:outline-none"
            />
          </label>
          <label class="block">
            <span class="text-brand text-sm font-medium">识别结果最大 Token</span>
            <input
              v-model.number="form.media_file.max_output_tokens"
              type="number"
              min="128"
              max="4096"
              step="128"
              class="focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white focus:ring-2 focus:outline-none"
            />
          </label>
          <label class="block">
            <span class="text-brand text-sm font-medium">图片默认最长边（像素）</span>
            <input
              v-model.number="form.media_file.image_max_edge"
              type="number"
              min="512"
              max="4096"
              step="128"
              :disabled="!form.media_file.image_enabled"
              class="focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white focus:ring-2 focus:outline-none disabled:opacity-50"
            />
          </label>
          <label class="block">
            <span class="text-brand text-sm font-medium">图片压缩质量（50–95）</span>
            <input
              v-model.number="form.media_file.jpeg_quality"
              type="number"
              min="50"
              max="95"
              step="1"
              :disabled="!form.media_file.image_enabled"
              class="focus:border-brand focus:ring-brand/20 mt-2 w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white focus:ring-2 focus:outline-none disabled:opacity-50"
            />
          </label>
        </div>

        <label class="mt-4 block">
          <span class="text-brand text-sm font-medium">默认识别提示词</span>
          <textarea
            v-model="form.media_file.default_prompt"
            rows="3"
            class="focus:border-brand focus:ring-brand/20 mt-2 w-full resize-y rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white focus:ring-2 focus:outline-none"
          ></textarea>
        </label>

        <p v-if="form.media_file.video_enabled" class="mt-3 px-1 text-sm text-amber-300/90">
          ⚠ 视频通过 OpenAI 兼容的 video_url 发送；实际支持情况取决于视觉模型供应商。
        </p>
      </div>

      <div v-else-if="selected === 'file_ops'">
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
          {{ navLabel(selected) }}
        </h2>
        <p class="mb-4 px-1 text-sm text-gray-400">
          开启后 ta 就能在聊天时主动使用这些工具哦（保存后生效）
        </p>
        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.groups[selected] ?? false"
            :aria-label="groupText[selected]"
            class="toggle-control"
            @click="setGroup(selected, !(form.groups[selected] ?? false))"
          >
            <span
              class="toggle-track"
              :class="form.groups[selected] ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.groups[selected] ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">{{ groupText[selected] }}</p>
        </div>
        <p
          class="mb-2 px-1 text-sm"
          :class="form.access_mode === 'full_access' ? 'text-amber-300' : 'text-gray-400'"
        >
          {{ fileAccessText[form.access_mode] }}
        </p>
        <p v-if="android" class="mb-2 px-1 text-sm text-amber-300">
          Android 文件工具仅能访问 LingChat 应用沙箱。相册、下载目录等外部文件请先通过系统文件选择器导入。
        </p>
      </div>

      <div v-else-if="selected === 'command'">
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
          {{ navLabel(selected) }}
        </h2>
        <p class="mb-4 px-1 text-sm text-gray-400">
          开启后 ta 就能在聊天时主动使用这些工具哦（保存后生效）
        </p>
        <p v-if="!commandAvailable" class="mb-2 px-1 text-sm text-amber-400">
          ⚠ 命令执行仅桌面端可用，Android 无法运行此工具
        </p>
        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.groups[selected] ?? false"
            :aria-label="groupText[selected]"
            :disabled="!commandAvailable"
            class="toggle-control disabled:cursor-not-allowed disabled:opacity-50"
            @click="setGroup(selected, !(form.groups[selected] ?? false))"
          >
            <span
              class="toggle-track"
              :class="form.groups[selected] ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.groups[selected] ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">{{ groupText[selected] }}</p>
        </div>
        <p
          class="mb-2 px-1 text-sm"
          :class="form.access_mode === 'full_access' ? 'text-amber-300' : 'text-gray-400'"
        >
          {{ commandAccessText[form.access_mode] }}
        </p>
      </div>

      <div v-else>
        <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
          {{ navLabel(selected) }}
        </h2>
        <p class="mb-4 px-1 text-sm text-gray-400">
          开启后 ta 就能在聊天时主动使用这些工具哦（保存后生效）
        </p>
        <div class="flex items-center gap-3 px-1 py-2.5">
          <button
            type="button"
            role="switch"
            :aria-checked="form.groups[selected] ?? false"
            :aria-label="groupText[selected]"
            class="toggle-control"
            @click="setGroup(selected, !(form.groups[selected] ?? false))"
          >
            <span
              class="toggle-track"
              :class="form.groups[selected] ? 'toggle-track-on' : 'toggle-track-off'"
            >
              <span
                class="toggle-knob"
                :class="form.groups[selected] ? 'toggle-knob-on' : 'toggle-knob-off'"
              ></span>
            </span>
          </button>
          <p class="text-sm text-gray-300">{{ groupText[selected] }}</p>
        </div>
      </div>

      <div class="mt-6 flex items-center gap-2">
        <button
          type="button"
          class="bg-brand w-18 cursor-pointer rounded-lg border-none px-5 py-2.5 text-sm font-medium text-white transition-colors duration-200 hover:bg-[#0056b3]"
          @click="saveSettings"
        >
          保存
        </button>
        <button
          v-if="selected === 'web_search'"
          type="button"
          class="cursor-pointer rounded-lg border border-white/20 bg-white/10 px-5 py-2.5 text-sm font-medium text-white transition-colors duration-200 hover:bg-white/20"
          @click="runTest"
        >
          测试搜索
        </button>
        <p class="text-sm text-green-400">{{ statusMessage }}</p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import type {
  MediaFileSettings,
  SiteSettings,
  ToolAccessMode,
  ToolRecommendMode,
  WebSearchSettings,
} from "../../../data/mock";
import { notify, store, updateSettings } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";

const TOOL_GROUP_KEYS = [
  "schedule",
  "memory",
  "character",
  "scene",
  "status",
  "clock",
  "skills",
  "media",
  "file_ops",
  "command",
] as const;

type ToolGroupKey = (typeof TOOL_GROUP_KEYS)[number];
type ToolSection = "access" | "web_search" | ToolGroupKey;
type ToolForm = Pick<
  SiteSettings,
  | "web_search"
  | "media_file"
  | "groups"
  | "access_mode"
  | "max_tool_rounds"
  | "tool_rag"
  | "tool_recommend_mode"
>;

const DEFAULT_TOOL_ROUND_LIMIT = 8;
const MIN_TOOL_ROUND_LIMIT = 1;
const MAX_TOOL_ROUND_LIMIT = 64;
const DEFAULT_MEDIA_PROMPT =
  "请详细识别并描述这个媒体文件的内容；如果其中包含文字、界面、人物、物体、动作或时间顺序，请准确说明。";

const selected = ref<ToolSection>("access");
const android = /Android/i.test(navigator.userAgent);
const windows = /Windows/i.test(navigator.userAgent);
const commandAvailable = computed(() => !android);
const modelConfigured = computed(() => store.settings.llmProviders.length > 0);
const nativeToolCallsSupported = computed(() => modelConfigured.value);
const allowedToolCount = computed(
  () =>
    Object.entries(form.groups).filter(([key, enabled]) => enabled && key !== "command").length +
    Number(form.web_search.enabled),
);
const navItems: readonly ToolSection[] = ["access", "web_search", ...TOOL_GROUP_KEYS];
const accessModes: ToolAccessMode[] = ["manual", "auto_approve", "full_access"];
const toolRecommendModes: ToolRecommendMode[] = ["off", "agent", "rag"];

const navText: Record<ToolSection, string> = {
  access: "工具访问权限",
  web_search: "网页搜索",
  schedule: "日程与待办",
  memory: "笔记与记忆",
  character: "面试官切换",
  scene: "场景切换",
  status: "状态查询",
  clock: "当前时间",
  skills: "技能库",
  media: "媒体识别",
  file_ops: "文件操作",
  command: "命令执行",
};

const groupText: Record<ToolGroupKey, string> = {
  schedule: "日程与待办（查日程、加/改/删待办）",
  memory: "笔记与记忆（ta 自己的小本本）",
  character: "面试官切换（列出/切换在场面试官）",
  scene: "场景切换（列出/切换场景）",
  status: "状态查询（当前状态/场景状态）",
  clock: "当前时间（查询设备本地时间）",
  skills: "技能库（让ta读取技能指令，学会新技能）",
  media: "媒体识别（用视觉模型读取图片和视频）",
  file_ops: "文件操作（沙箱内列/读/写/删/改/搜文件，限 data 目录）",
  command: "命令执行（让ta在本机运行 shell 命令）",
};

const accessModeText: Record<ToolAccessMode, { label: string; description: string }> = {
  manual: {
    label: "逐次确认",
    description: "写入、编辑、删除文件和执行命令前都需要你确认。",
  },
  auto_approve: {
    label: "自动通过",
    description: "自动批准普通写入、编辑和命令；删除文件或删除命令仍会询问。",
  },
  full_access: {
    label: "完全访问",
    description: "不再询问，可读写电脑上的任意路径并执行命令。",
  },
};

const recommendModeText: Record<ToolRecommendMode, { label: string; description: string }> = {
  off: {
    label: "不推荐",
    description: "不做任何推荐，模型完全靠自己判断要不要检索或调用工具。",
  },
  agent: {
    label: "Agent 推荐",
    description: "由一个小的决策代理看一遍最近的对话来挑工具，每轮多一次小模型调用。",
  },
  rag: {
    label: "检索推荐",
    description:
      "拿你这句话静默检索一次工具，只有分数超过阈值的才会被推荐；不额外调用模型，只花一点本地算力。",
  },
};

const fileAccessText: Record<ToolAccessMode, string> = {
  manual: "写入、编辑和删除前会逐次询问；路径限制在 data/ 沙箱内。",
  auto_approve: "普通写入和编辑自动通过，删除仍需确认；路径限制在 data/ 沙箱内。",
  full_access: "⚠ 可免确认读写或删除任意路径。",
};

const commandAccessText: Record<ToolAccessMode, string> = {
  manual: "每条命令执行前都会询问。",
  auto_approve: "普通命令自动通过；检测到删除操作时仍会询问。",
  full_access: "⚠ 所有命令（包括删除命令）均可免确认执行。",
};

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const form = reactive<ToolForm>({
  web_search: clone(store.settings.web_search) as WebSearchSettings,
  media_file: clone(store.settings.media_file) as MediaFileSettings,
  groups: { ...store.settings.groups },
  access_mode: store.settings.access_mode,
  max_tool_rounds: store.settings.max_tool_rounds,
  tool_rag: store.settings.tool_rag,
  tool_recommend_mode: store.settings.tool_recommend_mode,
});

const statusMessage = ref("");
let statusTimer: number | undefined;

const apiKeyPlaceholder = computed(() => {
  if (form.web_search.provider === "deepseek") return "填入 DeepSeek API Key";
  if (form.web_search.provider === "kimi") return "可留空复用当前 Kimi Code 凭据";
  return "填入自定义端点 API Key";
});

function navLabel(item: ToolSection): string {
  return navText[item];
}

function setGroup(key: string, value: boolean): void {
  form.groups[key] = value;
}

function enableAndroidRecommended(): void {
  for (const group of TOOL_GROUP_KEYS) form.groups[group] = group !== "command";
  statusMessage.value = "已勾选 Android 可用工具，请点击保存使其生效";
}

async function selectAccessMode(mode: ToolAccessMode): Promise<void> {
  if (mode === form.access_mode) return;
  if (mode === "full_access") {
    const approved = await confirmDialog(
      "开启完全访问？",
      "开启后，模型可以不经确认读写或删除电脑上的任意文件，并执行本机命令。请只在信任当前面试官、模型和任务时使用。是否继续？",
    );
    if (!approved) return;
  }
  form.access_mode = mode;
}

function normalizeToolRoundLimit(): void {
  const value = Number(form.max_tool_rounds);
  form.max_tool_rounds = Number.isFinite(value)
    ? Math.min(MAX_TOOL_ROUND_LIMIT, Math.max(MIN_TOOL_ROUND_LIMIT, Math.round(value)))
    : DEFAULT_TOOL_ROUND_LIMIT;
}

function normalizeMediaSettings(): void {
  const clamp = (value: unknown, fallback: number, min: number, max: number) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.min(max, Math.max(min, Math.round(parsed))) : fallback;
  };
  form.media_file.max_file_mb = clamp(form.media_file.max_file_mb, 100, 1, 100);
  form.media_file.image_max_edge = clamp(form.media_file.image_max_edge, 2000, 512, 4096);
  form.media_file.jpeg_quality = clamp(form.media_file.jpeg_quality, 85, 50, 95);
  form.media_file.max_output_tokens = clamp(form.media_file.max_output_tokens, 1024, 128, 4096);
  form.web_search.max_results = clamp(form.web_search.max_results, 8, 1, 20);
  if (!form.media_file.default_prompt.trim()) form.media_file.default_prompt = DEFAULT_MEDIA_PROMPT;
}

function showSaved(): void {
  statusMessage.value = "保存成功";
  window.clearTimeout(statusTimer);
  statusTimer = window.setTimeout(() => {
    statusMessage.value = "";
  }, 5000);
}

function saveSettings(): void {
  normalizeToolRoundLimit();
  normalizeMediaSettings();
  if (android) form.groups.command = false;
  if (form.web_search.provider === "deepseek") form.web_search.base_url = "";
  updateSettings({
    web_search: clone(form.web_search),
    media_file: clone(form.media_file),
    groups: { ...form.groups },
    access_mode: form.access_mode,
    max_tool_rounds: form.max_tool_rounds,
    tool_rag: form.tool_rag,
    tool_recommend_mode: form.tool_recommend_mode,
  });
  notify("工具配置已保存");
  showSaved();
}

function runTest(): void {
  saveSettings();
  void alertDialog("测试搜索", "需要后端服务，当前未接入");
}

async function restartAsAdmin(): Promise<void> {
  const approved = await confirmDialog(
    "以管理员身份重启？",
    "Windows 将显示一次正常的 UAC 确认。重启后，完全访问模式下执行的命令会继承管理员权限。是否继续？",
  );
  if (!approved) return;
  saveSettings();
  await alertDialog("管理员运行状态", "需要后端服务，当前未接入");
}
</script>

<style scoped>
.text-brand {
  color: var(--accent-color);
}
.border-brand,
.focus-within\:border-brand:focus-within,
.focus\:border-brand:focus {
  border-color: var(--accent-color);
}
.bg-brand {
  background-color: var(--accent-color);
}
.bg-brand\/10 {
  background-color: color-mix(in srgb, var(--accent-color) 10%, transparent);
}
.bg-brand\/15 {
  background-color: color-mix(in srgb, var(--accent-color) 15%, transparent);
}
.bg-brand\/30 {
  background-color: color-mix(in srgb, var(--accent-color) 30%, transparent);
}
.border-brand\/30 {
  border-color: color-mix(in srgb, var(--accent-color) 30%, transparent);
}
.border-brand\/60 {
  border-color: color-mix(in srgb, var(--accent-color) 60%, transparent);
}

.toggle-control {
  display: inline-flex;
  cursor: pointer;
  align-items: center;
  border: 0;
  background: transparent;
  padding: 0;
}
.toggle-track {
  position: relative;
  display: inline-block;
  width: 3.125rem;
  height: 1.625rem;
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
</style>
