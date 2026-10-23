<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { editorShortcuts } from "../../../store/editorShortcuts";
import {
  DEFAULT_SHORTCUTS,
  SHORTCUT_ACTIONS,
  bindingsEqual,
  captureFromEvent,
  formatBinding,
  type ShortcutAction,
  type ShortcutBinding,
} from "../../../utils/shortcuts";

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ close: [] }>();

/** 动作 → 描述文案（原版走 i18n 的 shortcutHelp 段，本项目中文直写） */
const DESC: Record<ShortcutAction, string> = {
  save: "立刻保存（平时是改完自动存，这条是给不放心的人的）",
  undo: "撤销",
  redo: "恢复刚才撤销的操作（Ctrl+Y 也行）",
  copyEvent: "复制选中的事件",
  playtest: "从当前位置试玩",
  deleteEvent: "删除选中的事件",
  moveCursor: "在事件之间移动光标",
  moveEvent: "把选中的事件上移 / 下移",
  esc: "结束试玩 / 返回上一层",
  shortcutHelp: "打开这张表",
  expandProps: "展开/收起事件属性栏",
};

// ---- 捕获模式：点击某行后监听下一次 keydown 作为新键位 ----
const recording = ref<ShortcutAction | null>(null);
const conflictName = ref<string>("");
const singleKeyBlocked = ref(false);

const onCaptureKey = (e: KeyboardEvent) => {
  if (!recording.value) return;
  e.preventDefault();
  e.stopPropagation();
  const result = captureFromEvent(e);
  // 纯修饰键：组合键进行中，继续等待（不取消、不绑定）
  if (result.kind === "ignore") return;
  // Esc 取消捕获
  if (result.kind === "cancel") {
    recording.value = null;
    conflictName.value = "";
    singleKeyBlocked.value = false;
    return;
  }
  // 无修饰的普通字符键：拒绝并提示，保持捕获让用户重新按
  if (result.kind === "blocked") {
    singleKeyBlocked.value = true;
    return;
  }
  const binding = result.binding;
  singleKeyBlocked.value = false;
  // 冲突检测：其他动作已占用同一组合则拒绝
  const clash = SHORTCUT_ACTIONS.find(
    (a) => a !== recording.value && bindingsEqual(editorShortcuts.shortcuts[a], binding),
  );
  if (clash) {
    conflictName.value = DESC[clash];
    return;
  }
  editorShortcuts.update(`shortcuts.${recording.value}`, binding);
  recording.value = null;
  conflictName.value = "";
};

watch(
  () => props.visible,
  (v) => {
    // 面板关闭时清掉捕获态，避免残留监听
    if (!v) {
      recording.value = null;
      conflictName.value = "";
      singleKeyBlocked.value = false;
    }
  },
);

onUnmounted(() => {
  recording.value = null;
});

// 面板打开且处于捕获模式时挂全局监听
watch(recording, (v) => {
  if (v) window.addEventListener("keydown", onCaptureKey, true);
  else window.removeEventListener("keydown", onCaptureKey, true);
});

const restoreDefaults = () => {
  editorShortcuts.update("shortcuts", { ...DEFAULT_SHORTCUTS });
};

const rowBinding = (a: ShortcutAction) => editorShortcuts.shortcuts[a] as ShortcutBinding;
</script>

<template>
  <!-- Teleport 到 #app 而非 body：body 下浮层脱离 #app 的整体缩放作用域 -->
  <Teleport to="#app">
    <Transition
      enter-active-class="transition-opacity duration-200 ease"
      leave-active-class="transition-opacity duration-200 ease"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <div
        v-if="visible"
        class="modal-mask fixed inset-0 z-9999 flex items-center justify-center bg-black/55 p-4 backdrop-blur-md"
        @click.self="emit('close')"
      >
        <div
          class="max-h-[86dvh] w-[min(460px,92vw)] overflow-y-auto rounded-xl border border-white/12.5 bg-[rgba(12,20,30,0.86)] px-4.5 py-4 pb-4.5 shadow-[0_8px_32px_rgba(0,0,0,0.45),inset_0_1px_1px_rgba(255,255,255,0.06)] backdrop-blur-lg backdrop-saturate-[1.4]"
        >
          <div
            class="mb-4 flex items-center gap-2 border-b-2 border-brand pb-2"
          >
            <h4 class="font-semibold text-white">快捷键</h4>
            <button
              class="ml-auto text-white/50 transition-all duration-300 hover:rotate-90 hover:text-brand"
              @click="emit('close')"
            >
              ✕
            </button>
          </div>

          <p class="mb-3 text-[0.72rem] leading-[1.7] text-white/40">
            点击某一行即可重新绑定它的快捷键；按 Esc 取消。默认键位不含 Command（⌘），macOS
            可自行绑定；Ctrl+E 展开/折叠属性栏。
          </p>

          <div
            v-for="a in SHORTCUT_ACTIONS"
            :key="a"
            class="flex items-center gap-3 border-t border-white/6 py-1.5 text-[0.78rem] leading-[1.8] text-white/70 first:border-t-0"
            :class="
              recording === a ? 'text-brand' : 'cursor-pointer hover:text-white'
            "
            @click="recording = a"
          >
            <kbd
              class="min-w-37 shrink-0 rounded-[5px] border border-white/[0.14] bg-white/5 px-2 py-0.5 font-mono text-[0.7rem] text-brand"
            >
              {{ recording === a ? "按下新快捷键…（Esc 取消）" : formatBinding(rowBinding(a)) }}
            </kbd>
            <span class="min-w-0 flex-1">{{ DESC[a] }}</span>
            <span
              v-if="recording === a && conflictName"
              class="shrink-0 text-[0.68rem] text-yellow-300"
            >
              已被「{{ conflictName }}」占用
            </span>
            <span
              v-else-if="recording === a && singleKeyBlocked"
              class="shrink-0 text-[0.68rem] text-yellow-300"
            >
              不能只绑定普通字符键，请搭配 Ctrl/Alt/Shift/⌘
            </span>
          </div>

          <div class="mt-4 flex justify-end">
            <button
              class="inline-flex items-center gap-1 rounded-lg border border-white/10 bg-white/6 px-3 py-[0.3rem] text-[0.78rem] whitespace-nowrap text-white/70 transition-all duration-200 hover:enabled:bg-white/12 hover:enabled:text-white"
              @click="restoreDefaults"
            >
              恢复默认
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
