<template>
  <div class="lc-reset-radius flex h-full min-h-0 flex-col bg-black/40 p-2 md:p-4">
    <div
      class="flex h-full min-h-0 flex-col overflow-hidden rounded-3xl border border-white/20 bg-[linear-gradient(135deg,rgba(255,255,255,0.15)_0%,rgba(255,255,255,0.05)_100%)] text-white shadow-[0_20px_60px_rgba(0,0,0,0.4),inset_0_0_1px_rgba(255,255,255,0.3)]"
    >
      <!-- Header -->
      <div
        class="flex items-center justify-between border-b border-white/10 bg-[linear-gradient(180deg,rgba(255,255,255,0.1)_0%,rgba(255,255,255,0.05)_100%)] p-6"
      >
        <div class="flex items-center gap-4">
          <div
            class="flex h-12 w-12 items-center justify-center overflow-hidden rounded-xl bg-white/10 shadow-inner"
          >
            <img :src="headerAvatar" :alt="title ?? ''" class="h-full w-full object-cover" />
          </div>
          <div>
            <h2 class="m-0 text-xl font-bold drop-shadow-[0_2px_4px_rgba(0,0,0,0.3)]">
              {{ title ?? '' }} - 配置编辑
            </h2>
            <p class="m-0 text-sm text-white/50">修改角色的详细设置</p>
          </div>
        </div>
        <button
          class="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full border-none bg-white/10 text-white transition-all duration-200 hover:rotate-90 hover:bg-white/20"
          @click="handleClose"
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>

      <!-- Content -->
      <div class="flex flex-1 flex-row overflow-hidden">
        <!-- Sidebar (vertical scrollable for narrow viewports) -->
        <div
          class="tab-sidebar-scroll flex w-44 shrink-0 flex-col gap-2 overflow-y-auto border-r border-white/10 bg-black/10 p-3"
        >
          <button
            v-for="tab in tabs"
            :key="tab.id"
            class="w-full cursor-pointer rounded-xl border-none bg-transparent px-4 py-2.5 text-left font-medium text-white/60 transition-all duration-200 hover:bg-white/5 hover:text-white"
            :class="{
              'bg-[rgba(94,114,228,0.2)] font-semibold! text-[#79d9ff]!': activeTab === tab.id,
            }"
            @click="activeTab = tab.id"
          >
            {{ tab.label }}
          </button>
        </div>

        <!-- Tab Panels -->
        <div ref="panelRef" class="relative flex-1 overflow-y-auto p-6">
          <fieldset class="mx-auto max-w-3xl min-w-0 space-y-6 border-0 p-0">
            <p
              class="rounded-xl border border-cyan-200/15 bg-cyan-400/5 p-3 text-xs leading-relaxed text-cyan-100/70"
            >
              所有配置和资源更改先保留在草稿。点击底部「保存更改」统一保存；取消或关闭会放弃草稿。试听和预览不保存配置。删除角色是独立操作。
            </p>

            <PetLayoutEditor
              v-if="activeTab === 'pet'"
              v-model="localSettings"
              :role-id="roleId"
              :clothes="draftClothes"
            />
            <RoleLayoutEditor
              v-if="activeTab === 'visuals'"
              v-model="localSettings"
              :role-id="roleId"
              :clothes="draftClothes"
            />

            <!-- Data-Driven Form (tabs with schemas) -->
            <div v-if="currentTabConfig" class="space-y-4">
              <div
                v-for="(field, index) in currentTabFields"
                :key="index"
                class="flex flex-col gap-2"
              >
                <label :for="field.key" class="text-[13px] font-medium text-white/60"
                  >{{ field.label }} ({{ field.key }})</label
                >
                <input
                  v-if="field.type === 'text' || field.type === 'number'"
                  :id="field.key"
                  v-model="fieldModel(field).value"
                  :type="field.type"
                  :step="field.step"
                  :placeholder="field.placeholder"
                  class="form-control rounded-xl border border-white/10 bg-black/20 px-3.5 py-2.5 text-sm text-white transition-all duration-200 outline-none"
                />
                <DialogueExamplesEditor
                  v-else-if="field.type === 'examples'"
                  v-model="fieldModel(field).value"
                  :user-name="localSettings.user_name || ''"
                  :character-name="localSettings.ai_name || ''"
                />
                <textarea
                  v-else-if="field.type === 'textarea'"
                  :id="field.key"
                  v-model="fieldModel(field).value"
                  :rows="field.rows || 4"
                  class="form-control rounded-xl border border-white/10 bg-black/20 px-3.5 py-2.5 font-mono text-sm leading-relaxed text-white transition-all duration-200 outline-none"
                ></textarea>
                <select
                  v-else-if="field.type === 'select'"
                  :id="field.key"
                  v-model="fieldModel(field).value"
                  class="form-control rounded-xl border border-white/10 bg-black/20 px-3.5 py-2.5 text-sm text-white transition-all duration-200 outline-none"
                >
                  <option
                    v-for="opt in resolveFieldOptions(field)"
                    :key="opt.value"
                    :value="opt.value"
                    class="bg-[#333] text-white"
                  >
                    {{ opt.label }}
                  </option>
                </select>
                <!-- 开关：轨道本身充当第二个 <label>（与上方的字段名 label 是兄弟，
                     不能嵌套），滑块用绝对定位的兄弟节点 + peer-checked 联动 -->
                <div v-else-if="field.type === 'switch'" class="relative flex w-fit items-center">
                  <input
                    :id="field.key"
                    v-model="fieldModel(field).value"
                    type="checkbox"
                    class="peer sr-only"
                  />
                  <label
                    :for="field.key"
                    class="peer-checked:border-brand peer-checked:bg-brand block h-5 w-9 cursor-pointer rounded-full border border-white/20 bg-white/10 transition-colors"
                  ></label>
                  <div
                    class="pointer-events-none absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white transition-transform peer-checked:translate-x-4"
                  ></div>
                </div>
                <p v-if="field.hint" class="text-[12px] leading-relaxed text-white/40">
                  {{ field.hint }}
                </p>
              </div>

              <!-- 标签：卡片上的徽标字面量，直接进草稿 -->
              <div v-if="activeTab === 'basic'" class="flex flex-col gap-2">
                <label for="tags" class="text-[13px] font-medium text-white/60">tags</label>
                <div class="flex flex-wrap gap-2">
                  <span
                    v-for="(tag, tagIndex) in tagList"
                    :key="`${tag}-${tagIndex}`"
                    class="flex items-center gap-1 rounded-full border border-white/15 bg-white/10 px-2.5 py-1 text-xs text-white/80"
                  >
                    {{ tag }}
                    <button
                      type="button"
                      class="cursor-pointer text-white/45 transition-colors hover:text-red-200"
                      :aria-label="`移除标签 ${tag}`"
                      @click="removeTag(tagIndex)"
                    >
                      ×
                    </button>
                  </span>
                  <input
                    id="tags"
                    v-model="tagDraft"
                    type="text"
                    placeholder="输入标签后回车"
                    class="form-control min-w-40 flex-1 rounded-xl border border-white/10 bg-black/20 px-3 py-1.5 text-sm text-white transition-all duration-200 outline-none"
                    @keydown.enter.prevent="addTag"
                  />
                </div>
                <p class="text-[12px] leading-relaxed text-white/40">
                  标签展示在角色卡与角色详情头部，回车添加，点击 × 删除。
                </p>
              </div>
            </div>

            <!-- GSV 六情绪参考语音：填全的分类按情绪切换参考音色，未填全的
                 分类仍回退到上面的 gsv_voice_filename / gsv_voice_text -->
            <div v-if="activeTab === 'voice' && localSettings.tts_type === 'gsv'" class="space-y-4">
              <div
                class="flex items-center justify-between rounded-xl border border-white/10 bg-white/5 p-4"
              >
                <div>
                  <h3 class="text-sm font-bold text-white/70">GSV 情绪参考语音</h3>
                  <p class="mt-1 text-xs text-white/40">
                    开启后按情绪分类（吃惊/开心/恐惧/难过/生气/中立）切换参考语音与文本。音频路径与文本都填写了的分类才生效，未填全的分类仍使用上面的 gsv_voice_filename / gsv_voice_text。
                  </p>
                </div>
                <button
                  role="switch"
                  :aria-checked="gsvEmoEnabled"
                  class="cursor-pointer rounded-lg border-none px-3.5 py-1.5 text-xs transition-colors"
                  :class="
                    gsvEmoEnabled
                      ? 'bg-[#5e72e4] text-white hover:bg-[#4a5acf]'
                      : 'bg-white/10 text-white/60 hover:bg-white/20'
                  "
                  @click="toggleGsvEmo"
                >
                  {{ gsvEmoEnabled ? '已开启' : '已关闭' }}
                </button>
              </div>

              <div v-if="gsvEmoEnabled" class="space-y-3">
                <div
                  v-for="cat in GSV_EMO_CATEGORIES"
                  :key="cat"
                  class="grid grid-cols-1 gap-3 rounded-xl border border-white/10 bg-white/5 p-4 md:grid-cols-2"
                >
                  <div class="flex flex-col gap-2">
                    <label class="text-[13px] font-medium text-white/60">
                      {{ cat }} · 参考语音文件
                    </label>
                    <input
                      v-model="gsvEmoField(cat, 'gsv_emo_voice_files').value"
                      type="text"
                      placeholder="填写 GSV 服务端可访问的音频路径（如 /refs/开心.wav）"
                      class="form-control rounded-xl border border-white/10 bg-black/20 px-3.5 py-2.5 text-sm text-white transition-all duration-200 outline-none"
                    />
                  </div>
                  <div class="flex flex-col gap-2">
                    <label class="text-[13px] font-medium text-white/60">{{ cat }} · 参考文本</label>
                    <input
                      v-model="gsvEmoField(cat, 'gsv_emo_texts').value"
                      type="text"
                      placeholder="参考音频对应的文本内容"
                      class="form-control rounded-xl border border-white/10 bg-black/20 px-3.5 py-2.5 text-sm text-white transition-all duration-200 outline-none"
                    />
                  </div>
                </div>
              </div>
            </div>

            <VoicePreview
              v-if="activeTab === 'voice'"
              :role-id="roleId"
              :model-value="localSettings"
            />

            <AvatarManager
              v-if="activeTab === 'avatars'"
              :role-id="roleId"
              :clothes="clothesList"
              :resource-clothes="draftClothes"
              :initial-costume="avatarCostume"
            />

            <Live2DSettings
              v-if="activeTab === 'live2d'"
              v-model="localSettings.live2d"
              :role-id="roleId"
              :character-folder="localSettings.character_folder || ''"
              :clothes="clothesList"
              :scale="Number(localSettings.scale) || 1"
              :offset-x="Number(localSettings.offset_x) || 0"
              :offset-y="Number(localSettings.offset_y) || 0"
            />

            <CostumeManager
              v-if="activeTab === 'clothes'"
              v-model="localSettings"
              :role-id="roleId"
              @changed="onCostumeChanged"
              @edit-avatars="openCostumeAvatars"
            />

            <!-- Touch Tab：区域绘制放在独立的全屏层里，这里只做入口与概览 -->
            <div v-if="activeTab === 'touch'" class="space-y-4">
              <p class="text-[13px] leading-relaxed text-white/50">
                在立绘上画出可以摸的区域，每个部位配一句发给 AI 的话。每件服装各有一套区域。仅对静态立绘生效，Live2D 角色的可摸范围由模型配置决定。
              </p>
              <button
                class="cursor-pointer rounded-lg border-none bg-[#5e72e4] px-3 py-1.5 text-xs text-white transition-colors hover:bg-[#4a5acf]"
                @click="touchEditorVisible = true"
              >
                编辑触摸区域
              </button>
            </div>
          </fieldset>
        </div>
      </div>

      <!-- Footer -->
      <div
        class="flex justify-between gap-3 border-t border-white/10 bg-[linear-gradient(180deg,rgba(255,255,255,0.05)_0%,rgba(255,255,255,0.1)_100%)] p-4"
      >
        <!-- 危险操作区（左侧）：删除角色 -->
        <div class="flex items-center">
          <button
            :disabled="saving"
            :title="deleteReason"
            :class="[
              'rounded-[20px] border px-4 py-2 text-sm font-medium transition-all duration-200',
              'cursor-not-allowed border-white/10 bg-white/5 text-white/25',
            ]"
          >
            <span class="inline-flex items-center gap-1.5">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <polyline points="3 6 5 6 21 6"></polyline>
                <path
                  d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
                ></path>
                <line x1="10" y1="11" x2="10" y2="17"></line>
                <line x1="14" y1="11" x2="14" y2="17"></line>
              </svg>
              删除角色
            </span>
          </button>
        </div>

        <!-- 普通操作（右侧）：取消/保存 -->
        <div class="flex gap-3">
          <button
            class="cursor-pointer rounded-[20px] border-none bg-white/10 px-5 py-2 text-sm font-medium text-white transition-all duration-200 hover:bg-white/20"
            @click="handleClose"
          >
            取消
          </button>
          <button
            class="cursor-pointer rounded-[20px] border-none bg-[#5e72e4] px-5 py-2 text-sm font-medium text-white transition-all duration-200 hover:enabled:-translate-y-px hover:enabled:bg-[#4a5acf] hover:enabled:shadow-[0_4px_12px_rgba(94,114,228,0.3)] disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="saving"
            @click="saveSettings"
          >
            <span
              v-if="saving"
              class="mr-2 inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white"
            ></span>
            {{ saving ? '保存中...' : '保存更改' }}
          </button>
        </div>
      </div>

      <!-- 触摸区域编辑器：全屏层，保持在页面 DOM 子树内，避免点击穿透到上层关闭逻辑 -->
      <TouchRegionsEditor
        v-if="touchEditorVisible"
        :user-name="localSettings.user_name"
        :body-part="localSettings.body_part"
        :clothes="draftClothes"
        @apply="onTouchRegionsApply"
        @close="touchEditorVisible = false"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { avatarUrl, findInterviewer } from "../../../data/interviewers";
import { loadRoleSettings, saveRoleSettings, type RoleSettings } from "../../../store/roleEditor";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";
import { notify } from "../../../store/appStore";
import Live2DSettings from "../character/Live2DSettings.vue";
import AvatarManager from "../character/AvatarManager.vue";
import RoleLayoutEditor from "../character/RoleLayoutEditor.vue";
import CostumeManager from "../character/CostumeManager.vue";
import PetLayoutEditor from "../character/PetLayoutEditor.vue";
import VoicePreview from "../character/VoicePreview.vue";
import DialogueExamplesEditor from "../character/DialogueExamplesEditor.vue";
import TouchRegionsEditor from "../character/TouchRegionsEditor.vue";

const props = defineProps<{
  /** 角色 code（原版是数字 roleId，本项目用 InterviewerCode） */
  roleId: string;
  /** 卡片标题，用于头部「{title} - 配置编辑」 */
  title?: string;
}>();

const emit = defineEmits<{ (e: "close"): void; (e: "saved"): void }>();

const activeTab = ref("basic");
/** 切换分区时把内容区滚回顶部，否则会停在上一个分区的滚动位置 */
const panelRef = ref<HTMLElement | null>(null);
watch(activeTab, () => {
  panelRef.value?.scrollTo({ top: 0 });
});
const avatarCostume = ref("default");
const saving = ref(false);
const touchEditorVisible = ref(false);
const localSettings = ref<RoleSettings>(loadRoleSettings(props.roleId));
const baseline = ref(JSON.stringify(localSettings.value));

watch(
  () => props.roleId,
  (code) => {
    activeTab.value = "basic";
    avatarCostume.value = "default";
    touchEditorVisible.value = false;
    localSettings.value = loadRoleSettings(code);
    baseline.value = JSON.stringify(localSettings.value);
  },
);

const hasChanges = computed(
  () => JSON.stringify(localSettings.value) !== baseline.value,
);

const headerAvatar = computed(() => avatarUrl(props.roleId, "正常"));

/** 静态四个面试官是内置角色，不支持删除（与原版 systemProtected 同一语义） */
const deleteReason = computed(() => `系统内置角色（${findInterviewer(props.roleId).name}），无法删除`);

const tabs = [
  { id: "basic", label: "基本信息" },
  { id: "prompts", label: "提示词" },
  { id: "visuals", label: "视觉效果" },
  { id: "avatars", label: "差分管理" },
  { id: "clothes", label: "服装" },
  { id: "touch", label: "触摸区域" },
  { id: "live2d", label: "Live2D" },
  { id: "pet", label: "桌宠" },
  { id: "voice", label: "语音设置" },
];

// --- Schema Definition ---

type FieldType = "text" | "number" | "textarea" | "examples" | "select" | "switch";

interface FieldOption {
  label: string;
  value: string;
  visibleIf?: (settings: RoleSettings) => boolean;
}

interface FieldSchema {
  key: string;
  label: string;
  type: FieldType;
  rows?: number;
  step?: string;
  placeholder?: string;
  options?: FieldOption[];
  // Dynamic options computed from refs/state. Overrides options when set.
  dynamicOptions?: () => { label: string; value: string }[];
  // One-line explainer rendered under the control (switch fields especially need it)
  hint?: string;
  visibleIf?: (settings: RoleSettings) => boolean;
  isVoiceModel?: boolean;
  // When set, the field reads/writes into localSettings.value[parent][key].
  // The parent object is auto-initialised to {} on first write if missing.
  parent?: string;
}

const resolveFieldOptions = (field: FieldSchema) => {
  const options: FieldOption[] = field.dynamicOptions ? field.dynamicOptions() : (field.options ?? []);
  return options.filter((option) => !option.visibleIf || option.visibleIf(localSettings.value));
};

// 主对话/桌宠形象共用的两个选项。value 是落盘值（settings.yml 的
// avatar_mode / avatar_mode_p），按原版只翻译标签、不翻译取值。
const avatarModeOptions: FieldOption[] = [
  { label: "Live2D 模型", value: "live2d" },
  { label: "静态立绘", value: "image" },
];

const schemas: Record<string, FieldSchema[]> = {
  basic: [
    { key: "ai_name", label: "AI 名称", type: "text" },
    { key: "ai_subtitle", label: "AI 副标题", type: "text" },
    { key: "user_name", label: "用户名称", type: "text" },
    { key: "user_subtitle", label: "用户副标题", type: "text" },
    { key: "title", label: "角色标题", type: "text" },
    { key: "info", label: "角色介绍", type: "textarea", rows: 4 },
  ],
  prompts: [
    { key: "system_prompt", label: "系统提示词", type: "textarea", rows: 10 },
    { key: "system_prompt_example", label: "角色台词示例", type: "examples", rows: 6 },
    {
      key: "system_prompt_example_old",
      label: "旧版兼容台词示例",
      type: "examples",
      rows: 4,
    },
  ],
  visuals: [
    {
      key: "avatar_mode",
      label: "舞台显示方式",
      type: "select",
      // 没有 Live2D 模型时只有「静态立绘」一种可能，整项隐藏（Live2D tab 导入模型后自动出现）
      visibleIf: (s) => Boolean(s.live2d),
      options: avatarModeOptions,
    },
    { key: "scale", label: "缩放", type: "number", step: "0.01" },
    { key: "offset_x", label: "水平偏移", type: "number", step: "0.1" },
    { key: "offset_y", label: "垂直偏移", type: "number", step: "0.1" },
    { key: "bubble_top", label: "气泡顶部距离", type: "number" },
    { key: "bubble_left", label: "气泡左侧距离", type: "number" },
    { key: "thinking_message", label: "思考消息文本", type: "text" },
  ],
  pet: [
    {
      key: "avatar_mode_p",
      label: "桌宠显示方式",
      type: "select",
      visibleIf: (s) => Boolean(s.live2d),
      options: avatarModeOptions,
    },
    {
      key: "pet_frameless",
      label: "无框桌宠",
      type: "switch",
      hint: "隐藏外框与背景，只留角色本体；粒子一并隐藏，角色不再裁成圆形",
    },
    { key: "scale_p", label: "桌宠缩放", type: "number", step: "0.01" },
    { key: "offset_x_p", label: "桌宠水平偏移", type: "number", step: "0.1" },
    { key: "offset_y_p", label: "桌宠垂直偏移", type: "number", step: "0.1" },
  ],
  voice: [
    {
      key: "tts_type",
      label: "TTS 类型",
      type: "select",
      options: [
        { label: "sva", value: "sva-vits" },
        { label: "sbv2", value: "sbv2" },
        { label: "bv2", value: "sva-bv2" },
        { label: "sbv2api", value: "sbv2api" },
        { label: "gsv", value: "gsv" },
        { label: "aivis", value: "aivis" },
        { label: "opentts", value: "opentts" },
        { label: "Fish S2（本地 API）", value: "fishs2" },
        { label: "本地 SBV2 API", value: "localsbv2api" },
        { label: "indextts2", value: "indextts2" },
        { label: "语音克隆TTS", value: "cosyvoice" },
      ],
    },
    {
      key: "voice_lang",
      label: "语音语言",
      type: "select",
      options: [
        // 顺序：中、英、日、德、法、俄、韩、葡（原版）；es/ar 仅 indextts2 可见
        { label: "中文", value: "zh" },
        {
          label: "英语",
          value: "en",
          visibleIf: (s) =>
            ["gsv", "opentts", "sbv2", "sbv2api", "indextts2", "fishs2", "cosyvoice"].includes(
              s.tts_type,
            ),
        },
        { label: "日语", value: "ja" },
        { label: "德语", value: "de", visibleIf: (s) => s.tts_type === "cosyvoice" },
        { label: "法语", value: "fr", visibleIf: (s) => s.tts_type === "cosyvoice" },
        { label: "俄语", value: "ru", visibleIf: (s) => s.tts_type === "cosyvoice" },
        {
          label: "韩语",
          value: "ko",
          visibleIf: (s) => ["gsv", "opentts", "cosyvoice"].includes(s.tts_type),
        },
        { label: "葡萄牙语", value: "pt", visibleIf: (s) => s.tts_type === "cosyvoice" },
        { label: "西班牙语", value: "es", visibleIf: (s) => s.tts_type === "indextts2" },
        { label: "阿拉伯语", value: "ar", visibleIf: (s) => s.tts_type === "indextts2" },
      ],
    },
    {
      // 中文方言：仅语音克隆TTS + 中文时可选
      key: "voice_dialect",
      label: "中文方言",
      type: "select",
      options: [
        { label: "普通话", value: "" },
        { label: "广东话", value: "广东话" },
        { label: "东北话", value: "东北话" },
        { label: "甘肃话", value: "甘肃话" },
        { label: "贵州话", value: "贵州话" },
        { label: "河南话", value: "河南话" },
        { label: "湖北话", value: "湖北话" },
        { label: "江西话", value: "江西话" },
        { label: "闽南话", value: "闽南话" },
        { label: "宁夏话", value: "宁夏话" },
        { label: "山西话", value: "山西话" },
        { label: "陕西话", value: "陕西话" },
        { label: "山东话", value: "山东话" },
        { label: "上海话", value: "上海话" },
        { label: "四川话", value: "四川话" },
        { label: "天津话", value: "天津话" },
        { label: "云南话", value: "云南话" },
      ],
      visibleIf: (s) => s.tts_type === "cosyvoice" && s.voice_lang === "zh",
    },
    {
      key: "gsv_voice_text",
      label: "gsv_voice_text",
      type: "text",
      isVoiceModel: true,
      visibleIf: (s) => s.tts_type === "gsv",
    },
    {
      key: "gsv_voice_filename",
      label: "gsv_voice_filename",
      type: "text",
      isVoiceModel: true,
      visibleIf: (s) => s.tts_type === "gsv",
    },
    {
      key: "gsv_gpt_model_name",
      label: "gsv_gpt_model_name",
      type: "text",
      isVoiceModel: true,
      visibleIf: (s) => s.tts_type === "gsv",
    },
    {
      key: "gsv_sovits_model_name",
      label: "gsv_sovits_model_name",
      type: "text",
      isVoiceModel: true,
      visibleIf: (s) => s.tts_type === "gsv",
    },
    {
      key: "opentts_voice",
      label: "OpenTTS 音色标识",
      type: "text",
      isVoiceModel: true,
      placeholder: "留空则使用高级设置中的全局音色标识",
      visibleIf: (s) => s.tts_type === "opentts",
    },
    {
      key: "fish_s2_voice",
      label: "Fish S2 音色标识",
      type: "text",
      isVoiceModel: true,
      placeholder: "留空则使用高级设置中的默认音色（paimeng）",
      visibleIf: (s) => s.tts_type === "fishs2",
    },
    // --- 云端语音克隆 (cosyvoice) ---
    {
      key: "cosyvoice_voice_id",
      parent: "voice_models",
      label: "云端音色",
      type: "select",
      dynamicOptions: () =>
        cloudVoices.value.length === 0
          ? [{ label: "暂无云端音色，请先到设置页注册", value: "" }]
          : cloudVoices.value.map((voice) => ({
              label: voice.name ? `${voice.name} (${voice.id})` : voice.id,
              value: voice.id,
            })),
      visibleIf: (s) => s.tts_type === "cosyvoice",
    },
    // --- Local SBV2 (localsbv2api) ---
    {
      key: "sbv2_local_voice_id",
      parent: "voice_models",
      label: "本地语音 ID",
      type: "select",
      dynamicOptions: () =>
        installedVoices.value.length === 0
          ? [{ label: "未安装本地模型（请先在 TTS 设置中导入）", value: "" }]
          : installedVoices.value.map((voice) => ({
              label: voice.displayName ? `${voice.displayName} (${voice.id})` : voice.id,
              value: voice.id,
            })),
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_speaker_id",
      parent: "voice_models",
      label: "说话人 ID",
      type: "number",
      step: "1",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_style_id",
      parent: "voice_models",
      label: "风格 ID",
      type: "number",
      step: "1",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_length_scale",
      parent: "voice_models",
      label: "长度缩放 (length_scale)",
      type: "number",
      step: "0.05",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_sdp_ratio",
      parent: "voice_models",
      label: "SDP 噪声比",
      type: "number",
      step: "0.05",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_cloud_fallback_model",
      parent: "voice_models",
      label: "本地 TTS 云端备用模型",
      type: "text",
      placeholder: "本地 TTS 关闭时使用，可留空",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
    {
      key: "sbv2_local_cloud_fallback_speaker_id",
      parent: "voice_models",
      label: "本地 TTS 云端备用说话人 ID",
      type: "text",
      placeholder: "本地 TTS 关闭时使用，可留空",
      visibleIf: (s) => s.tts_type === "localsbv2api",
    },
  ],
};

// --- 音色清单：原版由 TTS 服务提供，这里保持空表等后端 ---
interface VoiceRecord {
  id: string;
  name?: string;
  displayName?: string;
}
const installedVoices = ref<VoiceRecord[]>([]);
const cloudVoices = ref<VoiceRecord[]>([]);

// --- Computed Properties ---

const currentTabConfig = computed(() => schemas[activeTab.value]);

const currentTabFields = computed(() => {
  const fields = currentTabConfig.value || [];
  // 过滤掉当前不可见的字段（visibleIf 为 false），避免留下空 div 占位
  return fields.filter((field) => !field.visibleIf || field.visibleIf(localSettings.value));
});

function ensureVoiceModels(): Record<string, unknown> {
  if (
    !localSettings.value.voice_models ||
    typeof localSettings.value.voice_models !== "object" ||
    Array.isArray(localSettings.value.voice_models)
  ) {
    localSettings.value.voice_models = {};
  }
  return localSettings.value.voice_models as Record<string, unknown>;
}

// --- GSV 六情绪参考语音（自定义 UI；只写草稿，由底部保存统一提交） ---

const GSV_EMO_CATEGORIES = ["吃惊", "开心", "恐惧", "难过", "生气", "中立"] as const;

const gsvEmoEnabled = computed({
  get: () => Boolean(localSettings.value.voice_models?.gsv_emo_enabled),
  set: (val: boolean) => {
    ensureVoiceModels().gsv_emo_enabled = val;
  },
});

const toggleGsvEmo = () => {
  gsvEmoEnabled.value = !gsvEmoEnabled.value;
};

/** 分类参考值的读写入口，与 voice_models 下的平级字段同样只落草稿。 */
const gsvEmoField = (cat: string, key: "gsv_emo_texts" | "gsv_emo_voice_files") =>
  computed({
    get: () => (ensureVoiceModels()[key] as Record<string, string> | undefined)?.[cat] ?? "",
    set: (val: string) => {
      const voiceModels = ensureVoiceModels();
      const map = (voiceModels[key] as Record<string, string> | undefined) ?? {};
      map[cat] = val;
      voiceModels[key] = map;
    },
  });

const fieldModel = (field: FieldSchema) => {
  return computed({
    get: () => {
      let target: Record<string, any>;
      if (field.parent) {
        const parentObj = (localSettings.value as Record<string, any>)[field.parent];
        target =
          parentObj && typeof parentObj === "object"
            ? parentObj
            : ((localSettings.value as Record<string, any>)[field.parent] = {});
      } else if (field.isVoiceModel) {
        target = ensureVoiceModels() as Record<string, any>;
      } else {
        target = localSettings.value as Record<string, any>;
      }
      return target[field.key];
    },
    set: (val: any) => {
      const coerced = field.type === "number" ? Number(val) : val;
      let target: Record<string, any>;
      const raw = localSettings.value as Record<string, any>;
      if (field.parent) {
        if (!raw[field.parent] || typeof raw[field.parent] !== "object") {
          raw[field.parent] = {};
        }
        target = raw[field.parent];
      } else if (field.isVoiceModel) {
        target = ensureVoiceModels() as Record<string, any>;
      } else {
        target = raw;
      }
      target[field.key] = coerced;
    },
  });
};

const clothesList = computed({
  get: () => {
    const raw = localSettings.value as Record<string, any>;
    if (!Array.isArray(raw.clothes)) raw.clothes = [];
    return raw.clothes as Array<{ name: string; prompt: string }>;
  },
  set: (val) => {
    (localSettings.value as Record<string, any>).clothes = val;
  },
});

/**
 * 触摸编辑器 / 差分管理拿到的「服装 + 立绘」列表。
 * 本机没有按服装拆分的资源目录，每件服装都指向该角色的默认立绘。
 */
const draftClothes = computed(() => [
  { title: "default", avatar: headerAvatar.value },
  ...clothesList.value.map((costume) => ({ title: costume.name, avatar: headerAvatar.value })),
]);

// --- 标签编辑 ---

const tagDraft = ref("");
const tagList = computed<string[]>(() => {
  const raw = localSettings.value as Record<string, any>;
  if (!Array.isArray(raw.tags)) raw.tags = [];
  return raw.tags as string[];
});
function addTag() {
  const value = tagDraft.value.trim();
  if (!value || tagList.value.includes(value)) return;
  tagList.value.push(value);
  tagDraft.value = "";
}
function removeTag(index: number) {
  tagList.value.splice(index, 1);
}

// --- Methods ---

const handleClose = async () => {
  if (saving.value) return;
  if (
    hasChanges.value &&
    !(await confirmDialog("放弃角色编辑？", "尚有未保存的配置或资源更改，关闭后将全部放弃。确定关闭吗？"))
  )
    return;
  touchEditorVisible.value = false;
  emit("close");
};

function openCostumeAvatars(name: string) {
  avatarCostume.value = name;
  activeTab.value = "avatars";
}

function onCostumeChanged(change: { oldName: string; newName: string }) {
  if (avatarCostume.value === change.oldName) avatarCostume.value = change.newName;
}

const saveSettings = () => {
  if (saving.value) return;
  saving.value = true;
  try {
    saveRoleSettings(props.roleId, localSettings.value);
    baseline.value = JSON.stringify(localSettings.value);
    notify(`已保存角色配置：${localSettings.value.ai_name || props.roleId}`);
    emit("saved");
  } catch (error) {
    void alertDialog(
      "保存失败",
      `保存失败：${error instanceof Error ? error.message : String(error)}\n草稿已保留，请处理错误后重试；取消会放弃草稿。`,
    );
    return;
  } finally {
    saving.value = false;
  }
  emit("close");
};

/** 子编辑器只应用到弹窗草稿，统一由底部保存提交。 */
async function onTouchRegionsApply(value: Record<string, unknown>) {
  const raw = localSettings.value as Record<string, any>;
  if (Object.keys(value).length) raw.body_part = value;
  else delete raw.body_part;
  touchEditorVisible.value = false;
  notify("触摸区域已应用到草稿，点击底部「保存更改」生效");
}
</script>

<style scoped>
/* Vertical sidebar: thin custom scrollbar (Webkit + Firefox). */
.tab-sidebar-scroll {
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.18) transparent;
}
.tab-sidebar-scroll::-webkit-scrollbar {
  width: 6px;
}
.tab-sidebar-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.tab-sidebar-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.18);
  border-radius: 3px;
}
.tab-sidebar-scroll::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.32);
}

.form-control:focus {
  border-color: #79d9ff;
  background: rgba(0, 0, 0, 0.3);
  box-shadow: 0 0 0 3px rgba(121, 217, 255, 0.2);
}

/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供），
   开关开启态沿用 MenuItem 的补色做法 */
.peer-checked\:border-brand:checked + label {
  border-color: var(--accent-color);
}
.peer-checked\:bg-brand:checked + label {
  background-color: var(--accent-color);
}

/* tokens.css 把 --radius-lg 覆盖成 22px（全局 css 禁改），这里锁回 Tailwind 默认值，
   让照抄原版的 rounded-lg=8px / rounded-xl=12px 生效 */
.lc-reset-radius {
  --radius-sm: 0.25rem;
  --radius-lg: 0.5rem;
  --radius: 0.25rem;
}
</style>
