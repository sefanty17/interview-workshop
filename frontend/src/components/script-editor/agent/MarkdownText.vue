<script setup lang="ts">
/**
 * 把 AI 回复渲染出来（原版 `MarkdownText.vue` 的降级版，规格 E7b1 §3.3）。
 *
 * - 原版用 `marked` 解析 GFM（表格/任务列表/删除线），原始 HTML 一律转义防 XSS，
 *   链接交给 Tauri 的 opener 在外部浏览器打开。
 * - 本项目**没有安装 marked**，也不许为这一批新增依赖，所以走最简安全渲染：
 *   纯文本 + `white-space: pre-wrap` 保留换行，不做 HTML 解析 ——
 *   prompt injection 带来的 `<script>` / `<img onerror>` 因此只会显示成可见文本。
 *   外层仍用全局的 `.markdown-body` 类拿到深色主题下的字号与行高。
 * - V2 装好 markdown 库后按原版逐字恢复（marked + escapeHtml + 外链打开）。
 */
defineProps<{ content: string }>();
</script>

<template>
  <div class="markdown-body whitespace-pre-wrap">{{ content }}</div>
</template>
