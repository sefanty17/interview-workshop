<template>
  <div
    class="pointer-events-none fixed inset-0 mt-[-15dvh] flex flex-col items-center justify-center"
  >
    <!-- duration 倒计时（仅当 script:choice 带 duration 时出现；只展示不自动提交） -->
    <div
      v-if="remaining !== null"
      class="pointer-events-none mb-6 rounded-full bg-slate-900/50 px-4 py-1 font-mono text-xs tracking-widest text-white/60 backdrop-blur-md"
    >
      剩余 {{ remaining }}s
    </div>
    <transition-group
      appear
      :css="false"
      tag="div"
      class="pointer-events-auto flex w-full max-w-2xl flex-col gap-10 px-4"
      @before-enter="choiceBeforeEnter"
      @enter="choiceEnter"
      @leave="choiceLeave"
    >
      <button
        v-for="(choice, index) in displayChoices"
        :key="choice.text"
        :data-index="index"
        :disabled="choice.disabled"
        :title="choice.disabled ? choice.reason || '该选项当前不可选' : ''"
        @click="selectChoice(choice)"
        :class="[
          `group shadow-glass relative w-full rounded-full border border-white/10 px-8 py-4 text-sm transition-all duration-200`,
          choice.disabled
            ? `cursor-not-allowed bg-slate-900/20 text-white/30 backdrop-blur-xl backdrop-saturate-150 hover:outline-none`
            : `hover:border-brand hover:ring-brand/20 transform bg-slate-900/40 text-white shadow-[0_4px_12px_rgba(0,0,0,0.3)] backdrop-blur-xl backdrop-saturate-150 hover:-translate-y-1 hover:shadow-[0_0_15px_rgba(0,0,0,0.5)] hover:ring-2 hover:outline-none`,
        ]"
      >
        <!-- 粒子效果 - 静态粒子 (小圆点) -->
        <div
          class="absolute inset-0 opacity-30 transition-opacity duration-700 group-hover:opacity-50"
        >
          <!-- 左上区域粒子 -->
          <div class="absolute top-2 left-4 h-1 w-1 rounded-full bg-white/60"></div>
          <div class="absolute top-6 left-8 h-0.5 w-0.5 rounded-full bg-blue-300/50"></div>
          <div class="bg-brand/40 absolute top-4 left-16 h-1.5 w-1.5 rounded-full blur-[1px]"></div>

          <!-- 右上区域粒子 -->
          <div class="absolute top-3 right-6 h-1 w-1 rounded-full bg-white/40"></div>
          <div class="absolute top-8 right-12 h-0.5 w-0.5 rounded-full bg-purple-300/50"></div>
          <div class="bg-brand/30 absolute top-5 right-20 h-1 w-1 rounded-full blur-[1px]"></div>

          <!-- 中部区域粒子 -->
          <div class="absolute top-1/2 left-10 h-0.5 w-0.5 rounded-full bg-cyan-300/40"></div>
          <div class="absolute top-1/2 right-12 h-1 w-1 rounded-full bg-white/30"></div>

          <!-- 底部区域粒子 -->
          <div class="bg-brand/30 absolute bottom-4 left-8 h-1 w-1 rounded-full"></div>
          <div class="absolute right-10 bottom-8 h-0.5 w-0.5 rounded-full bg-blue-300/40"></div>
          <div
            class="absolute right-16 bottom-3 h-1.5 w-1.5 rounded-full bg-white/20 blur-[1px]"
          ></div>
        </div>

        <!-- 动态漂浮粒子 (悬停时动画) -->
        <div
          class="absolute inset-0 opacity-0 transition-opacity duration-500 group-hover:opacity-40"
        >
          <!-- 漂浮粒子1 -->
          <div
            class="animate-float-slow absolute top-2 left-4 h-1 w-1 rounded-full bg-white/60"
          ></div>
          <!-- 漂浮粒子2 -->
          <div
            class="bg-brand/60 animate-float absolute right-8 bottom-6 h-0.5 w-0.5 rounded-full"
          ></div>
          <!-- 漂浮粒子3 -->
          <div
            class="animate-float-reverse absolute top-8 right-12 h-1 w-1 rounded-full bg-purple-400/50"
          ></div>
          <!-- 漂浮粒子4 -->
          <div
            class="animate-float-slow absolute bottom-10 left-12 h-1.5 w-1.5 rounded-full bg-cyan-300/40 blur-[1px]"
          ></div>
          <!-- 漂浮粒子5 -->
          <div
            class="animate-float absolute top-1/3 right-20 h-1 w-1 rounded-full bg-white/40"
          ></div>
        </div>

        <!-- 微光扫射效果 (悬停时) -->
        <div
          class="absolute inset-0 overflow-hidden rounded-full opacity-0 transition-opacity duration-700 group-hover:opacity-100"
        >
          <div
            class="animate-shine absolute -inset-full top-0 z-5 block h-full w-1/2 -skew-x-12 transform bg-linear-to-r from-transparent via-white/10 to-transparent"
          ></div>
        </div>
        <span
          class="block text-center text-lg font-medium tracking-widest text-white drop-shadow-[0_2px_4px_rgba(0,0,0,0.8)] group-hover:text-white"
        >
          {{ choice.text }}
        </span>
      </button>
    </transition-group>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { alertDialog } from "../../../store/dialogStore";
import { DEMO_CHOICES, extraDemo } from "../../../store/extraDemo";
import { roomStore, type RoomChoice } from "../../../store/roomStore";

const emit = defineEmits<{
  /** 玩家点选了真实选项（script:choice），参数为选项文案 */
  choose: [text: string];
}>();

/**
 * 选项数据：真实 script:choice 优先（原版读 gameStore.runningScript.choices），
 * 没有时退回演示数据（控制台 __extraDemo(true)）。
 * 原版也是「开关关掉 / 选项清空 → 喂空数组触发交错渐隐」（GameChoices.vue:108-111）。
 */
const displayChoices = computed<RoomChoice[]>(() => {
  if (roomStore.choices.length) return roomStore.choices;
  if (!extraDemo.showChoices) return [];
  return extraDemo.choices.map((text) => ({ text, disabled: false }));
});

/** 真实的 choice 处于等待中（区别于演示数据） */
const isLiveChoice = computed(() => roomStore.choices.length > 0);

/** 重新打开选项层时回填演示选项（点击后已清空） */
function restoreChoices() {
  if (extraDemo.choices.length === 0) extraDemo.choices = [...DEMO_CHOICES];
}

// ───────────── duration 倒计时（仅展示，不自动提交） ─────────────
// LingChat 前端对 script:choice 不做任何自动提交：ChoiceProcessor 只把选项塞进
// gameStore 并把状态切回 input，duration 在 event-queue.ts:103-108 里是「非阻塞事件
// 的自动推进间隔」，而 choice 属于阻塞事件（等玩家点选），所以那个时长从不消费。
// 本移植照此只显示剩余秒数，倒计时归零也不代玩家选择。
const remaining = ref<number | null>(null);
let countdownTimer: number | undefined;

function stopCountdown() {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
}

watch(
  () => [roomStore.choices.length, roomStore.choiceDuration] as const,
  ([count, duration]) => {
    stopCountdown();
    if (!count || typeof duration !== "number" || duration <= 0) {
      remaining.value = null;
      return;
    }
    remaining.value = Math.ceil(duration);
    countdownTimer = window.setInterval(() => {
      if (remaining.value === null) return;
      remaining.value = Math.max(0, remaining.value - 1);
      if (remaining.value === 0) stopCountdown();
    }, 1000);
  },
  { immediate: true },
);

onBeforeUnmount(stopCountdown);

/**
 * 处理玩家选择。
 * 原版（GameChoices.vue:114-127）：先 appendGameMessage 记下玩家说的这句，
 * 再 invoke("script_submit_choice")，随后清空选项触发渐隐。
 * 这里把提交交给房间视图，走同一条 advance 链路；disabled 项永远不提交。
 */
function selectChoice(choice: RoomChoice) {
  if (choice.disabled) return;
  if (isLiveChoice.value) {
    emit("choose", choice.text);
    return;
  }
  // 演示数据不进入对话流程，弹窗确认后清空选项触发交错渐隐
  alertDialog("选择结果", `已选择：${choice.text}（演示数据，不进入对话流程）`);
  extraDemo.choices = [];
}

// 选项进入前的状态：透明且靠下
function choiceBeforeEnter(el: Element) {
  const element = el as HTMLElement;
  element.style.opacity = "0";
  element.style.transform = "translateY(30px)";
  element.style.transition = "all 0.5s cubic-bezier(0.34, 1.56, 0.64, 1)"; // 使用回弹缓动曲线
}

// 选项进入动画：加入 requestAnimationFrame 强制渲染初始状态，随后通过 setTimeout 加入交错延迟
function choiceEnter(el: Element, done: () => void) {
  const element = el as HTMLElement;
  const index = parseInt(element.dataset.index || "0");

  requestAnimationFrame(() => {
    setTimeout(() => {
      element.style.opacity = "1";
      element.style.transform = "translateY(0)";
      setTimeout(done, 500); // 等待动画完成释放生命周期
    }, index * 100); // 每个选项延迟 100ms
  });
}

// 选项离开动画：点击后稍微缩小并渐隐消失
function choiceLeave(el: Element, done: () => void) {
  const element = el as HTMLElement;
  element.style.transition = "all 0.3s cubic-bezier(0.4, 0, 0.2, 1)";
  element.style.opacity = "0";
  element.style.transform = "scale(0.95)";
  setTimeout(done, 300);
}

// 开关联动：打开时回填选项
watch(
  () => extraDemo.showChoices,
  (show) => {
    if (show) restoreChoices();
  },
  { immediate: true },
);
</script>

<style scoped>
@keyframes float {
  0%,
  100% {
    transform: translateY(0) translateX(0);
  }

  25% {
    transform: translateY(-4px) translateX(2px);
  }

  50% {
    transform: translateY(0) translateX(4px);
  }

  75% {
    transform: translateY(4px) translateX(0);
  }
}

@keyframes float-slow {
  0%,
  100% {
    transform: translateY(0) translateX(0);
  }

  33% {
    transform: translateY(-3px) translateX(-2px);
  }

  66% {
    transform: translateY(2px) translateX(3px);
  }
}

@keyframes float-reverse {
  0%,
  100% {
    transform: translateY(0) translateX(0);
  }

  33% {
    transform: translateY(3px) translateX(-3px);
  }

  66% {
    transform: translateY(-2px) translateX(2px);
  }
}

@keyframes shine {
  100% {
    left: 200%;
  }
}

.animate-float {
  animation: float 6s ease-in-out infinite;
}

.animate-float-slow {
  animation: float-slow 8s ease-in-out infinite;
}

.animate-float-reverse {
  animation: float-reverse 7s ease-in-out infinite;
}

.animate-shine {
  animation: shine 3s ease-in-out infinite;
}

/* 为粒子添加随机延迟 */
.animate-float:nth-child(1) {
  animation-delay: 0s;
}

.animate-float:nth-child(2) {
  animation-delay: 1.2s;
}

.animate-float:nth-child(3) {
  animation-delay: 2.4s;
}

.animate-float:nth-child(4) {
  animation-delay: 0.8s;
}

.animate-float:nth-child(5) {
  animation-delay: 1.8s;
}
</style>
