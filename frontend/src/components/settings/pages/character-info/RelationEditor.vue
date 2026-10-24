<template>
  <div id="relation-block-role" class="space-y-3 rounded-xl border border-white/10 bg-black/20 p-4">
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0">
        <h3 class="text-sm font-bold text-white/80">关系</h3>
        <p class="mt-1 text-[11px] leading-relaxed text-white/40">
          这里填的是这个角色对各方的初始关系（例如「青梅竹马」「宿敌」）。游戏中的变化记录在本局存档里，不会改动角色卡。
        </p>
      </div>
      <button class="btn-sm shrink-0" :disabled="relationsSaving || relationsLoading" @click="saveRelations">
        {{ relationsSaving ? "加载中..." : "保存关系" }}
      </button>
    </div>
    <div v-if="relationsLoading" class="text-xs text-white/40">加载中...</div>

    <template v-else>
      <div v-for="(row, idx) in relationRows" :key="idx" class="flex items-center gap-2">
        <select v-model="row.target" class="glass-input w-44 shrink-0 cursor-pointer text-sm">
          <option
            v-for="option in relationOptionsFor(row)"
            :key="option.value"
            :value="option.value"
            :disabled="option.disabled"
            class="bg-[#16202c] text-white"
          >
            {{ option.group ? `${option.group} · ${option.label}` : option.label }}
          </option>
        </select>
        <input v-model="row.text" class="glass-input flex-1" />
        <button class="btn-sm" @click="removeRelationRow(idx)">×</button>
      </div>
      <button class="btn-sm" @click="addRelationRow">添加关系</button>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { interviewers } from "../../../../data/interviewers";
import {
  aiKey,
  getRoleRelations,
  listPlayerIdentities,
  meKey,
  saveRoleRelations,
  type PlayerIdentitySummary,
} from "../../../../api/playerIdentity";
import { notify } from "../../../../store/appStore";
import { alertDialog } from "../../../../store/dialogStore";
import {
  useRelationEditor,
  type RelationRow,
  type RelationTargetOption,
} from "../../../../composables/useRelationEditor";

const props = defineProps<{
  /** 角色 code（如 strict）；后端关系表就按这个键存 */
  interviewerCode: string;
}>();

const emit = defineEmits<{ (e: "saved"): void }>();

const relationTargets = ref<RelationTargetOption[]>([]);
const relationsLoading = ref(false);
const relationsSaving = ref(false);

const {
  rows: relationRows,
  syncRows,
  addRow,
  removeRow,
  fold,
  optionsFor,
} = useRelationEditor(relationTargets, (key) => `目标已不存在（${key}）`);

const relationOptionsFor = (row: RelationRow) => optionsFor(row);

let loadSeq = 0;

const loadRelations = async (interviewerCode: string) => {
  const seq = ++loadSeq;
  relationsLoading.value = true;
  try {
    const identities = await listPlayerIdentities().catch(() => [] as PlayerIdentitySummary[]);
    if (seq !== loadSeq) return;

    relationTargets.value = [
      ...interviewers
        .filter((c) => c.code !== interviewerCode)
        .map((c) => ({
          value: aiKey(c.code),
          label: c.name,
          group: "AI 角色",
        })),
      ...identities.map((i) => ({
        value: meKey(i.id),
        label: i.name,
        group: "我的身份",
      })),
    ];

    const saved = await getRoleRelations(interviewerCode);
    if (seq !== loadSeq) return;
    syncRows(saved);
  } catch (e) {
    if (seq !== loadSeq) return;
    console.error("读取角色关系失败:", e);
    void alertDialog("读取关系失败", e instanceof Error ? e.message : String(e));
    syncRows({});
  } finally {
    if (seq === loadSeq) relationsLoading.value = false;
  }
};

const addRelationRow = () => {
  // 目标默认留空（不预选角色）；保存时空目标的行走丢弃逻辑
  addRow("");
};

const removeRelationRow = (idx: number) => {
  removeRow(idx);
};

const saveRelations = async () => {
  relationsSaving.value = true;
  try {
    // 空目标 / 空文本直接丢弃（后端也会再过滤一次）
    await saveRoleRelations(props.interviewerCode, fold());
    notify("已保存角色关系");
    emit("saved");
  } catch (e) {
    void alertDialog("保存关系失败", e instanceof Error ? e.message : String(e));
  } finally {
    relationsSaving.value = false;
  }
};

// 换角色就重读这张卡的关系
watch(
  () => props.interviewerCode,
  (code) => {
    if (code) void loadRelations(code);
  },
  { immediate: true },
);
</script>
