<template>
  <div class="flex h-full min-h-0 flex-col md:grid md:grid-cols-[min(30%,280px)_1fr]">
    <!-- 导航菜单：宽屏始终可见；窄屏仅在浏览菜单层级时可见 -->
    <nav
      ref="navContainerRef"
      v-show="!isNarrow || narrowViewLevel === 'menu'"
      class="relative flex flex-col justify-start gap-6.25 overflow-y-auto border-b border-brand transition-all duration-300 ease-[cubic-bezier(0.18,0.89,0.32,1.00)] md:border-r md:border-b-0"
      @click="removeMoreMenu"
    >
      <!-- 滑动指示器 -->
      <div
        ref="indicatorRef"
        class="absolute left-2 z-0 w-[calc(100%-40px)] rounded-lg bg-brand transition-all duration-300 ease-[cubic-bezier(0.18,0.89,0.32,1.00)]"
      ></div>

      <div
        class="mt-2 flex items-center gap-1 px-5 text-sm"
        style="color: white; -webkit-text-stroke: 1px black; paint-order: stroke fill"
      >
        💡 这里的设置重启软件生效哦！
      </div>

      <div
        v-for="(category, categoryIndex) in configData"
        :key="category.name"
        class="flex w-full flex-col gap-1"
      >
        <span
          class="mb-1 block rounded-lg border border-white/10 bg-white/10 px-3.75 py-2.5 text-base font-bold text-brand shadow-[0_8px_32px_rgba(0,0,0,0.1),inset_0_1px_1px_rgba(255,255,255,0.1)]"
        >
          {{ category.name }}
        </span>
        <a
          v-for="(group, groupIndex) in category.groups"
          :key="group.name"
          href="#"
          class="adv-nav-link relative z-10 block rounded-lg px-5 py-3 text-white no-underline transition-colors duration-200 hover:bg-gray-200 hover:text-black"
          :class="{ active: selection.category === categoryIndex && selection.group === groupIndex }"
          @click.prevent="selectGroup(categoryIndex, groupIndex)"
        >
          {{ group.name }}
        </a>
      </div>
    </nav>

    <!-- 设置内容区域 -->
    <main
      v-show="!isNarrow || narrowViewLevel === 'content'"
      class="relative flex h-full min-h-0 justify-center overflow-auto px-10 py-10 md:px-10 md:py-0"
    >
      <!-- 窄屏返回按钮 -->
      <button
        v-if="isNarrow"
        class="absolute top-0 left-4 flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm text-white/70 transition-colors hover:bg-white/10 hover:text-white"
        @click="narrowViewLevel = 'menu'"
      >
        <ArrowLeft :size="16" />
        返回设置列表
      </button>

      <div v-if="activeGroup" class="w-full">
        <div class="overflow-auto pt-2.5">
          <header class="mb-6 border-b border-brand pb-4">
            <h2 class="m-0 text-2xl font-semibold text-brand">
              {{ activeGroup.name }}
            </h2>
            <p class="mt-2 text-base">{{ activeGroup.desc }}</p>
          </header>

          <form @submit.prevent="saveSettings">
            <div v-for="field in activeGroup.fields" :key="field.key" class="mb-6">
              <label
                class="inline-flex cursor-pointer items-center font-medium text-brand"
                :for="field.key"
              >
                {{ field.label }}
              </label>
              <p class="mt-1 mb-2 text-sm text-gray-300">{{ field.desc }}</p>

              <!-- 布尔值 -->
              <div v-if="field.type === 'switch'" class="flex items-baseline px-1 py-2.5">
                <button
                  :id="field.key"
                  type="button"
                  role="switch"
                  :aria-checked="field.value === true"
                  :aria-label="field.label"
                  class="relative inline-block h-6.5 w-12.5 shrink-0 cursor-pointer rounded-[13px] border-none transition-all duration-300 ease-in-out"
                  :class="
                    field.value === true
                      ? 'bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                      : 'bg-white/20'
                  "
                  @click="field.value = field.value !== true"
                >
                  <span
                    class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
                    :class="
                      field.value === true
                        ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6] shadow-[0_3px_8px_rgba(121,217,255,0.4),0_1px_3px_rgba(0,0,0,0.2)]'
                        : 'left-1 bg-linear-to-br from-white to-[#f0f0f0] shadow-[0_2px_6px_rgba(0,0,0,0.3),0_1px_2px_rgba(0,0,0,0.1)]'
                    "
                  ></span>
                </button>
                <p class="ml-3 text-sm text-gray-300">{{ field.key }}</p>
              </div>

              <!-- 文本域 -->
              <textarea
                v-else-if="field.type === 'textarea'"
                :id="field.key"
                :value="String(field.value)"
                rows="6"
                class="w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                @input="field.value = ($event.target as HTMLTextAreaElement).value"
              ></textarea>

              <!-- 数字 -->
              <input
                v-else-if="field.type === 'number'"
                :id="field.key"
                type="number"
                :value="Number(field.value)"
                :min="field.min"
                :max="field.max"
                :step="field.step"
                class="w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                @input="onNumberInput(field, $event)"
              />

              <!-- 下拉 -->
              <select
                v-else-if="field.type === 'select'"
                :id="field.key"
                :value="String(field.value)"
                class="w-full cursor-pointer rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                style="color-scheme: dark"
                @change="field.value = ($event.target as HTMLSelectElement).value"
              >
                <option
                  v-for="opt in field.options ?? []"
                  :key="opt"
                  :value="opt"
                  class="bg-slate-800 text-white"
                >
                  {{ opt }}
                </option>
              </select>

              <!-- 文本 -->
              <input
                v-else
                :id="field.key"
                type="text"
                :value="String(field.value)"
                class="w-full rounded-lg border border-white/10 bg-white/10 px-3 py-2.5 text-sm text-white transition-all duration-200 focus:border-brand focus:ring-2 focus:ring-[rgba(121,217,255,0.25)] focus:outline-none"
                @input="field.value = ($event.target as HTMLInputElement).value"
              />

              <p v-if="field.value === ''" class="mt-1 text-xs text-white/40">留空则使用默认值</p>
            </div>
          </form>

          <!-- TTS 连接控制 -->
          <section
            v-if="activeCategory?.name === 'TTS 配置'"
            class="mb-6 rounded-xl border border-white/10 bg-black/15 p-4"
          >
            <h3 class="mb-1 text-base font-semibold text-white">TTS 连接控制</h3>
            <p class="mb-3 text-sm leading-6 text-white/65">
              TTS 服务重启后，可在这里解除离线状态并让下一次语音立即重新连接。模型、语言或接口参数错误导致的 HTTP 400 仍需修正对应配置。
            </p>
            <Button type="big" :disabled="isReconnectingTts" @click="forceReconnectTts">
              <RefreshCw :size="18" :class="{ 'animate-spin': isReconnectingTts }" />
              {{ isReconnectingTts ? "正在重新连接…" : "强制重新连接 TTS" }}
            </Button>
            <p v-if="reconnectStatus.message" class="mt-2 text-sm" :class="reconnectStatus.colorClass">
              {{ reconnectStatus.message }}
            </p>
          </section>

          <!-- 面试印象动画开关：前端即时生效，不入配置树 -->
          <section
            v-if="activeCategory?.name === '面试印象' && activeGroup.name === '通用'"
            class="mb-6 rounded-xl border border-white/10 bg-black/15 p-4"
          >
            <h3 class="mb-1 text-base font-semibold text-white">面试印象动画</h3>
            <p class="mb-3 text-sm leading-6 text-white/65">
              面试印象爱心的跳动与红色液体波浪动画开关，即时生效；关闭心跳后顶栏爱心静止，面试印象面板内仍会跳动
            </p>
            <button
              type="button"
              role="switch"
              :aria-checked="affectionHeartbeat"
              class="inline-flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white"
              @click="toggleHeartbeat"
            >
              <span
                class="relative inline-block h-6.5 w-12.5 shrink-0 rounded-[13px] transition-all duration-300 ease-in-out"
                :class="
                  affectionHeartbeat
                    ? 'bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                    : 'bg-white/20'
                "
              >
                <span
                  class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
                  :class="
                    affectionHeartbeat
                      ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6]'
                      : 'left-1 bg-linear-to-br from-white to-[#f0f0f0]'
                  "
                ></span>
              </span>
              启用心跳动画
            </button>
            <button
              type="button"
              role="switch"
              :aria-checked="affectionWave"
              class="mt-2 flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white"
              @click="toggleWave"
            >
              <span
                class="relative inline-block h-6.5 w-12.5 shrink-0 rounded-[13px] transition-all duration-300 ease-in-out"
                :class="
                  affectionWave
                    ? 'bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                    : 'bg-white/20'
                "
              >
                <span
                  class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
                  :class="
                    affectionWave
                      ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6]'
                      : 'left-1 bg-linear-to-br from-white to-[#f0f0f0]'
                  "
                ></span>
              </span>
              启用波浪动画
            </button>
          </section>

          <!-- 保存操作区域 -->
          <div
            class="inline-flex min-w-30 cursor-pointer flex-col gap-2 rounded-lg border-none bg-brand px-5 py-2.5 text-sm font-medium text-white transition-all duration-200 hover:brightness-110"
            @click="saveSettings"
          >
            <button
              type="button"
              class="m-0 h-full w-full cursor-pointer border-none bg-transparent p-0 text-white"
            >
              保存
            </button>
          </div>
          <p v-if="saveStatus.message" class="mt-2 text-sm" :class="saveStatus.colorClass">
            {{ saveStatus.message }}
          </p>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ArrowLeft, RefreshCw } from "@lucide/vue";
import { Button } from "../../base";
import { notify } from "../../../store/appStore";

type ControlType = "switch" | "text" | "number" | "select" | "textarea";

interface AdvanceField {
  key: string;
  label: string;
  desc: string;
  type: ControlType;
  value: string | number | boolean;
  options?: string[];
  min?: number;
  max?: number;
  step?: number;
}

interface AdvanceGroup {
  name: string;
  desc: string;
  fields: AdvanceField[];
}

interface AdvanceCategory {
  name: string;
  groups: AdvanceGroup[];
}

/** 配置本地持久化：原版走后端配置树，这里等价落到 localStorage */
const STORE_KEY = "interview-advance-other";
/** 面试印象总开关，与高级设置首页的卡片共用同一项 */
const MASTER_KEY = "interview-affection-master";
const HEARTBEAT_KEY = "interview-affection-heartbeat";
const WAVE_KEY = "interview-affection-wave";

const configData = ref<AdvanceCategory[]>([
  {
    name: "LLM 配置",
    groups: [
      {
        name: "高级选项",
        desc: "调优 AI 对话行为的高级参数",
        fields: [
          {
            key: "llm.timeout_secs",
            label: "空闲超时",
            desc: "LLM 请求空闲超时（秒）— 首次响应及流式相邻事件最长等待时间（10–3600）",
            type: "number",
            value: 120,
            min: 10,
            max: 3600,
            step: 1,
          },
          {
            key: "llm.consumers",
            label: "并发消费者",
            desc: "并发消费者数量（增大可加速流式输出，默认 3）",
            type: "number",
            value: 3,
            min: 1,
            max: 16,
            step: 1,
          },
          {
            key: "llm.model",
            label: "默认模型",
            desc: "面试对话使用的大模型标识，需与大模型管理中的配置保持一致",
            type: "select",
            value: "deepseek-chat",
            options: ["deepseek-chat", "deepseek-reasoner", "qwen-max", "glm-4-plus"],
          },
          {
            key: "llm.output_sec_lang",
            label: "第二语言输出",
            desc: "是否允许输出第二语言（关闭后仅输出中文）",
            type: "switch",
            value: false,
          },
          {
            key: "llm.no_emotion_limit_prompt",
            label: "解除情绪数量限制",
            desc: "解除 emotion 数量限制（可能增加 token 消耗）",
            type: "switch",
            value: true,
          },
        ],
      },
    ],
  },
  {
    name: "功能设置",
    groups: [
      {
        name: "对话增强",
        desc: "这里可以设置是否启用时间感知和情绪分类器功能",
        fields: [
          {
            key: "features.enable_time_sense",
            label: "时间感知",
            desc: "启用时间感知（根据上下文时间添加系统提醒）",
            type: "switch",
            value: true,
          },
          {
            key: "features.enable_emotion_classifier",
            label: "情感分类器",
            desc: "启用情感分类器（ONNX 模型，用于自动标注对话 emotion）",
            type: "switch",
            value: false,
          },
          {
            key: "features.max_follow_up",
            label: "最大追问轮次",
            desc: "同一道题最多连续追问的轮次（1–5），超过后进入下一题",
            type: "number",
            value: 2,
            min: 1,
            max: 5,
            step: 1,
          },
        ],
      },
      {
        name: "记忆系统",
        desc: "在这里设定你想要的永久记忆效果",
        fields: [
          {
            key: "features.use_persistent_memory",
            label: "永久记忆",
            desc: "开启后记忆会自动压缩，保存后需重启生效",
            type: "switch",
            value: false,
          },
          {
            key: "features.memory_update_interval",
            label: "摘要触发间隔",
            desc: "触发摘要的可见台词数（1–10000，默认 250）",
            type: "number",
            value: 250,
            min: 1,
            max: 10000,
            step: 1,
          },
          {
            key: "features.memory_recent_window",
            label: "保留最近台词",
            desc: "压缩后保留的面试官可见台词数（0–10000，默认 30）",
            type: "number",
            value: 30,
            min: 0,
            max: 10000,
            step: 1,
          },
          {
            key: "features.memory_inject_continue_user",
            label: "补齐求职者消息",
            desc: "上下文窗口内没有求职者（user）消息时（工具调用挤满窗口等情况），裁切到下一条 assistant 并在其前面注入一条 user「继续」，兼容要求首条消息为 user 的 provider（如 Gemini）；关闭则只裁切、不注入",
            type: "switch",
            value: true,
          },
        ],
      },
      {
        name: "界面与显示",
        desc: "界面外观与启动行为等显示相关设置",
        fields: [
          {
            key: "display.disable_splash_animation",
            label: "关闭开屏动画",
            desc: "关闭首次启动的开屏动画（猫爪加载动画）",
            type: "switch",
            value: false,
          },
          {
            key: "display.show_timer",
            label: "显示计时器",
            desc: "面试房间顶部显示本次面试已用时长，关闭后只在结束报告中统计",
            type: "switch",
            value: true,
          },
        ],
      },
    ],
  },
  {
    name: "TTS 配置",
    groups: [
      {
        name: "适配器 URL",
        desc: "各个 TTS 后端的 API 地址，对应原环境变量 SIMPLE_VITS_API_URL / STYLE_BERT_VITS2_URL 等",
        fields: [
          {
            key: "tts.sbv2_api_url",
            label: "Style-Bert-VITS2 服务地址",
            desc: "Style-Bert-Vits2 本地服务地址",
            type: "text",
            value: "http://127.0.0.1:5000",
          },
          {
            key: "tts.gsv_api_url",
            label: "GPT-SoVITS API 地址",
            desc: "GPT-SoVITS API 地址",
            type: "text",
            value: "http://127.0.0.1:9880",
          },
          {
            key: "tts.indextts_api_url",
            label: "IndexTTS2 API 地址",
            desc: "IndexTTS2 API 地址",
            type: "text",
            value: "http://127.0.0.1:7860",
          },
        ],
      },
      {
        name: "音频参数",
        desc: "TTS 音频输出格式与语言设置，对应原环境变量 TTS_AUDIO_FORMAT / VOICE_LANG",
        fields: [
          {
            key: "tts.audio_format",
            label: "音频格式",
            desc: "音频文件格式（wav / mp3 / flac / ogg 等）",
            type: "select",
            value: "wav",
            options: ["wav", "mp3", "flac", "ogg"],
          },
          {
            key: "tts.lang",
            label: "语音语言",
            desc: "合成语音使用的语言，需与面试官音色保持一致",
            type: "select",
            value: "zh-CN",
            options: ["zh-CN", "ja-JP", "en-US"],
          },
          {
            key: "tts.speed",
            label: "语速",
            desc: "合成语音的播放速度（0.5–2.0，1.0 为原始速度）",
            type: "number",
            value: 1,
            min: 0.5,
            max: 2,
            step: 0.1,
          },
        ],
      },
    ],
  },
  {
    name: "日志配置",
    groups: [
      {
        name: "基础设置",
        desc: "程序运行时文件日志的相关设置",
        fields: [
          {
            key: "log.enable",
            label: "写入日志文件",
            desc: "是否将运行日志写入文件（位于 data/log/app/ 目录）",
            type: "switch",
            value: true,
          },
          {
            key: "log.retention_days",
            label: "保留天数",
            desc: "日志文件保留天数，超过的旧文件在启动时自动清理",
            type: "number",
            value: 7,
            min: 1,
            max: 90,
            step: 1,
          },
          {
            key: "log.llm_request_body",
            label: "记录 LLM 请求体",
            desc: "记录每次 LLM 请求的完整请求体 JSON 到 data/log/llm/ 目录（默认关闭）",
            type: "switch",
            value: false,
          },
          {
            key: "log.genai_debug",
            label: "SDK 调试日志",
            desc: "开启 genai SDK 的调试日志（含请求/响应细节，保存后即时生效，默认关闭）",
            type: "switch",
            value: false,
          },
        ],
      },
    ],
  },
  {
    name: "面试印象",
    groups: [
      {
        name: "通用",
        desc: "面试印象系统的评估开关与打分口径",
        fields: [
          {
            key: "affection.enabled",
            label: "启用面试印象",
            desc: "面试印象系统总开关，关闭后停止评估并隐藏面试印象面板",
            type: "switch",
            value: true,
          },
          {
            key: "affection.window",
            label: "评估窗口",
            desc: "参与印象评估的最近对话轮数（1–20，默认 6）",
            type: "number",
            value: 6,
            min: 1,
            max: 20,
            step: 1,
          },
          {
            key: "affection.tone_bias",
            label: "反馈语气",
            desc: "面试官在追问与点评时的语气倾向",
            type: "select",
            value: "中性",
            options: ["严格", "中性", "鼓励"],
          },
        ],
      },
    ],
  },
]);

function allFields(): AdvanceField[] {
  return configData.value.flatMap((c) => c.groups.flatMap((g) => g.fields));
}

function findField(key: string): AdvanceField | undefined {
  return allFields().find((f) => f.key === key);
}

function applySaved(): void {
  const raw = localStorage.getItem(STORE_KEY);
  if (raw) {
    try {
      const saved = JSON.parse(raw) as Record<string, unknown>;
      for (const field of allFields()) {
        if (!(field.key in saved)) continue;
        const value = saved[field.key];
        if (field.type === "switch" && typeof value === "boolean") field.value = value;
        else if (field.type === "number" && typeof value === "number") field.value = value;
        else if (field.type !== "switch" && typeof value === "string") field.value = value;
      }
    } catch {
      /* 损坏的本地配置按默认值处理 */
    }
  }
  // 总开关以首页卡片写入的那一项为准
  const master = localStorage.getItem(MASTER_KEY);
  const enabled = findField("affection.enabled");
  if (master !== null && enabled) enabled.value = master !== "0";
}

applySaved();

const affectionHeartbeat = ref(localStorage.getItem(HEARTBEAT_KEY) !== "0");
const affectionWave = ref(localStorage.getItem(WAVE_KEY) !== "0");

const selection = reactive({ category: 0, group: 0 });
const narrowViewLevel = ref<"menu" | "content">("menu");
const isNarrow = ref(false);

const activeCategory = computed<AdvanceCategory | null>(
  () => configData.value[selection.category] ?? null,
);
const activeGroup = computed<AdvanceGroup | null>(
  () => activeCategory.value?.groups[selection.group] ?? null,
);

const saveStatus = reactive({ message: "", colorClass: "text-green-500" });
const isReconnectingTts = ref(false);
const reconnectStatus = reactive({ message: "", colorClass: "text-green-400" });

let saveStatusTimer: number | null = null;
let reconnectStatusTimer: number | null = null;

function selectGroup(categoryIndex: number, groupIndex: number): void {
  selection.category = categoryIndex;
  selection.group = groupIndex;
  if (isNarrow.value) narrowViewLevel.value = "content";
}

function onNumberInput(field: AdvanceField, event: Event): void {
  const raw = (event.target as HTMLInputElement).value;
  const parsed = Number(raw);
  field.value = raw === "" || Number.isNaN(parsed) ? "" : parsed;
}

function saveSettings(): void {
  const all: Record<string, string | number | boolean> = {};
  for (const field of allFields()) all[field.key] = field.value;
  localStorage.setItem(STORE_KEY, JSON.stringify(all));

  const enabled = findField("affection.enabled");
  if (enabled) localStorage.setItem(MASTER_KEY, enabled.value === true ? "1" : "0");

  saveStatus.message = `已保存 ${activeGroup.value?.fields.length ?? 0} 项配置，重启软件后生效`;
  saveStatus.colorClass = "text-green-500";
  notify("高级设置已保存");
  if (saveStatusTimer !== null) window.clearTimeout(saveStatusTimer);
  saveStatusTimer = window.setTimeout(() => {
    saveStatus.message = "";
    saveStatusTimer = null;
  }, 5000);
}

function forceReconnectTts(): void {
  if (isReconnectingTts.value) return;
  isReconnectingTts.value = true;
  reconnectStatus.message = "正在重新启用 TTS 服务…";
  reconnectStatus.colorClass = "text-white/70";
  if (reconnectStatusTimer !== null) {
    window.clearTimeout(reconnectStatusTimer);
    reconnectStatusTimer = null;
  }
  window.setTimeout(() => {
    isReconnectingTts.value = false;
    reconnectStatus.message = "TTS 已重新启用，下一次语音会立即重试连接。";
    reconnectStatus.colorClass = "text-green-400";
    reconnectStatusTimer = window.setTimeout(() => {
      reconnectStatus.message = "";
      reconnectStatusTimer = null;
    }, 8000);
  }, 1200);
}

function toggleHeartbeat(): void {
  affectionHeartbeat.value = !affectionHeartbeat.value;
  localStorage.setItem(HEARTBEAT_KEY, affectionHeartbeat.value ? "1" : "0");
  notify(affectionHeartbeat.value ? "已启用心跳动画" : "已关闭心跳动画");
}

function toggleWave(): void {
  affectionWave.value = !affectionWave.value;
  localStorage.setItem(WAVE_KEY, affectionWave.value ? "1" : "0");
  notify(affectionWave.value ? "已启用波浪动画" : "已关闭波浪动画");
}

// ========== 导航指示器 ==========
const navContainerRef = ref<HTMLElement | null>(null);
const indicatorRef = ref<HTMLElement | null>(null);
let resizeObserver: ResizeObserver | null = null;

function updateIndicatorPosition(): void {
  if (!navContainerRef.value || !indicatorRef.value) return;
  const activeLink = navContainerRef.value.querySelector(".adv-nav-link.active") as HTMLElement | null;
  if (!activeLink) {
    indicatorRef.value.style.height = "0px";
    return;
  }
  indicatorRef.value.style.top = `${activeLink.offsetTop}px`;
  indicatorRef.value.style.height = `${activeLink.offsetHeight}px`;
}

watch(
  selection,
  async () => {
    await nextTick();
    updateIndicatorPosition();
  },
  { deep: true },
);

// ========== 窄屏：菜单 / 内容两级切换 ==========
let mediaQuery: MediaQueryList | null = null;

function syncNarrow(event?: MediaQueryListEvent): void {
  isNarrow.value = event ? event.matches : (mediaQuery?.matches ?? false);
}

onMounted(async () => {
  mediaQuery = window.matchMedia("(max-width: 767px)");
  syncNarrow();
  mediaQuery.addEventListener("change", syncNarrow);

  await nextTick();
  updateIndicatorPosition();
  if (navContainerRef.value) {
    resizeObserver = new ResizeObserver(() => updateIndicatorPosition());
    resizeObserver.observe(navContainerRef.value);
  }
});

onUnmounted(() => {
  mediaQuery?.removeEventListener("change", syncNarrow);
  resizeObserver?.disconnect();
  if (saveStatusTimer !== null) window.clearTimeout(saveStatusTimer);
  if (reconnectStatusTimer !== null) window.clearTimeout(reconnectStatusTimer);
});

const emit = defineEmits<{
  "remove-more-menu-from-b": [];
}>();

function addMoreMenu(): void {
  narrowViewLevel.value = "menu";
}

function removeMoreMenu(): void {
  emit("remove-more-menu-from-b");
}

defineExpose({ addMoreMenu });
</script>

<style scoped>
.adv-nav-link {
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.25);
}

.adv-nav-link.active {
  color: #fff;
  font-weight: 700;
}

.adv-nav-link.active:hover {
  background: transparent;
  color: #fff;
}
</style>
