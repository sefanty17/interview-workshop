<template>
  <div class="grid grid-cols-1 items-stretch gap-5 p-2 md:grid-cols-2 lg:grid-cols-3">
    <!-- 大模型管理 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'llm')">
      <MenuItem title="大模型管理" size="large">
        <template #header>
          <Cpu :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          配置和管理 AI 大模型提供商，添加、编辑、测试模型连接参数
        </p>
        <Button type="big" icon="advance" :icon_size="18">进入大模型管理界面</Button>
      </MenuItem>
    </div>

    <!-- 本地 TTS -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'tts')">
      <MenuItem title="TTS 设置" size="large">
        <template #header>
          <AudioLines :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          下载并管理 DeBERTa 与面试官语音模型，离线使用本地 TTS 引擎；也可使用云端语音克隆（CosyVoice）创建专属音色
        </p>
        <Button type="big" icon="mic" :icon_size="18">进入 TTS 设置界面</Button>
      </MenuItem>
    </div>

    <!-- 语音识别 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'asr')">
      <MenuItem title="语音识别" size="large">
        <template #header>
          <Mic :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          配置阿里云语音识别、发送方式与麦克风监听
        </p>
        <Button type="big" icon="mic" :icon_size="18">进入语音识别界面</Button>
      </MenuItem>
    </div>

    <!-- 其他高级设置 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'other')">
      <MenuItem title="其他高级设置" size="large">
        <template #header>
          <SlidersHorizontal :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          调整系统环境配置、提示词模板、对话参数等高级选项（重启后生效）
        </p>
        <Button type="big" icon="setting" :icon_size="18">进入其他高级设置界面</Button>
      </MenuItem>
    </div>

    <!-- 工具配置 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'tools')">
      <MenuItem title="工具配置" size="large">
        <template #header>
          <Wrench :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          配置 AI 可调用的工具，比如网页搜索（联网查资料）
        </p>
        <Button type="big" icon="setting" :icon_size="18">进入工具配置界面</Button>
      </MenuItem>
    </div>

    <!-- 投屏设置 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'cast')">
      <MenuItem title="投屏设置" size="large">
        <template #header>
          <Cast :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          将面试画面串流到局域网设备，配置端口、分辨率与画面调参
        </p>
        <Button type="big" icon="advance" :icon_size="18">进入投屏设置界面</Button>
      </MenuItem>
    </div>

    <!-- 永久记忆调试 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="emit('navigate', 'memory')">
      <MenuItem title="永久记忆调试" size="large">
        <template #header>
          <Database :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          查看面试官当前的记忆库内容与真实上下文，用于排查记忆压缩与注入问题
        </p>
        <Button type="big" icon="advance" :icon_size="18">进入记忆调试界面</Button>
      </MenuItem>
    </div>

    <!-- 界面语言 -->
    <div class="h-full transition-all duration-300">
      <MenuItem title="界面语言" size="large">
        <template #header>
          <Languages :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          切换应用界面显示语言，立即生效并自动保存
        </p>
        <select
          :value="language"
          class="w-full cursor-pointer rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 transition-all duration-200 hover:border-white/30 hover:text-white focus:border-[rgba(121,217,255,0.6)] focus:outline-none"
          style="color-scheme: dark"
          aria-label="界面语言"
          @change="onLanguageChange(($event.target as HTMLSelectElement).value)"
        >
          <option v-for="opt in LANGUAGES" :key="opt" :value="opt" class="bg-slate-800 text-white">
            {{ opt }}
          </option>
        </select>
      </MenuItem>
    </div>

    <!-- 面试印象系统 -->
    <div class="h-full transition-all duration-300">
      <MenuItem title="面试印象系统" size="large">
        <template #header>
          <Heart :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          面试印象系统总开关（保存后立即生效）；心跳与波浪动画开关在「其他高级设置 → 面试印象」中
        </p>
        <button
          type="button"
          role="switch"
          :aria-checked="affectionOn"
          aria-label="启用面试印象系统"
          class="inline-flex cursor-pointer items-center gap-2 border-none bg-transparent p-0 text-sm text-white select-none"
          style="text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3)"
          @click="onAffectionToggle"
        >
          <span
            class="relative inline-block h-6.5 w-12.5 shrink-0 rounded-[13px] transition-all duration-300 ease-in-out"
            :class="
              affectionOn
                ? 'border-brand bg-[rgba(121,217,255,0.3)] shadow-[0_0_10px_rgba(121,217,255,0.3)]'
                : 'border-white/30 bg-white/20'
            "
          >
            <span
              class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all duration-300 ease-in-out"
              :class="
                affectionOn
                  ? 'left-6.5 bg-linear-to-br from-brand to-[#64b5f6] shadow-[0_3px_8px_rgba(121,217,255,0.4),0_1px_3px_rgba(0,0,0,0.2)]'
                  : 'left-1 bg-linear-to-br from-white to-[#f0f0f0] shadow-[0_2px_6px_rgba(0,0,0,0.3),0_1px_2px_rgba(0,0,0,0.1)]'
              "
            ></span>
          </span>
          <span class="min-w-0 flex-1 text-left">启用面试印象系统</span>
        </button>
      </MenuItem>
    </div>

    <!-- 内置 TTS 教程 -->
    <div class="h-full cursor-pointer transition-all duration-300" @click="openGuide">
      <MenuItem title="内置 TTS 教程" size="large">
        <template #header>
          <BookOpen :size="20" />
        </template>
        <p class="mb-3 min-h-17 text-sm leading-relaxed text-white/50">
          查看内置 TTS 使用教程（下载模型、导入语音、切换设备等）
        </p>
        <Button type="big" icon="advance" :icon_size="18">打开教程页面</Button>
      </MenuItem>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import {
  AudioLines,
  BookOpen,
  Cast,
  Cpu,
  Database,
  Heart,
  Languages,
  Mic,
  SlidersHorizontal,
  Wrench,
} from "@lucide/vue";
import { MenuItem } from "../../ui";
import { Button } from "../../base";
import { notify } from "../../../store/appStore";
import { alertDialog, confirmDialog } from "../../../store/dialogStore";

/** 界面语言：原版走 i18n 的 SUPPORTED_LOCALES，本项目只提供中文界面，选择结果仍会持久化 */
const LANGUAGES = ["简体中文", "English", "日本語"] as const;
const LANGUAGE_KEY = "interview-advance-language";

/** 面试印象系统总开关，与「其他高级设置 → 面试印象 → 通用」的 affection.enabled 是同一项 */
const AFFECTION_KEY = "interview-affection-master";

const language = ref<string>(
  LANGUAGES.includes(localStorage.getItem(LANGUAGE_KEY) as (typeof LANGUAGES)[number])
    ? (localStorage.getItem(LANGUAGE_KEY) as string)
    : LANGUAGES[0],
);

const affectionOn = ref(localStorage.getItem(AFFECTION_KEY) !== "0");

function onLanguageChange(next: string): void {
  language.value = next;
  localStorage.setItem(LANGUAGE_KEY, next);
  notify(`界面语言已切换为 ${next}`);
}

async function onAffectionToggle(): Promise<void> {
  const next = !affectionOn.value;
  const ok = await confirmDialog(
    "切换面试印象系统",
    "确定要切换面试印象系统吗？关闭后将立即停止面试印象评估并隐藏面试印象面板。",
  );
  if (!ok) return;
  affectionOn.value = next;
  localStorage.setItem(AFFECTION_KEY, next ? "1" : "0");
  notify(next ? "面试印象系统已启用" : "面试印象系统已关闭");
}

const emit = defineEmits<{
  navigate: [tab: "llm" | "tts" | "asr" | "other" | "tools" | "cast" | "memory"];
}>();

function openGuide(): void {
  void alertDialog(
    "内置 TTS 教程",
    [
      "1. 在「TTS 设置」中下载 DeBERTa 情感模型与面试官语音模型，并放到 data/models/ 目录；",
      "2. 导入音色样本（wav / mp3，建议 5~15 秒干声），保存后即可在面试官详情里选择；",
      "3. 在「语音识别 → 输出设备」中确认播放设备，Android 与桌面端的设备名不同；",
      "4. 试听失败时回到「其他高级设置 → TTS 配置」强制重新连接一次 TTS 服务。",
    ].join("\n"),
  );
}
</script>

<style scoped>
/* 统一卡片尺寸:菜单卡片等高(撑满 grid 行)、描述区对齐、按钮贴底 */
:deep(.menu-item) {
  height: 100%;
  display: flex;
  flex-direction: column;
}

:deep(.menu-item .content) {
  flex: 1;
  display: flex;
  flex-direction: column;
}

:deep(.menu-item .content > :last-child) {
  margin-top: auto;
}
</style>
