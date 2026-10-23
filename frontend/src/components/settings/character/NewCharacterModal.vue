<template>
  <dialog
    ref="dialog"
    aria-labelledby="new-character-title"
    class="fixed m-auto w-full max-w-md bg-transparent p-4 backdrop:bg-black/60 backdrop:backdrop-blur-sm"
    @click.self="close"
    @cancel.prevent="close"
  >
    <form
      class="w-full max-w-md space-y-5 rounded-2xl border border-white/20 bg-slate-900 p-6 text-white shadow-2xl"
      @submit.prevent="submit"
    >
      <h2 id="new-character-title" class="text-xl font-bold">新建角色</h2>
      <p class="text-sm text-white/70">
        创建后将打开角色配置，可继续填写人设和上传素材。取消编辑只放弃后续修改，已创建的角色会保留，可稍后继续编辑或删除。
      </p>
      <label class="block space-y-2">
        <span>角色名称</span>
        <input
          ref="nameInput"
          v-model="name"
          required
          maxlength="100"
          class="w-full rounded-xl border border-white/20 bg-white/10 px-3 py-2"
        />
      </label>
      <label class="block space-y-2">
        <span>资源目录名</span>
        <input
          v-model="folder"
          required
          maxlength="80"
          class="w-full rounded-xl border border-white/20 bg-white/10 px-3 py-2"
        />
      </label>
      <p v-if="error" role="alert" class="text-sm text-rose-300">{{ error }}</p>
      <div class="flex justify-end gap-3">
        <button type="button" class="rounded-xl px-4 py-2 hover:bg-white/10" @click="close">
          取消
        </button>
        <button
          type="submit"
          :disabled="!name.trim() || !folder.trim()"
          class="rounded-xl bg-indigo-500 px-4 py-2 hover:bg-indigo-400 disabled:opacity-50"
        >
          创建并编辑
        </button>
      </div>
    </form>
  </dialog>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from "vue";

// 原版在这里调用 createCharacter() 落库并打开 SettingsCharacterInfo。
// 本项目角色数据是静态的 data/interviewers.ts，本批表单不落库：只做结构，提交后上层给提示
const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ close: []; created: [name: string] }>();

const name = ref("");
const folder = ref("");
const error = ref("");
const nameInput = ref<HTMLInputElement>();
const dialog = ref<HTMLDialogElement>();

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) {
      dialog.value?.close();
      return;
    }
    name.value = "";
    folder.value = `character-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
    error.value = "";
    await nextTick();
    dialog.value?.showModal();
    nameInput.value?.focus();
  },
);

function close() {
  emit("close");
}

function submit() {
  if (!name.value.trim() || !folder.value.trim()) return;
  emit("created", name.value.trim());
  emit("close");
}
</script>
