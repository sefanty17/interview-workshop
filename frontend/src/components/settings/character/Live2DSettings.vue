<template>
  <div class="space-y-5">
    <div class="flex flex-wrap gap-2">
      <button
        type="button"
        class="rounded-lg bg-white/10 px-3 py-2 text-sm hover:bg-white/20"
        @click="pickSource('directory')"
      >
        导入模型目录
      </button>
      <button
        type="button"
        class="rounded-lg bg-white/10 px-3 py-2 text-sm hover:bg-white/20"
        @click="pickSource('zip')"
      >
        导入 ZIP
      </button>
      <button
        v-if="localSettings"
        type="button"
        class="rounded-lg bg-red-500/15 px-3 py-2 text-sm text-red-200 hover:bg-red-500/30"
        @click="removeLive2d"
      >
        移除 Live2D 配置
      </button>
    </div>

    <p v-if="busy" class="text-sm text-cyan-200">正在读取 Live2D 模型...</p>
    <p v-if="errorMessage" class="text-sm text-red-300">{{ errorMessage }}</p>
    <p v-if="!localSettings && !busy" class="py-8 text-center text-sm text-white/45">
      此角色尚未配置 Live2D。
    </p>

    <template v-if="localSettings">
      <div class="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
        <div class="space-y-4">
          <label class="flex flex-col gap-2 text-sm text-white/70">
            默认模型变体
            <select
              :value="localSettings.default_variant"
              class="live2d-control"
              @change="setDefaultVariant(($event.target as HTMLSelectElement).value)"
            >
              <option v-for="name in variantNames" :key="name" :value="name">{{ name }}</option>
            </select>
          </label>

          <label class="flex flex-col gap-2 text-sm text-white/70">
            当前编辑的模型变体
            <select v-model="selectedVariant" class="live2d-control">
              <option v-for="name in variantNames" :key="name" :value="name">{{ name }}</option>
            </select>
          </label>

          <div
            v-if="currentVariant"
            class="space-y-3 rounded-lg border border-white/10 bg-white/5 p-3"
          >
            <div class="text-xs break-all text-white/55">{{ currentVariant.model }}</div>
            <label class="flex flex-col gap-2 text-sm text-white/70">
              默认表情
              <select v-model="currentVariant.default_expression" class="live2d-control">
                <option value="">-</option>
                <option v-for="name in expressionOptions" :key="name" :value="name">{{ name }}</option>
              </select>
              <span class="text-xs text-white/45">
                仅在当前情绪没有对应表情映射时作为后备；它不等同于“正常”情绪的表情。
              </span>
            </label>
            <div class="grid grid-cols-2 gap-2">
              <label class="flex flex-col gap-2 text-sm text-white/70">
                视线基点 X（0-1）
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.01"
                  class="live2d-control"
                  :value="currentVariant.focus_anchor?.x ?? 0.5"
                  @input="setFocusAnchor('x', ($event.target as HTMLInputElement).value)"
                />
              </label>
              <label class="flex flex-col gap-2 text-sm text-white/70">
                视线基点 Y（0-1）
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.01"
                  class="live2d-control"
                  :value="currentVariant.focus_anchor?.y ?? 0.5"
                  @input="setFocusAnchor('y', ($event.target as HTMLInputElement).value)"
                />
              </label>
            </div>
            <button
              v-if="currentVariant.focus_anchor"
              type="button"
              class="text-left text-xs text-white/50 hover:text-white/80"
              @click="currentVariant.focus_anchor = null"
            >
              恢复使用模型显示区域中心
            </button>
            <div class="grid grid-cols-1 gap-2 md:grid-cols-2">
              <div v-for="emotion in emotions" :key="emotion" class="rounded-lg bg-black/15 p-2">
                <div
                  class="mb-2 flex items-center justify-between gap-2 text-xs font-medium text-white/70"
                >
                  <span>{{ emotion }}</span>
                  <button
                    type="button"
                    class="preview-button"
                    :disabled="!previewReady"
                    @click="previewUnavailable"
                  >
                    演示
                  </button>
                </div>
                <select v-model="currentVariant.expressions[emotion]" class="live2d-control mb-2">
                  <option value="">不切换表情</option>
                  <option v-for="name in expressionOptions" :key="name" :value="name">{{ name }}</option>
                </select>
                <select
                  class="live2d-control"
                  :value="motionValue(emotion)"
                  @change="setMotion(emotion, ($event.target as HTMLSelectElement).value)"
                >
                  <option value="">不播放动作</option>
                  <option v-for="motion in motionOptions" :key="motion.value" :value="motion.value">
                    {{ motion.label }}
                  </option>
                </select>
              </div>
            </div>
          </div>

          <div
            v-if="currentVariant"
            class="space-y-2 rounded-lg border border-white/10 bg-white/5 p-3"
          >
            <h4 class="text-sm font-semibold text-white/75">抚摸反应</h4>
            <p class="text-xs text-white/45">
              勾选部位后，主对话的触摸模式里抚摸它就会有反应：头随手的移动方向轻晃，手停下后播放这里绑定的表情与动作。两者都可留空，留空就只晃动。
            </p>
            <div class="grid grid-cols-1 gap-2 md:grid-cols-2">
              <div v-for="part in touchParts" :key="part" class="rounded-lg bg-black/15 p-2">
                <label class="mb-2 flex items-center gap-2 text-xs font-medium text-white/70">
                  <input
                    type="checkbox"
                    :checked="touchEnabled(part)"
                    @change="setTouchEnabled(part, ($event.target as HTMLInputElement).checked)"
                  />
                  {{ touchPartLabel(part) }}
                </label>
                <button
                  type="button"
                  class="preview-button mb-2"
                  :disabled="!previewReady || !touchEnabled(part)"
                  @click="previewUnavailable"
                >
                  测试此部位表情 / 动作
                </button>
                <select
                  class="live2d-control mb-2"
                  :disabled="!touchEnabled(part)"
                  :value="touchExpression(part)"
                  @change="setTouchExpression(part, ($event.target as HTMLSelectElement).value)"
                >
                  <option value="">不切换表情</option>
                  <option v-for="name in expressionOptions" :key="name" :value="name">{{ name }}</option>
                </select>
                <select
                  class="live2d-control"
                  :disabled="!touchEnabled(part)"
                  :value="touchMotionValue(part)"
                  @change="setTouchMotion(part, ($event.target as HTMLSelectElement).value)"
                >
                  <option value="">不播放动作</option>
                  <option v-for="motion in motionOptions" :key="motion.value" :value="motion.value">
                    {{ motion.label }}
                  </option>
                </select>
              </div>
            </div>
          </div>

          <div
            v-if="clothesNames.length"
            class="space-y-2 rounded-lg border border-white/10 bg-white/5 p-3"
          >
            <h4 class="text-sm font-semibold text-white/75">服装与模型变体映射</h4>
            <label
              v-for="clothes in clothesNames"
              :key="clothes"
              class="grid grid-cols-2 items-center gap-3 text-sm"
            >
              <span>{{ clothes }}</span>
              <select v-model="localSettings.clothes_variants[clothes]" class="live2d-control">
                <option value="">{{ localSettings.default_variant }}</option>
                <option v-for="name in variantNames" :key="name" :value="name">{{ name }}</option>
              </select>
            </label>
          </div>
        </div>

        <aside
          class="order-first min-w-0 space-y-3 self-start rounded-xl border border-white/10 bg-black/20 p-3 xl:sticky xl:top-0 xl:order-last xl:max-h-[calc(85dvh-12rem)] xl:overflow-y-auto"
        >
          <h4 class="text-sm font-semibold text-white/75">Live2D 调试预览</h4>
          <div class="relative h-72 overflow-hidden rounded-lg border border-white/10 bg-black/25">
            <span class="pointer-events-none absolute inset-x-2 bottom-2 text-center text-xs text-white/50">
              {{ previewUnavailableHint }}
            </span>
          </div>
          <button type="button" class="preview-button w-full" disabled @click="previewUnavailable">
            在模型上点选焦点锚点
          </button>
          <p class="text-xs leading-relaxed text-white/45">
            十字标记为焦点锚点。点选坐标按模型边界归一化，保存角色配置后生效。
          </p>
          <label class="block space-y-2 text-xs text-white/65">
            <span>预览情绪</span>
            <select v-model="previewEmotionValue" class="live2d-control">
              <option v-for="emotion in emotions" :key="emotion" :value="emotion">{{ emotion }}</option>
            </select>
          </label>
          <button type="button" class="preview-button w-full" disabled @click="previewUnavailable">
            重播情绪 / 恢复情绪表情
          </button>
          <label class="block space-y-2 text-xs text-white/65">
            <span>独立表情测试</span>
            <select v-model="testExpression" class="live2d-control">
              <option value="">选择表情</option>
              <option v-for="name in expressionOptions" :key="name" :value="name">{{ name }}</option>
            </select>
          </label>
          <button type="button" class="preview-button w-full" disabled @click="previewUnavailable">
            应用表情
          </button>
          <label class="block space-y-2 text-xs text-white/65">
            <span>独立动作测试</span>
            <select v-model="testMotion" class="live2d-control">
              <option value="">选择动作</option>
              <option v-for="motion in motionOptions" :key="motion.value" :value="motion.value">
                {{ motion.label }}
              </option>
            </select>
          </label>
          <button type="button" class="preview-button w-full" disabled @click="previewUnavailable">
            播放一次
          </button>
          <p role="status" class="min-h-8 text-xs leading-relaxed text-cyan-100/70">
            {{ previewStatus || "测试仅作用于此预览，不改变当前游戏状态。触摸测试播放所配置的表情和动作。" }}
          </p>
        </aside>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Live2dSettings } from "../../../types/live2d";
import { TOUCH_PART_ORDER } from "../../../types/live2d";
import { requireBackend } from "../../../utils/backendStub";

const props = defineProps<{
  roleId: string;
  characterFolder: string;
  clothes: Array<{ name?: string }> | null | undefined;
  scale?: number;
  offsetX?: number;
  offsetY?: number;
  modelValue: Live2dSettings | null | undefined;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: Live2dSettings | null];
}>();

const localSettings = computed<Live2dSettings | null>({
  get: () => props.modelValue ?? null,
  set: (value) => emit("update:modelValue", value),
});
/** 变体里可用的表情 / 动作清单来自后端导入模型时的扫描结果，纯前端拿不到 */
const metadata = ref<Array<{ variant: string; model: string; expressions: string[]; motions: Record<string, string[]> }>>(
  [],
);
const selectedVariant = ref("");
const previewReady = ref(false);
const previewEmotionValue = ref("正常");
const testExpression = ref("");
const testMotion = ref("");
const previewStatus = ref("");
const busy = ref(false);
const errorMessage = ref("");
const previewUnavailableHint = ref("模型预览需要后端模型文件与 WebGL 运行时");

const emotions = [
  "正常",
  "平静",
  "高兴",
  "兴奋",
  "生气",
  "害羞",
  "疑惑",
  "哭泣",
  "惊讶",
  "厌恶",
  "担心",
  "认真",
  "紧张",
  "害怕",
  "慌张",
  "无奈",
  "心动",
  "调皮",
  "难为情",
  "自信",
];

const variantNames = computed(() => Object.keys(localSettings.value?.variants ?? {}));
const currentVariant = computed(() => localSettings.value?.variants[selectedVariant.value]);
const currentMetadata = computed(() => metadata.value.find((item) => item.variant === selectedVariant.value));
const expressionOptions = computed(() => currentMetadata.value?.expressions ?? []);
const motionOptions = computed(() => {
  const options: Array<{ value: string; label: string }> = [];
  for (const [group, files] of Object.entries(currentMetadata.value?.motions ?? {})) {
    files.forEach((file, index) =>
      options.push({ value: `${group}:${index}`, label: `${group}[${index}] ${file}` }),
    );
  }
  return options;
});
const clothesNames = computed(() => [
  "default",
  ...(props.clothes ?? [])
    .map((item) => item.name?.trim())
    .filter((name): name is string => Boolean(name)),
]);

watch(
  () => props.modelValue?.default_variant,
  (defaultVariant) => {
    if (!selectedVariant.value || !props.modelValue?.variants[selectedVariant.value]) {
      selectedVariant.value = defaultVariant ?? "";
    }
  },
  { immediate: true },
);

function previewUnavailable() {
  previewStatus.value = "没有可播放的绑定，或模型未能应用该资源";
}

function setFocusAnchor(axis: "x" | "y", rawValue: string) {
  const variant = currentVariant.value;
  const value = Number(rawValue);
  if (!variant || !Number.isFinite(value)) return;
  const anchor = variant.focus_anchor ?? { x: 0.5, y: 0.5 };
  variant.focus_anchor = { ...anchor, [axis]: Math.min(1, Math.max(0, value)) };
}

function setDefaultVariant(variantName: string) {
  const settings = localSettings.value;
  if (!settings?.variants[variantName]) return;
  settings.default_variant = variantName;
  if (Object.prototype.hasOwnProperty.call(settings.clothes_variants, "default")) {
    settings.clothes_variants.default = variantName;
  }
  selectedVariant.value = variantName;
}

function motionValue(emotion: string) {
  const motion = currentVariant.value?.motions[emotion];
  return motion ? `${motion.group}:${motion.index}` : "";
}

function setMotion(emotion: string, value: string) {
  const variant = currentVariant.value;
  if (!variant) return;
  if (!value) {
    delete variant.motions[emotion];
    return;
  }
  const separator = value.lastIndexOf(":");
  variant.motions[emotion] = {
    ...variant.motions[emotion],
    group: value.slice(0, separator),
    index: Number(value.slice(separator + 1)),
    loop: false,
  };
}

// --- 抚摸反应 ---

/** 判定顺序同时也是展示顺序，与运行时共用一份，免得两处各列一遍导致漏配部位。 */
const touchParts = TOUCH_PART_ORDER;

function touchBinding(part: string) {
  return currentVariant.value?.touch_motions?.[part];
}

/** 部位出现在表里就代表可摸，所以「只晃动、表情动作都留空」也是合法配置。 */
function touchEnabled(part: string) {
  return touchBinding(part) !== undefined;
}

function touchPartLabel(part: string) {
  return (
    { head: "头部", body: "身体", legs: "双腿", earLeft: "左耳", earRight: "右耳" }[part] ?? part
  );
}

function setTouchEnabled(part: string, enabled: boolean) {
  const variant = currentVariant.value;
  if (!variant) return;
  const bindings = { ...(variant.touch_motions ?? {}) };
  if (enabled) {
    if (!bindings[part]) bindings[part] = {};
  } else {
    delete bindings[part];
  }
  if (Object.keys(bindings).length) variant.touch_motions = bindings;
  else delete variant.touch_motions;
}

function touchExpression(part: string) {
  return touchBinding(part)?.expression ?? "";
}

function setTouchExpression(part: string, value: string) {
  const binding = touchBinding(part);
  if (!binding) return;
  if (value) binding.expression = value;
  else delete binding.expression;
}

function touchMotionValue(part: string) {
  const binding = touchBinding(part);
  if (binding?.group === undefined || binding.index === undefined) return "";
  return `${binding.group}:${binding.index}`;
}

function setTouchMotion(part: string, value: string) {
  const binding = touchBinding(part);
  if (!binding) return;
  // group 与 index 必须同进同退，只给一个运行时不会播任何动作
  if (!value) {
    delete binding.group;
    delete binding.index;
    return;
  }
  const separator = value.lastIndexOf(":");
  binding.group = value.slice(0, separator);
  binding.index = Number(value.slice(separator + 1));
}

async function pickSource(sourceKind: "directory" | "zip") {
  busy.value = true;
  errorMessage.value = "";
  try {
    await requireBackend(
      sourceKind === "directory"
        ? "「导入模型目录」需要后端读取本机模型目录并把 model3.json 落进角色资源目录，当前为纯前端演示。"
        : "「导入 ZIP」需要后端解压并校验 Live2D 模型包，当前为纯前端演示。",
    );
  } finally {
    busy.value = false;
  }
}

function removeLive2d() {
  localSettings.value = null;
  metadata.value = [];
  selectedVariant.value = "";
}
</script>

<style scoped>
.preview-button {
  border-radius: 0.5rem;
  background: rgb(255 255 255 / 0.1);
  padding: 0.4rem 0.65rem;
  color: rgb(255 255 255 / 0.8);
  font-size: 0.75rem;
}
.preview-button:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.2);
}
.preview-button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.live2d-control {
  width: 100%;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 0.5rem;
  background: rgb(0 0 0 / 0.2);
  padding: 0.45rem 0.65rem;
  color: white;
  font-size: 0.8rem;
}
.live2d-control option {
  background: #292929;
}
</style>
