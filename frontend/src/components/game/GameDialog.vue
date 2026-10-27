<template>
  <div
    class="game-dialog relative z-2 flex w-full scrollbar-thin [scrollbar-color:var(--accent-color)_transparent] justify-center p-3.75 transition-all duration-200 ease-[cubic-bezier(0.25,0.46,0.45,0.94)] before:pointer-events-none before:absolute before:-top-10 before:right-0 before:left-0 before:h-10 before:bg-linear-to-b before:from-transparent before:via-[rgba(0,14,39,0.3)] before:to-[rgba(0,14,39,0.6)] before:content-['']"
    :class="{ 'max-h-[40dvh]': !roomStore.isNarrowScreen }"
  >
    <div :style="{ width: roomStore.containerWidth + '%' }" class="relative">
      <div class="overflow-y-auto">
        <!-- 标题栏 -->
        <div class="mb-2 flex items-baseline">
          <!-- 角色名 + 副标题：整体切换，旧标题向上滑出、新标题从上方滑入 -->
          <Transition name="title-slide">
            <div
              :key="titleSubtitleKey"
              v-show="!isNarration"
              class="flex items-baseline"
              :class="{ 'min-w-0': roomStore.isNarrowScreen }"
            >
              <div
                class="mr-3.75 font-[inherit] text-2xl font-bold text-shadow-[inherit]"
                :class="{
                  'min-w-0 overflow-hidden text-ellipsis whitespace-nowrap':
                    roomStore.isNarrowScreen,
                }"
              >
                <div id="character">{{ activeTitle }}</div>
              </div>
              <div
                v-show="!roomStore.isNarrowScreen && activeSubtitle"
                class="font-[inherit] text-xl font-bold text-[#6eb4ff] text-shadow-[inherit]"
              >
                <div id="character-sub">{{ activeSubtitle }}</div>
              </div>
            </div>
          </Transition>

          <!-- 情绪标签（旁白句 / 玩家展示行不显示） -->
          <div
            v-show="!isNarration && !isPlayerLine"
            class="relative mx-4 shrink-0 font-[inherit] text-xl font-bold whitespace-nowrap text-[#ff77dd] text-shadow-[inherit]"
          >
            <Transition name="emotion-slide">
              <div id="character-emotion" :key="roomStore.showCharacterEmotion" class="inline-block">
                {{ roomStore.showCharacterEmotion }}
              </div>
            </Transition>
          </div>

          <!-- 操作按钮组 -->
          <div class="ml-auto flex min-w-0 items-baseline">
            <div
              class="custom-scroll overflow-x-auto"
              :class="roomStore.isNarrowScreen ? 'min-w-0 flex-1' : 'shrink-0'"
            >
              <div class="flex whitespace-nowrap">
                <Button
                  type="nav"
                  icon="background"
                  title="场景设置（B5 接入）"
                  @click="sceneTip"
                ></Button>
                <Button type="nav" icon="hand" title="触摸模式（待接入）" @click="touchTip"></Button>
                <Button
                  type="nav"
                  icon="history"
                  title="历史对话"
                  @click="emit('history')"
                ></Button>
                <Button
                  type="nav"
                  icon="mic"
                  :title="micTitle"
                  :class="phase === 'recording' ? 'text-cyan-300 animate-pulse' : ''"
                  @pointerdown="pttDown"
                  @pointerup="pttUp"
                  @pointerleave="pttUp"
                ></Button>
                <ScreenshotButton @start="screenshotTip" />
                <Button type="nav" icon="upload" title="快捷存档" @click="quickSave"></Button>
                <Button type="nav" icon="download" title="快捷读档" @click="quickLoad"></Button>
                <Button type="nav" icon="close" title="结束本场面试" @click="emit('finish')"></Button>
              </div>
            </div>
          </div>
        </div>

        <!-- 分割线：青蓝色发光线条，亮段从左向右流动 -->
        <div class="dialog-divider-glow my-1.5"></div>

        <!-- 输入区 -->
        <div
          class="my-1.25 flex min-h-10 w-full resize-none flex-col border-none bg-transparent text-xl font-bold whitespace-pre-line text-white transition-all duration-300 outline-none"
        >
          <!-- AI 回应显示区（仅回应状态可见，逐字符淡入+上浮）。
               两个子容器：台词区白字 + 动作区灰字，各由一个打字机并行打字 -->
          <div
            v-show="roomStore.state === 'responding'"
            ref="inlineDisplayRef"
            tabindex="0"
            class="response-display my-1.25 max-h-[50dvh] min-h-30 flex-1 resize-none overflow-y-auto border-none bg-transparent font-[inherit] text-xl font-bold break-all whitespace-pre-line outline-none text-shadow-[inherit]"
            @keydown.enter.exact.prevent="sendOrContinue"
            @click="sendOrContinue"
          >
            <!-- 台词区：白字（旁白句改灰字斜体居中） -->
            <div
              ref="dialogueLineRef"
              class="whitespace-pre-line"
              :class="isNarration ? 'text-center text-white/55 italic' : 'text-white'"
            ></div>
            <!-- 动作区：灰字（独立打字机，与台词并行） -->
            <div ref="motionLineRef" class="whitespace-pre-line text-[#9ca3af]"></div>
          </div>

          <!-- 输入框（非回应状态：输入/思考） -->
          <textarea
            v-show="roomStore.state !== 'responding'"
            id="inputMessage"
            ref="textareaRef"
            class="my-1.25 max-h-[50dvh] min-h-30 flex-1 resize-none border-none bg-transparent font-[inherit] text-[max(1.25rem,16px)] font-bold transition-all duration-300 outline-none text-shadow-[inherit] placeholder:text-white/50 placeholder:shadow-none"
            :placeholder="placeholderText"
            v-model="inputMessage"
            @keydown.enter.exact.prevent="sendOrContinue"
            :readonly="roomStore.state !== 'input'"
          ></textarea>
        </div>
      </div>

      <!-- 发送按钮（内容区右侧外部） -->
      <button
        id="sendButton"
        class="absolute right-0 bottom-0 translate-x-full cursor-pointer rounded-[5px] border-none bg-transparent px-2 py-2 font-[inherit] text-sm font-bold text-[#04bcff] transition-all duration-300 text-shadow-[inherit] hover:bg-transparent hover:text-[rgba(136,255,251,0.827)] disabled:cursor-not-allowed disabled:bg-[#333] disabled:opacity-70"
        :disabled="roomStore.state === 'thinking'"
        @click="sendOrContinue"
      >
        ▼
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Button } from "../base";
import ScreenshotButton from "./ScreenshotButton.vue";
import { alertDialog } from "../../store/dialogStore";
import { notify, persist, store as appStore } from "../../store/appStore";
import { advance, isShowableLine, roomStore, type RoomLine } from "../../store/roomStore";
import {
  registerAsrInputBridge,
  setChatMounted,
  useAsrAutoSend,
  useAsrInput,
} from "../../composables/asr";
import { pttDown, pttUp } from "../../composables/asr/ptt";
import { asrStore } from "../../store/asrStore";

const emit = defineEmits<{
  /** 用户在输入态按 Enter / ▼，参数为已 trim 的文本 */
  send: [text: string];
  /** 关闭按钮：结束本场面试 */
  finish: [];
  /** 历史按钮 */
  history: [];
}>();

const textareaRef = ref<HTMLTextAreaElement | null>(null);
const inlineDisplayRef = ref<HTMLDivElement | null>(null);
const dialogueLineRef = ref<HTMLDivElement | null>(null);
const motionLineRef = ref<HTMLDivElement | null>(null);

const inputMessage = ref("");

const placeholderText = computed(() => {
  if (roomStore.state === "thinking") return "思考中…";
  if (roomStore.choices.length) {
    // 剧本 script:choice：allowFree 时输入框仍可用（文本由后端转投给 choice 匹配）
    return roomStore.allowFree
      ? "点选上方选项，或直接输入你的回答…"
      : "请先在上方选择一个选项";
  }
  return roomStore.inputHint ? roomStore.inputHint : "输入你的回答…（Enter 发送，Shift + Enter 换行）";
});

/** 当前正在显示的那一句（旁白句要换样式并隐藏角色名/情绪标签） */
const currentLine = computed<RoomLine | undefined>(() =>
  roomStore.state === "responding" ? roomStore.lines[roomStore.cursor] : undefined,
);
const isNarration = computed(() => currentLine.value?.narration === true);
/** 剧本写死的玩家展示行（script:player）：标题取 displayName，缺省「我」 */
const isPlayerLine = computed(() => currentLine.value?.player === true);
const activeTitle = computed(() =>
  isPlayerLine.value ? currentLine.value?.displayName || "我" : roomStore.showCharacterTitle,
);
const activeSubtitle = computed(() =>
  isPlayerLine.value ? "" : roomStore.showCharacterSubtitle,
);

/** 标题栏 key：角色名/副标题任一变化时整体滑出滑入 */
const titleSubtitleKey = computed(() => `${activeTitle.value}|${activeSubtitle.value}`);

// ─────────────────────────── 打字机 ───────────────────────────
/** 原版 TypeWriter：speed=80 → 延迟 200-((speed-1)/99)*190 + [0, speed*0.2] ms ≈ 48~64ms */
const TYPE_SPEED = 80;
function charDelay(): number {
  return 200 - ((TYPE_SPEED - 1) / 99) * (200 - 10) + Math.random() * (TYPE_SPEED * 0.2);
}

interface Typer {
  start: (el: HTMLElement, text: string) => void;
  finish: () => void;
  stop: () => void;
}

function createTyper(onTypingChange: (typing: boolean) => void): Typer {
  let el: HTMLElement | null = null;
  let chars: string[] = [];
  let pos = 0;
  let timer: number | undefined;

  function clearTimer() {
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timer = undefined;
    }
  }

  function appendChar(ch: string) {
    if (!el) return;
    if (ch === "\n") {
      el.appendChild(document.createElement("br"));
      return;
    }
    const span = document.createElement("span");
    span.className = "lc-char";
    span.textContent = ch;
    el.appendChild(span);
  }

  function scrollDown() {
    const box = inlineDisplayRef.value;
    if (box) box.scrollTop = box.scrollHeight;
  }

  function step() {
    if (!el || pos >= chars.length) {
      timer = undefined;
      onTypingChange(false);
      return;
    }
    appendChar(chars[pos]);
    pos += 1;
    scrollDown();
    timer = window.setTimeout(step, charDelay());
  }

  return {
    start(target, text) {
      clearTimer();
      el = target;
      chars = Array.from(text);
      pos = 0;
      target.innerHTML = "";
      if (!chars.length) {
        onTypingChange(false);
        return;
      }
      onTypingChange(true);
      step();
    },
    /** 立即输出全文（点击 / 推进时） */
    finish() {
      clearTimer();
      if (!el) return;
      pos = chars.length;
      el.textContent = chars.join("");
      onTypingChange(false);
    },
    stop() {
      clearTimer();
      pos = chars.length;
      onTypingChange(false);
    },
  };
}

const dialogueTyping = ref(false);
const motionTyping = ref(false);
const isTyping = computed(() => dialogueTyping.value || motionTyping.value);

const dialogueTyper = createTyper((v) => (dialogueTyping.value = v));
const motionTyper = createTyper((v) => (motionTyping.value = v));

function clearResponse() {
  dialogueTyper.stop();
  motionTyper.stop();
  if (dialogueLineRef.value) dialogueLineRef.value.innerHTML = "";
  if (motionLineRef.value) motionLineRef.value.innerHTML = "";
}

/** 播放当前这一句：台词白字 + 动作灰字，两个打字机并行 */
function playLine(line: RoomLine) {
  clearResponse();
  if (line.motion && motionLineRef.value) {
    motionTyper.start(motionLineRef.value, line.motion);
  }
  if (dialogueLineRef.value) {
    dialogueTyper.start(dialogueLineRef.value, line.text);
  }
}

function finishTyping() {
  dialogueTyper.finish();
  motionTyper.finish();
  motionTyping.value = false;
}

// ─────────────────────────── 推进 ───────────────────────────
function sendOrContinue() {
  if (roomStore.state === "input") {
    // 选项题且 allowFree=false：输入框不锁（照原版），但不放行提交——
    // 原版同样由后端拒绝后弹 choiceRequired 提示（useChatInput.ts:100-118）
    if (roomStore.choices.length && !roomStore.allowFree) {
      void alertDialog("需要选择", "这道题需要点选上方选项，自由输入已关闭。");
      return;
    }
    const text = inputMessage.value.trim();
    if (!text) return;
    inputMessage.value = "";
    emit("send", text);
    return;
  }
  if (roomStore.state !== "responding") return;
  if (isTyping.value) {
    finishTyping();
    return;
  }
  // 已打完：推进到下一句；队列空则 advance() 内部切回输入态
  advance();
}

/** 状态/句号变化 → 起播当前句或收起打字机 */
watch(
  () => `${roomStore.state}:${roomStore.cursor}`,
  (key) => {
    const [state] = key.split(":");
    if (state !== "responding") {
      clearResponse();
      if (state === "input") nextTick(() => textareaRef.value?.focus());
      return;
    }
    const line = roomStore.lines[roomStore.cursor];
    if (!line || !isShowableLine(line)) return;
    nextTick(() => {
      playLine(line);
      inlineDisplayRef.value?.focus();
    });
  },
);

// ─────────────────────────── 语音输入（ASR） ───────────────────────────
// useAsrInput 的门控全部在 composables/asr 里（总开关 / 对话状态 / 抽屉 / TTS 播放…），
// 这里只负责「按钮与输入框」这两端：按钮触发录音、识别结果写进 inputMessage。
const asr = useAsrInput();
/** 录音相位（idle / recording / recognizing）。ref 在模板里自动解包，脚本里要 .value */
const { phase } = asr;

const micTitle = computed(() =>
  phase.value === "recording"
    ? "松开结束"
    : phase.value === "recognizing"
      ? "识别中…"
      : "按住说话（F8）",
);

/** 把识别文本填进输入框并聚焦（用户可直接改完再回车） */
function fillFromAsr(text: string): void {
  inputMessage.value = text;
  nextTick(() => textareaRef.value?.focus());
}

/**
 * 整句识别结果走 window 事件（`session.ts::handleResult`：fill_only → `asr-text`，
 * auto_send → `asr-send`），不是走 registerAsrInputBridge——那条桥只服务流式 partial
 * （`writePartial`，后端 V8 才有）。两者都接上：现在只有事件这条路是活的。
 */
function onAsrText(e: Event): void {
  fillFromAsr(String((e as CustomEvent<string | undefined>).detail ?? ""));
}

/** auto_send：先显示 800ms 再发送（窗口唯一性由 useAsrAutoSend 保证） */
const asrAutoSend = useAsrAutoSend((text) => {
  // 窗口期内用户可能已经改过输入框：内容不是识别结果就不替他发送（尊重手动编辑）
  if (inputMessage.value !== text) return;
  sendOrContinue();
});

function onAsrSend(e: Event): void {
  const text = String((e as CustomEvent<string | undefined>).detail ?? "");
  if (!text) return;
  fillFromAsr(text);
  asrAutoSend.arm(text);
}

onMounted(() => {
  if (roomStore.state === "responding") {
    const line = roomStore.lines[roomStore.cursor];
    if (line && isShowableLine(line)) playLine(line);
  } else {
    textareaRef.value?.focus();
  }

  // ── 语音输入接线 ──
  // 桥必须在 textareaRef 已存在之后注册（onMounted 里 = 已挂载）；卸载不必反注册，
  // registerAsrInputBridge 是覆盖式注册，下一个房间挂载会重新注册。
  registerAsrInputBridge((text) => fillFromAsr(text));
  window.addEventListener("asr-text", onAsrText);
  window.addEventListener("asr-send", onAsrSend);
  // 门控开关：只有聊天框挂载期间语音才可用（离开房间由 onBeforeUnmount 置回 false，
  // 否则语音会在别的页面被触发）
  setChatMounted(true);
  // mic 按钮的 pointerdown/pointerup 在模板里直接绑 pttDown/pttUp（见上方按钮）。
  // PTT 键盘监听不在这里挂：它由 composables/asr/index.ts::ensureInit() 统一注册
  // （全局一份、永不移除，isAndroid() 时不注册），重复挂只会让每次按键被处理两遍。
  void asrStore.load();
});

onBeforeUnmount(() => {
  dialogueTyper.stop();
  motionTyper.stop();

  setChatMounted(false);
  window.removeEventListener("asr-text", onAsrText);
  window.removeEventListener("asr-send", onAsrSend);
});

// ─────────────────────────── 操作按钮 ───────────────────────────
function sceneTip() {
  void alertDialog("场景设置", "场景面板待接入（B5）");
}

function touchTip() {
  void alertDialog("触摸模式", "摸头互动待接入");
}

function screenshotTip() {
  void alertDialog("截图", "截图功能待接入");
}

/** 快捷存档 / 读档：写 localStorage（本批真做） */
const SAVE_KEY = "interview-room-quick-save-v1";

function quickSave() {
  localStorage.setItem(
    SAVE_KEY,
    JSON.stringify({
      lines: roomStore.lines,
      cursor: roomStore.cursor,
      messages: appStore.session.messages,
      questionIndex: appStore.session.questionIndex,
      at: Date.now(),
    }),
  );
  notify("已快捷存档");
}

function quickLoad() {
  const raw = localStorage.getItem(SAVE_KEY);
  if (!raw) {
    void alertDialog("快捷读档", "本机还没有快捷存档。");
    return;
  }
  try {
    const data = JSON.parse(raw) as {
      lines?: RoomLine[];
      cursor?: number;
      messages?: typeof appStore.session.messages;
      questionIndex?: number;
    };
    if (!data.lines?.length) {
      void alertDialog("快捷读档", "存档内容为空。");
      return;
    }
    roomStore.lines = data.lines;
    roomStore.cursor = Math.min(data.cursor ?? 0, data.lines.length - 1);
    if (data.messages?.length) appStore.session.messages = data.messages;
    if (typeof data.questionIndex === "number") appStore.session.questionIndex = data.questionIndex;
    persist();
    roomStore.state = "responding";
    notify("已读取快捷存档");
  } catch {
    void alertDialog("快捷读档", "存档已损坏，无法读取。");
  }
}
</script>

<style scoped>
/* AI 回复显示区：颜色由两个子容器决定（台词白 / 动作灰），这里灰色仅作 fallback */
.response-display {
  color: #9ca3af;
}

/* 兼容 Firefox */
.custom-scroll {
  scrollbar-width: thin;
}
/* 兼容 Chrome / Edge / Safari */
.custom-scroll::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

/* 情绪标签切换：上一个情绪向左滑出，下一个从右侧滑入（推挤效果）
   —— lingchat.css 已有全局同名实现，这里只为组件内一致性保留语义说明 */

/* 标题栏（角色名 + 副标题）切换：整体从上方滑出/滑入 */
.title-slide-enter-active,
.title-slide-leave-active {
  transition:
    transform 0.3s cubic-bezier(0.25, 0.46, 0.45, 0.94),
    opacity 0.3s ease;
}
/* 离开中的旧标题脱离文档流，向上滑出，避免撑动标题栏布局 */
.title-slide-leave-active {
  position: absolute;
  left: 0;
  top: 0;
}
.title-slide-enter-from {
  transform: translateY(-100%);
  opacity: 0;
}
.title-slide-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>

<style>
/* 面板外观（原版全局样式）：铺到屏幕底的渐变 + 16px 圆角，
   仅内容底部让出 Home 指示器安全区 */
.game-dialog {
  padding-bottom: calc(15px + var(--safe-area-inset-bottom, 0px));
  color: #ffffff;
  border-radius: 16px;
  background: linear-gradient(to top, rgba(0, 14, 39, 0.7), rgba(0, 14, 39, 0.6));
}
</style>
