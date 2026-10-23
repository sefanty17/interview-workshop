<template>
  <MenuPage>
    <div class="flex h-[85dvh] max-h-full min-h-0 w-full flex-1 flex-col overflow-hidden rounded-lg bg-white/10 p-0 md:p-4">
      <!-- 顶部 Tab 切换栏 -->
      <div class="mb-5 flex shrink-0 flex-wrap items-center justify-between gap-2 select-none">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="rounded-md px-4 py-1.5 text-sm font-medium transition-all duration-200"
          :class="
            advanceTab === tab.key
              ? 'bg-brand text-white shadow-[0_2px_8px_rgba(0,0,0,0.3)]'
              : 'text-white/60 hover:text-white/80'
          "
          @click="advanceTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- ====== 主菜单 ====== -->
      <div v-if="advanceTab === 'menu'" class="min-h-0 flex-1 overflow-y-auto">
        <SettingsAdvanceMenu @navigate="advanceTab = $event" />
      </div>

      <!-- ====== 大模型管理 ====== -->
      <div v-else-if="advanceTab === 'llm'" class="min-h-0 flex-1 overflow-y-auto">
        <SettingsLlmProviders />
      </div>

      <!-- ====== TTS 设置 ====== -->
      <div v-else-if="advanceTab === 'tts'" class="min-h-0 flex-1 overflow-y-auto">
        <SettingsTts />
      </div>

      <!-- ====== 语音识别 ====== -->
      <div v-else-if="advanceTab === 'asr'" class="min-h-0 flex-1 overflow-y-auto">
        <SettingsAsr />
      </div>

      <!-- ====== 工具配置 ====== -->
      <div v-else-if="advanceTab === 'tools'" class="min-h-0 flex-1 overflow-hidden">
        <SettingsTools />
      </div>

      <!-- ====== 投屏设置 ====== -->
      <div v-else-if="advanceTab === 'cast'" class="min-h-0 flex-1 overflow-hidden">
        <SettingsCast />
      </div>

      <!-- ====== 其他高级设置 ====== -->
      <div v-else-if="advanceTab === 'other'" class="min-h-0 flex-1">
        <SettingsAdvanceOther />
      </div>

      <!-- ====== 永久记忆调试 ====== -->
      <div v-else-if="advanceTab === 'memory'" class="min-h-0 flex-1 overflow-y-auto">
        <SettingsMemoryDebug />
      </div>

      <!-- ====== 尚未接入的模块：保留真实跳转与返回 ====== -->
      <div v-else class="min-h-0 flex-1 overflow-y-auto">
        <div class="flex h-full min-h-full flex-col items-center justify-center px-6 py-10 text-center">
          <div
            class="flex w-full max-w-[560px] flex-col items-center gap-5 rounded-xl border border-dashed border-white/15 bg-black/20 px-8 py-12"
          >
            <span
              class="flex h-16 w-16 items-center justify-center rounded-2xl border border-white/15 bg-white/10 text-brand"
            >
              <component :is="currentPage.icon" :size="30" />
            </span>

            <div class="flex flex-wrap items-center justify-center gap-2.5">
              <h3 class="m-0 text-xl font-semibold text-white">{{ currentPage.title }}</h3>
              <span
                class="rounded-full border border-[rgba(121,217,255,0.35)] bg-[rgba(121,217,255,0.12)] px-2.5 py-0.5 text-xs font-semibold text-brand"
              >
                即将接入
              </span>
            </div>

            <p class="m-0 max-w-[440px] text-sm leading-relaxed text-white/55">
              {{ currentPage.desc }}
            </p>

            <div class="mt-1 w-full max-w-[300px]">
              <Button type="big" icon="advance" :icon_size="18" @click="advanceTab = 'menu'">
                返回高级设置
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </MenuPage>
</template>

<script setup lang="ts">
import { computed, ref, type Component } from "vue";
import { AudioLines, Cast, Cpu, Database, Mic, Wrench } from "@lucide/vue";
import { MenuPage } from "../../ui";
import { Button } from "../../base";
import SettingsAdvanceMenu from "./SettingsAdvanceMenu.vue";
import SettingsAdvanceOther from "./SettingsAdvanceOther.vue";
import SettingsLlmProviders from "./SettingsLlmProviders.vue";
import SettingsAsr from "./SettingsAsr.vue";
import SettingsTts from "./SettingsTts.vue";
import SettingsTools from "./SettingsTools.vue";
import SettingsCast from "./SettingsCast.vue";
import SettingsMemoryDebug from "./SettingsMemoryDebug.vue";

type AdvanceTab = "menu" | "llm" | "tts" | "asr" | "tools" | "cast" | "other" | "memory";

const TABS: { key: AdvanceTab; label: string }[] = [
  { key: "menu", label: "主菜单" },
  { key: "llm", label: "大模型管理" },
  { key: "tts", label: "TTS 设置" },
  { key: "asr", label: "语音识别" },
  { key: "tools", label: "工具配置" },
  { key: "cast", label: "投屏设置" },
  { key: "other", label: "其他高级设置" },
];

/** 子页未接入时的说明页内容（文案取自语言包 advance.menu.*） */
const PAGES: Record<
  Exclude<AdvanceTab, "menu" | "other">,
  { title: string; desc: string; icon: Component }
> = {
  llm: {
    title: "大模型管理",
    desc: "配置和管理 AI 大模型提供商，添加、编辑、测试模型连接参数",
    icon: Cpu,
  },
  tts: {
    title: "TTS 设置",
    desc: "下载并管理 DeBERTa 与面试官语音模型，离线使用本地 TTS 引擎；也可使用云端语音克隆（CosyVoice）创建专属音色",
    icon: AudioLines,
  },
  asr: {
    title: "语音识别",
    desc: "配置阿里云语音识别、发送方式与麦克风监听",
    icon: Mic,
  },
  tools: {
    title: "工具配置",
    desc: "配置 AI 可调用的工具，比如网页搜索（联网查资料）",
    icon: Wrench,
  },
  cast: {
    title: "投屏设置",
    desc: "将面试画面串流到局域网设备，配置端口、分辨率与画面调参",
    icon: Cast,
  },
  memory: {
    title: "永久记忆调试",
    desc: "查看面试官当前的记忆库内容与真实上下文，用于排查记忆压缩与注入问题",
    icon: Database,
  },
};

const advanceTab = ref<AdvanceTab>("menu");

const currentPage = computed(
  () => PAGES[advanceTab.value as Exclude<AdvanceTab, "menu" | "other">] ?? PAGES.llm,
);

/** 与其他设置页一致，供 SettingsPanel 转发窄屏菜单指令 */
function addMoreMenu(): void {
  /* 高级设置页没有二级折叠菜单，保留同名接口供父级调用 */
}

defineExpose({ addMoreMenu });
</script>
