<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-white/50">
      按服装管理情绪立绘。上传、替换和删除先更新草稿，点击底部保存更改后生效；取消编辑可放弃。支持 PNG、JPEG、WebP，单张最大 20 MB、边长最大 8192 像素。
    </p>
    <label class="flex items-center gap-3 text-sm text-white/70">
      服装
      <select
        v-model="costume"
        class="rounded-xl border border-white/10 bg-[#252535] px-3 py-2 text-white"
      >
        <option value="default">默认服装</option>
        <option v-for="name in costumeNames" :key="name" :value="name">{{ name }}</option>
      </select>
    </label>
    <p v-if="costume !== 'default'" class="text-xs leading-relaxed text-white/45">
      默认服装使用静态差分资源，其他服装的立绘需要由后端角色资源目录提供，当前只能查看缺哪些情绪。
    </p>
    <p v-if="error" role="alert" class="text-sm text-red-300">{{ error }}</p>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      <article
        v-for="slot in slots"
        :key="slot.emotion"
        class="space-y-2 rounded-xl border border-white/10 bg-white/5 p-3"
      >
        <div class="flex flex-wrap items-center justify-between gap-1 text-xs">
          <span class="text-white/80">{{ emotionLabel(slot.emotion) }}</span>
          <span :class="slot.path ? 'text-cyan-200/70' : 'text-amber-200/70'">{{
            slot.fallback ? '使用正常立绘' : slot.path ? '已配置' : '未配置'
          }}</span>
        </div>
        <div
          class="flex aspect-[3/4] items-center justify-center overflow-hidden rounded-lg bg-black/20"
        >
          <img
            v-if="slot.path"
            :src="slot.path"
            :alt="emotionLabel(slot.emotion)"
            class="h-full w-full object-contain"
          />
          <span v-else class="text-xs text-white/30">未配置</span>
        </div>
        <div class="flex gap-2 text-xs">
          <label
            class="flex-1 rounded-lg bg-[#5e72e4] px-2 py-2 text-center"
            :class="busy ? 'pointer-events-none opacity-50' : 'cursor-pointer hover:bg-[#4a5acf]'"
          >
            {{ slot.path && !slot.fallback ? '替换' : '上传' }}
            <input
              type="file"
              accept="image/png,image/jpeg,image/webp"
              class="sr-only"
              :disabled="busy"
              @change="upload(slot, $event)"
            />
          </label>
          <button
            :disabled="busy || !slot.path || slot.fallback"
            class="rounded-lg bg-red-500/15 px-2 py-2 text-red-200 hover:bg-red-500/30 disabled:opacity-30"
            @click="remove(slot)"
          >
            删除
          </button>
        </div>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { EMOTIONS, avatarUrl } from "../../../data/interviewers";
import { requireBackend } from "../../../utils/backendStub";
import { confirmDialog } from "../../../store/dialogStore";

const props = defineProps<{
  roleId: string;
  clothes: Array<{ name?: string }>;
  resourceClothes?: Array<{ title: string }>;
  initialCostume?: string;
}>();
const emit = defineEmits<{ changed: [] }>();
type Slot = { emotion: string; path: string; fallback: boolean };
const costume = ref(props.initialCostume || "default");
const costumeNames = computed(() => [
  ...new Set(
    [...props.clothes.map((c) => c.name), ...(props.resourceClothes ?? []).map((c) => c.title)]
      .map((name) => name?.trim())
      .filter((n): n is string => Boolean(n) && n !== "default" && n !== "默认"),
  ),
]);
const slots = computed<Slot[]>(() =>
  // 只有默认服装有静态差分文件；其余服装的资源要等后端角色目录到位才有
  EMOTIONS.map((emotion) => ({
    emotion,
    path: costume.value === "default" ? avatarUrl(props.roleId, emotion) : "",
    fallback: false,
  })),
);
const error = ref("");
const busy = ref(false);
const emotionLabel = (emotion: string) =>
  emotion === "伤心" ? "哭泣（伤心）" : emotion === "羞耻" ? "难为情（羞耻）" : emotion;

async function upload(slot: Slot, event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (file.size > 20 * 1024 * 1024) {
    error.value = "图片不能超过 20 MB";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await requireBackend(
      `写入「${emotionLabel(slot.emotion)}」的情绪立绘需要后端的角色资源目录读写，当前为纯前端演示（已选中 ${file.name}）。`,
    );
  } finally {
    busy.value = false;
  }
}

async function remove(slot: Slot) {
  if (
    !(await confirmDialog(
      "删除",
      `确定删除「${emotionLabel(slot.emotion)}」的立绘吗？该服装下此情绪的所有图片格式都会删除，操作立即生效。`,
    ))
  )
    return;
  busy.value = true;
  error.value = "";
  try {
    await requireBackend(
      `删除「${emotionLabel(slot.emotion)}」的立绘需要后端的角色资源目录删除接口，当前为纯前端演示。`,
    );
    emit("changed");
  } finally {
    busy.value = false;
  }
}

watch(
  () => props.roleId,
  () => {
    costume.value = props.initialCostume || "default";
    error.value = "";
  },
);
</script>
