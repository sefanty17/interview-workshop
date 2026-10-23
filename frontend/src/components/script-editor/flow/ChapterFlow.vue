<template>
  <div class="flex flex-col items-center px-2 pt-[18px] pb-6">
    <p v-if="!store.report" class="mt-[26px] max-w-[560px] text-xs leading-[1.9] text-white/40">
      正在读取章节跳转关系…
    </p>

    <template v-else>
      <div v-for="(row, ri) in rows" :key="row.key" class="flex w-full flex-col items-center">
        <!-- 层与层之间的连线。分支层上方画一个分叉提示。
             末尾的三角箭头用 after: 变体；分叉层的横线用 fork 条件追加 before: -->
        <div
          v-if="ri > 0"
          class="conn bg-brand/55 relative h-[34px] w-px after:absolute after:bottom-0 after:left-[-4px] after:border-t-[8px] after:border-r-[4.5px] after:border-l-[4.5px] after:border-t-[rgba(121,217,255,0.6)] after:border-r-transparent after:border-l-transparent after:content-['']"
          :class="
            row.nodes.length > 1
              ? `fork before:absolute before:top-1/2 before:left-[-60px] before:h-px before:w-[120px] before:bg-[rgba(121,217,255,0.35)] before:content-[\'\']`
              : ''
          "
        >
          <span
            v-if="row.inboundLabels.length"
            class="absolute top-1/2 left-2.5 -translate-y-1/2 font-mono text-[9.5px] whitespace-nowrap text-white/50"
            >{{ row.inboundLabels.join(" / ") }}</span
          >
        </div>

        <div class="flex w-full flex-wrap justify-center gap-[18px]">
          <div
            v-for="node in row.nodes"
            :key="node.id"
            class="hover:border-brand group relative max-w-[460px] flex-[1_1_300px] cursor-pointer rounded-xl border border-white/12.5 bg-white/10 px-3.5 py-3 shadow-[0_8px_32px_rgba(0,0,0,0.1),inset_0_1px_1px_rgba(255,255,255,0.1)] transition-all duration-200 ease-in-out hover:-translate-y-0.5 hover:shadow-[0_6px_18px_rgba(121,217,255,0.22)]"
            :class="{
              'border-green-400/50': node.isIntro,
              'border-dashed border-amber-300/50': node.isOrphan,
            }"
            @click="open(node.id)"
          >
            <span
              class="absolute -top-2 left-3 rounded border bg-[#16202c] px-1.5 py-px font-mono text-[9.5px]"
              :class="
                node.isIntro
                  ? 'border-green-400/40 text-green-400'
                  : node.isOrphan
                    ? 'border-amber-300/40 text-amber-300'
                    : 'border-white/[0.14] text-white/50'
              "
            >
              {{ leaf(node.id) }}.yaml{{ node.isIntro ? "开场" : ""
              }}{{ node.isOrphan ? "无人进入" : "" }}
            </span>

            <div class="flex items-baseline gap-2">
              <span class="text-[0.88rem] font-semibold text-white">{{
                node.name || node.id
              }}</span>
              <span class="ml-auto text-[0.7rem] whitespace-nowrap text-white/45">{{
                `${node.eventCount} 个事件`
              }}</span>
            </div>

            <div class="mt-2 flex min-h-4 items-center gap-1.5">
              <span
                v-if="node.errors"
                class="rounded border border-red-400/35 bg-red-400/15 px-[6px] py-px text-[0.64rem] whitespace-nowrap text-red-300"
                >{{ `${node.errors} 个错误` }}</span
              >
              <span
                v-else-if="node.warns"
                class="rounded border border-amber-300/30 bg-amber-300/15 px-[6px] py-px text-[0.64rem] whitespace-nowrap text-amber-300"
                >{{ `${node.warns} 个提醒` }}</span
              >
              <span
                v-if="node.endType && node.endType !== 'linear'"
                class="rounded border border-purple-400/35 bg-purple-400/15 px-[6px] py-px text-[0.64rem] whitespace-nowrap text-purple-300"
                >{{
                  node.endType === "branching" ? "条件分支" : "AI 判定分支"
                }}</span
              >
              <span
                v-if="node.isOrphan"
                class="rounded border border-amber-300/30 bg-amber-300/15 px-[6px] py-px text-[0.64rem] whitespace-nowrap text-amber-300"
                >玩家走不到</span
              >
              <button
                class="rounded px-[5px] py-px text-[11px] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-[rgba(121,217,255,0.15)] hover:text-brand"
                title="从本章试玩"
                @click.stop="startPreview(node.id)"
              >
                ▶ 试玩
              </button>
              <button
                class="ml-auto rounded px-[5px] py-px text-[11px] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-red-400/15 hover:text-red-300"
                title="删除章节"
                @click.stop="removeChapter(node.id)"
              >
                ✕
              </button>
            </div>
          </div>
        </div>
      </div>

      <div
        class="conn bg-brand/55 relative h-[34px] w-px after:absolute after:bottom-0 after:left-[-4px] after:border-t-[8px] after:border-r-[4.5px] after:border-l-[4.5px] after:border-t-[rgba(121,217,255,0.6)] after:border-r-transparent after:border-l-transparent after:content-['']"
      ></div>
      <div
        class="rounded-full border border-dashed border-white/25 px-3.5 py-[5px] text-[0.72rem] whitespace-nowrap text-white/50"
      >
        剧本结束
      </div>

      <p
        class="[&_code]:text-brand mt-[26px] max-w-[560px] text-xs leading-[1.9] text-white/40 [&_b]:text-white/65 [&_code]:font-mono"
      >
        这张图是<b>读出来的，不是排出来的</b>
        —— 连线来自每章最后那条「章节结束」，章节的先后也由它决定。要改走向，进对应章节改那条事件的
        <code>下一章</code
        >；改完这里会跟着变。
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  chapters,
  diagnosticsByChapter,
  edges,
  introChapter,
  openChapter,
  removeChapter,
  startPreview,
  store,
} from "../../../store/scriptEditor";

interface FlowNode {
  id: string;
  name?: string;
  eventCount: number;
  isIntro: boolean;
  isOrphan: boolean;
  errors: number;
  warns: number;
  /** 该章 chapter_end 的 end_type，用来标注分支类型 */
  endType: string;
}

interface FlowRow {
  key: string;
  nodes: FlowNode[];
  /** 从上一层进来的分支标签 */
  inboundLabels: string[];
}

const leaf = (id: string) => (id.includes("/") ? id.slice(id.lastIndexOf("/") + 1) : id);

const open = (id: string) => void openChapter(id);

/** 校验器已经算过可达性，直接用它的结论，避免前后端两套图算法 */
const orphanIds = computed(() => {
  const set = new Set<string>();
  for (const d of store.report?.diagnostics ?? []) {
    if (d.code === "graph.unreachable" && d.chapter) set.add(d.chapter);
  }
  return set;
});

/**
 * 按真实跳转关系分层：从开场章节沿 edges 广度优先。
 *
 * 早先这里是把章节按文件名字典序排一列，箭头表达的是「id 的字母顺序」而不是
 * 真实跳转 —— 看起来对但完全不对，是最容易骗到下一个维护者的地方。
 */
const rows = computed<FlowRow[]>(() => {
  const summaries = new Map(chapters.value.map((c) => [c.id, c]));
  const diag = diagnosticsByChapter.value;
  const edgeList = edges.value;

  const outgoing = new Map<string, { to: string; label: string; endType: string }[]>();
  for (const e of edgeList) {
    if (e.isEnd) continue;
    const list = outgoing.get(e.from) ?? [];
    list.push({ to: e.to, label: e.label ?? "", endType: e.endType });
    outgoing.set(e.from, list);
  }
  const endTypeOf = new Map<string, string>();
  for (const e of edgeList) endTypeOf.set(e.from, e.endType);

  const mk = (id: string): FlowNode => {
    const s = summaries.get(id);
    const d = diag[id] ?? { errors: 0, warns: 0, infos: 0 };
    const endType = endTypeOf.get(id) ?? "";
    return {
      id,
      name: s?.name,
      eventCount: s?.eventCount ?? 0,
      isIntro: id === introChapter.value,
      isOrphan: orphanIds.value.has(id),
      errors: d.errors,
      warns: d.warns,
      endType,
    };
  };

  const out: FlowRow[] = [];
  const seen = new Set<string>();
  let frontier = summaries.has(introChapter.value) ? [introChapter.value] : [];
  let inboundLabels: string[] = [];

  while (frontier.length) {
    const layer = frontier.filter((id) => !seen.has(id) && summaries.has(id));
    if (!layer.length) break;
    layer.forEach((id) => seen.add(id));
    out.push({
      key: layer.join("|"),
      nodes: layer.map(mk),
      inboundLabels: inboundLabels.filter(Boolean),
    });

    const nextIds: string[] = [];
    const nextLabels: string[] = [];
    for (const id of layer) {
      for (const e of outgoing.get(id) ?? []) {
        if (!nextIds.includes(e.to)) {
          nextIds.push(e.to);
          nextLabels.push(e.label);
        }
      }
    }
    frontier = nextIds;
    inboundLabels = nextLabels;
  }

  // 走不到的章节单独挂在末尾，不参与主链布局
  const unreached = chapters.value.filter((c) => !seen.has(c.id));
  if (unreached.length) {
    out.push({
      key: "__unreached",
      nodes: unreached.map((c) => mk(c.id)),
      inboundLabels: [],
    });
  }

  return out;
});
</script>

<style scoped>
/* 本项目全局 css 未定义 Tailwind 主题色 --color-brand（原版由 base.css 的 @theme 提供），
   连接线保留原版 bg-brand/55 类名，此处补等效底色，避免竖线与「下一章」隐形 */
.conn {
  background-color: rgba(121, 217, 255, 0.55);
}
p code {
  color: var(--accent-color);
}
</style>
