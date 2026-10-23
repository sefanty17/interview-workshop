<template>
  <div>
    <MenuItem size="large" title="剧本总览" icon="📜">
      <div class="space-y-1 text-sm text-white/80">
        <p>已装剧本 {{ store.scripts.length }} 个</p>
        <p>进行中 {{ store.session.active ? 1 : 0 }} 个</p>
      </div>
    </MenuItem>

    <MenuItem size="large" title="独立剧本" icon="🎭">
      <div class="space-y-2">
        <div
          v-for="s in store.scripts"
          :key="s.code"
          class="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-3"
        >
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm font-bold text-white">{{ s.script_name }}</div>
            <p class="mt-1 line-clamp-2 text-xs text-white/50">{{ s.description }}</p>
          </div>
          <button
            class="shrink-0 cursor-pointer rounded-full border border-cyan-400/40 bg-cyan-500/20 px-4 py-1 text-xs font-bold text-[#79d9ff] transition-all hover:bg-cyan-500/35"
            @click="startScript(s.script_name)"
          >
            开始
          </button>
        </div>
      </div>
    </MenuItem>

    <MenuItem size="large" title="剧本面试（测试中）" icon="🧪">
      <p class="mb-3 text-sm text-white/60">
        用剧本固定提问顺序与追问节奏，适合反复演练同一套流程。当前版本仅做入口占位。
      </p>
      <button class="btn-big" @click="notify('剧本面试将在后续版本接入')">进入剧本面试</button>
    </MenuItem>

    <MenuItem size="small" title="创意工坊" icon="🛠️">
      <button class="btn-big" @click="router.push('/workshop')">打开创意工坊</button>
    </MenuItem>

    <MenuItem size="small" title="创建自己的剧本" icon="✍️">
      <button class="btn-big" @click="router.push('/script-editor')">新建剧本</button>
    </MenuItem>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from "vue-router";
import MenuItem from "../MenuItem.vue";
import { notify, store } from "../../../store/appStore";

const router = useRouter();

function startScript(name: string) {
  notify(`已载入剧本：${name}`);
  router.push("/interview");
}
</script>
