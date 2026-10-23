<template>
  <div v-for="message in dialog" :key="message.id" class="message-item">
    <div
      v-if="message.type === 'message' && message.content && message.content.trim() !== ''"
      class="player-message"
      @click="$emit('open-user', message)"
    >
      <DialogUser :name="message.displayName" :content="message.content" />
    </div>
    <div
      v-else-if="message.type === 'reply' && message.content && message.content.trim() !== ''"
      class="chararter-reply"
      @click="$emit('open-character', message)"
    >
      <DialogCharacter
        :name="message.displayName"
        :content="message.content"
        :action_content="message.motionText"
        :emotionTag="message.originalTag ?? ''"
        :emotionText="message.comment ?? ''"
        @click="$emit('open-character', message)"
      />
    </div>
    <div v-if="message.isFinal" class="final-spacer"></div>
  </div>
</template>

<script lang="ts">
/** 一条会话记录（原版 GameMessage 在本项目的等价结构） */
export interface SessionMessage {
  id: string;
  type: "message" | "reply";
  displayName: string;
  content: string;
  /** 动作 / 括注文本，对应原版 motionText */
  motionText?: string;
  /** 情绪标签，对应原版 originalTag */
  originalTag?: string;
  isFinal?: boolean;
  /** 复盘信息：本题得分 / 面试官点评 */
  score?: number;
  comment?: string;
}
</script>

<script setup lang="ts">
import DialogUser from "./DialogUser.vue";
import DialogCharacter from "./DialogCharacter.vue";

defineProps({
  dialog: {
    type: Array as () => SessionMessage[],
    required: true,
  },
});

defineEmits<{
  (e: "open-user", message: SessionMessage): void;
  (e: "open-character", message: SessionMessage): void;
}>();
</script>

<style scoped>
.message-item {
  line-height: 1.2;
  word-wrap: break-word;
}

.player-message {
  cursor: pointer;
  display: inline-block; /* 让边框包裹内容 */
  border-radius: 6px;
  padding: 1px 3px;
  transition:
    background 0.2s,
    color 0.2s;
}

.player-message:hover {
  background: rgba(255, 255, 255, 0.08);
}

.chararter-reply {
  cursor: pointer;
  display: inline-block; /* 让边框包裹内容 */
  border-radius: 6px;
  padding: 1px 3px;
  transition:
    background 0.2s,
    color 0.2s;
}

.chararter-reply:hover {
  background: rgba(121, 217, 255, 0.1);
}

/* 为 isFinal 消息的间隔元素添加样式 */
.final-spacer {
  height: 1em; /* 高度约等于一个空行 */
}
</style>
