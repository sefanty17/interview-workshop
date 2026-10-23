<template>
  <!-- 立绘舞台：按 roomStore.presentRoles 渲染多个槽位，底部对齐；对话框（z-10 容器）压在它上面 -->
  <div
    class="absolute inset-0 h-full w-full overflow-hidden"
    :style="{ '--role-slot-duration': `${roomStore.characterTransition}s` }"
  >
    <div class="relative z-2 h-full w-full">
      <!-- 槽位：进场淡入 + 原版 role-enter 位移（StaticRolePresentation），退场淡出 -->
      <TransitionGroup name="role-slot">
        <div
          v-for="item in slots"
          :key="item.code"
          class="role-slot-box absolute inset-y-0"
          :style="item.box"
        >
          <GameRoleAvatar
            :code="item.code"
            :emotion="item.emotion"
            :emotion-revision="item.emotionRevision"
            :scale="item.scale"
            :offset-x="item.offsetX"
            :offset-y="item.offsetY"
            :active="item.active"
          />
        </div>
      </TransitionGroup>
      <slot></slot>
    </div>
    <!-- 场景光照叠加层：B5 接入 -->
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import GameRoleAvatar from "./GameRoleAvatar.vue";
import { interviewers } from "../../data/interviewers";
import { roomStore } from "../../store/roomStore";

interface RoleSlot {
  code: string;
  emotion: string;
  /** 情绪版本号：同情绪连说两次也要让立绘重播一次动画 */
  emotionRevision: number;
  scale: number;
  offsetX: number;
  offsetY: number;
  active: boolean;
  box: Record<string, string>;
}

/**
 * 槽位盒（舞台从左到右，顺序 = presentRoles 顺序）：
 * - 1 人：整屏，立绘 50% 居中——与原单槽位渲染完全一致
 * - 2 人：各占 45% 宽度、贴外侧（左 0%~45%，右 55%~100%），中间留 10% 缝
 * - 3 人以上：均分，每人 100/n%
 */
function slotBox(index: number, total: number): Record<string, string> {
  if (total === 1) return { left: "0%", width: "100%" };
  if (total === 2) return { left: index === 0 ? "0%" : "55%", width: "45%" };
  const width = 100 / total;
  return { left: `${index * width}%`, width: `${width}%` };
}

const slots = computed<RoleSlot[]>(() => {
  const codes = roomStore.presentRoles;
  const total = codes.length;
  if (!total) return [];
  return codes.map((code, index) => {
    const meta = interviewers.find((p) => p.code === code);
    return {
      code,
      emotion: roomStore.roleEmotions[code] ?? "正常",
      emotionRevision: roomStore.roleEmotionRevisions[code] ?? 0,
      // 单人沿用角色卡设定的缩放；多人取 1，让人物按槽位宽度适配容器
      // （原版多立绘的基准缩放就是「适配容器高度」：Live2DStage.vue:412-418），
      // 否则 1.2~1.3 的角色缩放会让相邻槽位互相压边。
      scale: total === 1 ? (meta?.scale ?? 1) : 1,
      offsetX: meta?.offsetX ?? 0,
      offsetY: meta?.offsetY ?? 0,
      // 没人说话（旁白/玩家句）时全员常态，只有「别人在说」时才压暗非说话者
      active: !roomStore.activeSpeaker || roomStore.activeSpeaker === code,
      box: slotBox(index, total),
    };
  });
});
</script>

<style scoped>
/* 槽位换宽（1 人 ↔ 2 人：100% ↔ 45%）与换边都要平滑，否则立绘是瞬间跳位。
   时长沿用 script:modify-character 的 duration（--role-slot-duration，缺省 0.5s）。 */
.role-slot-box,
.role-slot-enter-active,
.role-slot-leave-active {
  transition:
    left var(--role-slot-duration, 0.5s) cubic-bezier(0.25, 0.8, 0.5, 1),
    width var(--role-slot-duration, 0.5s) cubic-bezier(0.25, 0.8, 0.5, 1),
    opacity var(--role-slot-duration, 0.5s) ease-in-out;
}

.role-slot-enter-from,
.role-slot-leave-to {
  opacity: 0;
}
</style>
