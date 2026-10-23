<template>
  <!-- 视图：日程主题列表 -->
  <div v-if="scheduleStore.scheduleView === 'schedule_groups'">
    <div v-if="Object.keys(scheduleStore.scheduleGroups).length === 0" class="py-20 text-center text-slate-300">
      <Inbox class="mx-auto mb-4 h-10 w-10 opacity-20" />
      <p>还没有日程主题，点击右上角新建一个吧</p>
    </div>
    <div v-else class="grid grid-cols-1 gap-6 sm:grid-cols-1 lg:grid-cols-2">
      <div
        v-for="(group, id) in scheduleStore.scheduleGroups"
        :key="id"
        class="group glass-effect border-brand relative cursor-pointer rounded-3xl border p-6 shadow-sm transition-all hover:-translate-y-1 hover:shadow-xl"
        @click="selectGroup(String(id))"
      >
        <button
          class="absolute top-4 right-4 z-10 cursor-pointer border-none bg-transparent p-1 text-slate-300 hover:text-red-400"
          @click.stop="removeGroup(String(id))"
        >
          <Trash2 :size="18" />
        </button>
        <div
          class="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-cyan-500 text-cyan-50 transition-colors group-hover:bg-cyan-50 group-hover:text-cyan-500"
        >
          <FolderKanban></FolderKanban>
        </div>
        <h3 class="text-brand text-lg font-bold">
          {{ group.title }}
        </h3>
        <p class="mt-2 line-clamp-2 text-sm text-white">
          {{ group.description }}
        </p>
        <div
          class="text-brand mt-6 flex items-center justify-between border-t border-slate-50 pt-4 text-xs font-bold"
        >
          <span>{{ group.items.length }} 个日程</span>
          <ArrowRight :size="16" />
        </div>
      </div>
    </div>
  </div>

  <!-- 视图：日程详情列表（同一天归到一组） -->
  <div v-if="scheduleStore.scheduleView === 'schedule_detail'" class="mx-auto max-w-3xl space-y-6">
    <div v-if="activeGroup.items.length === 0" class="py-20 text-center text-slate-300">
      <Inbox class="mx-auto mb-4 h-10 w-10 opacity-20" />
      <p>还没有日程，点击右上角新建一个吧</p>
    </div>
    <div v-for="day in groupedItems" :key="day.date" class="space-y-3">
      <div class="flex items-center gap-2 pl-1 text-xs font-black tracking-[0.2em] uppercase">
        <CalendarDays :size="14" class="text-cyan-400" />
        <span class="text-slate-50">{{ day.label }}</span>
        <span class="text-white/40">{{ day.items.length }} 项</span>
      </div>
      <div
        v-for="item in day.items"
        :key="item.id"
        class="glass-effect flex items-start space-x-4 rounded-2xl border border-slate-100 p-5 shadow-sm"
      >
        <!-- 完成状态切换 -->
        <button
          class="mt-1 flex h-6 w-6 shrink-0 cursor-pointer items-center justify-center rounded-lg border-2 transition-all"
          :class="item.done ? 'border-cyan-500 bg-cyan-500' : 'border-slate-100 hover:border-cyan-500'"
          :aria-label="item.done ? '标记为未完成' : '标记为已完成'"
          @click="toggleDone(item.id)"
        >
          <Check v-if="item.done" class="h-4 w-4 text-white" />
        </button>
        <div class="self-center rounded-lg bg-cyan-500 px-3 py-1 text-xs font-bold text-white">
          {{ item.time }}
        </div>
        <div class="flex-1">
          <h4
            class="text-brand text-lg font-bold"
            :class="item.done ? 'line-through opacity-60' : ''"
          >
            {{ item.name }}
          </h4>
          <p class="mt-1 text-sm text-white">{{ item.content }}</p>
        </div>
        <button
          class="cursor-pointer border-none bg-transparent p-1 text-slate-300 hover:text-cyan-400"
          aria-label="编辑日程"
          @click="openEdit(item)"
        >
          <Pencil :size="16" />
        </button>
        <button
          class="cursor-pointer border-none bg-transparent p-1 text-slate-300 hover:text-red-400"
          aria-label="删除日程"
          @click="removeItem(item)"
        >
          <Trash2 :size="16" />
        </button>
      </div>
    </div>
  </div>

  <!-- 引入通用模态框 -->
  <BaseModal
    :show="showModal"
    :title="modalTitle"
    theme="glass"
    @close="showModal = false"
    @confirm="confirmCreate"
  >
    <!-- 场景1：新建日程组 -->
    <template v-if="modalMode === 'group'">
      <input
        v-model="formData.groupTitle"
        placeholder="主题名称"
        :class="glassField"
      />
      <textarea
        v-model="formData.groupDesc"
        placeholder="描述..."
        rows="3"
        :class="[glassField, 'resize-none']"
      ></textarea>
    </template>

    <!-- 场景2：新建 / 编辑日程项 -->
    <template v-else>
      <input v-model="formData.itemName" placeholder="活动名称" :class="glassField" />
      <div class="flex gap-3">
        <input
          v-model="formData.itemDate"
          type="date"
          class="min-w-0 flex-1 rounded-2xl border border-white/10 bg-white/10 px-4 py-4 text-sm text-white transition-all outline-none [color-scheme:dark] focus:border-brand focus:ring-2 focus:ring-cyan-500/30"
        />
        <input
          v-model="formData.itemTime"
          type="time"
          class="w-32 shrink-0 rounded-2xl border border-white/10 bg-white/10 px-4 py-4 text-sm text-white transition-all outline-none [color-scheme:dark] focus:border-brand focus:ring-2 focus:ring-cyan-500/30"
        />
      </div>
      <textarea
        v-model="formData.itemContent"
        placeholder="指令详情..."
        rows="2"
        :class="[glassField, 'resize-none']"
      ></textarea>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
/**
 * 日程页（原版 components/schedule/pages/SchedulePage.vue）
 *
 * groups 视图 = 主题卡片墙；detail 视图 = 该主题下的日程，按日期分组。
 * 原版 detail 是一条平铺列表，这里补上日期分组 / 完成勾选 / 编辑，
 * 数据全部读写 store/scheduleStore.ts。
 */
import { computed, reactive, ref } from "vue";
import { ArrowRight, CalendarDays, Check, FolderKanban, Inbox, Pencil, Trash2 } from "@lucide/vue";
import BaseModal from "../BaseModal.vue";
import {
  scheduleStore,
  addScheduleItem,
  createScheduleGroup,
  removeScheduleGroup,
  removeScheduleItem,
  toggleScheduleDone,
  updateScheduleItem,
  type ScheduleItem,
} from "../../../store/scheduleStore";
import { confirmDialog } from "../../../store/dialogStore";

const selectedGroupId = ref<string | null>(null);

const activeGroup = computed<{ items: ScheduleItem[] }>(() => {
  const id = selectedGroupId.value;
  if (!id) return { items: [] };
  return scheduleStore.scheduleGroups[id] ?? { items: [] };
});

const WEEK_LABELS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

const dateLabel = (date: string): string => {
  const d = new Date(`${date}T00:00:00`);
  if (Number.isNaN(d.getTime())) return date;
  const today = new Date();
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate();
  const base = `${d.getMonth() + 1}月${d.getDate()}日 ${WEEK_LABELS[d.getDay()]}`;
  return sameDay ? `今天 · ${base}` : base;
};

/** 详情页按日期分组，日期升序（同一天内按时间升序） */
const groupedItems = computed(() => {
  const buckets = new Map<string, ScheduleItem[]>();
  [...activeGroup.value.items]
    .sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time))
    .forEach((item) => {
      const key = item.date || "未定日期";
      const list = buckets.get(key) ?? [];
      list.push(item);
      buckets.set(key, list);
    });
  return [...buckets.entries()].map(([date, items]) => ({
    date,
    label: date === "未定日期" ? "未定日期" : dateLabel(date),
    items,
  }));
});

const selectGroup = (id: string) => {
  selectedGroupId.value = id;
  scheduleStore.scheduleView = "schedule_detail";
};

const removeGroup = async (id: string) => {
  const group = scheduleStore.scheduleGroups[id];
  if (!group) return;
  const ok = await confirmDialog(
    "删除日程主题",
    `确定删除「${group.title}」吗？组内 ${group.items.length} 条日程会一并删除。`,
  );
  if (!ok) return;
  removeScheduleGroup(id);
  if (selectedGroupId.value === id) {
    selectedGroupId.value = null;
    scheduleStore.scheduleView = "schedule_groups";
  }
};

const toggleDone = (itemId: string) => {
  if (!selectedGroupId.value) return;
  toggleScheduleDone(selectedGroupId.value, itemId);
};

const removeItem = async (item: ScheduleItem) => {
  const ok = await confirmDialog("删除日程", `确定删除「${item.name}」吗？`);
  if (!ok || !selectedGroupId.value) return;
  removeScheduleItem(selectedGroupId.value, item.id);
};

// 模态框状态
const showModal = ref(false);
const modalMode = ref<"group" | "item">("group");
const editingId = ref<string | null>(null);
const formData = reactive({
  groupTitle: "",
  groupDesc: "",
  itemName: "",
  itemDate: "",
  itemTime: "",
  itemContent: "",
});

/** 弹窗内的玻璃态输入框样式，与待办页一致 */
const glassField =
  "w-full rounded-2xl border border-white/10 bg-white/10 px-5 py-4 text-sm text-white transition-all outline-none placeholder:text-white/40 focus:border-brand focus:ring-2 focus:ring-cyan-500/30 [color-scheme:dark]";

const modalTitle = computed(() => {
  if (modalMode.value === "group") return "新建日程主题";
  return editingId.value ? "编辑日程" : "新建具体日程";
});

const resetForm = () => {
  formData.groupTitle = "";
  formData.groupDesc = "";
  formData.itemName = "";
  formData.itemDate = todayISO();
  formData.itemTime = "";
  formData.itemContent = "";
  editingId.value = null;
};

function todayISO(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 父组件「新建」按钮分发入口 */
const handleCreate = () => {
  resetForm();
  modalMode.value = scheduleStore.scheduleView === "schedule_groups" ? "group" : "item";
  showModal.value = true;
};

const openEdit = (item: ScheduleItem) => {
  resetForm();
  modalMode.value = "item";
  editingId.value = item.id;
  formData.itemName = item.name;
  formData.itemDate = item.date;
  formData.itemTime = item.time;
  formData.itemContent = item.content;
  showModal.value = true;
};

const confirmCreate = () => {
  if (modalMode.value === "group") {
    if (!formData.groupTitle.trim()) return;
    const id = createScheduleGroup(formData.groupTitle.trim(), formData.groupDesc.trim());
    showModal.value = false;
    resetForm();
    // 新建分组后直接进入，少点一次
    selectGroup(id);
    return;
  }

  if (!formData.itemName.trim() || !selectedGroupId.value) return;
  const payload = {
    name: formData.itemName.trim(),
    date: formData.itemDate || todayISO(),
    time: formData.itemTime || "09:00",
    content: formData.itemContent.trim(),
  };
  if (editingId.value) {
    updateScheduleItem(selectedGroupId.value, editingId.value, payload);
  } else {
    addScheduleItem(selectedGroupId.value, payload);
  }
  showModal.value = false;
  resetForm();
};

defineExpose({ handleCreate });
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供） */
.text-brand {
  color: var(--accent-color);
}
.bg-brand {
  background-color: var(--accent-color);
}
.border-brand {
  border-color: var(--accent-color);
}
.focus\:border-brand:focus {
  border-color: var(--accent-color);
}
</style>
