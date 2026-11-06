<template>
  <div ref="rootRef" class="relative">
    <button
      type="button"
      class="glass-input flex cursor-pointer items-center justify-between gap-2 text-left"
      :aria-expanded="open"
      @click="toggle"
    >
      <span class="min-w-0 truncate text-white/80">{{ summary }}</span>
      <ChevronDown
        :size="16"
        class="shrink-0 text-white/40 transition-transform"
        :class="{ 'rotate-180': open }"
      />
    </button>

    <Transition name="menu-pop">
      <div v-if="open" class="multi-menu" @click.stop>
        <input
          v-if="searchable"
          v-model="keyword"
          class="glass-input menu-search"
          :placeholder="searchPlaceholder"
        />

        <div class="menu-list">
          <label v-for="option in filteredOptions" :key="option.value" class="menu-item">
            <input
              type="checkbox"
              class="shrink-0 cursor-pointer"
              :checked="modelValue.includes(option.value)"
              @change="toggleOption(option.value)"
            />
            <span class="min-w-0 flex-1 truncate">{{ option.label }}</span>
            <span v-if="option.hint" class="menu-hint">{{ option.hint }}</span>
          </label>

          <p v-if="filteredOptions.length === 0" class="menu-empty">{{ emptyText }}</p>
        </div>

        <div class="menu-foot">
          <button type="button" class="menu-clear" @click="clear">清空选择</button>
          <span>已选 {{ modelValue.length }} 项</span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
/**
 * 多选下拉：视觉与 `RoleExportMenu` 的弹层一致（`menu-pop` 动效 + 深色菜单行），
 * 触发器直接复用全局 `glass-input`，这样与设置页其它输入控件同一套边框/字号。
 *
 * 只负责「选哪些」，不关心业务含义；summary 由调用方传入，避免把「已绑定 N 本」这类文案写死在组件里。
 */
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ChevronDown } from "@lucide/vue";

interface Option {
  value: string;
  label: string;
  /** 右侧灰字提示，例如「5 段」。 */
  hint?: string;
}

const props = withDefaults(
  defineProps<{
    modelValue: string[];
    options: Option[];
    /** 触发器上的文案，由调用方拼好（例如「已绑定 2 本：A、B」）。 */
    summary: string;
    searchable?: boolean;
    searchPlaceholder?: string;
    emptyText?: string;
  }>(),
  {
    searchable: true,
    searchPlaceholder: "搜索",
    emptyText: "没有可选项",
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: string[]];
}>();

const open = ref(false);
const keyword = ref("");
const rootRef = ref<HTMLElement | null>(null);

const filteredOptions = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) return props.options;
  return props.options.filter(
    (option) =>
      option.label.toLowerCase().includes(text) || option.value.toLowerCase().includes(text),
  );
});

function toggle() {
  open.value = !open.value;
}

/** 供调用方在「新增文档」后自动展开，让新条目立刻可见。 */
function openPanel() {
  open.value = true;
}

function closePanel() {
  open.value = false;
}

function toggleOption(value: string) {
  const next = props.modelValue.includes(value)
    ? props.modelValue.filter((item) => item !== value)
    : [...props.modelValue, value];
  emit("update:modelValue", next);
}

function clear() {
  emit("update:modelValue", []);
}

// 点空白处收起，避免弹层挡住下面的表单
function onDocumentClick(event: MouseEvent) {
  if (open.value && !rootRef.value?.contains(event.target as Node)) {
    open.value = false;
  }
}
onMounted(() => document.addEventListener("click", onDocumentClick));
onUnmounted(() => document.removeEventListener("click", onDocumentClick));

defineExpose({ openPanel, closePanel });
</script>

<style scoped>
/* 与 RoleExportMenu 的 .export-menu 同一套观感：深色半透明 + 20px 模糊 + 白 10% 描边 */
.multi-menu {
  position: absolute;
  inset-inline: 0;
  top: 100%;
  z-index: 50;
  margin-top: 0.25rem;
  overflow: hidden;
  border-radius: 0.75rem;
  padding: 0.375rem;
  background: rgba(15, 15, 15, 0.85);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
}

.menu-search {
  margin-bottom: 0.5rem;
  padding: 0.5rem 0.625rem;
  font-size: 0.75rem;
}

.menu-list {
  max-height: 14rem;
  overflow-y: auto;
}

/* 与 RoleExportMenu 的 .menu-item 同行高与 hover 反馈 */
.menu-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.625rem;
  border-radius: 0.5rem;
  padding: 0.5rem 0.625rem;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.8);
  transition:
    background-color 0.15s,
    color 0.15s;
}
.menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.menu-hint {
  flex-shrink: 0;
  font-size: 0.6875rem;
  color: rgba(255, 255, 255, 0.35);
}

.menu-empty {
  padding: 0.5rem 0.625rem;
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.4);
}

.menu-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 0.5rem;
  padding-top: 0.5rem;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  font-size: 0.6875rem;
  color: rgba(255, 255, 255, 0.45);
}

.menu-clear {
  border: none;
  background: transparent;
  padding: 0;
  font-size: 0.6875rem;
  color: inherit;
  cursor: pointer;
}
.menu-clear:hover {
  color: rgba(255, 255, 255, 0.8);
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
