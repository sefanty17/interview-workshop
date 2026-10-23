/**
 * 面试印象（原好感度）状态：六维正值 + 六维负面 + 变化历史。
 * 初始值来自 data/mock.ts 的 affection / affectionHistory，
 * 变化通过 applyAffectionChange 记录，驱动 AffectionChangeFloat / AffectionPanel。
 */
import { computed, reactive } from "vue";
import {
  affection as initialAffection,
  affectionHistory as seedHistory,
  type AffectionHistoryEntry,
} from "../data/mock";

export interface AffectionVector {
  fondness: number;
  trust: number;
  intimacy: number;
  rapport: number;
  interest: number;
  longing: number;
}

export interface NegativeVector {
  anger: number;
  hurt: number;
  disappointment: number;
  indifference: number;
  jealousy: number;
  estrangement: number;
}

export interface AffectionChangeRecord {
  roleId: number;
  deltas: Partial<AffectionVector>;
  negativeDeltas: Partial<NegativeVector>;
  reason: string;
}

export const AFFECTION_ROLE_ID = 1;

/** 标量初值摊到六维：主维 fondness 承担与均值的差，其余围绕均值微调 */
function seedDims(avg: number): AffectionVector {
  const base: AffectionVector = {
    fondness: avg + 6,
    trust: avg + 2,
    intimacy: avg - 4,
    rapport: avg + 3,
    interest: avg - 2,
    longing: avg - 5,
  };
  const sum = Object.values(base).reduce((a, b) => a + b, 0);
  base.fondness += avg * 6 - sum;
  return base;
}

export const affectionStore = reactive({
  roleId: AFFECTION_ROLE_ID,
  dims: seedDims(initialAffection) as AffectionVector,
  negDims: {
    anger: 6,
    hurt: 3,
    disappointment: 14,
    indifference: 2,
    jealousy: 0,
    estrangement: 4,
  } as NegativeVector,
  history: [...seedHistory] as AffectionHistoryEntry[],
  lastChange: null as AffectionChangeRecord | null,
});

export const affectionAverage = computed(() =>
  Math.round(
    (affectionStore.dims.fondness +
      affectionStore.dims.trust +
      affectionStore.dims.intimacy +
      affectionStore.dims.rapport +
      affectionStore.dims.interest +
      affectionStore.dims.longing) /
      6,
  ),
);

export const negativePeak = computed(() => Math.max(...Object.values(affectionStore.negDims)));

function nowTime(): string {
  const d = new Date();
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** 记录一次变化：更新六维、写入 lastChange（驱动飘出标签）并追加历史 */
export function applyAffectionChange(
  deltas: Partial<AffectionVector>,
  negativeDeltas: Partial<NegativeVector>,
  reason: string,
): void {
  const before = affectionAverage.value;
  for (const [k, v] of Object.entries(deltas) as [keyof AffectionVector, number][]) {
    affectionStore.dims[k] += v ?? 0;
  }
  for (const [k, v] of Object.entries(negativeDeltas) as [keyof NegativeVector, number][]) {
    affectionStore.negDims[k] += v ?? 0;
  }
  affectionStore.lastChange = { roleId: AFFECTION_ROLE_ID, deltas, negativeDeltas, reason };
  affectionStore.history.push({
    time: nowTime(),
    delta: affectionAverage.value - before,
    value: affectionAverage.value,
    reason,
  });
}

/** 标量式修改：delta 记到主维 fondness 上（可手动改值测试飘出标签与爱心液体） */
export function setAffection(delta: number, reason = "面试表现评估"): void {
  applyAffectionChange({ fondness: delta }, {}, reason);
}

// 手动测试入口：浏览器控制台 __setAffection(5) / __setNegative("anger", 30)
declare global {
  interface Window {
    __setAffection?: (delta: number, reason?: string) => void;
    __setNegative?: (key: keyof NegativeVector, delta: number, reason?: string) => void;
  }
}

if (typeof window !== "undefined") {
  window.__setAffection = setAffection;
  window.__setNegative = (key, delta, reason = "情绪波动") =>
    applyAffectionChange({}, { [key]: delta }, reason);
}
