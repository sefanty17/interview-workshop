<template>
  <div class="rounded-xl border border-white/10 bg-black/15 p-3">
    <!-- ============ choices 的选项列表 ============ -->
    <template v-if="field.kind === 'choice_options'">
      <div v-for="(opt, i) in rows" :key="i" class="mb-2 rounded-lg bg-white/6 p-2.5 last:mb-0">
        <div class="mb-1.5 flex items-center gap-2">
          <span class="text-xs text-white/40">{{ i + 1 }}</span>
          <input
            class="w-full min-w-0 rounded-md border border-white/[0.1] bg-black/[0.25] px-2 py-1.5 text-xs text-white transition-all focus:border-brand focus:outline-none"
            placeholder="选项文案（留空 = 匹配任意输入）"
            :value="str(opt.text)"
            @change="(e) => patch(i, 'text', val(e))"
          />
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="删除选项"
            @click="removeRow(i)"
          >
            ✕
          </button>
        </div>

        <!-- 选项级条件（引擎支持 options[].condition）：默认收起，点「＋ 条件」才展开。
             说明在卡片底部统一给出，这里隐藏编辑器自带的顶部行 -->
        <div v-if="conditionOpen(i)" class="mt-1 flex items-center gap-2 pl-6">
          <span class="shrink-0 text-xs text-white/40">条件</span>
          <ConditionEditor
            class="min-w-0 flex-1"
            :model-value="str(opt.condition)"
            :variables="variables"
            :hint="''"
            @update:model-value="(v: string) => patch(i, 'condition', v)"
          />
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="移除条件"
            @click="closeCondition(i)"
          >
            ✕
          </button>
        </div>
        <div v-if="conditionOpen(i)" class="mt-1 flex items-center gap-2 pl-6">
          <span class="shrink-0 text-xs text-white/40">不可选提示</span>
          <input
            class="min-w-0 flex-1 rounded-md border border-white/[0.1] bg-black/[0.25] px-2 py-1.5 text-xs text-white transition-all focus:border-brand focus:outline-none"
            placeholder="条件不满足时显示这句话（选项变灰）"
            :value="str(opt.lock_hint)"
            @change="(e) => patch(i, 'lock_hint', val(e))"
          />
        </div>

        <!-- 选项动作：每类动作独立按钮添加，不用下拉 -->
        <div v-for="(act, ai) in actions(opt)" :key="ai" class="mt-1 flex items-center gap-2 pl-6">
          <span class="shrink-0 text-xs text-white/40">{{ actionLabel(str(act.type)) }}</span>
          <VariableEditor
            v-if="act.type === 'set_var' && !legacySetVar(act)"
            class="min-w-0 flex-1"
            :model-value="str(act.content)"
            :variables="variables"
            :hint="''"
            @update:model-value="(v: string) => patchAction(i, ai, 'content', v)"
          />
          <div
            v-else-if="legacySetVar(act)"
            class="min-w-0 flex-1 rounded-md border border-yellow-300/25 bg-yellow-300/10 px-2 py-1.5"
          >
            <p class="text-xs text-yellow-200">这是旧版本留下的写法，运行时会跳过。</p>
            <button
              class="mt-1 text-xs text-brand hover:underline"
              @click="convertLegacy(i, ai)"
            >
              转成新格式
            </button>
          </div>
          <input
            v-else
            class="min-w-0 flex-1 rounded-md border border-white/[0.1] bg-black/[0.25] px-2 py-1.5 text-xs text-white transition-all focus:border-brand focus:outline-none"
            :placeholder="actionPlaceholder(str(act.type))"
            :value="str(act.content)"
            @change="(e) => patchAction(i, ai, 'content', val(e))"
          />
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="删除"
            @click="removeAction(i, ai)"
          >
            ✕
          </button>
        </div>

        <!-- 动作区：每个可用动作一行 = 高亮概念名 + 短说明 + 右侧添加。
             与底部说明合并，行距拉开，避免按钮横排拥挤 -->
        <div class="mt-1.5 ml-6 flex flex-col">
          <div
            class="flex items-center gap-2.5 rounded-md px-1.5 py-1.5 transition-colors hover:bg-white/[0.04]"
          >
            <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-brand"></span>
            <span class="shrink-0 text-xs font-semibold text-brand">
              追加玩家台词
            </span>
            <span class="min-w-0 flex-1 text-xs leading-snug text-white/40">
              选择后以玩家的名义写一句台词进对话，AI 能看到
            </span>
            <button
              v-if="!hasAddLine(i)"
              class="shrink-0 rounded-full border border-[rgba(121,217,255,0.4)] bg-[rgba(121,217,255,0.1)] px-2.5 py-0.5 text-xs text-brand transition-all hover:bg-[rgba(121,217,255,0.2)]"
              @click="addAction(i, 'add_line')"
            >
              ＋ 添加
            </button>
            <span v-else class="shrink-0 text-xs text-green-400/90">已添加</span>
          </div>
          <div
            class="flex items-center gap-2.5 rounded-md px-1.5 py-1.5 transition-colors hover:bg-white/[0.04]"
          >
            <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-brand"></span>
            <span class="shrink-0 text-xs font-semibold text-brand">设置变量</span>
            <span class="min-w-0 flex-1 text-xs leading-snug text-white/40">
              选择后修改剧本变量，可用于后续分支判断
            </span>
            <button
              class="shrink-0 rounded-full border border-[rgba(121,217,255,0.4)] bg-[rgba(121,217,255,0.1)] px-2.5 py-0.5 text-xs text-brand transition-all hover:bg-[rgba(121,217,255,0.2)]"
              @click="addAction(i, 'set_var')"
            >
              ＋ 添加
            </button>
          </div>
          <div
            class="flex items-center gap-2.5 rounded-md px-1.5 py-1.5 transition-colors hover:bg-white/[0.04]"
          >
            <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-brand"></span>
            <span class="shrink-0 text-xs font-semibold text-brand">触发条件</span>
            <span class="min-w-0 flex-1 text-xs leading-snug text-white/40">
              只有满足条件时，这个选项才可选
            </span>
            <button
              v-if="!conditionOpen(i)"
              class="shrink-0 rounded-full border border-[rgba(121,217,255,0.4)] bg-[rgba(121,217,255,0.1)] px-2.5 py-0.5 text-xs text-brand transition-all hover:bg-[rgba(121,217,255,0.2)]"
              @click="openCondition(i)"
            >
              ＋ 添加
            </button>
            <span v-else class="shrink-0 text-xs text-green-400/90">已添加</span>
          </div>
        </div>
      </div>

      <button
        class="mt-2 w-full rounded-lg border border-dashed border-white/15 py-1.5 text-xs text-white/45 transition-all hover:border-brand hover:text-brand"
        @click="addRow({ text: '', actions: [] })"
      >
        ＋ 添加选项
      </button>
    </template>

    <!-- ============ chapter_end 的分支 ============ -->
    <template v-else-if="field.kind === 'branch_options'">
      <div v-for="(opt, i) in rows" :key="i" class="mb-2 rounded-lg bg-white/6 p-2.5 last:mb-0">
        <div v-if="!isAiJudged" class="mb-1.5 flex items-center gap-2">
          <span class="shrink-0 text-xs text-white/40">如果</span>
          <ConditionEditor
            class="min-w-0 flex-1"
            :model-value="str(opt.condition)"
            :variables="variables"
            @update:model-value="(v: string) => patch(i, 'condition', v)"
          />
        </div>
        <div class="flex items-center gap-2 pl-6">
          <span class="shrink-0 text-xs text-white/40">跳到</span>
          <select
            class="w-full min-w-0 rounded-md border border-white/[0.1] bg-black/[0.25] px-2 py-1.5 text-xs text-white transition-all focus:border-brand focus:outline-none"
            :value="str(opt.next)"
            @change="(e) => patch(i, 'next', val(e))"
          >
            <option value="">（未选择）</option>
            <option v-for="c in chapterOptions" :key="c.value" :value="c.value">
              {{ c.label }}
            </option>
          </select>
          <label
            class="flex shrink-0 items-center gap-1 text-xs whitespace-nowrap text-white/60"
            title="勾选后表示「以上都不满足时」走这条"
          >
            <input
              type="checkbox"
              :checked="opt.default === true"
              @change="(e) => patch(i, 'default', (e.target as HTMLInputElement).checked)"
            />
            兜底
          </label>
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="删除分支"
            @click="removeRow(i)"
          >
            ✕
          </button>
        </div>
        <div v-if="isAiJudged" class="mt-1.5 flex items-center gap-2 pl-6">
          <span class="shrink-0 text-xs text-white/40">AI 识别名</span>
          <input
            class="w-full min-w-0 rounded-md border border-white/[0.1] bg-black/[0.25] px-2 py-1.5 text-xs text-white transition-all focus:border-brand focus:outline-none"
            placeholder="分支名（AI 回复里要出现的词）"
            :value="str(opt.name)"
            @change="(e) => patch(i, 'name', val(e))"
          />
        </div>
      </div>

      <button
        class="mt-2 w-full rounded-lg border border-dashed border-white/15 py-1.5 text-xs text-white/45 transition-all hover:border-brand hover:text-brand"
        @click="addRow({ condition: '', next: '' })"
      >
        ＋ 添加分支
      </button>
      <p class="mt-2 text-xs text-white/40">
        顺序即优先级。条件分支按 condition 逐条判断；AI 判定分支按名字在 AI
        回复里找。建议留一条「兜底」分支，否则所有分支都不满足时会直接结束剧本。
      </p>
    </template>

    <!-- ============ set_variable 的赋值组 ============ -->
    <template v-else>
      <div v-for="(opt, i) in rows" :key="i" class="mb-2 rounded-lg bg-white/6 p-2.5 last:mb-0">
        <!-- 组条件：默认收起，点「＋ 条件」展开（与 choices 选项一致），
             避免与事件级「触发条件」混在一起 -->
        <div v-if="conditionOpen(i)" class="mb-1.5 flex items-center gap-2">
          <span class="shrink-0 text-xs text-white/40">如果</span>
          <ConditionEditor
            class="w-full min-w-0"
            :model-value="str(opt.condition)"
            :variables="variables"
            @update:model-value="(v: string) => patch(i, 'condition', v)"
          />
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="移除条件"
            @click="closeCondition(i)"
          >
            ✕
          </button>
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="删除这一组"
            @click="removeRow(i)"
          >
            ✕
          </button>
        </div>
        <div v-else class="mb-1.5 flex items-center gap-2">
          <button
            class="text-xs text-brand hover:underline"
            @click="openCondition(i)"
          >
            ＋ 触发条件
          </button>
          <button
            class="ml-auto shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            title="删除这一组"
            @click="removeRow(i)"
          >
            ✕
          </button>
        </div>
        <div v-for="(act, ai) in actions(opt)" :key="ai" class="mt-1 flex items-center gap-2 pl-6">
          <VariableEditor
            v-if="act.type === 'set_var' && !legacySetVar(act)"
            class="min-w-0 flex-1"
            :model-value="str(act.content)"
            :variables="variables"
            @update:model-value="(v: string) => patchAction(i, ai, 'content', v)"
          />
          <div
            v-else-if="legacySetVar(act)"
            class="min-w-0 flex-1 rounded-md border border-yellow-300/25 bg-yellow-300/10 px-2 py-1.5"
          >
            <p class="text-xs text-yellow-200">这是旧版本留下的写法，运行时会跳过。</p>
            <button
              class="mt-1 text-xs text-brand hover:underline"
              @click="convertLegacy(i, ai)"
            >
              转成新格式
            </button>
          </div>
          <button
            class="shrink-0 rounded-md px-1.5 py-1 text-xs text-white/[0.35] transition-all hover:bg-[rgba(248,113,113,0.15)] hover:text-[#fca5a5]"
            @click="removeAction(i, ai)"
          >
            ✕
          </button>
        </div>
        <button
          class="mt-1.5 ml-6 text-xs text-brand hover:underline"
          @click="addAction(i, 'set_var')"
        >
          ＋ 添加赋值
        </button>
      </div>

      <button
        class="mt-2 w-full rounded-lg border border-dashed border-white/15 py-1.5 text-xs text-white/45 transition-all hover:border-brand hover:text-brand"
        @click="addRow({ actions: [{ type: 'set_var', content: '' }] })"
      >
        ＋ 添加赋值组
      </button>
      <p class="mt-2 text-xs text-white/40">
        每组的条件都可省略；所有满足条件的组都会执行（与选项只执行第一条不同）。
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from "vue";
import type { FieldSpec } from "../../../api/editor";
import { chapterOptions, variables } from "../../../store/scriptEditor";
import ConditionEditor from "./ConditionEditor.vue";
import VariableEditor from "./VariableEditor.vue";

type Row = Record<string, unknown>;

const props = defineProps<{
  field: FieldSpec;
  value: unknown;
  /** 分支编辑器的显示模式，由父组件按 chapter_end 的 end_type 传入 */
  branchMode?: "branching" | "ai_judged";
}>();

const emit = defineEmits<{ (e: "update", value: unknown): void }>();

const rows = computed<Row[]>(() => (Array.isArray(props.value) ? (props.value as Row[]) : []));

const str = (v: unknown) => (typeof v === "string" ? v : v === undefined ? "" : String(v));
const val = (e: Event) => (e.target as HTMLInputElement | HTMLSelectElement).value;

const actions = (opt: Row): Row[] => (Array.isArray(opt.actions) ? (opt.actions as Row[]) : []);

/** 该选项是否已有一条「追加玩家台词」——有则隐藏对应按钮（每条选项最多一句玩家台词有意义） */
const hasAddLine = (i: number) => actions(rows.value[i]).some((a) => a.type === "add_line");

/**
 * 分支编辑器模式。父组件在 end_type 为 ai_judged 时传 ai_judged，否则传
 * branching（linear 不显示分支，走不到这里）。null 兜底为 branching。
 */
const isAiJudged = computed(() => props.branchMode === "ai_judged");

/**
 * 命中「旧原型形状」的 set_var 动作：只写了 name/value/op、没有 content 表达式。
 * 引擎只读 content，这类动作会被静默跳过（校验器也会报 action.legacy_shape）。
 * 编辑器里只读展示，提供「一键转为新格式」入口。
 */
const legacySetVar = (act: Row) =>
  act.type === "set_var" &&
  !(typeof act.content === "string" && act.content.trim()) &&
  Boolean(act.name || act.value || act.op);

/** 把旧版 name/value/op 合并成 content 表达式（name op value），并清掉旧字段 */
const convertLegacy = (i: number, ai: number) => {
  const next = clone();
  const list = Array.isArray(next[i]?.actions) ? (next[i].actions as Row[]) : [];
  const act = list[ai];
  if (!act) return;
  const expr = [act.name, act.op, act.value]
    .filter((v) => typeof v === "string")
    .join(" ")
    .trim();
  if (!expr) return;
  act.content = expr;
  delete act.name;
  delete act.value;
  delete act.op;
  commit(next);
};

/** choices 选项的条件行是否展开：已有条件 或 作者点过「＋ 条件」 */
const conditionOpenState = reactive<Record<number, boolean>>({});
const conditionOpen = (i: number) => {
  const has =
    typeof rows.value[i]?.condition === "string" && str(rows.value[i]?.condition).trim() !== "";
  return has || conditionOpenState[i] === true;
};
const openCondition = (i: number) => {
  conditionOpenState[i] = true;
};
const closeCondition = (i: number) => {
  conditionOpenState[i] = false;
  patch(i, "condition", "");
};

const actionLabel = (type: string) => (type === "set_var" ? "设置变量" : "玩家台词");

const actionPlaceholder = (type: string) =>
  type === "set_var" ? "affection += 1" : "以玩家名义说的一句话";

/** 深拷贝后再改，避免直接 mutate 掉撤销栈里的旧帧 */
const clone = (): Row[] => JSON.parse(JSON.stringify(rows.value));

const commit = (next: Row[]) => emit("update", next);

const patch = (i: number, key: string, v: unknown) => {
  const next = clone();
  if (!next[i]) return;
  if (v === "" || v === false) delete next[i][key];
  else next[i][key] = v;
  commit(next);
};

const addRow = (row: Row) => commit([...clone(), row]);

const removeRow = (i: number) => {
  const next = clone();
  next.splice(i, 1);
  commit(next);
};

const addAction = (i: number, type = "add_line") => {
  const next = clone();
  if (!next[i]) return;
  const list = Array.isArray(next[i].actions) ? (next[i].actions as Row[]) : [];
  // 同一个选项里不允许重复添加「追加玩家台词」——每条选项最多一句玩家台词有意义
  if (type === "add_line" && list.some((a) => a.type === "add_line")) return;
  // 追加玩家台词：默认复制上方选项文案，免去重复输入（一般保持一致即可）
  const content = type === "add_line" ? str(rows.value[i]?.text) : "";
  list.push({ type, content });
  next[i].actions = list;
  commit(next);
};

const patchAction = (i: number, ai: number, key: string, v: unknown) => {
  const next = clone();
  const list = Array.isArray(next[i]?.actions) ? (next[i].actions as Row[]) : [];
  if (!list[ai]) return;
  list[ai][key] = v;
  commit(next);
};

const removeAction = (i: number, ai: number) => {
  const next = clone();
  const list = Array.isArray(next[i]?.actions) ? (next[i].actions as Row[]) : [];
  list.splice(ai, 1);
  next[i].actions = list;
  commit(next);
};
</script>

<style scoped>
/* select 里的 option 无法用 class 直接打进去，保留本文件唯一一条 scoped 规则 */
select option {
  background: #16202c;
  color: #fff;
}
</style>
