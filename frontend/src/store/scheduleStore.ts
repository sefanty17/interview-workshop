import { reactive, watch } from "vue";

/**
 * 日程面板数据（原版 components/schedule/** 全部走 Tauri invoke 读写本地 SQLite）。
 *
 * 原版数据形态照抄：scheduleGroups / todoGroups / importantDays 三个 Record|Array，
 * 主动对话是一组 ENV 配置键（ENABLE_* / *_WEIGHT / PROACTIVE_EXPECTED_INTERVAL_MINUTES…），
 * 工具调用是一份最近记录列表。
 *
 * 本项目没有 Tauri 运行时，只换存储：reactive + localStorage（interview-lab-schedule-v1）。
 * 为了做「完成状态切换 / 按日期分组 / 事件归属某一天」这几件原版由后端表结构承担的活，
 * 在 ScheduleItem 上补了 date / done / id 三个字段，其余字段名与原版一致。
 */

export type ScheduleView =
  | "schedule_groups"
  | "schedule_detail"
  | "todo_groups"
  | "todo_detail"
  | "calendar"
  | "proactive_settings"
  | "tool_calls";

/** 原版 ScheduleItem：name / time / content */
export interface ScheduleItem {
  id: string;
  name: string;
  /** HH:mm */
  time: string;
  content: string;
  /** YYYY-MM-DD，详情页按它分组 */
  date: string;
  done: boolean;
}

export interface ScheduleGroup {
  title: string;
  description: string;
  items: ScheduleItem[];
}

/** 原版 TodoItem */
export interface TodoItem {
  id: number;
  text: string;
  deadline?: string | null;
  /** 截止前多少分钟提醒；null / 缺省表示不提醒 */
  remindBeforeMinutes?: number | null;
  priority: number;
  completed: boolean;
}

export interface TodoGroup {
  title: string;
  description?: string;
  todos: TodoItem[];
}

/** 原版 ImportantDay */
export interface ImportantDay {
  id: string;
  date: string;
  title: string;
  desc?: string;
  cycle?: string;
}

/** 主动消息触发条件（原版由后端读取各 ENABLE_* 开关决定） */
export type ProactiveCondition =
  | "before_schedule"
  | "todo_deadline"
  | "long_silence"
  | "after_interview";

/** 一条主动消息规则：原版一个开关 + 一个间隔配置 + 一个触发源 */
export interface ProactiveRule {
  /** 原版 ENV 键名，保留以便 1:1 对照 */
  key: string;
  title: string;
  desc: string;
  enabled: boolean;
  intervalMinutes: number;
  condition: ProactiveCondition;
  /** 原版 SCREEN_WEIGHT / TODO_WEIGHT 一类的权重 */
  weight: number;
}

/** 原版 ToolCallRecord */
export interface ToolCallRecord {
  time: string;
  tool: string;
  ok: boolean;
  summary: string;
  arguments: string;
  result: string;
  error?: string;
}

export interface ProactiveSettings {
  rules: ProactiveRule[];
  /** 原版 MAX_PROACTIVE_TIMES：一天最多主动几次 */
  maxProactiveTimes: number;
  /** 原版 TODO_REMINDER_STYLE：role / toast */
  todoReminderStyle: "role" | "toast";
}

interface ScheduleState {
  scheduleView: ScheduleView;
  scheduleGroups: Record<string, ScheduleGroup>;
  todoGroups: Record<string, TodoGroup>;
  importantDays: ImportantDay[];
  proactive: ProactiveSettings;
  toolCalls: ToolCallRecord[];
}

const STORAGE_KEY = "interview-lab-schedule-v1";

// ─── 日期工具（种子数据按「今天」推，保证无论哪天打开都落在本月/本周） ─────────
const pad = (n: number) => String(n).padStart(2, "0");

function isoDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function dayFromToday(offset: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return isoDate(d);
}

/** 下一个周 N（1=周一 … 6=周六），今天恰好是则取下周 */
function nextWeekday(target: number): string {
  const today = new Date().getDay();
  let diff = (target - today + 7) % 7;
  if (diff === 0) diff = 7;
  return dayFromToday(diff);
}

/** 本月第 n 天（n 超出本月天数时顺延，用于种子事件） */
function thisMonthDay(n: number): string {
  const now = new Date();
  const last = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(Math.min(n, last))}`;
}

function deadline(offset: number, time: string): string {
  return `${dayFromToday(offset)}T${time}`;
}

// ─── 演示数据 ────────────────────────────────────────────────────────────────
function seedScheduleGroups(): Record<string, ScheduleGroup> {
  return {
    s1: {
      title: "本周面试安排",
      description: "把每一轮的时间和要准备的东西钉在这里，到点 ta 会提醒你",
      items: [
        {
          id: "si1",
          name: "Java 后端二面",
          time: "14:00",
          content: "提前 20 分钟进会议室，重点准备 JVM 内存模型与 GC 调优",
          date: nextWeekday(3),
          done: false,
        },
        {
          id: "si2",
          name: "算法机试（中等难度）",
          time: "10:30",
          content: "限时 60 分钟，先写暴力解再优化，留 10 分钟自测边界",
          date: nextWeekday(4),
          done: false,
        },
        {
          id: "si3",
          name: "HR 面 · 薪资与到岗时间",
          time: "16:00",
          content: "准备好期望薪资区间与实习可到岗日期，别报虚高",
          date: nextWeekday(5),
          done: false,
        },
        {
          id: "si4",
          name: "项目复盘讲解",
          time: "09:30",
          content: "重点讲 3 个技术难点和当时的取舍，控制在 8 分钟内",
          date: nextWeekday(1),
          done: false,
        },
        {
          id: "si5",
          name: "三面 · 架构组 Leader",
          time: "15:00",
          content: "准备分布式事务与缓存一致性案例，最好带一张自己画的时序图",
          date: dayFromToday(-2),
          done: true,
        },
      ],
    },
    s2: {
      title: "复习计划",
      description: "每天两小时，把八股文啃成自己的话再说出来",
      items: [
        {
          id: "si6",
          name: "复习 Spring 事务传播",
          time: "20:00",
          content: "REQUIRED / REQUIRES_NEW 区别，手写一个 @Transactional 失效的例子",
          date: dayFromToday(1),
          done: false,
        },
        {
          id: "si7",
          name: "手写单例与线程池参数",
          time: "21:00",
          content: "线程池 7 个参数 + 4 种拒绝策略的场景题各准备一个",
          date: dayFromToday(2),
          done: false,
        },
        {
          id: "si8",
          name: "MySQL 索引与执行计划",
          time: "20:30",
          content: "EXPLAIN 的 type / rows / Extra 各看什么，回表是什么",
          date: dayFromToday(3),
          done: false,
        },
      ],
    },
  };
}

function seedTodoGroups(): Record<string, TodoGroup> {
  return {
    t1: {
      title: "面试准备",
      todos: [
        {
          id: 101,
          text: "打印三份纸质简历，带一支笔",
          priority: 3,
          completed: false,
          deadline: deadline(0, "18:00"),
          remindBeforeMinutes: 30,
        },
        {
          id: 102,
          text: "把项目里 Redis 缓存的坑整理成两页纸",
          priority: 4,
          completed: false,
          deadline: deadline(1, "21:00"),
          remindBeforeMinutes: 60,
        },
        {
          id: 103,
          text: "准备三个反问面试官的问题",
          priority: 2,
          completed: false,
          deadline: deadline(2, "09:00"),
          remindBeforeMinutes: null,
        },
        {
          id: 104,
          text: "录一段两分钟自我介绍视频，看回放掐语速",
          priority: 3,
          completed: true,
          deadline: null,
          remindBeforeMinutes: null,
        },
      ],
    },
    t2: {
      title: "八股复习",
      todos: [
        {
          id: 105,
          text: "复习 Spring 事务传播行为与失效场景",
          priority: 4,
          completed: false,
          deadline: deadline(1, "20:00"),
          remindBeforeMinutes: 30,
        },
        {
          id: 106,
          text: "线程池参数与拒绝策略",
          priority: 3,
          completed: false,
          deadline: deadline(2, "21:00"),
          remindBeforeMinutes: null,
        },
        {
          id: 107,
          text: "JVM 三种垃圾回收器对比",
          priority: 3,
          completed: true,
          deadline: null,
          remindBeforeMinutes: null,
        },
        {
          id: 108,
          text: "HashMap 扩容与红黑树转换阈值",
          priority: 2,
          completed: false,
          deadline: null,
          remindBeforeMinutes: null,
        },
        {
          id: 109,
          text: "synchronized 与 ReentrantLock 的区别",
          priority: 2,
          completed: false,
          deadline: null,
          remindBeforeMinutes: null,
        },
      ],
    },
    t3: {
      title: "面试后跟进",
      todos: [
        {
          id: 110,
          text: "给一面面试官发感谢邮件",
          priority: 3,
          completed: false,
          deadline: deadline(1, "12:00"),
          remindBeforeMinutes: 15,
        },
        {
          id: 111,
          text: "复盘二面被问住的分布式题",
          priority: 5,
          completed: false,
          deadline: deadline(2, "22:00"),
          remindBeforeMinutes: 60,
        },
        {
          id: 112,
          text: "更新简历里的实习起止时间",
          priority: 2,
          completed: true,
          deadline: null,
          remindBeforeMinutes: null,
        },
      ],
    },
  };
}

function seedImportantDays(): ImportantDay[] {
  return [
    {
      id: "d1",
      date: thisMonthDay(3),
      title: "简历投递截止（美团）",
      desc: "官网投递后记得在邮箱里确认回执",
      cycle: "",
    },
    {
      id: "d2",
      date: thisMonthDay(8),
      title: "字节跳动二面",
      desc: "线上视频面试，提前半小时测摄像头和麦克风",
      cycle: "",
    },
    {
      id: "d3",
      date: thisMonthDay(12),
      title: "课设终期检查",
      desc: "带上需求文档和数据库设计图",
      cycle: "",
    },
    {
      id: "d4",
      date: thisMonthDay(18),
      title: "阿里笔试",
      desc: "19:00 开始，两小时，先扫一遍题再动手",
      cycle: "",
    },
    {
      id: "d5",
      date: thisMonthDay(24),
      title: "三方协议签署截止",
      desc: "确认违约金条款再签",
      cycle: "",
    },
    {
      id: "d6",
      date: thisMonthDay(28),
      title: "室友生日 · 聚餐",
      desc: "提前订位，别又忘带礼物",
      cycle: "yearly",
    },
  ];
}

function seedToolCalls(): ToolCallRecord[] {
  return [
    {
      time: "10:12:03",
      tool: "schedule_get_all",
      ok: true,
      summary: "读取日程与待办",
      arguments: "{}",
      result:
        '{"scheduleGroups":2,"scheduleItems":8,"todoGroups":3,"pendingTodos":9,"importantDays":6}',
    },
    {
      time: "10:13:41",
      tool: "web_search",
      ok: true,
      summary: '{"query":"Spring 事务传播 REQUIRED 与 REQUIRES_NEW 区别"}',
      arguments: '{"query":"Spring 事务传播 REQUIRED 与 REQUIRES_NEW 区别","max_results":3}',
      result:
        '{"results":[{"title":"Spring 事务传播行为详解","url":"https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html"},{"title":"为什么 @Transactional 会失效","url":"https://example.com/tx-self-invocation"}]}',
    },
    {
      time: "10:15:02",
      tool: "schedule_add_todo",
      ok: true,
      summary: '{"text":"准备三个反问面试官的问题","priority":2}',
      arguments: '{"group":"面试准备","text":"准备三个反问面试官的问题","priority":2}',
      result: '{"ok":true,"id":113}',
    },
    {
      time: "10:16:28",
      tool: "get_current_time",
      ok: true,
      summary: "{}",
      arguments: "{}",
      result: '{"now":"2026-10-21 10:16:28","weekday":"周三"}',
    },
    {
      time: "10:18:55",
      tool: "web_search",
      ok: false,
      summary: '{"query":"分布式事务 Seata AT 模式 原理"}',
      arguments: '{"query":"分布式事务 Seata AT 模式 原理","max_results":3}',
      result: "",
      error: "请求超时：搜索服务 15s 内未返回（可重试）",
    },
  ];
}

function seedProactive(): ProactiveSettings {
  return {
    maxProactiveTimes: 5,
    todoReminderStyle: "role",
    rules: [
      {
        key: "ENABLE_SCHEDULE_REMINDER",
        title: "日程提醒",
        desc: "日程到点前提醒你，比如「周三 14:00 Java 后端二面」",
        enabled: true,
        intervalMinutes: 30,
        condition: "before_schedule",
        weight: 1.2,
      },
      {
        key: "ENABLE_TODO_PRECEPTION",
        title: "待办感知",
        desc: "看到快到期或高优先级的待办，就主动催你一句",
        enabled: true,
        intervalMinutes: 60,
        condition: "todo_deadline",
        weight: 1,
      },
      {
        key: "ENABLE_TOPIC_CREATER",
        title: "话题主动发起",
        desc: "你安静太久时，ta 会自己找个面试相关的话题开口",
        enabled: false,
        intervalMinutes: 120,
        condition: "long_silence",
        weight: 0.8,
      },
    ],
  };
}

function createInitialState(): ScheduleState {
  return {
    scheduleView: "schedule_groups",
    scheduleGroups: seedScheduleGroups(),
    todoGroups: seedTodoGroups(),
    importantDays: seedImportantDays(),
    proactive: seedProactive(),
    toolCalls: seedToolCalls(),
  };
}

function loadState(): ScheduleState {
  const initial = createInitialState();
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return initial;
    const saved = JSON.parse(raw) as Partial<ScheduleState>;
    if (!saved || typeof saved !== "object") return initial;
    return {
      // 视图是内存态，刷新后总回到第一页，与「重新打开面板」的体验一致
      scheduleView: "schedule_groups",
      scheduleGroups: saved.scheduleGroups ?? initial.scheduleGroups,
      todoGroups: saved.todoGroups ?? initial.todoGroups,
      importantDays: saved.importantDays ?? initial.importantDays,
      proactive: {
        ...initial.proactive,
        ...(saved.proactive ?? {}),
        rules: saved.proactive?.rules ?? initial.proactive.rules,
      },
      toolCalls: saved.toolCalls ?? initial.toolCalls,
    };
  } catch {
    return initial;
  }
}

export const scheduleStore = reactive<ScheduleState>(loadState());

/** 任何一层字段变化都回写 localStorage（deep watch 覆盖 items.push / splice / delete 等） */
watch(
  scheduleStore,
  (state) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...state, scheduleView: "schedule_groups" }));
    } catch {
      // 配额满 / 隐私模式下静默失败：页面仍可用，只是不持久化
    }
  },
  { deep: true },
);

// ─── 日程 ────────────────────────────────────────────────────────────────────
export function createScheduleGroup(title: string, description: string): string {
  const id = `g${Date.now()}`;
  scheduleStore.scheduleGroups[id] = { title, description, items: [] };
  return id;
}

export function removeScheduleGroup(id: string): void {
  delete scheduleStore.scheduleGroups[id];
}

export function addScheduleItem(groupId: string, item: Omit<ScheduleItem, "id" | "done">): void {
  const group = scheduleStore.scheduleGroups[groupId];
  if (!group) return;
  group.items.push({ ...item, id: `si${Date.now()}`, done: false });
}

export function updateScheduleItem(
  groupId: string,
  itemId: string,
  patch: Partial<Omit<ScheduleItem, "id">>,
): void {
  const item = scheduleStore.scheduleGroups[groupId]?.items.find((it) => it.id === itemId);
  if (item) Object.assign(item, patch);
}

export function findScheduleItem(
  groupId: string,
  itemId: string,
): ScheduleItem | undefined {
  return scheduleStore.scheduleGroups[groupId]?.items.find((it) => it.id === itemId);
}

export function removeScheduleItem(groupId: string, itemId: string): void {
  const group = scheduleStore.scheduleGroups[groupId];
  if (!group) return;
  const idx = group.items.findIndex((it) => it.id === itemId);
  if (idx >= 0) group.items.splice(idx, 1);
}

export function toggleScheduleDone(groupId: string, itemId: string): void {
  const item = findScheduleItem(groupId, itemId);
  if (item) item.done = !item.done;
}

// ─── 待办 ────────────────────────────────────────────────────────────────────
export function createTodoGroup(title: string): string {
  const id = `t${Date.now()}`;
  scheduleStore.todoGroups[id] = { title, todos: [] };
  return id;
}

export function removeTodoGroup(id: string): void {
  delete scheduleStore.todoGroups[id];
}

export function addTodo(
  groupId: string,
  todo: Omit<TodoItem, "id" | "completed">,
): void {
  const group = scheduleStore.todoGroups[groupId];
  if (!group) return;
  group.todos.push({ ...todo, id: Date.now(), completed: false });
}

export function findTodo(groupId: string, todoId: number): TodoItem | undefined {
  return scheduleStore.todoGroups[groupId]?.todos.find((it) => it.id === todoId);
}

export function removeTodo(groupId: string, todoId: number): void {
  const group = scheduleStore.todoGroups[groupId];
  if (!group) return;
  const idx = group.todos.findIndex((it) => it.id === todoId);
  if (idx >= 0) group.todos.splice(idx, 1);
}

export function setTodoCompleted(groupId: string, todoId: number, completed: boolean): void {
  const todo = findTodo(groupId, todoId);
  if (todo) todo.completed = completed;
}

// ─── 日历 ────────────────────────────────────────────────────────────────────
export function addImportantDay(day: Omit<ImportantDay, "id">): void {
  scheduleStore.importantDays.push({ ...day, id: Date.now().toString() });
}

export function removeImportantDay(id: string): void {
  scheduleStore.importantDays = scheduleStore.importantDays.filter((e) => e.id !== id);
}

// ─── 主动消息 ────────────────────────────────────────────────────────────────
export function saveProactiveSettings(draft: ProactiveSettings): void {
  scheduleStore.proactive.rules = draft.rules.map((r) => ({ ...r }));
  scheduleStore.proactive.maxProactiveTimes = draft.maxProactiveTimes;
  scheduleStore.proactive.todoReminderStyle = draft.todoReminderStyle;
}

// ─── 工具调用 ────────────────────────────────────────────────────────────────
export function clearToolCallRecords(): void {
  scheduleStore.toolCalls = [];
}
