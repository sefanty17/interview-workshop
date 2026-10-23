<template>
  <div v-if="scheduleStore.scheduleView === 'todo_groups'" class="space-y-8">
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <div
        v-for="(group, id) in scheduleStore.todoGroups"
        :key="'group-' + id"
        class="group glass-effect relative flex cursor-pointer items-center justify-between rounded-2xl border border-slate-100 p-5 shadow-sm transition-all hover:border-cyan-200"
        @click="selectTodoGroup(String(id))"
      >
        <button
          class="absolute top-1 left-1 z-10 cursor-pointer border-none bg-transparent p-1 text-slate-200 hover:text-red-400"
          @click.stop="removeGroup(String(id))"
        >
          <Trash2 :size="18" />
        </button>
        <div class="ml-2 flex items-center space-x-4">
          <div
            class="flex h-10 w-10 items-center justify-center rounded-xl bg-cyan-500 text-cyan-50 transition-all group-hover:bg-cyan-50 group-hover:text-cyan-500"
          >
            <Folder />
          </div>
          <div>
            <h4 class="text-brand font-bold">
              {{ group.title }}
            </h4>
            <p class="text-[10px] font-bold text-white uppercase">
              {{ group.todos.length }} 项任务
            </p>
          </div>
        </div>
        <ChevronRight class="text-slate-200 group-hover:text-cyan-500" />
      </div>
    </div>

    <!-- High Priority Global Tasks -->
    <div class="space-y-4">
      <h3 class="flex items-center text-xs font-black tracking-[0.2em] text-slate-50 uppercase">
        <Zap class="mr-2 h-3 w-3 text-amber-400" />
        全局进行中 (按优先级)
      </h3>
      <div
        v-if="globalPendingTodos.length === 0"
        class="text-brand font-blod rounded-3xl border border-dashed border-slate-200 py-10 text-center text-xl"
      >
        暂时没有进行中的任务
      </div>
      <div
        v-for="todo in globalPendingTodos"
        :key="'global-' + todo.id"
        class="glass-effect flex cursor-pointer items-center space-x-4 rounded-2xl border-l-4 border-l-cyan-500 p-4 shadow-sm"
        @click="openEdit(todo)"
      >
        <button
          class="h-6 w-6 cursor-pointer rounded-lg border-2 border-cyan-100 bg-transparent transition-all hover:border-cyan-500"
          aria-label="完成待办"
          @click.stop="completeTodo(todo)"
        ></button>

        <div class="flex-1">
          <div class="flex items-center space-x-2">
            <span class="rounded bg-white/80 px-1.5 py-0.5 text-[11px] font-bold text-cyan-500">{{
              todo.groupTitle
            }}</span>
            <p class="font-bold text-cyan-50">{{ todo.text }}</p>
          </div>
          <div class="mt-1 flex items-center">
            <Star
              v-for="s in 5"
              :key="'star-global-' + todo.id + '-' + s"
              :class="[
                'h-3 w-3',
                s <= todo.priority ? 'fill-amber-400 text-amber-400' : 'text-slate-100',
              ]"
            />
          </div>
          <div
            v-if="todo.deadline"
            class="mt-1 flex items-center gap-1 text-[11px] font-bold"
            :class="isOverdue(todo) ? 'text-red-400' : 'text-cyan-500'"
          >
            <CalendarClock class="h-3 w-3" />
            <span>{{ formatDeadline(todo.deadline) }}</span>
            <BellRing v-if="todo.remindBeforeMinutes" class="h-3 w-3" />
          </div>
        </div>
      </div>
    </div>

    <!-- Global Completed History -->
    <div v-if="globalCompletedTodos.length > 0" class="space-y-3">
      <button
        class="flex cursor-pointer items-center space-x-2 border-none bg-transparent px-1 text-slate-400 transition-colors hover:text-cyan-600"
        @click="showCompleted = !showCompleted"
      >
        <component :is="showCompleted ? ChevronDown : ChevronRight" class="h-4 w-4" />
        <span class="text-[10px] font-black tracking-widest uppercase"
          >已完成历史 ({{ globalCompletedTodos.length }})</span
        >
      </button>
      <div v-if="showCompleted" class="space-y-2">
        <div
          v-for="todo in globalCompletedTodos"
          :key="'done-' + todo.id"
          class="flex items-center space-x-4 rounded-2xl border border-slate-100 bg-slate-50/50 p-4 opacity-50"
        >
          <CheckCircle class="h-5 w-5 text-cyan-500" />
          <div class="flex-1">
            <div class="flex items-center space-x-2">
              <span class="text-brand rounded border border-slate-200 px-1.5 py-0.5 text-[9px]">{{
                todo.groupTitle
              }}</span>
              <p class="text-sm text-gray-200 line-through">
                {{ todo.text }}
              </p>
            </div>
          </div>
          <button
            class="cursor-pointer border-none bg-transparent text-[10px] font-bold text-cyan-600 hover:underline"
            @click.stop="undoComplete(todo)"
          >
            撤回
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- Todo Detail View -->
  <div v-if="scheduleStore.scheduleView === 'todo_detail'" class="mx-auto max-w-2xl space-y-4">
    <div v-if="activeTodoGroup.todos.length === 0" class="py-20 text-center text-slate-300">
      <Inbox class="mx-auto mb-4 h-10 w-10 opacity-20" />
      <p>还没有任务，点击右上角新建一个吧</p>
    </div>
    <div
      v-for="todo in activeTodoGroup.todos"
      :key="'detail-todo-' + todo.id"
      class="glass-effect flex cursor-pointer items-center space-x-4 rounded-2xl border border-slate-100 p-4 shadow-sm transition-all"
      :class="todo.completed ? 'opacity-50' : ''"
      @click="openEdit(todo)"
    >
      <button
        class="flex h-6 w-6 cursor-pointer items-center justify-center rounded-lg border-2 transition-all"
        :class="
          todo.completed ? 'border-cyan-500 bg-cyan-500' : 'border-slate-100 bg-transparent hover:border-cyan-500'
        "
        :aria-label="todo.completed ? '撤回完成' : '完成待办'"
        @click.stop="todo.completed ? undoComplete(todo) : completeTodo(todo)"
      >
        <Check v-if="todo.completed" class="h-4 w-4 text-white" />
      </button>
      <div class="flex-1">
        <p :class="['font-medium text-white', todo.completed ? 'line-through' : '']">
          {{ todo.text }}
        </p>
        <div class="mt-1 flex items-center">
          <Star
            v-for="s in 5"
            :key="'star-detail-' + todo.id + '-' + s"
            :class="[
              'h-3 w-3',
              s <= todo.priority ? 'fill-amber-400 text-amber-400' : 'text-slate-100',
            ]"
          />
        </div>
        <div
          v-if="todo.deadline"
          class="mt-1 flex items-center gap-1 text-[11px] font-bold"
          :class="isOverdue(todo) ? 'text-red-400' : 'text-cyan-500'"
        >
          <CalendarClock class="h-3 w-3" />
          <span>{{ formatDeadline(todo.deadline) }}</span>
          <BellRing v-if="todo.remindBeforeMinutes" class="h-3 w-3" />
        </div>
      </div>
      <button
        class="cursor-pointer border-none bg-transparent p-2 text-slate-200 hover:text-red-400"
        aria-label="删除待办"
        @click.stop="removeItem(todo)"
      >
        <Trash2 />
      </button>
    </div>
  </div>

  <BaseModal
    :show="showModal"
    :title="modalTitle"
    theme="glass"
    @close="showModal = false"
    @confirm="confirmCreate"
  >
    <!-- 场景1：新建待办分组 -->
    <template v-if="modalMode === 'group'">
      <input
        v-model="formData.groupTitle"
        placeholder="项目名称 (例如: 学校任务)"
        :class="glassField"
      />
    </template>

    <!-- 场景2：新建具体任务 -->
    <template v-else>
      <input v-model="formData.todoText" placeholder="任务内容" :class="glassField" />
      <div class="flex items-center space-x-3 rounded-2xl border border-white/10 bg-white/10 p-2">
        <span class="pl-2 text-xs font-bold text-white/50 uppercase">优先级:</span>
        <button
          v-for="s in 5"
          :key="'prio-' + s"
          class="transform cursor-pointer border-none bg-transparent transition-transform focus:outline-none active:scale-125"
          @click="formData.priority = s"
        >
          <Star
            :size="24"
            :class="[s <= formData.priority ? 'fill-amber-400 text-amber-400' : 'text-white/25']"
          />
        </button>
      </div>

      <!-- 截止时间（可选） -->
      <div class="flex items-center gap-2 rounded-2xl border border-white/10 bg-white/10 p-2">
        <CalendarClock class="ml-2 h-4 w-4 shrink-0 text-white/50" />
        <input
          v-model="formData.deadline"
          type="datetime-local"
          class="min-w-0 flex-1 rounded-xl border-none bg-transparent px-2 py-2 text-sm text-white [color-scheme:dark] outline-none"
        />
        <button
          v-if="formData.deadline"
          class="shrink-0 cursor-pointer border-none bg-transparent px-2 text-white/50 hover:text-red-400"
          @click="formData.deadline = ''"
        >
          <X :size="16" />
        </button>
      </div>

      <!-- 提前提醒（可选，依赖截止时间） -->
      <div class="flex items-center gap-2 rounded-2xl border border-white/10 bg-white/10 p-2">
        <BellRing
          class="ml-2 h-4 w-4 shrink-0"
          :class="formData.deadline ? 'text-white/50' : 'text-white/25'"
        />
        <select
          v-model="formData.remindBeforeMinutes"
          :disabled="!formData.deadline"
          class="select-dark min-w-0 flex-1 rounded-xl border-none bg-transparent px-2 py-2 text-sm text-white [color-scheme:dark] outline-none disabled:text-white/30"
        >
          <option v-for="opt in reminderOptions" :key="String(opt.value)" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
/**
 * 待办页（原版 components/schedule/pages/TodoPage.vue）
 *
 * 分组总览 + 全局进行中（按优先级）+ 已完成历史；详情页勾选 / 编辑 / 删除。
 * 原版读写 Tauri 本地库，这里全部换成 store/scheduleStore.ts。
 */
import { computed, reactive, ref } from "vue";
import {
  BellRing,
  CalendarClock,
  Check,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  Folder,
  Inbox,
  Star,
  Trash2,
  X,
  Zap,
} from "@lucide/vue";
import BaseModal from "../BaseModal.vue";
import {
  scheduleStore,
  addTodo,
  createTodoGroup,
  findTodo,
  removeTodo,
  removeTodoGroup,
  setTodoCompleted,
  type TodoItem,
} from "../../../store/scheduleStore";
import { confirmDialog } from "../../../store/dialogStore";

interface TodoItemWithGroup extends TodoItem {
  groupTitle: string;
  gid: string;
}

const showCompleted = ref(false);
const selectedTodoGroupId = ref<string | null>(null);

const activeTodoGroup = computed<{ todos: TodoItem[] }>(() => {
  const id = selectedTodoGroupId.value;
  if (!id) return { todos: [] };
  return scheduleStore.todoGroups[id] ?? { todos: [] };
});

const globalPendingTodos = computed<TodoItemWithGroup[]>(() => {
  const list: TodoItemWithGroup[] = [];
  Object.keys(scheduleStore.todoGroups).forEach((gid) => {
    const group = scheduleStore.todoGroups[gid];
    if (group) {
      group.todos.forEach((t) => {
        if (!t.completed) list.push({ ...t, groupTitle: group.title, gid });
      });
    }
  });
  return list.sort((a, b) => b.priority - a.priority);
});

const globalCompletedTodos = computed<TodoItemWithGroup[]>(() => {
  const list: TodoItemWithGroup[] = [];
  Object.keys(scheduleStore.todoGroups).forEach((gid) => {
    const group = scheduleStore.todoGroups[gid];
    if (group) {
      group.todos.forEach((t) => {
        if (t.completed) list.push({ ...t, groupTitle: group.title, gid });
      });
    }
  });
  return list;
});

/** 按 gid 还原原始条目（全局视图传来的是副本，详情视图是原始引用） */
const resolveOriginal = (
  todo: TodoItem | TodoItemWithGroup,
  gid?: string | null,
): { gid: string; todo: TodoItem } | null => {
  const targetGid = gid ?? (todo as TodoItemWithGroup).gid ?? selectedTodoGroupId.value;
  if (!targetGid) return null;
  const found = findTodo(targetGid, todo.id);
  return found ? { gid: targetGid, todo: found } : null;
};

const completeTodo = (todo: TodoItem | TodoItemWithGroup) => {
  const hit = resolveOriginal(todo);
  if (hit) setTodoCompleted(hit.gid, hit.todo.id, true);
};

const undoComplete = (todo: TodoItem | TodoItemWithGroup) => {
  const hit = resolveOriginal(todo);
  if (hit) setTodoCompleted(hit.gid, hit.todo.id, false);
};

const removeItem = async (todo: TodoItem) => {
  if (!selectedTodoGroupId.value) return;
  const ok = await confirmDialog("删除待办", `确定删除「${todo.text}」吗？`);
  if (!ok) return;
  removeTodo(selectedTodoGroupId.value, todo.id);
};

const removeGroup = async (id: string) => {
  const group = scheduleStore.todoGroups[id];
  if (!group) return;
  const ok = await confirmDialog(
    "删除任务组",
    `确定删除「${group.title}」吗？组内 ${group.todos.length} 项任务会一并删除。`,
  );
  if (!ok) return;
  removeTodoGroup(id);
  if (selectedTodoGroupId.value === id) {
    selectedTodoGroupId.value = null;
    scheduleStore.scheduleView = "todo_groups";
  }
};

const selectTodoGroup = (id: string) => {
  selectedTodoGroupId.value = id;
  scheduleStore.scheduleView = "todo_detail";
};

const showModal = ref(false);
/** 弹窗场景。不能只看 scheduleView：分组总览页也能点开待办条目做编辑。 */
const modalMode = ref<"group" | "task">("task");
const editingId = ref<number | null>(null);
const editingGid = ref<string | null>(null);
const formData = reactive({
  groupTitle: "",
  todoText: "",
  priority: 1,
  /** datetime-local 的原生格式：YYYY-MM-DDTHH:mm，空串表示未设 */
  deadline: "",
  remindBeforeMinutes: null as number | null,
});

/** 弹窗内的玻璃态输入框样式，与日程面板的深色毛玻璃风格一致 */
const glassField =
  "w-full rounded-2xl border border-white/10 bg-white/10 px-5 py-4 text-sm text-white transition-all outline-none placeholder:text-white/40 focus:border-brand focus:ring-2 focus:ring-cyan-500/30 [color-scheme:dark]";

const reminderOptions = computed(() => [
  { value: null as number | null, label: "不提醒" },
  { value: 5, label: "提前 5 分钟" },
  { value: 15, label: "提前 15 分钟" },
  { value: 30, label: "提前 30 分钟" },
  { value: 60, label: "提前 1 小时" },
  { value: 1440, label: "提前 1 天" },
]);

/** 归一化为 datetime-local 能回显的格式 */
const toLocalInput = (raw?: string | null): string => {
  if (!raw) return "";
  const normalized = raw.trim().replace(" ", "T").slice(0, 16);
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(normalized) ? normalized : "";
};

const formatDeadline = (raw?: string | null): string => {
  const local = toLocalInput(raw);
  return local ? local.slice(5).replace("T", " ") : "";
};

const isOverdue = (todo: TodoItem): boolean => {
  const local = toLocalInput(todo.deadline);
  if (!local || todo.completed) return false;
  return new Date(local).getTime() < Date.now();
};

const modalTitle = computed(() => {
  if (modalMode.value === "group") return "新建任务组";
  return editingId.value !== null ? "编辑待办任务" : "新建待办任务";
});

const resetForm = () => {
  formData.groupTitle = "";
  formData.todoText = "";
  formData.priority = 1;
  formData.deadline = "";
  formData.remindBeforeMinutes = null;
  editingId.value = null;
  editingGid.value = null;
};

const handleCreate = () => {
  resetForm();
  modalMode.value = scheduleStore.scheduleView === "todo_groups" ? "group" : "task";
  showModal.value = true;
};

/** 打开编辑弹窗。gid 用于定位条目所属分组。 */
const openEdit = (todo: TodoItem | TodoItemWithGroup, gid?: string) => {
  resetForm();
  modalMode.value = "task";
  editingId.value = todo.id;
  editingGid.value = gid ?? (todo as TodoItemWithGroup).gid ?? selectedTodoGroupId.value;
  formData.todoText = todo.text;
  formData.priority = todo.priority;
  formData.deadline = toLocalInput(todo.deadline);
  formData.remindBeforeMinutes = todo.remindBeforeMinutes ?? null;
  showModal.value = true;
};

const confirmCreate = () => {
  if (modalMode.value === "group") {
    if (!formData.groupTitle.trim()) return;
    const id = createTodoGroup(formData.groupTitle.trim());
    showModal.value = false;
    resetForm();
    selectTodoGroup(id);
    return;
  }

  if (!formData.todoText.trim()) return;
  const deadline = formData.deadline || null;
  // 没有截止时间的提醒没有意义
  const remindBeforeMinutes = deadline ? formData.remindBeforeMinutes : null;

  if (editingId.value !== null) {
    const hit = resolveOriginal({ id: editingId.value } as TodoItem, editingGid.value);
    if (hit) {
      hit.todo.text = formData.todoText.trim();
      hit.todo.priority = formData.priority;
      hit.todo.deadline = deadline;
      hit.todo.remindBeforeMinutes = remindBeforeMinutes;
    }
  } else {
    // 总览页/详情页都可能新建任务：优先当前分组，否则落到第一个分组
    const gid = selectedTodoGroupId.value ?? Object.keys(scheduleStore.todoGroups)[0];
    if (gid) {
      addTodo(gid, {
        text: formData.todoText.trim(),
        priority: formData.priority,
        deadline,
        remindBeforeMinutes,
      });
    }
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
/* 原生 select 的展开项在深色面板里要自己压暗 */
.select-dark option {
  background: #16202c;
  color: #fff;
}
</style>
