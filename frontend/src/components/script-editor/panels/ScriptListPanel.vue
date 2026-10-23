<script setup lang="ts">
import { Button, Icon } from "../../base";
import { MenuPage, MenuItem } from "../../ui";
import { deleteScript, store, openScript } from "../../../store/scriptEditor";

// 原版的 border-brand / text-brand 由 Tailwind @theme 的 --color-brand 提供，
// 本项目 lingchat.css 已用同名变量指向强调色，类名与原版保持一致。

const emit = defineEmits<{ "new-script": [] }>();
</script>

<template>
  <MenuPage>
    <MenuItem title="剧本列表">
      <template #header>
        <Icon icon="package" :size="20" />
      </template>

      <p v-if="store.loading" class="py-8 text-center text-[0.85rem] text-white/45">加载中…</p>
      <p v-else-if="store.scripts.length === 0" class="py-8 text-center text-[0.85rem] text-white/45">
        还没有剧本，从设置页导入一个试试
      </p>

      <div
        v-for="s in store.scripts"
        :key="s.key"
        class="group mb-2 w-full cursor-pointer rounded-[10px] border border-white/10 bg-white/6 px-[13px] py-[11px] transition-all duration-200 hover:border-brand hover:bg-[rgba(121,217,255,0.08)]"
        @click="openScript(s.key)"
      >
        <div class="flex items-baseline gap-2">
          <span class="font-semibold text-white">{{ s.scriptName }}</span>
          <!-- isAdventure 本项目恒 false、loadedByEngine 恒 true，两个徽标按原版保留但不会渲染 -->
          <span
            v-if="s.isAdventure"
            class="rounded-full border border-brand bg-[rgba(121,217,255,0.12)] px-[7px] text-[0.62rem] text-brand"
            >冒险</span
          >
          <span
            v-if="!s.loadedByEngine"
            class="rounded-full border border-amber-300/35 bg-amber-300/12 px-[7px] text-[0.62rem] text-amber-300"
            >未加载</span
          >
          <span class="ml-auto text-xs text-white/40">{{ s.chapterCount }} 章</span>
          <button
            class="rounded px-[5px] text-[11px] leading-[1.4] text-white/25 opacity-0 transition-all duration-150 group-hover:opacity-100 hover:bg-red-400/15 hover:text-red-300"
            title="删除剧本"
            @click.stop="deleteScript(s.key, s.scriptName)"
          >
            ✕
          </button>
        </div>
        <p class="mt-1 text-xs text-white/50">{{ s.description || "（无简介）" }}</p>
        <p class="mt-1 font-mono text-[10px] text-white/25">{{ s.key }}</p>
      </div>

      <Button type="big" class="mt-4" @click="emit('new-script')">
        ＋ 新建剧本
      </Button>
    </MenuItem>
  </MenuPage>
</template>
