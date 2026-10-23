/**
 * 角色卡状态中枢（规格 R1b §2.2）。
 *
 * 项目没有 Pinia：照 `store/scriptEditor.ts` 的写法，模块级 `reactive` 单例 +
 * 导出 action 函数 + 导出 computed。房间只读 `characterCards` / `cardForCode`，
 * 加载一律走 `ensureCharactersLoaded()`。
 */

import { computed, reactive } from "vue";
import { fetchCharacters, type CharacterCardSummary } from "../api/characters";

/**
 * 后端目录名 ↔ 前端 `interviewer.code` 的映射（对齐后端 CharacterCardService.FOLDER_TO_CODE）。
 *
 * 后端 folder 取自 LingChat 原版的 `characters/` 目录名（中文），前端 code 是英语短名；
 * 两侧各自独立演化（新 adding 一个角色要同时在这里登记），写死常量是这批的最小改动。
 */
const CODE_TO_FOLDER: Record<string, string> = {
  strict: "风雪",
  friendly: "诺一钦灵",
  pressure: "松子",
  expert: "DeepSeek",
};

export const store = reactive({
  /** 后端角色卡摘要；空 = 还没拿到（或加载失败） */
  cards: [] as CharacterCardSummary[],
  /** 已经成功过一次（决定 ensureCharactersLoaded 是否再发一次请求） */
  loaded: false,
  /** 正在加载（不用于渲染阻塞，只为了避免重复请求） */
  loading: false,
  /** 加载失败文案；加载失败不抛也不拦渲染，由调用方决定是否提示 */
  error: null as string | null,
});

// ============================================================
// getters
// ============================================================

export const characterCards = computed<CharacterCardSummary[]>(() => store.cards);

/** 按后端目录名取一张卡（folder 是后端口径的权威标识） */
export function characterByFolder(folder: string): CharacterCardSummary | undefined {
  return store.cards.find((c) => c.folder === folder);
}

/**
 * 按前端人设 code 取一张卡。
 *
 * 映射查不到时返回 undefined —— 调用方要负责回落到 `data/interviewers.ts` 的本地卡，
 * 这样后端没起 / 角色没登记时也照样能演出房间。
 */
export function cardForCode(code: string): CharacterCardSummary | undefined {
  const folder = CODE_TO_FOLDER[code];
  return folder ? characterByFolder(folder) : undefined;
}

// ============================================================
// actions
// ============================================================

/**
 * 幂等懒加载：已有数据、正在加载都直接返回。
 *
 * 失败只写 `store.error`、不抛：角色卡是**显示层**的数据源，缺了它房间应该照常演出
 * （回落本地静态卡），不能因为它挂了就整个房间转圈。
 */
export async function ensureCharactersLoaded(): Promise<void> {
  if (store.loaded || store.loading) return;
  store.loading = true;
  try {
    store.cards = await fetchCharacters();
    store.loaded = true;
    store.error = null;
  } catch (e) {
    store.error = e instanceof Error ? e.message : String(e);
  } finally {
    store.loading = false;
  }
}
