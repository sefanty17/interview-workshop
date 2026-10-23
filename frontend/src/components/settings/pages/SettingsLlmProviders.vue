<template>
  <MenuPage>
    <MenuItem title="大模型管理" size="large">
      <template #header>
        <Cpu :size="20" />
      </template>

      <div class="flex h-[66dvh] min-h-[430px] w-full overflow-hidden">
        <!-- ========== LEFT: Provider List ========== -->
        <!-- 窄屏浏览编辑/测试面板时隐藏列表 -->
        <div
          v-show="!isNarrowScreen || !sidePanel"
          class="flex min-h-0 flex-col transition-all duration-300 ease-[cubic-bezier(0.18,0.89,0.32,1)]"
          :class="!isNarrowScreen && sidePanel ? 'w-[45%] border-r border-white/10 pr-4' : 'w-full'"
        >
          <div class="mb-4 flex shrink-0 items-center justify-end gap-2">
            <button
              class="flex items-center gap-1.5 rounded-lg border border-amber-500/30 bg-amber-500/20 px-4 py-2 text-sm font-medium text-amber-300 transition-colors hover:bg-amber-500/30"
              @click="restartApp"
            >
              <RotateCw :size="16" />
              重启软件
            </button>
            <button
              class="rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-[rgba(121,217,255,0.8)]"
              @click="startAdd"
            >
              + 添加模型
            </button>
          </div>

          <div v-if="providers.length === 0" class="py-8 text-center text-base text-white/50">
            暂无配置的模型，点击"添加模型"开始配置
          </div>
          <div v-else class="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto">
            <div
              v-for="p in providers"
              :key="p.id"
              class="flex cursor-pointer items-center gap-3 rounded-lg border border-white/10 bg-white/5 px-4 py-3.5 transition-colors hover:border-white/20"
              :class="{
                'border-[rgba(121,217,255,0.4)] bg-[rgba(121,217,255,0.05)]':
                  sidePanel && editing.id === p.id,
              }"
              @click="startEdit(p)"
            >
              <!-- Info -->
              <div class="min-w-0 flex-1">
                <div class="mb-1 flex items-center gap-2">
                  <span class="truncate text-[15px] font-semibold text-white">{{
                    p.label || "(未命名)"
                  }}</span>
                  <span
                    class="rounded bg-[rgba(121,217,255,0.2)] px-2 py-0.5 text-xs text-brand/90"
                    >{{ providerLabel(p.provider) }}</span
                  >
                </div>
                <div class="truncate text-sm text-white/40">{{ p.model || "未设置模型" }}</div>
                <!-- Role badges -->
                <div class="mt-1.5 flex flex-wrap gap-1.5">
                  <span
                    v-if="store.settings.llmChatProviderId === p.id"
                    class="rounded-full border border-green-500/30 bg-green-500/20 px-2 py-0.5 text-xs text-green-300"
                    >对话</span
                  >
                  <span
                    v-if="store.settings.llmTranslateProviderId === p.id"
                    class="rounded-full border border-blue-500/30 bg-blue-500/20 px-2 py-0.5 text-xs text-blue-300"
                    >翻译</span
                  >
                  <span
                    v-if="store.settings.llmGodAgentProviderId === p.id"
                    class="rounded-full border border-purple-500/30 bg-purple-500/20 px-2 py-0.5 text-xs text-purple-300"
                    >Agent</span
                  >
                  <span
                    v-if="store.settings.llmVisionProviderId === p.id"
                    class="rounded-full border border-orange-500/30 bg-orange-500/20 px-2 py-0.5 text-xs text-orange-300"
                    >视觉</span
                  >
                </div>
              </div>

              <!-- Actions -->
              <div class="flex shrink-0 gap-1" @click.stop>
                <button
                  class="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-white/70 transition-colors hover:bg-white/20 hover:text-white"
                  @click="startEdit(p)"
                >
                  编辑
                </button>
                <button
                  class="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-white/70 transition-colors hover:bg-blue-500/20 hover:text-blue-300"
                  @click="startTest(p)"
                >
                  测试
                </button>
                <button
                  class="rounded-lg bg-white/10 px-3 py-1.5 text-sm text-white/70 transition-colors hover:bg-red-500/20 hover:text-red-300"
                  @click="confirmDelete(p)"
                >
                  删除
                </button>
              </div>
            </div>
          </div>

          <!-- Role assignment -->
          <div class="mt-4 shrink-0 border-t border-white/10 pt-4">
            <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <div class="flex flex-col gap-1.5">
                <label class="text-xs font-medium text-white/60">对话模型</label>
                <div class="relative">
                  <select
                    :value="store.settings.llmChatProviderId ?? ''"
                    class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                    @change="onChatRoleChange(($event.target as HTMLSelectElement).value)"
                  >
                    <option value="" class="bg-slate-800 text-white">未选择</option>
                    <option
                      v-for="p in providers"
                      :key="p.id"
                      :value="p.id"
                      class="bg-slate-800 text-white"
                    >
                      {{ p.label || p.model || "(未命名)" }}
                    </option>
                  </select>
                  <ChevronDown
                    :size="16"
                    class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                  />
                </div>
              </div>
              <div class="flex flex-col gap-1.5">
                <label class="text-xs font-medium text-white/60">翻译模型</label>
                <div class="relative">
                  <select
                    :value="store.settings.llmTranslateProviderId ?? '__follow__'"
                    class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                    @change="onTranslateRoleChange(($event.target as HTMLSelectElement).value)"
                  >
                    <option value="__follow__" class="bg-slate-800 text-white">跟随对话模型</option>
                    <option
                      v-for="p in providers"
                      :key="p.id"
                      :value="p.id"
                      class="bg-slate-800 text-white"
                    >
                      {{ p.label || p.model || "(未命名)" }}
                    </option>
                  </select>
                  <ChevronDown
                    :size="16"
                    class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                  />
                </div>
              </div>
              <div class="flex flex-col gap-1.5">
                <label class="text-xs font-medium text-white/60">上帝Agent</label>
                <div class="relative">
                  <select
                    :value="store.settings.llmGodAgentProviderId ?? '__follow__'"
                    class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                    @change="onGodAgentRoleChange(($event.target as HTMLSelectElement).value)"
                  >
                    <option value="__follow__" class="bg-slate-800 text-white">跟随对话模型</option>
                    <option
                      v-for="p in providers"
                      :key="p.id"
                      :value="p.id"
                      class="bg-slate-800 text-white"
                    >
                      {{ p.label || p.model || "(未命名)" }}
                    </option>
                  </select>
                  <ChevronDown
                    :size="16"
                    class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                  />
                </div>
              </div>
              <div class="flex flex-col gap-1.5">
                <label class="text-xs font-medium text-white/60">视觉模型</label>
                <div class="relative">
                  <select
                    :value="store.settings.llmVisionProviderId ?? '__follow__'"
                    class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                    @change="onVisionRoleChange(($event.target as HTMLSelectElement).value)"
                  >
                    <option value="__follow__" class="bg-slate-800 text-white">跟随对话模型</option>
                    <option
                      v-for="p in providers"
                      :key="p.id"
                      :value="p.id"
                      class="bg-slate-800 text-white"
                    >
                      {{ p.label || p.model || "(未命名)" }}
                    </option>
                  </select>
                  <ChevronDown
                    :size="16"
                    class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                  />
                </div>
              </div>
            </div>
          </div>

          <p
            v-if="saveMessage"
            class="mt-3 shrink-0 text-xs"
            :class="saveError ? 'text-red-400' : 'text-green-400'"
          >
            {{ saveMessage }}
          </p>
        </div>

        <!-- ========== RIGHT: Slide-in Panel ========== -->
        <Transition
          enter-active-class="transition duration-[350ms] ease-[cubic-bezier(0.18,0.89,0.32,1)]"
          enter-from-class="translate-x-10 opacity-0"
          leave-active-class="transition duration-[250ms] ease-[cubic-bezier(0.6,-0.28,0.74,0.05)]"
          leave-to-class="translate-x-10 opacity-0"
        >
          <div
            v-if="sidePanel"
            class="flex min-h-0 flex-col"
            :class="isNarrowScreen ? 'w-full' : 'w-[55%] pl-4'"
          >
            <!-- Header: narrow shows back button, wide shows close button -->
            <div class="mb-4 flex shrink-0 items-center justify-between">
              <!-- 窄屏：返回按钮 + 标题 -->
              <template v-if="isNarrowScreen">
                <button
                  class="flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm text-white/70 transition-colors hover:bg-white/10 hover:text-white"
                  @click="closePanel"
                >
                  <ChevronLeft :size="16" />
                  返回模型列表
                </button>
              </template>
              <template v-else>
                <h3 class="text-base font-semibold text-white">
                  <template v-if="sidePanel === 'edit'">{{
                    editing.id ? "编辑模型" : "添加模型"
                  }}</template>
                  <template v-else>{{
                    `测试 ${testProvider?.label || testProvider?.model || ""}`
                  }}</template>
                </h3>
                <button
                  class="rounded-lg p-1 text-white/50 transition-colors hover:bg-white/10 hover:text-white"
                  @click="closePanel"
                >
                  <X :size="20" />
                </button>
              </template>
            </div>

            <!-- ===== EDIT FORM ===== -->
            <template v-if="sidePanel === 'edit'">
              <form
                @submit.prevent="saveCurrent"
                class="flex flex-1 flex-col gap-4 overflow-y-auto pr-1"
              >
                <!-- Presets -->
                <div class="flex flex-col gap-2">
                  <label class="text-xs font-medium text-white/60">预设（快速配置）</label>
                  <div class="flex flex-wrap gap-2">
                    <button
                      v-for="preset in presets"
                      :key="preset.key"
                      type="button"
                      class="rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors"
                      :class="
                        isPresetActive(preset)
                          ? 'border-[rgba(121,217,255,0.4)] bg-[rgba(121,217,255,0.2)] text-brand'
                          : 'border-white/15 bg-white/5 text-white/60 hover:border-white/25 hover:bg-white/10 hover:text-white/80'
                      "
                      @click="applyPreset(preset)"
                    >
                      {{ preset.label }}
                    </button>
                  </div>
                </div>

                <!-- Label -->
                <div class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">名称</label>
                  <input
                    v-model="editing.label"
                    type="text"
                    placeholder="例如: DeepSeek V3"
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20 focus:border-[rgba(121,217,255,0.65)]"
                  />
                </div>

                <!-- Provider type -->
                <div class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">提供商类型</label>
                  <div class="relative">
                    <select
                      v-model="editing.provider"
                      class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                      style="color-scheme: dark"
                      @change="onProviderChange"
                    >
                      <option
                        v-for="opt in PROVIDER_TYPES"
                        :key="opt.value"
                        :value="opt.value"
                        class="bg-slate-800 text-white"
                      >
                        {{ opt.label }}
                      </option>
                    </select>
                    <ChevronDown
                      :size="16"
                      class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                    />
                  </div>
                </div>

                <!-- Model -->
                <div v-if="!isOAuthProvider" class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">模型名称</label>
                  <input
                    v-model="editing.model"
                    type="text"
                    :placeholder="
                      editing.provider === 'lmstudio'
                        ? '如: llama-3.2-3b-instruct'
                        : '如: gpt-4o'
                    "
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20 focus:border-[rgba(121,217,255,0.65)]"
                  />
                </div>

                <!-- Kimi Code / Codex model discovery -->
                <div v-else class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">模型名称</label>
                  <div class="flex gap-2">
                    <input
                      v-model="editing.model"
                      type="text"
                      :placeholder="
                        editing.provider === 'codex' ? 'gpt-5.6-sol' : 'kimi-for-coding'
                      "
                      class="min-w-0 flex-1 rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20 focus:border-[rgba(121,217,255,0.65)]"
                    />
                    <button
                      type="button"
                      class="shrink-0 rounded-lg bg-brand/80 px-3 py-2 text-sm text-white transition-colors hover:bg-brand disabled:cursor-not-allowed disabled:opacity-50"
                      :disabled="loadingModels"
                      @click="fetchProviderModels"
                    >
                      {{ loadingModels ? "获取中..." : "自动获取" }}
                    </button>
                  </div>
                  <p
                    v-if="modelsMessage"
                    class="text-xs"
                    :class="modelsError ? 'text-red-400' : 'text-green-400'"
                  >
                    {{ modelsMessage }}
                  </p>
                </div>

                <!-- Reasoning effort（按模型能力显示） -->
                <div v-if="showReasoningEffort" class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">推理深度（部分模型支持）</label>
                  <div class="relative">
                    <select
                      v-model="editing.reasoning_effort"
                      class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                      style="color-scheme: dark"
                    >
                      <option :value="null" class="bg-slate-800 text-white">默认（跟随模型）</option>
                      <option
                        v-for="effort in reasoningEffortOptions"
                        :key="effort"
                        :value="effort"
                        class="bg-slate-800 text-white"
                      >
                        {{ effortLabel(effort) }}
                      </option>
                    </select>
                    <ChevronDown
                      :size="16"
                      class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                    />
                  </div>
                </div>

                <!-- API Key（Codex 走 OAuth 订阅登录，无需 API Key） -->
                <div v-if="editing.provider !== 'codex'" class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">API 密钥</label>
                  <input
                    v-model="editing.api_key"
                    type="password"
                    placeholder="sk-..."
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20 focus:border-[rgba(121,217,255,0.65)]"
                  />
                </div>

                <!-- Codex 登录区：设备码授权 ChatGPT 订阅 -->
                <div
                  v-else
                  class="flex flex-col gap-2 rounded-lg border border-white/10 bg-white/5 p-3"
                >
                  <div class="flex items-center justify-between">
                    <span class="text-xs font-medium text-white/60">ChatGPT 订阅账号</span>
                    <span v-if="codexLoggedIn" class="text-xs text-green-400">● 已登录</span>
                    <span v-else class="text-xs text-white/40">未登录</span>
                  </div>

                  <p
                    v-if="codexLoginMessage"
                    class="text-xs"
                    :class="codexLoginError ? 'text-red-400' : 'text-green-400'"
                  >
                    {{ codexLoginMessage }}
                  </p>

                  <div class="flex gap-2">
                    <button
                      v-if="!codexLoggedIn"
                      type="button"
                      class="rounded-lg bg-brand/80 px-3 py-1.5 text-xs text-white transition-colors hover:bg-brand"
                      @click="startCodexLogin"
                    >
                      登录 ChatGPT 订阅
                    </button>
                    <button
                      v-else
                      type="button"
                      class="rounded-lg bg-white/10 px-3 py-1.5 text-xs text-white/70 transition-colors hover:bg-white/20"
                      @click="doCodexLogout"
                    >
                      退出登录
                    </button>
                  </div>
                </div>

                <!-- Base URL -->
                <div v-if="!isOAuthProvider" class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">API 地址</label>
                  <input
                    v-model="editing.base_url"
                    type="text"
                    placeholder="https://api.deepseek.com"
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20 focus:border-[rgba(121,217,255,0.65)]"
                  />
                </div>

                <!-- Codex Fast Mode（1.5× 速度，额度消耗更快） -->
                <div
                  v-if="editing.provider === 'codex'"
                  class="flex items-center justify-between rounded-lg border border-white/10 bg-white/5 px-3 py-2"
                >
                  <div class="flex flex-col gap-0.5">
                    <span class="text-xs font-medium text-white/60">Fast Mode（1.5× 速度）</span>
                    <span class="text-[11px] text-white/35">响应更快，但额度消耗也更快</span>
                  </div>
                  <button
                    type="button"
                    class="relative h-6 w-11 shrink-0 rounded-full transition-colors duration-200"
                    :class="editing.fast_mode ? 'bg-brand' : 'bg-white/15'"
                    @click="editing.fast_mode = !editing.fast_mode"
                  >
                    <span
                      class="absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all duration-200"
                      :class="editing.fast_mode ? 'left-[22px]' : 'left-0.5'"
                    ></span>
                  </button>
                </div>

                <!-- Temperature -->
                <div class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">Temperature（留空使用默认）</label>
                  <input
                    v-model.number="editing.temperature"
                    type="number"
                    step="0.1"
                    min="0"
                    max="2"
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                  />
                </div>

                <!-- Top P -->
                <div class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60">Top P（留空使用默认）</label>
                  <input
                    v-model.number="editing.top_p"
                    type="number"
                    step="0.05"
                    min="0"
                    max="1"
                    class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                    style="color-scheme: dark"
                  />
                </div>

                <!-- Enable thinking -->
                <label class="flex cursor-pointer items-center gap-3">
                  <span class="text-xs font-medium text-white/60">启用思考链（部分模型支持）</span>
                  <div class="relative">
                    <input v-model="editing.enable_thinking" type="checkbox" class="peer sr-only" />
                    <div
                      class="h-5 w-9 rounded-full border border-white/20 bg-white/10 transition-colors peer-checked:border-brand peer-checked:bg-brand"
                    ></div>
                    <div
                      class="absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform peer-checked:translate-x-4"
                    ></div>
                  </div>
                </label>

                <!-- 思考等级：始终显示，仅在开启思考模式时可调整（OAuth 提供商走上方按模型能力渲染的下拉） -->
                <div v-if="!isOAuthProvider" class="flex flex-col gap-1">
                  <label class="text-xs font-medium text-white/60"
                    >思考等级（仅开启思考模式时生效）</label
                  >
                  <div class="relative">
                    <select
                      v-model="editing.reasoning_effort"
                      :disabled="!editing.enable_thinking"
                      class="w-full cursor-pointer appearance-none rounded-lg border border-white/20 bg-white/10 py-2 pr-8 pl-3 text-sm text-white transition-colors outline-none disabled:cursor-not-allowed disabled:opacity-50 focus:border-[rgba(121,217,255,0.65)]"
                      style="color-scheme: dark"
                    >
                      <option :value="null" class="bg-slate-800 text-white">默认（跟随模型）</option>
                      <option
                        v-for="effort in thinkingLevelOptions"
                        :key="effort"
                        :value="effort"
                        class="bg-slate-800 text-white"
                      >
                        {{ effortLabel(effort) }}
                      </option>
                    </select>
                    <ChevronDown
                      :size="16"
                      class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-white/40"
                    />
                  </div>
                </div>

                <!-- 支持识图（原生多模态）：勾选后用户发图/截图直接走对话模型，不再旁白转述 -->
                <div
                  class="flex items-center justify-between rounded-lg border border-white/10 bg-white/5 px-3 py-2"
                >
                  <div class="flex flex-col gap-0.5">
                    <span class="text-xs font-medium text-white/60">支持识图（原生多模态）</span>
                    <span class="text-[11px] text-white/35"
                      >勾选后发图/截图可直接走该对话模型识图，不再旁白转述</span
                    >
                  </div>
                  <button
                    type="button"
                    class="relative h-6 w-11 shrink-0 rounded-full transition-colors duration-200"
                    :class="editing.support_vision ? 'bg-brand' : 'bg-white/15'"
                    @click="editing.support_vision = !editing.support_vision"
                  >
                    <span
                      class="absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all duration-200"
                      :class="editing.support_vision ? 'left-[22px]' : 'left-0.5'"
                    ></span>
                  </button>
                </div>

                <!-- 原生识图压缩设置：仅启用识图时展示，默认不压缩（原图直发） -->
                <div
                  v-if="editing.support_vision"
                  class="flex flex-col gap-3 rounded-lg border border-white/10 bg-white/5 p-3"
                >
                  <div class="flex items-center justify-between">
                    <div class="flex flex-col gap-0.5">
                      <span class="text-xs font-medium text-white/60">识图时压缩图片</span>
                      <span class="text-[11px] text-white/35"
                        >默认关闭，图片原图直发。开启后可控制边长与质量，节省 token 与上下文缓存</span
                      >
                    </div>
                    <button
                      type="button"
                      class="relative h-6 w-11 shrink-0 rounded-full transition-colors duration-200"
                      :class="editing.vision_compress ? 'bg-brand' : 'bg-white/15'"
                      @click="editing.vision_compress = !editing.vision_compress"
                    >
                      <span
                        class="absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all duration-200"
                        :class="editing.vision_compress ? 'left-[22px]' : 'left-0.5'"
                      ></span>
                    </button>
                  </div>

                  <template v-if="editing.vision_compress">
                    <!-- 最大边长（像素） -->
                    <div class="flex flex-col gap-1">
                      <label class="text-xs font-medium text-white/60">最大边长（像素）</label>
                      <input
                        v-model.number="editing.vision_max_edge"
                        type="number"
                        step="64"
                        min="64"
                        max="8192"
                        class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                        style="color-scheme: dark"
                      />
                      <span class="text-[11px] text-white/30"
                        >超宽图片等比缩放到此边长（64–8192）</span
                      >
                    </div>

                    <!-- 编码质量（0-100） -->
                    <div class="flex flex-col gap-1">
                      <label class="text-xs font-medium text-white/60">编码质量（0-100）</label>
                      <input
                        v-model.number="editing.vision_jpeg_quality"
                        type="number"
                        min="1"
                        max="100"
                        class="rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none focus:border-[rgba(121,217,255,0.65)]"
                        style="color-scheme: dark"
                      />
                      <span class="text-[11px] text-white/30"
                        >数值越高越清晰，体积（token 占用）也越大</span
                      >
                    </div>
                  </template>
                </div>

                <!-- Action buttons -->
                <div class="flex gap-3 pt-2">
                  <button
                    type="submit"
                    class="rounded-lg bg-brand px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-[rgba(121,217,255,0.8)]"
                  >
                    保存
                  </button>
                  <button
                    type="button"
                    class="rounded-lg bg-white/10 px-5 py-2 text-sm text-white/70 transition-colors hover:bg-white/20"
                    @click="closePanel"
                  >
                    取消
                  </button>
                </div>

                <p
                  v-if="saveMessage"
                  class="text-xs"
                  :class="saveError ? 'text-red-400' : 'text-green-400'"
                >
                  {{ saveMessage }}
                </p>
              </form>
            </template>

            <!-- ===== TEST VIEW ===== -->
            <template v-if="sidePanel === 'test'">
              <div class="flex min-h-0 flex-1 flex-col gap-4">
                <div class="flex gap-2">
                  <input
                    v-model="testMessage"
                    type="text"
                    placeholder="输入测试消息..."
                    class="focus:border-[rgba(121,217,255,0.65)] flex-1 rounded-lg border border-white/20 bg-white/10 px-3 py-2 text-sm text-white transition-colors outline-none placeholder:text-white/20"
                    @keydown.enter="doTest"
                  />
                  <button
                    class="rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-[rgba(121,217,255,0.8)] disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="testing || !testMessage.trim()"
                    @click="doTest"
                  >
                    {{ testing ? "测试中..." : "发送" }}
                  </button>
                </div>

                <div
                  class="min-h-0 flex-1 overflow-y-auto rounded-lg border border-white/10 bg-white/5 p-4"
                >
                  <div v-if="testing" class="flex items-center gap-2 text-sm text-white/40">
                    <div
                      class="h-4 w-4 animate-spin rounded-full border-2 border-white/20 border-t-brand"
                    ></div>
                    等待响应...
                  </div>
                  <div v-else-if="testError" class="text-sm whitespace-pre-wrap text-red-400">
                    {{ testError }}
                  </div>
                  <div
                    v-else-if="testResponse"
                    class="text-sm leading-relaxed whitespace-pre-wrap text-white/80"
                  >
                    {{ testResponse }}
                  </div>
                  <div v-else class="text-sm text-white/30">输入消息并点击发送，测试模型响应</div>
                </div>
              </div>
            </template>
          </div>
        </Transition>
      </div>
    </MenuItem>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ChevronDown, ChevronLeft, Cpu, RotateCw, X } from "@lucide/vue";
import { MenuItem, MenuPage } from "../../ui";
import { store, notify, updateSettings } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import {
  llmPresets as presets,
  type LlmPreset,
  type LlmProviderConfig,
} from "../../../data/mock";

/** 未接入后端时的统一提示语 */
const NEED_BACKEND = "需要后端服务，当前未接入";

const providers = computed<LlmProviderConfig[]>(() => store.settings.llmProviders ?? []);

const sidePanel = ref<"edit" | "test" | null>(null);
const editing = reactive<LlmProviderConfig>(emptyProvider());
const saveMessage = ref("");
const saveError = ref(false);
const lmstudioAutoFilled = ref(false);
const kimicodeAutoFilled = ref(false);
const loadingModels = ref(false);
const modelsMessage = ref("");
const modelsError = ref(false);

// ---- Codex（ChatGPT 订阅）登录状态：本项目未接入后端，登录区始终显示「未登录」 ----
const codexLoggedIn = ref(false);
const codexLoginMessage = ref("");
const codexLoginError = ref(false);

// Test state
const testProvider = ref<LlmProviderConfig | null>(null);
const testMessage = ref("");
const testResponse = ref("");
const testError = ref("");
const testing = ref(false);

const PROVIDER_TYPES = [
  { value: "deepseek", label: "DeepSeek" },
  { value: "openai", label: "OpenAI 兼容" },
  { value: "lmstudio", label: "LM Studio（本地）" },
  { value: "gemini", label: "Gemini" },
  { value: "kimicode", label: "Kimi Code (kimi-for-coding)" },
  { value: "codex", label: "OpenAI Codex（ChatGPT 订阅）" },
] as const;

function emptyProvider(): LlmProviderConfig {
  return {
    id: "",
    label: "",
    provider: "deepseek",
    model: "deepseek-chat",
    api_key: "",
    base_url: "https://api.deepseek.com",
    temperature: null,
    top_p: null,
    enable_thinking: false,
    reasoning_effort: null,
    fast_mode: false,
    support_vision: false,
    vision_compress: false,
    vision_max_edge: 2048,
    vision_jpeg_quality: 85,
  };
}

/** 服务商类型下拉里没有的取值（如历史数据）原样显示，已知值显示中文名 */
function providerLabel(provider: string): string {
  return PROVIDER_TYPES.find((t) => t.value === provider)?.label ?? provider;
}

// ---- 窄屏：原版 uiStore.isNarrowScreen，等价实现（视口 < 768px 走单栏） ----
const isNarrowScreen = ref(false);
function syncViewport() {
  isNarrowScreen.value = window.innerWidth < 768;
}
onMounted(() => {
  syncViewport();
  window.addEventListener("resize", syncViewport);
});
onUnmounted(() => window.removeEventListener("resize", syncViewport));

function closePanel() {
  resetModelList();
  sidePanel.value = null;
  saveMessage.value = "";
}

// OAuth 订阅类提供商（Kimi Code / OpenAI Codex）：免填 base_url，模型走自动发现
const isOAuthProvider = computed(
  () => editing.provider === "kimicode" || editing.provider === "codex",
);

// 推理深度档位完全由模型声明的 think_efforts.valid_efforts 驱动（与 kimi-code 官方一致）：
// 列表非空 → 显示选择器并按其渲染档位；为空（如 K2.7 思考常开、不可调档）→ 不显示。
// 本项目没有在线模型目录，Kimi Code / Codex 的模型能力列表为空，故不显示该选择器。
const availableModels = ref<{ id: string; think_efforts: { valid_efforts: string[] } | null }[]>([]);
const reasoningEffortOptions = computed<string[]>(() => {
  if (!isOAuthProvider.value) return [];
  const info = availableModels.value.find((m) => m.id === editing.model);
  return info?.think_efforts?.valid_efforts ?? [];
});
const showReasoningEffort = computed(() => reasoningEffortOptions.value.length > 0);

// 非 OAuth 提供商的思考等级固定档位（仅开启思考模式时生效）
const thinkingLevelOptions = ["low", "medium", "high", "xhigh", "max"];

const EFFORT_LABELS: Record<string, string> = {
  off: "Off（关闭）",
  minimal: "Minimal（最低）",
  low: "Low（低）",
  medium: "Medium（中）",
  high: "High（高）",
  xhigh: "Xhigh（超高）",
  max: "Max（最强）",
};

function effortLabel(effort: string): string {
  return EFFORT_LABELS[effort] ?? effort;
}

// 切换到不可调档的模型/提供商时清掉已选档位，避免残留值被静默发往其他模型
watch([() => editing.provider, () => editing.model], () => {
  const options = isOAuthProvider.value ? reasoningEffortOptions.value : thinkingLevelOptions;
  if (
    options.length === 0 ||
    (editing.reasoning_effort && !options.includes(editing.reasoning_effort))
  ) {
    editing.reasoning_effort = null;
  }
});

function resetModelList() {
  loadingModels.value = false;
  availableModels.value = [];
  modelsMessage.value = "";
  modelsError.value = false;
}

function applyPreset(preset: LlmPreset) {
  editing.label = preset.label;
  editing.provider = preset.provider;
  editing.model = preset.model;
  editing.base_url = preset.base_url;
  // 重置自动填充标记
  lmstudioAutoFilled.value = false;
  kimicodeAutoFilled.value = false;
  resetModelList();
}

function isPresetActive(preset: LlmPreset): boolean {
  return (
    editing.label === preset.label &&
    editing.provider === preset.provider &&
    editing.model === preset.model
  );
}

// LM Studio 兼容：本质是 OpenAI 协议，这里只帮用户预填默认地址和假 key
function onProviderChange() {
  resetModelList();
  if (editing.provider === "deepseek") {
    editing.model = "deepseek-chat";
    editing.base_url = "https://api.deepseek.com";
  } else if (editing.provider === "lmstudio") {
    editing.base_url = "http://localhost:1234/v1";
    editing.api_key = "sk-lingchat70";
    lmstudioAutoFilled.value = true;
  } else if (editing.provider === "kimicode") {
    editing.model = "kimi-for-coding";
    editing.base_url = "https://api.kimi.com/coding";
    kimicodeAutoFilled.value = true;
  } else if (editing.provider === "codex") {
    // Codex：OAuth 订阅，无需 key/base_url；模型从登录账号在线发现
    editing.model = "gpt-5.6-sol";
    editing.base_url = "";
    editing.api_key = "";
  } else {
    // 仅清除由 LM Studio / Kimi Code 自动填入的默认值，不误伤用户手写的相同值
    if (lmstudioAutoFilled.value) {
      if (editing.base_url === "http://localhost:1234/v1") {
        editing.base_url = "";
      }
      if (editing.api_key === "sk-lingchat70") {
        editing.api_key = "";
      }
      lmstudioAutoFilled.value = false;
    }
    if (kimicodeAutoFilled.value) {
      if (editing.model === "kimi-for-coding") {
        editing.model = "";
      }
      if (editing.base_url === "https://api.kimi.com/coding") {
        editing.base_url = "";
      }
      kimicodeAutoFilled.value = false;
    }
  }
}

function startAdd() {
  Object.assign(editing, emptyProvider());
  resetModelList();
  sidePanel.value = "edit";
  saveMessage.value = "";
}

function startEdit(p: LlmProviderConfig) {
  Object.assign(editing, { ...p });
  resetModelList();
  sidePanel.value = "edit";
  saveMessage.value = "";
}

async function confirmDelete(p: LlmProviderConfig) {
  const name = p.label || p.model || "(未命名)";
  const ok = await confirmDialog("删除模型", `确定删除模型 "${name}"？`);
  if (!ok) return;
  const next = providers.value.filter((item) => item.id !== p.id);
  // 角色指向被删的服务商时一并清空，避免指向已不存在的配置
  updateSettings({
    llmProviders: next,
    llmChatProviderId:
      store.settings.llmChatProviderId === p.id ? null : store.settings.llmChatProviderId,
    llmTranslateProviderId:
      store.settings.llmTranslateProviderId === p.id ? null : store.settings.llmTranslateProviderId,
    llmGodAgentProviderId:
      store.settings.llmGodAgentProviderId === p.id ? null : store.settings.llmGodAgentProviderId,
    llmVisionProviderId:
      store.settings.llmVisionProviderId === p.id ? null : store.settings.llmVisionProviderId,
  });
  saveMessage.value = "已删除";
  saveError.value = false;
  notify(`已删除模型「${name}」`);
  if (editing.id === p.id) closePanel();
}

function saveCurrent() {
  saveMessage.value = "";
  saveError.value = false;
  if (!editing.label.trim() && !editing.model.trim()) {
    saveMessage.value = "保存失败: 请至少填写名称或模型名称";
    saveError.value = true;
    return;
  }
  const snapshot: LlmProviderConfig = { ...editing };
  if (!snapshot.id) {
    snapshot.id = `llm-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    editing.id = snapshot.id;
    // 首个模型自动担任对话模型，避免配好却没人用的空档
    if (!store.settings.llmChatProviderId) {
      updateSettings({ llmChatProviderId: snapshot.id });
    }
    writeProviders([...providers.value, snapshot]);
    notify(`已添加模型「${snapshot.label || snapshot.model}」`);
  } else {
    writeProviders(providers.value.map((item) => (item.id === snapshot.id ? snapshot : item)));
    notify(`已保存模型「${snapshot.label || snapshot.model}」`);
  }
  saveMessage.value = "保存成功！";
}

function writeProviders(next: LlmProviderConfig[]) {
  updateSettings({ llmProviders: next });
}

function onChatRoleChange(value: string) {
  updateSettings({ llmChatProviderId: value || null });
  saveMessage.value = "对话模型已切换并生效！";
  saveError.value = false;
  notify("对话模型已切换");
}

function onTranslateRoleChange(value: string) {
  updateSettings({ llmTranslateProviderId: value === "__follow__" ? null : value });
  saveMessage.value = "翻译模型已切换并生效！";
  saveError.value = false;
  notify("翻译模型已切换");
}

function onGodAgentRoleChange(value: string) {
  updateSettings({ llmGodAgentProviderId: value === "__follow__" ? null : value });
  saveMessage.value = "上帝Agent已切换并生效！";
  saveError.value = false;
  notify("上帝Agent已切换");
}

function onVisionRoleChange(value: string) {
  updateSettings({ llmVisionProviderId: value === "__follow__" ? null : value });
  saveError.value = false;
  notify("视觉模型已切换");
}

/** 在线拉取服务商模型目录：需要后端网关，当前未接入 */
function fetchProviderModels() {
  modelsError.value = true;
  modelsMessage.value = `获取失败: ${NEED_BACKEND}`;
  void alertDialog("自动获取模型", NEED_BACKEND);
}

function startTest(p: LlmProviderConfig) {
  testProvider.value = p;
  testMessage.value = "";
  testResponse.value = "";
  testError.value = "";
  sidePanel.value = "test";
}

/** 连接测试：先走一遍等待态，再由 alertDialog 说明需要后端服务 */
async function doTest() {
  if (!testProvider.value || !testMessage.value.trim()) return;
  testing.value = true;
  testResponse.value = "";
  testError.value = "";
  await new Promise((resolve) => window.setTimeout(resolve, 400));
  testing.value = false;
  testError.value = NEED_BACKEND;
  void alertDialog("测试连接", NEED_BACKEND);
}

/** 原版是 Tauri relaunch，Web 端等价为重新加载应用 */
function restartApp() {
  window.location.reload();
}

// ---- Codex 设备码登录流程：依赖后端 OAuth 服务，当前未接入 ----
function startCodexLogin() {
  codexLoginError.value = true;
  codexLoginMessage.value = `登录失败: ${NEED_BACKEND}`;
  void alertDialog("登录 ChatGPT 订阅", NEED_BACKEND);
}

function doCodexLogout() {
  codexLoggedIn.value = false;
  codexLoginMessage.value = "";
  void alertDialog("退出登录", NEED_BACKEND);
}
</script>
