/**
 * 角色头像解析与情绪演出 —— 照搬 references/lingchat/src/composables/role/useRoleAvatar.ts。
 *
 * 与原版的差异（A1 规格第 2 节）：
 *   1. 去掉 Tauri：不再 `invoke("get_avatar_file")` + `convertFileSrc`，立绘 URL 直接按
 *      本项目既有规则拼（data/interviewers.ts 的 avatarUrl：`/avatars/{code}/{情绪}.webp`）；
 *      原版两个 `listen("character:*")` 是监听本地文件系统的 Tauri 事件，Web 端无对应物，
 *      连同只被它们推进的 avatarRevision 一并删除。
 *   2. store 替换：useGameStore.currentStatus → roomStore.state（"thinking" 同一个语义）；
 *      useUIStore.bubbleVolume → settingsUi.bubbleVolume（本项目新增的最小字段，默认 100）。
 *
 * 保留不变的部分：resolveAvatarId / latestEmotionId 两条代次号竞态保护、
 * 情绪气泡的「置 false → nextTick → 置 true」重播、思考气泡不重触发、handleAnimationEnd
 * 播完回 normal。
 */
import { ref, watch, nextTick, onUnmounted, type Ref } from "vue";
import { roomStore } from "../../store/roomStore";
import { settingsUi } from "../../store/settingsUi";
import { EMOTION_CONFIG, EMOTION_CONFIG_EMO } from "../../controllers/emotion/config";
import { avatarUrl } from "../../data/interviewers";

/** 气泡默认停留时长（毫秒） */
const DEFAULT_BUBBLE_MS = 2000;

/** 演出所需的最小角色视图（原版 GameRole 在本项目的等价物） */
export interface AvatarRole {
  /** 角色 code，对应 public/avatars/{code}/ */
  code: string;
  /** 情绪展示名（可能是「哭泣 / 难为情」这类说法，需过 EMOTION_CONFIG_EMO） */
  emotion: string;
  /** 情绪版本号：每次切情绪自增，保证连续两次相同情绪也能重播 */
  emotionRevision: number;
}

export interface UseRoleAvatarOptions {
  role: Ref<AvatarRole>;
  /** 模板里的 `<audio ref>`，用于播放情绪气泡音效 */
  audioRef: Ref<HTMLAudioElement | null>;
  /**
   * 等待展示层就绪（图片/模型加载完成）后再放动画。
   * 主界面传 presentationRef.waitForLoad()。
   */
  waitForAvatarLoad?: () => Promise<void> | void;
  /** 气泡停留时长，默认 2000ms */
  bubbleDurationMs?: number;
}

export interface UseRoleAvatarApi {
  /** 当前情绪对应的立绘 URL（/avatars/{code}/{情绪}.webp） */
  targetAvatarUrl: Ref<string>;
  /** 待机/情绪动画类名，如 "normal" / "happy-bounce" */
  activeAnimationClass: Ref<string>;
  isBubbleVisible: Ref<boolean>;
  currentBubbleImageUrl: Ref<string>;
  currentBubbleClass: Ref<string>;
  resolveAvatar: () => Promise<void>;
  /** 绑定到动画元素的 @animationend */
  handleAnimationEnd: () => void;
}

/**
 * 解析立绘前的归一化（纯函数，便于核对映射）：表外情绪回落「正常」。
 */
export function avatarFolderParams(role: AvatarRole): { code: string; emotion: string } {
  return {
    code: role.code,
    emotion: EMOTION_CONFIG_EMO[role.emotion] || "正常",
  };
}

/**
 * 立绘的宽高比，取不到返回 null。
 *
 * 用 onload/onerror 而不是 decode()：decode() 在部分情况下会为一个已经加载成功的
 * 图片抛错，那样宽高比就永远拿不到，整块区域功能会静默失效。
 */
export function loadImageAspect(url: string): Promise<number | null> {
  if (!url) return Promise.resolve(null);
  return new Promise((resolve) => {
    const image = new Image();
    image.onload = () =>
      resolve(image.naturalHeight ? image.naturalWidth / image.naturalHeight : null);
    image.onerror = () => resolve(null);
    image.src = url;
  });
}

export { avatarObjectFit } from "../../utils/avatar-layout";

export function useRoleAvatar(options: UseRoleAvatarOptions): UseRoleAvatarApi {
  const { role, audioRef } = options;
  const bubbleDurationMs = options.bubbleDurationMs ?? DEFAULT_BUBBLE_MS;

  const targetAvatarUrl = ref("");
  const activeAnimationClass = ref("normal");
  const isBubbleVisible = ref(false);
  const currentBubbleImageUrl = ref("");
  const currentBubbleClass = ref("");

  let bubbleTimeoutId: number | null = null;
  // 两个代次号各管一条异步链：立绘解析、情绪演出
  let resolveAvatarId = 0;
  let latestEmotionId = 0;
  onUnmounted(() => {
    resolveAvatarId++;
    latestEmotionId++;
  });

  async function resolveAvatar() {
    const currentId = ++resolveAvatarId;
    try {
      const { code, emotion } = avatarFolderParams(role.value);
      const url = avatarUrl(code, emotion);
      if (currentId === resolveAvatarId) {
        targetAvatarUrl.value = url;
      }
    } catch {
      if (currentId === resolveAvatarId) {
        targetAvatarUrl.value = "";
      }
    }
  }

  // 播放情绪气泡音效（音量跟随「气泡音量」设置，否则恒为满音量）
  const playBubbleAudio = (src: string) => {
    if (!audioRef.value) return;
    audioRef.value.volume = settingsUi.bubbleVolume / 100;
    audioRef.value.src = src;
    audioRef.value.load();
    audioRef.value.play().catch((e) => console.error("气泡音效播放失败:", e));
  };

  const clearBubbleTimer = () => {
    if (bubbleTimeoutId !== null) {
      window.clearTimeout(bubbleTimeoutId);
      bubbleTimeoutId = null;
    }
  };

  /** 安排气泡在停留时长后自动隐藏 */
  const armBubbleHide = () => {
    clearBubbleTimer();
    bubbleTimeoutId = window.setTimeout(() => {
      isBubbleVisible.value = false;
      bubbleTimeoutId = null;
    }, bubbleDurationMs);
  };

  /**
   * 情绪气泡：先置 false → nextTick 置 true，重触发 CSS 过渡。
   * 演出版号要求重播时，即使两次图片 URL 相同，也要重新触发过渡。
   */
  const showEmotionBubble = (image: string, className: string) => {
    currentBubbleImageUrl.value = image;
    currentBubbleClass.value = className;
    isBubbleVisible.value = false;
    nextTick(() => {
      isBubbleVisible.value = true;
      armBubbleHide();
    });
  };

  /**
   * 思考气泡：不重触发。currentStatus 离开 thinking 时下面的 watch 已先隐藏，
   * 再进 thinking 必然是 false → true，天然会重播。与重构前行为一致。
   */
  const showThinkingBubble = (image: string, className: string) => {
    currentBubbleImageUrl.value = image;
    currentBubbleClass.value = className;
    if (!isBubbleVisible.value) {
      isBubbleVisible.value = true;
    }
    armBubbleHide();
  };

  // 角色变化直接解析；情绪变化由下方演出流程统一解析。
  watch(() => role.value.code, () => resolveAvatar(), { immediate: true });

  // 监听表情，配合展示层的加载状态播放特效
  watch(
    () => [role.value.emotion, role.value.emotionRevision] as const,
    async ([newEmotion]) => {
      const currentId = ++latestEmotionId;
      resolveAvatarId++;
      const config = EMOTION_CONFIG[newEmotion];

      if (
        config?.animation &&
        config.animation !== "none" &&
        activeAnimationClass.value === config.animation
      ) {
        // 连续使用同一种 CSS 动作时，先保留旧立绘并完成动作重置，再解析下一姿势，
        // 确保新立绘首次显示时已经带上本次动作。
        activeAnimationClass.value = "normal";
        await nextTick();
        await new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        });
        if (currentId !== latestEmotionId) return;
      }

      // 依次等待：立绘路径解析 → DOM 更新传给子组件 → 子组件图片加载完成
      await resolveAvatar();
      await nextTick();
      await options.waitForAvatarLoad?.();

      // 陈旧性检查必须在注入的 await 之后：期间表情又变了就丢弃这次结果
      if (currentId !== latestEmotionId) return;

      if (!config) return;

      if (config.animation && config.animation !== "none") {
        activeAnimationClass.value = config.animation;
      }

      if (config.bubbleImage && config.bubbleImage !== "none") {
        showEmotionBubble(config.bubbleImage, config.bubbleClass);
      }

      if (config.audio && config.audio !== "none") {
        playBubbleAudio(config.audio);
      }
    },
    { immediate: true },
  );

  // 气泡音量设置变化时，对已加载的音效实时生效
  watch(
    () => settingsUi.bubbleVolume,
    (v) => {
      if (audioRef.value) audioRef.value.volume = v / 100;
    },
  );

  // 思考中反馈：气泡 + 音效（由 roomStore.state 驱动，与 emotion 解耦）
  watch(
    () => roomStore.state,
    (newStatus) => {
      if (newStatus === "thinking") {
        const config = EMOTION_CONFIG["AI思考"];
        if (config && config.bubbleImage && config.bubbleImage !== "none") {
          showThinkingBubble(config.bubbleImage, config.bubbleClass);
        }
        if (config?.audio && config.audio !== "none") {
          playBubbleAudio(config.audio);
        }
      } else {
        // 离开思考态：隐藏思考气泡、停掉定时器
        isBubbleVisible.value = false;
        clearBubbleTimer();
      }
    },
  );

  const handleAnimationEnd = () => {
    if (activeAnimationClass.value !== "normal") {
      activeAnimationClass.value = "normal";
    }
  };

  // 卸载时清掉待触发的气泡定时器，避免组件已销毁还在写 ref
  onUnmounted(clearBubbleTimer);

  return {
    targetAvatarUrl,
    activeAnimationClass,
    isBubbleVisible,
    currentBubbleImageUrl,
    currentBubbleClass,
    resolveAvatar,
    handleAnimationEnd,
  };
}
