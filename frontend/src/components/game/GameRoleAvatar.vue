<template>
  <StaticRolePresentation
    ref="presentationRef"
    :src="targetAvatarUrl"
    :layer-style="staticLayerStyle"
    :animation-classes="containerClasses"
    :object-fit="computedObjectFit"
    @animation-end="handleAnimationEnd"
  />
  <!-- 气泡与情绪音效位于立绘层上方（原版 GameRoleAvatar 的 effects 层，z-index 2） -->
  <div
    class="pointer-events-none absolute h-full w-full origin-[center_0%]"
    :style="effectsLayerStyle"
  >
    <div :class="bubbleClasses" :style="bubbleStyles" class="bubble"></div>
    <audio ref="bubbleAudio"></audio>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import StaticRolePresentation from "./StaticRolePresentation.vue";
import { findInterviewer } from "../../data/interviewers";
import { avatarObjectFit, standardAvatarStyle } from "../../utils/avatar-layout";
import { useRoleAvatar, type AvatarRole } from "../../composables/role/useRoleAvatar";
import { roomStore } from "../../store/roomStore";

const props = withDefaults(
  defineProps<{
    /** 角色 code，对应 public/avatars/{code}/ */
    code: string;
    /** 情绪文件名（去掉 .webp） */
    emotion?: string;
    /**
     * 情绪版本号：每次切情绪自增。
     * 原版靠 role.emotionRevision 让「连续两次相同情绪」也能重播动画，
     * 本项目 emotion 变化时组件不会重建，所以这个号必须一路传下来。
     */
    emotionRevision?: number;
    /** 立绘缩放 / 偏移（原版角色设定字段，缺省 1 / 0 / 0） */
    scale?: number;
    offsetX?: number;
    offsetY?: number;
    /** 是否当前说话者（多槽位舞台传入；单人房间恒为 true） */
    active?: boolean;
  }>(),
  { emotion: "正常", emotionRevision: 0, scale: 1, offsetX: 0, offsetY: 0, active: true },
);

const bubbleAudio = ref<HTMLAudioElement | null>(null);
const presentationRef = ref<InstanceType<typeof StaticRolePresentation> | null>(null);

/** 演出所需的最小角色视图（原版 GameRole 的等价物） */
const role = computed<AvatarRole>(() => ({
  code: props.code,
  emotion: props.emotion,
  emotionRevision: props.emotionRevision,
}));

// 立绘解析 + 情绪演出（动画类 / 气泡 / 音效）
const {
  targetAvatarUrl,
  activeAnimationClass,
  isBubbleVisible,
  currentBubbleImageUrl,
  currentBubbleClass,
  handleAnimationEnd,
} = useRoleAvatar({
  role,
  audioRef: bubbleAudio,
  waitForAvatarLoad: () => presentationRef.value?.waitForLoad(),
});

/** 窄屏取景：宽高比 <1 时收缩可见高度（原版 avatarObjectFit）。
 *  读 isNarrowScreen 让 resize（roomStore 更新）能触发重算 */
const computedObjectFit = computed(() => {
  void roomStore.isNarrowScreen;
  return avatarObjectFit(window.innerWidth / Math.max(1, window.innerHeight));
});

/**
 * 非当前说话者的观感。
 *
 * 原版 GameRoleAvatar 里立绘层的 filter 只来自场景光照（lightingFilter，:105-115），
 * 没有按说话者变暗的规则；说话/非说话在 3D 侧靠 Live2D 口型区分（Live2DStage.vue:828-830），
 * 静态立绘这条通道没有对应物。这里沿用原版「filter 挂在立绘层」的同一机制，
 * 给非说话者压一点亮度与饱和：只改观感、不动几何，槽位宽高与定位完全不变。
 */
const inactiveFilter = computed(() =>
  props.active ? undefined : "brightness(0.78) saturate(0.92)",
);

const roleLayerStyle = computed<Record<string, string>>(() => {
  void roomStore.isNarrowScreen;
  const style: Record<string, string> = {
    ...standardAvatarStyle(
      { scale: props.scale, offsetX: props.offsetX, offsetY: props.offsetY },
      { width: window.innerWidth, height: window.innerHeight },
      50,
    ),
    opacity: "1",
    // 槽位换宽（1 人 ↔ 2 人）时立绘跟着平滑过渡，所以 transition 里必须带 width
    transition: "left 0.5s cubic-bezier(0.25, 0.8, 0.5, 1), top 0.3s ease, width 0.5s cubic-bezier(0.25, 0.8, 0.5, 1), opacity 0.3s ease-in-out, filter 0.3s ease-in-out",
  };
  const filter = inactiveFilter.value;
  if (filter) style.filter = filter;
  return style;
});

// --avatar-breath-scale 挂在动画元素父层（原版主界面取 1.0035）
const staticLayerStyle = computed<Record<string, string>>(() => ({
  ...roleLayerStyle.value,
  zIndex: "1",
  "--avatar-breath-scale": "1.0035",
}));
const effectsLayerStyle = computed<Record<string, string>>(() => ({
  ...roleLayerStyle.value,
  zIndex: "2",
}));

/** 待机/情绪动画类：原版恒为 {}，立绘全程静止；这里接 useRoleAvatar 算出来的类 */
const containerClasses = computed<Record<string, boolean>>(() => ({
  [activeAnimationClass.value]: true,
}));

const bubbleClasses = computed<Record<string, boolean>>(() => ({
  show: isBubbleVisible.value,
  [currentBubbleClass.value]: isBubbleVisible.value && currentBubbleClass.value !== "none",
}));

// 气泡落点沿用角色卡设定的 bubbleLeft/bubbleTop（原版 role.bubbleLeft / role.bubbleTop）
const bubbleStyles = computed(() => {
  const meta = findInterviewer(props.code);
  return {
    left: `${+meta.bubbleLeft + 5}%`,
    top: `${+meta.bubbleTop - 5}%`,
    backgroundImage: `url(${currentBubbleImageUrl.value})`,
  };
});
</script>
