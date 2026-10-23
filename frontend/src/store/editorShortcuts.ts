/**
 * 编辑器快捷键状态（规格 E6a §2）。
 *
 * 原版键位存在 `useSettingsStore().shortcuts`；本项目设置面板没有这个 store，
 * 等价实现落在这里：模块级 reactive 单例 + localStorage 持久化，坏数据回落默认。
 */

import { reactive, watch } from "vue";
import {
  DEFAULT_SHORTCUTS,
  sanitizeShortcuts,
  type ShortcutAction,
  type ShortcutBinding,
} from "../utils/shortcuts";

type Shortcuts = Record<ShortcutAction, ShortcutBinding>;

const LS_KEY = "script-editor-shortcuts";

/** localStorage 读 JSON，merge 进 DEFAULT_SHORTCUTS 后 sanitizeShortcuts，坏数据回落默认 */
function load(): Shortcuts {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (!raw) return { ...DEFAULT_SHORTCUTS };
    return sanitizeShortcuts(JSON.parse(raw) as Partial<Shortcuts>);
  } catch {
    return { ...DEFAULT_SHORTCUTS };
  }
}

export const editorShortcuts = reactive({
  shortcuts: load() as Shortcuts,
  /** 支持 "shortcuts"（整组）与 "shortcuts.<action>" 两种 path，ShortcutHelpPanel 要用 */
  update(path: string, value: unknown) {
    if (path === "shortcuts") {
      this.shortcuts = sanitizeShortcuts(value as Partial<Shortcuts>);
      return;
    }
    const action = path.startsWith("shortcuts.") ? path.slice("shortcuts.".length) : path;
    if (!(action in this.shortcuts)) return;
    this.shortcuts[action as ShortcutAction] = value as ShortcutBinding;
  },
});

watch(
  () => editorShortcuts.shortcuts,
  (v) => localStorage.setItem(LS_KEY, JSON.stringify(v)),
  { deep: true },
);
