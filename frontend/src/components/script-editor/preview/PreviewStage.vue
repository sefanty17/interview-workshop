<template>
  <!--
    Teleport 到 #app 而不是 body：全局 Ctrl+滚轮缩放是 transform: scale 整体打在
    #app 上（useZoom），挂到 body 的浮层会脱离缩放作用域，永远按 100% 视口渲染，
    缩放后与编辑器主体比例失调。试玩层是编辑器最常用的浮层，必须与主体等比缩放。
  -->
  <Teleport to="#app">
    <Transition
      enter-active-class="transition-opacity duration-[0.25s] ease-[cubic-bezier(0.18,0.89,0.32,1)]"
      leave-active-class="transition-opacity duration-[0.25s] ease-[cubic-bezier(0.18,0.89,0.32,1)]"
      enter-from-class="opacity-0"
      leave-to-class="opacity-0"
    >
      <div v-if="store.previewing" class="fixed inset-0 z-[9990] overflow-hidden bg-black">
        <!--
          `main-box` 是 MainChat 里的全局类（那个 <style> 没有 scoped），这里直接
          复用而不是另写一套。它是 `flex-direction: column; justify-content: flex-end`，
          对话框才会贴在屏幕底部 —— 早先这里只是个 `position: fixed` 的空壳，
          GameDialog 作为普通块元素落在最上面，于是试玩时对话框跑到了屏幕顶部。
          复用同一个类还顺带保证：以后正式游玩的布局改了，试玩跟着一起变。
        -->
        <div class="main-box">
          <!-- 复用真实的游戏渲染层。这是当初选「复用真引擎 + 真渲染层」而不是
               另写一套预览解释器的兑现点：这四个组件读的是同一份 store，
               引擎 emit 的事件经 runScriptStream 进来后，表现与正式游玩逐帧一致。 -->
          <GameBackground />
          <GameRolesStage />
          <GameExtraUI @choose="sendChoice" />
          <GameDialog @send="sendText" @finish="stopPreview()" />
        </div>

        <!-- 预览专属的顶栏，明确「这是试玩」而不是真在玩 -->
        <div
          class="absolute inset-x-0 top-0 z-[10000] flex items-center gap-3 bg-[linear-gradient(180deg,rgba(0,0,0,0.55),transparent)] px-4 py-2"
        >
          <span
            class="rounded-full border border-[rgba(121,217,255,0.5)] bg-[rgba(121,217,255,0.15)] px-2.5 py-0.5 text-[0.72rem] font-semibold text-brand"
            >试玩中</span
          >
          <span class="text-[0.78rem] text-white [text-shadow:0_1px_3px_rgba(0,0,0,0.6)]">{{
            label
          }}</span>
          <span class="text-[0.7rem] text-white/[0.6] [text-shadow:0_1px_3px_rgba(0,0,0,0.6)]"
            >试玩不会保存成绩与记录</span
          >
          <span
            v-if="store.previewAutoEnd"
            class="text-[0.7rem] text-white/[0.6] [text-shadow:0_1px_3px_rgba(0,0,0,0.6)]"
            >本段已演完</span
          >
          <button
            class="ml-auto rounded-lg border border-[rgba(248,113,113,0.45)] bg-[rgba(248,113,113,0.16)] px-[14px] py-[5px] text-[0.76rem] text-[#fca5a5] backdrop-blur-[8px] transition-all hover:bg-[rgba(248,113,113,0.32)] hover:text-white"
            title="Esc"
            @click="stopPreview()"
          >
            停止试玩
          </button>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * 试玩层（对照原版 `components/script-editor/preview/PreviewStage.vue`）。
 *
 * 保住原版的三条语义：
 * 1. 走真引擎、真渲染层 —— 事件由 `runScriptStream`（与正式房间同一份消费器）落进
 *    roomStore，四个游戏组件读的是同一份状态，所以试玩画面 = 正式游玩画面。
 * 2. 与正式游玩的差别只有两点：不记通关、不触发成就。本项目即「试玩场次不计分、
 *    不进面试记录」（后端 preview=true 的场次）。
 * 3. 进来搭台、出去还原。原版试玩与自由对话共用同一个 GameStatus，所以要做整体
 *    快照 + 还原；本项目没有全局共享会话状态，每场次的状态天然隔离在
 *    interview_session 行里，因此「快照/还原」退化为「专用试玩场次（preview=true）
 *    + 退出即删除」—— 玩家的真实场次一个字节都没被碰过，与原版目标一致，
 *    原版 PreviewSession 的 line_len / generation / scene 快照不需要照抄。
 */

import { computed, onBeforeUnmount, onMounted, watch } from "vue";
import { GameBackground, GameDialog, GameExtraUI, GameRolesStage } from "../../game";
import { startScript, submitScriptChoice, submitScriptInput } from "../../../api/chat";
import { createPreview, discardPreview } from "../../../api/editor";
import { applyPendingChoice, runScriptStream } from "../../../composables/useScriptRunner";
import { alertDialog } from "../../../store/dialogStore";
import { store as appStore, type SessionMode } from "../../../store/appStore";
import { resetRoom, roomStore, setPresentRoles } from "../../../store/roomStore";
import { scriptKey, stopPreview, store } from "../../../store/scriptEditor";

const props = defineProps<{ fromChapter?: string }>();

const label = computed(() => {
  const parts = [store.detail?.package.scriptName ?? ""];
  if (props.fromChapter) parts.push(`从第 ${props.fromChapter} 章开始`);
  // 把 MAIN 解析成了谁直接写出来 —— 演错人是最难自己看出来的一类问题
  const who = store.previewInfo?.interviewerName;
  if (who) parts.push(`MAIN = ${who}`);
  return parts.filter(Boolean).join(" · ");
});

/**
 * 角色标识 → 舞台 code。试玩只有一位（剧本绑定的面试官），所以：
 * - `null` / `0`：旁白 / 求职者，不切说话人
 * - `"MAIN"`：剧本里写的主角，落到本场面试官（正式房间同样按本场人设兜底）
 * - 数字：interviewer.id 口径，同样按本场面试官兜底
 * - 其它字符串：剧本事件里的角色 code，原样用
 */
function resolveRole(raw: unknown): string | null {
  const main = store.previewInfo?.interviewerCode ?? "";
  if (raw === null || raw === undefined) return null;
  if (typeof raw === "number") return raw === 0 ? null : main;
  const code = String(raw).trim();
  if (!code || code === "0") return null;
  if (code === "MAIN") return main;
  return code;
}

/** 流消费器的公共 hook：试玩不落历史（不传 recordLine），只关心「演完」。 */
function hooks() {
  return {
    resolveRole,
    onEnd: () => (store.previewAutoEnd = true),
    onError: (payload: unknown) => console.warn("[preview] ai:error", payload),
  };
}

/** 提交即收起选项层（与正式房间 handleSend 同语义），否则刚点过的按钮还挂在那儿 */
function clearChoices() {
  if (!roomStore.choices.length) return;
  roomStore.choices = [];
  roomStore.allowFree = false;
  roomStore.choiceDuration = null;
}

/** GameDialog 的输入提交：走剧本的 input 入口 */
async function sendText(text: string) {
  const id = store.previewSessionId;
  if (id === null || !store.previewing) return;
  clearChoices();
  roomStore.state = "thinking";
  const ok = await runScriptStream(submitScriptInput(id, text), hooks());
  // 后端一个字都没回也没停在阻塞点：不要留在「思考中」卡死
  if (!ok && roomStore.state === "thinking") roomStore.state = "input";
}

/** GameChoices 的选项点击：走剧本的 choice 入口（后端强制按选项文本匹配） */
async function sendChoice(text: string) {
  const id = store.previewSessionId;
  if (id === null || !store.previewing) return;
  clearChoices();
  roomStore.state = "thinking";
  const ok = await runScriptStream(submitScriptChoice(id, text), hooks());
  if (!ok && roomStore.state === "thinking") roomStore.state = "input";
}

/**
 * GameExtraUI 的剧本附加层（选项 / 章节名 / 完成庆祝）由 `session.mode === "script"` 开闸。
 * 试玩跑的就是剧本，必须开闸否则选项层不出场；这里只临时改内存态、退出时原样还原，
 * 不调 persist()，所以玩家的本地存档不会被这次试玩改写。
 */
let savedMode: SessionMode | null = null;

function enterScriptMode() {
  if (savedMode !== null) return;
  savedMode = appStore.session.mode;
  appStore.session.mode = "script";
}

function leaveScriptMode() {
  if (savedMode === null) return;
  appStore.session.mode = savedMode;
  savedMode = null;
}

/**
 * 进 / 出试玩（原版的 watch 同构）。
 *
 * 原版在开演前先把 eventQueue 放行、结束时清队；本项目没有事件队列，事件由
 * runScriptStream 直接消费，所以「搭台/收台」就是 resetRoom 与删场。
 */
watch(
  () => store.previewing,
  async (on) => {
    if (!on) {
      resetRoom();
      leaveScriptMode();
      const id = store.previewSessionId;
      store.previewSessionId = null;
      store.previewInfo = null;
      store.previewAutoEnd = false;
      if (id !== null) {
        // 退出即删除：试玩场次不进记录，也不该在库里堆积
        try {
          await discardPreview(id);
        } catch (e) {
          console.warn("[preview] 删除试玩场次失败", e);
        }
      }
      return;
    }

    // 1. 搭台：清空舞台（上一场的立绘 / 背景 / 选项留在 roomStore 里）
    resetRoom();

    const key = scriptKey.value;
    if (!key) {
      store.previewError = "没有打开剧本，无法试玩";
      store.previewing = false;
      void alertDialog("试玩启动失败", store.previewError);
      return;
    }

    try {
      // 2. 建专用试玩场次（后端顺带清掉上一轮残留）
      const info = await createPreview(key, props.fromChapter || undefined);
      // 建场期间用户已经点了「停止试玩」：当场收掉，别留一个孤儿场次
      if (!store.previewing) {
        await discardPreview(info.sessionId).catch(() => undefined);
        return;
      }
      store.previewInfo = info;
      store.previewSessionId = info.sessionId;

      // 3. 注入本场面试官：标题栏与舞台都按剧本绑定的人设来
      enterScriptMode();
      roomStore.showCharacterTitle = info.interviewerName;
      roomStore.showCharacterSubtitle = "";
      roomStore.showCharacterEmotion = "正常";
      setPresentRoles([info.interviewerCode]);

      // 4. 开演（真引擎）：从编辑器选定的章节开始，没选就从 intro_chapter 开始
      await runScriptStream(
        startScript(info.sessionId, info.scriptCode, props.fromChapter || undefined),
        hooks(),
      );
    } catch (e) {
      // 失败要让人看见：只写 console 等于没提示
      store.previewError = e instanceof Error ? e.message : String(e);
      store.previewing = false;
      void alertDialog("试玩启动失败", store.previewError);
    }
  },
);

/**
 * 挂起的选项在同一批台词读完后才放出（原版事件队列顺序）。
 * 房间侧靠自己的 state watch 做同一件事；试玩没有那个 watch，所以在这里补上。
 */
watch(
  () => roomStore.state,
  (state) => {
    if (state === "input") applyPendingChoice();
  },
);

/** Esc = 停止试玩（与顶栏按钮同效） */
function onKeydown(e: KeyboardEvent) {
  if (e.key === "Escape" && store.previewing) stopPreview();
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKeydown);
  leaveScriptMode();
  // 整页卸载（离开编辑器路由）时上面那个 watch 已经失效，试玩层不会自己收尾：
  // 这里补一次「收台 + 删场」，否则会把试玩场次留在库里，下次进来还是个开着灯的壳。
  if (store.previewing) {
    const id = store.previewSessionId;
    // 先清 id 再关开关：这样即使 watch 还活着也不会重复删一次
    store.previewSessionId = null;
    store.previewInfo = null;
    stopPreview();
    resetRoom();
    if (id !== null) void discardPreview(id).catch(() => undefined);
  }
});
</script>
