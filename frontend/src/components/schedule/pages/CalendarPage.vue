<template>
  <!-- Calendar View -->
  <div
    v-if="scheduleStore.scheduleView === 'calendar'"
    class="flex h-full items-center justify-center"
    :class="roomStore.isNarrowScreen ? 'flex-col gap-4' : ''"
  >
    <div
      class="glass-effect flex flex-col overflow-hidden rounded-xl border border-cyan-500 shadow-sm"
      :class="roomStore.isNarrowScreen ? 'min-h-0 w-full flex-1' : 'w-2/3'"
    >
      <div class="flex items-center justify-between border-b border-cyan-500 p-4">
        <div class="flex w-full items-center justify-around">
          <button
            class="cursor-pointer rounded-lg border-none bg-transparent p-2 text-cyan-500 transition-all duration-300 hover:bg-cyan-50"
            aria-label="上一月"
            @click="changeMonth(-1)"
          >
            <ChevronLeft />
          </button>
          <h3 class="text-brand text-lg font-bold">{{ calendarYear }}年 {{ calendarMonth + 1 }}月</h3>
          <button
            class="cursor-pointer rounded-lg border-none bg-transparent p-2 text-cyan-500 transition-all duration-300 hover:bg-cyan-50"
            aria-label="下一月"
            @click="changeMonth(1)"
          >
            <ChevronRight />
          </button>
        </div>
      </div>
      <div class="flex flex-1 flex-col">
        <div
          class="calendar-grid border-b border-cyan-500 bg-slate-50/30 py-3 text-center text-[10px] font-bold tracking-widest text-white"
        >
          <div v-for="d in weekDays" :key="d">
            {{ d }}
          </div>
        </div>
        <div class="calendar-grid flex-1">
          <div
            v-for="(day, idx) in calendarDays"
            :key="'day-' + idx"
            :class="[
              `day-cell relative cursor-pointer border-r border-b border-cyan-500 p-2 transition-all hover:bg-cyan-50/30`,
              !day.currentMonth ? 'bg-slate-50/20 opacity-30' : '',
              isSelected(day) ? 'ring-2 ring-cyan-400 ring-inset' : '',
            ]"
            @click="selectDate(day)"
          >
            <span
              :class="[
                'text-sm font-medium',
                day.today
                  ? 'flex h-7 w-7 items-center justify-center rounded-full bg-cyan-500 text-white'
                  : 'text-white',
              ]"
              >{{ day.date }}</span
            >
            <div class="mt-1 space-y-1">
              <div
                v-for="event in getEvents(day)"
                :key="'event-' + event.id"
                class="truncate rounded bg-cyan-100 px-1.5 py-0.5 text-[9px] font-bold text-cyan-700"
              >
                {{ event.title }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div
      class="glass-effect flex flex-col rounded-xl border border-cyan-500 shadow-sm"
      :class="roomStore.isNarrowScreen ? 'min-h-0 w-full' : 'h-full w-1/3 pl-4'"
    >
      <div :class="roomStore.isNarrowScreen ? 'p-3' : 'flex h-full flex-col p-4'">
        <div class="mb-4 flex items-center justify-between">
          <h3 class="text-brand text-lg font-bold">重要日子</h3>
          <button
            v-if="selectedDate"
            class="cursor-pointer rounded-full border border-white/20 px-2.5 py-0.5 text-[11px] text-white/70 transition-colors hover:bg-white/10 hover:text-white"
            @click="selectedDate = null"
          >
            查看全部
          </button>
        </div>
        <p v-if="selectedDate" class="mb-3 text-xs text-white/60">
          只看 {{ selectedDateLabel }} 的事件（{{ eventsOfSelectedDate.length }} 条）
        </p>

        <!-- 添加新事件按钮 -->
        <button
          class="mb-4 flex w-full cursor-pointer items-center justify-center rounded-lg border-none bg-cyan-500 px-4 py-2 text-white transition-all duration-300 hover:bg-cyan-600"
          @click="showAddEventModal = true"
        >
          <span class="mr-2">+</span> 添加重要日子
        </button>

        <!-- 事件列表 -->
        <div class="flex-1 space-y-2 overflow-y-auto">
          <div
            v-for="event in visibleEvents"
            :key="event.id"
            class="cursor-pointer rounded-lg border border-cyan-500/30 bg-slate-50/30 p-3 transition-all duration-300 hover:bg-slate-50/50"
            @click="selectEvent(event)"
          >
            <div class="flex items-start justify-between">
              <div class="flex-1">
                <h4 class="text-brand font-medium">{{ event.title }}</h4>
                <p class="mt-1 text-xs text-white">{{ formatDate(event.date) }}</p>
                <p v-if="event.desc" class="mt-1 text-xs text-white">{{ event.desc }}</p>
              </div>
              <button
                class="ml-2 cursor-pointer border-none bg-transparent text-red-500 hover:text-red-700"
                aria-label="删除重要日子"
                @click.stop="deleteEvent(event.id)"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  class="h-4 w-4"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
          </div>

          <!-- 空状态 -->
          <div v-if="visibleEvents.length === 0" class="py-8 text-center text-gray-500">
            暂无重要日子
          </div>
        </div>

        <!-- 添加事件模态框 -->
        <div
          v-if="showAddEventModal"
          class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          @click.self="showAddEventModal = false"
        >
          <div class="glass-effect w-96 rounded-xl border border-cyan-500 p-6 shadow-sm">
            <h3 class="text-brand mb-4 text-lg font-bold">添加重要日子</h3>

            <div class="space-y-4">
              <div>
                <label class="mb-1 block text-sm font-medium text-gray-700">标题</label>
                <input
                  v-model="newEvent.title"
                  type="text"
                  class="w-full rounded-lg border border-cyan-500 px-3 py-2 focus:ring-2 focus:ring-cyan-500 focus:outline-none"
                  placeholder="输入标题"
                />
              </div>

              <div>
                <label class="mb-1 block text-sm font-medium text-gray-700">日期</label>
                <input
                  v-model="newEvent.date"
                  type="date"
                  class="w-full rounded-lg border border-cyan-500 px-3 py-2 text-white [color-scheme:dark] focus:ring-2 focus:ring-cyan-500 focus:outline-none"
                />
              </div>

              <div>
                <label class="mb-1 block text-sm font-medium text-gray-700">描述 (可选)</label>
                <textarea
                  v-model="newEvent.desc"
                  class="w-full rounded-lg border border-cyan-500 px-3 py-2 focus:ring-2 focus:ring-cyan-500 focus:outline-none"
                  rows="3"
                  placeholder="输入描述"
                ></textarea>
              </div>

              <div>
                <label class="mb-1 block text-sm font-medium text-gray-700">周期 (可选)</label>
                <select
                  v-model="newEvent.cycle"
                  class="w-full rounded-lg border border-cyan-500 px-3 py-2 focus:ring-2 focus:ring-cyan-500 focus:outline-none"
                >
                  <option value="">无</option>
                  <option value="yearly">每年</option>
                  <option value="monthly">每月</option>
                  <option value="weekly">每周</option>
                </select>
              </div>
            </div>

            <div class="mt-6 flex justify-end space-x-2">
              <button
                class="cursor-pointer rounded-lg border border-cyan-500 bg-transparent px-4 py-2 text-cyan-500 transition-all duration-300 hover:bg-cyan-50"
                @click="showAddEventModal = false"
              >
                取消
              </button>
              <button
                class="cursor-pointer rounded-lg border-none bg-cyan-500 px-4 py-2 text-white transition-all duration-300 hover:bg-cyan-600"
                @click="addEvent"
              >
                添加
              </button>
            </div>
          </div>
        </div>

        <!-- 事件详情模态框 -->
        <div
          v-if="selectedEvent"
          class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          @click.self="selectedEvent = null"
        >
          <div class="glass-effect w-96 rounded-xl border border-cyan-500 p-6 shadow-sm">
            <h3 class="text-brand mb-4 text-lg font-bold">{{ selectedEvent.title }}</h3>

            <div class="space-y-2">
              <div>
                <span class="text-sm font-medium text-gray-700">日期：</span>
                <span class="text-sm">{{ formatDate(selectedEvent.date) }}</span>
              </div>

              <div v-if="selectedEvent.desc">
                <span class="text-sm font-medium text-gray-700">描述：</span>
                <p class="mt-1 text-sm">{{ selectedEvent.desc }}</p>
              </div>

              <div v-if="selectedEvent.cycle">
                <span class="text-sm font-medium text-gray-700">周期：</span>
                <span class="text-sm">{{ getCycleText(selectedEvent.cycle) }}</span>
              </div>
            </div>

            <div class="mt-6 flex justify-end">
              <button
                class="cursor-pointer rounded-lg border-none bg-cyan-500 px-4 py-2 text-white transition-all duration-300 hover:bg-cyan-600"
                @click="selectedEvent = null"
              >
                关闭
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 重要日子 / 日历页（原版 components/schedule/pages/CalendarPage.vue）
 *
 * 月份切换、点日期筛当天事件、事件增删与详情。原版 selectDate 只记录选中项
 * （打开弹窗的那行被注释掉了），这里把选中日期用于筛选右侧列表。
 */
import { computed, ref } from "vue";
import { ChevronLeft, ChevronRight } from "@lucide/vue";
import {
  scheduleStore,
  addImportantDay,
  removeImportantDay,
  type ImportantDay,
} from "../../../store/scheduleStore";
import { roomStore } from "../../../store/roomStore";
import { confirmDialog } from "../../../store/dialogStore";

interface Day {
  date: number;
  month: number;
  year: number;
  currentMonth: boolean;
  today?: boolean;
}

const calendarDate = ref(new Date());
const calendarYear = computed(() => calendarDate.value.getFullYear());
const calendarMonth = computed(() => calendarDate.value.getMonth());
const weekDays = ["日", "一", "二", "三", "四", "五", "六"];

const selectedDate = ref<Day | null>(null);

const toDateString = (day: Day) =>
  `${day.year}-${String(day.month + 1).padStart(2, "0")}-${String(day.date).padStart(2, "0")}`;

const calendarDays = computed<Day[]>(() => {
  const days: Day[] = [];
  const year = calendarYear.value;
  const month = calendarMonth.value;
  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);
  const today = new Date();

  // 上个月的尾巴：跨年/跨月由 Date 自己归一化（原版直接写 month - 1 会在 1 月算出 0 月）
  for (let i = firstDay.getDay(); i > 0; i--) {
    const d = new Date(year, month, 1 - i);
    days.push({
      date: d.getDate(),
      month: d.getMonth(),
      year: d.getFullYear(),
      currentMonth: false,
    });
  }
  for (let i = 1; i <= lastDay.getDate(); i++) {
    days.push({
      date: i,
      month,
      year,
      currentMonth: true,
      today:
        today.getDate() === i &&
        today.getMonth() === month &&
        today.getFullYear() === year,
    });
  }
  const remaining = 42 - days.length;
  for (let i = 1; i <= remaining; i++) {
    const d = new Date(year, month + 1, i);
    days.push({
      date: d.getDate(),
      month: d.getMonth(),
      year: d.getFullYear(),
      currentMonth: false,
    });
  }
  return days;
});

const changeMonth = (offset: number) => {
  calendarDate.value = new Date(calendarYear.value, calendarMonth.value + offset, 1);
  selectedDate.value = null;
};

const isSelected = (day: Day) =>
  !!selectedDate.value && toDateString(selectedDate.value) === toDateString(day);

const selectDate = (day: Day) => {
  selectedDate.value = isSelected(day) ? null : day;
};

const getEvents = (day: Day) => {
  const ds = toDateString(day);
  return scheduleStore.importantDays.filter((e) => e.date === ds);
};

const selectedDateLabel = computed(() => {
  const d = selectedDate.value;
  return d ? `${d.year}年${d.month + 1}月${d.date}日` : "";
});

const eventsOfSelectedDate = computed(() => {
  const d = selectedDate.value;
  if (!d) return [];
  const ds = toDateString(d);
  return scheduleStore.importantDays.filter((e) => e.date === ds);
});

/** 排序后的事件列表（按日期升序）；选中某天时只列当天 */
const sortedEvents = computed(() =>
  [...scheduleStore.importantDays].sort(
    (a, b) => new Date(a.date).getTime() - new Date(b.date).getTime(),
  ),
);

const visibleEvents = computed(() =>
  selectedDate.value ? eventsOfSelectedDate.value : sortedEvents.value,
);

const formatDate = (dateString: string) => {
  const date = new Date(dateString);
  if (Number.isNaN(date.getTime())) return dateString;
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
};

const getCycleText = (cycle: string) => {
  const cycleMap: { [key: string]: string } = {
    yearly: "每年",
    monthly: "每月",
    weekly: "每周",
  };
  return cycleMap[cycle] || cycle;
};

// 添加事件相关
const showAddEventModal = ref(false);
const selectedEvent = ref<ImportantDay | null>(null);
const newEvent = ref<ImportantDay>({
  id: "",
  date: "",
  title: "",
  desc: "",
  cycle: "",
});

const addEvent = () => {
  if (!newEvent.value.title || !newEvent.value.date) return;

  addImportantDay({
    date: newEvent.value.date,
    title: newEvent.value.title.trim(),
    desc: newEvent.value.desc,
    cycle: newEvent.value.cycle,
  });

  // 重置表单
  newEvent.value = {
    id: "",
    date: "",
    title: "",
    desc: "",
    cycle: "",
  };

  showAddEventModal.value = false;
};

const deleteEvent = async (id: string) => {
  const target = scheduleStore.importantDays.find((e) => e.id === id);
  if (!target) return;
  const ok = await confirmDialog("删除重要日子", `确定删除「${target.title}」吗？`);
  if (!ok) return;
  removeImportantDay(id);
  if (selectedEvent.value?.id === id) selectedEvent.value = null;
};

const selectEvent = (event: ImportantDay) => {
  selectedEvent.value = event;
};

const handleCreate = () => {
  showAddEventModal.value = true;
};

defineExpose({
  handleCreate,
});
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供） */
.text-brand {
  color: var(--accent-color);
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
}
/* 原版用 aspect-ratio: 1/1 撑成正方形；面板高度不够时 6 行会被裁掉最后两周，
   这里改成最小高度，保证整月都能看见 */
.day-cell {
  min-height: 68px;
}
</style>
