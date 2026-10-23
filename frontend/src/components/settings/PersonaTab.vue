<template>
  <div>
    <MenuItem size="large" title="面试官列表（切换面试官会开始全新面试）" icon="🐰">
      <div class="grid grid-cols-1 gap-5 md:grid-cols-2">
        <div
          v-for="p in interviewers"
          :key="p.code"
          class="group relative flex items-center rounded-2xl border border-white/20 bg-white/10 p-4 backdrop-blur-[20px] transition-all duration-300 hover:-translate-y-1 hover:border-white/40 hover:shadow-2xl hover:shadow-indigo-500/20"
        >
          <div class="flex w-28 shrink-0 flex-col items-center space-y-2 border-r border-white/10 pr-4 md:w-32">
            <div class="h-24 w-24 overflow-hidden rounded-full border-2 border-indigo-400/50 shadow-lg">
              <img :src="avatarUrl(p.code)" :alt="p.name" class="h-full w-full object-cover" />
            </div>
            <span class="mt-1 h-1 w-6 rounded-full bg-[#79d9ff]"></span>
            <h4 class="text-center text-base font-bold tracking-wide text-white drop-shadow-md">{{ p.name }}</h4>
          </div>
          <div class="flex h-full min-h-36 flex-1 flex-col justify-between pl-4">
            <div>
              <div class="mb-1 text-xl font-bold uppercase tracking-widest text-[#79d9ff] opacity-80">{{ p.displayRole }}</div>
              <div class="mb-2 text-sm font-medium tracking-widest text-[#79d9ff] opacity-60">{{ p.subtitle }}</div>
              <p class="line-clamp-3 text-base leading-relaxed text-gray-200/90 opacity-80">{{ p.cardInfo }}</p>
            </div>
            <div class="mt-4 flex items-center justify-end gap-2">
              <button class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1 text-xs font-semibold text-white transition-all hover:bg-white/20" @click="detail = p">详情</button>
              <button class="cursor-pointer rounded-full border px-5 py-1 text-xs font-bold shadow-lg transition-all"
                :class="store.settings.personaId === p.code ? 'border-emerald-400 bg-emerald-500/80 text-white shadow-emerald-500/20' : 'border-indigo-500 bg-indigo-600/80 text-white shadow-indigo-500/20 hover:bg-indigo-500'"
                @click="pick(p)">
                {{ store.settings.personaId === p.code ? "✓ 已选中" : "选择" }}
              </button>
            </div>
          </div>
        </div>

        <button class="group flex min-h-44 cursor-pointer flex-col items-center justify-center gap-4 rounded-2xl border-2 border-dashed border-white/20 bg-white/5 p-4 text-white/60 transition-all duration-300 hover:-translate-y-1 hover:border-[#79d9ff] hover:bg-white/10 hover:text-white" @click="notify('演示版暂未开放新建人设')">
          <span class="flex h-16 w-16 items-center justify-center rounded-full bg-white/5 text-4xl transition-colors group-hover:bg-white/10">＋</span>
          <span class="text-lg font-semibold">新建人设</span>
        </button>
      </div>
    </MenuItem>

    <MenuItem size="large" title="我的身份" icon="👤">
      <div class="rounded-2xl border border-white/20 bg-white/10 p-4 backdrop-blur-xl">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="text-base font-bold text-white">候选人 · 蔡智豪</span>
            <span class="rounded-full bg-white/10 px-2.5 py-0.5 text-xs text-white/70">应届毕业生 · 在读</span>
            <span class="rounded-full bg-[rgba(121,217,255,0.15)] px-2.5 py-0.5 text-xs text-[#79d9ff]">目标：{{ currentJob.name }}</span>
          </div>
          <div class="flex gap-2">
            <button class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white hover:bg-white/20" @click="notify('演示版暂未开放编辑')">编辑</button>
            <button class="cursor-pointer rounded-full border border-white/10 bg-white/10 px-4 py-1.5 text-xs font-semibold text-white hover:bg-white/20" @click="notify('演示版暂未开放删除')">删除</button>
          </div>
        </div>
        <p class="mt-2 text-sm leading-relaxed text-white/60">计算机专业在读，熟悉 Java / Vue / MySQL，做过多个课程项目。简历摘要会注入面试官的提问上下文（P1 接入）。</p>
      </div>
    </MenuItem>

    <MenuItem size="small" title="从压缩包导入人设" icon="📦">
      <button class="btn-big" @click="notify('演示版暂未开放导入')">选择压缩包…</button>
    </MenuItem>

    <MenuItem size="small" title="刷新人设列表" icon="🔄">
      <button class="btn-big" @click="notify('列表已刷新')">刷新列表</button>
    </MenuItem>

    <Teleport to="body">
      <Transition name="detail">
        <div
          v-if="detail"
          class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-md"
          @click.self="detail = null"
        >
          <div class="max-h-[85dvh] w-full max-w-4xl overflow-y-auto rounded-3xl border border-white/20 bg-slate-900/40 shadow-2xl backdrop-blur-2xl">
            <div class="flex items-start gap-5 border-b border-white/10 p-6">
              <div class="h-24 w-24 shrink-0 overflow-hidden rounded-full border-2 border-indigo-400/50 shadow-lg">
                <img :src="avatarUrl(detail.code)" :alt="detail.name" class="h-full w-full object-cover" />
              </div>
              <div class="flex-1">
                <div class="text-2xl font-bold text-white">{{ detail.name }}</div>
                <div class="mt-1 text-sm text-indigo-300">{{ detail.displayRole }} · {{ detail.subtitle }}</div>
              </div>
              <button class="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full border-none bg-transparent text-xl text-white/70 transition-colors hover:bg-red-500/20 hover:text-white" @click="detail = null">✕</button>
            </div>

            <div class="p-6">
              <div class="mb-4 flex items-center gap-2 font-bold text-white">
                <span class="h-4 w-1 rounded-full bg-orange-500"></span>基本信息
              </div>
              <div class="mb-6">
                <div class="mb-3 text-sm font-medium tracking-widest text-gray-200/90 opacity-80">姓名：{{ detail.name }}</div>
                <div class="mb-3 text-sm font-medium tracking-widest text-gray-200/90 opacity-80">定位：{{ detail.displayRole }}</div>
                <div class="mb-3 text-sm font-medium tracking-widest text-gray-200/90 opacity-80">卡片标题：{{ detail.cardTitle }}</div>
                <div class="mb-3 text-sm font-medium tracking-widest text-gray-200/90 opacity-80">副标题：{{ detail.subtitle }}</div>
              </div>

              <div class="mb-4 flex items-center gap-2 font-bold text-white">
                <span class="h-4 w-1 rounded-full bg-indigo-500"></span>提问风格
              </div>
              <div class="mb-6">
                <div class="rounded-r-md border-l-[3px] border-[#79d9ff] bg-white/5 p-3 text-white/70">{{ detail.cardInfo }}</div>
              </div>

              <div class="mb-4 flex items-center gap-2 font-bold text-white">
                <span class="h-4 w-1 rounded-full bg-[#79d9ff]"></span>开场白
              </div>
              <div class="mb-6">
                <p class="text-sm leading-relaxed text-gray-200/90 opacity-80">{{ detail.opening }}</p>
              </div>

              <div class="mb-4 flex items-center gap-2 font-bold text-white">
                <span class="h-4 w-1 rounded-full bg-emerald-500"></span>面试编排
                <span class="ml-1 rounded-full bg-white/10 px-2 py-0.5 text-[10px] font-normal tracking-normal text-white/50">只读</span>
              </div>
              <div class="rounded-xl border border-white/10 bg-white/5 p-4">
                <div class="grid grid-cols-2 gap-3 text-sm text-gray-200/90">
                  <div>岗位：<span class="font-bold text-white">{{ currentJob.name }}</span></div>
                  <div>题量：<span class="font-bold text-white">{{ store.settings.questionCount }}</span> 题</div>
                  <div>时长：<span class="font-bold text-white">{{ store.settings.timeLimit }}</span> 分钟</div>
                  <div>追问深度：<span class="font-bold text-white">{{ store.settings.followUpDepth }}</span> 层</div>
                </div>
                <div class="mt-4 flex justify-end">
                  <button
                    class="cursor-pointer rounded-full border border-emerald-400/40 bg-emerald-500/15 px-4 py-1 text-xs font-bold text-emerald-300 transition-colors hover:bg-emerald-500/25"
                    @click="goScript"
                  >
                    去编排
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import MenuItem from "./MenuItem.vue";
import { avatarUrl, interviewers, type Interviewer } from "../../data/interviewers";
import { setPersona, store, notify, currentJob } from "../../store/appStore";

const router = useRouter();
const detail = ref<Interviewer | null>(null);

function pick(p: Interviewer) {
  setPersona(p.code);
  notify(`已选择面试官：${p.name} · ${p.displayRole}`);
}

function goScript() {
  detail.value = null;
  router.push("/script-editor");
}
</script>

<style scoped>
.detail-enter-active,
.detail-leave-active {
  transition: all 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}
.detail-enter-from,
.detail-leave-to {
  opacity: 0;
  transform: scale(0.95) translateY(10px);
}
</style>
