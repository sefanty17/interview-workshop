<template>
  <PageShell title="创意工坊">
    <MenuItem size="large" title="剧本市集" icon="◇">
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap items-center gap-2">
          <button
            v-for="c in categories"
            :key="c"
            class="cursor-pointer rounded-full border px-3 py-1 text-xs font-bold transition-colors"
            :class="
              activeCat === c
                ? 'border-[#79d9ff] bg-[#79d9ff]/15 text-[#79d9ff]'
                : 'border-white/10 bg-white/[0.06] text-white/50 hover:bg-white/10 hover:text-white/80'
            "
            @click="activeCat = c"
          >
            {{ c }}
          </button>
        </div>

        <div class="flex items-center gap-1 rounded-full bg-white/5 p-1">
          <button
            class="cursor-pointer rounded-full px-3 py-1 text-xs transition-colors"
            :class="sort === 'hot' ? 'bg-white/10 font-bold text-white' : 'text-white/50 hover:text-white/80'"
            @click="sort = 'hot'"
          >
            最热
          </button>
          <button
            class="cursor-pointer rounded-full px-3 py-1 text-xs transition-colors"
            :class="sort === 'new' ? 'bg-white/10 font-bold text-white' : 'text-white/50 hover:text-white/80'"
            @click="sort = 'new'"
          >
            最新
          </button>
        </div>
      </div>

      <div v-if="filtered.length" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
        <article
          v-for="item in filtered"
          :key="item.id"
          class="flex flex-col rounded-xl border border-white/12 bg-white/[0.05] p-4 transition-all duration-300 hover:-translate-y-[3px] hover:border-[#79d9ff]/50"
        >
          <div class="flex items-start justify-between gap-2">
            <h4 class="m-0 min-w-0 text-base font-bold text-white">{{ item.title }}</h4>
            <span
              v-if="installed.includes(item.id)"
              class="shrink-0 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-bold text-emerald-300"
            >
              已装
            </span>
          </div>

          <p class="mt-2 line-clamp-2 text-xs leading-relaxed text-white/50">{{ item.desc }}</p>

          <div class="mt-3 flex flex-wrap gap-1.5">
            <span
              v-for="t in item.tags"
              :key="t"
              class="rounded-full border border-white/10 bg-white/[0.06] px-2 py-0.5 text-[10px] text-white/50"
            >
              {{ t }}
            </span>
          </div>

          <div class="mt-auto flex items-center justify-between gap-2 pt-4">
            <span class="min-w-0 truncate text-[11px] text-white/40">
              @{{ item.author }} · {{ item.uses }} 次 · ★ {{ item.score }}
            </span>
            <div class="flex shrink-0 gap-1.5">
              <button
                class="cursor-pointer rounded-full border border-[#79d9ff]/40 bg-[#79d9ff]/15 px-3 py-1 text-xs font-bold text-[#79d9ff] transition-colors hover:bg-[#79d9ff]/25"
                @click="openDetail(item)"
              >
                查看
              </button>
              <button
                class="cursor-pointer rounded-full border border-emerald-400/40 bg-emerald-500/15 px-3 py-1 text-xs font-bold text-emerald-300 transition-colors hover:bg-emerald-500/25"
                @click="install(item)"
              >
                安装
              </button>
            </div>
          </div>
        </article>
      </div>

      <p v-else class="py-16 text-center text-sm text-white/40">这个分类下还没有剧本，换个分类试试。</p>
    </MenuItem>

    <LcModal :show="!!detail" :title="detail?.title ?? ''" width="34rem" @close="detail = null">
      <template v-if="detail">
        <p class="text-sm leading-relaxed text-white/70">{{ longDesc(detail) }}</p>

        <div class="flex flex-wrap gap-1.5">
          <span
            v-for="t in detail.tags"
            :key="t"
            class="rounded-full border border-white/10 bg-white/[0.06] px-2 py-0.5 text-[10px] text-white/50"
          >
            {{ t }}
          </span>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div class="rounded-lg border border-white/10 bg-white/[0.04] p-3">
            <div class="text-[10px] tracking-widest text-white/40">步骤数</div>
            <div class="mt-1 text-lg font-bold text-[#79d9ff]">{{ stepCount(detail) }} 步</div>
          </div>
          <div class="rounded-lg border border-white/10 bg-white/[0.04] p-3">
            <div class="text-[10px] tracking-widest text-white/40">适用岗位</div>
            <div class="mt-1 text-lg font-bold text-white">{{ detail.tags[0] }}</div>
          </div>
        </div>

        <div class="text-xs text-white/40">作者 @{{ detail.author }} · {{ detail.uses }} 次使用 · ★ {{ detail.score }}</div>
      </template>

      <template #footer>
        <div class="flex justify-end gap-2">
          <button
            class="cursor-pointer rounded-full border border-white/15 bg-white/10 px-4 py-1.5 text-xs text-white/80 transition-colors hover:bg-white/20"
            @click="detail = null"
          >
            关闭
          </button>
          <button
            class="cursor-pointer rounded-full border border-emerald-400/40 bg-emerald-500/15 px-4 py-1.5 text-xs font-bold text-emerald-300 transition-colors hover:bg-emerald-500/25"
            @click="detail && install(detail)"
          >
            安装
          </button>
        </div>
      </template>
    </LcModal>
  </PageShell>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import PageShell from "../components/ui/PageShell.vue";
import MenuItem from "../components/settings/MenuItem.vue";
import LcModal from "../components/ui/LcModal.vue";
import { workshopItems } from "../data/mock";
import { notify } from "../store/appStore";

type WorkshopItem = (typeof workshopItems)[number];

const categories = ["全部", "Java", "前端", "压力面", "行为面"];
const activeCat = ref("全部");
const sort = ref<"hot" | "new">("hot");
const detail = ref<WorkshopItem | null>(null);
const installed = ref<string[]>([]);

/** 「行为面」分类在本数据集中对应的标签是「行为题」 */
const catAlias: Record<string, string> = { 行为面: "行为题" };

const filtered = computed(() => {
  const cat = activeCat.value;
  const tag = catAlias[cat] ?? cat;
  const list = workshopItems.filter((item) => cat === "全部" || item.tags.includes(tag));
  if (sort.value === "hot") return [...list].sort((a, b) => b.uses - a.uses);
  return [...list].reverse();
});

function stepCount(item: WorkshopItem): number {
  return 5 + (item.uses % 5);
}

function longDesc(item: WorkshopItem): string {
  return `${item.desc} 剧本按「开场 → 题目 → 追问 → 结束」编排，共 ${stepCount(item)} 个步骤，可直接在剧本编辑器中二次修改后开始面试。`;
}

function openDetail(item: WorkshopItem) {
  detail.value = item;
}

function install(item: WorkshopItem) {
  if (installed.value.includes(item.id)) {
    notify(`「${item.title}」已经安装过了`);
    return;
  }
  installed.value.push(item.id);
  notify(`已安装「${item.title}」`);
}
</script>
