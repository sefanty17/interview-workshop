<template>
  <div class="relative">
    <button class="export-trigger" title="导出角色" @click.stop="toggle">
      <Upload :size="24" />
    </button>

    <Transition name="menu-pop">
      <div v-if="open" class="export-menu" @click.stop>
        <div class="menu-header">{{ roleName }} - 导出</div>
        <button class="menu-item" @click="choose('zip')">
          <span class="dot dot-zip"></span>
          <span class="label">ZIP</span>
          <span class="hint">通用压缩</span>
        </button>
        <button class="menu-item" @click="choose('7z')">
          <span class="dot dot-7z"></span>
          <span class="label">7Z</span>
          <span class="hint">高压缩率</span>
        </button>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { Upload } from "@lucide/vue";
import { alertDialog } from "../../store/dialogStore";

// 原版依赖 Tauri 的 role-archive 导出命令。本批只做 UI：点菜单项弹提示，不做真实导出
const props = defineProps<{
  roleId: string;
  roleName: string;
}>();

const open = ref(false);

function toggle() {
  open.value = !open.value;
}

function close() {
  open.value = false;
}

async function choose(fmt: "zip" | "7z") {
  close();
  await alertDialog(
    "导出功能待后端接入",
    `将把「${props.roleName}」导出为 ${fmt.toUpperCase()} 压缩包（${props.roleId}）。该能力依赖后端归档服务，本版本暂未接入。`,
  );
}

function onDocClick() {
  close();
}

onMounted(() => {
  // 延迟注册，避免捕获刚刚用于打开菜单的点击事件。
  setTimeout(() => document.addEventListener("click", onDocClick), 0);
});
onUnmounted(() => {
  document.removeEventListener("click", onDocClick);
});
</script>

<style scoped>
/* 原版用 @apply 写的，这里展开成等效原生 CSS（本项目没有给 SFC style 配 tailwind 的 @reference） */
.export-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 9999px;
  padding: 0.25rem;
  background: rgba(0, 0, 0, 0.05);
  color: rgba(255, 255, 255, 0.6);
  cursor: pointer;
  transition: all 0.2s ease;
}
.export-trigger:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.export-menu {
  position: absolute;
  top: 100%;
  right: 0;
  z-index: 50;
  min-width: 11.25rem;
  overflow: hidden;
  border-radius: 0.75rem;
  padding: 0.375rem;
  background: rgba(15, 15, 15, 0.85);
  backdrop-filter: blur(20px);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.menu-header {
  padding: 0.375rem 0.75rem;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.05em;
  color: rgba(255, 255, 255, 0.5);
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  margin-bottom: 4px;
}

.menu-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.625rem;
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: rgba(255, 255, 255, 0.8);
  transition: background-color 0.15s, color 0.15s;
}
.menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.dot {
  height: 0.5rem;
  width: 0.5rem;
  flex-shrink: 0;
  border-radius: 9999px;
}
.dot-zip {
  background: #fbbf24;
}
.dot-7z {
  background: #79d9ff;
}

.label {
  width: 2rem;
  font-size: 0.875rem;
  font-weight: 700;
}
.hint {
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.4);
}

.menu-pop-enter-active,
.menu-pop-leave-active {
  transition: all 0.18s ease;
}
.menu-pop-enter-from,
.menu-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.96);
}
</style>
