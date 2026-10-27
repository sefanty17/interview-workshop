import { reactive } from "vue";

/** 设置面板的 11 个标签页（原版 uiStore.currentSettingsTab 去掉 plugins，本项目扩展 knowledge） */
export type SettingsTab =
  | "character"
  | "adventure"
  | "text"
  | "knowledge"
  | "background"
  | "sound"
  | "history"
  | "achievement"
  | "save"
  | "advance"
  | "log";

/** 原版 useUIStore() 中设置面板相关字段的等价实现 */
export const settingsUi = reactive({
  showSettings: false,
  currentSettingsTab: "character" as SettingsTab,
  /** 情绪气泡音效音量（0-100）。原版 useUIStore.bubbleVolume，本项目暂无设置界面入口，
   *  先按原版缺省值 100 落一个最小字段，避免音量被硬编码。 */
  bubbleVolume: 100,
});

/** 打开设置面板，可指定落在哪个标签（原版默认落在角色页） */
export function openSettings(tab?: SettingsTab): void {
  if (tab) settingsUi.currentSettingsTab = tab;
  settingsUi.showSettings = true;
}

export function closeSettings(): void {
  settingsUi.showSettings = false;
}

export function setTab(tab: SettingsTab): void {
  settingsUi.currentSettingsTab = tab;
}
