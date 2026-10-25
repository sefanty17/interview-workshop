<script setup lang="ts">
/**
 * 新建弹窗（对照原版 `modals/EditorModals.vue`，322 行）。
 *
 * 只保留 script / chapter 两个分支。原版还有 `character`（新建/编辑角色）与
 * `importChar`（从磁盘导入角色目录）两个分支，本项目**不做**，不是漏做：
 * - 原版那两个分支由 CharactersTab emit `new-character` / `import-character` 触发；
 * - 本项目 `tabs/CharactersTab.vue` 自己走 `/api/editor/characters` 完成同样的
 *   新建 / 编辑 / 删除（后端 CharacterController 有 POST/PUT/DELETE），
 *   能力等价，只是不经过这个弹窗；
 * - `importChar` 从磁盘导目录是 Tauri 能力，网页版本就没有，两版都不做。
 *
 * 剧本字段也与原版不同：原版是 `folderName` + `isAdventure` + `boundCharacterFolder`
 * （galgame 的冒险模式），本项目是 `code` + `scriptName` + 岗位 / 面试官 / 难度 / 公司风格
 * —— 这是领域差异，不是漏字段。
 */
import { reactive, computed } from "vue";
import { createChapter, createScript } from "../../../store/scriptEditor";
import { jobs } from "../../../data/mock";
import { interviewers } from "../../../data/interviewers";

const props = defineProps<{
  modal: "script" | "chapter" | null;
}>();
const emit = defineEmits<{
  "update:modal": [value: "script" | "chapter" | null];
}>();

const close = () => emit("update:modal", null);

const MODAL_TITLES: Record<string, string> = {
  script: "新建剧本",
  chapter: "新建章节",
};
const modalTitle = computed(() => MODAL_TITLES[props.modal ?? ""] ?? "");

const scriptForm = reactive({
  code: "",
  scriptName: "",
  description: "",
  jobCode: "java",
  interviewerCode: "strict",
  level: "校招",
  companyStyle: "",
});
const chapterForm = reactive({ id: "", name: "" });

/**
 * 剧本编码留空时从剧本名兜底生成一个。
 *
 * <p>后端要求「小写字母开头，仅含小写字母/数字/_/-」，空串直接 400。
 * 中文剧本名转不出合法字符，这种情况退成时间戳前缀，保证一定能建出来 ——
 * 编码只是唯一标识，作者真正关心的是剧本名。
 */
function fallbackCode(scriptName: string): string {
  const ascii = scriptName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  if (ascii && /^[a-z]/.test(ascii)) return ascii;
  return `script-${Date.now().toString(36)}`;
}

const confirmModal = async () => {
  const which = props.modal;
  if (which === "script") {
    const code = scriptForm.code.trim() || fallbackCode(scriptForm.scriptName);
    // 成功才关弹窗、才重置表单。原来是「先关再提交」：后端一拒（比如编码撞了已存在的），
    // 弹窗已经没了、表单也清了，作者看到的就是「点了新建，弹个错，什么都没发生」。
    const ok = await createScript({ ...scriptForm, code });
    if (!ok) return;
    emit("update:modal", null);
    Object.assign(scriptForm, {
      code: "",
      scriptName: "",
      description: "",
      jobCode: "java",
      interviewerCode: "strict",
      level: "校招",
      companyStyle: "",
    });
  } else if (which === "chapter") {
    emit("update:modal", null);
    await createChapter(chapterForm.id, chapterForm.name);
    chapterForm.id = "";
    chapterForm.name = "";
  }
};
</script>

<template>
  <!-- Teleport 到 #app 而非 body：body 下浮层脱离 #app 的整体缩放作用域，
       缩放后与编辑器主体比例失调 -->
  <Teleport to="#app">
    <Transition
      enter-active-class="transition-opacity duration-200 ease"
      leave-active-class="transition-opacity duration-200 ease"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <div
        v-if="modal"
        class="modal-mask fixed inset-0 z-[9999] flex items-center justify-center bg-black/55 p-4 backdrop-blur-md"
        @click.self="close"
      >
        <!-- 主弹窗 -->
        <div
          class="max-h-[86dvh] w-[min(440px,92vw)] overflow-y-auto rounded-xl border border-white/12.5 bg-[rgba(12,20,30,0.86)] px-[18px] py-4 pb-[18px] shadow-[0_8px_32px_rgba(0,0,0,0.45),inset_0_1px_1px_rgba(255,255,255,0.06)] backdrop-blur-lg backdrop-saturate-[1.4]"
        >
          <div
            class="mb-4 flex items-center gap-2 border-b-2 border-brand pb-2"
          >
            <h4 class="font-semibold text-white">{{ modalTitle }}</h4>
            <button
              class="ml-auto text-white/50 transition-all duration-300 hover:rotate-90 hover:text-brand"
              @click="close"
            >
              ✕
            </button>
          </div>

          <template v-if="modal === 'script'">
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >剧本编码</label
              >
              <input v-model="scriptForm.code" class="glass-input" placeholder="java-friendly" />
              <p class="mt-[0.3rem] text-[0.72rem] leading-[1.7] text-white/40">
                小写字母开头，可含数字、_ 与 -；作为剧本唯一标识，创建后不改
              </p>
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >剧本名</label
              >
              <input
                v-model="scriptForm.scriptName"
                class="glass-input"
                placeholder="Java 后端 · 友好一轮"
              />
              <p class="mt-[0.3rem] text-[0.72rem] leading-[1.7] text-white/40">
                显示在剧本列表与面包屑上
              </p>
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >简介</label
              >
              <textarea v-model="scriptForm.description" class="glass-input min-h-16"></textarea>
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >岗位</label
              >
              <select v-model="scriptForm.jobCode" class="glass-input">
                <option v-for="j in jobs" :key="j.id" :value="j.id">{{ j.name }}</option>
              </select>
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >面试官</label
              >
              <select v-model="scriptForm.interviewerCode" class="glass-input">
                <option v-for="p in interviewers" :key="p.code" :value="p.code">
                  {{ p.name }}
                </option>
              </select>
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >难度</label
              >
              <input v-model="scriptForm.level" class="glass-input" placeholder="校招 / 社招 / 实习" />
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >公司风格</label
              >
              <input
                v-model="scriptForm.companyStyle"
                class="glass-input"
                placeholder="一线互联网大厂"
              />
            </div>
          </template>

          <template v-else-if="modal === 'chapter'">
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >章节文件名</label
              >
              <input v-model="chapterForm.id" class="glass-input" placeholder="main2，或 Intro/intro2 放进子目录" />
            </div>
            <div class="mb-4">
              <label class="inline-flex items-center text-[0.9rem] font-medium text-brand"
                >显示名</label
              >
              <input v-model="chapterForm.name" class="glass-input" placeholder="例如：2 樱花的公园" />
              <p class="mt-[0.3rem] text-[0.72rem] leading-[1.7] text-white/40">
                新章节自带一条「章节结束」，免得一保存就报缺少结束事件。
              </p>
            </div>
          </template>

          <div class="mt-5 flex justify-end gap-2">
            <button
              class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/[0.12] hover:enabled:text-white disabled:cursor-not-allowed disabled:opacity-40"
              @click="close"
            >
              取消
            </button>
            <button
              class="inline-flex items-center gap-1 rounded-lg border border-[rgba(121,217,255,0.45)] bg-[rgba(121,217,255,0.14)] px-3 py-[0.3rem] text-[0.8rem] whitespace-nowrap text-brand transition-all duration-200 hover:bg-[rgba(121,217,255,0.24)] disabled:cursor-not-allowed disabled:opacity-40"
              @click="confirmModal"
            >
              确定
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
