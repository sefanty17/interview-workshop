<template>
  <section :class="['menu-item', size]">
    <div class="border-brand mb-4 flex w-full items-center space-x-2 border-b-2 pb-2">
      <slot name="header"></slot>
      <div class="title-wrapper">
        <h4>{{ displayTitle }}</h4>
      </div>
    </div>
    <div class="content">
      <slot></slot>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = defineProps({
  title: {
    type: String,
    default: "",
  },
  size: {
    type: String as () => "small" | "large",
    default: "large",
    validator: (value: string) => ["small", "large"].includes(value),
  },
});

// 原版走 i18n 的 ui.menuItem.defaultTitle，本项目没有 i18n，直接给中文兜底
const displayTitle = computed(() => props.title || "未命名");
</script>

<style scoped>
section {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 15px;
}

section.large {
  width: 100%;
}

section.small {
  width: calc(50% - 12.5px); /* 减去一半的margin */
}

.title-wrapper h4 {
  margin: 0;
  color: #fff;
  font-weight: 600;
  /* 原版这里不写字号，靠 html 的 16px 基准继承；本项目 body 是 14px，显式补回 16px */
  font-size: 1rem;
  text-shadow: 0 0 2px rgba(0, 0, 0, 0.5);
}

.content {
  width: 100%;
}

/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供）。
   标题栏底部的青色线保留原版 border-brand 类名，此处补等效描边色 */
.border-brand {
  border-color: var(--accent-color);
}

/* 响应式设计 - 在小屏幕上让small菜单项变为全宽 */
@media (max-width: 768px) {
  section.small {
    width: 100%;
    max-width: 900px;
  }
}
</style>
